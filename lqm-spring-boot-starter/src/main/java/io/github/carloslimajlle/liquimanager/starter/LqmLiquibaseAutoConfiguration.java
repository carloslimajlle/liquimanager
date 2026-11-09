package io.github.carloslimajlle.liquimanager.starter;

import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseDataSource;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

import liquibase.integration.spring.SpringLiquibase;

/**
 * Replaces Boot's own {@code SpringLiquibase} with {@link LqmSpringLiquibase}.
 *
 * <p>Runs BEFORE {@code LiquibaseAutoConfiguration}, whose bean is {@code @ConditionalOnMissingBean},
 * so it steps aside. The {@code spring.liquibase.*} properties are applied the way Boot applies
 * them; a service that sets {@code lqm.enabled=false}, or has no DataSource, behaves exactly as
 * it did before the starter was added.
 */
@AutoConfiguration(before = LiquibaseAutoConfiguration.class, after = DataSourceAutoConfiguration.class)
@ConditionalOnClass(SpringLiquibase.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "lqm", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties({LqmProperties.class, LiquibaseProperties.class})
public class LqmLiquibaseAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SpringLiquibase.class)
    public SpringLiquibase liquibase(LqmProperties lqm, LiquibaseProperties properties,
                                     ObjectProvider<DataSource> dataSource,
                                     @LiquibaseDataSource ObjectProvider<DataSource> liquibaseDataSource) {
        LqmSpringLiquibase liquibase = new LqmSpringLiquibase(lqm);
        liquibase.setDataSource(chooseDataSource(properties, liquibaseDataSource.getIfAvailable(),
                dataSource.getIfUnique()));
        liquibase.setChangeLog(properties.getChangeLog());
        liquibase.setClearCheckSums(properties.isClearChecksums());
        List<String> contexts = properties.getContexts();
        liquibase.setContexts(contexts == null ? null : StringUtils.collectionToCommaDelimitedString(contexts));
        liquibase.setDefaultSchema(properties.getDefaultSchema());
        liquibase.setLiquibaseSchema(properties.getLiquibaseSchema());
        liquibase.setLiquibaseTablespace(properties.getLiquibaseTablespace());
        liquibase.setDatabaseChangeLogTable(properties.getDatabaseChangeLogTable());
        liquibase.setDatabaseChangeLogLockTable(properties.getDatabaseChangeLogLockTable());
        liquibase.setDropFirst(properties.isDropFirst());
        liquibase.setShouldRun(properties.isEnabled());
        liquibase.setLabelFilter(properties.getLabelFilter() == null ? null
                : StringUtils.collectionToCommaDelimitedString(properties.getLabelFilter()));
        liquibase.setChangeLogParameters(properties.getParameters());
        liquibase.setRollbackFile(properties.getRollbackFile());
        liquibase.setTestRollbackOnUpdate(properties.isTestRollbackOnUpdate());
        if (properties.getTag() != null) {
            liquibase.setTag(properties.getTag());
        }
        return liquibase;
    }

    private static DataSource chooseDataSource(LiquibaseProperties properties, DataSource dedicated,
                                               DataSource primary) {
        if (dedicated != null) {
            return dedicated;
        }
        if (properties.getUrl() != null) {
            return DataSourceBuilder.create().url(properties.getUrl())
                    .username(properties.getUser()).password(properties.getPassword()).build();
        }
        return primary;
    }

    /** {@code lqm.migrate-only=true}: the update has run by the time the context is up, so just leave. */
    @Bean
    @ConditionalOnProperty(prefix = "lqm", name = "migrate-only", havingValue = "true")
    public ApplicationRunner lqmMigrateOnlyExit(ConfigurableApplicationContext context) {
        return new ApplicationRunner() {
            @Override
            public void run(ApplicationArguments args) {
                int code = SpringApplication.exit(context, () -> 0);
                System.exit(code);
            }
        };
    }
}
