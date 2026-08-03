package net.bigtangle.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.server.data.AnchorRecord;
import net.bigtangle.server.service.CrosstangleProcessor;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Bridge-side handler for CROSSTANGLE blocks. On save (local post or batch
 * receive from L1) a layer anchor is validated and recorded; a valid anchor is
 * treated as final (signed + SPV-verified) and any embedded burn is settled by
 * the peg-out. Confirmation/un-confirmation from the core MCMC is mirrored to
 * the anchor record.
 */
@Component
public class CrosstangleProcessorImpl implements CrosstangleProcessor {

    private static final Logger logger = LoggerFactory.getLogger(CrosstangleProcessorImpl.class);

    @Autowired
    private AnchorService anchorService;

    @Autowired
    private BridgeService bridgeService;

    @Override
    public void onCrosstangleBlockSaved(Block block, BlockStoreInterface store) {
        if (block.getBlockType() != BlockType.BLOCKTYPE_CROSSTANGLE) {
            return;
        }
        try {
            anchorService.processReceivedAnchor(block, store);
            anchorService.confirmAnchor(block, true, store);
            AnchorRecord rec = store.getAnchorByBlockHash(block.getHash());
            if (rec != null && rec.isConfirmed()) {
                bridgeService.processPegOut(rec, store);
            }
        } catch (Exception e) {
            logger.warn("CROSSTANGLE block {} rejected by bridge: {}",
                    block.getHashAsString(), e.getMessage());
        }
    }

    @Override
    public void onCrosstangleBlockConfirmed(Block block, boolean confirmed, BlockStoreInterface store) {
        if (block.getBlockType() != BlockType.BLOCKTYPE_CROSSTANGLE) {
            return;
        }
        try {
            anchorService.confirmAnchor(block, confirmed, store);
        } catch (Exception e) {
            logger.warn("Failed to mirror CROSSTANGLE confirmation for block {}: {}",
                    block.getHashAsString(), e.getMessage());
        }
    }
}
