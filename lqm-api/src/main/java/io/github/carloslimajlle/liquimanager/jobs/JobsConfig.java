package io.github.carloslimajlle.liquimanager.jobs;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

/**
 * Scheduled jobs (docs/04: the worker starts inside the API). They run in the API process, behind {@code lqm.jobs.enabled}, with a database
 * lock per job so two replicas never run the same one at the same time.
 */
@Configuration
@EnableScheduling
@EnableAsync
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
@ConditionalOnProperty(name = "lqm.jobs.enabled", havingValue = "true", matchIfMissing = true)
class JobsConfig {

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        // The database clock, not each replica's own: replicas with skewed clocks would otherwise fight over expiry.
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource)).usingDbTime().build());
    }
}
