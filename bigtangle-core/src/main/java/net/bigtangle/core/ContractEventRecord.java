/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.core;

import java.math.BigInteger;
import java.util.Objects;

public class ContractEventRecord extends SpentBlock {

	private static final long serialVersionUID = -2331665478149550684L;

	private Sha256Hash collectinghash;
	private String contractTokenid;
	private BigInteger targetValue;
	private String targetTokenid;
	// owner public address of the order for query
	private String beneficiaryAddress;

	public ContractEventRecord() {
	}

	public ContractEventRecord(Sha256Hash initialBlockHash, Sha256Hash collectinghash, String contractTokenid,
			boolean confirmed, boolean spent, Sha256Hash spenderBlockHash, BigInteger targetValue, String targetTokenid,
			String beneficiaryAddress) {
		super();
		this.setBlockHash(initialBlockHash);
		this.collectinghash = collectinghash;
		this.setConfirmed(confirmed);
		this.setSpent(spent);
		this.setSpenderBlockHash(spenderBlockHash);
		this.targetValue = targetValue;
		this.targetTokenid = targetTokenid;

		this.beneficiaryAddress = beneficiaryAddress;
		this.contractTokenid = contractTokenid;
	}

	public static ContractEventRecord cloneOrderRecord(ContractEventRecord old) {
		return new ContractEventRecord(old.getBlockHash(), old.getCollectinghash(), old.contractTokenid,
				old.isConfirmed(), old.isSpent(), old.getSpenderBlockHash(), old.targetValue, old.targetTokenid,
				old.beneficiaryAddress);
	}

	public BigInteger getTargetValue() {
		return targetValue;
	}

	public void setTargetValue(BigInteger targetValue) {
		this.targetValue = targetValue;
	}

	public String getTargetTokenid() {
		return targetTokenid;
	}

	public void setTargetTokenid(String targetTokenid) {
		this.targetTokenid = targetTokenid;
	}

	public static long getSerialversionuid() {
		return serialVersionUID;
	}

	public String getBeneficiaryAddress() {
		return beneficiaryAddress;
	}

	public void setBeneficiaryAddress(String beneficiaryAddress) {
		this.beneficiaryAddress = beneficiaryAddress;
	}

	public String getContractTokenid() {
		return contractTokenid;
	}

	public void setContractTokenid(String contractTokenid) {
		this.contractTokenid = contractTokenid;
	}

	public Sha256Hash getCollectinghash() {
		return collectinghash;
	}

	public void setCollectinghash(Sha256Hash collectinghash) {
		this.collectinghash = collectinghash;
	}

	@Override
	public String toString() {
		return "ContractEventRecord [  beneficiaryAddress=" + beneficiaryAddress + ", collectinghash=" + collectinghash
				+ ", contractTokenid=" + contractTokenid + ", targetValue=" + targetValue + ", targetTokenid="
				+ targetTokenid 
				+super.toString() + "]";
	}

}
