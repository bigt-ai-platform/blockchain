package net.bigtangle.l1.contract;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * L1-contract server node. Provides the REST API for contract operations
 * on the contract sub-chain. chainId = {@code "contract"}.
 */
@SpringBootApplication
@ComponentScan(basePackages = { "net.bigtangle" })
@EnableScheduling
public class ContractL1ServerStart {

    public static void main(String[] args) {
        SpringApplication springApplication = new SpringApplication(ContractL1ServerStart.class);
        springApplication.run(args);
    }
}
