package net.bigtangle.apps.data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.SignatureException;

import org.bouncycastle.crypto.InvalidCipherTextException;

import net.bigtangle.core.DataClass;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.KeyValue;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TokenKeyValues;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.ECIESCoder;
import net.bigtangle.crypto.pq.PQScriptUtils;
import net.bigtangle.crypto.pq.SignatureBundle;
import net.bigtangle.exception.NoSignedDataException;

public class SignedData extends DataClass implements java.io.Serializable {
    /**
     * 
     */
    private static final long serialVersionUID = 1L;

    // dataClassName of serialized data
    String dataClassName;

    // serialized data Utils.HEX.encode before encryption
    String serializedData;
    // used for verify this message
    private byte[] signerpubkey;
    private String signature;

    // milliseconds, not encrypted, can be null
    private Long validtodate;

    public void verify() throws SignatureException {
        Sha256Hash hash = Sha256Hash.of(serializedData.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] sigBytes = Utils.HEX.decode(signature);
        if (!PQScriptUtils.verifyPQ(signerpubkey, sigBytes, hash))
            throw new SignatureException("Signature verification failed");
    }

    public void signMessage(PQKey key) throws SignatureException {
        Sha256Hash hash = Sha256Hash.of(serializedData.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        SignatureBundle sigBundle = key.sign(hash);
        signature = Utils.HEX.encode(sigBundle.serialize());
    }

    public void setSerializedData(byte[] byteData) {
        this.serializedData = Utils.HEX.encode(byteData);
    }

    public byte[] toByteArray() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            DataOutputStream dos = new DataOutputStream(baos);
            dos.write(super.toByteArray());
            Utils.writeNBytesString(dos, dataClassName);
            Utils.writeNBytesString(dos, serializedData);
            Utils.writeNBytes(dos, signerpubkey);
            Utils.writeNBytesString(dos, signature);
            Utils.writeLong(dos, validtodate);

            dos.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }

    public SignedData parse(byte[] buf) throws IOException {
        ByteArrayInputStream bain = new ByteArrayInputStream(buf);
        DataInputStream dis = new DataInputStream(bain);

        parseDIS(dis);

        dis.close();
        bain.close();
        return this;
    }

    public SignedData parseDIS(DataInputStream dis) throws IOException {
        super.parseDIS(dis);
        dataClassName = Utils.readNBytesString(dis);
        serializedData = Utils.readNBytesString(dis);
        signerpubkey = Utils.readNBytes(dis);
        signature = Utils.readNBytesString(dis);
        validtodate = Utils.readLong(dis);

        dis.close();

        return this;
    }

    public MemoInfo encryptToMemo(PQKey userkey) throws InvalidCipherTextException, IOException {
        String memoHex = Utils.HEX.encode(this.toByteArray());
        MemoInfo memoInfo = new MemoInfo();
        memoInfo.addEncryptMemo(memoHex);
        return memoInfo;
    }

    public static SignedData decryptFromMemo(PQKey userkey, MemoInfo memoInfo)
            throws InvalidCipherTextException, IOException, SignatureException, NoSignedDataException {
        for (KeyValue keyValue : memoInfo.getKv()) {
            if (keyValue.getKey().equals(MemoInfo.ENCRYPT)) {
                byte[] decryptedPayload = Utils.HEX.decode(keyValue.getValue());
                SignedData sdata = new SignedData().parse(decryptedPayload);
                sdata.verify();
                return sdata;
            }
        }
        throw new NoSignedDataException();
    }

    /*
     * transform the data with encryption to be saved in token
     */
    public TokenKeyValues toTokenKeyValues(PQKey key, PQKey userkey)
            throws InvalidCipherTextException, IOException, SignatureException {

        byte[] data = this.toByteArray();

        TokenKeyValues tokenKeyValues = new TokenKeyValues();

        KeyValue kv = new KeyValue();
        kv.setKey(key.getPublicKeyAsHex());
        kv.setValue(Utils.HEX.encode(data));
        tokenKeyValues.addKeyvalue(kv);
        if (!key.getPublicKeyAsHex().equals(userkey.getPublicKeyAsHex())) {
            kv = new KeyValue();
            kv.setKey(userkey.getPublicKeyAsHex());
            kv.setValue(Utils.HEX.encode(data));
            tokenKeyValues.addKeyvalue(kv);
        }

        return tokenKeyValues;
    }

    public void signData(PQKey signkey, byte[] originalData, String dataClassname) throws SignatureException {
        setSerializedData(originalData);
        setSignerpubkey(signkey.getPubKey());
        setDataClassName(dataClassname);
        signMessage(signkey);
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getSerializedData() {
        return serializedData;
    }

    public void setSerializedData(String serializedData) {
        this.serializedData = serializedData;
    }

    public String getDataClassName() {
        return dataClassName;
    }

    public void setDataClassName(String dataClassName) {
        this.dataClassName = dataClassName;
    }

    public byte[] getSignerpubkey() {
        return signerpubkey;
    }

    public void setSignerpubkey(byte[] signerpubkey) {
        this.signerpubkey = signerpubkey;
    }

    public Long getValidtodate() {
        return validtodate;
    }

    public void setValidtodate(Long validtodate) {
        this.validtodate = validtodate;
    }

}
