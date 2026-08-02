package net.bigtangle.evm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.Utils;

class KeccakTest {

	@Test
	void emptyString() {
		byte[] h = Keccak.hash(new byte[0]);
		assertEquals("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470", Utils.HEX.encode(h));
	}

	@Test
	void abc() {
		byte[] h = Keccak.hash("abc".getBytes(StandardCharsets.UTF_8));
		assertEquals("4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45", Utils.HEX.encode(h));
	}

	@Test
	void quickBrownFox() {
		byte[] h = Keccak.hash("The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8));
		assertEquals("4d741b6f1eb29cb2a9b9911c82f56fa8d73b04959d3d9d222895df6c0b28aa15", Utils.HEX.encode(h));
	}

	@Test
	void multiPartMatchesSingle() {
		byte[] part1 = "The quick brown fox".getBytes(StandardCharsets.UTF_8);
		byte[] part2 = " jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);
		byte[] joined = "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);
		assertEquals(Utils.HEX.encode(Keccak.hash(joined)), Utils.HEX.encode(Keccak.hash(part1, part2)));
	}

	@Test
	void twoHundredFiftySixBytes() {
		byte[] input = new byte[256];
		for (int i = 0; i < input.length; i++) {
			input[i] = (byte) (i & 0xff);
		}
		byte[] h = Keccak.hash(input);
		assertEquals(32, h.length);
		assertEquals("dc924469b334aed2a19fac7252e9961aea41f8d91996366029dbe0884229bf36", Utils.HEX.encode(h));
	}
}
