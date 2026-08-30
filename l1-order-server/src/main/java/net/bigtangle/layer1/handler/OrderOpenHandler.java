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
		ServiceBaseConfirmation.queueBlockEvaluation(ctx.blockHash(), ctx.chainlength(), ctx.confirmation(),
				ctx.store());
		// Confirmation only — never reset spent/spenderblockhash. The matcher
		// marks the order's initial (ZERO_HASH) row spent when the first beacon
		// interval supersedes it; clobbering that here resurrects a matched
		// order as open in the order book (getAllOpenOrdersSorted).
		ctx.store().updateOrderConfirmedOnly(ctx.blockHash(), ctx.confirmation());
	}

	@Override
	public void connect(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseConnect base = (ServiceBaseConnect) ctx.base();
		base.connectOrder(ctx.block(), ctx.store());
	}
}
