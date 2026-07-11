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
}
