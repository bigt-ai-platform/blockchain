package net.bigtangle.layer1.handler;

import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.service.base.ServiceBaseCheck;
import net.bigtangle.server.service.base.ServiceBaseConfirmation;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.server.service.base.handler.BlockTypeHandler;
import net.bigtangle.server.service.base.handler.SolidityContext;

public class OrderOpenHandler implements BlockTypeHandler {

	@Override
	public SolidityState checkFull(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseCheck base = (ServiceBaseCheck) ctx.base();
		return base.checkFullOrderOpenSolidity(ctx.block(), ctx.height(),
				ctx.throwExceptions(), ctx.store());
	}

	@Override
	public SolidityState checkFormal(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseCheck base = (ServiceBaseCheck) ctx.base();
		return base.checkFormalOrderOpenSolidity(ctx.block(), ctx.throwExceptions());
	}

	@Override
	public void confirm(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseConfirmation base = (ServiceBaseConfirmation) ctx.base();
		ctx.store().updateBlockEvaluationConfirmed(ctx.blockHash(), ctx.confirmation());
		ctx.store().updateBlockEvaluationMilestone(ctx.blockHash(), ctx.milestoneNumber());
		ctx.store().updateOrderBlockhash(ctx.blockHash(),
				net.bigtangle.core.Sha256Hash.ZERO_HASH, ctx.confirmation(), false, null);
	}

	@Override
	public void connect(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseConnect base = (ServiceBaseConnect) ctx.base();
		base.connectOrder(ctx.block(), ctx.store());
	}
}
