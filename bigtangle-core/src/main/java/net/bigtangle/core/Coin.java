/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
/*
 * Copyright 2014 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.bigtangle.core;

import java.beans.Transient;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.Arrays;

import com.google.common.math.LongMath;

/**
 * Represents a token as Coin value. This class is immutable. Only the bitangle
 * coin has 2 digit decimal.
 */
public final class Coin implements Monetary, Comparable<Coin>, Serializable {

	public Coin() {
	}

	private static final long serialVersionUID = 551802452657362699L;

	/**
	 * Zero .
	 */
	public static final Coin ZERO = Coin.valueOf(0, NetworkParameters.BIGTANGLE_TOKENID);

	/**
	 * One .
	 */
	public static final Coin COIN = Coin.valueOf(LongMath.pow(10, NetworkParameters.BIGTANGLE_DECIMAL),
			NetworkParameters.BIGTANGLE_TOKENID);

	/**
	 * A satoshi is the smallest unit that can be transferred.
	 */
	public static final Coin SATOSHI = Coin.valueOf(1, NetworkParameters.BIGTANGLE_TOKENID);

	/**
	 * Represents a monetary value of minus one satoshi.
	 */
	public static final Coin NEGATIVE_SATOSHI = Coin.valueOf(-1, NetworkParameters.BIGTANGLE_TOKENID);

	public static final Coin FEE_DEFAULT = Coin.valueOf(1000, NetworkParameters.BIGTANGLE_TOKENID);

	/**
	 * The number of satoshis of this monetary value.
	 */
	private BigInteger value;
	private byte[] tokenid;

	public Coin(final long satoshis, final byte[] tokenid) {
		this.value = BigInteger.valueOf(satoshis);
		this.tokenid = tokenid;
	}

	public Coin(final BigInteger satoshis, final byte[] tokenid) {
		this.value = satoshis;
		this.tokenid = tokenid;
	}

	public Coin(final BigInteger satoshis, final String tokenid) {
		this.value = satoshis;
		this.tokenid = Utils.HEX.decode(tokenid);
    }

	public static Coin valueOf(final long satoshis) {
		return new Coin(satoshis, NetworkParameters.BIGTANGLE_TOKENID);
	}

	public static Coin valueOf(final long satoshis, byte[] tokenid) {
		return new Coin(satoshis, tokenid);
	}

	public static Coin valueOf(final long satoshis, String tokenid) {
		byte[] buf = Utils.HEX.decode(tokenid);
		return new Coin(satoshis, buf);
	}

	/**
	 * Returns the number of satoshis of this monetary value.
	 */
	@Override
	public BigInteger getValue() {
		return value;
	}

	public void setValue(BigInteger value) {
		this.value = value;
	}

	public String getTokenHex() {
		if (tokenid == null) {
			return "";
		}
        return Utils.HEX.encode(this.tokenid);
	}

	public Coin add(final Coin value) {
		if (!Arrays.equals(this.tokenid, value.tokenid)) {
			throw new IllegalArgumentException("!this.tokenid.equals( value.tokenid)");
		}
		return new Coin(this.value.add(value.value), value.tokenid);
	}

	/** Alias for add */
	public Coin plus(final Coin value) {
		return add(value);
	}

	public Coin subtract(final Coin value) {
		if (!Arrays.equals(this.tokenid, value.tokenid)) {
			throw new IllegalArgumentException("");
		}
		return new Coin(this.value.subtract(value.value), value.tokenid);
	}

	/** Alias for subtract */
	public Coin minus(final Coin value) {
		return subtract(value);
	}

	public Coin multiply(final long factor) {
		return new Coin(this.value.multiply(BigInteger.valueOf(factor)), this.tokenid);
	}

	/** Alias for multiply */
	public Coin times(final long factor) {
		return multiply(factor);
	}

	/** Alias for multiply */
	public Coin times(final int factor) {
		return multiply(factor);
	}

	public BigInteger divide(final Coin divisor) {
		return this.value.divide(divisor.value);
	}

	public Coin divide(final long divisor) {
		return new Coin(this.value.divide(BigInteger.valueOf(divisor)), this.tokenid);
	}

	/**
	 * Returns true if and only if this instance represents a monetary value greater
	 * than zero, otherwise false.
	 */
	@Transient
	public boolean isPositive() {
		return signum() == 1;
	}

	/**
	 * Returns true if and only if this instance represents a monetary value less
	 * than zero, otherwise false.
	 */
	@Transient
	public boolean isNegative() {
		return signum() == -1;
	}

	/**
	 * Returns true if and only if this instance represents zero monetary value,
	 * otherwise false.
	 */
	@Transient
	public boolean isZero() {
		return signum() == 0;
	}

	public boolean isBIG() {
		return Arrays.equals(this.tokenid, NetworkParameters.BIGTANGLE_TOKENID);

	}

	/**
	 * Returns true if the monetary value represented by this instance is greater
	 * than that of the given other Coin, otherwise false.
	 */
	public boolean isGreaterThan(Coin other) {
		return compareTo(other) > 0;
	}

	/**
	 * Returns true if the monetary value represented by this instance is less than
	 * that of the given other Coin, otherwise false.
	 */
	public boolean isLessThan(Coin other) {
		return compareTo(other) < 0;
	}

	@Override
	public int signum() {
		return this.value.signum();
	}

	public Coin negate() {
		return new Coin(this.value.negate(), this.tokenid);
	}

	@Override
	public String toString() {
		return "[" + value + ":" + getTokenHex() + "]";
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + Arrays.hashCode(tokenid);
		result = prime * result + ((value == null) ? 0 : value.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Coin other = (Coin) obj;
		if (!Arrays.equals(tokenid, other.tokenid))
			return false;
		if (value == null) {
            return other.value == null;
		} else return value.equals(other.value);
    }

	@Override
	public int compareTo(final Coin other) {
		return this.value.compareTo(other.value);
	}

	public byte[] getTokenid() {
		return tokenid;
	}

}
