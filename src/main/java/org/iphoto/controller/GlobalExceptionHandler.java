package org.iphoto.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@ControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // List of common non-existent resources requested by browsers/tools to ignore
    private static final Set<String> IGNORED_RESOURCES = Set.of(
            "favicon.ico",
            ".well-known",
            "com.chrome.devtools.json"
    );

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResourceFound(NoResourceFoundException ex) {
        String path = ex.getResourcePath();
        
        // Silently ignore specific system/browser metadata requests
        boolean shouldIgnore = IGNORED_RESOURCES.stream().anyMatch(path::contains);
        
        if (!shouldIgnore) {
            log.warn("Static resource not found: {}", path);
        }

        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAllExceptions(Exception ex) {
        log.error("Unhandled exception occurred:", ex);

        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));

        Map<String, Object> body = new HashMap<>();
        body.put("error", ex.getMessage());
        body.put("type", ex.getClass().getSimpleName());
        body.put("stackTrace", sw.toString());
        body.put("timestamp", System.currentTimeMillis());

        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
