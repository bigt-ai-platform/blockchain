package net.bigtangle.lifecycle;

public class StatusCollector {

    private boolean status = true;

    private final StringBuilder okMessage = new StringBuilder();
    private final StringBuilder failedMessage = new StringBuilder();

    private String okSep = "";

    private String failedSep = "";

    public void setFailedMessage(String message) {
        status = false;
        failedMessage.append(failedSep).append(message);
        failedSep = ",";
    }

    public void setOkMessage(String message) {
        okMessage.append(okSep).append(message);
        okSep = ",";
    }

    public boolean isStatus() {
        return status;
    }

    public void setStatus(boolean status) {
        this.status = status;
    }

    public StringBuilder getOkMessage() {
        return okMessage;
    }

    public StringBuilder getFailedMessage() {
        return failedMessage;
    }
    
}
