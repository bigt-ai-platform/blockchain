package net.bigtangle.p2p;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "peer")
public class PeerConfiguration {

    private List<String> bootnodes = new ArrayList<>();
    private List<String> dnsSeeds = new ArrayList<>();
    private int udpPort = 30303;
    private int tcpPort = 30304;
    private int bucketSize = 16;
    private int maxPeers = 100;
    private int activePeers = 8;
    private int minValidators = 2;
    private double scoreFloor = 0.1;
    private double scoreWeightChain = 0.3;
    private double scoreWeightRtt = 0.3;
    private double scoreWeightSuccess = 0.2;
    private double scoreWeightAge = 0.1;
    private double scoreWeightStake = 0.1;

    public List<String> getBootnodes() { return bootnodes; }
    public void setBootnodes(List<String> bootnodes) { this.bootnodes = bootnodes; }
    public List<String> getDnsSeeds() { return dnsSeeds; }
    public void setDnsSeeds(List<String> dnsSeeds) { this.dnsSeeds = dnsSeeds; }
    public int getUdpPort() { return udpPort; }
    public void setUdpPort(int udpPort) { this.udpPort = udpPort; }
    public int getTcpPort() { return tcpPort; }
    public void setTcpPort(int tcpPort) { this.tcpPort = tcpPort; }
    public int getBucketSize() { return bucketSize; }
    public void setBucketSize(int bucketSize) { this.bucketSize = bucketSize; }
    public int getMaxPeers() { return maxPeers; }
    public void setMaxPeers(int maxPeers) { this.maxPeers = maxPeers; }
    public int getActivePeers() { return activePeers; }
    public void setActivePeers(int activePeers) { this.activePeers = activePeers; }
    public int getMinValidators() { return minValidators; }
    public void setMinValidators(int minValidators) { this.minValidators = minValidators; }
    public double getScoreFloor() { return scoreFloor; }
    public void setScoreFloor(double scoreFloor) { this.scoreFloor = scoreFloor; }
    public double getScoreWeightChain() { return scoreWeightChain; }
    public void setScoreWeightChain(double scoreWeightChain) { this.scoreWeightChain = scoreWeightChain; }
    public double getScoreWeightRtt() { return scoreWeightRtt; }
    public void setScoreWeightRtt(double scoreWeightRtt) { this.scoreWeightRtt = scoreWeightRtt; }
    public double getScoreWeightSuccess() { return scoreWeightSuccess; }
    public void setScoreWeightSuccess(double scoreWeightSuccess) { this.scoreWeightSuccess = scoreWeightSuccess; }
    public double getScoreWeightAge() { return scoreWeightAge; }
    public void setScoreWeightAge(double scoreWeightAge) { this.scoreWeightAge = scoreWeightAge; }
    public double getScoreWeightStake() { return scoreWeightStake; }
    public void setScoreWeightStake(double scoreWeightStake) { this.scoreWeightStake = scoreWeightStake; }
}
