package net.bigtangle.layer1.handler;

import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.server.service.base.handler.BlockTypeHandler;
import net.bigtangle.server.service.base.handler.SolidityContext;

public class ContractEventCancelHandler implements BlockTypeHandler {

	@Override
	public void confirm(SolidityContext ctx) throws BlockStoreException {
		ctx.store().updateBlockEvaluationConfirmed(ctx.blockHash(), ctx.confirmation());
		ctx.store().updateBlockEvaluationMilestone(ctx.blockHash(), ctx.milestoneNumber());
	}

	@Override
	public void connect(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseConnect base = (ServiceBaseConnect) ctx.base();
		base.connectContractEventCancel(ctx.block(), ctx.store());
	}
}
