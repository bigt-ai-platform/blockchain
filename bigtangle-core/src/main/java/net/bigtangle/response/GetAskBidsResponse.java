/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.response;

import java.math.BigDecimal;
import java.util.Map;
import java.util.NavigableMap;

public class GetAskBidsResponse extends AbstractResponse {

 

    private   Map<String, NavigableMap<BigDecimal, BigDecimal>> askbids;
    
    public static GetAskBidsResponse create(Map<String, NavigableMap<BigDecimal, BigDecimal>> askbids) {
        GetAskBidsResponse res = new GetAskBidsResponse();
        res.askbids = askbids;
     
        return res;
    }

    public Map<String, NavigableMap<BigDecimal, BigDecimal>> getAskbids() {
        return askbids;
    }

    public void setAskbids(Map<String, NavigableMap<BigDecimal, BigDecimal>> askbids) {
        this.askbids = askbids;
    }
 
}
