package edu.cpsc488.brainfeed.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestDatabaseNameTest {

    @Test
    void defaultsToBrainfeedTest() {
        assertEquals("brainfeed_test", TestDatabase.testDatabaseName(null));
        assertEquals("brainfeed_test", TestDatabase.testDatabaseName(" "));
    }

    @Test
    void acceptsASuffix() {
        assertEquals("brainfeed_test_quiz", TestDatabase.testDatabaseName("brainfeed_test_quiz"));
    }

    @Test
    void refusesNamesThatCouldDropARealDatabase() {
        // the name goes straight into DROP DATABASE, so it must never be "brainfeed" or contain SQL
        assertThrows(IllegalStateException.class, () -> TestDatabase.testDatabaseName("brainfeed"));
        assertThrows(IllegalStateException.class, () -> TestDatabase.testDatabaseName("brainfeed_test; DROP DATABASE brainfeed"));
        assertThrows(IllegalStateException.class, () -> TestDatabase.testDatabaseName("brainfeed_test-X"));
    }
}
