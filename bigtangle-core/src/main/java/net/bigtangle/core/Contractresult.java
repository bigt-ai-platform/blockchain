package net.bigtangle.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class Contractresult extends SpentBlock implements java.io.Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	private Sha256Hash prevblockhash;

	private byte[] contractExecutionResult;
	private String contracttokenid;
	private long milestone;

	// this is for json
	public Contractresult() {

	}

	public Contractresult(Sha256Hash hash, boolean confirmed, boolean spent, Sha256Hash prevBlockHash,
			Sha256Hash spenderblockhash, byte[] contractExecutionResult, String contracttokenid, long milestone,
			long inserttime) {
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
	}

	public static Contractresult firstContractresult() {
		return new Contractresult(Sha256Hash.ZERO_HASH, false, false, null, null, null, null, -1, 0L);
	}

	public byte[] toByteArray() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			DataOutputStream dos = new DataOutputStream(baos);
			dos.write(super.toByteArray());
			Utils.writeNBytes(dos, prevblockhash.getBytes());
			Utils.writeNBytes(dos, contractExecutionResult);
			Utils.writeNBytesString(dos, contracttokenid);
			dos.writeLong(milestone);
			dos.close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return baos.toByteArray();
	}

	@Override
	public Contractresult parseDIS(DataInputStream dis) throws IOException {
		super.parseDIS(dis);

		prevblockhash = Sha256Hash.wrap(Utils.readNBytes(dis));
		contractExecutionResult = Utils.readNBytes(dis);

		contracttokenid = Utils.readNBytesString(dis);
		milestone = dis.readLong();
		return this;
	}

	public Contractresult parse(byte[] buf) {
		try {
			ByteArrayInputStream bain = new ByteArrayInputStream(buf);
			DataInputStream dis = new DataInputStream(bain);
			parseDIS(dis);
			dis.close();
			bain.close();
			return this;
		} catch (IOException e) {
			// Cannot happen since checked before
			throw new RuntimeException(e);
		}
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

	@Override
	public String toString() {
		return "Contractresult [prevblockhash=" + prevblockhash + ", contracttokenid=" + contracttokenid
				+ ", milestone=" + milestone + "]";
	}

}
