package net.bigtangle.l1.ordermatch;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import net.bigtangle.layer0.params.Layer0TestParams;
import net.bigtangle.layer1.params.OrderMatchL1Params;
import net.bigtangle.layer1.params.OrderMatchL1TestParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ServerConfiguration;

@Configuration
public class OrderMatchL1NetworkConfiguration {

    @Autowired
    ServerConfiguration serverConfiguration;

    @Bean
    @Primary
    public NetworkParameters networkParameters() {
        // Remote integration tests run the L1 order server on the shared
        // Layer-0 chain so order blocks created on L0 are visible to it.
        // Override server.chain=L0 to opt into this mode.
        if ("L0".equals(serverConfiguration.getChain())) {
            return new Layer0TestParams();
        }
        if ("Test".equals(serverConfiguration.getNet())) {
            return new OrderMatchL1TestParams();
        }
        return new OrderMatchL1Params();
    }
}
