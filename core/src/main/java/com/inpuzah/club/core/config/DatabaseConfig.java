package com.inpuzah.club.core.config;

import java.util.Set;
import java.util.regex.Pattern;

public record DatabaseConfig(
        String host,
        int port,
        String name,
        String username,
        String password,
        String sslMode,
        int poolSize,
        long connectionTimeoutMs
) {

    public static final Set<String> SSL_MODES = Set.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full");
    public static final int MAX_POOL_SIZE = 20;

    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.\\-]+|\\[[0-9A-Fa-f:]+]");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_\\-]{0,62}");

    public DatabaseConfig {
        require(host != null && HOST.matcher(host).matches(), "database.host must be a hostname or IP address");
        require(port >= 1 && port <= 65535, "database.port must be between 1 and 65535");
        require(name != null && IDENTIFIER.matcher(name).matches(), "database.name must be a valid database name");
        require(username != null && IDENTIFIER.matcher(username).matches(), "database.username must be a valid role name");
        require(password != null && !password.isEmpty(), "database password must not be empty");
        require(sslMode != null && SSL_MODES.contains(sslMode), "database.ssl-mode must be one of " + SSL_MODES);
        require(poolSize >= 1 && poolSize <= MAX_POOL_SIZE, "database.pool-size must be between 1 and " + MAX_POOL_SIZE);
        require(connectionTimeoutMs >= 250 && connectionTimeoutMs <= 60_000,
                "database.connection-timeout-ms must be between 250 and 60000");
    }

    /** JDBC URL without credentials; safe to log. */
    public String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s?sslmode=%s".formatted(host, port, name, sslMode);
    }

    @Override
    public String toString() {
        return "DatabaseConfig[url=%s, username=%s, poolSize=%d, connectionTimeoutMs=%d]"
                .formatted(jdbcUrl(), username, poolSize, connectionTimeoutMs);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ConfigException(message);
        }
    }
}
