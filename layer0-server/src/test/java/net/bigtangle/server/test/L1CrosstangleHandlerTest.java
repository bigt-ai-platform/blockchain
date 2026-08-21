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
        return signedIssuance(signKey, chainId, addressOf(PQKey.createNew()), 1000);
    }

    /**
     * Builds a LOCK-BACKED issuance: the data declares the exact L0 vault lock
     * (amount, token, beneficiary) that the single output claims to back, and
     * the output matches it 1:1. This is the shape the consensus rule
     * ({@code L1CrosstangleHandler.validateIssuance}) requires.
     */
    private Transaction signedIssuance(PQKey signKey, String chainId, Address beneficiary, long amount)
            throws Exception {
        return buildIssuance(signKey, chainId, amount, Utils.HEX.encode(NetworkParameters.BIGTANGLE_TOKENID),
                beneficiary.toBase58(), Coin.valueOf(amount, NetworkParameters.BIGTANGLE_TOKENID), beneficiary);
    }

    /** Flexible issuance builder so attack tests can diverge the declared lock from the mint. */
    private Transaction buildIssuance(PQKey signKey, String chainId, long lockAmount, String lockTokenHex,
            String lockBeneficiary, Coin mintAmount, Address mintAddress) throws Exception {
        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName(L1CrosstangleHandler.ISSUE_WRAPPED_TOKEN_DATA_CLASS);
        tx.setData(Json.jsonmapper().writeValueAsBytes(java.util.Map.of(
                "chainId", chainId,
                "lockBlockHash", "aa",
                "lockIndex", 0,
                L1CrosstangleHandler.LOCK_AMOUNT_KEY, lockAmount,
                L1CrosstangleHandler.LOCK_TOKEN_ID_KEY, lockTokenHex,
                L1CrosstangleHandler.LOCK_BENEFICIARY_KEY, lockBeneficiary)));
        tx.addOutput(mintAmount, mintAddress);
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

    /**
     * ATTACK: can an L1 chain mint NEW (wrapped) BIG that is not backed by any
     * real L0 vault lock, then burn it to withdraw real BIG from L0?
     *
     * <p>Today the mint output is bound 1:1 to the lock it declares
     * ({@code L1CrosstangleHandler.validateIssuance}): a wrapped mint must carry
     * the lock's amount, token id and beneficiary, and the single output must
     * match them exactly. So the attack variants below are all REJECTED by L1
     * consensus:
     * <ul>
     * <li>minting wrapped BIG without declaring any backing lock,</li>
     * <li>declaring a lock of 1000 but minting 1,000,000,000,</li>
     * <li>declaring one token but minting another,</li>
     * <li>declaring one beneficiary but paying another,</li>
     * <li>minting to several outputs.</li>
     * </ul>
     *
     * <p>Residual (documented in {@link #testSelfConsistentFabricatedLockAccepted}):
     * L1 consensus cannot verify that a declared lock EXISTS on L0 — that check
     * lives in the honest polling path ({@code processPegInFromL0}), so a fully
     * compromised issuance key can still declare a self-consistent fabricated
     * lock. Closing that requires an L0→L1 lock proof channel.
     */
    @Test
    public void testRejectsUnbackedWrappedBIGMint() throws Exception {
        // Attack: mint 1,000,000,000 wrapped BIG (the REAL BIG id) declaring NO
        // backing lock (no lockAmount/lockTokenId/lockBeneficiary in the data).
        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName(L1CrosstangleHandler.ISSUE_WRAPPED_TOKEN_DATA_CLASS);
        tx.setData(Json.jsonmapper().writeValueAsBytes(java.util.Map.of(
                "chainId", networkParameters.getChainId(),
                "lockBlockHash", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                "lockIndex", 999L)));
        tx.addOutput(Coin.valueOf(1_000_000_000L, NetworkParameters.BIGTANGLE_TOKENID),
                addressOf(PQKey.createNew()));
        tx.setDataSignature(issuanceKey.sign(tx.getHash()).serialize());

        assertTrue(check(crosstangleBlock(tx)).isFailState(),
                "an issuance that does not declare a backing L0 lock must be rejected");
    }

    @Test
    public void testRejectsIssuanceMismatchingLockAmount() throws Exception {
        Address beneficiary = addressOf(PQKey.createNew());
        // Declare a lock worth 1000, but mint 1,000,000,000 wrapped BIG.
        Transaction tx = buildIssuance(issuanceKey, networkParameters.getChainId(), 1000L,
                Utils.HEX.encode(NetworkParameters.BIGTANGLE_TOKENID), beneficiary.toBase58(),
                Coin.valueOf(1_000_000_000L, NetworkParameters.BIGTANGLE_TOKENID), beneficiary);
        assertTrue(check(crosstangleBlock(tx)).isFailState(),
                "an issuance minting more than its declared lock must be rejected");
    }

    @Test
    public void testRejectsIssuanceMismatchingLockToken() throws Exception {
        Address beneficiary = addressOf(PQKey.createNew());
        // Declare a foreign token as the backing lock, but mint BIG.
        Transaction tx = buildIssuance(issuanceKey, networkParameters.getChainId(), 1000L,
                Utils.HEX.encode(PQKey.createNew().getPubKey()), beneficiary.toBase58(),
                Coin.valueOf(1000, NetworkParameters.BIGTANGLE_TOKENID), beneficiary);
        assertTrue(check(crosstangleBlock(tx)).isFailState(),
                "an issuance minting a different token than its declared lock must be rejected");
    }

    @Test
    public void testRejectsIssuanceMismatchingBeneficiary() throws Exception {
        Address declared = addressOf(PQKey.createNew());
        // Declare lock beneficiary A, but pay the mint to B.
        Transaction tx = buildIssuance(issuanceKey, networkParameters.getChainId(), 1000L,
                Utils.HEX.encode(NetworkParameters.BIGTANGLE_TOKENID), declared.toBase58(),
                Coin.valueOf(1000, NetworkParameters.BIGTANGLE_TOKENID), addressOf(PQKey.createNew()));
        assertTrue(check(crosstangleBlock(tx)).isFailState(),
                "an issuance minting to someone other than the declared lock beneficiary must be rejected");
    }

    @Test
    public void testRejectsIssuanceWithMultipleOutputs() throws Exception {
        Address beneficiary = addressOf(PQKey.createNew());
        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName(L1CrosstangleHandler.ISSUE_WRAPPED_TOKEN_DATA_CLASS);
        tx.setData(Json.jsonmapper().writeValueAsBytes(java.util.Map.of(
                "chainId", networkParameters.getChainId(),
                "lockBlockHash", "aa",
                "lockIndex", 0,
                L1CrosstangleHandler.LOCK_AMOUNT_KEY, 2000L,
                L1CrosstangleHandler.LOCK_TOKEN_ID_KEY,
                        Utils.HEX.encode(NetworkParameters.BIGTANGLE_TOKENID),
                L1CrosstangleHandler.LOCK_BENEFICIARY_KEY, beneficiary.toBase58())));
        tx.addOutput(Coin.valueOf(1000, NetworkParameters.BIGTANGLE_TOKENID), beneficiary);
        tx.addOutput(Coin.valueOf(1000, NetworkParameters.BIGTANGLE_TOKENID), addressOf(PQKey.createNew()));
        tx.setDataSignature(issuanceKey.sign(tx.getHash()).serialize());

        assertTrue(check(crosstangleBlock(tx)).isFailState(),
                "an issuance must have exactly one lock-backed output");
    }

    /**
     * Residual trust gap (documented, not a bug): a SELF-CONSISTENT issuance that
     * declares a fabricated lock (one that does not exist on L0) still passes L1
     * consensus, because L1 has no L0→L1 lock proof channel — the existence check
     * lives in the honest polling path ({@code processPegInFromL0}). Only a
     * compromised issuance-key holder could produce this; normal L1 token
     * creation derives ids from pubkeys, and the L0 peg-out still requires the
     * burn's token/amount to match a real, unspent vault.
     */
    @Test
    public void testSelfConsistentFabricatedLockAccepted() throws Exception {
        // Lock reference "aa:0" never existed on L0, but the mint is internally
        // consistent (amount/token/beneficiary match), so checkFull accepts it.
        Transaction tx = signedIssuance(issuanceKey, networkParameters.getChainId());
        assertEquals(SolidityState.State.Success, check(crosstangleBlock(tx)).getState(),
                "self-consistent issuance is accepted (L1 cannot verify L0 lock existence)");
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
