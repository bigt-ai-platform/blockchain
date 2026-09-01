package net.bigtangle.server.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.data.TransactionStatus;
import net.bigtangle.server.data.TransactionStatusRecord;
import net.bigtangle.server.service.BlockSaveService;
import net.bigtangle.server.service.base.ServiceVerifyReward;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.wallet.FreeStandingTransactionOutput;

/**
 * Regression tests for the double-batch conflict that made e2e payment
 * transactions report BATCHED forever even though they confirmed.
 *
 * <p>Root cause A: {@code MempoolService.submitTransaction} queued a duplicate
 * resubmission (the {@code seenTxIds} dedup returned early from verification
 * but {@code addToPending} still ran), so the same transaction was drained
 * twice into two batch blocks.
 *
 * <p>Root cause B: when conflict detection invalidated one of the twin blocks
 * (negative chainlength) its transactions' status records were never
 * reconciled, so {@code getTransactionStatus} read BATCHED off the orphaned
 * block even though the same transaction confirmed in the winning twin.
 */
public class DoubleBatchStatusIT extends AbstractIntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(DoubleBatchStatusIT.class);

	/** Builds a signed bc spend tx from the funded genesis wallet to a fresh recipient. */
	private Transaction buildSpendTx(String memo) throws Exception {
		List<FreeStandingTransactionOutput> candidates = wallet.calculateAllSpendCandidates(null, false);
		FreeStandingTransactionOutput coin = null;
		for (FreeStandingTransactionOutput c : candidates) {
			if (Arrays.equals(NetworkParameters.BIGTANGLE_TOKENID, c.getUTXO().getTokenidBuf())) {
				coin = c;
				break;
			}
		}
		assertNotNull(coin, "genesis wallet must hold a spendable bc UTXO");
		PQKey recipient = PQKey.createNew();
		String addr = Address.fromHash160(networkParameters, recipient.getPubKeyHash()).toBase58();
		return wallet.payToListTransaction(null,
				new HashMap<>(Map.of(addr, BigInteger.valueOf(1000))),
				NetworkParameters.BIGTANGLE_TOKENID, memo, List.of(coin));
	}

	/**
	 * Fix A: submitting the same transaction twice must queue it only once, so
	 * the mempool drain creates ONE batch block containing it exactly once
	 * instead of two conflicting twin blocks.
	 */
	@Test
	public void testDuplicateSubmissionIsNotDoubleBatched() throws Exception {
		Transaction tx = buildSpendTx("dup-no-double-batch");
		Sha256Hash txHash = tx.getHash();

		int before = mempoolService.size();
		mempoolService.submitTransaction(tx);
		mempoolService.submitTransaction(tx);
		int after = mempoolService.size();
		assertEquals(before + 1, after,
				"submitting the same transaction twice must queue it only once, "
						+ "or it is drained into two conflicting twin blocks");
		log.info("mempool {} -> {} for one tx submitted twice", before, after);

		List<Transaction> drained = mempoolService.drainAll();
		assertEquals(1, drained.size(), "the transaction must be drained exactly once");
		assertEquals(txHash, drained.get(0).getHash());

		Block predecessor = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
		Block block = Block.createBlock(networkParameters, predecessor, predecessor);
		for (Transaction t : drained) {
			block.addTransaction(t);
		}
		BlockSaveService.setBlockTypeFromTransactions(block);
		blockGraph.addBlock(block, false, store);
		blockGraph.updateChain(false);
		store.commitDatabaseBatchWrite();

		long occurrences = block.getTransactions().stream().filter(t -> t.getHash().equals(txHash)).count();
		assertEquals(1, occurrences, "the batch block must contain the transaction exactly once");
		log.info("block {} contains tx {} {} time(s)", block.getHash(), txHash, occurrences);

		makeRewardBlock(block);
		blockGraph.updateChain(false);

		// The transaction must be accepted by the chain: it is present in a
		// confirmed block (single inclusion — no orphaned twin).
		Sha256Hash confirmedBlock = store.getConfirmedBlockForTransaction(txHash);
		assertNotNull(confirmedBlock, "the confirmed transaction must live in a confirmed block");
		assertEquals(block.getHash(), confirmedBlock, "the batch block must be the confirmed container");
		log.info("tx {} confirmed in block {}", txHash, confirmedBlock);
	}

	/**
	 * Fix B: when a double-batched block is invalidated by conflict detection,
	 * its transaction status must be re-pointed to the confirmed twin block
	 * instead of staying BATCHED on the orphaned block forever.
	 */
	@Test
	public void testInvalidatedDoubleBatchStatusReconciled() throws Exception {
		Transaction tx = buildSpendTx("reconcile-status");
		Sha256Hash txHash = tx.getHash();

		// Simulate the double batch: save the SAME transaction into two batch
		// blocks with different parents (the orphaned twin sits on a different
		// branch, exactly like the wild scenario). The LATER save (blockA) wins
		// the status record — like the orphaned twin created after the winner.
		BlockStoreInterface bs = storeService.getStore();
		Sha256Hash blockB;
		Sha256Hash blockA;
		try {
			Block base = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
			Block protoB = Block.createBlock(networkParameters, base, base);
			protoB.addTransaction(tx);
			blockSaveService.saveBatchBlock(protoB, bs);
			blockB = protoB.getHash();

			// Twin block on a distinct parent (prev=base, branch=blockB) so the
			// two blocks hash differently while both carrying the same tx.
			Block protoA = Block.createBlock(networkParameters, base, protoB);
			protoA.addTransaction(tx);
			blockSaveService.saveBatchBlock(protoA, bs);
			blockA = protoA.getHash();
		} finally {
			bs.close();
		}
		assertNotEquals(blockA, blockB, "two batch blocks must be created (the double batch)");

		// Chain outcome: the twin blockB confirmed, blockA was orphaned /
		// invalidated by conflict detection (negative chainlength).
		store.updateBlockEvaluationConfirmed(blockB, true);
		store.updateBlockEvaluationChainlength(blockA, -183);

		// Bug precondition: the status record points at the orphaned block A.
		TransactionStatusRecord record = store.getTransactionStatus(txHash);
		assertNotNull(record, "the tx should have a status record after batching");
		assertEquals(TransactionStatus.BATCHED, record.getStatus());
		assertEquals(blockA, record.getBlockHash(),
				"the status must point at the orphaned block (double-batch bug precondition)");

		// Reconcile the invalidated block: the status must be re-pointed to the
		// confirmed twin block.
		List<BlockWrap> wraps = store.getBlockWraps(List.of(blockA));
		assertEquals(1, wraps.size(), "the orphaned block should resolve to a BlockWrap");
		ServiceVerifyReward verifier = new ServiceVerifyReward(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper, mempoolService);
		verifier.reconcileInvalidatedBlock(wraps.get(0), store);

		TransactionStatusRecord reconciled = store.getTransactionStatus(txHash);
		assertNotNull(reconciled, "the status record must still exist after reconcile");
		assertEquals(TransactionStatus.CONFIRMED, reconciled.getStatus(),
				"reconciled status must be CONFIRMED (the tx confirmed in the twin block)");
		assertEquals(blockB, reconciled.getBlockHash(),
				"reconciled status must point at the confirmed twin block");
		log.info("tx {} status reconciled to {} @ {}", txHash, reconciled.getStatus(), reconciled.getBlockHash());
	}
}
