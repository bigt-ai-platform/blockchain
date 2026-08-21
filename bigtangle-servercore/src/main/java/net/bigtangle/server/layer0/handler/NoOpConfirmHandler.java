package net.bigtangle.server.layer0.handler;

import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.service.base.ServiceBaseConfirmation;
import net.bigtangle.server.service.base.handler.BlockTypeHandler;
import net.bigtangle.server.service.base.handler.SolidityContext;

/**
 * Handler for block types that only need
 * {@code updateBlockConfirmOnly} + transaction confirmation
 * (already done by the caller). This covers:
 * BLOCKTYPE_CROSSTANGLE, BLOCKTYPE_FILE, BLOCKTYPE_GOVERNANCE,
 * BLOCKTYPE_INITIAL, BLOCKTYPE_TRANSFER.
 */
public class NoOpConfirmHandler implements BlockTypeHandler {

	@Override
	public void confirm(SolidityContext ctx) throws BlockStoreException {
		// Batched with the rest of the confirm cycle's block-evaluation writes.
		ServiceBaseConfirmation.queueBlockEvaluation(ctx.blockHash(), ctx.chainlength(), ctx.confirmation(),
				ctx.store());
	}
}
