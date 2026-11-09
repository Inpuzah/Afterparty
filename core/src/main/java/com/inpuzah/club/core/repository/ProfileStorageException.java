package com.inpuzah.club.core.repository;

/**
 * The profile store could not be reached or rejected an operation. Messages may contain SQL details and must
 * only be logged, never shown to players.
 */
public final class ProfileStorageException extends RuntimeException {

    public ProfileStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
