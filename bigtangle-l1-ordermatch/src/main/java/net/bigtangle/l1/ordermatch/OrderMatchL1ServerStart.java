package net.bigtangle.l1.ordermatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * L1-ordermatch server node. Provides the REST API for order operations
 * on the ordermatch sub-chain. chainId = {@code "ordermatch"}.
 */
@SpringBootApplication
@ComponentScan(basePackages = { "net.bigtangle" })
@EnableScheduling
public class OrderMatchL1ServerStart {

    public static void main(String[] args) {
        SpringApplication springApplication = new SpringApplication(OrderMatchL1ServerStart.class);
        springApplication.run(args);
    }
}
