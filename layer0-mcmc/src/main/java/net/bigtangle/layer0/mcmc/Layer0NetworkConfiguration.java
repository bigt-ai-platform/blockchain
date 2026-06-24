package net.bigtangle.layer0.mcmc;

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
        return new Layer0Params();
    }
}
