package net.bigtangle.l1.ordermatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * L1-ordermatch MCMC node. Runs MCMC tip-selection + reward consensus
 * for the ordermatch sub-chain. Accepts only {@code ORDER_*} block types.
 * chainId = {@code "ordermatch"}.
 *
 * @see net.bigtangle.l1.contract.ContractL1MCMCStart (the L1-contract counterpart)
 */
@SpringBootApplication
@ComponentScan(basePackages = { "net.bigtangle" },
               excludeFilters = @ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.REGEX,
                                                        pattern = "net\\.bigtangle\\.layer0\\..*|net\\.bigtangle\\.layer1\\..*|net\\.bigtangle\\.l1\\.contract\\..*|net\\.bigtangle\\.server\\.config\\.NetConfiguration|net\\.bigtangle\\.server\\.service\\.schedule\\..*|net\\.bigtangle\\.server\\.ServerStart|net\\.bigtangle\\.server\\.service\\.RewardService|net\\.bigtangle\\.server\\.service\\.MCMCService|net\\.bigtangle\\.server\\.service\\.TipsService"))
@EnableScheduling
@EnableCaching
public class OrderMatchL1MCMCStart {

    public static void main(String[] args) {
        SpringApplication springApplication = new SpringApplication(OrderMatchL1MCMCStart.class);
        springApplication.run(args);
    }
}
