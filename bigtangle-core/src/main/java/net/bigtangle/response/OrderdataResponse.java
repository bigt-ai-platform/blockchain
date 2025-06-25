/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.response;

import java.util.List;
import java.util.Map;

import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.Token;

public class OrderdataResponse extends AbstractResponse {

    private List<OrderRecord> allOrdersSorted;
    private Map<String, Token> tokennames;
    public static AbstractResponse createOrderRecordResponse(List<OrderRecord> allOrdersSorted,Map<String, Token> tokennames) {
        OrderdataResponse res = new OrderdataResponse();
        res.allOrdersSorted = allOrdersSorted;
        res.tokennames =tokennames;
        return res;
    }

    public List<OrderRecord> getAllOrdersSorted() {
        return allOrdersSorted;
    }

    public void setAllOrdersSorted(List<OrderRecord> allOrdersSorted) {
        this.allOrdersSorted = allOrdersSorted;
    }

    public Map<String, Token> getTokennames() {
        return tokennames;
    }

    public void setTokennames(Map<String, Token> tokennames) {
        this.tokennames = tokennames;
    }

}
