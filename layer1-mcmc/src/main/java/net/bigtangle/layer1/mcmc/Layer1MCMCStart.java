package net.bigtangle.layer1.mcmc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Layer 1 MCMC node entry point. Runs the MCMC tip-selection + reward consensus
 * for a Layer 1 sub-chain (order-match / contract). Mirrors {@code MCMCStart}'s
 * component-scan filters and scopes the chain to Layer 1 via
 * {@link Layer1NetworkConfiguration}. See {@code LAYERING-PLAN.md}.
 */
@SpringBootApplication
@ComponentScan(basePackages = { "net.bigtangle" },
               excludeFilters = @ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.REGEX,
                                                        pattern = "net\\.bigtangle\\.layer0\\..*|net\\.bigtangle\\.layer1\\.server\\..*|net\\.bigtangle\\.server\\.config\\.NetConfiguration|net\\.bigtangle\\.server\\.service\\.schedule\\..*|net\\.bigtangle\\.server\\.ServerStart|net\\.bigtangle\\.server\\.service\\.RewardService|net\\.bigtangle\\.server\\.service\\.MCMCService|net\\.bigtangle\\.server\\.service\\.TipsService"))
@EnableScheduling
@EnableCaching
public class Layer1MCMCStart {

    public static void main(String[] args) {
        SpringApplication springApplication = new SpringApplication(Layer1MCMCStart.class);
        springApplication.run(args);
    }
}
