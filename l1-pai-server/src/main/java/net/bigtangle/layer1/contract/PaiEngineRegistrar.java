package net.bigtangle.layer1.contract;

import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import net.bigtangle.server.service.base.handler.ContractExecutorRegistry;

@Component
public class PaiEngineRegistrar {

    @PostConstruct
    public void register() {
        ContractExecutorRegistry.register(new PaiEngine());
    }
}
