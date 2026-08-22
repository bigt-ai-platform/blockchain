package net.bigtangle.l1.social;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(basePackages = { "net.bigtangle" })
@EnableScheduling
public class SocialL1ServerStart {

    public static void main(String[] args) {
        SpringApplication springApplication = new SpringApplication(SocialL1ServerStart.class);
        springApplication.run(args);
    }
}
