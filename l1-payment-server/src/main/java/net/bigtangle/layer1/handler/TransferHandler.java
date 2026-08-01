package net.bigtangle.layer1.handler;

import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.service.base.handler.BlockTypeHandler;
import net.bigtangle.server.service.base.handler.SolidityContext;

public class TransferHandler implements BlockTypeHandler {

    @Override
    public void confirm(SolidityContext ctx) throws BlockStoreException {
        ctx.store().updateBlockEvaluationConfirmed(ctx.blockHash(), ctx.confirmation());
        ctx.store().updateBlockEvaluationChainlength(ctx.blockHash(), ctx.chainlength());
    }
}
