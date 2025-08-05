package com.clusterpulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ClusterPulseApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClusterPulseApplication.class, args);
    }
}
