/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import net.bigtangle.exception.VerificationException;
import net.bigtangle.params.NetworkParameters;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Date;

public class UtilsTest {

	private static final Logger log = LoggerFactory.getLogger(UtilsTest.class);

	@Test
	public void testReverseBytes() {
		assertArrayEquals(new byte[] { 1, 2, 3, 4, 5 }, Utils.reverseBytes(new byte[] { 5, 4, 3, 2, 1 }));
	}

	@Test
	public void testReverseDwordBytes() {
		assertArrayEquals(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 },
				Utils.reverseDwordBytes(new byte[] { 4, 3, 2, 1, 8, 7, 6, 5 }, -1));
		assertArrayEquals(new byte[] { 1, 2, 3, 4 }, Utils.reverseDwordBytes(new byte[] { 4, 3, 2, 1, 8, 7, 6, 5 }, 4));
		assertArrayEquals(new byte[0], Utils.reverseDwordBytes(new byte[] { 4, 3, 2, 1, 8, 7, 6, 5 }, 0));
		assertArrayEquals(new byte[0], Utils.reverseDwordBytes(new byte[0], 0));
	}

	@Test
	public void testMaxOfMostFreq() throws Exception {
		assertEquals(0, Utils.maxOfMostFreq());
		assertEquals(0, Utils.maxOfMostFreq(0, 0, 1));
		assertEquals(2, Utils.maxOfMostFreq(1, 1, 2, 2));
		assertEquals(1, Utils.maxOfMostFreq(1, 1, 2, 2, 1));
		assertEquals(-1, Utils.maxOfMostFreq(-1, -1, 2, 2, -1));
	}

	@Test
	public void compactEncoding() throws Exception {
		assertEquals(new BigInteger("1234560000", 16), Utils.decodeCompactBits(0x05123456L));
		assertEquals(new BigInteger("c0de000000", 16), Utils.decodeCompactBits(0x0600c0de));
		assertEquals(0x05123456L, Utils.encodeCompactBits(new BigInteger("1234560000", 16)));
		assertEquals(0x0600c0deL, Utils.encodeCompactBits(new BigInteger("c0de000000", 16)));
	}

	@Test
	public void dateTimeFormat() {
		assertEquals("2014-11-16T10:54:33Z", Utils.dateTimeFormat(1416135273781L));
		assertEquals("2014-11-16T10:54:33Z", Utils.dateTimeFormat(new Date(1416135273781L)));
	}

	public static Block createBlock(NetworkParameters params, Block prevBlock, Block branchBlock) {
		return createNextBlock(prevBlock, branchBlock, NetworkParameters.BLOCK_VERSION_GENESIS);
	}

	/**
	 * Returns a solved, valid empty block that builds on top of this one and
	 * the specified other Block.
	 */
	public static Block createNextBlock(Block prevBlock, Block branchBlock, final long version) {
		Block b = Block.setBlock2(prevBlock.getParams(), version);

		b.setPrevBlockHash(prevBlock.getHash());
		b.setPrevBranchBlockHash(branchBlock.getHash());

		// Set difficulty according to previous consensus
		// only BLOCKTYPE_BEACON and BLOCKTYPE_INITIAL should overwrite this
		b.setLastMiningRewardBlock(
				Math.max(prevBlock.getLastMiningRewardBlock(), branchBlock.getLastMiningRewardBlock()));

		b.setHeight(Math.max(prevBlock.getHeight(), branchBlock.getHeight()) + 1);

		// Don't let timestamp go backwards
		long currTime = System.currentTimeMillis() / 1000;
		long minTime = Math.max(currTime, branchBlock.getTimeSeconds());
		if (currTime >= minTime)
			b.setTime(currTime + 1);
		else
			b.setTime(minTime);
		try {
			b.verifyHeader();
		} catch (VerificationException e) {
			throw new RuntimeException(e); // Cannot happen.
		}
		if (b.getVersion() != version) {
			throw new RuntimeException();
		}
		return b;
	}

}
