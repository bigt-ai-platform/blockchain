package net.bigtangle.l1.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(basePackages = { "net.bigtangle" },
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "net\\.bigtangle\\.layer0\\..*|net\\.bigtangle\\.l1\\.contract\\..*|net\\.bigtangle\\.l1\\.ordermatch\\..*|net\\.bigtangle\\.l1\\.pai\\..*|net\\.bigtangle\\.l1\\.nft\\..*|net\\.bigtangle\\.l1\\.payment\\.PaymentL1ServerStart|net\\.bigtangle\\.server\\.config\\.NetConfiguration|net\\.bigtangle\\.server\\.ServerStart"))
@EnableScheduling
@EnableCaching
public class PaymentL1MCMCStart {

    public static void main(String[] args) {
        SpringApplication springApplication = new SpringApplication(PaymentL1MCMCStart.class);
        springApplication.run(args);
    }
}
