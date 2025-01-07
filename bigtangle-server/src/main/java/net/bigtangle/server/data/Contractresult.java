package net.bigtangle.server.data;

import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.SpentBlock;

public class Contractresult extends SpentBlock implements java.io.Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	private Sha256Hash prevblockhash;

	private byte[] contractExecutionResult;
	private String contracttokenid;
	private long milestone;
	private long chainlength;

	// this is for json
	public Contractresult() {

	}

	public Contractresult(Sha256Hash hash, boolean confirmed, boolean spent, Sha256Hash prevBlockHash,
			Sha256Hash spenderblockhash, byte[] contractExecutionResult, String contracttokenid, long milestone,
			long chainlength, long inserttime) {
		super();
		this.setBlockHash(hash);
		this.setConfirmed(confirmed);
		this.setSpent(spent);
		this.setTime(inserttime);
		this.prevblockhash = prevBlockHash;
		this.setSpenderBlockHash(spenderblockhash);
		this.contractExecutionResult = contractExecutionResult;

		this.contracttokenid = contracttokenid;
		this.milestone = milestone;
		this.chainlength = chainlength;
	}

	public static Contractresult firstContractresult() {
		return new Contractresult(Sha256Hash.ZERO_HASH, false, false, null, null, null, null, -1, 0, 0L);
	}

	public Sha256Hash getPrevblockhash() {
		return prevblockhash;
	}

	public void setPrevblockhash(Sha256Hash prevblockhash) {
		this.prevblockhash = prevblockhash;
	}

	public byte[] getContractExecutionResult() {
		return contractExecutionResult;
	}

	public void setContractExecutionResult(byte[] contractExecutionResult) {
		this.contractExecutionResult = contractExecutionResult;
	}

	public String getContracttokenid() {
		return contracttokenid;
	}

	public void setContracttokenid(String contracttokenid) {
		this.contracttokenid = contracttokenid;
	}

	public long getMilestone() {
		return milestone;
	}

	public void setMilestone(long milestone) {
		this.milestone = milestone;
	}

	public long getChainlength() {
		return chainlength;
	}

	public void setChainlength(long chainlength) {
		this.chainlength = chainlength;
	}

	@Override
	public String toString() {
		return super.toString() + " [prevblockhash=" + prevblockhash + ", contracttokenid=" + contracttokenid
				+ ", milestone=" + milestone + ", chainlength=" + chainlength + "]";
	}

}
