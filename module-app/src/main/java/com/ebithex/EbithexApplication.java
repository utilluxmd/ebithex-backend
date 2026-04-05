package com.ebithex;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableRetry
@EnableAsync
@EnableScheduling
@org.springframework.cache.annotation.EnableCaching
public class EbithexApplication {
    public static void main(String[] args) {
        SpringApplication.run(EbithexApplication.class, args);
    }
}
