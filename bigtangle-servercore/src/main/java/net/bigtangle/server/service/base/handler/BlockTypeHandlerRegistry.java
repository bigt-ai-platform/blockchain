package net.bigtangle.server.service.base.handler;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import net.bigtangle.core.BlockType;

/**
 * Holds the {@link BlockTypeHandler} registered per {@link BlockType}.
 *
 * <p>Layer modules register their handlers here (e.g. layer0 registers a
 * {@code RewardHandler} for {@code BLOCKTYPE_BEACON}); the base
 * {@code ServiceBaseCheck}/{@code ServiceBaseConfirmation} switches consult it
 * and delegate when a handler is present, otherwise fall back to the existing
 * in-class behaviour. This keeps the extraction incremental: a handler can be
 * moved out of the base class one {@code BlockType} at a time without changing
 * behaviour.
 */
public final class BlockTypeHandlerRegistry {

	private final Map<BlockType, BlockTypeHandler> handlers = new EnumMap<>(BlockType.class);

	/** Register (or replace) the handler for a block type. */
	public void register(BlockType type, BlockTypeHandler handler) {
		handlers.put(type, handler);
	}

	/** The handler for the type, if any has been registered. */
	public Optional<BlockTypeHandler> get(BlockType type) {
		return Optional.ofNullable(handlers.get(type));
	}

	/** True if a handler is registered for the type. */
	public boolean has(BlockType type) {
		return handlers.containsKey(type);
	}
}
