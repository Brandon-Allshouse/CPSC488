package edu.cpsc488.brainfeed;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientIpTest {

    // the Vite container in Docker, as our one trusted proxy
    private static final String PROXY = "172.23.0.3";

    @Test
    void withNoTrustedProxiesTheConnectionAddressIsUsed() {
        ClientIp ip = new ClientIp("");
        assertEquals("203.0.113.7", ip.resolve("203.0.113.7", null));
        // the header is ignored completely, because nobody is trusted to write it
        assertEquals("203.0.113.7", ip.resolve("203.0.113.7", "198.51.100.1"));
    }

    @Test
    void readsTheClientFromOurProxy() {
        ClientIp ip = new ClientIp(PROXY);
        assertEquals("198.51.100.20", ip.resolve(PROXY, "198.51.100.20"));
    }

    @Test
    void ignoresTheHeaderFromAnyoneElse() {
        // someone connecting directly can't pretend to be another address
        ClientIp ip = new ClientIp(PROXY);
        assertEquals("203.0.113.7", ip.resolve("203.0.113.7", "198.51.100.20"));
    }

    @Test
    void aClientCantFakeItsAddressThroughTheProxy() {
        // the client sent "X-Forwarded-For: 1.2.3.4" itself; our proxy then added the real address
        ClientIp ip = new ClientIp(PROXY);
        assertEquals("198.51.100.20", ip.resolve(PROXY, "1.2.3.4, 198.51.100.20"));
    }

    @Test
    void skipsEveryTrustedProxyInAChain() {
        ClientIp ip = new ClientIp(PROXY + ", 10.0.0.0/8");
        assertEquals("198.51.100.20", ip.resolve(PROXY, "1.2.3.4, 198.51.100.20, 10.1.2.3"));
    }

    @Test
    void stopsAtAGarbledEntry() {
        // can't tell who is behind it, so the last address we trust is the best we know
        ClientIp ip = new ClientIp(PROXY);
        assertEquals(PROXY, ip.resolve(PROXY, "not-an-ip"));
        assertEquals("198.51.100.20", ip.resolve(PROXY, "garbage, 198.51.100.20"));
    }

    @Test
    void usesTheProxyItselfWhenThereIsNoHeader() {
        ClientIp ip = new ClientIp(PROXY);
        assertEquals(PROXY, ip.resolve(PROXY, null));
        assertEquals(PROXY, ip.resolve(PROXY, "  "));
    }

    @Test
    void onlyLooksAtTheLastFewHops() {
        // a header with thousands of entries can't make us do thousands of lookups
        ClientIp ip = new ClientIp("10.0.0.0/8");
        String longChain = "198.51.100.20, " + "10.0.0.1, ".repeat(100) + "10.0.0.2";
        assertEquals("10.0.0.1", ip.resolve("10.0.0.9", longChain));
    }

    @Test
    void handlesIpv6() {
        ClientIp ip = new ClientIp("fd00::/8");
        assertEquals("2001:db8:0:0:0:0:0:1", ip.resolve("fd00::5", "2001:db8::1"));
        assertEquals("2001:db8:0:0:0:0:0:1", ip.resolve("[fd00::5]", "2001:db8::1"));
    }

    @Test
    void neverLooksUpNamesSentByAClient() {
        // parseIp must not turn "example.com" into an address with a DNS lookup
        assertNull(ClientIp.parseIp("example.com"));
        assertNull(ClientIp.parseIp("localhost"));
        assertNull(ClientIp.parseIp(""));
        assertNull(ClientIp.parseIp(null));
        assertEquals("127.0.0.1", ClientIp.parseIp("127.0.0.1").getHostAddress());
    }

    @Test
    void trustsAProxyByHostnameAndCachesTheLookup() {
        List<String> lookups = new ArrayList<>();
        InetAddress frontend = InetAddress.ofLiteral(PROXY);
        ClientIp ip = new ClientIp("frontend", host -> {
            lookups.add(host);
            return new InetAddress[] {frontend};
        }, () -> 1_000);

        assertEquals("198.51.100.20", ip.resolve(PROXY, "198.51.100.20"));
        assertEquals("198.51.100.21", ip.resolve(PROXY, "198.51.100.21"));
        assertEquals(List.of("frontend"), lookups);
    }

    @Test
    void looksTheHostnameUpAgainAfterAWhile() {
        // the frontend container can be recreated with a new address
        long[] now = {1_000};
        InetAddress[][] answer = {{InetAddress.ofLiteral(PROXY)}};
        ClientIp ip = new ClientIp("frontend", host -> answer[0], () -> now[0]);
        assertEquals("198.51.100.20", ip.resolve(PROXY, "198.51.100.20"));

        answer[0] = new InetAddress[] {InetAddress.ofLiteral("172.23.0.9")};
        now[0] += ClientIp.HOSTNAME_CACHE_MS - 1;
        // still the cached old address
        assertEquals("198.51.100.20", ip.resolve(PROXY, "198.51.100.20"));
        assertEquals("172.23.0.9", ip.resolve("172.23.0.9", "198.51.100.20"));

        now[0] += 1;
        assertEquals("198.51.100.20", ip.resolve("172.23.0.9", "198.51.100.20"));
        assertEquals(PROXY, ip.resolve(PROXY, "198.51.100.20"));
    }

    @Test
    void trustsNothingWhileAProxyHostnameCantBeFound() {
        ClientIp ip = new ClientIp("frontend", host -> new InetAddress[0], () -> 1_000);
        assertEquals(PROXY, ip.resolve(PROXY, "198.51.100.20"));
    }

    @Test
    void normalizesAddressesForTheDatabase() {
        assertEquals("127.0.0.1", ClientIp.normalize("127.0.0.1"));
        assertEquals("0:0:0:0:0:0:0:1", ClientIp.normalize("[::1]"));
        // an IPv4 address written as IPv6 is stored as plain IPv4
        assertEquals("192.0.2.1", ClientIp.normalize("::ffff:192.0.2.1"));
        assertNull(ClientIp.normalize("unknown"));
        assertNull(ClientIp.normalize("1.2.3.4'; DROP TABLE users;--"));
    }

    @Test
    void matchesCidrRangesExactly() {
        ClientIp.Range range = ClientIp.parseRange("172.16.0.0/12");
        assertTrue(range.contains(ClientIp.parseIp("172.16.0.1")));
        assertTrue(range.contains(ClientIp.parseIp("172.31.255.255")));
        assertFalse(range.contains(ClientIp.parseIp("172.32.0.0")));
        assertFalse(range.contains(ClientIp.parseIp("::1")));
    }

    @Test
    void rejectsBadSettings() {
        assertNull(ClientIp.parseRange("10.0.0.0/33"));
        assertNull(ClientIp.parseRange("10.0.0.0/x"));
        assertThrows(IllegalArgumentException.class, () -> new ClientIp("10.0.0.0/33"));
        assertThrows(IllegalArgumentException.class, () -> new ClientIp("bad host!"));
    }
}
