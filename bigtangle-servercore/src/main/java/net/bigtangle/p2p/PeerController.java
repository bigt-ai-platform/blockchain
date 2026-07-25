package net.bigtangle.p2p;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

@RestController
public class PeerController {

    @Autowired(required = false)
    private PeerManager peerManager;

    @PostMapping("/getPeers")
    public Map<String, Object> getPeers() {
        if (peerManager == null) {
            return Map.of("status", "disabled");
        }

        List<Map<String, Object>> peers = peerManager.getRoutingTable().getAllEntries().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeId", r.getNodeId().toString());
            m.put("host", r.getHost());
            m.put("tcpPort", r.getTcpPort());
            m.put("udpPort", r.getUdpPort());
            m.put("seq", r.getSeq());
            PeerScore score = peerManager.getScore(r.getNodeId());
            if (score != null) {
                m.put("score", String.format("%.4f", score.compute(
                        peerManager.getRoutingTable().getAllEntries().stream()
                                .mapToLong(s -> score.getChainLength()).max().orElse(1))));
                m.put("chainLength", score.getChainLength());
                m.put("responseTime", score.getResponseTime());
            }
            return m;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("self", peerManager.getSelfRecord().getNodeId().toString());
        result.put("count", peers.size());
        result.put("peers", peers);
        return result;
    }

    @GetMapping("/discover")
    public Map<String, Object> discover(@RequestParam(defaultValue = "100") int max) {
        if (peerManager == null) {
            return Map.of("status", "disabled");
        }

        List<NodeRecord> bootnodes = peerManager.getRoutingTable().getAllEntries();
        if (bootnodes.isEmpty()) {
            return Map.of("status", "no_bootnodes", "count", 0);
        }

        PeerDiscoveryClient client = new PeerDiscoveryClient(bootnodes);
        List<NodeRecord> discovered = client.discover(max);

        for (NodeRecord record : discovered) {
            peerManager.updatePeer(record);
        }

        return Map.of(
                "status", "ok",
                "discovered", discovered.size(),
                "total", peerManager.getRoutingTable().totalEntries()
        );
    }
}
