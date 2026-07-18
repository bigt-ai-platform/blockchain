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
import java.util.ArrayList;
import java.util.List;

public class TokenInfo extends DataClass implements java.io.Serializable {

    private static final long serialVersionUID = 1554582498768357964L;

    private Token token;
    private List<MultiSignAddress> multiSignAddresses;

    public byte[] toByteArray() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            if (token != null) {
                byte[] tBytes = token.toByteArray();
                dos.writeInt(tBytes.length);
                dos.write(tBytes);
            } else {
                dos.writeInt(0);
            }
            if (multiSignAddresses != null) {
                dos.writeInt(multiSignAddresses.size());
                for (MultiSignAddress msa : multiSignAddresses) {
                    byte[] mBytes = msa.toByteArray();
                    dos.writeInt(mBytes.length);
                    dos.write(mBytes);
                }
            } else {
                dos.writeInt(0);
            }
            dos.close();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public TokenInfo parse(byte[] buf) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(buf));
        int tLen = dis.readInt();
        if (tLen > 0) {
            byte[] tBytes = new byte[tLen];
            dis.readFully(tBytes);
            this.token = new Token().parse(tBytes);
        }
        int mSize = dis.readInt();
        this.multiSignAddresses = new ArrayList<>();
        for (int i = 0; i < mSize; i++) {
            int mLen = dis.readInt();
            byte[] mBytes = new byte[mLen];
            dis.readFully(mBytes);
            this.multiSignAddresses.add(MultiSignAddress.parse(mBytes));
        }
        dis.close();
        return this;
    }

    public TokenInfo parseChecked(byte[] buf) {
        try {
            return parse(buf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Token getToken() {
        return token;
    }

    public void setToken(Token tokens) {
        this.token = tokens;
    }

    public List<MultiSignAddress> getMultiSignAddresses() {
        return multiSignAddresses;
    }

    public void setMultiSignAddresses(List<MultiSignAddress> multiSignAddresses) {
        this.multiSignAddresses = multiSignAddresses;
    }

    public TokenInfo() {
        this.multiSignAddresses = new ArrayList<>();
    }

    @Override
    public String toString() {
        return "TokenInfo [tokens=" + token + ", multiSignAddresses=" + multiSignAddresses + "]";
    }
}
