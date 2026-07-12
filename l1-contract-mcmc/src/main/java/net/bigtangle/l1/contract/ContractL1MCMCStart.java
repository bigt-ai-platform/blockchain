package net.bigtangle.l1.contract;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * L1-contract MCMC node. Runs MCMC tip-selection + reward consensus
 * for the contract sub-chain. Accepts only {@code CONTRACT_*} block types.
 * chainId = {@code "contract"}.
 *
 * @see net.bigtangle.l1.ordermatch.OrderMatchL1MCMCStart (the L1-ordermatch counterpart)
 */
@SpringBootApplication
@ComponentScan(basePackages = { "net.bigtangle" },
               excludeFilters = @ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.REGEX,
                                                        pattern = "net\\.bigtangle\\.layer0\\..*|net\\.bigtangle\\.l1\\.ordermatch\\..*|net\\.bigtangle\\.l1\\.contract\\.ContractL1ServerStart|net\\.bigtangle\\.server\\.config\\.NetConfiguration|net\\.bigtangle\\.server\\.ServerStart"))
@EnableScheduling
@EnableCaching
public class ContractL1MCMCStart {

    public static void main(String[] args) {
        SpringApplication springApplication = new SpringApplication(ContractL1MCMCStart.class);
        springApplication.run(args);
    }
}
