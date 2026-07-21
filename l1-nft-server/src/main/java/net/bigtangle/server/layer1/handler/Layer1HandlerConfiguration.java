package net.bigtangle.server.layer1.handler;

import jakarta.annotation.PostConstruct;

import org.springframework.context.annotation.Configuration;

import net.bigtangle.core.BlockType;
import net.bigtangle.server.service.base.ServiceBase;

/**
 * Registers all Layer-1 {@link net.bigtangle.server.service.base.handler.BlockTypeHandler}
 * implementations globally so every {@code ServiceBase} instance created at
 * runtime includes them via {@link ServiceBase#registerGlobalHandler}.
 */
@Configuration
public class Layer1HandlerConfiguration {

	@PostConstruct
	public void registerHandlers() {
		ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_USERDATA, UserDataHandler::new);
		ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_NFT, NftHandler::new);
	}
}
