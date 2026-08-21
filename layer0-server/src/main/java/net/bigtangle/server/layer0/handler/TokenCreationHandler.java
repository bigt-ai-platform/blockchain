package net.bigtangle.server.layer0.handler;

import net.bigtangle.core.BlockType;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.service.base.ServiceBaseCheck;
import net.bigtangle.server.service.base.ServiceBaseConfirmation;
import net.bigtangle.server.service.base.ServiceBaseConnect;
import net.bigtangle.server.service.base.handler.BlockTypeHandler;
import net.bigtangle.server.service.base.handler.SolidityContext;

/**
 * Layer 0 handler for {@link BlockType#BLOCKTYPE_TOKEN_CREATION} - token
 * issuance validation, confirmation and connection.
 *
 * <p>Registration example:
 * <pre>{@code
 * base.handlerRegistry().register(BlockType.BLOCKTYPE_TOKEN_CREATION, new TokenCreationHandler());
 * }</pre>
 */
public class TokenCreationHandler implements BlockTypeHandler {

	@Override
	public SolidityState checkFull(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseCheck base = (ServiceBaseCheck) ctx.base();
		return base.checkFullTokenSolidity(ctx.block(), ctx.height(), ctx.throwExceptions(), ctx.store());
	}

	@Override
	public SolidityState checkFormal(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseCheck base = (ServiceBaseCheck) ctx.base();
		return base.checkFormalTokenSolidity(ctx.block(), ctx.throwExceptions());
	}

	@Override
	public void confirm(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseConfirmation base = (ServiceBaseConfirmation) ctx.base();
		BlockWrap w = ctx.base().getBlockWrap(ctx.blockHash(), ctx.store());
		if (w != null) {
			base.confirmToken(w, ctx.confirmation(), ctx.store());
		}
		ServiceBaseConfirmation.queueBlockEvaluation(ctx.blockHash(), ctx.chainlength(), ctx.confirmation(),
				ctx.store());
	}

	@Override
	public void connect(SolidityContext ctx) throws BlockStoreException {
		ServiceBaseConnect base = (ServiceBaseConnect) ctx.base();
		base.connectToken(ctx.block(), ctx.store());
	}
}
