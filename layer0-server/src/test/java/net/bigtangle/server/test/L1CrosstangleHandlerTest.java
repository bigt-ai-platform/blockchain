package net.bigtangle.server.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import net.bigtangle.bridge.BridgeConfiguration;
import net.bigtangle.bridge.L1CrosstangleHandler;
import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.service.base.handler.SolidityContext;
import net.bigtangle.utils.Json;
import net.bigtangle.wallet.FreeStandingTransactionOutput;

/**
 * Consensus tests for the Layer-1 CROSSTANGLE handler (N1): L1 chains must
 * reject unauthenticated zero-input mints while accepting authenticated bridge
 * issuance and enforcing per-token conservation on value-moving txs.
 */
public class L1CrosstangleHandlerTest extends AbstractIntegrationTest {

    @Autowired
    private BridgeConfiguration bridgeConfiguration;

    private PQKey issuanceKey;
    private L1CrosstangleHandler handler;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);

        // Dedicated issuance key pair (R4): issuance is signed and verified with
        // this key, never the vault key.
        byte[] issuanceSeed = new byte[32];
        new java.security.SecureRandom().nextBytes(issuanceSeed);
        issuanceKey = PQKey.fromMLDSA(issuanceSeed);
        bridgeConfiguration.setIssuancePubKeyHex(Utils.HEX.encode(issuanceKey.getPublicKeyBytes()));
        bridgeConfiguration.setIssuancePriKeyHex(Utils.HEX.encode(issuanceSeed));
        handler = new L1CrosstangleHandler(bridgeConfiguration, networkParameters);
    }

    private Address addressOf(PQKey key) {
        return Address.fromHash160(networkParameters, key.getPubKeyHash());
    }

    private SolidityState check(Block block) {
        return handler.checkFull(SolidityContext.builder().block(block).store(store).throwExceptions(true).build());
    }

    private Block crosstangleBlock(Transaction tx) {
        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        Block b = Block.createBlock(networkParameters, genesis, genesis);
        b.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);
        b.addTransaction(tx);
        return b;
    }

    private Transaction signedIssuance(PQKey signKey, String chainId) throws Exception {
        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName(L1CrosstangleHandler.ISSUE_WRAPPED_TOKEN_DATA_CLASS);
        tx.setData(Json.jsonmapper().writeValueAsBytes(java.util.Map.of(
                "chainId", chainId,
                "lockBlockHash", "aa",
                "lockIndex", 0)));
        tx.addOutput(Coin.valueOf(1000, NetworkParameters.BIGTANGLE_TOKENID), addressOf(PQKey.createNew()));
        tx.setDataSignature(signKey.sign(tx.getHash()).serialize());
        return tx;
    }

    @Test
    public void testRejectsUnauthenticatedZeroInputMint() throws Exception {
        // The N1 exploit: a zero-input tx minting value with no auth.
        Transaction tx = new Transaction(networkParameters);
        tx.addOutput(Coin.valueOf(1_000_000, NetworkParameters.BIGTANGLE_TOKENID), addressOf(PQKey.createNew()));
        assertTrue(check(crosstangleBlock(tx)).isFailState(),
                "an unauthenticated zero-input CROSSTANGLE mint must be rejected");
    }

    @Test
    public void testRejectsZeroInputMintWithoutSignature() throws Exception {
        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName(L1CrosstangleHandler.ISSUE_WRAPPED_TOKEN_DATA_CLASS);
        tx.setData(Json.jsonmapper().writeValueAsBytes(java.util.Map.of("chainId", networkParameters.getChainId())));
        tx.addOutput(Coin.valueOf(1000, NetworkParameters.BIGTANGLE_TOKENID), addressOf(PQKey.createNew()));
        // no dataSignature
        assertTrue(check(crosstangleBlock(tx)).isFailState(),
                "an unsigned issuance must be rejected");
    }

    @Test
    public void testAcceptsAuthenticatedIssuance() throws Exception {
        Transaction tx = signedIssuance(issuanceKey, networkParameters.getChainId());
        SolidityState state = check(crosstangleBlock(tx));
        assertEquals(SolidityState.State.Success, state.getState(),
                "an issuance-key-signed issuance for this chain must be accepted");
    }

    @Test
    public void testRejectsIssuanceSignedByWrongKey() throws Exception {
        // signed by a DIFFERENT key, not the chain's issuance key
        Transaction tx = signedIssuance(PQKey.createNew(), networkParameters.getChainId());
        assertTrue(check(crosstangleBlock(tx)).isFailState(),
                "an issuance signed by a non-issuance key must be rejected");
    }

    @Test
    public void testRejectsIssuanceForOtherChain() throws Exception {
        Transaction tx = signedIssuance(issuanceKey, "some-other-chain");
        assertTrue(check(crosstangleBlock(tx)).isFailState(),
                "an issuance declaring a different destination chain must be rejected");
    }

    @Test
    public void testAcceptsDataOnlyCrosstangle() throws Exception {
        // A data-only message (e.g. a locally-created anchor) has no outputs and
        // is not a mint, so it passes.
        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName("LayerAnchor");
        tx.setData("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        SolidityState state = check(crosstangleBlock(tx));
        assertEquals(SolidityState.State.Success, state.getState(),
                "a data-only CROSSTANGLE message must be accepted");
    }

    @Test
    public void testEnforcesPerTokenConservation() throws Exception {
        // A value-moving CROSSTANGLE tx that spends BIG but mints another token
        // must be rejected (per-token conservation).
        List<FreeStandingTransactionOutput> candidates = wallet.calculateAllSpendCandidates(null, false);
        FreeStandingTransactionOutput big = null;
        for (FreeStandingTransactionOutput co : candidates) {
            if (java.util.Arrays.equals(NetworkParameters.BIGTANGLE_TOKENID, co.getUTXO().getTokenidBuf())) {
                big = co;
                break;
            }
        }
        assertTrue(big != null, "test wallet must hold a BIG UTXO");

        Address to = addressOf(PQKey.createNew());
        // Input: BIG. Outputs: BIG to one address + value of a DIFFERENT token
        // id -> valueOut has a token with no matching input.
        Transaction mismatched = new Transaction(networkParameters);
        mismatched.addInput(big.getUTXO().getBlockHash(), big);
        mismatched.addOutput(big.getValue(), to);
        mismatched.addOutput(Coin.valueOf(1, PQKey.createNew().getPubKey()), to);
        wallet.signTransaction(mismatched, null);
        assertTrue(check(crosstangleBlock(mismatched)).isFailState(),
                "a tx that converts BIG into another token must be rejected");

        // Same input, outputs that conserve exactly per token -> accepted.
        Transaction conserved = new Transaction(networkParameters);
        conserved.addInput(big.getUTXO().getBlockHash(), big);
        conserved.addOutput(big.getValue(), to);
        wallet.signTransaction(conserved, null);
        SolidityState state = check(crosstangleBlock(conserved));
        assertEquals(SolidityState.State.Success, state.getState(),
                "a value-conserving CROSSTANGLE tx must be accepted");
    }

    @Test
    public void testRejectsIssuanceWhenKeyNotConfigured() throws Exception {
        bridgeConfiguration.setIssuancePubKeyHex(null);
        Transaction tx = signedIssuance(issuanceKey, networkParameters.getChainId());
        assertTrue(check(crosstangleBlock(tx)).isFailState(),
                "issuance must be rejected when the chain has no configured issuance key");
    }

    @Test
    public void testReplayIssuanceRejectedAtConfirmation() throws Exception {
        // R3: the same signed issuance (byte-identical) wrapped in a SECOND
        // block still validates in checkFull, so it must be vetoed at
        // CONFIRMATION via the chain-derived issued-lock table.
        Transaction issuance = signedIssuance(issuanceKey, networkParameters.getChainId());
        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);

        // Two different blocks carrying the same issuance tx.
        Block dummy = Block.createBlock(networkParameters, genesis, genesis);
        Block first = Block.createBlock(networkParameters, dummy, genesis);
        first.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);
        first.addTransaction(issuance);
        Block replay = Block.createBlock(networkParameters, genesis, genesis);
        replay.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);
        replay.addTransaction(issuance);

        // The first block confirms: the lock is recorded chain-derived.
        SolidityContext confirmCtx = SolidityContext.builder().block(first).store(store).confirmation(true)
                .blockHash(first.getHash()).base(null).build();
        handler.confirm(confirmCtx);

        // The replay block must be vetoed before its outputs confirm.
        SolidityContext replayCtx = SolidityContext.builder().block(replay).store(store).confirmation(true)
                .blockHash(replay.getHash()).base(null).build();
        assertFalse(handler.checkPreConfirm(replayCtx),
                "a second block minting the same L0 lock must be rejected at confirmation (R3)");

        // Unconfirming the first block rolls the lock back so it can be reused.
        handler.confirm(SolidityContext.builder().block(first).store(store).confirmation(false)
                .blockHash(first.getHash()).base(null).build());
        assertTrue(handler.checkPreConfirm(replayCtx),
                "after the original issuance unconfirms (reorg), the lock is free again");
    }
}
