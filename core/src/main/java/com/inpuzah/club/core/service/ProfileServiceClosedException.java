package com.inpuzah.club.core.service;

/**
 * The profile service is shutting down or saturated and did not accept the operation.
 */
public final class ProfileServiceClosedException extends RuntimeException {

    public ProfileServiceClosedException(String message, Throwable cause) {
        super(message, cause);
    }
}
