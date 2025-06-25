/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.response;

import java.util.List;

import net.bigtangle.core.TXReward;

public class GetTXRewardListResponse extends AbstractResponse {
  
    private   List<TXReward> txReward;
  
 

    public static GetTXRewardListResponse create( List<TXReward> txReward) {
        GetTXRewardListResponse res = new GetTXRewardListResponse();
        res.txReward = txReward;
        return res;
    }



    public List<TXReward> getTxReward() {
        return txReward;
    }



    public void setTxReward(List<TXReward> txReward) {
        this.txReward = txReward;
    }
 
}
