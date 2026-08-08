package net.bigtangle.l1.ordermatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * L1-ordermatch server node. Provides the REST API for order operations
 * on the ordermatch sub-chain. chainId = {@code "ordermatch"}.
 */
@SpringBootApplication
@ComponentScan(basePackages = { "net.bigtangle" },
    excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
        pattern = "net\\.bigtangle\\.l1\\.contract\\..*|net\\.bigtangle\\.l1\\.pai\\..*|net\\.bigtangle\\.l1\\.nft\\..*|net\\.bigtangle\\.l1\\.payment\\..*|net\\.bigtangle\\.server\\.config\\.NetConfiguration"))
@EnableScheduling
public class OrderMatchL1ServerStart {

    public static void main(String[] args) {
        SpringApplication springApplication = new SpringApplication(OrderMatchL1ServerStart.class);
        springApplication.run(args);
    }
}
