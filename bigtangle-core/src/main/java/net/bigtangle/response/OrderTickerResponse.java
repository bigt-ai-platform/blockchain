/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.response;

import java.util.List;
import java.util.Map;

import net.bigtangle.core.Token;
import net.bigtangle.ordermatch.MatchLastdayResult;

public class OrderTickerResponse extends AbstractResponse {

    private List<MatchLastdayResult> tickers;
    
    private Map<String, Token> tokennames;
    public static OrderTickerResponse createOrderRecordResponse(List<MatchLastdayResult> tickers,Map<String, Token> tokennames) {
        OrderTickerResponse res = new OrderTickerResponse();
        res.tickers =  tickers;
        res.tokennames =tokennames;
        return res;
    }
   
    public List<MatchLastdayResult> getTickers() {
        return tickers;
    }
    public void setTickers(List<MatchLastdayResult> tickers) {
        this.tickers = tickers;
    }
    public Map<String, Token> getTokennames() {
        return tokennames;
    }
    public void setTokennames(Map<String, Token> tokennames) {
        this.tokennames = tokennames;
    } 

 
}
