package io.github.carloslimajlle.liquimanager.starter;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code lqm.*} (docs/07 § 1.1). */
@ConfigurationProperties("lqm")
public class LqmProperties {

    /** Master switch. With false the starter steps aside and Liquibase runs as if it were not here. */
    private boolean enabled = true;

    /** LiquiManager base URL; a trailing /api/v1 is accepted. Without it (or the token) nothing is reported. */
    private String url;

    /** Service token with the ingest scope, normally from a secret. */
    private String token;

    /** Project slug in LiquiManager. */
    private String project;

    /** Connection key; when absent LiquiManager matches by JDBC URL. */
    private String connectionKey;

    /** Environment code (DEV, TST, HML, PRD...). */
    private String environment;

    /** Evaluate the policy package before the update and abort the startup on a blocking finding. */
    private boolean enforce;

    /** Abort the startup when LiquiManager cannot be reached to check the policies. Off by default (ADR-004). */
    private boolean failClosed;

    /** Per request. Short: a deploy never waits long for LiquiManager. */
    private Duration timeout = Duration.ofSeconds(3);

    /** Where reports that could not be sent wait for the next start. */
    private String bufferDir = System.getProperty("java.io.tmpdir") + "/lqm-buffer";

    /** Run the update and exit (0 ok, 1 failed); for a Job or initContainer before the rollout. */
    private boolean migrateOnly;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getConnectionKey() {
        return connectionKey;
    }

    public void setConnectionKey(String connectionKey) {
        this.connectionKey = connectionKey;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public boolean isEnforce() {
        return enforce;
    }

    public void setEnforce(boolean enforce) {
        this.enforce = enforce;
    }

    public boolean isFailClosed() {
        return failClosed;
    }

    public void setFailClosed(boolean failClosed) {
        this.failClosed = failClosed;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public String getBufferDir() {
        return bufferDir;
    }

    public void setBufferDir(String bufferDir) {
        this.bufferDir = bufferDir;
    }

    public boolean isMigrateOnly() {
        return migrateOnly;
    }

    public void setMigrateOnly(boolean migrateOnly) {
        this.migrateOnly = migrateOnly;
    }
}
