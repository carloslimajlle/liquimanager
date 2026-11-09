package io.github.carloslimajlle.liquimanager.common;

import org.springframework.http.HttpStatus;

/**
 * A business error carrying one of the LQM-0xx codes of docs/06. The advice turns it
 * into an RFC 9457 problem+json response.
 */
public class LqmException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public LqmException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public static LqmException notFound(String what) {
        return new LqmException(HttpStatus.NOT_FOUND, "LQM-001", what + " not found");
    }

    public static LqmException forbidden(String message) {
        return new LqmException(HttpStatus.FORBIDDEN, "LQM-403", message);
    }

    public static LqmException invalid(String message) {
        return new LqmException(HttpStatus.UNPROCESSABLE_ENTITY, "LQM-422", message);
    }
}
