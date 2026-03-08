package com.soccerdashboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SoccerDashboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(SoccerDashboardApplication.class, args);
    }
}
