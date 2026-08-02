package net.bigtangle.evm;

import java.math.BigInteger;
import java.util.Arrays;

import net.bigtangle.core.Sha256Hash;

/**
 * A single EVM account: nonce, balance (wei), contract code and its hash.
 * Storage lives in {@link EVMStorage}, owned by {@link WorldState}.
 */
public final class EVMAccount {

	private final Address address;
	private long nonce;
	private BigInteger balance;
	private byte[] code;
	private Sha256Hash codeHash;

	public EVMAccount(Address address) {
		this(address, 0, BigInteger.ZERO, new byte[0]);
	}

	public EVMAccount(Address address, long nonce, BigInteger balance, byte[] code) {
		this.address = address;
		this.nonce = nonce;
		this.balance = balance;
		setCode(code);
	}

	public Address getAddress() {
		return address;
	}

	public long getNonce() {
		return nonce;
	}

	public void setNonce(long nonce) {
		this.nonce = nonce;
	}

	/** Increments the nonce and returns its previous value. */
	public long incrementNonce() {
		return nonce++;
	}

	public BigInteger getBalance() {
		return balance;
	}

	public void setBalance(BigInteger balance) {
		this.balance = balance;
	}

	public byte[] getCode() {
		return code;
	}

	public void setCode(byte[] code) {
		this.code = code == null ? new byte[0] : code.clone();
		this.codeHash = this.code.length == 0 ? Sha256Hash.ZERO_HASH : Sha256Hash.of(this.code);
	}

	public boolean hasCode() {
		return code.length > 0;
	}

	public Sha256Hash getCodeHash() {
		return codeHash;
	}

	public EVMAccount copy() {
		return new EVMAccount(address, nonce, balance, code);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof EVMAccount)) {
			return false;
		}
		EVMAccount that = (EVMAccount) o;
		return nonce == that.nonce && address.equals(that.address) && balance.equals(that.balance)
				&& Arrays.equals(code, that.code);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(new Object[] { address, nonce, balance, code });
	}
}
