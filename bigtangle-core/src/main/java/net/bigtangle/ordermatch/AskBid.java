package net.bigtangle.ordermatch;

import java.math.BigDecimal;

public class AskBid {

    private String tradesystem;

    private long eventTime;

    private String symbol;

    private BigDecimal askBestPrice;

    private BigDecimal askBestAmount;
    
    private BigDecimal bidBestPrice;

    private BigDecimal bidBestAmount; 

    public String getTradesystem() {
        return tradesystem;
    }

    public void setTradesystem(String tradesystem) {
        this.tradesystem = tradesystem;
    }

    public long getEventTime() {
        return eventTime;
    }

    public void setEventTime(long eventTime) {
        this.eventTime = eventTime;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public BigDecimal getAskBestPrice() {
        return askBestPrice;
    }

    public void setAskBestPrice(BigDecimal askBestPrice) {
        this.askBestPrice = askBestPrice;
    }

    public BigDecimal getAskBestAmount() {
        return askBestAmount;
    }

    public void setAskBestAmount(BigDecimal askBestAmount) {
        this.askBestAmount = askBestAmount;
    }

    public BigDecimal getBidBestPrice() {
        return bidBestPrice;
    }

    public void setBidBestPrice(BigDecimal bidBestPrice) {
        this.bidBestPrice = bidBestPrice;
    }

    public BigDecimal getBidBestAmount() {
        return bidBestAmount;
    }

    public void setBidBestAmount(BigDecimal bidBestAmount) {
        this.bidBestAmount = bidBestAmount;
    }

    @Override
    public String toString() {
        return "AskBid [tradesystem=" + tradesystem + ", eventTime=" + eventTime + ", symbol=" + symbol
                + ", askBestPrice=" + askBestPrice + ", askBestAmount=" + askBestAmount + ", bidBestPrice="
                + bidBestPrice + ", bidBestAmount=" + bidBestAmount + "]";
    }

   
}
