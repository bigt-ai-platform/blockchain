package net.bigtangle.l1.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(basePackages = { "net.bigtangle" })
@EnableScheduling
public class PaymentL1ServerStart {

    public static void main(String[] args) {
        SpringApplication springApplication = new SpringApplication(PaymentL1ServerStart.class);
        springApplication.run(args);
    }
}
