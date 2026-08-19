package net.bigtangle.server.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.server.data.TransactionStatus;
import net.bigtangle.server.data.TransactionStatusRecord;

/**
 * Verifies transaction lifecycle status tracking: mempool entry records
 * MEMPOOL, upserts are latest-wins, and queries by address/status work.
 */
public class TransactionStatusTest extends AbstractIntegrationTest {

	@Test
	public void testStatusUpsertIsLatestWins() throws Exception {
		Transaction tx = new Transaction(networkParameters);

		TransactionStatusRecord.mark(store, tx, TransactionStatus.CONFIRMED, tx.getHash(), 42L, networkParameters);
		TransactionStatusRecord confirmed = store.getTransactionStatus(tx.getHash());
		assertNotNull(confirmed);
		assertEquals(TransactionStatus.CONFIRMED, confirmed.getStatus());
		assertEquals(42L, confirmed.getChainlength());
		assertEquals(tx.getHash().toString(), confirmed.getTxHash().toString());

		// A later drop overwrites the confirmed state (latest wins)
		TransactionStatusRecord.mark(store, tx, TransactionStatus.DROPPED, null, null, networkParameters);
		assertEquals(TransactionStatus.DROPPED, store.getTransactionStatus(tx.getHash()).getStatus());
		assertNull(store.getTransactionStatus(tx.getHash()).getChainlength());
	}

	@Test
	public void testQueryByAddressAndStatus() throws Exception {
		Sha256Hash tx1 = Sha256Hash.of(new byte[] { 1 });
		Sha256Hash tx2 = Sha256Hash.of(new byte[] { 2 });

		long now = System.currentTimeMillis();
		store.upsertTransactionStatus(new TransactionStatusRecord(tx1, TransactionStatus.MEMPOOL, null,
				null, "addrA", now, now));
		store.upsertTransactionStatus(new TransactionStatusRecord(tx2, TransactionStatus.CONFIRMED,
				tx2, 7L, "addrB", now, now));

		List<TransactionStatusRecord> byAddrA = store.getTransactionStatusesByAddress("addrA");
		assertEquals(1, byAddrA.size());
		assertEquals(TransactionStatus.MEMPOOL, byAddrA.get(0).getStatus());

		List<TransactionStatusRecord> confirmed = store.getTransactionStatusesByStatus(TransactionStatus.CONFIRMED);
		assertEquals(1, confirmed.size());
		assertEquals("addrB", confirmed.get(0).getAddress());
		assertEquals(7L, confirmed.get(0).getChainlength());
	}
}
