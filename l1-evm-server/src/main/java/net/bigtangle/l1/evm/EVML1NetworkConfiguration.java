package net.bigtangle.l1.evm;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import net.bigtangle.layer1.params.EVML1Params;
import net.bigtangle.layer1.params.EVML1TestParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ServerConfiguration;

@Configuration
public class EVML1NetworkConfiguration {

	@Autowired
	ServerConfiguration serverConfiguration;

	@Bean
	@Primary
	public NetworkParameters networkParameters() {
		if ("Test".equals(serverConfiguration.getNet())) {
			return new EVML1TestParams();
		}
		return new EVML1Params();
	}
}
