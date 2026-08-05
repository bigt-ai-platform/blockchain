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
    private String burnAddress;
    private String l1Url;
    /** M-of-N multisig vault keys. Requires vaultM signatures to spend. */
    private List<String> vaultPubKeyHexList = new ArrayList<>();
    /** M-of-N signature threshold (default 1 = single-key mode). */
    private int vaultM = 1;

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getVaultPubKeyHex() { return vaultPubKeyHex; }
    public void setVaultPubKeyHex(String vaultPubKeyHex) { this.vaultPubKeyHex = vaultPubKeyHex; }

    public String getVaultPriKeyHex() { return vaultPriKeyHex; }
    public void setVaultPriKeyHex(String vaultPriKeyHex) { this.vaultPriKeyHex = vaultPriKeyHex; }

    public String getIssuancePubKeyHex() { return issuancePubKeyHex; }
    public void setIssuancePubKeyHex(String issuancePubKeyHex) { this.issuancePubKeyHex = issuancePubKeyHex; }

    public String getIssuancePriKeyHex() { return issuancePriKeyHex; }
    public void setIssuancePriKeyHex(String issuancePriKeyHex) { this.issuancePriKeyHex = issuancePriKeyHex; }

    public String getBurnAddress() { return burnAddress; }
    public void setBurnAddress(String burnAddress) { this.burnAddress = burnAddress; }

    public String getL1Url() { return l1Url; }
    public void setL1Url(String l1Url) { this.l1Url = l1Url; }

    public List<String> getVaultPubKeyHexList() { return vaultPubKeyHexList; }
    public void setVaultPubKeyHexList(List<String> vaultPubKeyHexList) { this.vaultPubKeyHexList = vaultPubKeyHexList; }

    public int getVaultM() { return vaultM; }
    public void setVaultM(int vaultM) { this.vaultM = vaultM; }
}
