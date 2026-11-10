package com.inpuzah.club.core.economy;

public final class EconomyException extends RuntimeException {

    public enum Code {
        PROFILE_NOT_FOUND,
        OPERATION_CONFLICT,
        BALANCE_LIMIT_EXCEEDED,
        SERVICE_UNAVAILABLE
    }

    private final Code code;

    public EconomyException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
