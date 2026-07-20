/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.utils;

import static net.bigtangle.core.Coin.COIN;
import static net.bigtangle.core.Coin.ZERO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.google.common.math.LongMath;

import net.bigtangle.core.Coin;
import net.bigtangle.params.NetworkParameters;

public class MonetaryFormatTest {

	private static final MonetaryFormat NO_CODE = MonetaryFormat.FIAT.noCode();

	@Test
	public void testSigns() throws Exception {
		assertEquals("-1", NO_CODE.format(Coin.COIN.negate()).toString());
		assertEquals("@0.01", NO_CODE.negativeSign('@').format(Coin.COIN.divide(100).negate()).toString());
		assertEquals("1", NO_CODE.format(Coin.COIN).toString());
		assertEquals("+1", NO_CODE.positiveSign('+').format(Coin.COIN).toString());
	}

	@Test
	public void testDecimalMark() throws Exception {
		// assertEquals("1", NO_CODE.format(Coin.COIN).toString());
		assertEquals("0,01", NO_CODE.decimalMark(',').format(Coin.COIN.divide(100)).toString());
	}

	@Test
	public void testGrouping() throws Exception {
		assertEquals("0.1", format(NO_CODE.parse("0.1"), 0, 1, 2, 3));
		assertEquals("0.010", format(NO_CODE.parse("0.01"), 0, 1, 2, 3));
		assertEquals("0.001", format(NO_CODE.parse("0.001"), 0, 1, 2, 3));
		assertEquals("0.000100", format(NO_CODE.parse("0.0001"), 0, 1, 2, 3));
		assertEquals("0.000010", format(NO_CODE.parse("0.00001"), 0, 1, 2, 3));
		assertEquals("0.000001", format(NO_CODE.parse("0.000001"), 0, 1, 2, 3));
	}

	@Test
	public void testTooSmall() throws Exception {
		assertThrows(NumberFormatException.class, () -> {
			assertEquals("0.0000001", format(NO_CODE.parse("0.0000001"), 0, 1, 2, 3));
		});

	}

	@Test
	public void btcRounding() throws Exception {
		assertEquals("0", format(ZERO, 0, 0));
		// assertEquals("0.00", format(ZERO, 0, 2));

		assertEquals("1", format(COIN, 0, 0));
		// assertEquals("1.0", format(COIN, 0, 1));
		// assertEquals("1.00", format(COIN, 0,2, 0));
		// assertEquals("1.00", format(COIN, 0, 2, 2, 0));
		// assertEquals("1.00", format(COIN, 0, 2, 2, 2, 2));
		// assertEquals("1.000", format(COIN, 0, 3));
		// assertEquals("1.0000", format(COIN, 0, 4));

		// assertEquals("0.99999999", format(justNot, 0, 2, 2, 2, 2));
		// assertEquals("1.000", format(justNot, 0, 3));
		// assertEquals("1.0000", format(justNot, 0, 4));

		// assertEquals("1.00000005", format(pivot, 0, 8));
		// assertEquals("1.00000005", format(pivot, 0, 7, 1));
		// assertEquals("1.0000001", format(pivot, 0, 7));

		final Coin value = Coin.valueOf(1122334455667788l);
		// assertEquals("112233445566778", format(value, 0, 0));
		// assertEquals("112233445566.7", format(value, 0, 1));
		// assertEquals("11223344.5567", format(value, 0, 2, 2));
		// assertEquals("11223344.556678", format(value, 0, 2, 2, 2));
		// assertEquals("11223344.55667788", format(value, 0, 2, 2, 2, 2));
		// assertEquals("11223344.557", format(value, 0, 3));
		// assertEquals("11223344.5567", format(value, 0, 4));
	}

	private String format(Coin coin, int shift, int minDecimals, int... decimalGroups) {
		return NO_CODE.shift(shift).minDecimals(minDecimals).optionalDecimals(decimalGroups).format(coin).toString();
	}

	private String formatRepeat(Coin coin, int decimals, int repetitions) {
		return NO_CODE.minDecimals(0).repeatOptionalDecimals(decimals, repetitions).format(coin).toString();
	}

	@Test
	public void withLocale() throws Exception {
		final Coin value = Coin.valueOf(-123456789 * LongMath.pow(10, NetworkParameters.BIGTANGLE_DECIMAL - 2));
		assertEquals("-1234567.89", NO_CODE.withLocale(Locale.US).format(value).toString());
		assertEquals("-1234567.89", NO_CODE.withLocale(Locale.CHINA).format(value).toString());
		assertEquals("-1234567,89", NO_CODE.withLocale(Locale.GERMANY).format(value).toString());
		// assertEquals("-१२.३४५६७८९०", NO_CODE.withLocale(new Locale("hi",
		// "IN")).format(value).toString()); // Devanagari
	}

	@Test
	public void parse() throws Exception {
		assertEquals(Coin.COIN, NO_CODE.parse("1"));
		assertEquals(Coin.COIN, NO_CODE.parse("1."));
		assertEquals(Coin.COIN, NO_CODE.parse("1.0"));
		assertEquals(Coin.COIN, NO_CODE.decimalMark(',').parse("1,0"));
		assertEquals(Coin.COIN, NO_CODE.parse("01.0000000000"));
		assertEquals(Coin.COIN, NO_CODE.positiveSign('+').parse("+1.0"));
		assertEquals(Coin.COIN.negate(), NO_CODE.parse("-1"));
		assertEquals(Coin.COIN.negate(), NO_CODE.parse("-1.0"));

		assertEquals(Coin.COIN.divide(100), NO_CODE.parse(".01"));

		assertEquals(Coin.COIN.divide(100), NO_CODE.withLocale(new Locale("hi", "IN")).parse(".०१")); // Devanagari
	}

	@Test
	public void parseInvalidEmpty() throws Exception {
		assertThrows(NumberFormatException.class, () -> {
			NO_CODE.parse("");
		});

	}

	@Test
	public void parseInvalidWhitespaceSign() throws Exception {
		assertThrows(NumberFormatException.class, () -> {
			NO_CODE.parse("- 1");
		});

	}

	@Test
	public void parseInvalidMultipleDecimalMarks() throws Exception {
		assertThrows(NumberFormatException.class, () -> {
			NO_CODE.parse("1.0.0");
		});

	}

	@Test
	public void parseInvalidDecimalMark() throws Exception {
		assertThrows(NumberFormatException.class, () -> {
			NO_CODE.decimalMark(',').parse("1.0");
		});

	}

	@Test
	public void parseInvalidPositiveSign() throws Exception {
		assertThrows(NumberFormatException.class, () -> {
			NO_CODE.positiveSign('@').parse("+1.0");
		});

	}

	@Test
	public void parseInvalidNegativeSign() throws Exception {
		assertThrows(NumberFormatException.class, () -> {
			NO_CODE.negativeSign('@').parse("-1.0");
		});

	}

}
