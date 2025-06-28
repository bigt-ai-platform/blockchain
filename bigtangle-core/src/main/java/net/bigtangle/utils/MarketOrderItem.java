package net.bigtangle.utils;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;

import net.bigtangle.core.ECKey;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.Token;
import net.bigtangle.params.NetworkParameters;

public class MarketOrderItem implements java.io.Serializable {

    /**
     * 
     */
    private static final long serialVersionUID = -4622211003160381995L;

    public static MarketOrderItem build(OrderRecord orderRecord, Map<String, Token> tokennames,
            NetworkParameters networkParameters, String buy, String sell ) {
        MonetaryFormat mf = MonetaryFormat.FIAT.noCode();
        MarketOrderItem marketOrderItem = new MarketOrderItem();
        Token base = tokennames.get(orderRecord.getOrderBaseToken());
        Integer priceshift = networkParameters.getOrderPriceShift(orderRecord.getOrderBaseToken());
        if (orderRecord.getOrderBaseToken().equals(orderRecord.getOfferTokenid())) {
            Token t = tokennames.get(orderRecord.getTargetTokenid());
            marketOrderItem.setType(buy);
            marketOrderItem.setAmount( new BigDecimal(mf.format(orderRecord.getTargetValue(), t.getDecimals())));
            marketOrderItem.setTokenId(orderRecord.getTargetTokenid());
            marketOrderItem.setTokenName(t.getTokennameDisplay());
            marketOrderItem.setPrice(new BigDecimal(mf.format(orderRecord.getPrice(), base.getDecimals() + priceshift)));
            marketOrderItem.setTotal(new BigDecimal(mf.format(orderRecord.getOfferValue(), base.getDecimals())));
        } else {
            Token t = tokennames.get(orderRecord.getOfferTokenid());
            marketOrderItem.setType(sell);
            marketOrderItem.setAmount(new BigDecimal(mf.format(orderRecord.getOfferValue(), t.getDecimals())));
            marketOrderItem.setTokenId(orderRecord.getOfferTokenid());
            marketOrderItem.setTokenName(t.getTokennameDisplay());
            marketOrderItem.setPrice(new BigDecimal(mf.format(orderRecord.getPrice(), base.getDecimals() + priceshift)));
            marketOrderItem.setTotal(new BigDecimal(mf.format(orderRecord.getTargetValue(), base.getDecimals())));
        } 
      
        marketOrderItem.setValidateTo( new Date(orderRecord.getValidToTime() * 1000) );
        marketOrderItem.setValidateFrom( new Date(orderRecord.getValidFromTime() * 1000)) ;
        marketOrderItem.setAddress(
                ECKey.fromPublicOnly(orderRecord.getBeneficiaryPubKey()).toAddress(networkParameters).toString());
        marketOrderItem.setInitialBlockHashHex(orderRecord.getBlockHashHex());
        marketOrderItem.setCancelPending(orderRecord.isCancelPending());
        marketOrderItem.setOrderBaseToken(base.getTokennameDisplay());
        return marketOrderItem;
    }

    private String type;

    private BigDecimal amount;

    private String tokenId;

    private BigDecimal price;

    private String orderId;

    private Date validateTo;

    private Date validateFrom;

    private String address;

    private String initialBlockHashHex;

    private String tokenName;

    private boolean cancelPending;

    private BigDecimal total;

    private String orderBaseToken;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTokenId() {
        return tokenId;
    }

    public void setTokenId(String tokenId) {
        this.tokenId = tokenId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public Date getValidateTo() {
        return validateTo;
    }

    public void setValidateTo(Date validateTo) {
        this.validateTo = validateTo;
    }

    public Date getValidateFrom() {
        return validateFrom;
    }

    public void setValidateFrom(Date validateFrom) {
        this.validateFrom = validateFrom;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getInitialBlockHashHex() {
        return initialBlockHashHex;
    }

    public void setInitialBlockHashHex(String initialBlockHashHex) {
        this.initialBlockHashHex = initialBlockHashHex;
    }

    public String getTokenName() {
        return tokenName;
    }

    public void setTokenName(String tokenName) {
        this.tokenName = tokenName;
    }

    public boolean isCancelPending() {
        return cancelPending;
    }

    public void setCancelPending(boolean cancelPending) {
        this.cancelPending = cancelPending;
    }

    public String getOrderBaseToken() {
        return orderBaseToken;
    }

    public void setOrderBaseToken(String orderBaseToken) {
        this.orderBaseToken = orderBaseToken;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(BigDecimal total) {
        this.total = total;
    }

}
