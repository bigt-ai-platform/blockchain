package net.bigtangle.core;

public class PermissionDomainname {

    private String pubKeyHex;

    private String priKeyHex;

    public PermissionDomainname() {
    }

    public PermissionDomainname(String pubKeyHex, String priKeyHex) {
        this.pubKeyHex = pubKeyHex;
        this.priKeyHex = priKeyHex;
    }

    public String getPubKeyHex() {
        return pubKeyHex;
    }

    public void setPubKeyHex(String pubKeyHex) {
        this.pubKeyHex = pubKeyHex;
    }

    public String getPriKeyHex() {
        return priKeyHex;
    }

    public void setPriKeyHex(String priKeyHex) {
        this.priKeyHex = priKeyHex;
    }

    public byte[] getPriKeyBuf() {
        return Utils.HEX.decode(this.priKeyHex);
    }

    public byte[] getPubKeyBuf() {
        return Utils.HEX.decode(this.pubKeyHex);
    }

    public PQKey getOutKey() {
     //   byte[] privKeyBytes = this.getPriKeyBuf();
        byte[] pubKey = this.getPubKeyBuf();

        PQKey outKey = PQKey.fromPublicOnly(  pubKey);
        return outKey;
    }
}
