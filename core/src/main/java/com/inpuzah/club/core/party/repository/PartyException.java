package com.inpuzah.club.core.party.repository;

public final class PartyException extends RuntimeException {
    public enum Code { PLAYER_NOT_FOUND, NOT_IN_PARTY, STALE_EPOCH, SERVICE_UNAVAILABLE }
    private final Code code;

    public PartyException(Code code, String message) {
        super(message);
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
