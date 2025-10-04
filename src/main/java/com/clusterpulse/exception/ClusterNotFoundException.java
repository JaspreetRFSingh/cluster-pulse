package com.clusterpulse.exception;

public class ClusterNotFoundException extends RuntimeException {
    public ClusterNotFoundException(String message) {
        super(message);
    }
}
