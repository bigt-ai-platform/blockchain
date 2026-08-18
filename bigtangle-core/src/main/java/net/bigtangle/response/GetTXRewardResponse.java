/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.response;

import net.bigtangle.core.TXReward;

public class GetTXRewardResponse extends AbstractResponse {
  
    private   TXReward txReward;

    /** Highest justified Casper checkpoint of the responding node (hex). */
    private String justifiedBlockHash;
    /** Highest finalized Casper checkpoint of the responding node (hex). */
    private String finalizedBlockHash;
    private Long justifiedEpoch;
    private Long finalizedEpoch;
  
 

    public static GetTXRewardResponse create( TXReward txReward) {
        GetTXRewardResponse res = new GetTXRewardResponse();
        res.txReward = txReward;
        return res;
    }



    public TXReward getTxReward() {
        return txReward;
    }



    public void setTxReward(TXReward txReward) {
        this.txReward = txReward;
    }

    public String getJustifiedBlockHash() {
        return justifiedBlockHash;
    }

    public void setJustifiedBlockHash(String justifiedBlockHash) {
        this.justifiedBlockHash = justifiedBlockHash;
    }

    public String getFinalizedBlockHash() {
        return finalizedBlockHash;
    }

    public void setFinalizedBlockHash(String finalizedBlockHash) {
        this.finalizedBlockHash = finalizedBlockHash;
    }

    public Long getJustifiedEpoch() {
        return justifiedEpoch;
    }

    public void setJustifiedEpoch(Long justifiedEpoch) {
        this.justifiedEpoch = justifiedEpoch;
    }

    public Long getFinalizedEpoch() {
        return finalizedEpoch;
    }

    public void setFinalizedEpoch(Long finalizedEpoch) {
        this.finalizedEpoch = finalizedEpoch;
    }
 
}
