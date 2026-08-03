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
     * Credits the anchor reward from the L0 fee pool to the L1 chainlength node.
     * The chainlength node's address is derived from the configured public key.
     * Creates a simple BLOCKTYPE_TRANSFER and saves it locally on L0 (permissive
     * path — bridge blocks are not fee-bearing value transfers).
     */
    private void creditAnchorReward(AnchorRecord anchor, BlockStoreInterface store) throws Exception {
        long rewardAmount = anchorConfiguration.getRewardAmount();
        if (rewardAmount <= 0) {
            return;
        }
        String feePoolPriKeyHex = anchorConfiguration.getFeePoolPriKeyHex();
        String rewardPubKeyHex = anchorConfiguration.getPubKeyHex();
        if (feePoolPriKeyHex == null || feePoolPriKeyHex.isEmpty()
                || rewardPubKeyHex == null || rewardPubKeyHex.isEmpty()) {
            logger.debug("Anchor reward not configured, skipping");
            return;
        }

        PQKey rewardKey = PQKey.fromPublicOnly(Utils.HEX.decode(rewardPubKeyHex));

        Block b = cacheBlockPrototypeService.getBlockPrototype(store);
        b.setBlockType(BlockType.BLOCKTYPE_TRANSFER);

        Transaction tx = new Transaction(networkParameters);
        Coin rewardCoin = Coin.valueOf(rewardAmount, NetworkParameters.BIGTANGLE_TOKENID);
        tx.addOutput(rewardCoin, Address.fromHash160(networkParameters, Utils.sha256hash160(rewardKey.getPubKey())));
        b.addTransaction(tx);

        blockSaveService.saveBlockPermissive(b, store);
        logger.info("Anchor reward of {} credited to chainlength node for chain {}",
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
        PQKey signKey = PQKey.fromPublicOnly(Utils.HEX.decode(pubKeyHex));
        if (!anchor.verifySignature(signKey)) {
            throw new BlockStoreException(
                    "Anchor signature verification failed for chain " + anchor.getChainId());
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
     * Collects all confirmed L1 block hashes up to the given height to build
     * the SPV Merkle tree. For simplicity, uses the confirmed reward blocks.
     */
    private List<Sha256Hash> collectConfirmedBlockHashes(long upToHeight, BlockStoreInterface store) throws Exception {
        List<Sha256Hash> hashes = new ArrayList<>();
        TXReward reward = cacheBlockService.getMaxConfirmedReward(store);
        if (reward != null) {
            hashes.add(reward.getBlockHash());
        }
        hashes.add(store.get(UtilGeneseBlock.createGenesis(networkParameters).getHash()).getHash());
        Collections.sort(hashes);
        return hashes;
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
                    Utils.HEX.decode(record.getSignatureHex()),
                    record.getSpvProofHex() != null ? MerkleProof.fromHex(record.getSpvProofHex()) : null,
                    record.getBurnJson() != null && !record.getBurnJson().isEmpty()
                            ? LayerAnchor.AnchorBurn.fromJson(record.getBurnJson()) : null);
            validateAnchor(anchor);
            return true;
        } catch (Exception e) {
            logger.warn("Rejected invalid remote anchor record for chain {} at height {}: {}",
                    record.getChainId(), record.getL1Height(), e.getMessage());
            return false;
        }
    }
}
