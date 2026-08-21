package net.bigtangle.server.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import net.bigtangle.core.AttestationData;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;

@Service
public class GossipService {

    private static final Logger log = LoggerFactory.getLogger(GossipService.class);

    @Value("${pos.gossipPeers:}")
    private String gossipPeers;

    /**
     * Dedicated worker pool for best-effort gossip. Broadcast MUST NOT run on
     * the caller (duty/slot-tick) thread: even short per-peer timeouts let one
     * congested peer consume the whole slot budget and stall finality. Workers
     * are daemons; overflow discards the OLDEST pending send — losing a
     * periodic attestation beats blocking consensus.
     */
    private static final java.util.concurrent.ThreadPoolExecutor GOSSIP_POOL = new java.util.concurrent.ThreadPoolExecutor(
            2, 4, 60L, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(64),
            r -> {
                Thread t = new Thread(r, "gossip");
                t.setDaemon(true);
                return t;
            }, new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());

    public void broadcastAttestation(AttestationData att) {
        String path = ReqCmd.submitAttestation.name();
        byte[] body = toBytes(att);
        broadcast(path, body);
    }

    public void broadcastSlashingProof(AttestationData att1, AttestationData att2) {
        try {
            String json = "{\"attestation1\":" + Json.jsonmapper().writeValueAsString(att1)
                    + ",\"attestation2\":" + Json.jsonmapper().writeValueAsString(att2) + "}";
            broadcast(ReqCmd.submitSlashingProof.name(), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.debug("Failed to serialize slashing proof for gossip", e);
        }
    }

    public void broadcastBeaconBlockHash(Sha256Hash blockHash, long slot) {
        try {
            String json = "{\"blockHash\":\"" + blockHash.toString() + "\",\"slot\":" + slot + "}";
            broadcast(ReqCmd.submitAttestation.name(), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.debug("Failed to broadcast beacon block hash", e);
        }
    }

    private void broadcast(String path, byte[] body) {
        if (gossipPeers == null || gossipPeers.trim().isEmpty()) return;
        for (String peer : gossipPeers.split(",")) {
            String p = peer.trim();
            if (p.isEmpty()) continue;
            String url = "http://" + p + "/" + path;
            try {
                GOSSIP_POOL.execute(() -> {
                    try {
                        OkHttp3Util.postGossip(url, body);
                    } catch (Exception e) {
                        log.debug("gossip to {} failed: {}", p, e.getMessage());
                    }
                });
            } catch (Exception e) {
                log.debug("gossip to {} dropped (pool saturated)", p);
            }
        }
    }

    private byte[] toBytes(Object obj) {
        try {
            return Json.jsonmapper().writeValueAsBytes(obj);
        } catch (Exception e) {
            log.debug("Failed to serialize for gossip", e);
            return new byte[0];
        }
    }
}
