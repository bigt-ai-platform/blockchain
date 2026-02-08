package net.bigtangle.server.data;

public class LockObject {
    public String lockobjectid;
    public Long locktime;
    
    
    public LockObject(String lockobjectid, Long locktime) {
        super();
        this.lockobjectid = lockobjectid;
        this.locktime = locktime;
    }
    public String getLockobjectid() {
        return lockobjectid;
    }
    public void setLockobjectid(String lockobjectid) {
        this.lockobjectid = lockobjectid;
    }
    public Long getLocktime() {
        return locktime;
    }
    public void setLocktime(Long locktime) {
        this.locktime = locktime;
    }
    
    
}
