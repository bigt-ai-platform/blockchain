package net.bigtangle.evm;

/**
 * Keccak-256 as used by Ethereum (the original Keccak padding {@code 0x01},
 * <em>not</em> the FIPS-202 SHA3-256 which uses {@code 0x06}). Needed by the EVM
 * for the {@code SHA3} opcode, {@code CREATE}/{@code CREATE2} address derivation
 * and Solidity ABI hashing.
 *
 * <p>Self-contained sponge implementation of the Keccak-f[1600] permutation
 * (rate 136 bytes / capacity 512 bits), outputting 32 bytes. Deterministic and
 * dependency-free.
 */
public final class Keccak {

	private static final long[] ROUND_CONSTANTS = new long[] { 0x0000000000000001L, 0x0000000000008082L,
			0x800000000000808aL, 0x8000000080008000L, 0x000000000000808bL, 0x0000000080000001L,
			0x8000000080008081L, 0x8000000000008009L, 0x000000000000008aL, 0x0000000000000088L,
			0x0000000080008009L, 0x000000008000000aL, 0x000000008000808bL, 0x800000000000008bL,
			0x8000000000008089L, 0x8000000000008003L, 0x8000000000008002L, 0x8000000000000080L,
			0x000000000000800aL, 0x800000008000000aL, 0x8000000080008081L, 0x8000000000008080L,
			0x0000000080000001L, 0x8000000080008008L };

	/** rhoOffsets[x][y] = rotation offset for lane (x, y). */
	private static final int[][] RHO = new int[][] { { 0, 36, 3, 41, 18 }, { 1, 44, 10, 45, 2 },
			{ 62, 6, 43, 15, 61 }, { 28, 55, 25, 21, 56 }, { 27, 20, 39, 8, 14 } };

	private static final int RATE = 136; // bytes per absorbed block for 256-bit output

	private Keccak() {
	}

	/** Convenience: concatenates the given parts then hashes them. */
	public static byte[] hash(byte[]... parts) {
		int length = 0;
		for (byte[] part : parts) {
			length += part.length;
		}
		byte[] input = new byte[length];
		int offset = 0;
		for (byte[] part : parts) {
			System.arraycopy(part, 0, input, offset, part.length);
			offset += part.length;
		}
		return hash(input);
	}

	public static byte[] hash(byte[] input) {
		long[] state = new long[25];
		byte[] block = new byte[RATE];

		int offset = 0;
		int remaining = input.length;
		while (remaining >= RATE) {
			for (int i = 0; i < RATE / 8; i++) {
				state[i] ^= readLittleEndian(input, offset + i * 8);
			}
			offset += RATE;
			remaining -= RATE;
			keccakF(state);
		}

		java.util.Arrays.fill(block, (byte) 0);
		System.arraycopy(input, offset, block, 0, remaining);
		block[remaining] ^= 0x01; // Keccak padding
		block[RATE - 1] ^= 0x80;
		for (int i = 0; i < RATE / 8; i++) {
			state[i] ^= readLittleEndian(block, i * 8);
		}
		keccakF(state);

		byte[] out = new byte[32];
		int outOffset = 0;
		outer: while (true) {
			for (int i = 0; i < RATE / 8; i++) {
				writeLittleEndian(state[i], out, outOffset);
				outOffset += 8;
				if (outOffset >= out.length) {
					break outer;
				}
			}
			keccakF(state);
		}
		return out;
	}

	private static void keccakF(long[] a) {
		long[] t = new long[25];
		long[] c = new long[5];
		for (int round = 0; round < 24; round++) {
			for (int x = 0; x < 5; x++) {
				c[x] = a[index(x, 0)] ^ a[index(x, 1)] ^ a[index(x, 2)] ^ a[index(x, 3)] ^ a[index(x, 4)];
			}
			for (int x = 0; x < 5; x++) {
				long d = c[(x + 4) % 5] ^ Long.rotateLeft(c[(x + 1) % 5], 1);
				for (int y = 0; y < 5; y++) {
					a[index(x, y)] ^= d;
				}
			}
			for (int x = 0; x < 5; x++) {
				for (int y = 0; y < 5; y++) {
					t[index(y, (2 * x + 3 * y) % 5)] = Long.rotateLeft(a[index(x, y)], RHO[x][y]);
				}
			}
			for (int x = 0; x < 5; x++) {
				for (int y = 0; y < 5; y++) {
					a[index(x, y)] = t[index(x, y)] ^ ((~t[index((x + 1) % 5, y)]) & t[index((x + 2) % 5, y)]);
				}
			}
			a[0] ^= ROUND_CONSTANTS[round];
		}
	}

	private static int index(int x, int y) {
		return x + 5 * y;
	}

	private static long readLittleEndian(byte[] b, int off) {
		long v = 0;
		for (int i = 7; i >= 0; i--) {
			v = (v << 8) | (b[off + i] & 0xffL);
		}
		return v;
	}

	private static void writeLittleEndian(long v, byte[] out, int off) {
		for (int i = 0; i < 8; i++) {
			out[off + i] = (byte) (v >>> (8 * i));
		}
	}
}
