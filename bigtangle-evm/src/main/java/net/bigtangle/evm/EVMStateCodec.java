package net.bigtangle.evm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;

/**
 * Deterministic binary serialization of a {@link WorldState}, used to persist
 * the EVM world state snapshot inside a contract execution result. Accounts are
 * written in sorted-address order and storage in sorted-key order so the bytes
 * are identical on every node.
 */
public final class EVMStateCodec {

	private EVMStateCodec() {
	}

	public static byte[] serialize(WorldState worldState) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			DataOutputStream dos = new DataOutputStream(baos);
			dos.writeInt(worldState.accountCount());
			for (Address address : worldState.addresses()) {
				EVMAccount account = worldState.getAccount(address);
				dos.write(address.toBytes());
				dos.writeLong(account.getNonce());
				writeWord32(dos, account.getBalance());
				byte[] code = account.getCode();
				dos.writeInt(code.length);
				dos.write(code);
				EVMStorage storage = worldState.getStorage(address);
				dos.writeInt(storage.size());
				for (java.util.Map.Entry<Word, Word> e : storage.entries()) {
					dos.write(e.getKey().toBytes());
					dos.write(e.getValue().toBytes());
				}
			}
			dos.flush();
			return baos.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static WorldState deserialize(byte[] bytes) {
		WorldState worldState = new WorldState();
		try {
			DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
			int accountCount = dis.readInt();
			for (int i = 0; i < accountCount; i++) {
				byte[] addressBytes = new byte[Address.LENGTH];
				dis.readFully(addressBytes);
				Address address = new Address(addressBytes);
				long nonce = dis.readLong();
				BigInteger balance = new BigInteger(readWord32(dis));
				int codeLen = dis.readInt();
				byte[] code = new byte[codeLen];
				dis.readFully(code);
				EVMAccount account = new EVMAccount(address, nonce, balance, code);
				worldState.setAccount(account);
				int slotCount = dis.readInt();
				for (int j = 0; j < slotCount; j++) {
					byte[] key = new byte[32];
					byte[] value = new byte[32];
					dis.readFully(key);
					dis.readFully(value);
					worldState.getStorage(address).put(Word.fromBytes(key), Word.fromBytes(value));
				}
			}
			dis.close();
			return worldState;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static void writeWord32(DataOutputStream dos, BigInteger value) throws IOException {
		byte[] out = new byte[32];
		byte[] raw = value.toByteArray();
		if (raw.length >= 32) {
			System.arraycopy(raw, raw.length - 32, out, 0, 32);
		} else {
			System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
		}
		dos.write(out);
	}

	private static byte[] readWord32(DataInputStream dis) throws IOException {
		byte[] out = new byte[32];
		dis.readFully(out);
		return out;
	}
}
