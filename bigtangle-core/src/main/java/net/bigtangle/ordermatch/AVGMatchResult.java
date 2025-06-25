package net.bigtangle.ordermatch;

import java.math.BigDecimal;

public class AVGMatchResult extends MatchResult {

    /**
     * 
     */
    private static final long serialVersionUID = 949271133615945378L;

    private BigDecimal avgprice;
    private String matchday;
    private long hignprice;
    private long lowprice;

    public BigDecimal getAvgprice() {
        return avgprice;
    }

    public void setAvgprice(BigDecimal avgprice) {
        this.avgprice = avgprice;
    }

    public String getMatchday() {
        return matchday;
    }

    public void setMatchday(String matchday) {
        this.matchday = matchday;
    }

    public long getHignprice() {
        return hignprice;
    }

    public void setHignprice(long hignprice) {
        this.hignprice = hignprice;
    }

    public long getLowprice() {
        return lowprice;
    }

    public void setLowprice(long lowprice) {
        this.lowprice = lowprice;
    }
}
