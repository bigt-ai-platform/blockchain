package net.bigtangle.core;

public class ByteResp implements java.io.Serializable {

    private static final long serialVersionUID = -7889974408476754383L;
    
    private byte[] data;

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }
}
