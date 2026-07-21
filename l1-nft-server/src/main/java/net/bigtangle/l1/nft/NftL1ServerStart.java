package net.bigtangle.l1.nft;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan("net.bigtangle")
public class NftL1ServerStart {
    public static void main(String[] args) {
        SpringApplication.run(NftL1ServerStart.class, args);
    }
}
