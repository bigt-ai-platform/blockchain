package net.bigtangle.evm;

import java.io.ByteArrayOutputStream;

import net.bigtangle.core.Sha256Hash;

/**
 * Deterministic state-root computation over a {@link WorldState}. The root is
 * derived from the sorted account leaves so that two nodes executing the same
 * history always derive the same root (the consensus invariant for the L1 EVM).
 *
 * <p>Leaf layout (all big-endian, fixed width):
 * {@code sha256(version(1) || address(20) || nonce(8) || balance(32) ||
 * codeHash(32) || storageRoot(32))}, and the state root is the hash of the
 * concatenation of those leaves in sorted address order.
 */
public final class EVMStateRoot {

	private static final byte VERSION = 0x01;

	private EVMStateRoot() {
	}

	public static Sha256Hash compute(WorldState worldState) {
		ByteArrayOutputStream leaves = new ByteArrayOutputStream();
		for (Address address : worldState.addresses()) {
			EVMAccount account = worldState.getAccount(address);
			byte[] nonce = toBytes8(account.getNonce());
			byte[] balance = toBytes32(account.getBalance());
			leaves.writeBytes(Sha256Hash.hash(
					concat(new byte[] { VERSION }, address.toBytes(), nonce, balance,
							account.getCodeHash().getBytes(), worldState.getStorage(address).root().getBytes())));
		}
		return Sha256Hash.of(leaves.toByteArray());
	}

	private static byte[] toBytes8(long value) {
		byte[] out = new byte[8];
		for (int i = 7; i >= 0; i--) {
			out[i] = (byte) value;
			value >>>= 8;
		}
		return out;
	}

	private static byte[] toBytes32(java.math.BigInteger value) {
		byte[] out = new byte[32];
		byte[] raw = value.toByteArray();
		if (raw.length >= 32) {
			System.arraycopy(raw, raw.length - 32, out, 0, 32);
		} else {
			System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
		}
		return out;
	}

	private static byte[] concat(byte[]... parts) {
		int length = 0;
		for (byte[] part : parts) {
			length += part.length;
		}
		byte[] out = new byte[length];
		int offset = 0;
		for (byte[] part : parts) {
			System.arraycopy(part, 0, out, offset, part.length);
			offset += part.length;
		}
		return out;
	}
}
