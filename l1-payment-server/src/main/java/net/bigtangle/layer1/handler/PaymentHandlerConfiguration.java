package net.bigtangle.layer1.handler;

import jakarta.annotation.PostConstruct;

import org.springframework.context.annotation.Configuration;

import net.bigtangle.core.BlockType;
import net.bigtangle.server.service.base.ServiceBase;

@Configuration
public class PaymentHandlerConfiguration {

    @PostConstruct
    public void registerHandlers() {
        ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_TRANSFER, TransferHandler::new);
    }
}
