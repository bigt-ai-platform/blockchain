/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.response;

import java.util.List;

import net.bigtangle.ordermatch.AskBid;

public class GetAskBidListResponse extends AbstractResponse {
    private List<AskBid> askbidlist;

    public static GetAskBidListResponse create(List<AskBid> askbidlist) {
        GetAskBidListResponse res = new GetAskBidListResponse();
        res.askbidlist = askbidlist;
        return res;
    }

    public List<AskBid> getAskbidlist() {
        return askbidlist;
    }

    public void setAskbidlist(List<AskBid> askbidlist) {
        this.askbidlist = askbidlist;
    }

    
  
}
