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

	// @Test
	public void testDigits() throws Exception {
		assertEquals("١٢.٣٤٥٦٧٨٩٠", NO_CODE.digits('\u0660').format(Coin.valueOf(1234567890l)).toString());
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

	// @Test
	public void mBtcRounding() throws Exception {
		assertEquals("0", format(ZERO, 2, 0));
		// assertEquals("0.00", format(ZERO, 2, 2));

		assertEquals("1000", format(COIN, 3, 0));
		assertEquals("1000.0", format(COIN, 3, 1));
		assertEquals("1000.00", format(COIN, 3, 2));
		assertEquals("1000.00", format(COIN, 3, 2, 2));
		assertEquals("1000.000", format(COIN, 3, 3));
		assertEquals("1000.0000", format(COIN, 3, 4));

		final Coin value = Coin.valueOf(1122334455667788l);
		assertEquals("11223344557", format(value, 3, 0));
		assertEquals("11223344556.7", format(value, 3, 1));
		assertEquals("11223344556.68", format(value, 3, 2));
		assertEquals("11223344556.6779", format(value, 3, 2, 2));
		assertEquals("11223344556.678", format(value, 3, 3));
		assertEquals("11223344556.6779", format(value, 3, 4));
	}

	// @Test
	public void uBtcRounding() throws Exception {
		assertEquals("0", format(ZERO, 2, 0));
		assertEquals("0.00", format(ZERO, 6, 2));

		assertEquals("1000000", format(COIN, 6, 0));
		assertEquals("1000000", format(COIN, 6, 0, 2));
		assertEquals("1000000.0", format(COIN, 6, 1));
		assertEquals("1000000.00", format(COIN, 6, 2));

		final Coin value = Coin.valueOf(1122334455667788l);
		assertEquals("11223344556678", format(value, 6, 0));
		assertEquals("11223344556677.88", format(value, 6, 2));
		assertEquals("11223344556677.9", format(value, 6, 1));
		assertEquals("11223344556677.88", format(value, 6, 2));
	}

	private String format(Coin coin, int shift, int minDecimals, int... decimalGroups) {
		return NO_CODE.shift(shift).minDecimals(minDecimals).optionalDecimals(decimalGroups).format(coin).toString();
	}

	// @Test
	public void repeatOptionalDecimals() {

		assertEquals("0.01", formatRepeat(Coin.COIN.divide(100), 2, 4));
		assertEquals("0.10", formatRepeat(Coin.COIN.divide(10), 2, 4));

		assertEquals("0.01", formatRepeat(Coin.COIN.divide(100), 2, 2));
		assertEquals("0.10", formatRepeat(Coin.COIN.divide(10), 2, 2));

		assertEquals("0", formatRepeat(Coin.COIN.divide(100), 2, 0));
		assertEquals("0", formatRepeat(Coin.COIN.divide(10), 2, 0));
	}

	private String formatRepeat(Coin coin, int decimals, int repetitions) {
		return NO_CODE.minDecimals(0).repeatOptionalDecimals(decimals, repetitions).format(coin).toString();
	}

	// @Test
	public void standardCodes() throws Exception {
		assertEquals(NetworkParameters.BIGTANGLE_TOKENID_STRING + " 0.00",
				MonetaryFormat.FIAT.format(Coin.ZERO).toString());

	}

	// @Test
	public void codeOrientation() throws Exception {
		assertEquals(NetworkParameters.BIGTANGLE_TOKENID_STRING + " 0.00",
				MonetaryFormat.FIAT.prefixCode().format(Coin.ZERO).toString());
		assertEquals("0.00 " + NetworkParameters.BIGTANGLE_TOKENID_STRING,
				MonetaryFormat.FIAT.postfixCode().format(Coin.ZERO).toString());
	}

	// @Test
	public void codeSeparator() throws Exception {
		assertEquals(NetworkParameters.BIGTANGLE_TOKENID_STRING + "@0.00",
				MonetaryFormat.FIAT.codeSeparator('@').format(Coin.ZERO).toString());
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

	// @Test(expected = NumberFormatException.class)
	public void parseInvalidWhitespaceBefore() throws Exception {
		NO_CODE.parse(" 1");
	}

	@Test
	public void parseInvalidWhitespaceSign() throws Exception {
		assertThrows(NumberFormatException.class, () -> {
			NO_CODE.parse("- 1");
		});

	}

	// @Test(expected = NumberFormatException.class)
	public void parseInvalidWhitespaceAfter() throws Exception {
		NO_CODE.parse("1 ");
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

	// @Test(expected = NumberFormatException.class)
	public void parseInvalidHugeNumber() throws Exception {
		NO_CODE.parse("99999999999999999999");
	}

	// @Test(expected = NumberFormatException.class)
	public void parseInvalidHugeNegativeNumber() throws Exception {
		NO_CODE.parse("-99999999999999999999");
	}

}
