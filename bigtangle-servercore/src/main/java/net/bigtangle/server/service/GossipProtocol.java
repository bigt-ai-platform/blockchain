package net.bigtangle.server.service;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import net.bigtangle.core.Block;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.p2p.DnsDiscoveryResolver;
import net.bigtangle.p2p.NodeRecord;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ServerConfiguration;

@Service
public class GossipProtocol implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GossipProtocol.class);

    @Value("${gossip.port:9093}")
    private int gossipPort;

    /** Optional comma-separated list of gossip peers (host:port).  Lets a
     *  private/test network form a block-propagation mesh without a DNS seed
     *  server (DNS seeds are unreachable inside a Docker bridge network). */
    @Value("${gossip.peers:}")
    private String gossipPeers;

    private static final int MAGIC = 0x42474C31;
    private static final int MSG_BLOCK = 1;
    private static final int MSG_TRANSACTION = 2;
    @Autowired
    private NetworkParameters networkParameters;

    @Autowired
    private ServerConfiguration serverConfiguration;

    @Autowired
    private BlockService blockService;

    @Autowired
    private net.bigtangle.server.service.MempoolService mempoolService;

    private final ExecutorService listenerPool = Executors.newCachedThreadPool();
    private final ExecutorService connectPool = Executors.newCachedThreadPool();
    private final Map<String, Socket> peers = new ConcurrentHashMap<>();
    private volatile boolean running;

    @PostConstruct
    public void start() {
        running = true;
        listenerPool.submit(this::listenLoop);
        connectPool.submit(this::discoveryLoop);
        log.info("GossipProtocol started on port {}", gossipPort);
    }

    private void listenLoop() {
        try (ServerSocket server = new ServerSocket(gossipPort)) {
            while (running) {
                Socket sock = server.accept();
                String addr = sock.getInetAddress().getHostAddress();
                peers.put(addr, sock);
                listenerPool.submit(() -> handleConnection(sock));
            }
        } catch (Exception e) {
            if (running) log.warn("Gossip listen error: {}", e.getMessage());
        }
    }

    private void handleConnection(Socket sock) {
        try (DataInputStream in = new DataInputStream(sock.getInputStream())) {
            while (running && !sock.isClosed()) {
                int magic = in.readInt();
                if (magic != MAGIC) continue;
                int type = in.readInt();
                int len = in.readInt();
                byte[] data = new byte[len];
                in.readFully(data);
                dispatch(type, data);
            }
        } catch (Exception e) {
            // connection closed
        }
    }

    private void dispatch(int type, byte[] data) {
        try {
            switch (type) {
                case MSG_BLOCK -> {
                    Block block = networkParameters.getDefaultSerializer().makeBlock(data);
                    blockService.addConnectedFromGossip(block);
                }
                case MSG_TRANSACTION -> {
                    Transaction tx = networkParameters.getDefaultSerializer().makeTransaction(data);
                    mempoolService.submitTransaction(tx);
                }
            }
        } catch (Exception e) {
            log.debug("Gossip dispatch error: {}", e.getMessage());
        }
    }

    private void discoveryLoop() {
        while (running) {
            try {
                discoverPeers();
                Thread.sleep(30000);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            catch (Exception e) { log.debug("Discovery error: {}", e.getMessage()); }
        }
    }

    private void discoverPeers() {
        connectExplicitPeers();
        String[] seeds = networkParameters.getDnsSeeds();
        if (seeds == null) return;
        for (String seed : seeds) {
            try {
                List<NodeRecord> records = DnsDiscoveryResolver.resolve(seed);
                for (NodeRecord r : records) {
                    String host = r.getHost();
                    if (!peers.containsKey(host) && !host.equals(getSelfHost())) {
                        connectPool.submit(() -> connectTo(host));
                    }
                }
            } catch (Exception e) {
                log.debug("DNS resolve failed: {}", e.getMessage());
            }
        }
    }

    /** Connect to the explicitly configured gossip peers (used when no DNS seed
     *  server is reachable, e.g. a Docker bridge network). */
    private void connectExplicitPeers() {
        if (gossipPeers == null || gossipPeers.trim().isEmpty()) return;
        for (String p : gossipPeers.split(",")) {
            String hp = p.trim();
            if (hp.isEmpty() || peers.containsKey(hp)) continue;
            String host = hp;
            int port = gossipPort;
            int colon = hp.lastIndexOf(':');
            if (colon > 0) {
                try {
                    host = hp.substring(0, colon);
                    port = Integer.parseInt(hp.substring(colon + 1));
                } catch (NumberFormatException ignore) { }
            }
            if (host.equals(getSelfHost())) continue;
            try {
                // Skip our own address even when referenced by DNS name.
                if (java.net.InetAddress.getByName(host).getHostAddress().equals(getSelfHost())) continue;
            } catch (Exception ignore) { }
            final String targetHost = host;
            final int targetPort = port;
            connectPool.submit(() -> connectTo(targetHost, targetPort));
        }
    }

    private void connectTo(String host) {
        connectTo(host, gossipPort);
    }

    private void connectTo(String host, int port) {
        try {
            Socket sock = new Socket(host, port);
            peers.put(host, sock);
            listenerPool.submit(() -> handleConnection(sock));
            log.info("Connected to gossip peer: {}:{}", host, port);
        } catch (Exception e) {
            log.debug("Failed to connect to {}:{} - {}", host, port, e.getMessage());
        }
    }

    public void broadcastBlock(Block block) {
        byte[] data = block.bitcoinSerialize();
        broadcast(MSG_BLOCK, data);
    }

    public void broadcastTransaction(Transaction tx) {
        broadcast(MSG_TRANSACTION, tx.bitcoinSerialize());
    }

    private void broadcast(int type, byte[] data) {
        byte[] frame = frame(type, data);
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, Socket> e : peers.entrySet()) {
            try {
                DataOutputStream out = new DataOutputStream(e.getValue().getOutputStream());
                out.write(frame);
                out.flush();
            } catch (Exception ex) {
                dead.add(e.getKey());
            }
        }
        dead.forEach(peers::remove);
    }

    private byte[] frame(int type, byte[] data) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(12 + data.length);
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(MAGIC);
            dos.writeInt(type);
            dos.writeInt(data.length);
            dos.write(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }

    private String getSelfHost() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    public boolean hasPeers() {
        return !peers.isEmpty();
    }

    @Override
    public void destroy() {
        running = false;
        for (Socket s : peers.values()) {
            try { s.close(); } catch (Exception e) {}
        }
        peers.clear();
        listenerPool.shutdownNow();
        connectPool.shutdownNow();
    }
}
