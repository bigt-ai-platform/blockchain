package net.bigtangle.evm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * An EVM log entry emitted by {@code LOG0..LOG4}. Topics and data are fixed
 * width so the entry is fully deterministic.
 */
public final class EVMLog {

	private final Address address;
	private final List<Word> topics;
	private final byte[] data;

	public EVMLog(Address address, List<Word> topics, byte[] data) {
		this.address = address;
		this.topics = topics;
		this.data = data.clone();
	}

	public Address getAddress() {
		return address;
	}

	public List<Word> getTopics() {
		return topics;
	}

	public byte[] getData() {
		return data;
	}

	public byte[] toByteArray() {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			DataOutputStream dos = new DataOutputStream(baos);
			dos.write(address.toBytes());
			dos.writeInt(topics.size());
			for (Word topic : topics) {
				dos.write(topic.toBytes());
			}
			dos.writeInt(data.length);
			dos.write(data);
			dos.flush();
			return baos.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static EVMLog parse(byte[] bytes) {
		try {
			DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
			byte[] addressBytes = new byte[Address.LENGTH];
			dis.readFully(addressBytes);
			Address address = new Address(addressBytes);
			int topicCount = dis.readInt();
			List<Word> topics = new ArrayList<>();
			for (int i = 0; i < topicCount; i++) {
				byte[] topic = new byte[32];
				dis.readFully(topic);
				topics.add(Word.fromBytes(topic));
			}
			int dataLen = dis.readInt();
			byte[] data = new byte[dataLen];
			dis.readFully(data);
			dis.close();
			return new EVMLog(address, topics, data);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
