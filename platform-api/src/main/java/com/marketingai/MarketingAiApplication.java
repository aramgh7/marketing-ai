package com.marketingai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MarketingAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketingAiApplication.class, args);
    }
}
