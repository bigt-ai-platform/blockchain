package net.bigtangle.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;

/**
 * Payload of an EVM transaction block on the Layer-1 contract chain
 * ({@code BLOCKTYPE_EVM_DEPLOY} / {@code BLOCKTYPE_EVM_CALL}).
 *
 * <p>Four modes, disambiguated by the fields:
 * <ul>
 * <li><b>deploy</b>: {@code to == null}, {@code data} non-empty (init code).</li>
 * <li><b>call</b>: {@code to != null}, {@code data} = calldata.</li>
 * <li><b>withdraw</b>: {@code withdraw == true}, {@code to == null} — moves
 * {@code value} from the sender's EVM balance back to the UTXO layer.</li>
 * <li><b>deposit</b>: otherwise ({@code to == null}, empty data) — moves
 * {@code value} from UTXO into the sender's EVM balance.</li>
 * </ul>
 *
 * <p>The {@code value} field also applies to calls (wei sent with the message)
 * and is always credited to the sender's EVM balance first (bridge-in).
 */
public class EVMTransactionInfo extends DataClass implements java.io.Serializable {

	private static final long serialVersionUID = 1L;

	private String contractTokenid;
	/** Base58 address of the UTXO sender; derives the EVM sender address. */
	private String fromAddress;
	/** Hex (40 chars) EVM recipient address, or null for deploy/withdraw/deposit. */
	private String to;
	/** Amount in the deposited UTXO token (deposit/call/withdraw). */
	private BigInteger value;
	/** Init code (deploy) or calldata (call); empty for deposit/withdraw. */
	private byte[] data;
	/** Gas limit for the EVM execution. */
	private long gasLimit;
	/** Gas price (in the native token). */
	private BigInteger gasPrice;
	/** EVM account nonce (calls/deploys only). */
	private long nonce;
	/** UTXO token id deposited into the EVM. */
	private String tokenid;
	/** Withdraw flag: move EVM balance back to the UTXO layer. */
	private boolean withdraw;

	public EVMTransactionInfo() {
		super();
	}

	public EVMTransactionInfo(String contractTokenid, String fromAddress, String to, BigInteger value, byte[] data,
			long gasLimit, BigInteger gasPrice, long nonce, String tokenid, boolean withdraw) {
		super();
		this.contractTokenid = contractTokenid;
		this.fromAddress = fromAddress;
		this.to = to;
		this.value = value;
		this.data = data == null ? new byte[0] : data.clone();
		this.gasLimit = gasLimit;
		this.gasPrice = gasPrice;
		this.nonce = nonce;
		this.tokenid = tokenid;
		this.withdraw = withdraw;
	}

	public boolean isDeploy() {
		return to == null && !withdraw && data != null && data.length > 0;
	}

	public boolean isCall() {
		return to != null && !withdraw;
	}

	public boolean isWithdraw() {
		return withdraw;
	}

	public boolean isDeposit() {
		return to == null && !withdraw && (data == null || data.length == 0);
	}

	public byte[] toByteArray() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			DataOutputStream dos = new DataOutputStream(baos);
			dos.write(super.toByteArray());
			Utils.writeNBytesString(dos, contractTokenid);
			Utils.writeNBytesString(dos, fromAddress);
			Utils.writeNBytesString(dos, to);
			Utils.writeNBytes(dos, value.toByteArray());
			Utils.writeNBytes(dos, data);
			dos.writeLong(gasLimit);
			Utils.writeNBytes(dos, gasPrice.toByteArray());
			dos.writeLong(nonce);
			Utils.writeNBytesString(dos, tokenid);
			dos.writeBoolean(withdraw);
			dos.close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return baos.toByteArray();
	}

	public EVMTransactionInfo parseDIS(DataInputStream dis) throws IOException {
		super.parseDIS(dis);
		contractTokenid = Utils.readNBytesString(dis);
		fromAddress = Utils.readNBytesString(dis);
		to = Utils.readNBytesString(dis);
		value = new BigInteger(Utils.readNBytes(dis));
		data = Utils.readNBytes(dis);
		gasLimit = dis.readLong();
		gasPrice = new BigInteger(Utils.readNBytes(dis));
		nonce = dis.readLong();
		tokenid = Utils.readNBytesString(dis);
		withdraw = dis.readBoolean();
		return this;
	}

	public EVMTransactionInfo parse(byte[] buf) throws IOException {
		ByteArrayInputStream bain = new ByteArrayInputStream(buf);
		DataInputStream dis = new DataInputStream(bain);
		parseDIS(dis);
		dis.close();
		bain.close();
		return this;
	}

	public EVMTransactionInfo parseChecked(byte[] buf) {
		try {
			return parse(buf);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public String getContractTokenid() {
		return contractTokenid;
	}

	public void setContractTokenid(String contractTokenid) {
		this.contractTokenid = contractTokenid;
	}

	public String getFromAddress() {
		return fromAddress;
	}

	public void setFromAddress(String fromAddress) {
		this.fromAddress = fromAddress;
	}

	public String getTo() {
		return to;
	}

	public void setTo(String to) {
		this.to = to;
	}

	public BigInteger getValue() {
		return value;
	}

	public void setValue(BigInteger value) {
		this.value = value;
	}

	public byte[] getData() {
		return data;
	}

	public void setData(byte[] data) {
		this.data = data;
	}

	public long getGasLimit() {
		return gasLimit;
	}

	public void setGasLimit(long gasLimit) {
		this.gasLimit = gasLimit;
	}

	public BigInteger getGasPrice() {
		return gasPrice;
	}

	public void setGasPrice(BigInteger gasPrice) {
		this.gasPrice = gasPrice;
	}

	public long getNonce() {
		return nonce;
	}

	public void setNonce(long nonce) {
		this.nonce = nonce;
	}

	public String getTokenid() {
		return tokenid;
	}

	public void setTokenid(String tokenid) {
		this.tokenid = tokenid;
	}

	public boolean isWithdrawFlag() {
		return withdraw;
	}

	public void setWithdraw(boolean withdraw) {
		this.withdraw = withdraw;
	}

	@Override
	public String toString() {
		return "EVMTransactionInfo [contractTokenid=" + contractTokenid + ", fromAddress=" + fromAddress + ", to=" + to
				+ ", value=" + value + ", gasLimit=" + gasLimit + ", gasPrice=" + gasPrice + ", nonce=" + nonce
				+ ", tokenid=" + tokenid + ", withdraw=" + withdraw + ", dataLen=" + (data == null ? 0 : data.length)
				+ "]";
	}
}
