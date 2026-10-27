package edu.cpsc488.brainfeed.db;

import edu.cpsc488.brainfeed.ApiException;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseTest {

    // Stand-ins for a real connection pool: they just write down which calls were made, in order.
    // There's no mocking library, and these tests shouldn't need a database.
    private final List<String> calls = new ArrayList<>();

    private DataSource fakeDataSource() {
        Connection conn = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    String call = method.getName();
                    if (call.equals("setAutoCommit")) {
                        call += "(" + args[0] + ")";
                    }
                    calls.add(call);
                    return null;
                });
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        return conn;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void commitsWhenTheWorkFinishes() {
        String result = Database.inTransaction(fakeDataSource(), conn -> "done");

        assertEquals("done", result);
        assertEquals(List.of("setAutoCommit(false)", "commit", "setAutoCommit(true)", "close"), calls);
    }

    @Test
    void rollsBackWhenAListIsFull() {
        // e.g. the 51st playlist: nothing from this request should be kept
        ApiException full = ApiException.conflict("You can have at most 50 playlists.");
        ApiException thrown = assertThrows(ApiException.class, () -> Database.inTransaction(fakeDataSource(), conn -> {
            throw full;
        }));

        assertSame(full, thrown);
        assertEquals(List.of("setAutoCommit(false)", "rollback", "setAutoCommit(true)", "close"), calls);
    }

    @Test
    void rollsBackAndWrapsDatabaseErrors() {
        SQLException duplicate = new SQLException("duplicate key", "23505");
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> Database.inTransaction(fakeDataSource(), conn -> {
            throw duplicate;
        }));

        // PlaylistRepository looks at the cause to spot a duplicate name
        assertInstanceOf(SQLException.class, thrown.getCause());
        assertEquals("23505", ((SQLException) thrown.getCause()).getSQLState());
        assertEquals(List.of("setAutoCommit(false)", "rollback", "setAutoCommit(true)", "close"), calls);
    }
}
