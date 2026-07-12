package net.bigtangle.layer0.mcmc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Layer 0 MCMC node entry point. Runs the MCMC tip-selection + reward consensus
 * for the Layer 0 settlement chain. Mirrors {@code MCMCStart}'s component-scan
 * filters (excludes the server's own schedules and MCMC services so the mcmc
 * module's versions are used) and scopes the chain to Layer 0 via
 * {@link Layer0NetworkConfiguration}. See {@code LAYERING-PLAN.md}.
 */
@SpringBootApplication
@ComponentScan(basePackages = { "net.bigtangle" },
               excludeFilters = @ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.REGEX,
                                                        pattern = "net\\.bigtangle\\.layer1\\..*|net\\.bigtangle\\.layer0\\.server\\..*|net\\.bigtangle\\.layer0\\.server\\.Layer0ServerStart|net\\.bigtangle\\.server\\.config\\.NetConfiguration|net\\.bigtangle\\.server\\.service\\.schedule\\.(?!UpdateChainService).*|net\\.bigtangle\\.server\\.ServerStart|net\\.bigtangle\\.server\\.service\\.RewardService|net\\.bigtangle\\.server\\.service\\.MCMCService|net\\.bigtangle\\.server\\.service\\.TipsService"))
@EnableScheduling
@EnableCaching
public class Layer0MCMCStart {

    public static void main(String[] args) {
        SpringApplication springApplication = new SpringApplication(Layer0MCMCStart.class);
        springApplication.run(args);
    }
}
