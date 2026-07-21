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

        // Build the chain: for each block, create txPerBlock transactions,
        // submit each to mempool, then batch into a block and connect it
        long chainBuildStart = System.nanoTime();
        int coinIdx = 0;
        int submittedTx = 0;
        for (int blockNum = 0; blockNum < numBlocks; blockNum++) {
            for (int j = 0; j < txPerBlock; j++) {
                if (coinIdx >= allCoins.size()) break;
                FreeStandingTransactionOutput coin = allCoins.get(coinIdx);
                ECKey wk = walletKeys.get(coinIdx);
                Wallet w = Wallet.fromKeys(networkParameters, wk, contextRoot);
                Transaction tx = w.payToListTransaction(null,
                        new HashMap<>(Map.of(finalAddr, BigInteger.valueOf(15000))),
                        NetworkParameters.BIGTANGLE_TOKENID, "pay", List.of(coin));
                if (tx != null) {
                    w.submitTransaction(tx);
                    submittedTx++;
                }
                coinIdx++;
            }
            // Batch this block's transactions from mempool
            blockSaveService.batchBlocksFromMempool();
            blockGraph.updateChain(false);

            if (blockNum > 0 && blockNum % 50 == 0) {
                log.info("  Built {} / {} blocks...", blockNum, numBlocks);
            }
        }
        long chainBuildMs = (System.nanoTime() - chainBuildStart) / 1_000_000;
        log.info("Chain build:  {} ms ({} blocks, {} tx)", chainBuildMs, numBlocks, submittedTx);

        // MCMC update — this is the main metric
        long mcmcStart = System.nanoTime();
        if (coinIdx > 0) mcmcService.update(store);
        long mcmcMs = (System.nanoTime() - mcmcStart) / 1_000_000;

        long protoStart = System.nanoTime();
        if (coinIdx > 0) mcmcService.calcNewBlockPrototype(store);
        long protoMs = (System.nanoTime() - protoStart) / 1_000_000;

        long chainUpdStart = System.nanoTime();
        if (coinIdx > 0) blockGraph.updateChain(false);
        long chainUpdMs = (System.nanoTime() - chainUpdStart) / 1_000_000;

        long wallMs = (System.nanoTime() - chainBuildStart) / 1_000_000;

        log.info("");
        log.info("==============================================");
        log.info("  Chain MCMC Results");
        log.info("==============================================");
        log.info("Blocks:       {}", numBlocks);
        log.info("Tx/block:     {}", txPerBlock);
        log.info("Total tx:     {}", coinIdx);
        log.info("Chain build:  {} ms", chainBuildMs);
        log.info("MCMC update:  {} ms", mcmcMs);
        log.info("Prototype:    {} ms", protoMs);
        log.info("Chain update: {} ms", chainUpdMs);
        log.info("Total wall:   {} ms", wallMs);
        log.info("==============================================");
        assertTrue(coinIdx > 0, "Must have successful transactions");
    }
}
