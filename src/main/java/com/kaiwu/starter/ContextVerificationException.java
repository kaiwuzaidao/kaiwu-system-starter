package com.kaiwu.starter;

/**
 * Gateway Context 无法建立可信身份时抛出。
 */
public class ContextVerificationException extends RuntimeException {

    public ContextVerificationException(String message) {
        super(message);
    }

    public ContextVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
