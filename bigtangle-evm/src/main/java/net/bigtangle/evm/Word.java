package net.bigtangle.evm;

import java.math.BigInteger;

import net.bigtangle.core.Utils;

/**
 * An immutable 256-bit EVM word. The value is kept as an unsigned
 * {@link BigInteger} in {@code [0, 2^256)}; every arithmetic operation reduces
 * modulo {@code 2^256} so results wrap exactly like the EVM.
 *
 * <p>Serialization is big-endian ({@link #toBytes()} returns 32 bytes), matching
 * the EVM. Two words are equal iff their unsigned values are equal (leading
 * zero bytes are not significant), which is also how the EVM treats storage
 * keys.
 */
public final class Word implements Comparable<Word> {

	public static final BigInteger TWO_256 = BigInteger.ONE.shiftLeft(256);
	public static final BigInteger MAX_VALUE = TWO_256.subtract(BigInteger.ONE);
	public static final int LENGTH = 32;

	public static final Word ZERO = new Word(BigInteger.ZERO);
	public static final Word ONE = new Word(BigInteger.ONE);

	private final BigInteger value;

	private Word(BigInteger value) {
		this.value = value;
	}

	public static Word of(long value) {
		return new Word(BigInteger.valueOf(value));
	}

	/**
	 * Wraps the given value modulo {@code 2^256}. Negative inputs are converted
	 * with two's-complement semantics, so {@code of(a.subtract(b))} wraps like
	 * the EVM {@code SUB} opcode.
	 */
	public static Word of(BigInteger value) {
		return new Word(value.and(MAX_VALUE));
	}

	/**
	 * Reads a big-endian value of up to 32 bytes. Longer inputs are truncated to
	 * their low-order 32 bytes.
	 */
	public static Word fromBytes(byte[] bytes) {
		if (bytes.length == 0) {
			return ZERO;
		}
		return new Word(new BigInteger(1, bytes).and(MAX_VALUE));
	}

	/** Big-endian 32-byte representation (MSB first), zero-padded on the left. */
	public byte[] toBytes() {
		byte[] out = new byte[LENGTH];
		byte[] raw = value.toByteArray();
		if (raw.length >= LENGTH) {
			System.arraycopy(raw, raw.length - LENGTH, out, 0, LENGTH);
		} else {
			System.arraycopy(raw, 0, out, LENGTH - raw.length, raw.length);
		}
		return out;
	}

	public BigInteger toBigInteger() {
		return value;
	}

	public boolean isZero() {
		return value.signum() == 0;
	}

	public int signum() {
		return value.signum();
	}

	public long longValue() {
		return value.longValue();
	}

	public int intValue() {
		return value.intValue();
	}

	/** Signed 256-bit interpretation: the two's-complement value of the word. */
	public BigInteger toSigned() {
		return value.testBit(255) ? value.subtract(TWO_256) : value;
	}

	public static Word fromSigned(BigInteger signed) {
		return of(signed);
	}

	public Word add(Word other) {
		return new Word(value.add(other.value).and(MAX_VALUE));
	}

	public Word sub(Word other) {
		return new Word(value.subtract(other.value).and(MAX_VALUE));
	}

	public Word mul(Word other) {
		return new Word(value.multiply(other.value).and(MAX_VALUE));
	}

	public Word div(Word other) {
		if (other.value.signum() == 0) {
			return ZERO;
		}
		return new Word(value.divide(other.value));
	}

	public Word mod(Word other) {
		if (other.value.signum() == 0) {
			return ZERO;
		}
		return new Word(value.mod(other.value));
	}

	public Word exp(Word exponent) {
		if (exponent.value.bitLength() >= 256) {
			return ZERO;
		}
		return new Word(value.modPow(exponent.value, TWO_256));
	}

	public Word and(Word other) {
		return new Word(value.and(other.value));
	}

	public Word or(Word other) {
		return new Word(value.or(other.value));
	}

	public Word xor(Word other) {
		return new Word(value.xor(other.value));
	}

	public Word not() {
		return new Word(value.xor(MAX_VALUE));
	}

	public Word shl(Word shift) {
		if (shift.value.compareTo(BigInteger.valueOf(256)) >= 0) {
			return ZERO;
		}
		return new Word(value.shiftLeft(shift.value.intValue()).and(MAX_VALUE));
	}

	public Word shr(Word shift) {
		if (shift.value.compareTo(BigInteger.valueOf(256)) >= 0) {
			return ZERO;
		}
		return new Word(value.shiftRight(shift.value.intValue()));
	}

	public Word sar(Word shift) {
		BigInteger signed = toSigned();
		BigInteger result;
		if (shift.value.compareTo(BigInteger.valueOf(256)) >= 0) {
			result = signed.signum() < 0 ? BigInteger.valueOf(-1) : BigInteger.ZERO;
		} else {
			result = signed.shiftRight(shift.value.intValue());
		}
		return new Word(result.and(MAX_VALUE));
	}

	@Override
	public int compareTo(Word other) {
		return value.compareTo(other.value);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Word)) {
			return false;
		}
		return value.equals(((Word) o).value);
	}

	@Override
	public int hashCode() {
		return value.hashCode();
	}

	@Override
	public String toString() {
		return "0x" + Utils.HEX.encode(toBytes());
	}
}
