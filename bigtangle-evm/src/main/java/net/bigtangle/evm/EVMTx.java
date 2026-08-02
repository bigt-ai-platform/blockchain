package net.bigtangle.evm;

import java.math.BigInteger;

/**
 * An EVM transaction fed to {@link EVMTxProcessor}. Signature verification and
 * the UTXO↔EVM bridge are wired up in later phases; here the sender is supplied
 * directly.
 */
public final class EVMTx {

	private final Address sender;
	private final Address to; // null = contract creation
	private final BigInteger value;
	private final byte[] data;
	private final long gasLimit;
	private final Word gasPrice;
	private final long nonce;

	public EVMTx(Address sender, Address to, BigInteger value, byte[] data, long gasLimit, Word gasPrice,
			long nonce) {
		this.sender = sender;
		this.to = to;
		this.value = value;
		this.data = data == null ? new byte[0] : data.clone();
		this.gasLimit = gasLimit;
		this.gasPrice = gasPrice;
		this.nonce = nonce;
	}

	public Address getSender() {
		return sender;
	}

	/** The recipient, or null for a contract creation. */
	public Address getTo() {
		return to;
	}

	public BigInteger getValue() {
		return value;
	}

	public byte[] getData() {
		return data;
	}

	public long getGasLimit() {
		return gasLimit;
	}

	public Word getGasPrice() {
		return gasPrice;
	}

	public long getNonce() {
		return nonce;
	}
}
