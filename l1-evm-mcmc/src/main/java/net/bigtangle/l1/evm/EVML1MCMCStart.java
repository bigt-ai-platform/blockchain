package net.bigtangle.l1.evm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * L1-EVM MCMC consensus node for the dedicated EVM chain (chainId {@code "EVM"}).
 * Reuses the Layer-1 contract MCMC implementation via {@code l1-contract-mcmc}
 * with EVM-only chain parameters.
 */
@SpringBootApplication
@ComponentScan(basePackages = { "net.bigtangle" }, excludeFilters = {
		@ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
				classes = { net.bigtangle.l1.contract.ContractL1NetworkConfiguration.class,
						net.bigtangle.l1.contract.ContractL1MCMCStart.class }) })
@EnableScheduling
public class EVML1MCMCStart {

	public static void main(String[] args) {
		SpringApplication springApplication = new SpringApplication(EVML1MCMCStart.class);
		springApplication.run(args);
	}
}
