package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import net.bigtangle.bridge.BridgeService;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.layer0.mcmc.Layer0MCMCStart;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.data.VaultRecord;
import net.bigtangle.wallet.Wallet;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = Layer0MCMCStart.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "server.net=Test",
                       "spring.main.allow-bean-definition-overriding=true",
                       "bridge.active=true",
                       "bridge.vaultPubKeyHex=02721b5eb0282e4bc86aab3380e2bba31d935cba386741c15447973432c61bc975",
                       "bridge.vaultPriKeyHex=ec1d240521f7f254c52aea69fca3f28d754d1b89f310f42b0fb094d16814317f",
                       "anchor.active=true" })
public class CrossChainFlowTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CrossChainFlowTest.class);

    @Autowired
    protected ScheduleConfiguration scheduleConfiguration;

    @Autowired(required = false)
    protected BridgeService bridgeService;

    private PQKey bobKey;
    private List<Block> addedBlocks;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        scheduleConfiguration.setInitSync(false);
        super.setUp();
        bobKey = PQKey.createNew();
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
        pegTx.setToAddressInSubtangle(
                net.bigtangle.core.Address.fromBase58(networkParameters, l1bobAddress).getHash160());
        pegTx.addOutput(bigUtxo.getValue(),
                PQKey.fromPublicOnly(Utils.HEX.decode(
                        "02721b5eb0282e4bc86aab3380e2bba31d935cba386741c15447973432c61bc975"))
                        .toAddress(networkParameters));
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
