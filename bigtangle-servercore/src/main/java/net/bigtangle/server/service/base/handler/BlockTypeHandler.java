package net.bigtangle.server.service.base.handler;

import net.bigtangle.core.Block;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Strategy for the per-{@link net.bigtangle.core.BlockType} validation and
 * confirmation logic that used to live inside the three big
 * {@code switch (BlockType)} statements of {@code ServiceBaseCheck} /
 * {@code ServiceBaseConfirmation}.
 *
 * <p>This is the seam that lets Layer 0 and Layer 1 live in separate modules:
 * the base servercore class keeps only the generic transactional solidity and
 * the dispatch switch; each layer module ships concrete handlers
 * ({@code TokenCreationHandler}, {@code OrderOpenHandler}, ...) and registers
 * them for the {@code BlockType}s it supports. See {@code LAYERING-PLAN.md}.
 *
 * <p>Every method receives a {@link SolidityContext} so a handler never needs
 * to extend the base class or touch its private state directly.
 */
public interface BlockTypeHandler {

	/**
	 * Deep solidity check including DAG dependencies (mirrors
	 * {@code ServiceBaseCheck.checkFullTypeSpecificSolidity}). Return
	 * {@link SolidityState#getSuccessState()} if there is nothing type-specific
	 * to check for this handler.
	 */
	default SolidityState checkFull(SolidityContext ctx) throws BlockStoreException {
		return SolidityState.getSuccessState();
	}

	/**
	 * Cheap self-contained check (mirrors
	 * {@code ServiceBaseCheck.checkFormalTypeSpecificSolidity}).
	 */
	default SolidityState checkFormal(SolidityContext ctx) throws BlockStoreException {
		return SolidityState.getSuccessState();
	}

	/**
	 * Pre-confirm veto: called for a block about to be CONFIRMED, BEFORE any of
	 * its outputs are marked confirmed. A handler returns false to reject the
	 * block at confirmation (it is marked invalid and excluded from future
	 * confirmation passes). This is the hook for consensus-level, chain-derived
	 * guards that must NOT run in {@link #checkFull} (where a node's local view
	 * can legitimately lag the confirmed chain).
	 */
	default boolean checkPreConfirm(SolidityContext ctx) throws BlockStoreException {
		return true;
	}

	/**
	 * Confirmation hook (mirrors
	 * {@code ServiceBaseConfirmation.confirmBlockTransactionWithType}). Called
	 * on both confirm and unconfirm passes ({@code ctx.confirmation}).
	 */
	default void confirm(SolidityContext ctx) throws BlockStoreException {
		// no-op by default
	}

	/**
	 * Connect hook (mirrors
	 * {@code ServiceBaseConnect.connectTypeSpecificUTXOs}). Called when a block
	 * is connected to the store to insert type-specific state (tokens, orders,
	 * contract events, etc.).
	 */
	default void connect(SolidityContext ctx) throws BlockStoreException {
		// no-op by default
	}
}
