package net.bigtangle.layer1.handler;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import net.bigtangle.bridge.BridgeConfiguration;
import net.bigtangle.bridge.L1CrosstangleHandler;
import net.bigtangle.core.BlockType;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.service.base.ServiceBase;

@Configuration
public class PaiHandlerConfiguration {

    @Autowired
    private BridgeConfiguration bridgeConfiguration;

    @Autowired
    private NetworkParameters networkParameters;

    @PostConstruct
    public void registerHandlers() {
        ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_CONTRACT_EVENT, PaiStakeHandler::new);
        ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_CONTRACT_EVENT, PaiRewardHandler::new);
        ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_CONTRACT_EVENT, PaiReputationHandler::new);
        ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_CONTRACTEVENT_CANCEL, PaiCancelHandler::new);
        // CROSSTANGLE is in the L1 allowed-type set, so without a real handler
        // the legacy no-op case would accept unauthenticated zero-input mints.
        ServiceBase.registerGlobalHandler(BlockType.BLOCKTYPE_CROSSTANGLE,
                () -> new L1CrosstangleHandler(bridgeConfiguration, networkParameters));
    }
}
