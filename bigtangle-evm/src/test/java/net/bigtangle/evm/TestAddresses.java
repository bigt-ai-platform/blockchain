package net.bigtangle.evm;

import net.bigtangle.core.Utils;

/** Test helpers for building addresses from hex strings. */
final class TestAddresses {

	private TestAddresses() {
	}

	/** Builds a 20-byte address from a 40-char hex string. */
	static Address addr(String hex) {
		byte[] bytes = Utils.HEX.decode(hex);
		if (bytes.length != Address.LENGTH) {
			byte[] out = new byte[Address.LENGTH];
			System.arraycopy(bytes, 0, out, Address.LENGTH - bytes.length, bytes.length);
			return new Address(out);
		}
		return new Address(bytes);
	}

	/** A simple EVM bytecode builder. */
	static final class Bytecode {
		private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

		Bytecode op(int op) {
			out.write(op);
			return this;
		}

		Bytecode push1(int value) {
			out.write(0x60);
			out.write(value & 0xff);
			return this;
		}

		Bytecode push2(int value) {
			out.write(0x61);
			out.write((value >>> 8) & 0xff);
			out.write(value & 0xff);
			return this;
		}

		Bytecode push32(byte[] bytes32) {
			out.write(0x7f);
			out.writeBytes(bytes32);
			return this;
		}

		Bytecode raw(byte[] bytes) {
			out.writeBytes(bytes);
			return this;
		}

		byte[] build() {
			return out.toByteArray();
		}
	}
}
