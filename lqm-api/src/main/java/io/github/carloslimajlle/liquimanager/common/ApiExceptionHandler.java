package io.github.carloslimajlle.liquimanager.common;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 responses with the LiquiManager business code in the `code` member. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(LqmException.class)
    ProblemDetail business(LqmException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setType(URI.create("about:blank"));
        problem.setTitle(e.status().getReasonPhrase());
        problem.setProperty("code", e.code());
        return problem;
    }

    /** A unique key or a foreign key refused the write: a conflict, not a server error. */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ProblemDetail conflict(org.springframework.dao.DataIntegrityViolationException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "the change conflicts with existing data (duplicate or still referenced)");
        problem.setTitle("Conflict");
        problem.setProperty("code", "LQM-409");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegal(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        problem.setTitle("Invalid payload");
        problem.setProperty("code", "LQM-422");
        return problem;
    }
}
