package net.bigtangle.layer1.handler;

import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.server.service.base.handler.BlockTypeHandler;
import net.bigtangle.server.service.base.handler.SolidityContext;
import net.bigtangle.server.service.base.ServiceBaseConfirmation;

public class ContractEventCancelHandler implements BlockTypeHandler {

	@Override
	public void confirm(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseConfirmation.queueBlockEvaluation(ctx.blockHash(), ctx.chainlength(), ctx.confirmation(),
				ctx.store());
	}

	@Override
	public void connect(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseConnect base = (ServiceBaseConnect) ctx.base();
		base.connectContractEventCancel(ctx.block(), ctx.store());
	}
}
