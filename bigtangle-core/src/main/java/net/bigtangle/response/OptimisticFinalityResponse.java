/*******************************************************************************
 *  Copyright   2018  Inasset GmbH.
 *
 *******************************************************************************/
package net.bigtangle.response;

/**
 * Advisory "optimistic finality" signal for the current confirmed head.
 *
 * <p>NOT consensus — a read-only view: how much current active stake is
 * voting for the confirmed head's branch right now, plus the highest
 * justified/finalized Casper checkpoints. Exchanges/bridges may treat
 * {@code supermajority == true} as fast-approval UX (Solana-style optimistic
 * confirmation); the FFG finalization guarantee is unchanged and remains the
 * only on-chain truth.
 */
public class OptimisticFinalityResponse extends AbstractResponse {

    /** Confirmed head beacon hash (hex). */
    private String headBlockHash;
    /** Reward-chain length of the confirmed head. */
    private Long chainLength;
    /** Stake weight currently voting for the head's branch (effective-stake units). */
    private String headVoteWeight;
    /** Total active stake (effective-stake units) at the current chain epoch. */
    private String totalStake;
    /** True when headVoteWeight >= 2/3 of totalStake. */
    private Boolean supermajority;
    /** Highest justified checkpoint epoch / hash (hex). */
    private Long justifiedEpoch;
    private String justifiedBlockHash;
    /** Highest finalized checkpoint epoch / hash (hex). */
    private Long finalizedEpoch;
    private String finalizedBlockHash;

    public static OptimisticFinalityResponse create() {
        return new OptimisticFinalityResponse();
    }

    public String getHeadBlockHash() {
        return headBlockHash;
    }

    public void setHeadBlockHash(String headBlockHash) {
        this.headBlockHash = headBlockHash;
    }

    public Long getChainLength() {
        return chainLength;
    }

    public void setChainLength(Long chainLength) {
        this.chainLength = chainLength;
    }

    public String getHeadVoteWeight() {
        return headVoteWeight;
    }

    public void setHeadVoteWeight(String headVoteWeight) {
        this.headVoteWeight = headVoteWeight;
    }

    public String getTotalStake() {
        return totalStake;
    }

    public void setTotalStake(String totalStake) {
        this.totalStake = totalStake;
    }

    public Boolean getSupermajority() {
        return supermajority;
    }

    public void setSupermajority(Boolean supermajority) {
        this.supermajority = supermajority;
    }

    public Long getJustifiedEpoch() {
        return justifiedEpoch;
    }

    public void setJustifiedEpoch(Long justifiedEpoch) {
        this.justifiedEpoch = justifiedEpoch;
    }

    public String getJustifiedBlockHash() {
        return justifiedBlockHash;
    }

    public void setJustifiedBlockHash(String justifiedBlockHash) {
        this.justifiedBlockHash = justifiedBlockHash;
    }

    public Long getFinalizedEpoch() {
        return finalizedEpoch;
    }

    public void setFinalizedEpoch(Long finalizedEpoch) {
        this.finalizedEpoch = finalizedEpoch;
    }

    public String getFinalizedBlockHash() {
        return finalizedBlockHash;
    }

    public void setFinalizedBlockHash(String finalizedBlockHash) {
        this.finalizedBlockHash = finalizedBlockHash;
    }
}
