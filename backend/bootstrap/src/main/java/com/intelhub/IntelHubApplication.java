package com.intelhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class IntelHubApplication {
    public static void main(String[] args) {
        SpringApplication.run(IntelHubApplication.class, args);
    }
}
