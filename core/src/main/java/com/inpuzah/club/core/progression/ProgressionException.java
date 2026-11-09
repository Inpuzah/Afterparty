package com.inpuzah.club.core.progression;

public final class ProgressionException extends RuntimeException {

    public enum Code {
        PROFILE_NOT_FOUND,
        OPERATION_CONFLICT,
        XP_OVERFLOW,
        SERVICE_UNAVAILABLE
    }

    private final Code code;

    public ProgressionException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
