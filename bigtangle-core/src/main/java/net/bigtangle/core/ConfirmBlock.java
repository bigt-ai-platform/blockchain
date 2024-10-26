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

/*
  * Block output dynamic evaluation data
  */
public class ConfirmBlock extends DataClass {
	private Sha256Hash blockHash;
	private boolean confirmed;

	private long time;

	public void setDefault() {
		confirmed = false;

		time = System.currentTimeMillis() / 1000;

	}

	public void setBlockHashHex(String blockHashHex) {
		if (!Utils.isBlank(blockHashHex))
			this.blockHash = Sha256Hash.wrap(blockHashHex);
	}

	public String getBlockHashHex() {
		return this.blockHash != null ? Utils.HEX.encode(this.blockHash.getBytes()) : "";
	}

	public byte[] toByteArray() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			DataOutputStream dos = new DataOutputStream(baos);
			dos.write(super.toByteArray());
			Utils.writeNBytes(dos, blockHash == null ? Sha256Hash.ZERO_HASH.getBytes() : blockHash.getBytes());

			dos.writeBoolean(confirmed);
			dos.writeLong(time);
			dos.close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return baos.toByteArray();
	}

	@Override
	public ConfirmBlock parseDIS(DataInputStream dis) throws IOException {
		super.parseDIS(dis);
		blockHash = Sha256Hash.wrap(Utils.readNBytes(dis));
		confirmed = dis.readBoolean();
		confirmed = dis.readBoolean();

		time = dis.readLong();
		return this;
	}

	public ConfirmBlock parse(byte[] buf) throws IOException {
		ByteArrayInputStream bain = new ByteArrayInputStream(buf);
		DataInputStream dis = new DataInputStream(bain);
		parseDIS(dis);
		dis.close();
		bain.close();
		return this;
	}

	public Sha256Hash getBlockHash() {
		return blockHash;
	}

	public void setBlockHash(Sha256Hash blockHash) {
		this.blockHash = blockHash;
	}

	public boolean isConfirmed() {
		return confirmed;
	}

	public void setConfirmed(boolean confirmed) {
		this.confirmed = confirmed;
	}

	public long getTime() {
		return time;
	}

	public void setTime(long time) {
		this.time = time;
	}

}
