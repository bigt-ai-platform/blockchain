package net.bigtangle.l1.pai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(basePackages = { "net.bigtangle" },
    excludeFilters = @ComponentScan.Filter(
        type = org.springframework.context.annotation.FilterType.REGEX,
        pattern = "net\\.bigtangle\\.layer0\\..*|net\\.bigtangle\\.l1\\.contract\\..*|net\\.bigtangle\\.l1\\.ordermatch\\..*|net\\.bigtangle\\.l1\\.pai\\.PaiL1ServerStart|net\\.bigtangle\\.server\\.config\\.NetConfiguration|net\\.bigtangle\\.server\\.ServerStart"))
@EnableScheduling
@EnableCaching
public class PaiL1MCMCStart {
    public static void main(String[] args) {
        SpringApplication springApplication = new SpringApplication(PaiL1MCMCStart.class);
        springApplication.run(args);
    }
}
