package edu.cpsc488.brainfeed.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

/** Creates the Postgres connection pool and keeps the schema up to date. */
public final class Database {

    private Database() {
    }

    public static HikariDataSource connect(String url, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(10);
        config.setPoolName("brainfeed");
        return new HikariDataSource(config);
    }

    /**
     * Runs any SQL files in src/main/resources/db/migration that haven't been applied to this
     * database yet, in version order. Called on every startup.
     *
     * <p>To change the schema, add a new file named {@code V<next number>__description.sql}.
     * Never edit a migration that has already been run on anyone's database: Flyway stores a
     * checksum of each applied file and will refuse to start if it changes.
     */
    public static void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }
}
