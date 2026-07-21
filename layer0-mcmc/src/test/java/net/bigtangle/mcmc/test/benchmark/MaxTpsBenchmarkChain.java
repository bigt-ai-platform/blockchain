package net.bigtangle.mcmc.test.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import net.bigtangle.core.Block;
import net.bigtangle.core.ECKey;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.layer0.mcmc.Layer0MCMCStart;
import net.bigtangle.mcmc.test.AbstractIntegrationTest;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = Layer0MCMCStart.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "server.net=Test",
                       "spring.main.allow-bean-definition-overriding=true",
                       "spring.datasource.hikari.maximum-pool-size=200" })
public class MaxTpsBenchmarkChain extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MaxTpsBenchmarkChain.class);

    @Autowired
    protected ScheduleConfiguration scheduleConfiguration;

    private String genesisPriv = "ec1d240521f7f254c52aea69fca3f28d754d1b89f310f42b0fb094d16814317f";

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        scheduleConfiguration.setInitSync(false);
        super.setUp();
    }

    @Test
    public void testChainMcmc() throws Exception {
        // Configurable: number of blocks and transactions per block
        int numBlocks = Integer.parseInt(System.getProperty("chain.blocks", "100"));
        int txPerBlock = Integer.parseInt(System.getProperty("chain.txPerBlock", "10"));
        int totalTx = numBlocks * txPerBlock;

        log.info("==============================================");
        log.info("  Chain MCMC Benchmark");
        log.info("==============================================");
        log.info("Blocks:       {}", numBlocks);
        log.info("Tx/block:     {}", txPerBlock);
        log.info("Total tx:     {}", totalTx);

        // Create wallet keys: one per transaction
        List<ECKey> walletKeys = new ArrayList<>();
        for (int i = 0; i < totalTx; i++) walletKeys.add(new ECKey());

        // Fund all wallets from genesis
        Wallet genesisWallet = Wallet.fromKeys(networkParameters,
                ECKey.fromPrivate(Utils.HEX.decode(genesisPriv)), contextRoot);
        HashMap<String, BigInteger> funding = new HashMap<>();
        for (ECKey k : walletKeys) {
            funding.put(k.toAddress(networkParameters).toString(), BigInteger.valueOf(20000));
        }
        Transaction fundingTx = genesisWallet.payToList(null, funding,
                NetworkParameters.BIGTANGLE_TOKENID, "fund");
        log.info("Funding sent to {} wallets", walletKeys.size());

        // Batch funding into a block and connect it
        Sha256Hash fundBlockHash;
        {
            BlockStoreInterface bs = storeService.getStore();
            try {
                Block proto = cacheBlockPrototypeService.getBlockPrototype(bs);
                proto.addTransaction(fundingTx);
                blockSaveService.saveBatchBlock(proto, bs);
                fundBlockHash = proto.getHash();
            } finally {
                bs.close();
            }
            blockGraph.updateChain(false);
        }

        // Retrieve funding UTXOs
        Sha256Hash fundTxHash = fundingTx.getHash();
        Map<String, FreeStandingTransactionOutput> addrToCoin = new HashMap<>();
        for (int i = 0; i < fundingTx.getOutputs().size(); i++) {
            UTXO utxo = store.getTransactionOutput(fundBlockHash, fundTxHash, i);
            if (utxo != null) {
                addrToCoin.put(utxo.getAddress(),
                        new FreeStandingTransactionOutput(networkParameters, utxo));
            }
        }
        List<FreeStandingTransactionOutput> allCoins = new ArrayList<>();
        for (ECKey k : walletKeys) {
            String addr = k.toAddress(networkParameters).toString();
            FreeStandingTransactionOutput c = addrToCoin.get(addr);
            if (c != null) allCoins.add(c);
        }
        log.info("Pre-fetched {} UTXOs", allCoins.size());

        ECKey finalRecipient = new ECKey();
        String finalAddr = finalRecipient.toAddress(networkParameters).toString();

        // Track cumulative per-phase times across ALL blocks
        long cumSubmitNs = 0, cumBatchNs = 0, cumMcmcNs = 0, cumProtoNs = 0, cumChainNs = 0;
        int submittedTx = 0;

        long wallStart = System.nanoTime();
        for (int blockNum = 0; blockNum < numBlocks; blockNum++) {
            // Phase 1: Submit transactions to mempool
            long t0 = System.nanoTime();
            for (int j = 0; j < txPerBlock; j++) {
                int idx = blockNum * txPerBlock + j;
                if (idx >= allCoins.size()) break;
                FreeStandingTransactionOutput coin = allCoins.get(idx);
                ECKey wk = walletKeys.get(idx);
                Wallet w = Wallet.fromKeys(networkParameters, wk, contextRoot);
                Transaction tx = w.payToListTransaction(null,
                        new HashMap<>(Map.of(finalAddr, BigInteger.valueOf(15000))),
                        NetworkParameters.BIGTANGLE_TOKENID, "pay", List.of(coin));
                if (tx != null) {
                    w.submitTransaction(tx);
                    submittedTx++;
                }
            }
            cumSubmitNs += System.nanoTime() - t0;

            // Phase 2: Batch mempool into block
            long t1 = System.nanoTime();
            blockSaveService.batchBlocksFromMempool();
            cumBatchNs += System.nanoTime() - t1;

            // Phase 3: MCMC update
            long t2 = System.nanoTime();
            mcmcService.update(store);
            cumMcmcNs += System.nanoTime() - t2;

            // Phase 4: Prototype
            long t3 = System.nanoTime();
            mcmcService.calcNewBlockPrototype(store);
            cumProtoNs += System.nanoTime() - t3;

            // Phase 5: Chain update
            long t4 = System.nanoTime();
            blockGraph.updateChain(false);
            cumChainNs += System.nanoTime() - t4;

            if (blockNum > 0 && blockNum % 50 == 0) {
                long elapsed = (System.nanoTime() - wallStart) / 1_000_000;
                log.info("  Block {}/{} — {} ms elapsed, {} tx submitted", blockNum, numBlocks, elapsed, submittedTx);
            }
        }
        long wallMs = (System.nanoTime() - wallStart) / 1_000_000;

        double submitAvg = txPerBlock > 0 ? (double) cumSubmitNs / numBlocks / 1_000_000 : 0;
        double batchAvg = (double) cumBatchNs / numBlocks / 1_000_000;
        double mcmcAvg = (double) cumMcmcNs / numBlocks / 1_000_000;
        double protoAvg = (double) cumProtoNs / numBlocks / 1_000_000;
        double chainAvg = (double) cumChainNs / numBlocks / 1_000_000;
        double tps = wallMs > 0 ? (double) submittedTx / wallMs * 1000 : 0;

        log.info("");
        log.info("==============================================");
        log.info("  Chain Throughput Benchmark");
        log.info("==============================================");
        log.info("Blocks:        {}", numBlocks);
        log.info("Tx/block:      {}", txPerBlock);
        log.info("Total tx:      {}", submittedTx);
        log.info("Total wall:    {} ms", wallMs);
        log.info("Throughput:    {} tx/s", (long) tps);
        log.info("");
        log.info("  Phase           Total (ms)  Avg/block (ms)  % of wall");
        log.info("  -------------  -----------  --------------  --------");
        long cumTotal = cumSubmitNs + cumBatchNs + cumMcmcNs + cumProtoNs + cumChainNs;
        long cumTotalMs = cumTotal / 1_000_000;
        log.info("  Submit          {}             {}               {}%",
                cumSubmitNs / 1_000_000, cumSubmitNs / numBlocks / 1_000_000,
                cumTotalMs > 0 ? cumSubmitNs / 1_000_000 * 100 / cumTotalMs : 0);
        log.info("  Batch           {}             {}               {}%",
                cumBatchNs / 1_000_000, cumBatchNs / numBlocks / 1_000_000,
                cumTotalMs > 0 ? cumBatchNs / 1_000_000 * 100 / cumTotalMs : 0);
        log.info("  MCMC update     {}             {}               {}%",
                cumMcmcNs / 1_000_000, cumMcmcNs / numBlocks / 1_000_000,
                cumTotalMs > 0 ? cumMcmcNs / 1_000_000 * 100 / cumTotalMs : 0);
        log.info("  Prototype       {}             {}               {}%",
                cumProtoNs / 1_000_000, cumProtoNs / numBlocks / 1_000_000,
                cumTotalMs > 0 ? cumProtoNs / 1_000_000 * 100 / cumTotalMs : 0);
        log.info("  Chain update    {}             {}               {}%",
                cumChainNs / 1_000_000, cumChainNs / numBlocks / 1_000_000,
                cumTotalMs > 0 ? cumChainNs / 1_000_000 * 100 / cumTotalMs : 0);
        log.info("==============================================");
        assertTrue(submittedTx > 0, "Must have successful transactions");
    }
}
