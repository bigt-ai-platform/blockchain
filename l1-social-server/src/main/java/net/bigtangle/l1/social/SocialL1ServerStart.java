package net.bigtangle.l1.social;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(basePackages = { "net.bigtangle" })
@EnableScheduling
public class SocialL1ServerStart {

    static void configureZeroFee() {
        System.setProperty("bigtangle.fee.default", "0");
    }

    public static void main(String[] args) {
        // The L1-SOCIAL chain runs fee-free: Coin.FEE_DEFAULT is a static final
        // read once at class load, so the property must be set before anything
        // references Coin. Setting it here (not per deployment) guarantees every
        // node of the chain runs the same fee rule — mixed values would fork.
        configureZeroFee();
        SpringApplication springApplication = new SpringApplication(SocialL1ServerStart.class);
        springApplication.run(args);
    }
}
