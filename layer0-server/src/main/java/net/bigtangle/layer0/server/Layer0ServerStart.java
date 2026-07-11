package net.bigtangle.layer0.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Layer 0 server node entry point. Boots the full bigtangle server stack but
 * scopes it to the Layer 0 settlement chain via {@link Layer0NetworkConfiguration}
 * (token creation + payment + reward). Component-scans the whole
 * {@code net.bigtangle} tree so all base + layer0 services are picked up.
 * See {@code LAYERING-PLAN.md}.
 */
@SpringBootApplication
@ComponentScan(basePackages = { "net.bigtangle" },
               excludeFilters = @ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.REGEX,
                                                       pattern = "net\\.bigtangle\\.server\\.config\\.NetConfiguration"))
@EnableScheduling
@EnableCaching
public class Layer0ServerStart {

    public static void main(String[] args) {
        SpringApplication springApplication = new SpringApplication(Layer0ServerStart.class);
        springApplication.run(args);
    }
}
