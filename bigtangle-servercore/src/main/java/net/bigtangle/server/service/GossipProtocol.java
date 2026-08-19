package net.bigtangle.server.service;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    /** Per-peer outbound frame queue cap. Frames are dropped (best-effort gossip)
     *  when a slow peer cannot keep up, instead of blocking the submit path. */
    private static final int GOSSIP_QUEUE_CAPACITY = 100_000;
    /** Max frames coalesced into a single socket write before one flush. */
    private static final int GOSSIP_BATCH_MAX = 512;
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
    private final ExecutorService senderPool = Executors.newCachedThreadPool();
    private final Map<String, Socket> peers = new ConcurrentHashMap<>();
    private final Map<String, BlockingQueue<byte[]>> sendQueues = new ConcurrentHashMap<>();
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
                registerPeer(addr, sock);
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
            registerPeer(host, sock);
            listenerPool.submit(() -> handleConnection(sock));
            log.info("Connected to gossip peer: {}:{}", host, port);
        } catch (Exception e) {
            log.debug("Failed to connect to {}:{} - {}", host, port, e.getMessage());
        }
    }

    /** Registers a peer socket and starts its dedicated async sender thread.
     *  Broadcasts only enqueue frames here; all socket I/O happens on the
     *  sender, so the submit path never blocks on a slow peer. */
    private void registerPeer(String key, Socket sock) {
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(GOSSIP_QUEUE_CAPACITY);
        peers.put(key, sock);
        sendQueues.put(key, queue);
        senderPool.submit(() -> senderLoop(key, sock, queue));
    }

    /** Single-writer drain loop per peer: coalesces up to GOSSIP_BATCH_MAX
     *  frames into one write + one flush, and removes the peer on failure. */
    private void senderLoop(String key, Socket sock, BlockingQueue<byte[]> queue) {
        try (DataOutputStream out = new DataOutputStream(sock.getOutputStream())) {
            while (running && !sock.isClosed()) {
                byte[] frame = queue.poll(1, TimeUnit.SECONDS);
                if (frame == null) {
                    continue;
                }
                out.write(frame);
                int sent = 1;
                while (sent < GOSSIP_BATCH_MAX) {
                    frame = queue.poll();
                    if (frame == null) {
                        break;
                    }
                    out.write(frame);
                    sent++;
                }
                out.flush();
            }
        } catch (Exception e) {
            log.debug("Gossip send to {} failed: {}", key, e.getMessage());
        } finally {
            peers.remove(key);
            sendQueues.remove(key);
            try { sock.close(); } catch (Exception ignore) { }
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
        for (Map.Entry<String, BlockingQueue<byte[]>> e : sendQueues.entrySet()) {
            if (!e.getValue().offer(frame)) {
                log.debug("Gossip queue full for {}: dropping frame", e.getKey());
            }
        }
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
        sendQueues.clear();
        listenerPool.shutdownNow();
        connectPool.shutdownNow();
        senderPool.shutdownNow();
    }
}
