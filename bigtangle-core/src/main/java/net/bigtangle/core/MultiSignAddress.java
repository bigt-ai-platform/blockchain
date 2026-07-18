/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class MultiSignAddress implements java.io.Serializable {

    private static final long serialVersionUID = -2956933642847534834L;

    private Sha256Hash blockhash;
    private String tokenid;
    private String address;
    private String pubKeyHex;
    private int posIndex;
    private int tokenHolder;

    public int getPosIndex() {
        return posIndex;
    }

    public void setPosIndex(int posIndex) {
        this.posIndex = posIndex;
    }

    public String getPubKeyHex() {
        return pubKeyHex;
    }

    public void setPubKeyHex(String pubKeyHex) {
        this.pubKeyHex = pubKeyHex;
    }

    public String getTokenid() {
        return tokenid;
    }

    public void setTokenid(String tokenid) {
        this.tokenid = tokenid;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public Sha256Hash getBlockhash() {
        return blockhash;
    }

    public void setBlockhash(Sha256Hash blockhash) {
        this.blockhash = blockhash;
    }

    public int getTokenHolder() {
        return tokenHolder;
    }

    public void setTokenHolder(int tokenHolder) {
        this.tokenHolder = tokenHolder;
    }

    public MultiSignAddress(String tokenid, String address, String pubKeyHex, int tokenHolder) {
        this.tokenid = tokenid;
        this.address = address;
        this.pubKeyHex = pubKeyHex;
        this.tokenHolder = tokenHolder;
    }

    public MultiSignAddress(String tokenid, String address, String pubKeyHex) {
        this(tokenid, address, pubKeyHex, 1);
    }

    public MultiSignAddress() {
    }

    public byte[] toByteArray() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            Utils.writeNBytesString(dos, tokenid);
            Utils.writeNBytesString(dos, address);
            Utils.writeNBytesString(dos, pubKeyHex);
            dos.writeInt(posIndex);
            dos.writeInt(tokenHolder);
            dos.writeBoolean(blockhash != null);
            if (blockhash != null) dos.write(blockhash.getBytes());
            dos.close();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static MultiSignAddress parse(byte[] buf) throws IOException {
        return parseDIS(new DataInputStream(new ByteArrayInputStream(buf)));
    }

    public static MultiSignAddress parseDIS(DataInputStream dis) throws IOException {
        MultiSignAddress m = new MultiSignAddress();
        m.tokenid = Utils.readNBytesString(dis);
        m.address = Utils.readNBytesString(dis);
        m.pubKeyHex = Utils.readNBytesString(dis);
        m.posIndex = dis.readInt();
        m.tokenHolder = dis.readInt();
        if (dis.readBoolean()) {
            byte[] hbuf = new byte[32];
            dis.readFully(hbuf);
            m.blockhash = Sha256Hash.wrap(hbuf);
        }
        return m;
    }

    @Override
    public String toString() {
        return "MultiSignAddress [blockhash=" + blockhash + ", tokenid=" + tokenid + ", address=" + address
                + ", pubKeyHex=" + pubKeyHex + ", posIndex=" + posIndex + ", tokenHolder=" + tokenHolder + "]";
    }
    
}
