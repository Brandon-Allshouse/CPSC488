package edu.cpsc488.brainfeed.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// These only test reading Have I Been Pwned's response, so they don't call the internet.
class BreachedPasswordCheckerTest {

    @Test
    void sha1IsUppercaseHex() {
        // known SHA-1 of "password", which is what the API expects
        assertEquals("5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8", BreachedPasswordChecker.sha1Hex("password"));
    }

    @Test
    void findsOurSuffixInTheResponse() {
        String body = "0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n1E4C9B93F3F0682250B6CF8331B7EE68FD8:9545824\r\n";
        assertTrue(BreachedPasswordChecker.foundInRange(body, "1E4C9B93F3F0682250B6CF8331B7EE68FD8"));
    }

    @Test
    void suffixMatchIgnoresCase() {
        assertTrue(BreachedPasswordChecker.foundInRange("ABCDEF:3", "abcdef"));
    }

    @Test
    void notInTheResponseMeansNotBreached() {
        assertFalse(BreachedPasswordChecker.foundInRange("0018A45C4D1DEF81644B54AB7F969B88D65:1", "1E4C9B93F3F0682250B6CF8331B7EE68FD8"));
    }

    @Test
    void paddingLinesDontCount() {
        // the API adds fake lines with a count of 0 so nobody watching can tell how many real matches there were
        assertFalse(BreachedPasswordChecker.foundInRange("ABCDEF:0", "ABCDEF"));
    }

    @Test
    void emptyResponseIsNotBreached() {
        assertFalse(BreachedPasswordChecker.foundInRange("", "ABCDEF"));
    }
}
