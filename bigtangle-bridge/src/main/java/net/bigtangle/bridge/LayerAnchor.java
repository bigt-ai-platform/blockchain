package net.bigtangle.bridge;

/**
 * Anchor payload carried in a {@code BLOCKTYPE_CROSSTANGLE} transaction from a
 * Layer 1 sub-chain to Layer 0 for finalisation.
 *
 * <p>
 * Every N reward milestones the L1 milestone node posts an anchor containing
 * the current L1 tip and a Merkle root of the confirmed block window. L0
 * confirms the anchor, which finalises the referenced L1 state and gates
 * peg-out releases.
 *
 * <p>
 * Phase 2.5 extends this class with an {@code spvProof} field that carries a
 * compact SPV proof so L0 can cryptographically verify the anchor instead of
 * trusting the milestone key alone.
 */
public class LayerAnchor {

    private String chainId;
    private String l1RewardHeadHash;
    private long l1Height;
    private String confirmedRoot;
    private String sig;

    public LayerAnchor() {
    }

    public LayerAnchor(String chainId, String l1RewardHeadHash, long l1Height, String confirmedRoot, String sig) {
        this.chainId = chainId;
        this.l1RewardHeadHash = l1RewardHeadHash;
        this.l1Height = l1Height;
        this.confirmedRoot = confirmedRoot;
        this.sig = sig;
    }

    public String getChainId() {
        return chainId;
    }

    public void setChainId(String chainId) {
        this.chainId = chainId;
    }

    public String getL1RewardHeadHash() {
        return l1RewardHeadHash;
    }

    public void setL1RewardHeadHash(String l1RewardHeadHash) {
        this.l1RewardHeadHash = l1RewardHeadHash;
    }

    public long getL1Height() {
        return l1Height;
    }

    public void setL1Height(long l1Height) {
        this.l1Height = l1Height;
    }

    public String getConfirmedRoot() {
        return confirmedRoot;
    }

    public void setConfirmedRoot(String confirmedRoot) {
        this.confirmedRoot = confirmedRoot;
    }

    public String getSig() {
        return sig;
    }

    public void setSig(String sig) {
        this.sig = sig;
    }
}
