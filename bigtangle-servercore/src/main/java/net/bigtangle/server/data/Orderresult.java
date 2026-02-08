package net.bigtangle.server.data;

import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.SpentBlock;

public class Orderresult extends SpentBlock implements java.io.Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	private Sha256Hash prevblockhash;

	private byte[] orderExecutionResult;
	
	private long milestone;
	private long chainlength;
	
	// this is for json
	public Orderresult() {

	}

	public Orderresult(Sha256Hash hash, boolean confirmed, boolean spent, Sha256Hash prevBlockHash,
			Sha256Hash spenderblockhash, byte[] orderExecutionResult, long milestone, long chainlength,  long inserttime ) {
		super();
		this.setBlockHash(hash);
		this.setConfirmed(confirmed);
		this.setSpent(spent);
		this.setTime(inserttime); 
		this.prevblockhash = prevBlockHash;
		this.setSpenderBlockHash(spenderblockhash);
		this.orderExecutionResult = orderExecutionResult;

		this.milestone = milestone;	
		this. chainlength= chainlength;
	}

	public static Orderresult zeroOrderresult( ) {
	 return new Orderresult(Sha256Hash.ZERO_HASH, false, false, null, null, null,  -1,0,0L);
	}
 
	public Sha256Hash getPrevblockhash() {
		return prevblockhash;
	}

	public void setPrevblockhash(Sha256Hash prevblockhash) {
		this.prevblockhash = prevblockhash;
	}
 

	 

	public byte[] getOrderExecutionResult() {
		return orderExecutionResult;
	}

	public void setOrderExecutionResult(byte[] orderExecutionResult) {
		this.orderExecutionResult = orderExecutionResult;
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
		return super.toString()+ "prevblockhash=" + prevblockhash + ", milestone=" + milestone+ ", chainlength=" + chainlength;
	}
  

}
