package net.bigtangle.server.layer0.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.bridge.AnchorService;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.service.base.handler.BlockTypeHandler;
import net.bigtangle.server.service.base.handler.SolidityContext;

public class L0AnchorHandler implements BlockTypeHandler {

    private static final Logger logger = LoggerFactory.getLogger(L0AnchorHandler.class);

    private AnchorService anchorService;

    public L0AnchorHandler(AnchorService anchorService) {
        this.anchorService = anchorService;
    }

    @Override
    public SolidityState checkFull(SolidityContext ctx) {
        return SolidityState.getSuccessState();
    }

    @Override
    public SolidityState checkFormal(SolidityContext ctx) {
        return SolidityState.getSuccessState();
    }

    @Override
    public void confirm(SolidityContext ctx) throws BlockStoreException {
        ctx.store().updateBlockEvaluationConfirmed(ctx.blockHash(), ctx.confirmation());
        ctx.store().updateBlockEvaluationMilestone(ctx.blockHash(), ctx.milestoneNumber());
        try {
            anchorService.processReceivedAnchor(ctx.block(), ctx.store());
        } catch (BlockStoreException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to process anchor at confirmation", e);
        }
    }

    @Override
    public void connect(SolidityContext ctx) {
    }
}
