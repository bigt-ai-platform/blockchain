package net.bigtangle.l1.pai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(basePackages = { "net.bigtangle" })
@EnableScheduling
public class PaiL1ServerStart {
    public static void main(String[] args) {
        SpringApplication springApplication = new SpringApplication(PaiL1ServerStart.class);
        springApplication.run(args);
    }
}
