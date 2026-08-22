package net.bigtangle.l1.social;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import net.bigtangle.layer1.params.SocialL1Params;
import net.bigtangle.layer1.params.SocialL1TestParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ServerConfiguration;

@Configuration
public class SocialL1NetworkConfiguration {

    @Autowired
    ServerConfiguration serverConfiguration;

    @Value("${CHAIN_ID:SOCIAL}")
    private String chainId;

    @Bean
    @Primary
    public NetworkParameters networkParameters() {
        if ("Test".equals(serverConfiguration.getNet())) {
            return new SocialL1TestParams(chainId);
        }
        return new SocialL1Params(chainId);
    }
}
