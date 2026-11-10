package com.inpuzah.club.core.settings;

public final class SettingsException extends RuntimeException {
    public enum Code { NOT_ONLINE, NOT_READY, BUSY, UNAVAILABLE, CLOSED }
    private final Code code;

    public SettingsException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
