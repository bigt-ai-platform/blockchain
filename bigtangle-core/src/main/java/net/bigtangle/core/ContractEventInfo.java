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
import java.math.BigInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContractEventInfo extends DataClass implements java.io.Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private static final Logger log = LoggerFactory.getLogger(ContractEventInfo.class);

	private String beneficiaryAddress;

	private BigInteger offerValue;
	private String offerTokenid;

	private String contractTokenid;
	private String offerSystem;

	public ContractEventInfo() {
		super();
	}

	public ContractEventInfo(String contractTokenid, BigInteger offerValue, String offerTokenid,
			String beneficiaryAddress, Long validToTimeMilli, Long validFromTimeMilli, String offerSystem) {
		super();
		this.contractTokenid = contractTokenid;

		this.beneficiaryAddress = beneficiaryAddress;

		this.offerValue = offerValue;
		this.offerTokenid = offerTokenid;
	}

	public byte[] toByteArray() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			DataOutputStream dos = new DataOutputStream(baos);

			dos.write(super.toByteArray());

			Utils.writeNBytesString(dos, beneficiaryAddress);

			Utils.writeNBytesString(dos, offerTokenid);
			Utils.writeNBytesString(dos, contractTokenid);
			Utils.writeNBytesString(dos, offerSystem);

			Utils.writeNBytes(dos, offerValue.toByteArray());
			dos.close();
		} catch (IOException e) {
			log.error("Failed to serialize ContractEventInfo", e);
		}
		return baos.toByteArray();
	}

	public ContractEventInfo parseDIS(DataInputStream dis) throws IOException {
		super.parseDIS(dis);

		beneficiaryAddress = Utils.readNBytesString(dis);
		offerTokenid = Utils.readNBytesString(dis);
		contractTokenid = Utils.readNBytesString(dis);
		offerSystem = Utils.readNBytesString(dis);
		offerValue = new BigInteger(Utils.readNBytes(dis));
		return this;
	}

	public ContractEventInfo parse(byte[] buf) throws IOException {
		ByteArrayInputStream bain = new ByteArrayInputStream(buf);
		DataInputStream dis = new DataInputStream(bain);

		parseDIS(dis);

		dis.close();
		bain.close();
		return this;
	}

	public ContractEventInfo parseChecked(byte[] buf) throws IOException {
		try {
			return parse(buf);
		} catch (IOException e) {
			throw new RuntimeException();
		}
	}

	public String getBeneficiaryAddress() {
		return beneficiaryAddress;
	}

	public void setBeneficiaryAddress(String beneficiaryAddress) {
		this.beneficiaryAddress = beneficiaryAddress;
	}

	public BigInteger getOfferValue() {
		return offerValue;
	}

	public void setOfferValue(BigInteger offerValue) {
		this.offerValue = offerValue;
	}

	public String getOfferTokenid() {
		return offerTokenid;
	}

	public void setOfferTokenid(String offerTokenid) {
		this.offerTokenid = offerTokenid;
	}

	public String getContractTokenid() {
		return contractTokenid;
	}

	public void setContractTokenid(String contractTokenid) {
		this.contractTokenid = contractTokenid;
	}

	public String getOfferSystem() {
		return offerSystem;
	}

	public void setOfferSystem(String offerSystem) {
		this.offerSystem = offerSystem;
	}

	@Override
	public String toString() {
		return "ContractEventInfo [beneficiaryAddress=" + beneficiaryAddress + ", offerValue=" + offerValue
				+ ", offerTokenid=" + offerTokenid + ", contractTokenid=" + contractTokenid + ", offerSystem="
				+ offerSystem + "]";
	}

 
}
