package net.bigtangle.server.layer0.handler;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import net.bigtangle.bridge.AnchorService;
import net.bigtangle.core.BlockType;
import net.bigtangle.server.service.base.ServiceBase;

/**
 * Registers all Layer-0 {@link net.bigtangle.server.service.base.handler.BlockTypeHandler}
 * implementations globally so every {@code ServiceBase} instance created at
 * runtime includes them via {@link ServiceBase#registerGlobalHandler}.
 */
@Configuration
public class Layer0HandlerConfiguration {

	@Autowired
	private AnchorService anchorService;

	@PostConstruct
	public void registerHandlers() {
		ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_TOKEN_CREATION, TokenCreationHandler::new);
		ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_BEACON, RewardHandler::new);
		ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_TRANSFER, NoOpConfirmHandler::new);
		ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_CROSSTANGLE, () -> new L0AnchorHandler(anchorService));
		ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_GOVERNANCE, NoOpConfirmHandler::new);
		ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_INITIAL, NoOpConfirmHandler::new);
	}
}
