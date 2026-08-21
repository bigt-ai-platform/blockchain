package net.bigtangle.layer1.handler;

import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.service.base.handler.BlockTypeHandler;
import net.bigtangle.server.service.base.handler.SolidityContext;
import net.bigtangle.server.service.base.ServiceBaseConfirmation;

public class TransferHandler implements BlockTypeHandler {

    @Override
    public void confirm(SolidityContext ctx) throws BlockStoreException {
        ServiceBaseConfirmation.queueBlockEvaluation(ctx.blockHash(), ctx.chainlength(), ctx.confirmation(),
                ctx.store());
    }
}
