package io.github.carloslimajlle.liquimanager.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * LiquiManager background jobs: drift detection, daily metrics, the
 * DATABASECHANGELOG scanner, notifications and retention housekeeping.
 *
 * <p>It runs apart from the API because it needs a route to EVERY monitored
 * database (D-11), and because a heavy snapshot must not compete with API
 * traffic. With two or more replicas, ShedLock makes sure each job runs once
 * (RNF-05).
 */
@SpringBootApplication
@EnableScheduling
public class LiquiManagerWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LiquiManagerWorkerApplication.class, args);
    }
}
