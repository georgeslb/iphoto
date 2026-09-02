package org.iphoto.exception;

// Wraps any failure talking to the Immich REST API (see ImmichClient) with a message clear
// enough to debug from server logs alone - this is a per-user migration feature (see
// specification.md), so falling through to the app's generic error handler is acceptable as long
// as the underlying cause is legible.
public class ImmichApiException extends RuntimeException {
    public ImmichApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
