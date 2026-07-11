package net.bigtangle.layer1.server;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import net.bigtangle.layer1.params.Layer1Params;
import net.bigtangle.layer1.params.Layer1TestParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ServerConfiguration;

@Configuration
public class Layer1NetworkConfiguration {

    @Autowired
    ServerConfiguration serverConfiguration;

    @Autowired
    private Environment env;

    @Bean
    @Primary
    public NetworkParameters networkParameters() {
        String chainId = env.getProperty("layer1.chainId", "L1");
        if ("Test".equals(serverConfiguration.getNet())) {
            return new Layer1TestParams(chainId);
        }
        return new Layer1Params(chainId);
    }
}
