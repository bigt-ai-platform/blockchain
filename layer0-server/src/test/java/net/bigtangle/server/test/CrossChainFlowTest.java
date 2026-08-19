package net.bigtangle.server.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import net.bigtangle.bridge.AnchorConfiguration;
import net.bigtangle.bridge.BridgeConfiguration;
import net.bigtangle.bridge.BridgeService;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.data.VaultRecord;
import net.bigtangle.wallet.Wallet;

public class CrossChainFlowTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CrossChainFlowTest.class);

    @Autowired
    protected ScheduleConfiguration scheduleConfiguration;

    @Autowired(required = false)
    protected BridgeService bridgeService;

    @Autowired
    protected BridgeConfiguration bridgeConfiguration;

    @Autowired
    protected AnchorConfiguration anchorConfiguration;

    private PQKey bobKey;
    private PQKey vaultKey;
    private List<Block> addedBlocks;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        scheduleConfiguration.setInitSync(false);
        super.setUp();
        bobKey = PQKey.createNew();
        vaultKey = PQKey.createNew();
        // Preserve the old context-level bridge.active/anchor.active toggles:
        // this test exercises the peg/vault flow end to end.
        bridgeConfiguration.setActive(true);
        bridgeConfiguration.setVaultPubKeyHex(Utils.HEX.encode(vaultKey.getPublicKeyBytes()));
        anchorConfiguration.setActive(true);
    }

    @Test
    public void testCrossChainBigFlow() throws Exception {
        log.info("=== PHASE 1: L0 BIG payment to bob ===");
        payBigTo(bobKey, BigInteger.valueOf(100000), addedBlocks);
        log.info("Paid 100000 BIG to bob");

        log.info("=== PHASE 2: Peg-in L0 -> L1 ===");
        Wallet bobWallet = Wallet.fromKeys(networkParameters, bobKey, contextRoot);
        List<UTXO> bobUtxos = bobWallet.calculateAllSpendCandidatesUTXO(null, false);
        assertTrue(bobUtxos.size() > 0, "Bob must have UTXOs");
        UTXO bigUtxo = bobUtxos.get(0);
        String l1bobAddress = bobKey.toAddress(networkParameters).toHex();

        Block proto = cacheBlockPrototypeService.getBlockPrototype(store);
        Block pegBlock = Block.createBlock(networkParameters,
                store.get(proto.getPrevBlockHash()),
                store.get(proto.getPrevBranchBlockHash()));
        pegBlock.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);
        Transaction pegTx = new Transaction(networkParameters);
        pegTx.setToAddressInSubtangle(bobKey.toAddress(networkParameters).hash());
        pegTx.addOutput(bigUtxo.getValue(), vaultKey);
        pegBlock.addTransaction(pegTx);
        store.put(pegBlock);
        blockGraph.updateChain(false);
        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);

        VaultRecord vaultRecord = new VaultRecord(networkParameters.getChainId(),
                pegBlock.getHash(), 0, bigUtxo.getValue().getValue().longValue(),
                Utils.HEX.encode(bigUtxo.getValue().getTokenid()),
                l1bobAddress, false);
        store.saveVaultUTXO(vaultRecord);
        log.info("Peg-in OK: {} BIG locked in vault", bigUtxo.getValue());

        List<VaultRecord> vaultRecords = store.getVaultUTXOsByChainId(
                networkParameters.getChainId(), false);
        assertEquals(1, vaultRecords.size());

        log.info("=== PHASE 3: L1 simulated token issuance ===");
        long bridged = vaultRecords.get(0).getAmount();
        Block l1Block = Block.createBlock(networkParameters,
                store.get(proto.getPrevBlockHash()),
                store.get(proto.getPrevBranchBlockHash()));
        l1Block.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);
        Transaction tx = new Transaction(networkParameters);
        tx.addOutput(new Coin(BigInteger.valueOf(bridged), NetworkParameters.BIGTANGLE_TOKENID), bobKey);
        l1Block.addTransaction(tx);
        store.put(l1Block);
        blockGraph.updateChain(false);
        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);
        log.info("L1 wrapped BIG issued: {}", bridged);

        log.info("=== PHASE 4: Peg-out L1 -> L0 ===");
        for (VaultRecord v : vaultRecords) {
            store.markVaultUTXOSpent(v.getChainId(), v.getUtxoBlockHash(), v.getUtxoIndex());
        }
        List<VaultRecord> spent = store.getVaultUTXOsByChainId(networkParameters.getChainId(), true);
        assertTrue(spent.size() > 0, "Vault must be spent");
        log.info("Peg-out OK: vault spent");

        log.info("=== ALL PHASES PASSED ===");
    }
}
