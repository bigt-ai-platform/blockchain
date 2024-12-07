/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import net.bigtangle.utils.OkHttp3Util;
 
@Component
@ConfigurationProperties(prefix = "server")
public class ServerConfiguration {
    // sync seeds for other servers
    private String requester;
    // port of server
    private String port;

    // Mainnet or Test
    private String net;

    private String mineraddress;
    private String serverurl;
    private String serverversion;
    private String clientversion;
    private Boolean permissioned=false;
    private String permissionadmin;
    
    private Boolean myserverblockOnly = false;
    private long maxsearchblocks = 5000;
  
    // does not reply all service request until service is set ready
    private Boolean serviceReady = false;
    private Boolean createtable = true;

    private double alphaMCMC = -0.05;
    private Boolean runKafkaStream = false;
    // At Chain length = int * checkpoint set a checkpoint
    private Long checkpoint=50000L ;
    private int syncblocks=500;
  

    private Boolean dockerCreateDBHost = true;
    private String dockerDBHost="mysql-test";
    private String dockerDBHostData="/data/vm/"+dockerDBHost;
    //save in the userdata with pubkey
    private  long timeoutMinute = OkHttp3Util.timeoutMinute;
    //can be FullPruned server node with cleanup old data or fullnode node with all data
    private String servermode="fullnode";
    

    
    private List<String> deniedIPlist = new ArrayList<>();
    private Boolean ipcheck = false;
    //if ipcheck, alway allow those IPs
    private List<String> allowIPlist = new ArrayList<>();
    
    public synchronized Boolean checkService()  {
        if (!serviceReady) {

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

        }
        return serviceReady;
    }


    public synchronized void setServiceWait() {

        serviceReady = false;
    }

    public boolean isPrunedServermode() {
       return "fullpruned".equals(servermode);
    }
    public String getRequester() {
        return requester;
    }

    public void setRequester(String requester) {
        this.requester = requester;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public String getNet() {
        return net;
    }

    public void setNet(String net) {
        this.net = net;
    }

    public String getMineraddress() {
        return mineraddress;
    }

    public void setMineraddress(String mineraddress) {
        this.mineraddress = mineraddress;
    }

    public String getServerversion() {
        return serverversion;
    }

    public void setServerversion(String serverversion) {
        this.serverversion = serverversion;
    }

    public String getClientversion() {
        return clientversion;
    }

    public void setClientversion(String clientversion) {
        this.clientversion = clientversion;
    }

    public Boolean getPermissioned() {
        return permissioned;
    }

    public void setPermissioned(Boolean permissioned) {
        this.permissioned = permissioned;
    }

    public String getPermissionadmin() {
        return permissionadmin;
    }

    public void setPermissionadmin(String permissionadmin) {
        this.permissionadmin = permissionadmin;
    }

    public Boolean getMyserverblockOnly() {
        return myserverblockOnly;
    }

    public void setMyserverblockOnly(Boolean myserverblockOnly) {
        this.myserverblockOnly = myserverblockOnly;
    }

    public Boolean getServiceReady() {
        return serviceReady;
    }

    public void setServiceReady(Boolean serviceReady) {
        this.serviceReady = serviceReady;
    }

    public Boolean getCreatetable() {
        return createtable;
    }

    public void setCreatetable(Boolean createtable) {
        this.createtable = createtable;
    }
 

    public String getServerurl() {
        return serverurl;
    }

    public void setServerurl(String serverurl) {
        this.serverurl = serverurl;
    }

   
  
    public double getAlphaMCMC() {
        return alphaMCMC;
    }

    public void setAlphaMCMC(double alphaMCMC) {
        this.alphaMCMC = alphaMCMC;
    }

    public Boolean getRunKafkaStream() {
        return runKafkaStream;
    }

    public void setRunKafkaStream(Boolean runKafkaStream) {
        this.runKafkaStream = runKafkaStream;
    }

    public Long getCheckpoint() {
        return checkpoint;
    }

    public void setCheckpoint(Long checkpoint) {
        this.checkpoint = checkpoint;
    }

    public int getSyncblocks() {
        return syncblocks;
    }

    public void setSyncblocks(int syncblocks) {
        this.syncblocks = syncblocks;
    } 
 
    public String getDockerDBHost() {
        return dockerDBHost;
    }

    public void setDockerDBHost(String dockerDBHost) {
        this.dockerDBHost = dockerDBHost;
    }

    public List<String> getAllowIPlist() {
        return allowIPlist;
    }


    public void setAllowIPlist(List<String> allowIPlist) {
        this.allowIPlist = allowIPlist;
    }


    @Override
    public String toString() {
        return "ServerConfiguration [requester=" + requester + ", port=" + port + ", net=" + net + ", mineraddress="
                + mineraddress + ", serverurl=" + serverurl + ", serverversion=" + serverversion + ", clientversion="
                + clientversion + ", permissioned=" + permissioned + ", permissionadmin=" + permissionadmin
                + ", myserverblockOnly=" + myserverblockOnly
                + ", maxsearchblocks=" + maxsearchblocks  
                + ", serviceReady=" + serviceReady + ", createtable=" + createtable + ", alphaMCMC=" + alphaMCMC
                + ", runKafkaStream=" + runKafkaStream + "]";
    }

    public long getMaxsearchblocks() {
        return maxsearchblocks;
    }

    public void setMaxsearchblocks(long maxsearchblocks) {
        this.maxsearchblocks = maxsearchblocks;
    }

    public long getTimeoutMinute() {
        return timeoutMinute;
    }

    public void setTimeoutMinute(long timeoutMinute) {
        this.timeoutMinute = timeoutMinute;
    }

    public String getServermode() {
        return servermode;
    }

    public void setServermode(String servermode) {
        this.servermode = servermode;
    }


    public List<String> getDeniedIPlist() {
        return deniedIPlist;
    }


    public void setDeniedIPlist(List<String> deniedIPlist) {
        this.deniedIPlist = deniedIPlist;
    }


    public Boolean getIpcheck() {
        return ipcheck;
    }


    public void setIpcheck(Boolean ipcheck) {
        this.ipcheck = ipcheck;
    }



    public Boolean getDockerCreateDBHost() {
        return dockerCreateDBHost;
    }


    public void setDockerCreateDBHost(Boolean dockerCreateDBHost) {
        this.dockerCreateDBHost = dockerCreateDBHost;
    }


    public String getDockerDBHostData() {
        return dockerDBHostData;
    }


    public void setDockerDBHostData(String dockerDBHostData) {
        this.dockerDBHostData = dockerDBHostData;
    }

}