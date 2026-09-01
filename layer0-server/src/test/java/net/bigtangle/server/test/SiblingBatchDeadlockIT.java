package net.bigtangle.server.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionOutPoint;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

/**
 * Reproduces the sibling-batch-block deadlock in
 * {@code ServiceBaseConfirmation.addAllUnconfirmedBlocks} and verifies the fix.
 *
 * <p>The shared kafka mempool lets 2+ nodes drain the SAME transactions into
 * SIBLING batch blocks (each with a different parent, all spending the same
 * inputs). Under PER_NODE_PG=1 the reference sweep rejected EVERY sibling via
 * {@code hasSpentInputs} but only collected {@code duplicatePoint} rejections
 * into {@code conflictingSiblings}. When every candidate was rejected by
 * {@code hasSpentInputs} (the sibling storm), the deadlock-break never fired,
 * the beacon carried 0 references, and every transaction stayed BATCHED forever
 * (measured 0/148 and 0/2000 confirmed).
 *
 * <p>Scenario reproduced here: one batch block (the "winner twin") confirmed
 * and spent the shared genesis UTXO; two later siblings re-spend it. The sweep
 * rejects BOTH siblings via {@code hasSpentInputs} (their input is already
 * spent by a positive-chainlength block), so without the fix {@code added=0}
 * and the deadlock-break never fires. The fix collects {@code hasSpentInputs}-
 * rejected candidates into {@code conflictingSiblings} so the deadlock-break
 * references exactly one.
 */
public class SiblingBatchDeadlockIT extends AbstractIntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(SiblingBatchDeadlockIT.class);

	private byte[] genesisKeySeed() {
		byte[] mlDsaSeed = new byte[32];
		java.util.Arrays.fill(mlDsaSeed, (byte) 0x01);
		return mlDsaSeed;
	}

	/** The genesis coinbase UTXO (index 0) — a real row in the outputs table. */
	private UTXO genesisUtxo() {
		Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
		Transaction coinbaseTx = genesis.getTransactions().get(0);
		TransactionOutput genesisOut = coinbaseTx.getOutput(0);
		UTXO utxo = new UTXO();
		utxo.setHash(coinbaseTx.getHash());
		utxo.setIndex(0);
		utxo.setValue(genesisOut.getValue());
		utxo.setCoinbase(true);
		utxo.setScript(genesisOut.getScriptPubKey());
		PQKey genesisKey = PQKey.fromMLDSA(genesisKeySeed());
		utxo.setAddress(Address.fromHash160(networkParameters, genesisKey.getPubKeyHash()).toBase58());
		utxo.setBlockHash(genesis.getHash());
		utxo.setTokenid(NetworkParameters.BIGTANGLE_TOKENID_STRING);
		utxo.setConfirmed(true);
		utxo.setSpent(false);
		return utxo;
	}

	/** A signed bc spend of the genesis coinbase to a fresh recipient. */
	private Transaction buildSpendTx(UTXO genesisUtxo, String memo) throws Exception {
		FreeStandingTransactionOutput coin = new FreeStandingTransactionOutput(networkParameters, genesisUtxo);
		Wallet w = Wallet.fromKeys(networkParameters, PQKey.fromMLDSA(genesisKeySeed()));
		Transaction tx = new Transaction(networkParameters);
		tx.addInput(genesisUtxo.getBlockHash(), coin);
		PQKey recipient = PQKey.createNew();
		tx.addOutput(genesisUtxo.getValue(),
				Address.fromHash160(networkParameters, recipient.getPubKeyHash()));
		w.signTransaction(tx, null);
		return tx;
	}

	@Test
	public void testSiblingBatchDeadlockReferenced() throws Exception {
		// The conflict cache is a static ThreadLocal shared across the Spring
		// context; a previous test's resolution must not leak a stale NoConflict
		// for this outpoint.
		net.bigtangle.server.service.base.ServiceBaseConfirmation.clearConflictCache();

		UTXO genesisUtxo = genesisUtxo();
		Transaction tx = buildSpendTx(genesisUtxo, "sibling-deadlock");
		TransactionOutPoint op = tx.getInputs().get(0).getOutpoint();

		// TWO SIBLING batch blocks, different parents, SAME transaction spend.
		Block base = tipsService.getValidatedBlockPair(store).getLeft().getBlock();
		Block siblingA = Block.createBlock(networkParameters, base, base);
		siblingA.addTransaction(tx);
		blockSaveService.saveBatchBlock(siblingA, store);
		Sha256Hash hashA = siblingA.getHash();

		Block siblingB = Block.createBlock(networkParameters, base, siblingA);
		siblingB.addTransaction(tx);
		blockSaveService.saveBatchBlock(siblingB, store);
		Sha256Hash hashB = siblingB.getHash();
		assertTrue(!hashA.equals(hashB), "siblings must hash differently");
		assertTrue(store.getBlockEvaluationsByhashs(hashA).getChainlength() < 0, "siblingA must be non-chain");
		assertTrue(store.getBlockEvaluationsByhashs(hashB).getChainlength() < 0, "siblingB must be non-chain");

		// The WINNER TWIN confirmed and spent the shared genesis UTXO (positive
		// chainlength). Both siblings now re-spend an already-spent outpoint, so
		// hasSpentInputs rejects them — the deadlock precondition.
		Block winner = makeRewardBlock();
		Sha256Hash winnerHash = winner != null ? winner.getHash() : base.getHash();
		store.updateBlockEvaluationChainlength(winnerHash, 1000L);
		store.updateTransactionOutputSpent(op.getBlockHash(), op.getTxHash(), op.getIndex(), true, winnerHash);
		store.commitDatabaseBatchWrite();
		// The sweep reads the @Cacheable("utxos") cache, not the DB directly; a
		// stale cache hides the poison (exactly the wild bug). Evict so the sweep
		// sees the true spent state and must reject ALL siblings.
		cacheBlockService.evictAllTransactionOutputs();

		// Direct proof of the deadlock precondition: hasSpentInputs must reject a
		// sibling spending the shared outpoint, else the sweep would add the first
		// sibling and the deadlock-break path would never be exercised.
		net.bigtangle.server.service.base.ServiceBaseConfirmation.clearConflictCache();
		ServiceBaseConnect probe = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper);
		BlockWrap wrapB = store.getBlockWraps(List.of(hashB)).get(0);
		boolean spent = probe.hasSpentInputs(Set.of(wrapB), true, store);
		log.info("hasSpentInputs(siblingB) after winner-spend = {} (must be true to reproduce the deadlock)", spent);
		assertTrue(spent, "the winner spend must make hasSpentInputs reject the sibling — deadlock precondition");

		// Run the reference sweep exactly like the beacon proposal does.
		ServiceBaseConnect serviceBase = new ServiceBaseConnect(serverConfiguration, networkParameters,
				cacheBlockService, jsonmapper);
		long prevChainLength = store.getRewardChainLength(cacheBlockService.getMaxConfirmedReward(store).getBlockHash());
		long cutoffheight = serviceBase.getRewardCutoffHeight(
				cacheBlockService.getMaxConfirmedReward(store).getBlockHash(), store);
		List<BlockType> ordertypes = new ArrayList<>();
		ordertypes.add(BlockType.BLOCKTYPE_INITIAL);
		ordertypes.add(BlockType.BLOCKTYPE_TRANSFER);
		ordertypes.add(BlockType.BLOCKTYPE_TOKEN_CREATION);
		ordertypes.add(BlockType.BLOCKTYPE_FILE);
		ordertypes.add(BlockType.BLOCKTYPE_USERDATA);
		ordertypes.add(BlockType.BLOCKTYPE_GOVERNANCE);
		ordertypes.add(BlockType.BLOCKTYPE_CROSSTANGLE);
		ordertypes.add(BlockType.BLOCKTYPE_STAKE);
		ordertypes.add(BlockType.BLOCKTYPE_SLASHING);
		ordertypes.add(BlockType.BLOCKTYPE_EXIT);
		ordertypes.add(BlockType.BLOCKTYPE_ORDER_OPEN);
		ordertypes.add(BlockType.BLOCKTYPE_ORDER_CANCEL);
		ordertypes.add(BlockType.BLOCKTYPE_CONTRACT_EVENT);
		ordertypes.add(BlockType.BLOCKTYPE_CONTRACTEVENT_CANCEL);
		ordertypes.add(BlockType.BLOCKTYPE_EVM_DEPLOY);
		ordertypes.add(BlockType.BLOCKTYPE_EVM_CALL);

		Set<BlockWrap> blocks = new HashSet<>();
		serviceBase.dagBlockHashesFrom(blocks, serviceBase.getBlockWrap(base.getHash(), store), cutoffheight,
				prevChainLength, ordertypes, true, true, store);
		serviceBase.addAllUnconfirmedBlocks(blocks, cutoffheight, ordertypes, true, store);

		boolean referencedA = blocks.stream().anyMatch(w -> w.getBlockHash().equals(hashA));
		boolean referencedB = blocks.stream().anyMatch(w -> w.getBlockHash().equals(hashB));
		log.info("sweep referenced siblingA={} siblingB={} (total refs={})", referencedA, referencedB, blocks.size());
		assertTrue(referencedA || referencedB,
				"the sweep must reference at least ONE sibling batch block so its transactions confirm — "
						+ "before the fix it referenced none and stranded the tx BATCHED forever");
		assertTrue(!(referencedA && referencedB),
				"exactly ONE sibling must win (both reference the same spend would double-spend the beacon)");
	}
}
