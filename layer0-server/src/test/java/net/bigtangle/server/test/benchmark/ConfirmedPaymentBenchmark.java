package net.bigtangle.server.test.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
import net.bigtangle.layer0.server.Layer0ServerStart;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.server.service.RandaoService;
import net.bigtangle.server.service.StakeService;
import net.bigtangle.server.service.ValidatorDutyService;
import net.bigtangle.server.test.AbstractIntegrationTest;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

/**
 * Realistic SINGLE-SERVER end-to-end confirmed-TPS benchmark. Boots the real
 * {@link Layer0ServerStart} (HTTP + postgres + all scheduled services) with a
 * staked/activated validator and a short slot interval, so the WHOLE pipeline
 * runs: HTTP submit → mempool → micro-batch blocks → beacon → on-chain
 * CONFIRMED. Unlike {@code PaymentBenchmark} (mempool ingest only) this measures
 * what the network actually confirms.
 *
 * <p>Run standalone (its own Spring context must not coexist with the other
 * test contexts):
 * <pre>
 * mvn test -pl layer0-server \
 *   -Dtest=ConfirmedPaymentBenchmark \
 *   -Dbench.tx=10000 -Dbench.clients=20 -Dbench.batch=250 \
 *   -Dbatch.minTx=3000 -Dbatch.maxBatchAgeMs=1500 \
 *   -Dperf.confirmLogMinTx=50 -Dperf.connectLogMinRefs=10 -Dperf.sweepLogMinBlocks=10
 * </pre>
 *
 * <p>Tunables (system properties): {@code bench.tx} (total payments, default
 * 5000), {@code bench.clients} (submit concurrency, default 20),
 * {@code bench.batch} (tx per submit call, default 250), {@code bench.pay}
 * (pay per tx, default 10000), {@code bench.fund} (fund per wallet, default
 * 20000), {@code bench.confirmTimeoutSec} (default 300).
 *
 * <p>Confirmation-path diagnostics (log lines per beacon):
 * {@code perf.sweepLogMinBlocks} (proposal reference sweep log threshold,
 * default 50), {@code perf.connectLogMinRefs} (beacon-connect breakdown
 * threshold, default 20), {@code perf.confirmLogMinTx} (confirmBlocksSorted
 * spent-write breakdown threshold, default 100). Batching: {@code batch.minTx}
 * (mempool size before a micro-batch drains, default 2000) and
 * {@code batch.maxBatchAgeMs} (force-drain age, default 2000) — larger
 * {@code batch.minTx} yields fewer, larger batch blocks per slot.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = Layer0ServerStart.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "server.net=Test", "service.schedule.initsync=false", "server.runKafkaStream=false",
				"server.fundEnabled=true", "spring.main.allow-circular-references=true",
				"service.schedule.chainlength=true", "service.schedule.blockbatch=true",
				"service.schedule.microbatch=true", "service.schedule.upchainrate=1000",
				// pos.slotIntervalMs intentionally NOT pinned here: it resolves
				// from application.yml (${POS_SLOT_INTERVAL_MS:12000}) so a run
				// can override the slot cadence via -DPOS_SLOT_INTERVAL_MS=...
				// without recompiling (max-TPS exploration).
				})
public class ConfirmedPaymentBenchmark extends AbstractIntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(ConfirmedPaymentBenchmark.class);

	@Autowired
	private StakeService stakeService;

	@Autowired
	private ValidatorDutyService validatorDutyService;

	@Test
	public void maxConfirmedTps() throws Exception {
		int totalTx = Integer.parseInt(System.getProperty("bench.tx", "5000"));
		int clients = Integer.parseInt(System.getProperty("bench.clients", "20"));
		int batchSize = Integer.parseInt(System.getProperty("bench.batch", "250"));
		long payAmount = Long.parseLong(System.getProperty("bench.pay", "10000"));
		long fundAmount = Long.parseLong(System.getProperty("bench.fund", "20000"));
		int confirmTimeoutSec = Integer.parseInt(System.getProperty("bench.confirmTimeoutSec", "300"));

		log.info("==============================================");
		log.info("  Confirmed TPS (whole pipeline) -> {}", contextRoot);
		log.info("==============================================");
		log.info("Total tx: {}, clients: {}, batch: {}", totalTx, clients, batchSize);
		log.info("Fund per wallet: {}, pay per tx: {}", fundAmount, payAmount);

		// ---- 1. Activate a validator so the real slot ticker proposes beacons.
		PQKey validatorKey = PQKey.createNew();
		StakeRecord stake = new StakeRecord(validatorKey.getPubKey(), StakeService.MIN_STAKE,
				validatorKey.getPubKeyHash());
		stake.setBlsPubkey(RandaoService.blsPubkey(validatorKey));
		store.saveStakeDeposit(stake);
		stakeService.activateValidator(validatorKey.getPubKey(), 0, store);
		validatorDutyService.setValidatorKey(validatorKey);
		// The duty state (last proposed/attested slot) is restored from the DB at
		// Spring context boot — BEFORE the per-run reset — so it still holds the
		// previous run's slot numbers. With a different slot interval those are
		// on a different numbering scale (e.g. a prior 1s-slot run leaves ~254M
		// while a 12s-slot run sits ~21M), which makes mayPropose() reject every
		// slot. Reload the (now-empty) state so this run proposes from scratch.
		validatorDutyService.restoreDutyState();

		// ---- 2. Wait for the beacon pipeline to produce + confirm blocks.
		// Scale the wait with the configured slot interval: reaching
		// chainLength>=2 takes ~2 slots, and a 60s-slot run needs ~2 minutes.
		long slotIntervalMs = Long.getLong("pos.slotIntervalMs", 12_000L);
		long deadline = System.currentTimeMillis() + Math.max(60_000, 3 * slotIntervalMs);
		long chainLength = 0;
		while (System.currentTimeMillis() < deadline) {
			chainLength = cacheBlockService.getMaxConfirmedReward(store).getChainLength();
			if (chainLength >= 2) {
				break;
			}
			Thread.sleep(1000);
		}
		assertTrue(chainLength >= 2, "beacon chain did not advance to chainLength>=2 (got " + chainLength
				+ ") — is a validator staked/activated and the slot ticker running?");
		log.info("Beacon pipeline live: chainLength={}", chainLength);

		// ---- 3. Fund one wallet per tx with a CONFIRMED spendable BC UTXO.
		// Memory model: keep only the 32-byte ML-DSA SEED per wallet (~6 MB
		// total). A materialized PQKey holds the fully-expanded ~25KB private
		// key, so 200k live keys would need ~6GB of heap; keys are re-derived
		// on demand during the build phase instead.
		Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
		byte[][] walletSeeds = new byte[totalTx][];
		String[] walletAddrs = new String[totalTx];
		long fundIndex = 1_000_000_000L;
		List<UTXO> fundBatch = new ArrayList<>(5000);
		for (int i = 0; i < totalTx; i++) {
			PQKey k = PQKey.createNew();
			walletSeeds[i] = Utils.HEX.decode(k.getPrivateKeySeedAsHex());
			String addr = Address.fromHash160(networkParameters, k.getPubKeyHash()).toBase58();
			walletAddrs[i] = addr;
			UTXO utxo = new UTXO();
			utxo.setHash(genesis.getTransactions().get(0).getHash());
			utxo.setIndex(fundIndex++);
			utxo.setValue(new net.bigtangle.core.Coin(BigInteger.valueOf(fundAmount),
					NetworkParameters.BIGTANGLE_TOKENID));
			utxo.setCoinbase(true);
			utxo.setScript(ScriptBuilder.createOutputScript(
					Address.fromHash160(networkParameters, k.getPubKeyHash())));
			utxo.setAddress(addr);
			utxo.setBlockHash(genesis.getHash());
			utxo.setTokenid(NetworkParameters.BIGTANGLE_TOKENID_STRING);
			utxo.setConfirmed(true);
			utxo.setSpent(false);
			fundBatch.add(utxo);
			if (fundBatch.size() >= 5000) {
				store.addUnspentTransactionOutput(fundBatch);
				fundBatch.clear();
			}
		}
		if (!fundBatch.isEmpty()) {
			store.addUnspentTransactionOutput(fundBatch);
		}
		log.info("Funded {} wallets with {} BC each", totalTx, fundAmount);

		// ---- 4. Pre-build + sign all transactions (client CPU, not timed).
		PQKey recipient = PQKey.createNew();
		String recvAddr = Address.fromHash160(networkParameters, recipient.getPubKeyHash()).toBase58();
		int txPerClient = Math.max(1, totalTx / clients);
		Transaction[] allTxs = new Transaction[totalTx];
		AtomicInteger built = new AtomicInteger(0);
		ExecutorService buildPool = Executors.newFixedThreadPool(clients);
		@SuppressWarnings("unchecked")
		CompletableFuture<Void>[] buildFutures = new CompletableFuture[clients];
		for (int c = 0; c < clients; c++) {
			int startIdx = c * txPerClient;
			buildFutures[c] = CompletableFuture.runAsync(() -> {
				try {
					for (int i = 0; i < txPerClient; i++) {
						int idx = startIdx + i;
						if (idx >= totalTx) {
							break;
						}
						PQKey wk = PQKey.fromMLDSA(walletSeeds[idx]);
						UTXO utxo = new UTXO();
						utxo.setHash(genesis.getTransactions().get(0).getHash());
						utxo.setIndex(1_000_000_000L + idx);
						utxo.setValue(new net.bigtangle.core.Coin(BigInteger.valueOf(fundAmount),
								NetworkParameters.BIGTANGLE_TOKENID));
						utxo.setCoinbase(true);
						utxo.setScript(ScriptBuilder.createOutputScript(
								Address.fromHash160(networkParameters, wk.getPubKeyHash())));
						utxo.setAddress(Address.fromHash160(networkParameters, wk.getPubKeyHash()).toBase58());
						utxo.setBlockHash(genesis.getHash());
						utxo.setTokenid(NetworkParameters.BIGTANGLE_TOKENID_STRING);
						utxo.setConfirmed(true);
						utxo.setSpent(false);
						FreeStandingTransactionOutput coin = new FreeStandingTransactionOutput(networkParameters, utxo);
						Wallet w = Wallet.fromKeys(networkParameters, wk);
						HashMap<String, BigInteger> pay = new HashMap<>();
						pay.put(recvAddr, BigInteger.valueOf(payAmount));
						Transaction tx = w.payToListTransaction(null, pay, NetworkParameters.BIGTANGLE_TOKENID,
								"bench", List.of(coin));
						if (tx != null) {
							allTxs[idx] = tx;
							built.incrementAndGet();
						}
					}
				} catch (Exception e) {
					log.error("Build client failed", e);
				}
			}, buildPool);
		}
		CompletableFuture.allOf(buildFutures).get(30, TimeUnit.MINUTES);
		buildPool.shutdownNow();
		log.info("Pre-built {}/{} transactions", built.get(), totalTx);

		// ---- 5. Timed parallel submit.
		AtomicInteger submitted = new AtomicInteger(0);
		ConcurrentLinkedQueue<String> txHashes = new ConcurrentLinkedQueue<>();
		ExecutorService pool = Executors.newFixedThreadPool(clients);
		@SuppressWarnings("unchecked")
		CompletableFuture<Void>[] futures = new CompletableFuture[clients];
		long submitWallStart = System.nanoTime();
		for (int c = 0; c < clients; c++) {
			int startIdx = c * txPerClient;
			futures[c] = CompletableFuture.runAsync(() -> {
				try {
					List<Transaction> txs = new ArrayList<>();
					for (int i = 0; i < txPerClient; i++) {
						int idx = startIdx + i;
						if (idx >= totalTx) {
							break;
						}
						Transaction tx = allTxs[idx];
						if (tx == null) {
							continue;
						}
						txs.add(tx);
						if (txs.size() == batchSize) {
							submitted.addAndGet(submitBatch(txs));
							for (Transaction t : txs) {
								txHashes.add(t.getHash().toString());
							}
							txs.clear();
						}
					}
					if (!txs.isEmpty()) {
						submitted.addAndGet(submitBatch(txs));
						for (Transaction t : txs) {
							txHashes.add(t.getHash().toString());
						}
					}
				} catch (Exception e) {
					log.error("Submit client failed", e);
				}
			}, pool);
		}
		// Submit wall scales with load: 200k txs under backlog pressure can
		// legitimately take longer than a fixed 10-minute window.
		CompletableFuture.allOf(futures).get(Math.max(10, totalTx / 200), TimeUnit.MINUTES);
		pool.shutdownNow();
		long submitWallMs = (System.nanoTime() - submitWallStart) / 1_000_000;
		int ok = submitted.get();
		log.info("Submit done: {} ms (submitted {}, txs tracked {})", submitWallMs, ok, txHashes.size());

		if (ok == 0) {
			assertTrue(ok > 0, "No transactions were submitted to the node");
		}

		// ---- 6. Poll until all CONFIRMED. Each transfer spends exactly one of
		// the fund UTXOs (all outputs of genesis tx[0]), and that UTXO is marked
		// spent only at confirmation — so the count of spent fund UTXOs is the
		// confirmed count. This is authoritative, reorg-aware (unconfirm flips
		// spent back to false), and avoids writing/reading the transactionstatus
		// table on the confirm critical path.
		Sha256Hash fundTxHash = genesis.getTransactions().get(0).getHash();
		int confirmed = 0;
		int peakConfirmed = 0;
		long peakReachedMs = 0;
		long confirmDeadline = System.currentTimeMillis() + confirmTimeoutSec * 1000L;
		while (System.currentTimeMillis() < confirmDeadline) {
			Thread.sleep(1000);
			confirmed = (int) store.countSpentOutputs(fundTxHash);
			if (confirmed > peakConfirmed) {
				peakConfirmed = confirmed;
				peakReachedMs = (System.nanoTime() - submitWallStart) / 1_000_000;
			}
			log.info("  confirmed {}/{} (chainLength {}, peak {})", confirmed, ok,
					cacheBlockService.getMaxConfirmedReward(store).getChainLength(), peakConfirmed);
			if (confirmed >= ok) {
				break;
			}
		}
		long confirmWallMs = (System.nanoTime() - submitWallStart) / 1_000_000;

		double submitTps = submitWallMs > 0 ? (double) ok / submitWallMs * 1000 : 0;
		double confirmTps = confirmWallMs > 0 ? (double) ok / confirmWallMs * 1000 : 0;
		double peakTps = peakReachedMs > 0 ? (double) peakConfirmed / peakReachedMs * 1000 : 0;

		log.info("");
		log.info("==============================================");
		log.info("  CONFIRMED TPS RESULTS ({} -> {})", contextRoot, confirmWallMs);
		log.info("==============================================");
		log.info("Total tx:        {} (submitted {}, confirmed {})", totalTx, ok, confirmed);
		log.info("Submit wall:     {} ms", submitWallMs);
		log.info("Confirm wall:    {} ms", confirmWallMs);
		log.info("Submit TPS:      {} tx/s", String.format("%.1f", submitTps));
		log.info("CONFIRMED TPS:   {} tx/s (final {}/{})", String.format("%.1f", confirmTps), confirmed, ok);
		log.info("PEAK SUSTAINED:  {} tx/s ({} confirmed in {} ms)", String.format("%.1f", peakTps), peakConfirmed,
				peakReachedMs);
		log.info("==============================================");

		assertTrue(peakConfirmed > 0, "No transaction confirmed within the timeout");
	}

	private int submitBatch(List<Transaction> txs) throws Exception {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);
		for (Transaction bt : txs) {
			byte[] b = bt.bitcoinSerialize();
			dos.writeInt(b.length);
			dos.write(b);
		}
		dos.close();
		OkHttp3Util.post(contextRoot + ReqCmd.submitTransactions.name(), baos.toByteArray());
		return txs.size();
	}

	private static long percentile(List<Long> values, int pct) {
		if (values.isEmpty()) {
			return 0;
		}
		List<Long> sorted = new ArrayList<>(values);
		Collections.sort(sorted);
		int idx = Math.min(sorted.size() - 1, (int) Math.ceil(pct / 100.0 * sorted.size()) - 1);
		return sorted.get(Math.max(0, idx));
	}
}