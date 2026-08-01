package net.bigtangle.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

public class TXReward extends SpentBlock implements java.io.Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	private Sha256Hash prevBlockHash;

	private long chainLength;

	// this is for json
	public TXReward() {

	}

	public TXReward(Sha256Hash hash, boolean confirmed, boolean spent, Sha256Hash prevBlockHash,
			Sha256Hash spenderblockhash, long chainLength) {
		super();
		this.setBlockHash(hash);
		this.setConfirmed(confirmed);
		this.setSpent(spent);
		this.prevBlockHash = prevBlockHash;
		this.setSpenderBlockHash(spenderblockhash);
		this.chainLength = chainLength;
	}

	public byte[] toByteArray() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			DataOutputStream dos = new DataOutputStream(baos);
			dos.write(super.toByteArray());
			Utils.writeNBytes(dos, prevBlockHash.getBytes());
			dos.writeLong(chainLength);

			dos.close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return baos.toByteArray();
	}

	@Override
	public TXReward parseDIS(DataInputStream dis) throws IOException {
		super.parseDIS(dis);

		prevBlockHash = Sha256Hash.wrap(Utils.readNBytes(dis));

		chainLength = dis.readLong();

		return this;
	}

	public TXReward parse(byte[] buf) throws IOException {
		ByteArrayInputStream bain = new ByteArrayInputStream(buf);
		DataInputStream dis = new DataInputStream(bain);
		parseDIS(dis);
		dis.close();
		bain.close();
		return this;
	}
  

	public long getChainLength() {
		return chainLength;
	}

	public void setChainLength(long chainLength) {
		this.chainLength = chainLength;
	}

	public Sha256Hash getPrevBlockHash() {
		return prevBlockHash;
	}

	public void setPrevBlockHash(Sha256Hash prevBlockHash) {
		this.prevBlockHash = prevBlockHash;
	}

	@Override
	public String toString() {
		return "TXReward [prevBlockHash=" + prevBlockHash + ", \n chainLength=" + chainLength + "]";
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result + Objects.hash(chainLength, prevBlockHash);
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (getClass() != obj.getClass())
			return false;
		TXReward other = (TXReward) obj;
		return chainLength == other.chainLength
				&& Objects.equals(prevBlockHash, other.prevBlockHash);
	}

}
