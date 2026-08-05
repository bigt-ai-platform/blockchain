package net.bigtangle.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Objects;

import net.bigtangle.core.MerkleProof;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.pq.PQScriptUtils;
import net.bigtangle.crypto.pq.SignatureBundle;
import net.bigtangle.utils.Json;

/**
 * Canonical cross-chain message produced by a layer-1 node and finalised on
 * layer 0.
 *
 * <p>It commits the anchored head ({@code l1RewardHeadHash}) to a Merkle root
 * of the source chain's confirmed block hashes ({@code confirmedRoot}) via an
 * SPV proof, and may carry an embedded burn ({@link AnchorBurn}) that ties a
 * peg-out to a specific vault, recipient, amount and token. The signature
 * covers a canonical digest of ALL committed fields ({@link #canonicalDigest}),
 * so chain id, height, event id, root, proof and burn cannot be altered without
 * invalidating the signature.
 */
public class LayerAnchor implements Serializable {

    private static final long serialVersionUID = 2L;

    /** Binary format version; 1 = canonical (eventId, spvProof, burn). */
    private static final byte FORMAT_VERSION = 1;

    private String chainId;
    private String eventId;
    private Sha256Hash l1RewardHeadHash;
    private long l1Height;
    private Sha256Hash confirmedRoot;
    private byte[] signature;
    private java.util.List<byte[]> signatures = new java.util.ArrayList<>();
    private MerkleProof spvProof;
    private AnchorBurn burn;

    public LayerAnchor() {
    }

    /** Backward-compatible constructor (no event id, proof or burn). */
    public LayerAnchor(String chainId, Sha256Hash l1RewardHeadHash, long l1Height, Sha256Hash confirmedRoot,
            byte[] signature, MerkleProof spvProof) {
        this(chainId, chainId + ":" + l1Height, l1RewardHeadHash, l1Height, confirmedRoot, signature, spvProof, null);
    }

    public LayerAnchor(String chainId, String eventId, Sha256Hash l1RewardHeadHash, long l1Height,
            Sha256Hash confirmedRoot, byte[] signature, MerkleProof spvProof) {
        this(chainId, eventId, l1RewardHeadHash, l1Height, confirmedRoot, signature, spvProof, null);
    }

    public LayerAnchor(String chainId, String eventId, Sha256Hash l1RewardHeadHash, long l1Height,
            Sha256Hash confirmedRoot, byte[] signature, MerkleProof spvProof, AnchorBurn burn) {
        this.chainId = chainId;
        this.eventId = eventId;
        this.l1RewardHeadHash = l1RewardHeadHash;
        this.l1Height = l1Height;
        this.confirmedRoot = confirmedRoot;
        this.signature = signature;
        this.spvProof = spvProof;
        this.burn = burn;
    }

    public String getChainId() {
        return chainId;
    }

    public void setChainId(String chainId) {
        this.chainId = chainId;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public Sha256Hash getL1RewardHeadHash() {
        return l1RewardHeadHash;
    }

    public void setL1RewardHeadHash(Sha256Hash l1RewardHeadHash) {
        this.l1RewardHeadHash = l1RewardHeadHash;
    }

    public long getL1Height() {
        return l1Height;
    }

    public void setL1Height(long l1Height) {
        this.l1Height = l1Height;
    }

    public Sha256Hash getConfirmedRoot() {
        return confirmedRoot;
    }

    public void setConfirmedRoot(Sha256Hash confirmedRoot) {
        this.confirmedRoot = confirmedRoot;
    }

    public byte[] getSignature() {
        return signature;
    }

    public void setSignature(byte[] signature) {
        this.signature = signature;
        if (signature != null && signature.length > 0) {
            signatures.add(signature);
        }
    }

    /** Adds a signer to the M-of-N quorum (each distinct authorized key counts once). */
    public void addSignature(PQKey key) {
        if (key != null) {
            signatures.add(key.sign(canonicalDigest()).serialize());
        }
    }

    public java.util.List<byte[]> getSignatures() {
        return signatures;
    }

    /**
     * M-of-N quorum check: the number of DISTINCT signatures that verify against
     * the authorized keys must be at least {@code threshold}. A single signature
     * (legacy) counts as one signer.
     */
    public boolean verifyQuorum(java.util.List<PQKey> authorizedKeys, int threshold) {
        if (authorizedKeys == null || authorizedKeys.isEmpty() || threshold <= 0) {
            return false;
        }
        int valid = 0;
        java.util.Set<String> matched = new java.util.HashSet<>();
        for (PQKey signer : authorizedKeys) {
            for (byte[] sig : signatures) {
                if (sig == null || sig.length == 0) {
                    continue;
                }
                try {
                    if (PQScriptUtils.verifyPQ(signer.getPublicKeyBytes(), sig, canonicalDigest())) {
                        matched.add(Utils.HEX.encode(signer.getPublicKeyBytes()));
                        break;
                    }
                } catch (Exception e) {
                    // ignore bad signature
                }
            }
        }
        valid = matched.size();
        return valid >= threshold;
    }

    public MerkleProof getSpvProof() {
        return spvProof;
    }

    public void setSpvProof(MerkleProof spvProof) {
        this.spvProof = spvProof;
    }

    public AnchorBurn getBurn() {
        return burn;
    }

    public void setBurn(AnchorBurn burn) {
        this.burn = burn;
    }

    /**
     * Canonical digest over all committed fields EXCEPT the signature. A valid
     * signature over this digest binds chainId, eventId, head hash, height,
     * confirmed root, SPV proof and burn together.
     */
    public Sha256Hash canonicalDigest() {
        return Sha256Hash.of(serializeCommitted());
    }

    public SignatureBundle sign(PQKey key) {
        return key.sign(canonicalDigest());
    }

    public boolean verifySignature(PQKey signer) {
        if (signature == null || signature.length == 0) {
            return false;
        }
        return PQScriptUtils.verifyPQ(signer.getPublicKeyBytes(), signature, canonicalDigest());
    }

    /**
     * Serializes every committed field except the signature. The digest of this
     * payload is what the signer signs and the verifier checks.
     */
    private byte[] serializeCommitted() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeByte(FORMAT_VERSION);
            dos.writeUTF(chainId != null ? chainId : "");
            dos.writeUTF(eventId != null ? eventId : "");
            dos.write(l1RewardHeadHash != null ? l1RewardHeadHash.getBytes() : new byte[32]);
            dos.writeLong(l1Height);
            if (confirmedRoot != null) {
                dos.write(1);
                dos.write(confirmedRoot.getBytes());
            } else {
                dos.write(0);
            }
            if (spvProof != null) {
                byte[] proofBytes = spvProof.toByteArray();
                dos.writeInt(proofBytes.length);
                dos.write(proofBytes);
            } else {
                dos.writeInt(0);
            }
            if (burn != null) {
                byte[] burnBytes = burn.toByteArray();
                dos.writeInt(burnBytes.length);
                dos.write(burnBytes);
            } else {
                dos.writeInt(0);
            }
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] toByteArray() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.write(serializeCommitted());
        if (signature != null) {
            dos.writeInt(signature.length);
            dos.write(signature);
        } else {
            dos.writeInt(0);
        }
        // Additional M-of-N signers (the primary signature above is the first).
        int extra = Math.max(0, signatures.size() - 1);
        dos.writeInt(extra);
        for (int i = 1; i < signatures.size(); i++) {
            byte[] s = signatures.get(i);
            dos.writeInt(s.length);
            dos.write(s);
        }
        dos.flush();
        return baos.toByteArray();
    }

    public static LayerAnchor parse(byte[] data) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream dis = new DataInputStream(bais);
        LayerAnchor anchor = new LayerAnchor();
        anchor.chainId = dis.readUTF();
        byte[] hashBytes = new byte[32];
        dis.readFully(hashBytes);
        anchor.l1RewardHeadHash = Sha256Hash.wrap(hashBytes);
        anchor.l1Height = dis.readLong();
        int marker = dis.read();
        if (marker == 1) {
            byte[] rootBytes = new byte[32];
            dis.readFully(rootBytes);
            anchor.confirmedRoot = Sha256Hash.wrap(rootBytes);
        }
        int sigLen = dis.readInt();
        if (sigLen > 0) {
            anchor.signature = new byte[sigLen];
            dis.readFully(anchor.signature);
        }
        return anchor;
    }

    public static LayerAnchor parseCanonical(byte[] data) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream dis = new DataInputStream(bais);
        int version = dis.readUnsignedByte();
        if (version != FORMAT_VERSION) {
            // Legacy binary layout: no version byte, chainId first.
            return parse(data);
        }
        LayerAnchor anchor = new LayerAnchor();
        anchor.chainId = dis.readUTF();
        anchor.eventId = dis.readUTF();
        byte[] hashBytes = new byte[32];
        dis.readFully(hashBytes);
        anchor.l1RewardHeadHash = Sha256Hash.wrap(hashBytes);
        anchor.l1Height = dis.readLong();
        int rootMarker = dis.read();
        if (rootMarker == 1) {
            byte[] rootBytes = new byte[32];
            dis.readFully(rootBytes);
            anchor.confirmedRoot = Sha256Hash.wrap(rootBytes);
        }
        int proofLen = dis.readInt();
        if (proofLen > 0) {
            byte[] proofBytes = new byte[proofLen];
            dis.readFully(proofBytes);
            anchor.spvProof = MerkleProof.parse(proofBytes);
        }
        int burnLen = dis.readInt();
        if (burnLen > 0) {
            byte[] burnBytes = new byte[burnLen];
            dis.readFully(burnBytes);
            anchor.burn = AnchorBurn.parse(burnBytes);
        }
        int sigLen = dis.readInt();
        if (sigLen > 0) {
            byte[] primary = new byte[sigLen];
            dis.readFully(primary);
            anchor.signature = primary;
            anchor.signatures.add(primary);
        }
        // Additional M-of-N signers (absent in the legacy single-signature format).
        if (dis.available() >= 4) {
            try {
                int extra = dis.readInt();
                for (int i = 0; i < extra && dis.available() > 0; i++) {
                    int len = dis.readInt();
                    byte[] s = new byte[len];
                    dis.readFully(s);
                    anchor.signatures.add(s);
                }
            } catch (Exception ignored) {
                // tolerate truncated legacy payloads
            }
        }
        return anchor;
    }

    public String toJson() {
        try {
            return Json.jsonmapper().writeValueAsString(this);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static LayerAnchor fromJson(String json) {
        try {
            return Json.jsonmapper().readValue(json, LayerAnchor.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof LayerAnchor))
            return false;
        LayerAnchor that = (LayerAnchor) o;
        return l1Height == that.l1Height && Objects.equals(chainId, that.chainId)
                && Objects.equals(eventId, that.eventId)
                && Objects.equals(l1RewardHeadHash, that.l1RewardHeadHash)
                && Objects.equals(confirmedRoot, that.confirmedRoot)
                && Objects.equals(burn, that.burn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(chainId, eventId, l1RewardHeadHash, l1Height, confirmedRoot, burn);
    }

    /**
     * Embedded burn message that ties a peg-out to a specific vault UTXO,
     * recipient, amount and token. Part of the anchor's canonical digest, so it
     * is covered by the anchor signature.
     */
    public static class AnchorBurn implements Serializable {
        private static final long serialVersionUID = 1L;

        /** Vault UTXO reference as {@code <blockHashHex>:<index>}. */
        private String vaultRef;
        private String recipient;
        private long amount;
        private String tokenIdHex;

        public AnchorBurn() {
        }

        public AnchorBurn(String vaultRef, String recipient, long amount, String tokenIdHex) {
            this.vaultRef = vaultRef;
            this.recipient = recipient;
            this.amount = amount;
            this.tokenIdHex = tokenIdHex;
        }

        public String getVaultRef() {
            return vaultRef;
        }

        public void setVaultRef(String vaultRef) {
            this.vaultRef = vaultRef;
        }

        public String getRecipient() {
            return recipient;
        }

        public void setRecipient(String recipient) {
            this.recipient = recipient;
        }

        public long getAmount() {
            return amount;
        }

        public void setAmount(long amount) {
            this.amount = amount;
        }

        public String getTokenIdHex() {
            return tokenIdHex;
        }

        public void setTokenIdHex(String tokenIdHex) {
            this.tokenIdHex = tokenIdHex;
        }

        public byte[] toByteArray() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeUTF(vaultRef != null ? vaultRef : "");
            dos.writeUTF(recipient != null ? recipient : "");
            dos.writeLong(amount);
            dos.writeUTF(tokenIdHex != null ? tokenIdHex : "");
            dos.flush();
            return baos.toByteArray();
        }

        public static AnchorBurn parse(byte[] data) throws IOException {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
            AnchorBurn burn = new AnchorBurn();
            burn.vaultRef = dis.readUTF();
            burn.recipient = dis.readUTF();
            burn.amount = dis.readLong();
            burn.tokenIdHex = dis.readUTF();
            return burn;
        }

        public String toJson() {
            try {
                return Json.jsonmapper().writeValueAsString(this);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public static AnchorBurn fromJson(String json) throws IOException {
            try {
                return Json.jsonmapper().readValue(json, AnchorBurn.class);
            } catch (Exception e) {
                throw new IOException("Cannot parse AnchorBurn from JSON", e);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof AnchorBurn))
                return false;
            AnchorBurn that = (AnchorBurn) o;
            return amount == that.amount && Objects.equals(vaultRef, that.vaultRef)
                    && Objects.equals(recipient, that.recipient) && Objects.equals(tokenIdHex, that.tokenIdHex);
        }

        @Override
        public int hashCode() {
            return Objects.hash(vaultRef, recipient, amount, tokenIdHex);
        }
    }
}
