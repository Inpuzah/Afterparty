package com.inpuzah.club.core.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import javax.sql.DataSource;

public final class Migrations {

    public static final String LOCATION = "classpath:db/migration";

    private Migrations() {
    }

    /**
     * Applies pending migrations. Throws {@link org.flywaydb.core.api.FlywayException} on failure.
     *
     * @param classLoader loader that can see {@code db/migration} (the plugin's own class loader on Paper)
     */
    public static MigrateResult apply(DataSource dataSource, ClassLoader classLoader) {
        return Flyway.configure(classLoader)
                .dataSource(dataSource)
                .locations(LOCATION)
                .failOnMissingLocations(true)
                .load()
                .migrate();
    }
}
