/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.core;

import static net.bigtangle.core.Coin.COIN;
import static net.bigtangle.core.Coin.ZERO;
import static net.bigtangle.core.Coin.valueOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.common.math.LongMath;

import net.bigtangle.utils.MonetaryFormat;

public class CoinTest {

    @Test
    public void testParseCoin() {
        // String version
        assertEquals(Coin.COIN.divide(100), MonetaryFormat.FIAT.noCode().parse("0.01"));
        // assertEquals(CENT, MonetaryFormat.FIAT.noCode().parse("1E-2"));
        assertEquals(COIN.add(Coin.COIN.divide(100)), MonetaryFormat.FIAT.noCode().parse("1.01"));
        assertEquals(COIN.negate(), MonetaryFormat.FIAT.noCode().parse("-1"));
        try {
            MonetaryFormat.FIAT.noCode().parse("2E-20");
            org.junit.jupiter.api.Assertions.fail("should not have accepted fractional satoshis");
        } catch (IllegalArgumentException expected) {
        } catch (Exception e) {
            org.junit.jupiter.api.Assertions.fail("should throw IllegalArgumentException");
        }
    }

    @Test
    public void testValueOf() {
        // int version
        // assertEquals(CENT, valueOf(0,
        // 1,NetworkParameters.BIGNETCOIN_TOKENID));

        valueOf(Long.MAX_VALUE, NetworkParameters.BIGTANGLE_TOKENID);
        valueOf(Long.MIN_VALUE, NetworkParameters.BIGTANGLE_TOKENID);

    }

    @Test
    public void testOperators() {

        assertFalse(ZERO.isPositive());
        assertFalse(ZERO.isNegative());
        assertTrue(ZERO.isZero());

        assertTrue(valueOf(2, NetworkParameters.BIGTANGLE_TOKENID)
                .isGreaterThan(valueOf(1, NetworkParameters.BIGTANGLE_TOKENID)));
        assertFalse(valueOf(2, NetworkParameters.BIGTANGLE_TOKENID)
                .isGreaterThan(valueOf(2, NetworkParameters.BIGTANGLE_TOKENID)));
        assertFalse(valueOf(1, NetworkParameters.BIGTANGLE_TOKENID)
                .isGreaterThan(valueOf(2, NetworkParameters.BIGTANGLE_TOKENID)));
        assertTrue(valueOf(1, NetworkParameters.BIGTANGLE_TOKENID)
                .isLessThan(valueOf(2, NetworkParameters.BIGTANGLE_TOKENID)));
        assertFalse(valueOf(2, NetworkParameters.BIGTANGLE_TOKENID)
                .isLessThan(valueOf(2, NetworkParameters.BIGTANGLE_TOKENID)));
        assertFalse(valueOf(2, NetworkParameters.BIGTANGLE_TOKENID)
                .isLessThan(valueOf(1, NetworkParameters.BIGTANGLE_TOKENID)));
    }

   // @Test(expected = ArithmeticException.class)
    public void testMultiplicationOverflow() {
        Coin.valueOf(Long.MAX_VALUE, NetworkParameters.BIGTANGLE_TOKENID).multiply(2);
    }

   // @Test(expected = ArithmeticException.class)
    public void testMultiplicationUnderflow() {
        Coin.valueOf(Long.MIN_VALUE, NetworkParameters.BIGTANGLE_TOKENID).multiply(2);
    }

  //  @Test(expected = ArithmeticException.class)
    public void testAdditionOverflow() {
        Coin.valueOf(Long.MAX_VALUE, NetworkParameters.BIGTANGLE_TOKENID).add(Coin.COIN);
    }

   // @Test(expected = ArithmeticException.class)
    public void testSubstractionUnderflow() {
        Coin.valueOf(Long.MIN_VALUE, NetworkParameters.BIGTANGLE_TOKENID).subtract(Coin.COIN);
    }

    /**
     * Test the bitcoinValueToPlainString amount formatter
     */
    @Test
    public void testToPlainString() {
        MonetaryFormat format = MonetaryFormat.FIAT.noCode();
        assertEquals("0.15", format.format(Coin.valueOf(150000, NetworkParameters.BIGTANGLE_TOKENID)));
        assertEquals("1.23", format.format(format.parse("1.23")));

        assertEquals("0.1", format.format(format.parse("0.1")));
        assertEquals("1.1", format.format(format.parse("1.1")));
        assertEquals("21.12", format.format(format.parse("21.12")));
        assertEquals("321.123", format.format(format.parse("321.123")));
        assertEquals("4321.1234", format.format(format.parse("4321.1234")));
        assertEquals("54321.12345", format.format(format.parse("54321.12345")));
        assertEquals("654321.123456", format.format(format.parse("654321.123456")));

        // check there are no trailing zeros
        assertEquals("1", format.format(format.parse("1.0")));
        assertEquals("2", format.format(format.parse("2.00")));
        assertEquals("3", format.format(format.parse("3.000")));
        assertEquals("4", format.format(format.parse("4.0000")));
        assertEquals("5", format.format(format.parse("5.00000")));
        assertEquals("6", format.format(format.parse("6.000000")));
        assertEquals("7", format.format(format.parse("7.0000000")));

    }

    @Test
    public void testToPlainStringFail() {
    	assertThrows(NumberFormatException.class, () -> {
        MonetaryFormat format = MonetaryFormat.FIAT.noCode();

        assertEquals("7654321.1234567", format.format(format.parse("7654321.1234567")));
        assertEquals("87654321.12345678", format.format(format.parse("87654321.12345678")));
    	});
    }

    @Test
    public void testOrder() {
        MonetaryFormat mf = MonetaryFormat.FIAT.noCode();
        OrderRecord orderRecord = new OrderRecord();
        orderRecord.setOfferValue(48);
        orderRecord.setTargetValue(24);
        orderRecord.setOfferTokenid(NetworkParameters.BIGTANGLE_TOKENID_STRING);
        // "SELL"
        Token t = new Token();
        t.setDecimals(2);
        assertEquals("0.48", mf.format(orderRecord.getOfferValue(), t.getDecimals()));
        assertEquals("0.00005", mf.format(
                orderRecord.getTargetValue() * LongMath.pow(10, t.getDecimals()) / orderRecord.getOfferValue()));
    }
    }

