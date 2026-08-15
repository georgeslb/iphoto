package org.iphoto.exception;

public class PhotoRetryableErrorException extends RuntimeException {
    public PhotoRetryableErrorException(String s) {
        super(s);
    }
}
