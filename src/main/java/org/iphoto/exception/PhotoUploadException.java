package org.iphoto.exception;

public class PhotoUploadException extends RuntimeException {
    public PhotoUploadException(Exception e) {
        super(e);
    }
}
