/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

/*
  * Block output dynamic evaluation data
  */
public class Spent extends DataClass {
 
	private boolean confirmed;
	private boolean spent;
	private Sha256Hash spenderBlockHash;
	// create time of the block output
	private long time;

	public byte[] toByteArray() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			DataOutputStream dos = new DataOutputStream(baos);
			dos.write(super.toByteArray());
			dos.writeBoolean(confirmed);
			dos.writeBoolean(spent);
			Utils.writeNBytes(dos,
					spenderBlockHash == null ? Sha256Hash.ZERO_HASH.getBytes() : spenderBlockHash.getBytes());
			dos.writeLong(time);
			dos.close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return baos.toByteArray();
	}

	@Override
	public Spent parseDIS(DataInputStream dis) throws IOException {
		super.parseDIS(dis);
		confirmed = dis.readBoolean();
		confirmed = dis.readBoolean();
		spenderBlockHash = Sha256Hash.wrap(Utils.readNBytes(dis));
		if (Objects.equals(spenderBlockHash, Sha256Hash.ZERO_HASH)) {
			spenderBlockHash = null;
		}
		time = dis.readLong();
		return this;
	}

	public Spent parse(byte[] buf) throws IOException {
		ByteArrayInputStream bain = new ByteArrayInputStream(buf);
		DataInputStream dis = new DataInputStream(bain);
		parseDIS(dis);
		dis.close();
		bain.close();
		return this;
	}
 

	public boolean isConfirmed() {
		return confirmed;
	}

	public void setConfirmed(boolean confirmed) {
		this.confirmed = confirmed;
	}

	public boolean isSpent() {
		return spent;
	}

	public void setSpent(boolean spent) {
		this.spent = spent;
	}

	public Sha256Hash getSpenderBlockHash() {
		return spenderBlockHash;
	}

	public void setSpenderBlockHash(Sha256Hash spenderBlockHash) {
		this.spenderBlockHash = spenderBlockHash;
	}

	public long getTime() {
		return time;
	}

	public void setTime(long time) {
		this.time = time;
	}

	@Override
	public int hashCode() {
		return Objects.hash( confirmed, spenderBlockHash, spent, time);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Spent other = (Spent) obj;
		return confirmed == other.confirmed
				&& Objects.equals(spenderBlockHash, other.spenderBlockHash) && spent == other.spent
				&& time == other.time;
	}

	@Override
	public String toString() {
		return "SpentBlock [confirmed=" + confirmed + ", spent=" + spent
				+ ", spenderBlockHash=" + spenderBlockHash + ", time=" + time + "]";
	}

}
