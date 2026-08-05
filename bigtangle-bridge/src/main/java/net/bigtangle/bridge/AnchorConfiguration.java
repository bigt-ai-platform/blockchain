package net.bigtangle.bridge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "anchor")
public class AnchorConfiguration {

    private int postInterval = 10;
    private boolean active = false;
    private String l0Url;
    private String priKeyHex;
    private String pubKeyHex;
    private long rewardAmount;
    private String feePoolPriKeyHex;
    private String feePoolPubKeyHex;

    /** M-of-N quorum: how many distinct authorized signers an anchor needs (default 1). */
    private int chainSignersRequired = 1;

    /**
     * Per-chain registry of authorized anchor signers (chainId -> public key
     * hexes). An anchor for a chain is valid if signed by ANY key in that
     * chain's list. Chains with no explicit entry fall back to the global
     * {@link #pubKeyHex}. Rotation = updating a chain's list (any-of).
     */
    private java.util.Map<String, java.util.List<String>> chainPubKeys = new java.util.HashMap<>();

    public int getPostInterval() {
        return postInterval;
    }

    public void setPostInterval(int postInterval) {
        this.postInterval = postInterval;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getL0Url() {
        return l0Url;
    }

    public void setL0Url(String l0Url) {
        this.l0Url = l0Url;
    }

    public String getPriKeyHex() {
        return priKeyHex;
    }

    public void setPriKeyHex(String priKeyHex) {
        this.priKeyHex = priKeyHex;
    }

    public String getPubKeyHex() {
        return pubKeyHex;
    }

    public void setPubKeyHex(String pubKeyHex) {
        this.pubKeyHex = pubKeyHex;
    }

    public long getRewardAmount() {
        return rewardAmount;
    }

    public void setRewardAmount(long rewardAmount) {
        this.rewardAmount = rewardAmount;
    }

    public String getFeePoolPriKeyHex() {
        return feePoolPriKeyHex;
    }

    public void setFeePoolPriKeyHex(String feePoolPriKeyHex) {
        this.feePoolPriKeyHex = feePoolPriKeyHex;
    }

    public String getFeePoolPubKeyHex() {
        return feePoolPubKeyHex;
    }

    public void setFeePoolPubKeyHex(String feePoolPubKeyHex) {
        this.feePoolPubKeyHex = feePoolPubKeyHex;
    }

    public java.util.Map<String, java.util.List<String>> getChainPubKeys() {
        return chainPubKeys;
    }

    public void setChainPubKeys(java.util.Map<String, java.util.List<String>> chainPubKeys) {
        this.chainPubKeys = chainPubKeys != null ? chainPubKeys : new java.util.HashMap<>();
    }

    /**
     * The authorized signer public keys for {@code chainId}: the chain's own
     * registry entry if present, otherwise the global {@link #pubKeyHex} (legacy
     * single-key fallback). A compromised global key can no longer forge anchors
     * for a chain that has its own registry entry.
     */
    public java.util.List<String> getChainPubKeys(String chainId) {
        java.util.List<String> keys = chainPubKeys.get(chainId);
        if (keys != null && !keys.isEmpty()) {
            return keys;
        }
        if (pubKeyHex != null && !pubKeyHex.isEmpty()) {
            return java.util.List.of(pubKeyHex);
        }
        return new java.util.ArrayList<>();
    }

    public int getChainSignersRequired() {
        return chainSignersRequired;
    }

    public void setChainSignersRequired(int chainSignersRequired) {
        this.chainSignersRequired = Math.max(1, chainSignersRequired);
    }
}
