/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigInteger;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import net.bigtangle.core.exception.AddressFormatException;
import net.bigtangle.utils.Base58;

public class Base58Test  {
	@Test
	public void testEncode() throws Exception {
		byte[] testbytes = "Hello World".getBytes();
		assertEquals("JxF12TrwUP45BMd", Base58.encode(testbytes));

		BigInteger bi = BigInteger.valueOf(3471844090L);
		assertEquals("16Ho7Hs", Base58.encode(bi.toByteArray()));

		byte[] zeroBytes1 = new byte[1];
		assertEquals("1", Base58.encode(zeroBytes1));

		byte[] zeroBytes7 = new byte[7];
		assertEquals("1111111", Base58.encode(zeroBytes7));

		// test empty encode
		assertEquals("", Base58.encode(new byte[0]));
	}

	@Test
	public void testDecode() throws Exception {
		byte[] testbytes = "Hello World".getBytes();
		byte[] actualbytes = Base58.decode("JxF12TrwUP45BMd");
		assertTrue(Arrays.equals(testbytes, actualbytes), new String(actualbytes));

		assertTrue(Arrays.equals(Base58.decode("1"), new byte[1]), "1");
		assertTrue(Arrays.equals(Base58.decode("1111"), new byte[4]), "1111");

		try {
			Base58.decode("This isn't valid base58");
			fail();
		} catch (AddressFormatException e) {
			// expected
		}

		Base58.decodeChecked("4stwEBjT6FYyVV");

		// Checksum should fail.
		try {
			Base58.decodeChecked("4stwEBjT6FYyVW");
			fail();
		} catch (AddressFormatException e) {
			// expected
		}

		// Input is too short.
		try {
			Base58.decodeChecked("4s");
			fail();
		} catch (AddressFormatException e) {
			// expected
		}

		// Test decode of empty String.
		assertEquals(0, Base58.decode("").length);

		// Now check we can correctly decode the case where the high bit of the first
		// byte is not zero, so BigInteger
		// sign extends. Fix for a bug that stopped us parsing keys exported using sipas
		// patch.
		Base58.decodeChecked("93VYUMzRG9DdbRP72uQXjaWibbQwygnvaCu9DumcqDjGybD864T");
	}

	@Test
	public void testDecodeToBigInteger() {
		byte[] input = Base58.decode("129");
		assertEquals(new BigInteger(1, input), Base58.decodeToBigInteger("129"));
	}
}
