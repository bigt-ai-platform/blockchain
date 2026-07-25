package net.bigtangle.p2p;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class PeerScore {

    private final NodeId nodeId;
    private final AtomicLong chainLength = new AtomicLong(0);
    private final AtomicLong responseTime = new AtomicLong(Long.MAX_VALUE);
    private final AtomicInteger successfulRequests = new AtomicInteger(0);
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicLong firstSeen = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastComputed = new AtomicLong(0);
    private final AtomicReference<double[]> weights = new AtomicReference<>(
            new double[]{0.3, 0.3, 0.2, 0.1, 0.1}
    );

    private static final double RESPONSE_TIME_MAX_MS = 5000.0;
    private static final long AGE_MAX_HOURS = 24 * 7;

    public PeerScore(NodeId nodeId) {
        this.nodeId = nodeId;
    }

    public void recordResponse(long ms) {
        long current = responseTime.get();
        if (ms < current) {
            responseTime.compareAndSet(current, ms);
        }
    }

    public void recordRequest(boolean success) {
        totalRequests.incrementAndGet();
        if (success) {
            successfulRequests.incrementAndGet();
        }
    }

    public void updateChainLength(long length) {
        chainLength.set(length);
    }

    public void setWeights(double chainWeight, double rttWeight, double successWeight, double ageWeight, double stakeWeight) {
        double sum = chainWeight + rttWeight + successWeight + ageWeight + stakeWeight;
        if (sum <= 0) sum = 1;
        weights.set(new double[]{
                chainWeight / sum, rttWeight / sum, successWeight / sum, ageWeight / sum, stakeWeight / sum
        });
    }

    public double compute(long maxChainLength) {
        double[] w = weights.get();
        long now = System.currentTimeMillis();

        double chainScore = maxChainLength > 0
                ? Math.min(1.0, (double) chainLength.get() / maxChainLength) : 0;

        double rttScore = responseTime.get() < Long.MAX_VALUE
                ? Math.max(0, 1.0 - responseTime.get() / RESPONSE_TIME_MAX_MS) : 0;

        int total = totalRequests.get();
        double successScore = total > 0
                ? (double) successfulRequests.get() / total : 0.5;

        double ageHours = (now - firstSeen.get()) / 3600000.0;
        double ageScore = Math.min(1.0, ageHours / AGE_MAX_HOURS);

        double combined = w[0] * chainScore + w[1] * rttScore + w[2] * successScore + w[3] * ageScore;

        lastComputed.set(now);
        return combined;
    }

    public NodeId getNodeId() { return nodeId; }
    public long getChainLength() { return chainLength.get(); }
    public long getResponseTime() { return responseTime.get(); }
    public int getSuccessfulRequests() { return successfulRequests.get(); }
    public int getTotalRequests() { return totalRequests.get(); }
    public double getSuccessRate() { return totalRequests.get() > 0 ? (double) successfulRequests.get() / totalRequests.get() : 0.5; }
    public long getAgeHours() { return (System.currentTimeMillis() - firstSeen.get()) / 3600000; }

    @Override
    public String toString() {
        return "PeerScore{id=" + nodeId + " score=" + String.format("%.3f", compute(chainLength.get())) + "}";
    }
}
