package net.bigtangle.p2p;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.stream.Collectors;

public class PeerDiscoveryClient {

    private static final Logger log = LoggerFactory.getLogger(PeerDiscoveryClient.class);
    private static final int RESPONSE_TIMEOUT_MS = 3000;
    private static final int MAX_PACKET_SIZE = 1400;
    private static final int ALPHA = 3;
    private static final int K = 16;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<NodeRecord> bootnodes;
    private final Map<String, NodeRecord> discovered = new LinkedHashMap<>();

    public PeerDiscoveryClient(List<NodeRecord> bootnodes) {
        this.bootnodes = bootnodes;
    }

    public static PeerDiscoveryClient fromDnsSeeds(List<String> dnsSeeds) {
        List<NodeRecord> bootnodes = new ArrayList<>();
        for (String seed : dnsSeeds) {
            bootnodes.addAll(DnsDiscoveryResolver.resolve(seed));
        }
        return new PeerDiscoveryClient(bootnodes);
    }

    public List<NodeRecord> discover(int maxNodes) {
        if (bootnodes.isEmpty()) return List.of();

        for (NodeRecord bootnode : bootnodes) {
            discovered.put(encodeNodeKey(bootnode), bootnode);
        }

        byte[] randomTarget = new byte[32];
        new Random().nextBytes(randomTarget);
        NodeId target = new NodeId(randomTarget);

        try {
            iterativeFindNode(target, maxNodes);
        } catch (IOException e) {
            log.debug("Discovery error: {}", e.getMessage());
        }

        List<NodeRecord> all = new ArrayList<>(discovered.values());
        if (all.size() > maxNodes) all = new ArrayList<>(all.subList(0, maxNodes));
        return all;
    }

    public List<NodeRecord> discover() {
        return discover(100);
    }

    private void iterativeFindNode(NodeId target, int maxNodes) throws IOException {
        Set<String> queried = new HashSet<>();
        List<NodeRecord> closest = new ArrayList<>(discovered.values());
        boolean foundCloser = true;

        while (foundCloser && discovered.size() < maxNodes) {
            foundCloser = false;

            closest.sort(Comparator.comparingInt(
                    n -> distanceToTarget(n.getNodeId(), target)));

            List<NodeRecord> toQuery = closest.stream()
                    .filter(n -> !queried.contains(encodeNodeKey(n)))
                    .limit(ALPHA)
                    .toList();

            if (toQuery.isEmpty()) break;

            for (NodeRecord peer : toQuery) {
                queried.add(encodeNodeKey(peer));
                try {
                    String host = peer.getHost();
                    int port = peer.getUdpPort();
                    List<NodeRecord> nodes = findNodeRequest(target, host, port);
                    for (NodeRecord n : nodes) {
                        String key = encodeNodeKey(n);
                        if (!discovered.containsKey(key)) {
                            discovered.put(key, n);
                            foundCloser = true;
                        }
                    }
                } catch (Exception e) {
                    log.debug("findNode to {} failed: {}", peer.getNodeId(), e.getMessage());
                }
            }

            closest = new ArrayList<>(discovered.values());
        }
    }

    List<NodeRecord> findNodeRequest(NodeId target, String host, int port) throws IOException {
        byte[] payload = mapper.writeValueAsBytes(Map.of(
                "target", hex(target.getBytes())
        ));

        NodeRecord.KeyPair ephemeral = NodeRecord.generateKeyPair();
        NodeId senderId = NodeId.fromPublicKey(ephemeral.publicKey);
        DiscoveryMessage query = DiscoveryMessage.create(
                DiscoveryMessage.Type.FINDNODE, senderId, payload, ephemeral.privateKey);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(RESPONSE_TIMEOUT_MS);

            byte[] queryData = query.serialize();
            socket.send(new DatagramPacket(queryData, queryData.length,
                    InetAddress.getByName(host), port));

            byte[] buf = new byte[MAX_PACKET_SIZE];
            DatagramPacket response = new DatagramPacket(buf, buf.length);
            socket.receive(response);

            byte[] responseData = new byte[response.getLength()];
            System.arraycopy(buf, 0, responseData, 0, response.getLength());

            DiscoveryMessage reply = DiscoveryMessage.deserialize(responseData);
            if (reply.getType() != DiscoveryMessage.Type.NODES) return List.of();

            return parseNodesResponse(reply.getPayload());
        } catch (SocketTimeoutException e) {
            return List.of();
        }
    }

    private List<NodeRecord> parseNodesResponse(byte[] payload) throws IOException {
        Map<String, Object> resp = mapper.readValue(payload,
                new TypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) resp.get("nodes");
        if (nodes == null) return List.of();

        List<NodeRecord> result = new ArrayList<>();
        for (Map<String, Object> n : nodes) {
            try {
                byte[] pubKey = parseHex((String) n.get("publicKey"));
                String host = (String) n.get("host");
                int udpPort = ((Number) n.get("udpPort")).intValue();
                int tcpPort = ((Number) n.get("tcpPort")).intValue();
                long seq = ((Number) n.get("seq")).longValue();
                NodeRecord record = NodeRecord.createSelf(
                        new NodeRecord.KeyPair(pubKey, null),
                        host, udpPort, tcpPort, seq);
                result.add(record);
            } catch (Exception e) {
                log.debug("Skipping invalid node record: {}", e.getMessage());
            }
        }
        return result;
    }

    public Map<String, NodeRecord> getDiscovered() {
        return Collections.unmodifiableMap(discovered);
    }

    public static String encodeNodeKey(NodeRecord record) {
        return record.getNodeId().toString();
    }

    static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    static byte[] parseHex(String s) {
        return java.util.HexFormat.of().parseHex(s);
    }

    private int distanceToTarget(NodeId a, NodeId target) {
        byte[] xor = a.xor(target);
        for (int i = 0; i < 32; i++) {
            if (xor[i] != 0) {
                int bitPos = i * 8 + (7 - (Integer.numberOfLeadingZeros(xor[i] & 0xFF) - 24));
                return 255 - bitPos;
            }
        }
        return 0;
    }
}
