package net.bigtangle.l1.evm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * L1-EVM server node. Dedicated chain for EVM smart contracts (chainId
 * {@code "EVM"}). Reuses the Layer-1 contract server implementation (EVM
 * engine, RPC, services) via {@code l1-contract-server}, but with EVM-only
 * chain parameters. The contract-chain network configuration is excluded so
 * only {@link EVML1NetworkConfiguration} supplies the {@link
 * net.bigtangle.params.NetworkParameters}.
 */
@SpringBootApplication
@ComponentScan(basePackages = { "net.bigtangle" }, excludeFilters = {
		@ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
				classes = { net.bigtangle.l1.contract.ContractL1NetworkConfiguration.class,
						net.bigtangle.l1.contract.ContractL1ServerStart.class }) })
@EnableScheduling
public class EVML1ServerStart {

	public static void main(String[] args) {
		SpringApplication springApplication = new SpringApplication(EVML1ServerStart.class);
		springApplication.run(args);
	}
}
