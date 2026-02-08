package net.bigtangle.lifecycle;

 
 
public class LifecycleStatus {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_NOTOK = "NOTOK";

    private   String status;

    private   String statusText;

    public String getStatus() {
        return status;
    }

    public String getStatusText() {
        return statusText;
    }

    public LifecycleStatus(String status, String statusText) {
        super();
        this.status = status;
        this.statusText = statusText;
    }

}
