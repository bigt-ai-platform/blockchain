package net.bigtangle.p2p;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class DiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);
    private static final int MAX_PACKET_SIZE = 1400;

    private final DatagramSocket socket;
    private final RoutingTable routingTable;
    private final NodeRecord selfRecord;
    private final byte[] privateKey;
    private final ObjectMapper mapper;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor;
    private final Map<NodeId, Long> rateLimit = new ConcurrentHashMap<>();

    public DiscoveryService(int port, RoutingTable routingTable, NodeRecord selfRecord, byte[] privateKey) throws IOException {
        this.socket = new DatagramSocket(new InetSocketAddress(port));
        this.routingTable = routingTable;
        this.selfRecord = selfRecord;
        this.privateKey = privateKey;
        this.mapper = new ObjectMapper();
        this.mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        this.executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "discv5-listener"));
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        executor.submit(this::listenLoop);
        log.info("DiscoveryService listening on UDP {}", socket.getLocalPort());
    }

    public void stop() {
        running.set(false);
        executor.shutdownNow();
        try { executor.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        socket.close();
    }

    private void listenLoop() {
        byte[] buf = new byte[MAX_PACKET_SIZE];
        while (running.get() && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                byte[] data = new byte[packet.getLength()];
                System.arraycopy(buf, 0, data, 0, packet.getLength());
                handlePacket(data, packet);
            } catch (Exception e) {
                if (running.get()) {
                    log.debug("Discovery receive error: {}", e.getMessage());
                }
            }
        }
    }

    private void handlePacket(byte[] data, DatagramPacket packet) {
        try {
            DiscoveryMessage msg = DiscoveryMessage.deserialize(data);

            long now = System.currentTimeMillis();
            Long last = rateLimit.get(msg.getSenderId());
            if (last != null && (now - last) < 100) {
                return;
            }
            rateLimit.put(msg.getSenderId(), now);

            switch (msg.getType()) {
                case PING -> handlePing(msg, packet);
                case FINDNODE -> handleFindNode(msg, packet);
                case ENR -> handleEnr(msg, packet);
            }
        } catch (Exception e) {
            log.debug("Failed to handle discovery packet: {}", e.getMessage());
        }
    }

    private void handlePing(DiscoveryMessage msg, DatagramPacket packet) throws IOException {
        byte[] payload = mapper.writeValueAsBytes(Map.of(
                "echo", msg.getPayload(),
                "observedIP", packet.getAddress().getHostAddress(),
                "observedPort", packet.getPort()
        ));
        DiscoveryMessage pong = DiscoveryMessage.create(
                DiscoveryMessage.Type.PONG, selfRecord.getNodeId(), payload, privateKey);
        sendTo(pong, packet.getAddress().getHostAddress(), packet.getPort());
    }

    private void handleFindNode(DiscoveryMessage msg, DatagramPacket packet) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> req = mapper.readValue(msg.getPayload(), Map.class);
        Object distanceObj = req.get("distance");
        int distance = distanceObj instanceof Number n ? n.intValue() : -1;

        List<NodeRecord> closest;
        if (distance >= 0 && distance <= 255) {
            closest = routingTable.findClosest(selfRecord.getNodeId(), 16);
        } else {
            String targetHex = (String) req.getOrDefault("target", "");
            if (targetHex.isEmpty()) return;
            byte[] targetBytes = java.util.HexFormat.of().parseHex(targetHex);
            closest = routingTable.findClosest(new NodeId(targetBytes), 16);
        }

        var nodeList = closest.stream().map(n -> {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("publicKey", java.util.HexFormat.of().formatHex(n.getPublicKey()));
            m.put("host", n.getHost());
            m.put("udpPort", n.getUdpPort());
            m.put("tcpPort", n.getTcpPort());
            m.put("seq", n.getSeq());
            return m;
        }).toList();

        byte[] payload = mapper.writeValueAsBytes(Map.of("nodes", nodeList));
        DiscoveryMessage response = DiscoveryMessage.create(
                DiscoveryMessage.Type.NODES, selfRecord.getNodeId(), payload, privateKey);
        sendTo(response, packet.getAddress().getHostAddress(), packet.getPort());
    }

    private void handleEnr(DiscoveryMessage msg, DatagramPacket packet) {
        byte[] payload = msg.getPayload();
        try {
            NodeRecord record = NodeRecord.deserialize(payload);
            if (record.getNodeId().equals(msg.getSenderId())) {
                routingTable.update(record);
                log.debug("Discovered peer {} via ENR exchange", record.getNodeId());
            }
        } catch (Exception e) {
            log.debug("Invalid ENR in discovery message: {}", e.getMessage());
        }
    }

    public void sendPing(String host, int port) throws IOException {
        byte[] payload = mapper.writeValueAsBytes(Map.of(
                "nodeId", selfRecord.getNodeId().toString()
        ));
        DiscoveryMessage ping = DiscoveryMessage.create(
                DiscoveryMessage.Type.PING, selfRecord.getNodeId(), payload, privateKey);
        sendTo(ping, host, port);
    }

    public void sendFindNode(NodeId target, String host, int port) throws IOException {
        byte[] payload = mapper.writeValueAsBytes(Map.of(
                "target", java.util.HexFormat.of().formatHex(target.getBytes())
        ));
        DiscoveryMessage query = DiscoveryMessage.create(
                DiscoveryMessage.Type.FINDNODE, selfRecord.getNodeId(), payload, privateKey);
        sendTo(query, host, port);
    }

    private void sendTo(DiscoveryMessage msg, String host, int port) throws IOException {
        byte[] data = msg.serialize();
        DatagramPacket packet = new DatagramPacket(data, data.length,
                java.net.InetAddress.getByName(host), port);
        socket.send(packet);
    }

    public RoutingTable getRoutingTable() { return routingTable; }
}
