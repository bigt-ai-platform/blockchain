package net.bigtangle.evm;

/**
 * Minimal RLP encoding needed for Ethereum {@code CREATE} address derivation:
 * {@code address = keccak256(rlp([sender, nonce]))[12..]}.
 */
public final class Rlp {

	private Rlp() {
	}

	/** RLP-encodes a byte string (single byte {@code < 0x80} stays as-is). */
	public static byte[] rlpString(byte[] data) {
		if (data.length == 1 && (data[0] & 0xff) < 0x80) {
			return data;
		}
		if (data.length < 56) {
			byte[] out = new byte[1 + data.length];
			out[0] = (byte) (0x80 + data.length);
			System.arraycopy(data, 0, out, 1, data.length);
			return out;
		}
		byte[] len = minimalBytes(data.length);
		byte[] out = new byte[1 + len.length + data.length];
		out[0] = (byte) (0xb7 + len.length);
		System.arraycopy(len, 0, out, 1, len.length);
		System.arraycopy(data, 0, out, 1 + len.length, data.length);
		return out;
	}

	/** RLP-encodes a non-negative integer (0 encodes as the empty string 0x80). */
	public static byte[] rlpInteger(long value) {
		if (value < 0) {
			throw new IllegalArgumentException("negative nonce");
		}
		if (value == 0) {
			return new byte[] { (byte) 0x80 };
		}
		byte[] be = minimalBytes(value);
		if (be.length == 1 && (be[0] & 0xff) < 0x80) {
			return be;
		}
		if (be.length < 56) {
			byte[] out = new byte[1 + be.length];
			out[0] = (byte) (0x80 + be.length);
			System.arraycopy(be, 0, out, 1, be.length);
			return out;
		}
		byte[] len = minimalBytes(be.length);
		byte[] out = new byte[1 + len.length + be.length];
		out[0] = (byte) (0xb7 + len.length);
		System.arraycopy(len, 0, out, 1, len.length);
		System.arraycopy(be, 0, out, 1 + len.length, be.length);
		return out;
	}

	/** Ethereum CREATE address for the given sender and its current nonce. */
	public static Address createAddress(Address sender, long nonce) {
		byte[] item1 = rlpString(sender.toBytes());
		byte[] item2 = rlpInteger(nonce);
		int listLen = item1.length + item2.length;
		byte[] list = new byte[1 + listLen];
		list[0] = (byte) (0xc0 + listLen);
		System.arraycopy(item1, 0, list, 1, item1.length);
		System.arraycopy(item2, 0, list, 1 + item1.length, item2.length);
		return Address.fromLast20Bytes(Keccak.hash(list));
	}

	private static byte[] minimalBytes(long value) {
		byte[] be = new byte[8];
		for (int i = 7; i >= 0; i--) {
			be[i] = (byte) value;
			value >>>= 8;
		}
		int start = 0;
		while (start < be.length - 1 && be[start] == 0) {
			start++;
		}
		byte[] out = new byte[be.length - start];
		System.arraycopy(be, start, out, 0, out.length);
		return out;
	}
}
