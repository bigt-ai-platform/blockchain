package net.bigtangle.mcmc.test.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import net.bigtangle.core.Block;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.layer0.mcmc.Layer0MCMCStart;
import net.bigtangle.mcmc.test.AbstractIntegrationTest;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = Layer0MCMCStart.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "server.net=Test",
                       "spring.main.allow-bean-definition-overriding=true",
                       "spring.datasource.hikari.maximum-pool-size=200" })
public class PosBenchmark extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PosBenchmark.class);

    private static final int VALIDATORS = 50;
    private static final int TX_PER_SLOT = 500;
    private static final int TOTAL_SLOTS = 100;
    private static final int TOTAL_TX = TX_PER_SLOT * TOTAL_SLOTS;

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
    public void testPosChain() throws Exception {
        // Create validator keys and wallet keys for funding
        List<PQKey> validatorKeys = new ArrayList<>();
        for (int i = 0; i < VALIDATORS; i++) validatorKeys.add(PQKey.createNew());

        List<PQKey> walletKeys = new ArrayList<>();
        for (int i = 0; i < TOTAL_TX; i++) walletKeys.add(PQKey.createNew());

        // Fund all wallets (validators + tx senders) via genesis
        Wallet genesisWallet = Wallet.fromKeys(networkParameters,
                PQKey.createNew()Utils.HEX.decode(genesisPriv)), contextRoot);
        HashMap<String, BigInteger> funding = new HashMap<>();
        for (PQKey k : validatorKeys) {
            funding.put(k.toAddress(networkParameters).toString(), BigInteger.valueOf(500000));
        }
        for (PQKey k : walletKeys) {
            funding.put(k.toAddress(networkParameters).toString(), BigInteger.valueOf(20000));
        }
        Block fb = wrapTransaction(genesisWallet.payToList(null, funding,
                NetworkParameters.BIGTANGLE_TOKENID, "fund"));
        if (fb != null) {
            makeRewardBlock(fb);
            blockGraph.updateChain(false);
            mcmcService.update(store);
            mcmcService.calcNewBlockPrototype(store);
        }
        log.info("Funded {} validators and {} wallets", validatorKeys.size(), walletKeys.size());

        // Pre-fetch UTXOs
        Sha256Hash fundBlockHash = fb.getHash();
        Sha256Hash fundTxHash = fb.getTransactions().get(0).getHash();
        Map<String, FreeStandingTransactionOutput> addrToCoin = new HashMap<>();
        for (int i = 0; i < fb.getTransactions().get(0).getOutputs().size(); i++) {
            UTXO utxo = store.getTransactionOutput(fundBlockHash, fundTxHash, i);
            if (utxo != null) {
                addrToCoin.put(utxo.getAddress(),
                        new FreeStandingTransactionOutput(networkParameters, utxo));
            }
        }
        List<FreeStandingTransactionOutput> walletCoins = new ArrayList<>();
        for (PQKey k : walletKeys) {
            String addr = k.toAddress(networkParameters).toString();
            FreeStandingTransactionOutput c = addrToCoin.get(addr);
            if (c != null) walletCoins.add(c);
        }
        log.info("Pre-fetched {} UTXOs", walletCoins.size());

        // Pre-create transactions (ECDSA in parallel before timing)
        PQKey finalRecipient = PQKey.createNew();
        String finalAddr = finalRecipient.toAddress(networkParameters).toString();

        List<Transaction> allTxs = new ArrayList<>();
        for (int i = 0; i < TOTAL_TX; i++) {
            PQKey wk = walletKeys.get(i);
            FreeStandingTransactionOutput coin = walletCoins.get(i);
            Wallet w = Wallet.fromKeys(networkParameters, wk, contextRoot);
            Transaction tx = w.payToListTransaction(null,
                    new HashMap<>(Map.of(finalAddr, BigInteger.valueOf(15000))),
                    NetworkParameters.BIGTANGLE_TOKENID, "pay", List.of(coin));
            if (tx != null) allTxs.add(tx);
        }
        log.info("Pre-created {} transactions", allTxs.size());

        // Warm up MCMC
        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);

        // PoS chain simulation: round-robin validator slots
        int MCMC_INTERVAL = 20;
        AtomicInteger txIdx = new AtomicInteger(0);
        AtomicInteger okSlots = new AtomicInteger(0);
        AtomicInteger failSlots = new AtomicInteger(0);
        long totalAttestations = 0;
        long attestTime = 0;
        long saveBlockTime = 0;

        long wallStart = System.nanoTime();
        for (int slot = 0; slot < TOTAL_SLOTS; slot++) {
            PQKey validatorKey = validatorKeys.get(slot % VALIDATORS);

            try {
                // Slot leader fetches tip via HTTP
                byte[] tipData = OkHttp3Util.postAndGetBlock(
                        contextRoot + "getTip", "");
                Block tip = networkParameters.getDefaultSerializer().makeBlock(tipData);

                // Add transactions to block
                int added = 0;
                for (int t = 0; t < TX_PER_SLOT; t++) {
                    int idx = txIdx.getAndIncrement();
                    if (idx >= allTxs.size()) break;
                    tip.addTransaction(allTxs.get(idx));
                    added++;
                }

                if (added == 0) break;

                // Block is "solved" (nonce for MCMC)

                // Simulate attestations from other validators
                long attStart = System.nanoTime();
                for (PQKey attester : validatorKeys) {
                    if (attester.equals(validatorKey)) continue;
                    attester.sign(tip.getHash());
                }
                attestTime += System.nanoTime() - attStart;
                totalAttestations += VALIDATORS - 1;

                // Submit block via HTTP (skips solidity for batch blocks)
                long t0 = System.nanoTime();
                OkHttp3Util.post(contextRoot + "saveBlock", tip.bitcoinSerialize());
                saveBlockTime += System.nanoTime() - t0;

                // MCMC + chain update only every MCMC_INTERVAL slots
                // Real PoS can run MCMC at epoch boundaries, not per slot
                if (slot > 0 && slot % MCMC_INTERVAL == 0) {
                    mcmcService.update(store);
                    mcmcService.calcNewBlockPrototype(store);
                    blockGraph.updateChain(false);
                }

                okSlots.incrementAndGet();

                if (slot > 0 && slot % 100 == 0) {
                    log.info("  Slot {}/{} completed", slot, TOTAL_SLOTS);
                }
            } catch (Exception e) {
                failSlots.incrementAndGet();
                log.error("Slot {} failed: {}", slot, e.getMessage());
            }
        }
        // Final MCMC + chain update after all slots
        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);
        blockGraph.updateChain(false);

        long wallMs = (System.nanoTime() - wallStart) / 1_000_000;
        int totalTx = okSlots.get() * TX_PER_SLOT;
        double tps = wallMs > 0 ? (double) totalTx / wallMs * 1000 : 0;

        log.info("");
        log.info("==============================================");
        log.info("  PoS Chain Benchmark (MCMC + round-robin)");
        log.info("==============================================");
        log.info("Validators:    {}", VALIDATORS);
        log.info("Total slots:   {} (ok {}, fail {})", TOTAL_SLOTS, okSlots.get(), failSlots.get());
        log.info("Tx/slot:       {}", TX_PER_SLOT);
        log.info("Total tx:      {}", totalTx);
        log.info("Wall time:     {} ms", wallMs);
        log.info("Attestations:  {} ({} ms cumulative)", totalAttestations, attestTime / 1_000_000);
        log.info("Throughput:    {} tx/s", (long) tps);
        if (totalTx > 0) {
            log.info("Avg slot time: {} ms", (double) wallMs / okSlots.get());
        }
        log.info("==============================================");
        assertTrue(okSlots.get() > 0, "Must have successful slots");
    }
}
