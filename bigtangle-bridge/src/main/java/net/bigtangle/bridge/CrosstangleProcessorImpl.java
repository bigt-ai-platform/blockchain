package net.bigtangle.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.server.service.CrosstangleProcessor;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Bridge-side handler for CROSSTANGLE blocks. On save a layer anchor is
 * validated and recorded; it is NOT treated as final. The peg-out for an
 * embedded burn is settled only when the block actually CONFIRMS on the chain
 * (see L0AnchorHandler.confirm); an unconfirm (reorg) mirrors back to the
 * anchor record.
 */
@Component
public class CrosstangleProcessorImpl implements CrosstangleProcessor {

    private static final Logger logger = LoggerFactory.getLogger(CrosstangleProcessorImpl.class);

    @Autowired
    private AnchorService anchorService;

    @Override
    public void onCrosstangleBlockSaved(Block block, BlockStoreInterface store) {
        if (block.getBlockType() != BlockType.BLOCKTYPE_CROSSTANGLE) {
            return;
        }
        try {
            // Validate + record the anchor, but do NOT treat it as final here:
            // a block "landed in the local DB" is not yet confirmed, and a reorg
            // can still flip it. The peg-out is settled only on actual chain
            // confirmation (L0AnchorHandler.confirm), never at save time.
            anchorService.processReceivedAnchor(block, store);
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
