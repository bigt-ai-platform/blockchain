package net.bigtangle.response;

import java.util.List;

/**
 * Transaction lifecycle status for a user: where the transaction is in the
 * mempool → batch block → solid → beacon-confirmed (chainlength)
 * → dropped/re-mempooled lifecycle.
 */
public class GetTransactionStatusResponse extends AbstractResponse {

	private String txHash;
	private String status;
	private String blockHash;
	private Long chainlength;
	private String address;
	private long createdTime;
	private long updatedTime;

	public static GetTransactionStatusResponse create(String txHash, String status, String blockHash,
			Long chainlength, String address, long createdTime, long updatedTime) {
		GetTransactionStatusResponse res = new GetTransactionStatusResponse();
		res.txHash = txHash;
		res.status = status;
		res.blockHash = blockHash;
		res.chainlength = chainlength;
		res.address = address;
		res.createdTime = createdTime;
		res.updatedTime = updatedTime;
		return res;
	}

	public static GetTransactionStatusResponse createEmpty(String txHash) {
		GetTransactionStatusResponse res = new GetTransactionStatusResponse();
		res.txHash = txHash;
		res.status = "UNKNOWN";
		return res;
	}

	public static class GetTransactionsStatusResponse extends AbstractResponse {
		private List<GetTransactionStatusResponse> transactions;

		public static GetTransactionsStatusResponse create(List<GetTransactionStatusResponse> transactions) {
			GetTransactionsStatusResponse res = new GetTransactionsStatusResponse();
			res.transactions = transactions;
			return res;
		}

		public List<GetTransactionStatusResponse> getTransactions() {
			return transactions;
		}

		public void setTransactions(List<GetTransactionStatusResponse> transactions) {
			this.transactions = transactions;
		}
	}

	public String getTxHash() {
		return txHash;
	}

	public void setTxHash(String txHash) {
		this.txHash = txHash;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getBlockHash() {
		return blockHash;
	}

	public void setBlockHash(String blockHash) {
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
}
