package org.iphoto.exception;

public class PhotoDeletionException extends RuntimeException {

    public PhotoDeletionException(String message, Exception e) {
        super(message, e);
    }

    public PhotoDeletionException() {

    }
}
