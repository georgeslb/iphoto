package org.iphoto.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.iphoto.exception.SpaceSyncLockedException;
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

    // Global (not per-controller try/catch) since a locked Space's writes can be rejected from
    // many different services/controllers (upload, garbage/restore/permanent-delete, edit,
    // date-change, move-to-main, space deletion - see SpaceService#assertSyncWritable's own
    // callers) - one handler here is less error-prone than remembering to catch it at every one of
    // those call sites. 423 LOCKED is the standard HTTP status for "the resource is locked", which
    // is exactly this case.
    @ExceptionHandler(SpaceSyncLockedException.class)
    public ResponseEntity<Map<String, Object>> handleSpaceSyncLocked(SpaceSyncLockedException ex) {
        return ResponseEntity.status(HttpStatus.LOCKED).body(Map.of(
                "error", "space_sync_locked",
                "spaceId", ex.getSpaceId().toString(),
                "lockedByDeviceName", ex.getLockedByDeviceName()
        ));
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
