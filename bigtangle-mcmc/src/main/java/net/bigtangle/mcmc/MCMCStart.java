/*******************************************************************************
 *  Copyright   2018  Inasset GmbH.
 *
 *******************************************************************************/
package net.bigtangle.mcmc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(basePackages = { "net.bigtangle" },
               excludeFilters = @ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.REGEX,
                                                       pattern = "net\\.bigtangle\\.server\\.service\\.schedule\\..*"))
@EnableScheduling
@EnableCaching
public class MCMCStart {

    public static void main(String[] args) {
        // SpringApplication.run(MCMCStart.class, args);
        SpringApplication springApplication = new SpringApplication(MCMCStart.class);

        springApplication.run(args);
    }
}
