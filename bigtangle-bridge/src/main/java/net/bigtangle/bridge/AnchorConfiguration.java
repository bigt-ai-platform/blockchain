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

    /**
     * L0-side freeze list ({@code anchor.disabledChains}): chain ids whose
     * anchors L0 must reject. This is the halt/recovery control for a
     * compromised or buggy L1 — while a chain is disabled, L0 accepts NO new
     * anchor from it and ignores every (even previously confirmed) peg-out burn,
     * so the vault collateral on L0 is protected and can later be returned to
     * the original depositors (L0 keeps the peg-in records).
     */
    private java.util.Set<String> disabledChains = new java.util.HashSet<>();

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

    public java.util.Set<String> getDisabledChains() {
        return disabledChains;
    }

    public void setDisabledChains(java.util.Set<String> disabledChains) {
        this.disabledChains = disabledChains != null ? disabledChains : new java.util.HashSet<>();
    }

    /** True when L0 has frozen {@code chainId} (its anchors and peg-outs are ignored). */
    public boolean isChainDisabled(String chainId) {
        return chainId != null && disabledChains.contains(chainId);
    }
}
