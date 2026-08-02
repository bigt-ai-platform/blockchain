package net.bigtangle.server.service.base.handler;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static holder for the {@link ContractExecutor}s registered by Layer-1
 * modules. {@code bigtangle-servercore} looks the executor up here (in the
 * inline BEACON confirmation path) instead of calling
 * {@code new ServiceContract(...)} directly, which is what removes the compile-
 * time dependency on the contract implementation.
 *
 * <p>An executor can be registered for a specific contract classname (the
 * {@code classname} token key-value set at contract deployment) and/or as the
 * default executor. {@link #get(String)} prefers the classname match and falls
 * back to the default.
 *
 * <p>Registration is process-wide (the Layer-1 modules register their engines
 * at startup, e.g. via Spring {@code @Bean}s or {@code @PostConstruct}).
 */
public final class ContractExecutorRegistry {

	private static final Map<String, ContractExecutor> executors = new ConcurrentHashMap<>();
	private static volatile ContractExecutor defaultExecutor;

	/** Register the default (fallback) contract executor. */
	public static void register(ContractExecutor e) {
		defaultExecutor = e;
	}

	/** Register an executor for a specific contract classname. */
	public static void register(String className, ContractExecutor e) {
		executors.put(className, e);
	}

	/** The executor registered for the classname, if any. */
	public static Optional<ContractExecutor> get(String className) {
		ContractExecutor e = className == null ? null : executors.get(className);
		return Optional.ofNullable(e != null ? e : defaultExecutor);
	}

	/** The default executor, if any. */
	public static Optional<ContractExecutor> get() {
		return Optional.ofNullable(defaultExecutor);
	}

	private ContractExecutorRegistry() {
	}
}
