package io.github.carloslimajlle.liquimanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Control plane for database changes, built on top of Liquibase OSS.
 *
 * <p>LiquiManager's own schema is version-controlled with Liquibase (dogfooding,
 * docs/01 § 8) and applied at startup from
 * {@code db/changelog/db.changelog-master.xml}.
 */
@SpringBootApplication
public class LiquiManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LiquiManagerApplication.class, args);
    }
}
