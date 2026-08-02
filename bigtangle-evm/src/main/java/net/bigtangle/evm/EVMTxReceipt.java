package net.bigtangle.evm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Receipt for one EVM transaction in a batch: execution status, gas, emitted
 * logs and (for creations) the deployed contract address.
 */
public final class EVMTxReceipt {

	public static final int STATUS_SUCCESS = 1;
	public static final int STATUS_FAILURE = 0;

	private final int status;
	private final long gasUsed;
	private final long cumulativeGasUsed;
	private final List<EVMLog> logs;
	private final Address contractAddress;
	private final byte[] returnData;
	private final Address from;
	private final Address to;

	public EVMTxReceipt(int status, long gasUsed, long cumulativeGasUsed, List<EVMLog> logs, Address contractAddress,
			byte[] returnData, Address from, Address to) {
		this.status = status;
		this.gasUsed = gasUsed;
		this.cumulativeGasUsed = cumulativeGasUsed;
		this.logs = new ArrayList<>(logs);
		this.contractAddress = contractAddress;
		this.returnData = returnData == null ? new byte[0] : returnData.clone();
		this.from = from;
		this.to = to;
	}

	public byte[] toByteArray() {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			DataOutputStream dos = new DataOutputStream(baos);
			dos.writeInt(status);
			dos.writeLong(gasUsed);
			dos.writeLong(cumulativeGasUsed);
			dos.writeInt(logs.size());
			for (EVMLog log : logs) {
				byte[] logBytes = log.toByteArray();
				dos.writeInt(logBytes.length);
				dos.write(logBytes);
			}
			dos.writeBoolean(contractAddress != null);
			if (contractAddress != null) {
				dos.write(contractAddress.toBytes());
			}
			dos.writeInt(returnData.length);
			dos.write(returnData);
			dos.write(from.toBytes());
			dos.writeBoolean(to != null);
			if (to != null) {
				dos.write(to.toBytes());
			}
			dos.flush();
			return baos.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static EVMTxReceipt parse(byte[] bytes) {
		try {
			DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
			int status = dis.readInt();
			long gasUsed = dis.readLong();
			long cumulativeGasUsed = dis.readLong();
			int logCount = dis.readInt();
			List<EVMLog> logs = new ArrayList<>();
			for (int i = 0; i < logCount; i++) {
				int len = dis.readInt();
				byte[] logBytes = new byte[len];
				dis.readFully(logBytes);
				logs.add(EVMLog.parse(logBytes));
			}
			Address contractAddress = null;
			if (dis.readBoolean()) {
				byte[] addr = new byte[Address.LENGTH];
				dis.readFully(addr);
				contractAddress = new Address(addr);
			}
			int returnLen = dis.readInt();
			byte[] returnData = new byte[returnLen];
			dis.readFully(returnData);
			byte[] fromBytes = new byte[Address.LENGTH];
			dis.readFully(fromBytes);
			Address from = new Address(fromBytes);
			Address to = null;
			if (dis.readBoolean()) {
				byte[] addr = new byte[Address.LENGTH];
				dis.readFully(addr);
				to = new Address(addr);
			}
			dis.close();
			return new EVMTxReceipt(status, gasUsed, cumulativeGasUsed, logs, contractAddress, returnData, from, to);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public int getStatus() {
		return status;
	}

	public boolean isSuccess() {
		return status == STATUS_SUCCESS;
	}

	public long getGasUsed() {
		return gasUsed;
	}

	public long getCumulativeGasUsed() {
		return cumulativeGasUsed;
	}

	public List<EVMLog> getLogs() {
		return logs;
	}

	public Address getContractAddress() {
		return contractAddress;
	}

	public byte[] getReturnData() {
		return returnData;
	}

	public Address getFrom() {
		return from;
	}

	public Address getTo() {
		return to;
	}
}
