package net.bigtangle.server.service.base.handler;

import java.util.Optional;

/**
 * Static holder for the {@link OrderExecutor} registered by the Layer-1
 * module. {@code bigtangle-servercore} looks the executor up here
 * (execution chain removed) instead of calling
 * {@code new ServiceOrderExecution(...)} directly.
 */
public final class OrderExecutorRegistry {

	private static volatile OrderExecutor executor;

	/** Register the process-wide order executor. */
	public static void register(OrderExecutor e) {
		executor = e;
	}

	/** The registered executor, if any. */
	public static Optional<OrderExecutor> get() {
		return Optional.ofNullable(executor);
	}

	private OrderExecutorRegistry() {
	}
}
