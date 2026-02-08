/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.config;

/**
 * Central configuration class for Bigtangle server settings. Contains properties
 * for:
 * <ul>
 *   <li>Network configuration (port, net type, URLs)</li>
 *   <li>Server mode and version control</li>
 *   <li>Permission and access control settings</li>
 *   <li>Database and storage configuration</li>
 *   <li>Blockchain synchronization parameters</li>
 *   <li>Security and IP filtering</li>
 *   <li>Performance tuning parameters</li>
 * </ul>
 * 
 * <p>Configuration properties are loaded from application.yml using Spring Boot's
 * {@code @ConfigurationProperties} mechanism with prefix "server".</p>
 * 
 * <p>Example configuration in application.yml:
 * <pre>
 * server:
 *   port: 8088
 *   net: test
 *   mineraddress: mxyz...
 *   maxsearchblocks: 5000
 *   checkpoint: 50000
 * </pre>
 * </p>
 */

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import net.bigtangle.utils.OkHttp3Util;
 
@Component
@ConfigurationProperties(prefix = "server")
public class ServerConfiguration {
    /** 
     * Identifier for this server when making requests to other nodes.
     * Used in peer-to-peer communication and seed synchronization.
     */
    private String requester;
    
    /**
     * Network port the server listens on for incoming connections.
     * Default is typically 8088 for testnet, 8080 for mainnet.
     */
    private String port;

    /**
     * Network type - either "main" for mainnet or "test" for testnet.
     * Determines which blockchain network the server connects to.
     */
    private String net;

    /** Mining reward address where block rewards are sent */
    private String mineraddress;
    
    /** Base URL for this server's API endpoints */
    private String serverurl;
    
    /** Current server software version */
    private String serverversion;
    
    /** Minimum required client version for compatibility */
    private String clientversion;
    
    /** 
     * Whether the server operates in permissioned mode, requiring
     * explicit access grants for certain operations.
     */
    private Boolean permissioned=false;
    
    /** 
     * Admin account identifier for permissioned operations.
     * Required when permissioned=true.
     */
    private String permissionadmin;
    
    /** 
     * Whether to only process blocks created by this server.
     * Used in private network configurations.
     */
    private Boolean myserverblockOnly = false;
    
    /** 
     * Maximum number of blocks to search when processing requests.
     * Affects performance of historical queries.
     */
    private long maxsearchblocks = 5000;
  
    /** 
     * Service readiness flag. When false, the server will not
     * process requests until initialization is complete.
     */
    private Boolean serviceReady = false;
    
    /** 
     * Whether to create database tables on startup.
     * Set to false in production to prevent accidental schema changes.
     */
    private Boolean createtable = true;

    /** 
     * Alpha parameter for Markov Chain Monte Carlo calculations.
     * Used in consensus algorithm calculations.
     */
    private double alphaMCMC = -0.05;
    
    /** 
     * Whether to enable Kafka stream processing for blockchain events.
     * Required for real-time analytics and monitoring.
     */
    private Boolean runKafkaStream = false;
    
    /** 
     * Block interval for creating checkpoints.
     * Checkpoints improve synchronization performance and security.
     */
    private Long checkpoint=50000L ;
    
    /** 
     * Number of blocks to synchronize in each batch during initial sync.
     * Affects synchronization speed and resource usage.
     */
    private int syncblocks=500;
  

    /** 
     * Whether to automatically create a Docker container for the database host.
     * Set to false when using external database services.
     */
    private Boolean dockerCreateDBHost = true;
    
    /** 
     * Hostname for the Docker database container.
     * Used when dockerCreateDBHost is true.
     */
    private String dockerDBHost="mysql-test";
    
    /** 
     * Data directory path for Docker database container.
     * Stores persistent database files.
     */
    private String dockerDBHostData="/data/vm/"+dockerDBHost;
    
    /** 
     * Network timeout in minutes for peer-to-peer communication.
     * Prevents stalled connections from consuming resources.
     */
    private  long timeoutMinute = OkHttp3Util.timeoutMinute;
    
    /** 
     * Server operation mode - either "fullnode" (stores all data) or
     * "fullpruned" (periodically cleans up old data).
     */
    private String servermode="fullnode";
    

    
    /** 
     * List of denied IP addresses for connection filtering.
     * Used when ipcheck is enabled.
     */
    private List<String> deniedIPlist = new ArrayList<>();
    
    /** 
     * Whether to enable IP address filtering for incoming connections.
     * When true, uses allowIPlist and deniedIPlist for access control.
     */
    private Boolean ipcheck = false;
    
    /** 
     * List of always-allowed IP addresses, bypassing other filters.
     * Used when ipcheck is enabled.
     */
    private List<String> allowIPlist = new ArrayList<>();
    
    /**
     * Checks if the server service is ready to handle requests. If not ready,
     * the method will block briefly before returning.
     * 
     * @return true if service is ready, false if still initializing
     * @throws RuntimeException if interrupted while waiting
     */
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

    /**
     * Checks if the server is running in pruned mode, where older blockchain data
     * is periodically cleaned up to save storage space.
     * 
     * @return true if server is in fullpruned mode, false for fullnode mode
     */
    public boolean isPrunedServermode() {
       return "fullpruned".equals(servermode);
    }
    /**
     * Gets the server's requester identifier used in peer-to-peer communication.
     * @return The requester identifier string
     */
    public String getRequester() {
        return requester;
    }

    /**
     * Sets the server's requester identifier.
     * @param requester The new requester identifier to use
     */
    public void setRequester(String requester) {
        this.requester = requester;
    }

    /**
     * Gets the network port the server listens on.
     * @return The port number as a string
     */
    public String getPort() {
        return port;
    }

    /**
     * Sets the network port for the server to listen on.
     * @param port The port number to use (as string)
     */
    public void setPort(String port) {
        this.port = port;
    }

    /**
     * Gets the current network type (mainnet/testnet).
     * @return "main" for mainnet or "test" for testnet
     */
    public String getNet() {
        return net;
    }

    /**
     * Sets the network type for the server.
     * @param net Must be either "main" or "test"
     */
    public void setNet(String net) {
        this.net = net;
    }

    /**
     * Gets the mining reward address where block rewards are sent.
     * @return The miner's reward address
     */
    public String getMineraddress() {
        return mineraddress;
    }

    /**
     * Sets the mining reward address.
     * @param mineraddress The new reward address to use
     */
    public void setMineraddress(String mineraddress) {
        this.mineraddress = mineraddress;
    }

    /**
     * Gets the current server software version.
     * @return The version string in semver format
     */
    public String getServerversion() {
        return serverversion;
    }

    /**
     * Sets the server software version.
     * @param serverversion The version string to use
     */
    public void setServerversion(String serverversion) {
        this.serverversion = serverversion;
    }

    /**
     * Gets the minimum required client version for compatibility.
     * @return The minimum client version string
     */
    public String getClientversion() {
        return clientversion;
    }

    /**
     * Sets the minimum required client version.
     * @param clientversion The minimum version string to require
     */
    public void setClientversion(String clientversion) {
        this.clientversion = clientversion;
    }

    /**
     * Checks if the server is running in permissioned mode.
     * @return true if permissioned mode is enabled
     */
    public Boolean getPermissioned() {
        return permissioned;
    }

    /**
     * Enables or disables permissioned mode.
     * @param permissioned true to enable permissioned mode
     */
    public void setPermissioned(Boolean permissioned) {
        this.permissioned = permissioned;
    }

    /**
     * Gets the admin account identifier for permissioned operations.
     * @return The admin account identifier
     */
    public String getPermissionadmin() {
        return permissionadmin;
    }

    /**
     * Sets the admin account identifier for permissioned operations.
     * @param permissionadmin The admin account identifier to use
     */
    public void setPermissionadmin(String permissionadmin) {
        this.permissionadmin = permissionadmin;
    }

    /**
     * Checks if the server only processes blocks created by itself.
     * @return true if only processing self-created blocks
     */
    public Boolean getMyserverblockOnly() {
        return myserverblockOnly;
    }

    /**
     * Sets whether the server should only process self-created blocks.
     * @param myserverblockOnly true to only process self-created blocks
     */
    public void setMyserverblockOnly(Boolean myserverblockOnly) {
        this.myserverblockOnly = myserverblockOnly;
    }

    /**
     * Checks if the server service is ready to handle requests.
     * @return true if service is ready, false if still initializing
     */
    public Boolean getServiceReady() {
        return serviceReady;
    }

    /**
     * Sets the service readiness flag.
     * @param serviceReady true to mark service as ready
     */
    public void setServiceReady(Boolean serviceReady) {
        this.serviceReady = serviceReady;
    }

    /**
     * Checks if database tables should be created on startup.
     * @return true if tables should be created
     */
    public Boolean getCreatetable() {
        return createtable;
    }

    /**
     * Sets whether database tables should be created on startup.
     * @param createtable true to create tables on startup
     */
    public void setCreatetable(Boolean createtable) {
        this.createtable = createtable;
    }


    /**
     * Gets the base URL for this server's API endpoints.
     * @return The server's base URL
     */
    public String getServerurl() {
        return serverurl;
    }

    /**
     * Sets the base URL for this server's API endpoints.
     * @param serverurl The new base URL to use
     */
    public void setServerurl(String serverurl) {
        this.serverurl = serverurl;
    }

   
  
    /**
     * Gets the alpha parameter for Markov Chain Monte Carlo calculations.
     * @return The current alpha value
     */
    public double getAlphaMCMC() {
        return alphaMCMC;
    }

    /**
     * Sets the alpha parameter for Markov Chain Monte Carlo calculations.
     * @param alphaMCMC The new alpha value to use
     */
    public void setAlphaMCMC(double alphaMCMC) {
        this.alphaMCMC = alphaMCMC;
    }

    /**
     * Checks if Kafka stream processing is enabled for blockchain events.
     * @return true if Kafka stream processing is enabled
     */
    public Boolean getRunKafkaStream() {
        return runKafkaStream;
    }

    /**
     * Enables or disables Kafka stream processing.
     * @param runKafkaStream true to enable Kafka stream processing
     */
    public void setRunKafkaStream(Boolean runKafkaStream) {
        this.runKafkaStream = runKafkaStream;
    }

    /**
     * Gets the block interval for creating checkpoints.
     * @return The checkpoint interval in blocks
     */
    public Long getCheckpoint() {
        return checkpoint;
    }

    /**
     * Sets the block interval for creating checkpoints.
     * @param checkpoint The new checkpoint interval in blocks
     */
    public void setCheckpoint(Long checkpoint) {
        this.checkpoint = checkpoint;
    }

    /**
     * Gets the number of blocks to synchronize in each batch during initial sync.
     * @return The sync batch size in blocks
     */
    public int getSyncblocks() {
        return syncblocks;
    }

    /**
     * Sets the number of blocks to synchronize in each batch.
     * @param syncblocks The new sync batch size in blocks
     */
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


    /**
     * Returns a string representation of key server configuration values.
     * Includes network, version, permission and operational settings.
     * 
     * @return string containing formatted configuration values
     */
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

    /**
     * Gets the network timeout duration in minutes for peer-to-peer communication.
     * @return The timeout duration in minutes
     */
    public long getTimeoutMinute() {
        return timeoutMinute;
    }

    /**
     * Sets the network timeout duration in minutes.
     * @param timeoutMinute The new timeout duration in minutes
     */
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
