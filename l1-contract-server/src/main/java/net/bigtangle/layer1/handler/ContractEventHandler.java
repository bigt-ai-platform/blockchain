package net.bigtangle.layer1.handler;

import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.service.base.ServiceBaseCheck;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.server.service.base.handler.BlockTypeHandler;
import net.bigtangle.server.service.base.handler.SolidityContext;
import net.bigtangle.server.service.base.ServiceBaseConfirmation;

public class ContractEventHandler implements BlockTypeHandler {

	@Override
	public SolidityState checkFull(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseCheck base = (ServiceBaseCheck) ctx.base();
		return base.checkFormalContractEventSolidity(ctx.block(), ctx.throwExceptions(), ctx.store());
	}

	@Override
	public void confirm(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseConfirmation.queueBlockEvaluation(ctx.blockHash(), ctx.chainlength(), ctx.confirmation(),
				ctx.store());
	}

	@Override
	public void connect(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseConnect base = (ServiceBaseConnect) ctx.base();
		base.connectContractEvent(ctx.block(), ctx.store());
	}
}
