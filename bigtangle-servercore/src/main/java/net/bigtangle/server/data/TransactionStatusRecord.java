package net.bigtangle.server.data;

import java.util.List;

import net.bigtangle.core.Block;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.script.Script;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Persistent record of a transaction's lifecycle status. Keyed by transaction
 * hash (latest state wins). The address is derived from the first spendable
 * output so a user can list "their" transactions.
 */
public class TransactionStatusRecord implements java.io.Serializable {

	private static final long serialVersionUID = 1L;

	private Sha256Hash txHash;
	private TransactionStatus status;
	private Sha256Hash blockHash;
	private Long chainlength;
	private String address;
	private long createdTime;
	private long updatedTime;

	public TransactionStatusRecord() {
	}

	public TransactionStatusRecord(Sha256Hash txHash, TransactionStatus status, Sha256Hash blockHash,
			Long chainlength, String address, long createdTime, long updatedTime) {
		this.txHash = txHash;
		this.status = status;
		this.blockHash = blockHash;
		this.chainlength = chainlength;
		this.address = address;
		this.createdTime = createdTime;
		this.updatedTime = updatedTime;
	}

	/**
	 * Writes the given status for a transaction (and best-effort the address of
	 * its first spendable output) into the status store.
	 */
	public static void mark(BlockStoreInterface store, Transaction tx, TransactionStatus status,
			Sha256Hash blockHash, Long chainlength, NetworkParameters params) throws net.bigtangle.exception.BlockStoreException {
		long now = System.currentTimeMillis();
		TransactionStatusRecord record = new TransactionStatusRecord(tx.getHash(), status, blockHash, chainlength,
				deriveAddress(tx, params), now, now);
		store.upsertTransactionStatus(record);
	}

	/**
	 * Builds the status record for every non-coinbase user transaction in a
	 * block WITHOUT writing, so callers can batch many blocks into a single
	 * chunked upsert. Coinbase/reward transactions are skipped.
	 */
	public static java.util.List<TransactionStatusRecord> collectBlock(Block block, TransactionStatus status,
			Long chainlength, NetworkParameters params) {
		java.util.List<TransactionStatusRecord> records = new java.util.ArrayList<>();
		if (block == null || block.getTransactions() == null) {
			return records;
		}
		long now = System.currentTimeMillis();
		for (Transaction tx : block.getTransactions()) {
			if (tx.isCoinBase() || tx.getInputs() == null || tx.getInputs().isEmpty()) {
				continue;
			}
			records.add(new TransactionStatusRecord(tx.getHash(), status, block.getHash(), chainlength,
					deriveAddress(tx, params), now, now));
		}
		return records;
	}

	/**
	 * Writes the given status for every non-coinbase user transaction in a
	 * block. Coinbase/reward transactions are skipped.
	 */
	public static void markBlock(BlockStoreInterface store, Block block, TransactionStatus status,
			Long chainlength, NetworkParameters params) throws net.bigtangle.exception.BlockStoreException {
		java.util.List<TransactionStatusRecord> records = collectBlock(block, status, chainlength, params);
		if (!records.isEmpty()) {
			store.upsertTransactionStatuses(records);
		}
	}

	/** Best-effort: address of the first output with a parseable script. */
	public static String deriveAddress(Transaction tx, NetworkParameters params) {
		List<TransactionOutput> outputs = tx.getOutputs();
		if (outputs != null) {
			for (TransactionOutput out : outputs) {
				try {
					byte[] scriptBytes = out.getScriptBytes();
					if (scriptBytes != null && scriptBytes.length > 0) {
						Script script = new Script(scriptBytes);
						return script.getToAddress(params).toBase58();
					}
				} catch (Exception e) {
					// not a standard pay-to-address output; try the next one
				}
			}
		}
		return null;
	}

	public Sha256Hash getTxHash() {
		return txHash;
	}

	public void setTxHash(Sha256Hash txHash) {
		this.txHash = txHash;
	}

	public TransactionStatus getStatus() {
		return status;
	}

	public void setStatus(TransactionStatus status) {
		this.status = status;
	}

	public Sha256Hash getBlockHash() {
		return blockHash;
	}

	public void setBlockHash(Sha256Hash blockHash) {
		this.blockHash = blockHash;
	}

	public Long getChainlength() {
		return chainlength;
	}

	public void setChainlength(Long chainlength) {
		this.chainlength = chainlength;
	}

	public String getAddress() {
		return address;
	}

	public void setAddress(String address) {
		this.address = address;
	}

	public long getCreatedTime() {
		return createdTime;
	}

	public void setCreatedTime(long createdTime) {
		this.createdTime = createdTime;
	}

	public long getUpdatedTime() {
		return updatedTime;
	}

	public void setUpdatedTime(long updatedTime) {
		this.updatedTime = updatedTime;
	}

	@Override
	public String toString() {
		return "TransactionStatusRecord [txHash=" + txHash + ", status=" + status + ", blockHash=" + blockHash
				+ ", chainlength=" + chainlength + ", address=" + address + ", createdTime=" + createdTime
				+ ", updatedTime=" + updatedTime + "]";
	}
}
