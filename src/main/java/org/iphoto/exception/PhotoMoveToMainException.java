package org.iphoto.exception;

public class PhotoMoveToMainException extends RuntimeException {
    public PhotoMoveToMainException(String message, Exception e) {
        super(message, e);
    }
}
