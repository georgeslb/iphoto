package org.iphoto.controller;

import jakarta.servlet.http.HttpServletResponse;
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
    public ResponseEntity<Map<String, Object>> handleAllExceptions(Exception ex, HttpServletResponse response) {
        // A client aborting an in-flight response - e.g. closing the Lightbox before a video
        // finishes streaming (PhotoController#streamVideo) - surfaces here as a plain IOException
        // once the connection is gone. By then the response is already committed with whatever
        // content-type the original endpoint was streaming (video/mp4, image/*, ...): trying to
        // write a JSON error body over that is what actually throws
        // HttpMessageNotWritableException ("No converter for HashMap with preset Content-Type
        // 'video/mp4'"), masking this as the real cause in the logs. Once committed, headers/body
        // can't be changed at all regardless of what we return here - the client is gone anyway,
        // so there's nothing to do but log quietly and stop. Returning null from a
        // ResponseEntity-typed @ExceptionHandler is the standard way to say "already handled,
        // nothing more to write" - Spring's HttpEntityMethodProcessor returns immediately for a
        // null return value.
        if (response.isCommitted()) {
            log.debug("Exception after the response was already committed (client likely "
                    + "disconnected mid-stream) - not attempting to write an error body", ex);
            return null;
        }

        log.error("Unhandled exception occurred:", ex);

        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));

        Map<String, Object> body = new HashMap<>();
        log.error(ex.getMessage(), ex);
        body.put("timestamp", System.currentTimeMillis());

        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
