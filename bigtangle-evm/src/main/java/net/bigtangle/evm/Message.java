package net.bigtangle.evm;

import java.math.BigInteger;

/**
 * An EVM message: the execution context handed to the interpreter for a call or
 * a create. The distinction matters for {@code CALL}/{@code CALLCODE}/
 * {@code DELEGATECALL}/{@code STATICCALL}:
 *
 * <ul>
 * <li>{@code sender} — the caller (opcode {@code CALLER}, propagated verbatim
 * through {@code DELEGATECALL}).</li>
 * <li>{@code address} — the executing account (opcode {@code ADDRESS} and the
 * storage/balance owner).</li>
 * <li>{@code codeAddress} — the account whose code is executed.</li>
 * <li>{@code value} — wei carried by the message (opcode {@code CALLVALUE}).</li>
 * </ul>
 */
public final class Message {

	private final Address origin;
	private final Address sender;
	private final Address address;
	private final Address codeAddress;
	private final BigInteger value;
	private final byte[] data;
	private final long gas;
	private final boolean isCreate;
	private final boolean isStatic;
	private final Word gasPrice;

	private Message(Address origin, Address sender, Address address, Address codeAddress, BigInteger value, byte[] data,
			long gas, boolean isCreate, boolean isStatic, Word gasPrice) {
		this.origin = origin;
		this.sender = sender;
		this.address = address;
		this.codeAddress = codeAddress;
		this.value = value;
		this.data = data.clone();
		this.gas = gas;
		this.isCreate = isCreate;
		this.isStatic = isStatic;
		this.gasPrice = gasPrice;
	}

	/** Top-level call from a transaction. */
	public static Message call(Address origin, Address sender, Address to, BigInteger value, byte[] data, long gas,
			Word gasPrice) {
		return new Message(origin, sender, to, to, value, data, gas, false, false, gasPrice);
	}

	/** Top-level contract creation from a transaction. */
	public static Message create(Address origin, Address sender, Address contract, byte[] initCode, long gas,
			Word gasPrice) {
		return new Message(origin, sender, contract, contract, BigInteger.ZERO, initCode, gas, true, false, gasPrice);
	}

	/** {@code CALL} opcode. */
	public static Message callOp(Message parent, Address to, BigInteger value, byte[] data, long gas, boolean isStatic) {
		return new Message(parent.origin, parent.address, to, to, value, data, gas, false, isStatic, parent.gasPrice);
	}

	/** {@code CALLCODE} opcode: code from {@code to}, context stays with the caller. */
	public static Message callCodeOp(Message parent, Address to, BigInteger value, byte[] data, long gas) {
		return new Message(parent.origin, parent.address, parent.address, to, value, data, gas, false, false,
				parent.gasPrice);
	}

	/** {@code DELEGATECALL} opcode: code from {@code to}, caller and value preserved. */
	public static Message delegateCallOp(Message parent, Address to, byte[] data, long gas) {
		return new Message(parent.origin, parent.sender, parent.address, to, parent.value, data, gas, false, false,
				parent.gasPrice);
	}

	/** {@code CREATE} opcode. */
	public static Message createOp(Message parent, Address contract, byte[] initCode, long gas) {
		return new Message(parent.origin, parent.address, contract, contract, BigInteger.ZERO, initCode, gas, true,
				false, parent.gasPrice);
	}

	public Address getOrigin() {
		return origin;
	}

	public Address getSender() {
		return sender;
	}

	public Address getAddress() {
		return address;
	}

	public Address getCodeAddress() {
		return codeAddress;
	}

	public BigInteger getValue() {
		return value;
	}

	public byte[] getData() {
		return data;
	}

	public long getGas() {
		return gas;
	}

	public boolean isCreate() {
		return isCreate;
	}

	public boolean isStatic() {
		return isStatic;
	}

	public Word getGasPrice() {
		return gasPrice;
	}
}
