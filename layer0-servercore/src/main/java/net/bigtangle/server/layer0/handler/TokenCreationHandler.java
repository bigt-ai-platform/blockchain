package net.bigtangle.server.layer0.handler;

import net.bigtangle.core.BlockType;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.service.base.ServiceBaseCheck;
import net.bigtangle.server.service.base.handler.BlockTypeHandler;
import net.bigtangle.server.service.base.handler.SolidityContext;

/**
 * Layer 0 handler for {@link BlockType#BLOCKTYPE_TOKEN_CREATION} - token
 * issuance validation.
 *
 * <p><b>Template status:</b> this first handler is intentionally a thin
 * delegate to the existing {@code checkFullTokenSolidity} /
 * {@code checkFormalTokenSolidity} methods that still live on
 * {@link ServiceBaseCheck}. It proves the {@link BlockTypeHandler} seam
 * end-to-end (registration -> dispatch -> execution) with zero behaviour
 * change. The validation bodies migrate fully into this class in a later
 * step; until then the {@code private} helpers they need
 * ({@code checkFormalTokenFields}, {@code checkDomainPermission}) are
 * accessed transitively through the public methods.
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

	// confirm() defaults to no-op: TOKEN_CREATION confirmation (confirmToken)
	// is wired through ServiceBaseConfirmation and migrates here in a later
	// step together with the reward handler.
}
