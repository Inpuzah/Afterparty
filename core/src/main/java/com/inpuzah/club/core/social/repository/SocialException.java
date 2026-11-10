package com.inpuzah.club.core.social.repository;

public final class SocialException extends RuntimeException {
    public enum Code { PLAYER_NOT_FOUND, SERVICE_UNAVAILABLE }
    private final Code code;

    public SocialException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
