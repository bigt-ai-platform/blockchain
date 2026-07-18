package net.bigtangle.layer1.handler;

import jakarta.annotation.PostConstruct;

import org.springframework.context.annotation.Configuration;

import net.bigtangle.core.BlockType;
import net.bigtangle.server.service.base.ServiceBase;

@Configuration
public class PaiHandlerConfiguration {

    @PostConstruct
    public void registerHandlers() {
        ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_CONTRACT_EVENT, PaiStakeHandler::new);
        ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_CONTRACTEVENT_CANCEL, PaiCancelHandler::new);
    }
}
