package com.clusterpulse.exception;

public class ClusterUnreachableException extends RuntimeException {
    public ClusterUnreachableException(String message) {
        super(message);
    }

    public ClusterUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }
}
