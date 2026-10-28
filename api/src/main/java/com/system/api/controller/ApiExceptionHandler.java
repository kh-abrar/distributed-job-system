package com.system.api.controller;

import com.system.api.service.IdempotencyConflictException;
import com.system.api.service.InvalidJobRequestException;
import com.system.api.service.JobNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps errors to RFC 7807 problem responses.
 *
 * The parent class already turns Spring's own errors (unreadable body, bad path
 * variable, wrong method) into 400/405 problems. Database constraint violations
 * are deliberately not mapped: if a CHECK fires, validation here missed a case,
 * and a 500 shows that bug instead of hiding it.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(InvalidJobRequestException.class)
    ProblemDetail handleInvalidRequest(InvalidJobRequestException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail handleConflict(IdempotencyConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(JobNotFoundException.class)
    ProblemDetail handleNotFound(JobNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }
}
