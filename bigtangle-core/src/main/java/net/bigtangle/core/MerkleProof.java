package net.bigtangle.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Compact Merkle proof for SPV verification. Proves that a given leaf hash
 * is contained in a Merkle tree with a known root, without revealing the
 * full leaf set.
 *
 * <p>Structure: a list of sibling hashes. To verify, start at the leaf,
 * hash it with each sibling (ordering by the position flag), and check
 * the final result matches the expected root.
 */
public class MerkleProof {

    private final List<byte[]> siblings;
    private final List<Boolean> positions; // true = sibling is on the left

    public MerkleProof() {
        this.siblings = new ArrayList<>();
        this.positions = new ArrayList<>();
    }

    public void addSibling(byte[] hash, boolean isLeft) {
        siblings.add(hash);
        positions.add(isLeft);
    }

    public List<byte[]> getSiblings() {
        return Collections.unmodifiableList(siblings);
    }

    public List<Boolean> getPositions() {
        return Collections.unmodifiableList(positions);
    }

    /**
     * Verifies this proof against a leaf hash and expected Merkle root.
     */
    public boolean verify(Sha256Hash leaf, Sha256Hash expectedRoot) {
        byte[] current = leaf.getBytes();
        for (int i = 0; i < siblings.size(); i++) {
            byte[] sibling = siblings.get(i);
            boolean isLeft = positions.get(i);
            byte[] combined;
            if (isLeft) {
                combined = new byte[sibling.length + current.length];
                System.arraycopy(sibling, 0, combined, 0, sibling.length);
                System.arraycopy(current, 0, combined, sibling.length, current.length);
            } else {
                combined = new byte[current.length + sibling.length];
                System.arraycopy(current, 0, combined, 0, current.length);
                System.arraycopy(sibling, 0, combined, current.length, sibling.length);
            }
            current = Sha256Hash.hashTwice(combined);
        }
        return Sha256Hash.wrap(current).equals(expectedRoot);
    }

    /**
     * Builds a Merkle tree from the given leaf hashes and returns the root.
     * The leaves must be sorted.
     */
    public static Sha256Hash computeRoot(List<Sha256Hash> leaves) {
        if (leaves == null || leaves.isEmpty()) {
            return Sha256Hash.ZERO_HASH;
        }
        List<byte[]> level = new ArrayList<>();
        for (Sha256Hash leaf : leaves) {
            level.add(leaf.getBytes());
        }
        while (level.size() > 1) {
            List<byte[]> nextLevel = new ArrayList<>();
            for (int i = 0; i < level.size(); i += 2) {
                byte[] left = level.get(i);
                byte[] right = (i + 1 < level.size()) ? level.get(i + 1) : left;
                byte[] combined = new byte[left.length + right.length];
                System.arraycopy(left, 0, combined, 0, left.length);
                System.arraycopy(right, 0, combined, left.length, right.length);
                nextLevel.add(Sha256Hash.hashTwice(combined));
            }
            level = nextLevel;
        }
        return Sha256Hash.wrap(level.get(0));
    }

    /**
     * Builds a Merkle proof for the leaf at the given index in the sorted
     * leaf list. Returns both the root and the proof.
     */
    public static ProofResult buildProof(List<Sha256Hash> leaves, int leafIndex) {
        if (leaves == null || leaves.isEmpty() || leafIndex < 0 || leafIndex >= leaves.size()) {
            throw new IllegalArgumentException("Invalid leaf index");
        }
        MerkleProof proof = new MerkleProof();
        List<byte[]> level = new ArrayList<>();
        for (Sha256Hash leaf : leaves) {
            level.add(leaf.getBytes());
        }
        int idx = leafIndex;
        while (level.size() > 1) {
            List<byte[]> nextLevel = new ArrayList<>();
            for (int i = 0; i < level.size(); i += 2) {
                byte[] left = level.get(i);
                byte[] right = (i + 1 < level.size()) ? level.get(i + 1) : left;
                byte[] combined = new byte[left.length + right.length];
                System.arraycopy(left, 0, combined, 0, left.length);
                System.arraycopy(right, 0, combined, left.length, right.length);
                combined = Sha256Hash.hashTwice(combined);
                nextLevel.add(combined);

                if (i == idx || i + 1 == idx) {
                    boolean isLeft = (i == idx);
                    byte[] sibling = isLeft ? right : left;
                    proof.addSibling(sibling, !isLeft);
                    idx = nextLevel.size() - 1;
                }
            }
            level = nextLevel;
        }
        return new ProofResult(Sha256Hash.wrap(level.get(0)), proof);
    }

    /**
     * Builds a Merkle tree and returns the root and a proof for the given leaf.
     */
    public static MerkleProof buildProofFor(List<Sha256Hash> leaves, Sha256Hash leaf) {
        int idx = Collections.binarySearch(leaves, leaf);
        if (idx < 0) {
            throw new IllegalArgumentException("Leaf not found in leaf list");
        }
        return buildProof(leaves, idx).proof;
    }

    public static class ProofResult {
        public final Sha256Hash root;
        public final MerkleProof proof;

        public ProofResult(Sha256Hash root, MerkleProof proof) {
            this.root = root;
            this.proof = proof;
        }
    }
}
