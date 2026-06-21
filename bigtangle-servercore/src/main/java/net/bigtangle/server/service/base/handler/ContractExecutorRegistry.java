package net.bigtangle.server.service.base.handler;

import java.util.Optional;

/**
 * Static holder for the {@link ContractExecutor} registered by the Layer-1
 * module. {@code bigtangle-servercore} looks the executor up here (in
 * {@code ServiceBaseConnect.connectContractExecute} and
 * {@code ServiceBaseConfirmation.confirmContractExecute}) instead of calling
 * {@code new ServiceContract(...)} directly, which is what removes the compile-
 * time dependency on the contract implementation.
 *
 * <p>Registration is a one-time, process-wide set (the Layer-1 module registers
 * its {@code ContractEngine} at startup, e.g. via a Spring {@code @Bean} or in a
 * static initializer). A Layer-0-only node simply leaves it unregistered; the
 * connect/confirm sites then skip contract execution (the same behaviour as if
 * no contract block was present).
 */
public final class ContractExecutorRegistry {

	private static volatile ContractExecutor executor;

	/** Register the process-wide contract executor. */
	public static void register(ContractExecutor e) {
		executor = e;
	}

	/** The registered executor, if any. */
	public static Optional<ContractExecutor> get() {
		return Optional.ofNullable(executor);
	}

	private ContractExecutorRegistry() {
	}
}
