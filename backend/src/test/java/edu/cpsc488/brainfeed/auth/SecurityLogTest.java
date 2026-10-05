package edu.cpsc488.brainfeed.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SecurityLogTest {

    @Test
    void newlinesCantBeUsedToFakeLogEntries() {
        // if an attacker puts \n in a field they could add a made up line to our log
        assertEquals("testuser_LOGIN_SUCCESS user=admin", SecurityLog.clean("testuser\nLOGIN_SUCCESS user=admin"));
    }

    @Test
    void quotesAreReplaced() {
        // the user agent is logged inside quotes, so a quote could end it early
        assertEquals("Mozilla _fake_", SecurityLog.clean("Mozilla \"fake\""));
    }

    @Test
    void nullBecomesADash() {
        assertEquals("-", SecurityLog.clean(null));
    }

    @Test
    void reallyLongValuesGetCutOff() {
        assertEquals(200, SecurityLog.clean("a".repeat(500)).length());
    }
}
