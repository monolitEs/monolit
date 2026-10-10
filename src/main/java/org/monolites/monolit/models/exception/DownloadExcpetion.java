package org.monolites.monolit.models.exception;

public class DownloadExcpetion extends RuntimeException {
    public DownloadExcpetion(String message, Throwable cause) {
        super(message, cause);
    }
}
