package dev.beacon.incident;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class IncidentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IncidentServiceApplication.class, args);
    }

    /** A single injectable clock keeps timestamps deterministic in tests. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
