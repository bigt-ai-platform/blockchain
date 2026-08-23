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
    /**
     * Reward-chain length of the responding node's finalized checkpoint.
     * Lets a joining node gate readiness on a FIXED target ("I have executed
     * through finality") instead of chasing the moving head.
     */
    private Long finalizedChainLength;
  
 

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

    public Long getFinalizedChainLength() {
        return finalizedChainLength;
    }

    public void setFinalizedChainLength(Long finalizedChainLength) {
        this.finalizedChainLength = finalizedChainLength;
    }
 
}
