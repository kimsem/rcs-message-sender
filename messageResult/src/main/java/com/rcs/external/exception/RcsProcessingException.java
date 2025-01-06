package com.rcs.external.exception;

import lombok.Getter;

@Getter
public class RcsProcessingException extends RuntimeException {
    private final String errorCode;

    public RcsProcessingException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }
}