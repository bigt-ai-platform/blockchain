package net.bigtangle.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.TreeMap;

public class TokensumsMap extends DataClass implements java.io.Serializable {
    /**
     * 
     */
    private static final long serialVersionUID = 1L;
    TreeMap<String, Tokensums> tokensumsMap;

    /*
     * calculate the signature of Tokensums. The List must be sorted unique.
     */
    public Sha256Hash hash() {
        return Sha256Hash.of(toByteArray());
    }

    public byte[] toByteArray() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            DataOutputStream dos = new DataOutputStream(baos);
            dos.write(super.toByteArray());
            for (String key : tokensumsMap.keySet()) {
                Utils.writeNBytesString(dos, key);
                dos.write(tokensumsMap.get(key).toByteArray());
            }
            dos.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }

    

    public TokensumsMap() {
        tokensumsMap = new TreeMap<>();
    }

 
    public TreeMap<String, Tokensums> getTokensumsMap() {
        return tokensumsMap;
    }

    public void setTokensumsMap(TreeMap<String, Tokensums> tokensumsMap) {
        this.tokensumsMap = tokensumsMap;
    }

}
