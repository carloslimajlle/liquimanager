package io.github.carloslimajlle.liquimanager.policy;

/** What a check looks at: the changelog, the target database, or both. */
public enum CheckScope {
    CHANGELOG,
    DATABASE,
    BOTH
}
