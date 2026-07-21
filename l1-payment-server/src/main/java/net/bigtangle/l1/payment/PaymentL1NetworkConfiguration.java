package net.bigtangle.l1.payment;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import net.bigtangle.layer1.params.PaymentL1Params;
import net.bigtangle.layer1.params.PaymentL1TestParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ServerConfiguration;

@Configuration
public class PaymentL1NetworkConfiguration {

    @Autowired
    ServerConfiguration serverConfiguration;

    @Value("${CHAIN_ID:PAYMENT}")
    private String chainId;

    @Bean
    @Primary
    public NetworkParameters networkParameters() {
        if ("Test".equals(serverConfiguration.getNet())) {
            return new PaymentL1TestParams(chainId);
        }
        return new PaymentL1Params(chainId);
    }
}
