package io.github.carloslimajlle.liquimanager.starter;

/** The startup was aborted because the policy gate blocked it (lqm.enforce=true). */
public class LqmPolicyViolationException extends RuntimeException {

    public LqmPolicyViolationException(String message) {
        super(message);
    }
}
