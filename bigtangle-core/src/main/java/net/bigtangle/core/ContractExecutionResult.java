package net.bigtangle.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/*
 * Contract Execution provide the results from the execution based on prev results.
 * It must be check on every node and should be the same result.
 * The data is saved in table ContractResult mainly as byte.
 */
public class ContractExecutionResult extends Spent {

	String contracttokenid;
	// reference the previous ContractResult block, it forms a chain
	Sha256Hash prevblockhash;
	private long chainlength;
	// referenced new order blocks
	Set<Sha256Hash> referencedBlocks = new HashSet<>();

    // this ContractResult produces coinbase outputTxHash
	Sha256Hash outputTxHash;
 
	// the cancelled records referenced by this ContractResult
	Set<Sha256Hash> cancelRecords = new HashSet<>();
	// remainder Record is open records after execution
	Set<Sha256Hash> remainderRecords = new HashSet<>();
	// allRecords (this execution) = newRecords (this execution) + remainderRecords
	// (previous execution)

	// not part of toArray, not persistent, but data after the check
	// with re calculation to save
	Transaction outputTx;
	Set<ContractEventRecord> remainderContractEventRecord;

	// optional engine-specific payload (e.g. the serialized EVM world state
	// snapshot produced by the EVM contract engine), persisted with the result
	byte[] extraData;
 
	
	public ContractExecutionResult() {

	}

	public ContractExecutionResult( String contractid,  
			Sha256Hash outputTxHash, Transaction outputTx, Sha256Hash prevblockhash,  
			Set<Sha256Hash> cancelRecords, Set<Sha256Hash> remainderRecords, long inserttime,
			Set<ContractEventRecord> remainderContractEventRecord, 
			  Set<Sha256Hash> referencedOrderBlocks,  long chainlength) {
		this.contracttokenid = contractid;
		this.prevblockhash = prevblockhash;
		this.outputTxHash = outputTxHash;
		this.outputTx = outputTx;
 
		this.cancelRecords = cancelRecords;
		this.remainderRecords = remainderRecords;
		this.setTime(inserttime);

		this.remainderContractEventRecord = remainderContractEventRecord; 
		
		this.referencedBlocks = referencedOrderBlocks;
		this.chainlength = chainlength;
	}

	public byte[] toByteArray() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			DataOutputStream dos = new DataOutputStream(baos);
			dos.write(super.toByteArray());
			Utils.writeNBytesString(dos, contracttokenid);
			Utils.writeNBytes(dos, outputTxHash.getBytes());
			Utils.writeNBytes(dos, prevblockhash.getBytes());
			Utils.writeLong(dos, chainlength); 

			dos.writeInt(cancelRecords.size());
			for (Sha256Hash c : cancelRecords) {
				Utils.writeNBytes(dos, c.getBytes());
			}
			dos.writeInt(remainderRecords.size());
			for (Sha256Hash c : remainderRecords) {
				Utils.writeNBytes(dos, c.getBytes());
			}
			dos.writeInt(referencedBlocks.size());
			for (Sha256Hash c : referencedBlocks) {
				Utils.writeNBytes(dos, c.getBytes());
			}

			Utils.writeNBytes(dos, extraData);

			dos.close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return baos.toByteArray();
	}

	@Override
	public ContractExecutionResult parseDIS(DataInputStream dis) throws IOException {
		super.parseDIS(dis);
		contracttokenid = Utils.readNBytesString(dis);
		outputTxHash = Sha256Hash.wrap(Utils.readNBytes(dis));
		prevblockhash = Sha256Hash.wrap(Utils.readNBytes(dis));
		chainlength =  Utils.readLong(dis);
 
		cancelRecords = new HashSet<>();
		int cancelRecordsSize = dis.readInt();
		for (int i = 0; i < cancelRecordsSize; i++) {
			cancelRecords.add(Sha256Hash.wrap(Utils.readNBytes(dis)));
		}
		remainderRecords = new HashSet<>();
		int remainderRecordsSize = dis.readInt();
		for (int i = 0; i < remainderRecordsSize; i++) {
			remainderRecords.add(Sha256Hash.wrap(Utils.readNBytes(dis)));
		}
		int blocksSize = dis.readInt();
		referencedBlocks = new HashSet<>();
		for (int i = 0; i < blocksSize; i++) {
			referencedBlocks.add(Sha256Hash.wrap(Utils.readNBytes(dis)));
		}

		extraData = Utils.readNBytes(dis);

		return this;
	}

	public ContractExecutionResult parseChecked(byte[] buf) {
		try {
			return parse(buf);
		} catch (IOException e) {
			// Cannot happen since checked before
			throw new RuntimeException(e);
		}
	}

	public ContractExecutionResult parse(byte[] buf) throws IOException {
		ByteArrayInputStream bain = new ByteArrayInputStream(buf);
		DataInputStream dis = new DataInputStream(bain);
		parseDIS(dis);
		dis.close();
		bain.close();
		return this;
	}

	public Sha256Hash getOutputTxHash() {
		return outputTxHash;
	}

	public Transaction getOutputTx() {
		return outputTx;
	}

	public void setOutputTx(Transaction outputTx) {
		this.outputTx = outputTx;
	}

	public String getContracttokenid() {
		return contracttokenid;
	}

	public void setContracttokenid(String contracttokenid) {
		this.contracttokenid = contracttokenid;
	}

	public Sha256Hash getPrevblockhash() {
		return prevblockhash;
	}

	public void setPrevblockhash(Sha256Hash prevblockhash) {
		this.prevblockhash = prevblockhash;
	}


	public Set<Sha256Hash> getCancelRecords() {
		return cancelRecords;
	}

	public void setCancelRecords(Set<Sha256Hash> cancelRecords) {
		this.cancelRecords = cancelRecords;
	}

	public Set<Sha256Hash> getRemainderRecords() {
		return remainderRecords;
	}

	public void setRemainderRecords(Set<Sha256Hash> remainderRecords) {
		this.remainderRecords = remainderRecords;
	}

	public Set<ContractEventRecord> getRemainderContractEventRecord() {
		return remainderContractEventRecord;
	}

	public void setRemainderContractEventRecord(Set<ContractEventRecord> remainderContractEventRecord) {
		this.remainderContractEventRecord = remainderContractEventRecord;
	}

	public Set<Sha256Hash> getReferencedBlocks() {
		return referencedBlocks;
	}

	public byte[] getExtraData() {
		return extraData;
	}

	public void setExtraData(byte[] extraData) {
		this.extraData = extraData;
	}

	public void setReferencedBlocks(Set<Sha256Hash> referencedBlocks) {
		this.referencedBlocks = referencedBlocks;
	}

 
	public long getChainlength() {
		return chainlength;
	}

	public void setChainlength(long chainlength) {
		this.chainlength = chainlength;
	}

	@Override
	public String toString() {
		return " [contracttokenid=" + contracttokenid + ", prevblockhash=" + prevblockhash
				+  ", referencedBlocks=" + referencedBlocks
				+ ", outputTxHash=" + outputTxHash  + ", cancelRecords=" + cancelRecords
				+ ", remainderRecords=" + remainderRecords + ", outputTx=" + outputTx
				+ ", remainderContractEventRecord=" + remainderContractEventRecord+ ", chainlength=" + chainlength + "]";
	}
 

}