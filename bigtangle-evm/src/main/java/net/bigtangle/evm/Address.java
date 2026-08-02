package net.bigtangle.evm;

import java.math.BigInteger;

import net.bigtangle.core.Utils;

/**
 * A 20-byte EVM account address. Immutable.
 */
public final class Address implements Comparable<Address> {

	public static final int LENGTH = 20;
	public static final Address ZERO = new Address(new byte[LENGTH]);

	private final byte[] bytes;

	public Address(byte[] bytes) {
		if (bytes.length != LENGTH) {
			throw new IllegalArgumentException("EVM address must be " + LENGTH + " bytes, got " + bytes.length);
		}
		this.bytes = bytes.clone();
	}

	/** Right-aligns a big-endian value into a 20-byte address. */
	public static Address fromBigInteger(BigInteger value) {
		byte[] raw = value.toByteArray();
		byte[] out = new byte[LENGTH];
		if (raw.length >= LENGTH) {
			System.arraycopy(raw, raw.length - LENGTH, out, 0, LENGTH);
		} else {
			System.arraycopy(raw, 0, out, LENGTH - raw.length, raw.length);
		}
		return new Address(out);
	}

	/** Takes the low-order 20 bytes of a 32-byte hash (Ethereum address derivation). */
	public static Address fromLast20Bytes(byte[] hash32) {
		byte[] out = new byte[LENGTH];
		System.arraycopy(hash32, hash32.length - LENGTH, out, 0, LENGTH);
		return new Address(out);
	}

	/** Parses a 40-char hex address, tolerating a leading {@code 0x}. */
	public static Address fromHex(String hex) {
		if (hex == null) {
			throw new IllegalArgumentException("null address");
		}
		String h = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
		if (h.length() != LENGTH * 2) {
			throw new IllegalArgumentException("EVM address must be 40 hex chars, got " + hex);
		}
		return new Address(Utils.HEX.decode(h));
	}

	/** 40-char lowercase hex, no {@code 0x} prefix. */
	public String toHex() {
		return Utils.HEX.encode(bytes);
	}

	public byte[] toBytes() {
		return bytes.clone();
	}

	public boolean isZero() {
		for (byte b : bytes) {
			if (b != 0) {
				return false;
			}
		}
		return true;
	}

	@Override
	public int compareTo(Address other) {
		for (int i = 0; i < LENGTH; i++) {
			int cmp = (bytes[i] & 0xff) - (other.bytes[i] & 0xff);
			if (cmp != 0) {
				return cmp;
			}
		}
		return 0;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Address)) {
			return false;
		}
		return java.util.Arrays.equals(bytes, ((Address) o).bytes);
	}

	@Override
	public int hashCode() {
		return java.util.Arrays.hashCode(bytes);
	}

	@Override
	public String toString() {
		return "0x" + Utils.HEX.encode(bytes);
	}
}
