package com.award.log.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public Result<String> handleRuntimeException(RuntimeException e) {
        log.error("Runtime exception", e);
        String detail = e.getClass().getSimpleName()
                + (e.getMessage() == null || e.getMessage().isBlank() ? "" : (": " + e.getMessage()));
        return Result.error(500, "Business processing failed: " + detail);
    }

    @ExceptionHandler(Exception.class)
    public Result<String> handleException(Exception e) {
        log.error("Unhandled exception", e);
        String detail = e.getClass().getSimpleName()
                + (e.getMessage() == null || e.getMessage().isBlank() ? "" : (": " + e.getMessage()));
        return Result.error(500, "Internal server error: " + detail);
    }
}
