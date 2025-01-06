package com.rcs.external;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RcsMessageApplication {
    public static void main(String[] args) {
        SpringApplication.run(RcsMessageApplication.class, args);
    }
}