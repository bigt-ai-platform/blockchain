package net.bigtangle.server.layer1.handler;

import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.service.base.ServiceBaseConfirmation;
import net.bigtangle.server.service.base.handler.BlockTypeHandler;
import net.bigtangle.server.service.base.handler.SolidityContext;

public class UserDataHandler implements BlockTypeHandler {

	@Override
	public void confirm(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseConfirmation base = (ServiceBaseConfirmation) ctx.base();
		BlockWrap w = ctx.base().getBlockWrap(ctx.blockHash(), ctx.store());
		if (w != null) {
			base.confirmVOSOrUserData(w, ctx.confirmation(), ctx.store());
		}
		ctx.store().updateBlockEvaluationConfirmed(ctx.blockHash(), ctx.confirmation());
		ctx.store().updateBlockEvaluationChainlength(ctx.blockHash(), ctx.chainlength());
	}
}
