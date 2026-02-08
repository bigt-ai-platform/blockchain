package net.bigtangle.server.data;

public class ApiCall {
    public String fromIp;
    public String cmd;
    public Long  time;
    
    
    public ApiCall(String fromIp, String cmd, Long time) {
        super();
        this.fromIp = fromIp;
        this.cmd = cmd;
        this.time = time;
    }
    public String getFromIp() {
        return fromIp;
    }
    public void setFromIp(String fromIp) {
        this.fromIp = fromIp;
    }
    public String getCmd() {
        return cmd;
    }
    public void setCmd(String cmd) {
        this.cmd = cmd;
    }
    public Long getTime() {
        return time;
    }
    public void setTime(Long time) {
        this.time = time;
    }
    @Override
    public String toString() {
        return "ApiCall [fromIp=" + fromIp + ", cmd=" + cmd + ", time=" + time + "]";
    }
        
}
