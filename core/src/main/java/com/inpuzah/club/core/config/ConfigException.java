package com.inpuzah.club.core.config;

/**
 * Thrown when configuration is missing or invalid. Messages are safe to log and never contain secrets.
 */
public final class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }
}
