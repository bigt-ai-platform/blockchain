/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
/*
 * Copyright 2013 Google Inc.
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
package net.bigtangle.crypto;

import com.google.common.base.Preconditions;

import net.bigtangle.core.Transaction;
import net.bigtangle.core.Transaction.SigHash;
import net.bigtangle.exception.VerificationException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DLSequence;

/**
 * A TransactionSignature wraps an ECDSA signature (r, s) and adds methods for handling
 * the additional SIGHASH mode byte that is used.
 */
public class TransactionSignature {
    public final BigInteger r;
    public final BigInteger s;
    public final int sighashFlags;

    public TransactionSignature(BigInteger r, BigInteger s) {
        this(r, s, Transaction.SigHash.ALL.value);
    }

    public TransactionSignature(BigInteger r, BigInteger s, int sighashFlags) {
        this.r = r;
        this.s = s;
        this.sighashFlags = sighashFlags;
    }

    public TransactionSignature(byte[] derEncoded) {
        this(derEncoded, Transaction.SigHash.ALL.value);
    }

    public TransactionSignature(byte[] derEncoded, int sighashFlags) {
        try {
            ASN1InputStream decoder = new ASN1InputStream(derEncoded);
            DLSequence seq = (DLSequence) decoder.readObject();
            this.r = ((ASN1Integer) seq.getObjectAt(0)).getPositiveValue();
            this.s = ((ASN1Integer) seq.getObjectAt(1)).getPositiveValue();
            decoder.close();
        } catch (Exception e) {
            throw new RuntimeException("Could not decode DER signature", e);
        }
        this.sighashFlags = sighashFlags;
    }

    public static TransactionSignature dummy() {
        BigInteger val = BigInteger.ONE;
        return new TransactionSignature(val, val);
    }

    public static int calcSigHashValue(Transaction.SigHash mode, boolean anyoneCanPay) {
        Preconditions.checkArgument(SigHash.ALL == mode || SigHash.NONE == mode || SigHash.SINGLE == mode);
        int sighashFlags = mode.value;
        if (anyoneCanPay)
            sighashFlags |= Transaction.SigHash.ANYONECANPAY.value;
        return sighashFlags;
    }

    public static boolean isEncodingCanonical(byte[] signature) {
        if (signature.length < 9 || signature.length > 73)
            return false;

        int hashType = (signature[signature.length-1] & 0xff) & ~Transaction.SigHash.ANYONECANPAY.value;
        if (hashType < Transaction.SigHash.ALL.value || hashType > Transaction.SigHash.SINGLE.value)
            return false;

        if ((signature[0] & 0xff) != 0x30 || (signature[1] & 0xff) != signature.length-3)
            return false;

        int lenR = signature[3] & 0xff;
        if (5 + lenR >= signature.length || lenR == 0)
            return false;
        int lenS = signature[5+lenR] & 0xff;
        if (lenR + lenS + 7 != signature.length || lenS == 0)
            return false;

        if (signature[4-2] != 0x02 || (signature[4] & 0x80) == 0x80)
            return false;
        if (lenR > 1 && signature[4] == 0x00 && (signature[4+1] & 0x80) != 0x80)
            return false;

        if (signature[6 + lenR - 2] != 0x02 || (signature[6 + lenR] & 0x80) == 0x80)
            return false;
        if (lenS > 1 && signature[6 + lenR] == 0x00 && (signature[6 + lenR + 1] & 0x80) != 0x80)
            return false;

        return true;
    }

    public boolean anyoneCanPay() {
        return (sighashFlags & Transaction.SigHash.ANYONECANPAY.value) != 0;
    }

    public Transaction.SigHash sigHashMode() {
        final int mode = sighashFlags & 0x1f;
        if (mode == Transaction.SigHash.NONE.value)
            return Transaction.SigHash.NONE;
        else if (mode == Transaction.SigHash.SINGLE.value)
            return Transaction.SigHash.SINGLE;
        else
            return Transaction.SigHash.ALL;
    }

    public byte[] encodeToBitcoin() {
        ByteArrayOutputStream bos = derByteStream();
        bos.write(sighashFlags);
        return bos.toByteArray();
    }

    public TransactionSignature toCanonicalised() {
        BigInteger canonicalS = s;
        if (s.compareTo(TransactionSignature.HALF_CURVE_ORDER) > 0)
            canonicalS = TransactionSignature.CURVE.getN().subtract(s);
        return new TransactionSignature(r, canonicalS, sighashFlags);
    }

    public static final BigInteger HALF_CURVE_ORDER;
    public static final org.bouncycastle.crypto.params.ECDomainParameters CURVE;

    static {
        org.bouncycastle.asn1.x9.X9ECParameters params = org.bouncycastle.asn1.sec.SECNamedCurves.getByName("secp256k1");
        CURVE = new org.bouncycastle.crypto.params.ECDomainParameters(params.getCurve(), params.getG(), params.getN(), params.getH());
        HALF_CURVE_ORDER = params.getN().shiftRight(1);
    }

    public ByteArrayOutputStream derByteStream() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(72);
        try {
            org.bouncycastle.asn1.DERSequenceGenerator seq = new org.bouncycastle.asn1.DERSequenceGenerator(bos);
            seq.addObject(new ASN1Integer(r));
            seq.addObject(new ASN1Integer(s));
            seq.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bos;
    }

    public static TransactionSignature decodeFromBitcoin(byte[] bytes,
                                                          boolean requireCanonicalEncoding,
                                                          boolean requireCanonicalSValue) throws VerificationException {
        if (requireCanonicalEncoding && !isEncodingCanonical(bytes))
            throw new VerificationException("Signature encoding is not canonical.");
        TransactionSignature sig;
        try {
            byte[] derBytes = new byte[bytes.length - 1];
            System.arraycopy(bytes, 0, derBytes, 0, derBytes.length);
            int hashType = bytes[bytes.length - 1] & 0xff;
            sig = new TransactionSignature(derBytes, hashType);
        } catch (Exception e) {
            throw new VerificationException("Could not decode DER", e);
        }
        if (requireCanonicalSValue && !sig.isCanonical())
            throw new VerificationException("S-value is not canonical.");
        return sig;
    }

    public boolean isCanonical() {
        return s.compareTo(HALF_CURVE_ORDER) <= 0;
    }
}
