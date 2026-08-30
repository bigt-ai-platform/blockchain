package net.bigtangle.bridge;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "bridge")
public class BridgeConfiguration {

    private boolean active = false;
    /** Single vault key (legacy). Use vaultPubKeyHexList for M-of-N. */
    private String vaultPubKeyHex;
    private String vaultPriKeyHex;
    /**
     * DEDICATED issuance key pair (R4). Wrapped-token issuance on L1 is signed
     * with this key, NOT the vault key — the vault key must stay on L0 where it
     * signs peg-out releases. Every L1 node verifies issuance against
     * issuancePubKeyHex.
     */
    private String issuancePubKeyHex;
    private String issuancePriKeyHex;
    /**
     * M-of-N issuance keys (optional). When non-empty, wrapped-token issuance is
     * signed by EVERY private key in {@code issuancePriKeyHexList} and verified
     * as a quorum of {@code issuanceM} distinct authorized public keys
     * ({@code issuancePubKeyHexList}). Empty = single-key issuance
     * ({@code issuancePubKeyHex}/{@code issuancePriKeyHex}).
     */
    private java.util.List<String> issuancePubKeyHexList = new ArrayList<>();
    private java.util.List<String> issuancePriKeyHexList = new ArrayList<>();
    private int issuanceM = 1;
    private String burnAddress;
    private String l1Url;
    /** M-of-N multisig vault keys. Requires vaultM signatures to spend. */
    private List<String> vaultPubKeyHexList = new ArrayList<>();
    /** M-of-N signature threshold (default 1 = single-key mode). */
    private int vaultM = 1;
    /** The M private keys used to sign a vault release (one per held signer key). */
    private List<String> vaultPriKeyHexList = new ArrayList<>();

    /**
     * Peg-out finality gate (default true): a peg-out is honoured only after the
     * anchor's L0 block is Casper-FINALIZED, not merely confirmed (confirmation
     * is optimistic and reversible). Tests/dev that do not run Casper finality
     * set this false to preserve the old confirm-only behaviour.
     */
    private boolean requireFinality = true;

    /**
     * When true, refuse to start if {@code bridge.active} and the vault is
     * single-key (no {@code vaultPubKeyHexList}). Single-key custody means one
     * key controls ALL locked collateral. Default false (warn only) so the
     * dev/test harness keeps working; set true in production.
     */
    private boolean requireMultisigVault = false;

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public boolean isRequireFinality() { return requireFinality; }
    public void setRequireFinality(boolean requireFinality) { this.requireFinality = requireFinality; }

    public boolean isRequireMultisigVault() { return requireMultisigVault; }
    public void setRequireMultisigVault(boolean requireMultisigVault) { this.requireMultisigVault = requireMultisigVault; }

    public String getVaultPubKeyHex() { return vaultPubKeyHex; }
    public void setVaultPubKeyHex(String vaultPubKeyHex) { this.vaultPubKeyHex = vaultPubKeyHex; }

    public String getVaultPriKeyHex() { return vaultPriKeyHex; }
    public void setVaultPriKeyHex(String vaultPriKeyHex) { this.vaultPriKeyHex = vaultPriKeyHex; }

    public String getIssuancePubKeyHex() { return issuancePubKeyHex; }
    public void setIssuancePubKeyHex(String issuancePubKeyHex) { this.issuancePubKeyHex = issuancePubKeyHex; }

    public String getIssuancePriKeyHex() { return issuancePriKeyHex; }
    public void setIssuancePriKeyHex(String issuancePriKeyHex) { this.issuancePriKeyHex = issuancePriKeyHex; }

    public java.util.List<String> getIssuancePubKeyHexList() { return issuancePubKeyHexList; }
    public void setIssuancePubKeyHexList(java.util.List<String> v) { this.issuancePubKeyHexList = v != null ? v : new java.util.ArrayList<>(); }

    public java.util.List<String> getIssuancePriKeyHexList() { return issuancePriKeyHexList; }
    public void setIssuancePriKeyHexList(java.util.List<String> v) { this.issuancePriKeyHexList = v != null ? v : new java.util.ArrayList<>(); }

    public int getIssuanceM() { return issuanceM; }
    public void setIssuanceM(int issuanceM) { this.issuanceM = Math.max(1, issuanceM); }

    public String getBurnAddress() { return burnAddress; }
    public void setBurnAddress(String burnAddress) { this.burnAddress = burnAddress; }

    public String getL1Url() { return l1Url; }
    public void setL1Url(String l1Url) { this.l1Url = l1Url; }

    public List<String> getVaultPubKeyHexList() { return vaultPubKeyHexList; }
    public void setVaultPubKeyHexList(List<String> vaultPubKeyHexList) { this.vaultPubKeyHexList = vaultPubKeyHexList; }

    public int getVaultM() { return vaultM; }
    public void setVaultM(int vaultM) { this.vaultM = vaultM; }

    public List<String> getVaultPriKeyHexList() { return vaultPriKeyHexList; }
    public void setVaultPriKeyHexList(List<String> vaultPriKeyHexList) {
        this.vaultPriKeyHexList = vaultPriKeyHexList != null ? vaultPriKeyHexList : new ArrayList<>();
    }
}
