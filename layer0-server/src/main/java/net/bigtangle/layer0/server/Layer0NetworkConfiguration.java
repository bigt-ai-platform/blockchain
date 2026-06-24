package net.bigtangle.layer0.server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import net.bigtangle.layer0.params.Layer0Params;
import net.bigtangle.params.NetworkParameters;

@Configuration
public class Layer0NetworkConfiguration {

    @Bean
    @Primary
    public NetworkParameters networkParameters() {
        // TODO: Layer0TestParams when testnet genesis differs
        return new Layer0Params();
    }
}
