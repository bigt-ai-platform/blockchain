package net.bigtangle.server.layer0.handler;

import jakarta.annotation.PostConstruct;

import org.springframework.context.annotation.Configuration;

import net.bigtangle.core.BlockType;
import net.bigtangle.server.service.base.ServiceBase;

/**
 * Registers all Layer-0 {@link net.bigtangle.server.service.base.handler.BlockTypeHandler}
 * implementations globally so every {@code ServiceBase} instance created at
 * runtime includes them via {@link ServiceBase#registerGlobalHandler}.
 */
@Configuration
public class Layer0HandlerConfiguration {

	@PostConstruct
	public void registerHandlers() {
		ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_TOKEN_CREATION, TokenCreationHandler::new);
		ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_REWARD, RewardHandler::new);
		ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_USERDATA, UserDataHandler::new);
		ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_TRANSFER, NoOpConfirmHandler::new);
		ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_CROSSTANGLE, NoOpConfirmHandler::new);
		ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_FILE, NoOpConfirmHandler::new);
		ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_GOVERNANCE, NoOpConfirmHandler::new);
		ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_INITIAL, NoOpConfirmHandler::new);
	}
}
