/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.SecureRandom;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.BufferedBlockCipher;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.KeyGenerationParameters;
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.generators.EphemeralKeyPairGenerator;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.modes.SICBlockCipher;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.IESParameters;
import org.bouncycastle.crypto.params.IESWithCipherParameters;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.bouncycastle.crypto.parsers.ECIESPublicKeyParser;
import org.bouncycastle.math.ec.ECPoint;

import net.bigtangle.core.ECKey;


public class ECIESCoder {


    public static final int KEY_SIZE = 128;

    public static byte[] decrypt(BigInteger privKey, byte[] cipher) throws IOException, InvalidCipherTextException {
        return decrypt(privKey, cipher, null);
    }

    public static byte[] decrypt(BigInteger privKey, byte[] cipher, byte[] macData) throws IOException, InvalidCipherTextException {

        byte[] plaintext;

        ByteArrayInputStream is = new ByteArrayInputStream(cipher);
        byte[] ephemBytes = new byte[2*((ECKey.CURVE.getCurve().getFieldSize()+7)/8) + 1];

        is.read(ephemBytes);
        ECPoint ephem = ECKey.CURVE.getCurve().decodePoint(ephemBytes);
        byte[] IV = new byte[KEY_SIZE /8];
        is.read(IV);
        byte[] cipherBody = new byte[is.available()];
        is.read(cipherBody);

        plaintext = decrypt(ephem, privKey, IV, cipherBody, macData);

        return plaintext;
    }

    public static byte[] decrypt(ECPoint ephem, BigInteger prv, byte[] IV, byte[] cipher, byte[] macData) throws InvalidCipherTextException {
        AESEngine aesFastEngine = new AESEngine();

        IESEngine iesEngine = new IESEngine(
                new ECDHBasicAgreement(),
                new ConcatKDFBytesGenerator(new SHA256Digest()),
                new HMac(new SHA256Digest()),
                new SHA256Digest(),
                new BufferedBlockCipher(new SICBlockCipher(aesFastEngine)));


        byte[]         d = new byte[] {};
        byte[]         e = new byte[] {};

        IESParameters p = new IESWithCipherParameters(d, e, KEY_SIZE, KEY_SIZE);
        ParametersWithIV parametersWithIV =
                new ParametersWithIV(p, IV);

        iesEngine.init(false, new ECPrivateKeyParameters(prv, ECKey.CURVE), new ECPublicKeyParameters(ephem, ECKey.CURVE), parametersWithIV);

        return iesEngine.processBlock(cipher, 0, cipher.length, macData);
    }

    /**
     *  Encryption equivalent to the Crypto++ default ECIES<ECP> settings:
     *
     *  DL_KeyAgreementAlgorithm:        DL_KeyAgreementAlgorithm_DH<struct ECPPoint,struct EnumToType<enum CofactorMultiplicationOption,0> >
     *  DL_KeyDerivationAlgorithm:       DL_KeyDerivationAlgorithm_P1363<struct ECPPoint,0,class P1363_KDF2<class SHA1> >
     *  DL_SymmetricEncryptionAlgorithm: DL_EncryptionAlgorithm_Xor<class HMAC<class SHA1>,0>
     *  DL_PrivateKey:                   DL_Key<ECPPoint>
     *  DL_PrivateKey_EC<class ECP>
     *
     *  Used for Whisper V3
     */
    public static byte[] decryptSimple(BigInteger privKey, byte[] cipher) throws IOException, InvalidCipherTextException {
        IESEngine iesEngine = new IESEngine(
                new ECDHBasicAgreement(),
                new MGF1BytesGeneratorExt(new SHA1Digest(), 1),
                new HMac(new SHA1Digest()),
                new SHA1Digest(),
                null);

        IESParameters p = new IESParameters(null, null, KEY_SIZE);
        ParametersWithIV parametersWithIV = new ParametersWithIV(p, new byte[0]);

        iesEngine.setHashMacKey(false);

        iesEngine.init(new ECPrivateKeyParameters(privKey, ECKey.CURVE), parametersWithIV,
                new ECIESPublicKeyParser(ECKey.CURVE));

        return iesEngine.processBlock(cipher, 0, cipher.length);
    }

    public static byte[] encrypt(ECPoint toPub, byte[] plaintext) throws InvalidCipherTextException, IOException {
        return encrypt(toPub, plaintext, null);
    }

    public static byte[] encrypt(ECPoint toPub, byte[] plaintext, byte[] macData) throws InvalidCipherTextException, IOException {

        ECKeyPairGenerator eGen = new ECKeyPairGenerator();
        SecureRandom random = new SecureRandom();
        KeyGenerationParameters gParam = new ECKeyGenerationParameters(ECKey.CURVE, random);

        eGen.init(gParam);

        byte[] IV = new byte[KEY_SIZE/8];
        new SecureRandom().nextBytes(IV);

        AsymmetricCipherKeyPair ephemPair = eGen.generateKeyPair();
        BigInteger prv = ((ECPrivateKeyParameters)ephemPair.getPrivate()).getD();
        ECPoint pub = ((ECPublicKeyParameters)ephemPair.getPublic()).getQ();
        IESEngine iesEngine = makeIESEngine(true, toPub, prv, IV);


        ECKeyGenerationParameters keygenParams = new ECKeyGenerationParameters(ECKey.CURVE, random);
        ECKeyPairGenerator generator = new ECKeyPairGenerator();
        generator.init(keygenParams);

        ECKeyPairGenerator gen = new ECKeyPairGenerator();
        gen.init(new ECKeyGenerationParameters(ECKey.CURVE, random));

        byte[] cipher;
        
            cipher = iesEngine.processBlock(plaintext, 0, plaintext.length, macData);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bos.write(pub.getEncoded(false));
            bos.write(IV);
            bos.write(cipher);
            return bos.toByteArray();
      
    }

    /**
     *  Encryption equivalent to the Crypto++ default ECIES<ECP> settings:
     *
     *  DL_KeyAgreementAlgorithm:        DL_KeyAgreementAlgorithm_DH<struct ECPPoint,struct EnumToType<enum CofactorMultiplicationOption,0> >
     *  DL_KeyDerivationAlgorithm:       DL_KeyDerivationAlgorithm_P1363<struct ECPPoint,0,class P1363_KDF2<class SHA1> >
     *  DL_SymmetricEncryptionAlgorithm: DL_EncryptionAlgorithm_Xor<class HMAC<class SHA1>,0>
     *  DL_PrivateKey:                   DL_Key<ECPPoint>
     *  DL_PrivateKey_EC<class ECP>
     *
     *  Used for Whisper V3
     */
    public static byte[] encryptSimple(ECPoint pub, byte[] plaintext) throws IOException, InvalidCipherTextException {
        IESEngine iesEngine = new IESEngine(
                new ECDHBasicAgreement(),
                new MGF1BytesGeneratorExt(new SHA1Digest(), 1),
                new HMac(new SHA1Digest()),
                new SHA1Digest(),
                null);

        IESParameters p = new IESParameters(null, null, KEY_SIZE);
        ParametersWithIV parametersWithIV = new ParametersWithIV(p, new byte[0]);

        iesEngine.setHashMacKey(false);

        ECKeyPairGenerator eGen = new ECKeyPairGenerator();
        SecureRandom random = new SecureRandom();
        KeyGenerationParameters gParam = new ECKeyGenerationParameters(ECKey.CURVE, random);
        eGen.init(gParam);

//        AsymmetricCipherKeyPairGenerator testGen = new AsymmetricCipherKeyPairGenerator() {
//            ECKey priv = ECKey.fromPrivate(Hex.decode("d0b043b4c5d657670778242d82d68a29d25d7d711127d17b8e299f156dad361a"));
//
//            @Override
//            public void init(KeyGenerationParameters keyGenerationParameters) {
//            }
//
//            @Override
//            public AsymmetricCipherKeyPair generateKeyPair() {
//                return new AsymmetricCipherKeyPair(new ECPublicKeyParameters(priv.getPubKeyPoint(), CURVE),
//                        new ECPrivateKeyParameters(priv.getPrivKey(), CURVE));
//            }
//        };

        EphemeralKeyPairGenerator ephemeralKeyPairGenerator =
                new EphemeralKeyPairGenerator(/*testGen*/eGen, new ECIESPublicKeyEncoder());

        iesEngine.init(new ECPublicKeyParameters(pub, ECKey.CURVE), parametersWithIV, ephemeralKeyPairGenerator);

        return iesEngine.processBlock(plaintext, 0, plaintext.length);
    }


    private static IESEngine makeIESEngine(boolean isEncrypt, ECPoint pub, BigInteger prv, byte[] IV) {
        AESEngine aesFastEngine = new AESEngine();

        IESEngine iesEngine = new IESEngine(
                new ECDHBasicAgreement(),
                new ConcatKDFBytesGenerator(new SHA256Digest()),
                new HMac(new SHA256Digest()),
                new SHA256Digest(),
                new BufferedBlockCipher(new SICBlockCipher(aesFastEngine)));


        byte[]         d = new byte[] {};
        byte[]         e = new byte[] {};

        IESParameters p = new IESWithCipherParameters(d, e, KEY_SIZE, KEY_SIZE);
        ParametersWithIV parametersWithIV = new ParametersWithIV(p, IV);

        iesEngine.init(isEncrypt, new ECPrivateKeyParameters(prv, ECKey.CURVE), new ECPublicKeyParameters(pub, ECKey.CURVE), parametersWithIV);
        return iesEngine;
    }

    public static int getOverhead() {
        // 256 bit EC public key, IV, 256 bit MAC
        return 65 + KEY_SIZE/8 + 32;
    }
}
