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
import java.nio.charset.StandardCharsets;

public class OrderOpenInfo extends DataClass implements java.io.Serializable {

    private static final long FROMTIME = System.currentTimeMillis() / 1000 - 5;
    private static final long serialVersionUID = 433387247051352702L;

    private long targetValue;
    private String targetTokenid;
    // public key is needed for verify
    private byte[] beneficiaryPubKey;
    // valid until this date, maximum is set in Network parameter
    private Long validToTime;
    // valid from this date, maximum is set in Network parameter
    private Long validFromTime;
    // owner public address of the order for query
    private String beneficiaryAddress;
    // Base token for the order
    private String orderBaseToken;
    // price from the order
    private Long price;

    private long offerValue;
    private String offerTokenid;
     
    public OrderOpenInfo() {
        super();
    }

    public OrderOpenInfo(long targetValue, String targetTokenid, byte[] beneficiaryPubKey, Long validToTimeMilli,
            Long validFromTimeMilli, Side side, String beneficiaryAddress, String orderBaseToken, Long price,
            long offerValue, String offerTokenid) {
        super();
        setVersion(2);
        this.targetValue = targetValue;
        this.targetTokenid = targetTokenid;
        this.beneficiaryPubKey = beneficiaryPubKey;
        if (validFromTimeMilli == null) {
            this.validFromTime = FROMTIME;
        } else {
            this.validFromTime = validFromTimeMilli / 1000;
        }
        if (validToTimeMilli == null) {
            this.validToTime = validFromTime + NetworkParameters.ORDER_TIMEOUT_MAX;
        } else {
            this.validToTime = Math.min(validToTimeMilli / 1000, validFromTime + NetworkParameters.ORDER_TIMEOUT_MAX);
        }
        this.beneficiaryAddress = beneficiaryAddress;
        this.orderBaseToken = orderBaseToken;
        this.price = price;
        this.offerValue = offerValue;
        this.offerTokenid = offerTokenid;
    }

    public byte[] toByteArray() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            DataOutputStream dos = new DataOutputStream(baos);

            dos.write(super.toByteArray());

            dos.writeLong(targetValue);
            dos.writeLong(validToTime);
            dos.writeLong(validFromTime);
            dos.writeInt(beneficiaryPubKey.length);
            dos.write(beneficiaryPubKey);

            dos.writeBoolean(targetTokenid != null);
            if (targetTokenid != null) {
                dos.writeInt(targetTokenid.getBytes(StandardCharsets.UTF_8).length);
                dos.write(targetTokenid.getBytes(StandardCharsets.UTF_8));
            }

            dos.writeBoolean(beneficiaryAddress != null);
            if (beneficiaryAddress != null) {
                dos.writeInt(beneficiaryAddress.getBytes(StandardCharsets.UTF_8).length);
                dos.write(beneficiaryAddress.getBytes(StandardCharsets.UTF_8));
            }
            dos.writeBoolean(orderBaseToken != null);
            if (orderBaseToken != null) {
                dos.writeInt(orderBaseToken.getBytes(StandardCharsets.UTF_8).length);
                dos.write(orderBaseToken.getBytes(StandardCharsets.UTF_8));
            }
            dos.writeLong(price);
            dos.writeLong(offerValue);
            dos.writeBoolean(offerTokenid != null);
            if (offerTokenid != null) {
                dos.writeInt(offerTokenid.getBytes(StandardCharsets.UTF_8).length);
                dos.write(offerTokenid.getBytes(StandardCharsets.UTF_8));
            }
            dos.close();
        } catch (IOException e) {
           throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }

    public OrderOpenInfo parseDIS(DataInputStream dis) throws IOException {
        super.parseDIS(dis); 
        targetValue = dis.readLong();
        validToTime = dis.readLong();
        validFromTime = dis.readLong();
        int size = dis.readInt();
        beneficiaryPubKey = new byte[size];
        dis.readFully(beneficiaryPubKey);
        targetTokenid = Utils.readNBytesString(dis);
        beneficiaryAddress = Utils.readNBytesString(dis);
        if (getVersion() > 1) {
            orderBaseToken = Utils.readNBytesString(dis);
            price = dis.readLong();
            offerValue = dis.readLong();
            offerTokenid = Utils.readNBytesString(dis);      
        } else {
            orderBaseToken = NetworkParameters.BIGTANGLE_TOKENID_STRING; 
             
        }
        return this;
    }

    public OrderOpenInfo parse(byte[] buf) throws IOException {
        ByteArrayInputStream bain = new ByteArrayInputStream(buf);
        DataInputStream dis = new DataInputStream(bain);

        parseDIS(dis);

        dis.close();
        bain.close();
        return this;
    }

    
    public boolean buy() {
        return  !targetTokenid.equals( getOrderBaseToken());
    }
    @Override
    public String toString() {
        return "OrderOpenInfo  \n targetValue=" + targetValue + ", \n targetTokenid=" + targetTokenid
                + ", \n validToTime=" + validToTime + ",  \n validFromTime=" + validFromTime
                + ", \n beneficiaryAddress=" + beneficiaryAddress + " \n offerValue=" + offerValue
                + ", \n offerTokenid=" + offerTokenid + ", \n orderBaseToken=" + orderBaseToken
                + ", \n price=" + price;
    }

    public Long getValidToTime() {
        return validToTime;
    }

    public void setValidToTime(Long validToTime) {
        this.validToTime = validToTime;
    }

    public Long getValidFromTime() {
        return validFromTime;
    }

    public void setValidFromTime(Long validFromTime) {
        this.validFromTime = validFromTime;
    }

    public String getBeneficiaryAddress() {
        return beneficiaryAddress;
    }

    public void setBeneficiaryAddress(String beneficiaryAddress) {
        this.beneficiaryAddress = beneficiaryAddress;
    }

    public String getOrderBaseToken() {
        return orderBaseToken;
    }

    public void setOrderBaseToken(String orderBaseToken) {
        this.orderBaseToken = orderBaseToken;
    }

    public Long getPrice() {
        return price;
    }

    public void setPrice(Long price) {
        this.price = price;
    }

    public byte[] getBeneficiaryPubKey() {
        return beneficiaryPubKey;
    }

    public void setBeneficiaryPubKey(byte[] beneficiaryPubKey) {
        this.beneficiaryPubKey = beneficiaryPubKey;
    }

    public long getTargetValue() {
        return targetValue;
    }

    public void setTargetValue(long targetValue) {
        this.targetValue = targetValue;
    }

    public String getTargetTokenid() {
        return targetTokenid;
    }

    public void setTargetTokenid(String targetTokenid) {
        this.targetTokenid = targetTokenid;
    }

    public long getOfferValue() {
        return offerValue;
    }

    public void setOfferValue(long offerValue) {
        this.offerValue = offerValue;
    }

    public String getOfferTokenid() {
        return offerTokenid;
    }

    public void setOfferTokenid(String offerTokenid) {
        this.offerTokenid = offerTokenid;
    }

}
