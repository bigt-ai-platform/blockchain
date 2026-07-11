package net.bigtangle.bridge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "bridge")
public class BridgeConfiguration {

    private boolean active = false;
    private String vaultPubKeyHex;
    private String vaultPriKeyHex;
    private String burnAddress;
    private String l1Url;

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getVaultPubKeyHex() { return vaultPubKeyHex; }
    public void setVaultPubKeyHex(String vaultPubKeyHex) { this.vaultPubKeyHex = vaultPubKeyHex; }

    public String getVaultPriKeyHex() { return vaultPriKeyHex; }
    public void setVaultPriKeyHex(String vaultPriKeyHex) { this.vaultPriKeyHex = vaultPriKeyHex; }

    public String getBurnAddress() { return burnAddress; }
    public void setBurnAddress(String burnAddress) { this.burnAddress = burnAddress; }

    public String getL1Url() { return l1Url; }
    public void setL1Url(String l1Url) { this.l1Url = l1Url; }
}
