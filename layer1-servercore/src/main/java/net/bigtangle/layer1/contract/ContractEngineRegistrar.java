package net.bigtangle.layer1.contract;

import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import net.bigtangle.server.service.base.handler.ContractExecutorRegistry;

/**
 * Registers the Layer-1 {@link ContractEngine} with the
 * {@link ContractExecutorRegistry} so {@code bigtangle-servercore} can invoke
 * contract execution without a compile-time dependency on this module. Picked
 * up automatically by {@code ServerStart}'s {@code @ComponentScan("net.bigtangle")}.
 */
@Component
public class ContractEngineRegistrar {

	@PostConstruct
	public void register() {
		ContractExecutorRegistry.register(new ContractEngine());
	}
}
