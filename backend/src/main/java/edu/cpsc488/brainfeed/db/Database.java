package edu.cpsc488.brainfeed.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

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

    /** Work done inside {@link #inTransaction}. Like a lambda, but allowed to throw SQLException. */
    public interface TransactionWork<T> {
        T run(Connection conn) throws SQLException;
    }

    /**
     * Runs {@code work} on one connection as a single transaction: committed if it returns, rolled
     * back if it throws (including an ApiException, so a "list is full" error never leaves half a
     * change behind). The connection goes back to the pool in autocommit mode either way.
     *
     * <p>Used for the per-user limits (saved videos, playlists): the work first locks the row that
     * owns the list with {@code SELECT ... FOR NO KEY UPDATE}, so two requests at once take turns
     * instead of both counting 499 and both inserting. That lock waits only for other limit checks
     * on the same row; it doesn't block normal writes that just reference the row.
     */
    public static <T> T inTransaction(DataSource dataSource, TransactionWork<T> work) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                T result = work.run(conn);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
