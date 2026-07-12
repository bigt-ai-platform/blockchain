package net.bigtangle.l1.contract;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import net.bigtangle.layer1.params.ContractL1Params;
import net.bigtangle.layer1.params.ContractL1TestParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ServerConfiguration;

@Configuration
public class ContractL1NetworkConfiguration {

    @Autowired
    ServerConfiguration serverConfiguration;

    @Bean
    @Primary
    public NetworkParameters networkParameters() {
        if ("Test".equals(serverConfiguration.getNet())) {
            return new ContractL1TestParams();
        }
        return new ContractL1Params();
    }
}
