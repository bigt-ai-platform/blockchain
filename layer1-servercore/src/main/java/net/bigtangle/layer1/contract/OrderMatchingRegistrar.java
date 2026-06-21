package net.bigtangle.layer1.contract;

import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import net.bigtangle.server.service.base.handler.OrderExecutorRegistry;

/**
 * Registers the Layer-1 {@link OrderMatchingEngine} with the
 * {@link OrderExecutorRegistry} so {@code bigtangle-servercore} can invoke
 * order matching without a compile-time dependency on this module.
 */
@Component
public class OrderMatchingRegistrar {

	@PostConstruct
	public void register() {
		OrderExecutorRegistry.register(new OrderMatchingEngine());
	}
}
