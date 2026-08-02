package net.bigtangle.evm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

class WordTest {

	@Test
	void wrapArithmetic() {
		assertEquals(BigInteger.valueOf(3), Word.ONE.add(Word.of(2)).toBigInteger());
		assertEquals(Word.MAX_VALUE, Word.ZERO.sub(Word.ONE).toBigInteger());
		assertEquals(BigInteger.ZERO, Word.of(Word.MAX_VALUE).add(Word.ONE).toBigInteger());
	}

	@Test
	void mulModulo() {
		Word a = Word.of(new BigInteger("2").pow(200));
		Word b = Word.of(new BigInteger("2").pow(200));
		assertEquals(new BigInteger("2").pow(400).and(Word.MAX_VALUE), a.mul(b).toBigInteger());
	}

	@Test
	void divByZero() {
		assertEquals(Word.ZERO, Word.ONE.div(Word.ZERO));
		assertEquals(Word.ZERO, Word.ONE.mod(Word.ZERO));
	}

	@Test
	void shiftLeftWraps() {
		assertEquals(Word.ZERO, Word.ONE.shl(Word.of(256)));
		assertEquals(Word.of(4), Word.ONE.shl(Word.of(2)));
	}

	@Test
	void logicalShiftRight() {
		assertEquals(Word.of(2), Word.of(5).shr(Word.ONE));
		assertEquals(Word.ZERO, Word.of(5).shr(Word.of(256)));
	}

	@Test
	void arithmeticShiftRight() {
		Word minusOne = Word.of(Word.MAX_VALUE);
		assertEquals(Word.MAX_VALUE, minusOne.sar(Word.of(5)).toBigInteger());
		Word highBit = Word.of(Word.TWO_256.shiftRight(1));
		assertEquals(Word.MAX_VALUE, highBit.sar(Word.of(255)).toBigInteger());
		assertEquals(Word.of(4), Word.of(8).sar(Word.ONE));
	}

	@Test
	void toBytesRoundTrip() {
		Word w = Word.of(new BigInteger("123456789012345678901234567890"));
		assertEquals(w, Word.fromBytes(w.toBytes()));
		assertEquals(32, w.toBytes().length);
	}

	@Test
	void signedConversion() {
		assertEquals(BigInteger.ONE.negate(), Word.of(Word.MAX_VALUE).toSigned());
		assertEquals(BigInteger.ZERO, Word.ZERO.toSigned());
		assertEquals(BigInteger.valueOf(5), Word.of(5).toSigned());
	}

	@Test
	void leadingZerosNotSignificant() {
		assertEquals(Word.ONE, Word.fromBytes(new byte[] { 0, 0, 1 }));
		assertTrue(Word.fromBytes(new byte[] { 0, 0, 0, 1 }).equals(Word.ONE));
	}
}
