package net.bigtangle.bridge;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Transaction;
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

    @Autowired
    private ObjectMapper jsonmapper;

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

        LayerAnchor anchor = new LayerAnchor(networkParameters.getChainId(), l1RewardHeadHash, l1Height, null,
                sigBytes);

        Block b = cacheBlockPrototypeService.getBlockPrototype(store);
        b.setBlockType(BlockType.BLOCKTYPE_CROSSTANGLE);

        Transaction tx = new Transaction(networkParameters);
        tx.setDataClassName("LayerAnchor");
        tx.setData(anchor.toJson().getBytes(StandardCharsets.UTF_8));
        b.addTransaction(tx);
        b.solve();

        blockSaveService.saveBlock(b, store);
        logger.info("Anchor block saved locally: {} for chain {} at height {}", b.getHashAsString(),
                anchor.getChainId(), l1Height);

        String l0Url = anchorConfiguration.getL0Url();
        if (l0Url != null && !l0Url.isEmpty()) {
            try {
                OkHttp3Util.post(l0Url + "/" + ReqCmd.saveBlock.name(), b.bitcoinSerialize());
                logger.info("Anchor block posted to L0: {}", b.getHashAsString());
            } catch (Exception e) {
                logger.warn("Failed to post anchor to L0 at {}: {}", l0Url, e.getMessage());
            }
        }
    }

    /**
     * Called after an anchor's CROSSTANGLE block is confirmed on L0.
     * Marks the anchor record as confirmed and credits the anchor reward
     * to the L1 milestone node (if configured).
     */
    public void confirmAnchor(Block block, BlockStoreInterface store) throws Exception {
        if (block.getBlockType() != BlockType.BLOCKTYPE_CROSSTANGLE) {
            return;
        }
        AnchorRecord anchor = store.getAnchorByBlockHash(block.getHash());
        if (anchor == null) {
            logger.warn("No anchor record found for confirmed CROSSTANGLE block {}", block.getHashAsString());
            return;
        }
        if (anchor.isConfirmed()) {
            return;
        }
        store.updateAnchorConfirmed(anchor.getChainId(), anchor.getL1Height(), true);
        logger.info("Anchor confirmed for chain {} at height {}", anchor.getChainId(), anchor.getL1Height());
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

        AnchorRecord record = new AnchorRecord(anchor.getChainId(), anchor.getL1RewardHeadHash(),
                anchor.getL1Height(), anchor.getConfirmedRoot(),
                Utils.HEX.encode(anchor.getSignature()), l0BlockHash, false);

        store.saveAnchor(record);
        logger.info("Saved anchor record for chain {} at height {}", anchor.getChainId(), anchor.getL1Height());
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

        LayerAnchor anchor = LayerAnchor.fromJson(new String(data, StandardCharsets.UTF_8));
        validateAndSaveAnchor(anchor, block.getHash(), store);
    }
}
