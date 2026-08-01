package net.bigtangle.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.BlockType;
import net.bigtangle.core.Transaction;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.exception.VerificationException;

public class MempoolServiceTest {

	@Test
	public void testGetTransactionTypeMapping() {
		Transaction order = new Transaction(MainNetParams.get());
		order.setDataClassName("OrderOpen");
		assertEquals(BlockType.BLOCKTYPE_ORDER_OPEN, MempoolService.getTransactionType(order));

		Transaction cancel = new Transaction(MainNetParams.get());
		cancel.setDataClassName("OrderCancelInfo");
		assertEquals(BlockType.BLOCKTYPE_ORDER_CANCEL, MempoolService.getTransactionType(cancel));

		Transaction contract = new Transaction(MainNetParams.get());
		contract.setDataClassName("ContractEventInfo");
		assertEquals(BlockType.BLOCKTYPE_CONTRACT_EVENT, MempoolService.getTransactionType(contract));

		Transaction transfer = new Transaction(MainNetParams.get());
		assertEquals(BlockType.BLOCKTYPE_TRANSFER, MempoolService.getTransactionType(transfer));

		Transaction unknown = new Transaction(MainNetParams.get());
		unknown.setDataClassName("SomethingElse");
		assertEquals(BlockType.BLOCKTYPE_TRANSFER, MempoolService.getTransactionType(unknown));
	}

	@Test
	public void testSubmitOrderTypesIntoOrderQueues() {
		MempoolService mempool = new MempoolService();

		Transaction buy = new Transaction(MainNetParams.get());
		buy.setDataClassName("OrderOpen");
		mempool.submitOrder(buy);
		assertEquals(1, mempool.countByType(BlockType.BLOCKTYPE_ORDER_OPEN));
		assertEquals(1, mempool.getPendingByType(BlockType.BLOCKTYPE_ORDER_OPEN).size());
		assertEquals(0, mempool.countByType(BlockType.BLOCKTYPE_ORDER_CANCEL));

		Transaction cancel = new Transaction(MainNetParams.get());
		cancel.setDataClassName("OrderCancelInfo");
		mempool.submitOrder(cancel);
		assertEquals(1, mempool.countByType(BlockType.BLOCKTYPE_ORDER_CANCEL));

		// Non-order transaction must be rejected and not enqueued
		Transaction transfer = new Transaction(MainNetParams.get());
		assertThrows(VerificationException.class, () -> mempool.submitOrder(transfer));
		assertEquals(0, mempool.countByType(BlockType.BLOCKTYPE_TRANSFER));
	}

	@Test
	public void testDrainAllByTypeGroupsOrders() {
		MempoolService mempool = new MempoolService();

		Transaction buy = new Transaction(MainNetParams.get());
		buy.setDataClassName("OrderOpen");
		Transaction cancel = new Transaction(MainNetParams.get());
		cancel.setDataClassName("OrderCancelInfo");
		mempool.submitOrder(buy);
		mempool.submitOrder(cancel);

		Map<BlockType, List<Transaction>> drained = mempool.drainAllByType();
		assertEquals(1, drained.get(BlockType.BLOCKTYPE_ORDER_OPEN).size());
		assertEquals(1, drained.get(BlockType.BLOCKTYPE_ORDER_CANCEL).size());
		assertFalse(drained.containsKey(BlockType.BLOCKTYPE_TRANSFER));
		assertEquals(0, mempool.size());
	}
}
