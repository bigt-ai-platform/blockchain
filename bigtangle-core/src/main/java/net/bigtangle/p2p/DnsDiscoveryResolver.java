package net.bigtangle.p2p;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DnsDiscoveryResolver {

    private static final Logger log = LoggerFactory.getLogger(DnsDiscoveryResolver.class);

    private static final String ROOT_PREFIX = "enrtree-root:v1";
    private static final String BRANCH_PREFIX = "enrtree-branch:";
    private static final String LEAF_PREFIX = "enr:";

    public static List<NodeRecord> resolve(String url) {
        if (url == null) return List.of();

        if (url.startsWith("enrtree://")) {
            return resolveEnrTree(url);
        }

        String[] parts = url.split(":");
        if (parts.length == 2) {
            try {
                int port = Integer.parseInt(parts[1]);
                return resolveSeedDomain(parts[0], port);
            } catch (NumberFormatException e) {
                log.warn("Invalid port in DNS seed: {}", url);
            }
        }

        log.warn("Unrecognized DNS seed format: {}", url);
        return List.of();
    }

    public static List<NodeRecord> resolveEnrTree(String enrTreeUrl) {
        if (enrTreeUrl == null || !enrTreeUrl.startsWith("enrtree://"))
            throw new IllegalArgumentException("Invalid enrtree URL: " + enrTreeUrl);

        String rest = enrTreeUrl.substring("enrtree://".length());
        int atIndex = rest.indexOf('@');
        if (atIndex < 0)
            throw new IllegalArgumentException("Missing @ in enrtree URL: " + enrTreeUrl);

        String pubkeyHex = rest.substring(0, atIndex);
        String domain = rest.substring(atIndex + 1);

        byte[] expectedPubKey = java.util.HexFormat.of().parseHex(pubkeyHex);

        List<String> rootTxts = queryTxt(domain);
        if (rootTxts.isEmpty()) {
            log.warn("No TXT records found for enrtree domain: {}", domain);
            return List.of();
        }

        RootRecord root = parseRootRecord(rootTxts.get(0));
        if (root == null) return List.of();

        byte[] rootData = serializeRootForSigning(root);
        if (!verifySignatureStatic(expectedPubKey, rootData, root.signature)) {
            log.warn("Invalid root signature for enrtree domain: {}", domain);
            return List.of();
        }

        List<NodeRecord> result = new ArrayList<>();
        resolveEnrTreeRecursive(domain, root.entryHash, expectedPubKey, result, new java.util.HashSet<>());
        return result;
    }

    public static List<NodeRecord> resolveSeedDomain(String domain, int defaultPort) {
        List<NodeRecord> records = new ArrayList<>();
        try {
            InetAddress[] addresses = InetAddress.getAllByName(domain);
            NodeRecord.KeyPair ephemeral = NodeRecord.generateKeyPair();
            for (InetAddress addr : addresses) {
                String host = addr.getHostAddress();
                if (host.contains(":")) continue;
                NodeRecord record = NodeRecord.createSelf(ephemeral, host, defaultPort, defaultPort, 0);
                records.add(record);
            }
            log.info("DNS A-record resolved {} -> {} peers", domain, records.size());
        } catch (Exception e) {
            log.warn("DNS A-record resolution failed for {}: {}", domain, e.getMessage());
        }
        return records;
    }

    private static void resolveEnrTreeRecursive(String domain, String hash,
                                                  byte[] expectedPubKey,
                                                  List<NodeRecord> result,
                                                  Set<String> visited) {
        if (hash == null || hash.isEmpty() || visited.contains(hash)) return;
        visited.add(hash);

        String subdomain = hash + "." + domain;
        List<String> txts = queryTxt(subdomain);
        if (txts.isEmpty()) return;

        String record = txts.get(0);

        if (record.startsWith(BRANCH_PREFIX)) {
            List<String> childHashes = parseBranchRecord(record);
            for (String childHash : childHashes) {
                resolveEnrTreeRecursive(domain, childHash, expectedPubKey, result, visited);
            }
        } else if (record.startsWith(LEAF_PREFIX)) {
            NodeRecord nodeRecord = parseLeafRecord(record);
            if (nodeRecord != null) {
                result.add(nodeRecord);
            }
        } else {
            log.debug("Unknown DNS record type: {}", record);
        }
    }

    static List<String> queryTxt(String domain) {
        List<String> records = new ArrayList<>();
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns:");

            DirContext ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(domain, new String[]{"TXT"});
            if (attrs != null) {
                Attribute txt = attrs.get("TXT");
                if (txt != null) {
                    NamingEnumeration<?> ne = txt.getAll();
                    while (ne.hasMore()) {
                        Object val = ne.next();
                        String str = val.toString();
                        if (str.startsWith("\"") && str.endsWith("\"")) {
                            str = str.substring(1, str.length() - 1);
                        }
                        records.add(str);
                    }
                }
            }
            ctx.close();
        } catch (Exception e) {
            log.debug("DNS TXT query failed for {}: {}", domain, e.getMessage());
        }
        return records;
    }

    static RootRecord parseRootRecord(String txt) {
        if (txt == null || !txt.startsWith(ROOT_PREFIX)) return null;

        String eHash = null;
        long seq = 0;
        byte[] sig = null;

        String[] parts = txt.split(" ");
        for (String part : parts) {
            if (part.startsWith("e=")) {
                eHash = part.substring(2);
            } else if (part.startsWith("seq=")) {
                seq = Long.parseLong(part.substring(4));
            } else if (part.startsWith("sig=")) {
                sig = java.util.HexFormat.of().parseHex(part.substring(4));
            }
        }

        if (eHash == null || sig == null) return null;

        return new RootRecord(eHash, seq, sig);
    }

    static List<String> parseBranchRecord(String txt) {
        List<String> hashes = new ArrayList<>();
        if (txt == null || !txt.startsWith(BRANCH_PREFIX)) return hashes;

        String rest = txt.substring(BRANCH_PREFIX.length()).trim();
        String[] parts = rest.split(" ");
        for (String part : parts) {
            part = part.trim();
            if (!part.isEmpty()) {
                hashes.add(part);
            }
        }
        return hashes;
    }

    static NodeRecord parseLeafRecord(String txt) {
        if (txt == null || !txt.startsWith(LEAF_PREFIX)) return null;
        String enrHex = txt.substring(LEAF_PREFIX.length()).trim();
        try {
            return NodeRecord.fromEnr("enr:" + enrHex);
        } catch (Exception e) {
            log.debug("Invalid ENR in DNS leaf: {}", e.getMessage());
            return null;
        }
    }

    static byte[] serializeRootForSigning(RootRecord root) {
        String content = "enrtree-root:v1 e=" + root.entryHash + " seq=" + root.seq;
        return content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static boolean verifySignatureStatic(byte[] publicKey, byte[] data, byte[] signature) {
        try {
            org.bouncycastle.crypto.signers.Ed25519Signer signer = new org.bouncycastle.crypto.signers.Ed25519Signer();
            signer.init(false, new org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(publicKey, 0));
            signer.update(data, 0, data.length);
            return signer.verifySignature(signature);
        } catch (Exception e) {
            log.debug("Signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static class RootRecord {
        final String entryHash;
        final long seq;
        final byte[] signature;

        RootRecord(String entryHash, long seq, byte[] signature) {
            this.entryHash = entryHash;
            this.seq = seq;
            this.signature = signature;
        }
    }
}
