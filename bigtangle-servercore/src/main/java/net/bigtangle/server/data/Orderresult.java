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
	
	private long rewardchainlength;
	private long chainlength;
	
	// this is for json
	public Orderresult() {

	}

	public Orderresult(Sha256Hash hash, boolean confirmed, boolean spent, Sha256Hash prevBlockHash,
			Sha256Hash spenderblockhash, byte[] orderExecutionResult, long rewardchainlength, long chainlength,  long inserttime ) {
		super();
		this.setBlockHash(hash);
		this.setConfirmed(confirmed);
		this.setSpent(spent);
		this.setTime(inserttime); 
		this.prevblockhash = prevBlockHash;
		this.setSpenderBlockHash(spenderblockhash);
		this.orderExecutionResult = orderExecutionResult;

		this.rewardchainlength = rewardchainlength;	
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
 
	public long getRewardchainlength() {
		return rewardchainlength;
	}

	public void setRewardchainlength(long rewardchainlength) {
		this.rewardchainlength = rewardchainlength;
	}

	public long getChainlength() {
		return chainlength;
	}

	public void setChainlength(long chainlength) {
		this.chainlength = chainlength;
	}

	@Override
	public String toString() {
		return super.toString()+ "prevblockhash=" + prevblockhash + ", rewardchainlength=" + rewardchainlength+ ", chainlength=" + chainlength;
	}
  

}
