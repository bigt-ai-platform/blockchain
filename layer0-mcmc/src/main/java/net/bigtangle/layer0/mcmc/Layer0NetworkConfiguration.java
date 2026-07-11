package net.bigtangle.layer0.mcmc;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import net.bigtangle.layer0.params.Layer0Params;
import net.bigtangle.layer0.params.Layer0TestParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ServerConfiguration;

@Configuration
public class Layer0NetworkConfiguration {

    @Bean
    @Primary
    public NetworkParameters networkParameters(ServerConfiguration serverConfiguration) {
        if ("Test".equals(serverConfiguration.getNet())) {
            return new Layer0TestParams();
        }
        return new Layer0Params();
    }
}
