package net.bigtangle.layer1.handler;

import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.service.base.ServiceBaseConfirmation;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.server.service.base.handler.BlockTypeHandler;
import net.bigtangle.server.service.base.handler.SolidityContext;

public class ContractExecuteHandler implements BlockTypeHandler {

	@Override
	public void confirm(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseConfirmation base = (ServiceBaseConfirmation) ctx.base();
		if (ctx.confirmation()) {
			base.handleNewBestExecutionChain(ctx.block(), ctx.milestoneNumber(), ctx.store());
		} else {
			base.confirmContractExecute(ctx.block(), ctx.milestoneNumber(), ctx.confirmation(), ctx.store());
		}
	}

	@Override
	public void connect(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseConnect base = (ServiceBaseConnect) ctx.base();
		base.connectContractExecute(ctx.block(), ctx.store());
	}
}
