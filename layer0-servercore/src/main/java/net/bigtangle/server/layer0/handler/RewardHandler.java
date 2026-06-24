package net.bigtangle.server.layer0.handler;

import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.service.base.ServiceBaseCheck;
import net.bigtangle.server.service.base.ServiceBaseConfirmation;
import net.bigtangle.server.service.base.handler.BlockTypeHandler;
import net.bigtangle.server.service.base.handler.SolidityContext;

public class RewardHandler implements BlockTypeHandler {

	@Override
	public SolidityState checkFull(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseCheck base = (ServiceBaseCheck) ctx.base();
		return base.checkFullRewardSolidity(ctx.block(), null, null, ctx.height(),
				ctx.throwExceptions(), ctx.store());
	}

	@Override
	public SolidityState checkFormal(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseCheck base = (ServiceBaseCheck) ctx.base();
		return base.checkFormalRewardSolidity(ctx.block(), ctx.throwExceptions());
	}

	@Override
	public void confirm(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseConfirmation base = (ServiceBaseConfirmation) ctx.base();
		BlockWrap w = ctx.base().getBlockWrap(ctx.blockHash(), ctx.store());
		if (w != null) {
			base.confirmReward(w, ctx.confirmation(), ctx.store());
			if (!ctx.base().enableOrderMatchExecutionChain(ctx.block())) {
				base.confirmOrderMatching(w, ctx.confirmation(), ctx.store());
			}
		}
		ctx.store().updateBlockEvaluationConfirmed(ctx.blockHash(), ctx.confirmation());
		ctx.store().updateBlockEvaluationMilestone(ctx.blockHash(), ctx.milestoneNumber());
	}
}
