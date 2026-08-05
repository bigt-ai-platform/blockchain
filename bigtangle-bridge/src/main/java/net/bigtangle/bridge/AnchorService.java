package net.bigtangle.bridge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.MerkleProof;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.pq.SignatureBundle;
import net.bigtangle.core.Address;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.server.data.AnchorRecord;
import net.bigtangle.server.service.BlockSaveService;
import net.bigtangle.server.service.CacheBlockPrototypeService;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;

@Service
public class AnchorService {

    private static final Logger logger = LoggerFactory.getLogger(AnchorService.class);

    @Autowired
    private AnchorConfiguration anchorConfiguration;

    @Autowired
    private NetworkParameters networkParameters;

    @Autowired
    private BlockSaveService blockSaveService;

    @Autowired
    private CacheBlockService cacheBlockService;

    @Autowired
    private CacheBlockPrototypeService cacheBlockPrototypeService;

    /**
     * Builds and locally saves an anchor for this chain's latest confirmed
     * reward, then posts it to L0 for finalisation. The anchor is signed with
     * the configured {@code anchor.priKeyHex} key (never a fresh random key),
     * and the signature covers the full canonical digest (chain id, event id,
     * height, head hash, Merkle root and SPV proof).
     */
    public void postAnchor(BlockStoreInterface store) throws Exception {
        if (!anchorConfiguration.isActive()) {
            logger.debug("Anchor service is not active");
            return;
        }
        String priKeyHex = anchorConfiguration.getPriKeyHex();
        if (priKeyHex == null || priKeyHex.isEmpty()) {
            logger.warn("anchor.priKeyHex not configured; cannot sign anchors");
            return;
        }

        TXReward maxConfirmedReward = cacheBlockService.getMaxConfirmedReward(store);
        if (maxConfirmedReward == null) {
            logger.warn("No max confirmed reward available, skipping anchor post");
            return;
        }

        Sha256Hash l1RewardHeadHash = maxConfirmedReward.getBlockHash();
        long l1Height = maxConfirmedReward.getChainLength();

        PQKey signKey = PQKey.fromPrivateKeyHex(priKeyHex);
        String pubKeyHex = anchorConfiguration.getPubKeyHex();
        if (pubKeyHex != null && !pubKeyHex.isEmpty()
                && !Utils.HEX.encode(signKey.getPublicKeyBytes()).equals(pubKeyHex)) {
            logger.warn("anchor.priKeyHex does not match anchor.pubKeyHex; refusing to sign");
            return;
        }

        List<Sha256Hash> confirmedHashes = collectConfirmedBlockHashes(l1Height, store);
        Sha256Hash confirmedRoot = MerkleProof.computeRoot(confirmedHashes);
        MerkleProof spvProof = MerkleProof.buildProofFor(confirmedHashes, l1RewardHeadHash);

        LayerAnchor anchor = new LayerAnchor(networkParameters.getChainId(),
                networkParameters.getChainId() + ":" + l1Height,
                l1RewardHeadHash, l1Height, confirmedRoot, null, spvProof, null);
        SignatureBundle sig = anchor.sign(signKey);
        anchor.setSignature(sig.serialize());

        Block b = cacheBlockPrototypeService.getBlockPrototype(store);
        b.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);

        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName("LayerAnchor");
        tx.setData(anchor.toByteArray());
        b.addTransaction(tx);

        // Permissive save: CROSSTANGLE blocks are signed cross-chain messages,
        // not fee-bearing value transfers; the strict saveBlock path (which
        // enforces input/output conservation and a fee) would reject them.
        blockSaveService.saveBlockPermissive(b, store);
        logger.info("Anchor block saved locally: {} for chain {} at height {}", b.getHashAsString(),
                anchor.getChainId(), l1Height);

        String l0Url = anchorConfiguration.getL0Url();
        if (l0Url != null && !l0Url.isEmpty()) {
            try {
                OkHttp3Util.post(l0Url + "/" + ReqCmd.batchBlock.name(), b.bitcoinSerialize());
                logger.info("Anchor block posted to L0: {}", b.getHashAsString());
            } catch (Exception e) {
                logger.warn("Failed to post anchor to L0 at {}: {}", l0Url, e.getMessage());
            }
        }
    }

    /**
     * Called after an anchor's CROSSTANGLE block is confirmed or unconfirmed on L0.
     * Marks the anchor record as confirmed (or unconfirmed on rollback) and
     * credits the anchor reward to the L1 chainlength node on initial confirmation.
     *
     * @param confirmed true if confirming, false if rolling back (reorg)
     */
    public void confirmAnchor(Block block, boolean confirmed, BlockStoreInterface store) throws Exception {
        if (block.getBlockType() != BlockType.BLOCKTYPE_CROSSTANGLE) {
            return;
        }
        AnchorRecord anchor = store.getAnchorByBlockHash(block.getHash());
        if (anchor == null) {
            logger.warn("No anchor record found for confirmed CROSSTANGLE block {}", block.getHashAsString());
            return;
        }
        if (anchor.isConfirmed() == confirmed) {
            return;
        }
        store.updateAnchorConfirmed(anchor.getChainId(), anchor.getL1Height(), confirmed);
        if (confirmed) {
            logger.info("Anchor confirmed for chain {} at height {}", anchor.getChainId(), anchor.getL1Height());
            creditAnchorReward(anchor, store);
        } else {
            logger.info("Anchor unconfirmed (reorg) for chain {} at height {}", anchor.getChainId(), anchor.getL1Height());
        }
    }

    /**
     * Credits the anchor reward to the L1 chainlength node. DISABLED: the
     * previous implementation minted an output-only transaction with no input
     * (free inflation) — the configured fee-pool key was never used to actually
     * spend pooled funds. Until the reward is backed by a real spend of
     * fee-pool UTXOs (signed by the fee pool key), no unbacked value is created.
     */
    private void creditAnchorReward(AnchorRecord anchor, BlockStoreInterface store) throws Exception {
        long rewardAmount = anchorConfiguration.getRewardAmount();
        String feePoolPriKeyHex = anchorConfiguration.getFeePoolPriKeyHex();
        if (rewardAmount <= 0 || feePoolPriKeyHex == null || feePoolPriKeyHex.isEmpty()) {
            return;
        }
        logger.warn("Anchor reward of {} for chain {} is NOT credited: no backed fee-pool spend is implemented",
                rewardAmount, anchor.getChainId());
    }

    /**
     * Validates an anchor's signature, SPV proof and embedded burn, then records
     * it. Rejects anchors whose signature is invalid, whose SPV proof does not
     * bind the anchored head hash to the committed Merkle root, or whose burn is
     * malformed.
     */
    public void validateAndSaveAnchor(LayerAnchor anchor, Sha256Hash l0BlockHash, BlockStoreInterface store)
            throws Exception {
        validateAnchor(anchor);

        AnchorRecord record = new AnchorRecord(anchor.getChainId(), anchor.getL1RewardHeadHash(),
                anchor.getL1Height(), anchor.getConfirmedRoot(),
                Utils.HEX.encode(anchor.getSignature()), l0BlockHash, false);
        record.setEventId(anchor.getEventId() != null ? anchor.getEventId()
                : anchor.getChainId() + ":" + anchor.getL1Height());
        record.setSpvProofHex(anchor.getSpvProof().toHex());
        // F6: persist the FULL M-of-N signature set so a remote AnchorRecord can
        // be revalidated as a quorum (not just the primary signature).
        java.util.List<String> sigHex = new ArrayList<>();
        for (byte[] sig : anchor.getSignatures()) {
            if (sig != null && sig.length > 0) {
                sigHex.add(Utils.HEX.encode(sig));
            }
        }
        if (!sigHex.isEmpty()) {
            record.setSignatureHexList(Json.jsonmapper().writeValueAsString(sigHex));
        }
        if (anchor.getBurn() != null) {
            record.setBurnJson(anchor.getBurn().toJson());
        }

        store.saveAnchor(record);
        logger.info("Saved anchor record for chain {} at height {}", anchor.getChainId(), anchor.getL1Height());
    }

    /**
     * Structural + cryptographic validation of an anchor: signature over the
     * canonical digest, SPV proof binding the head hash to the root, and burn
     * well-formedness. Throws on the first failure.
     */
    public void validateAnchor(LayerAnchor anchor) throws Exception {
        if (anchor.getL1RewardHeadHash() == null) {
            throw new BlockStoreException("Anchor l1RewardHeadHash is null");
        }
        if (anchor.getChainId() == null || anchor.getChainId().isEmpty()) {
            throw new BlockStoreException("Anchor chainId is null or empty");
        }
        if (anchor.getL1Height() < 0) {
            throw new BlockStoreException("Anchor l1Height must be non-negative");
        }
        // eventId is signature-covered, but it must ALSO match the committed
        // chain position — otherwise a signer could tag an anchor with a
        // misleading event id while keeping a valid signature.
        if (anchor.getEventId() != null
                && !(anchor.getChainId() + ":" + anchor.getL1Height()).equals(anchor.getEventId())) {
            throw new BlockStoreException("Anchor eventId '" + anchor.getEventId()
                    + "' does not match " + anchor.getChainId() + ":" + anchor.getL1Height());
        }
        if (anchor.getConfirmedRoot() == null) {
            throw new BlockStoreException("Anchor confirmedRoot is null");
        }
        if (anchor.getSpvProof() == null) {
            throw new BlockStoreException("Anchor SPV proof is missing");
        }
        if (anchor.getSignature() == null || anchor.getSignature().length == 0) {
            throw new BlockStoreException("Anchor signature is missing");
        }

        String pubKeyHex = anchorConfiguration.getPubKeyHex();
        if (pubKeyHex == null || pubKeyHex.isEmpty()) {
            throw new BlockStoreException("anchor.pubKeyHex not configured; cannot verify anchor");
        }
        // Per-chain registry + M-of-N quorum: the anchor is valid if at least
        // chainSignersRequired DISTINCT authorized keys for its chain signed it
        // (falls back to the global key). A single compromised key cannot forge
        // anchors for a chain with its own registry entry, and a threshold
        // quorum requires multiple distinct signers when configured.
        java.util.List<String> authorized = anchorConfiguration.getChainPubKeys(anchor.getChainId());
        if (authorized.isEmpty()) {
            throw new BlockStoreException("No authorized anchor signer for chain " + anchor.getChainId());
        }
        java.util.List<PQKey> signerKeys = new ArrayList<>();
        for (String keyHex : authorized) {
            signerKeys.add(PQKey.fromPublicOnly(Utils.HEX.decode(keyHex)));
        }
        if (!anchor.verifyQuorum(signerKeys, anchorConfiguration.getChainSignersRequired())) {
            throw new BlockStoreException(
                    "Anchor signature quorum verification failed for chain " + anchor.getChainId());
        }

        boolean spvValid = anchor.getSpvProof().verify(anchor.getL1RewardHeadHash(), anchor.getConfirmedRoot());
        if (!spvValid) {
            throw new BlockStoreException(
                    "SPV proof verification failed for chain " + anchor.getChainId()
                            + " at height " + anchor.getL1Height());
        }

        validateBurn(anchor);
    }

    private void validateBurn(LayerAnchor anchor) throws BlockStoreException {
        LayerAnchor.AnchorBurn burn = anchor.getBurn();
        if (burn == null) {
            return;
        }
        if (burn.getAmount() <= 0) {
            throw new BlockStoreException("Anchor burn amount must be positive");
        }
        if (burn.getRecipient() == null || burn.getRecipient().isEmpty()) {
            throw new BlockStoreException("Anchor burn recipient is missing");
        }
        if (burn.getTokenIdHex() == null || burn.getTokenIdHex().isEmpty()) {
            throw new BlockStoreException("Anchor burn token id is missing");
        }
        String vaultRef = burn.getVaultRef();
        if (vaultRef == null || !vaultRef.contains(":")) {
            throw new BlockStoreException("Anchor burn vault reference is missing or malformed");
        }
        try {
            Address.fromBase58(networkParameters, burn.getRecipient());
        } catch (Exception e) {
            throw new BlockStoreException("Anchor burn recipient is not a valid address");
        }
    }

    /**
     * Incremental cache of the confirmed-block set committed by the last anchor,
     * so an anchor post walks only the newly confirmed blocks (O(delta)) instead
     * of the whole reward chain back to genesis (O(chain length)).
     */
    private volatile AnchorHistoryCache historyCache;

    private static final class AnchorHistoryCache {
        final long height;             // last anchored chainlength
        final Sha256Hash anchorHash;   // confirmed reward block at chainlength == height
        final List<Sha256Hash> hashes; // sorted confirmed block hashes with chainlength <= height

        AnchorHistoryCache(long height, Sha256Hash anchorHash, List<Sha256Hash> hashes) {
            this.height = height;
            this.anchorHash = anchorHash;
            this.hashes = hashes;
        }
    }

    /**
     * Collects the hashes of all CONFIRMED reward blocks up to {@code upToHeight}
     * (the anchored chainlength) by walking the confirmed reward chain back to
     * genesis. The Merkle root over this set commits the anchor to the actual
     * anchored chain state — not a 2-leaf stub of {head, genesis} — so the
     * embedded "SPV proof" is a genuine inclusion proof over the anchored chain.
     *
     * <p>Incremental: after the first walk the cache is reused and only the
     * newly confirmed blocks are collected, unless a reorg moved the anchor
     * point (detected by comparing the reward block at the cached chainlength),
     * in which case the set is rebuilt from scratch.
     */
    private List<Sha256Hash> collectConfirmedBlockHashes(long upToHeight, BlockStoreInterface store) throws Exception {
        AnchorHistoryCache cache = historyCache;
        if (cache != null && cache.height < upToHeight) {
            List<Sha256Hash> fresh = collectNewSince(cache.height, cache.anchorHash, upToHeight, store);
            if (fresh != null) {
                List<Sha256Hash> merged = new ArrayList<>(cache.hashes);
                merged.addAll(fresh);
                Collections.sort(merged);
                TXReward tip = cacheBlockService.getMaxConfirmedReward(store);
                historyCache = new AnchorHistoryCache(upToHeight,
                        tip != null ? tip.getBlockHash() : cache.anchorHash, merged);
                return merged;
            }
            // Reorg or the cached anchor point is no longer reachable: rebuild.
        }
        List<Sha256Hash> hashes = new ArrayList<>();
        TXReward reward = cacheBlockService.getMaxConfirmedReward(store);
        Sha256Hash anchorHash = null;
        if (reward != null) {
            Sha256Hash cursor = reward.getBlockHash();
            java.util.Set<Sha256Hash> visited = new java.util.HashSet<>();
            while (cursor != null && visited.add(cursor)) {
                Block b = store.get(cursor);
                if (b == null || b.getBlockType() == BlockType.BLOCKTYPE_INITIAL) {
                    break;
                }
                net.bigtangle.core.RewardInfo ri = new net.bigtangle.core.RewardInfo()
                        .parseChecked(b.getTransactions().get(0).getData());
                if (ri == null) {
                    break;
                }
                if (ri.getChainlength() > upToHeight) {
                    cursor = ri.getPrevRewardHash(); // still above the anchored height
                    continue;
                }
                if (anchorHash == null && ri.getChainlength() == upToHeight) {
                    anchorHash = cursor;
                }
                hashes.add(cursor);
                if (ri.getChainlength() <= 1) {
                    break; // reached genesis
                }
                cursor = ri.getPrevRewardHash();
            }
            Collections.sort(hashes);
        }
        if (anchorHash == null && reward != null) {
            anchorHash = reward.getBlockHash();
        }
        historyCache = new AnchorHistoryCache(upToHeight, anchorHash, hashes);
        return hashes;
    }

    /**
     * Walks the confirmed reward chain from the tip back to the cached anchor
     * point, collecting the newly confirmed block hashes (chainlength in
     * (fromHeight, upToHeight]). Returns null when the anchor point no longer
     * matches (reorg) or cannot be reached — the caller then rebuilds.
     */
    private List<Sha256Hash> collectNewSince(long fromHeight, Sha256Hash anchorHash, long upToHeight,
            BlockStoreInterface store) throws Exception {
        TXReward tip = cacheBlockService.getMaxConfirmedReward(store);
        if (tip == null) {
            return null;
        }
        List<Sha256Hash> fresh = new ArrayList<>();
        Sha256Hash cursor = tip.getBlockHash();
        java.util.Set<Sha256Hash> visited = new java.util.HashSet<>();
        boolean reachedAnchor = false;
        while (cursor != null && visited.add(cursor)) {
            Block b = store.get(cursor);
            if (b == null || b.getBlockType() == BlockType.BLOCKTYPE_INITIAL) {
                break;
            }
            net.bigtangle.core.RewardInfo ri = new net.bigtangle.core.RewardInfo()
                    .parseChecked(b.getTransactions().get(0).getData());
            if (ri == null) {
                return null;
            }
            long chainlength = ri.getChainlength();
            if (chainlength > upToHeight) {
                cursor = ri.getPrevRewardHash();
                continue;
            }
            if (chainlength <= fromHeight) {
                // The cached anchor point must be the same confirmed block.
                reachedAnchor = chainlength == fromHeight && cursor.equals(anchorHash);
                break;
            }
            fresh.add(cursor);
            if (chainlength <= 1) {
                break;
            }
            cursor = ri.getPrevRewardHash();
        }
        return reachedAnchor ? fresh : null;
    }

    public void processReceivedAnchor(Block block, BlockStoreInterface store) throws Exception {
        if (block.getBlockType() != BlockType.BLOCKTYPE_CROSSTANGLE) {
            return;
        }

        List<Transaction> transactions = block.getTransactions();
        if (transactions == null || transactions.isEmpty()) {
            logger.warn("CROSSTANGLE block has no transactions");
            return;
        }

        Transaction tx = transactions.get(0);
        if (!"LayerAnchor".equals(tx.getDataClassName())) {
            logger.debug("Transaction data class is not LayerAnchor, got: {}", tx.getDataClassName());
            return;
        }

        byte[] data = tx.getData();
        if (data == null) {
            logger.warn("Transaction data is null");
            return;
        }

        LayerAnchor anchor;
        try {
            anchor = LayerAnchor.parseCanonical(data);
        } catch (Exception e) {
            try {
                anchor = LayerAnchor.parse(data);
            } catch (Exception e2) {
                anchor = LayerAnchor.fromJson(new String(data, StandardCharsets.UTF_8));
            }
        }
        validateAndSaveAnchor(anchor, block.getHash(), store);
    }

    /**
     * Re-validates a remote {@link AnchorRecord} (as fetched from L0) before it
     * is trusted and persisted by an L1 node. Rejects records whose signature,
     * SPV proof or burn are invalid — a compromised anchor endpoint can no
     * longer inject unauthenticated "confirmed" anchors.
     */
    public boolean revalidateAnchorRecord(AnchorRecord record) {
        try {
            LayerAnchor anchor = new LayerAnchor(record.getChainId(),
                    record.getEventId() != null ? record.getEventId() : record.getChainId() + ":" + record.getL1Height(),
                    record.getL1RewardHeadHash(), record.getL1Height(), record.getConfirmedRoot(),
                    record.getSignatureHex() != null ? Utils.HEX.decode(record.getSignatureHex()) : null,
                    record.getSpvProofHex() != null ? MerkleProof.fromHex(record.getSpvProofHex()) : null,
                    record.getBurnJson() != null && !record.getBurnJson().isEmpty()
                            ? LayerAnchor.AnchorBurn.fromJson(record.getBurnJson()) : null);
            // F6: restore the FULL M-of-N signature set so revalidation checks
            // the quorum, not just the primary signature. The primary signature
            // is always included (legacy records have no signatureHexList).
            if (record.getSignatureHex() != null && !record.getSignatureHex().isEmpty()) {
                anchor.getSignatures().add(Utils.HEX.decode(record.getSignatureHex()));
            }
            if (record.getSignatureHexList() != null && !record.getSignatureHexList().isEmpty()) {
                java.util.List<String> sigHex = Json.jsonmapper().readValue(record.getSignatureHexList(),
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});
                if (sigHex != null) {
                    for (String hex : sigHex) {
                        if (hex != null && !hex.isEmpty()) {
                            anchor.getSignatures().add(Utils.HEX.decode(hex));
                        }
                    }
                }
            }
            validateAnchor(anchor);
            return true;
        } catch (Exception e) {
            logger.warn("Rejected invalid remote anchor record for chain {} at height {}: {}",
                    record.getChainId(), record.getL1Height(), e.getMessage());
            return false;
        }
    }
}
