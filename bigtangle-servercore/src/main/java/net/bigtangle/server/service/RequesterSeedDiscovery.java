package net.bigtangle.server.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import net.bigtangle.p2p.DnsDiscoveryResolver;
import net.bigtangle.p2p.NodeRecord;
import net.bigtangle.p2p.PeerManager;
import net.bigtangle.p2p.PeerScore;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ServerConfiguration;

/**
 * <p>
 * Live registry of requester (HTTP API) endpoints for block sync and gossip
 * fallback, so a joining node bootstraps from the network seeds instead of
 * hardcoding the full requester mesh in every application.yml:
 * </p>
 * <ul>
 * <li>{@code server.requester} set — operator override, used alone (test and
 * private meshes stay exactly as configured, and a Test-net node never talks
 * to MainNet seeds)</li>
 * <li>{@code server.requester} empty — candidates are built from
 * {@link NetworkParameters#serverSeeds()} (HTTP seed servers), the enrtree DNS
 * seeds ({@link DnsDiscoveryResolver}, API tcpPort) and the peers discovered
 * by the UDP {@link PeerManager}, best score first</li>
 * </ul>
 * Re-resolved periodically like {@link net.bigtangle.kafka.KafkaSeedDiscovery}
 * so a node started before any seed answers still finds peers later.
 */
@Component
public class RequesterSeedDiscovery implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RequesterSeedDiscovery.class);

    private static final long REFRESH_INTERVAL_SEC = 60;

    @Autowired
    private NetworkParameters networkParameters;

    @Autowired
    private ServerConfiguration serverConfiguration;

    @Autowired(required = false)
    private PeerManager peerManager;

    private volatile Set<String> requesters = new LinkedHashSet<>();
    private Thread refresher;
    private volatile boolean running = true;

    @PostConstruct
    public void init() {
        refresh();
        refresher = new Thread(this::refreshLoop, "requester-seed-discovery");
        refresher.setDaemon(true);
        refresher.start();
    }

    private void refreshLoop() {
        while (running) {
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(REFRESH_INTERVAL_SEC));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            refresh();
        }
    }

    /** Rebuild the candidate requester list from config and the seeds. */
    public void refresh() {
        Set<String> found = new LinkedHashSet<>();
        String cfg = serverConfiguration.getRequester();
        if (cfg != null && !cfg.isBlank()) {
            // Operator override: the configured mesh is the whole list, in
            // config order. No dynamic merge — deterministic behaviour for
            // test and private nets, and no cross-net seed traffic.
            for (String s : cfg.split(",")) {
                add(found, s);
            }
        } else {
            // HTTP seed servers from the network params ("host:port").
            String[] seeds = networkParameters.serverSeeds();
            if (seeds != null) {
                for (String seed : seeds) {
                    if (seed != null && !seed.isBlank()) {
                        add(found, "http://" + seed.trim());
                    }
                }
            }
            // DNS (enrtree) discovery records carry the API tcpPort.
            String[] dnsSeeds = networkParameters.getDnsSeeds();
            if (dnsSeeds != null) {
                for (String seed : dnsSeeds) {
                    try {
                        for (NodeRecord r : DnsDiscoveryResolver.resolve(seed)) {
                            if (r.getTcpPort() > 0) {
                                add(found, "http://" + r.getHost() + ":" + r.getTcpPort());
                            }
                        }
                    } catch (Exception e) {
                        log.debug("DNS seed {} resolution failed: {}", seed, e.getMessage());
                    }
                }
            }
            // Peers from UDP discovery, best score first — they join last so
            // the deterministic seeds keep failover priority.
            if (peerManager != null) {
                try {
                    List<NodeRecord> records = peerManager.getRoutingTable().getAllEntries();
                    long maxChain = records.stream().mapToLong(r -> {
                        PeerScore s = peerManager.getScore(r.getNodeId());
                        return s == null ? 0 : s.getChainLength();
                    }).max().orElse(1);
                    records.sort(Comparator.comparingDouble((NodeRecord r) -> {
                        PeerScore s = peerManager.getScore(r.getNodeId());
                        return s == null ? Double.NEGATIVE_INFINITY : s.compute(maxChain);
                    }).reversed());
                    for (NodeRecord r : records) {
                        if (r.getTcpPort() > 0) {
                            add(found, "http://" + r.getHost() + ":" + r.getTcpPort());
                        }
                    }
                } catch (Exception e) {
                    log.debug("peer requester merge failed: {}", e.getMessage());
                }
            }
        }
        if (!found.isEmpty()) {
            requesters = found;
            log.info("Requester discovery: {} endpoint(s) known", found.size());
        }
    }

    private void add(Set<String> set, String url) {
        if (url != null && !url.isBlank()) {
            set.add(url.trim());
        }
    }

    /**
     * Current candidate list in failover order (operator config first, then
     * seeds, then scored peers). Never null; empty only when nothing is
     * configured and no seed answered.
     */
    public List<String> getRequesters() {
        return List.copyOf(requesters);
    }

    @Override
    public void destroy() {
        running = false;
        if (refresher != null) {
            refresher.interrupt();
        }
    }
}
