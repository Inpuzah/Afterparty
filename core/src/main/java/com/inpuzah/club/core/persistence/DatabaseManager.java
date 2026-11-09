package com.inpuzah.club.core.persistence;

import com.inpuzah.club.core.config.DatabaseConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.util.concurrent.TimeUnit;

/**
 * Owns the connection pool. Creating it verifies connectivity and fails fast if PostgreSQL is unreachable.
 */
public final class DatabaseManager implements AutoCloseable {

    private static final int SOCKET_TIMEOUT_SECONDS = 30;

    private final HikariDataSource dataSource;

    private DatabaseManager(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public static DatabaseManager connect(DatabaseConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("ClubCore-DB");
        hikari.setDriverClassName(org.postgresql.Driver.class.getName());
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.poolSize());
        hikari.setMinimumIdle(Math.min(2, config.poolSize()));
        hikari.setConnectionTimeout(config.connectionTimeoutMs());
        hikari.setInitializationFailTimeout(config.connectionTimeoutMs());
        hikari.addDataSourceProperty("ApplicationName", "ClubCore");
        hikari.addDataSourceProperty("connectTimeout",
                Math.max(1, TimeUnit.MILLISECONDS.toSeconds(config.connectionTimeoutMs())));
        hikari.addDataSourceProperty("socketTimeout", SOCKET_TIMEOUT_SECONDS);
        return new DatabaseManager(new HikariDataSource(hikari));
    }

    public DataSource dataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
