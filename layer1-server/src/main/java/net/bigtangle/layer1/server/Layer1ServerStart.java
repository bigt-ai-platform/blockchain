package net.bigtangle.layer1.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Layer 1 server node entry point. Boots the full bigtangle server stack but
 * scopes it to a Layer 1 sub-chain (order-match + contract) via
 * {@link Layer1NetworkConfiguration}. See {@code LAYERING-PLAN.md}.
 */
@SpringBootApplication
@ComponentScan(basePackages = { "net.bigtangle" })
@EnableScheduling
@EnableCaching
public class Layer1ServerStart {

    public static void main(String[] args) {
        SpringApplication springApplication = new SpringApplication(Layer1ServerStart.class);
        springApplication.run(args);
    }
}
