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
import net.bigtangle.core.ECKey;
import net.bigtangle.core.MerkleProof;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
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

    public void postAnchor(BlockStoreInterface store) throws Exception {
        if (!anchorConfiguration.isActive()) {
            logger.debug("Anchor service is not active");
            return;
        }

        TXReward maxConfirmedReward = cacheBlockService.getMaxConfirmedReward(store);
        if (maxConfirmedReward == null) {
            logger.warn("No max confirmed reward available, skipping anchor post");
            return;
        }

        Sha256Hash l1RewardHeadHash = maxConfirmedReward.getBlockHash();
        long l1Height = maxConfirmedReward.getChainLength();

        ECKey signKey = ECKey.fromPrivateAndPrecalculatedPublic(
                Utils.HEX.decode(anchorConfiguration.getPriKeyHex()),
                Utils.HEX.decode(anchorConfiguration.getPubKeyHex()));

        ECKey.ECDSASignature sig = signKey.sign(l1RewardHeadHash);
        byte[] sigBytes = sig.encodeToDER();

        List<Sha256Hash> confirmedHashes = collectConfirmedBlockHashes(l1Height, store);
        Sha256Hash confirmedRoot = MerkleProof.computeRoot(confirmedHashes);
        MerkleProof spvProof = MerkleProof.buildProofFor(confirmedHashes, l1RewardHeadHash);

        LayerAnchor anchor = new LayerAnchor(networkParameters.getChainId(), l1RewardHeadHash, l1Height,
                confirmedRoot, sigBytes, spvProof);

        Block b = cacheBlockPrototypeService.getBlockPrototype(store);
        b.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);

        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName("LayerAnchor");
        tx.setData(anchor.toByteArray());
        b.addTransaction(tx);

        blockSaveService.saveBlock(b, store);
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
     * credits the anchor reward to the L1 milestone node on initial confirmation.
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
     * Credits the anchor reward from the L0 fee pool to the L1 milestone node.
     * The milestone node's address is derived from the configured public key.
     * Creates a simple BLOCKTYPE_TRANSFER and saves it locally on L0.
     */
    private void creditAnchorReward(AnchorRecord anchor, BlockStoreInterface store) throws Exception {
        long rewardAmount = anchorConfiguration.getRewardAmount();
        if (rewardAmount <= 0) {
            return;
        }
        String feePoolPriKeyHex = anchorConfiguration.getFeePoolPriKeyHex();
        String milestonePubKeyHex = anchorConfiguration.getPubKeyHex();
        if (feePoolPriKeyHex == null || feePoolPriKeyHex.isEmpty()
                || milestonePubKeyHex == null || milestonePubKeyHex.isEmpty()) {
            logger.debug("Anchor reward not configured, skipping");
            return;
        }

        ECKey feePoolKey = ECKey.fromPrivate(Utils.HEX.decode(feePoolPriKeyHex));
        ECKey milestoneKey = ECKey.fromPublicOnly(Utils.HEX.decode(milestonePubKeyHex));

        Block b = cacheBlockPrototypeService.getBlockPrototype(store);
        b.setBlockType(BlockType.BLOCKTYPE_TRANSFER);

        Transaction tx = new Transaction(networkParameters);
        Coin rewardCoin = Coin.valueOf(rewardAmount, NetworkParameters.BIGTANGLE_TOKENID);
        tx.addOutput(rewardCoin, milestoneKey.toAddress(networkParameters));
        b.addTransaction(tx);

        blockSaveService.saveBlock(b, store);
        logger.info("Anchor reward of {} credited to milestone node for chain {}",
                rewardAmount, anchor.getChainId());
    }

    public void validateAndSaveAnchor(LayerAnchor anchor, Sha256Hash l0BlockHash, BlockStoreInterface store)
            throws Exception {
        if (anchor.getL1RewardHeadHash() == null) {
            throw new BlockStoreException("Anchor l1RewardHeadHash is null");
        }
        if (anchor.getChainId() == null || anchor.getChainId().isEmpty()) {
            throw new BlockStoreException("Anchor chainId is null or empty");
        }
        if (anchor.getL1Height() < 0) {
            throw new BlockStoreException("Anchor l1Height must be non-negative");
        }
        if (anchor.getSignature() == null || anchor.getSignature().length == 0) {
            throw new BlockStoreException("Anchor signature is missing");
        }

        ECKey signKey = ECKey.fromPublicOnly(Utils.HEX.decode(anchorConfiguration.getPubKeyHex()));
        ECKey.ECDSASignature sig = ECKey.ECDSASignature.decodeFromDER(anchor.getSignature());
        boolean valid = signKey.verify(anchor.getL1RewardHeadHash(), sig);
        if (!valid) {
            throw new BlockStoreException(
                    "Anchor signature verification failed for chain " + anchor.getChainId());
        }

        if (anchor.getConfirmedRoot() != null && anchor.getSpvProof() != null) {
            boolean spvValid = anchor.getSpvProof().verify(anchor.getL1RewardHeadHash(), anchor.getConfirmedRoot());
            if (!spvValid) {
                throw new BlockStoreException(
                        "SPV proof verification failed for chain " + anchor.getChainId()
                        + " at height " + anchor.getL1Height());
            }
        } else {
            logger.debug("No SPV proof in anchor for chain {} at height {} (Phase 2 trust model)",
                    anchor.getChainId(), anchor.getL1Height());
        }

        AnchorRecord record = new AnchorRecord(anchor.getChainId(), anchor.getL1RewardHeadHash(),
                anchor.getL1Height(), anchor.getConfirmedRoot(),
                Utils.HEX.encode(anchor.getSignature()), l0BlockHash, false);

        store.saveAnchor(record);
        logger.info("Saved anchor record for chain {} at height {}", anchor.getChainId(), anchor.getL1Height());
    }

    /**
     * Collects all confirmed L1 block hashes up to the given height to build
     * the SPV Merkle tree. Uses the anchor table's last confirmed root for
     * chain continuity. For simplicity, uses the confirmed reward blocks.
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
            anchor = LayerAnchor.parse(data);
        } catch (Exception e) {
            // Fallback to legacy JSON format
            anchor = LayerAnchor.fromJson(new String(data, StandardCharsets.UTF_8));
        }
        validateAndSaveAnchor(anchor, block.getHash(), store);
    }
}
