/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.TestParams;

@Configuration
public class NetConfiguration {

    @Autowired
    ServerConfiguration serverConfiguration;
    @Bean
    public NetworkParameters networkParameters() {
        if("Mainnet".equals(serverConfiguration.getNet())) {
            return new MainNetParams();
        }
        if("Test".equals(serverConfiguration.getNet())) {
            return new TestParams();
        }
  
        return new TestParams();
    }
    
    
}
