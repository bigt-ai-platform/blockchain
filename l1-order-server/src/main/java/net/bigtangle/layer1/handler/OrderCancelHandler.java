package net.bigtangle.layer1.handler;

import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.service.base.ServiceBaseCheck;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.server.service.base.handler.BlockTypeHandler;
import net.bigtangle.server.service.base.handler.SolidityContext;

public class OrderCancelHandler implements BlockTypeHandler {

	@Override
	public SolidityState checkFull(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseCheck base = (ServiceBaseCheck) ctx.base();
		return base.checkFullOrderOpSolidity(ctx.block(), ctx.height(),
				ctx.throwExceptions(), ctx.store());
	}

	@Override
	public SolidityState checkFormal(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseCheck base = (ServiceBaseCheck) ctx.base();
		return base.checkFormalOrderOpSolidity(ctx.block(), ctx.throwExceptions());
	}

	@Override
	public void confirm(SolidityContext ctx) throws BlockStoreException {
		ctx.store().updateBlockEvaluationConfirmed(ctx.blockHash(), ctx.confirmation());
		ctx.store().updateBlockEvaluationChainlength(ctx.blockHash(), ctx.chainlength());
	}

	@Override
	public void connect(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseConnect base = (ServiceBaseConnect) ctx.base();
		base.connectCancelOrder(ctx.block(), ctx.store());
	}
}
