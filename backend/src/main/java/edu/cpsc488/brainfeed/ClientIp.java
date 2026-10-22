package edu.cpsc488.brainfeed;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Works out the real IP address of whoever sent a request.
 *
 * <p>The browser never talks to the backend directly: it goes through a reverse proxy (Vite in
 * development, a web server in production), so the connection always comes from the proxy. The
 * proxy writes the address it saw into the {@code X-Forwarded-For} header. Anyone can send that
 * header, though, so it's only believed when the connection comes from a proxy listed in
 * {@code TRUSTED_PROXIES}, and it's read from the right: each trusted proxy appends the address it
 * saw, so the first untrusted address from the right is the real client. Anything further left was
 * written by the client and could be made up (OWASP: don't trust client-supplied IP headers).
 *
 * <p>The rate limits, the security log and the IPs shown on the admin page all come from here.
 */
public final class ClientIp {

    // A real chain is a few hops long; more than this is junk or an attempt to waste our time.
    static final int MAX_HOPS = 20;
    // Proxy hostnames (like "frontend" in Docker) are looked up again after this long, in case the
    // container was recreated with a new address.
    static final long HOSTNAME_CACHE_MS = 30_000;

    /** An IP address or CIDR range like 172.16.0.0/12. */
    record Range(byte[] network, int prefixLength) {

        boolean contains(InetAddress address) {
            byte[] bytes = address.getAddress();
            if (bytes.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (bytes[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (bytes[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    private final List<Range> ranges = new ArrayList<>();
    private final List<String> hostnames = new ArrayList<>();
    private final Function<String, InetAddress[]> resolver;
    private final LongSupplier clock;
    private volatile InetAddress[] resolvedHosts = new InetAddress[0];
    private volatile long nextLookupAt = 0;

    /**
     * @param trustedProxies comma-separated IPs, CIDR ranges or hostnames of our own proxies, from
     *                       the TRUSTED_PROXIES setting. Blank trusts nobody, so the connection's
     *                       address is used as-is (right when nothing sits in front of the backend).
     */
    public ClientIp(String trustedProxies) {
        this(trustedProxies, ClientIp::lookUp, System::currentTimeMillis);
    }

    /** For tests: {@code resolver} stands in for DNS and {@code clock} for the time in milliseconds. */
    ClientIp(String trustedProxies, Function<String, InetAddress[]> resolver, LongSupplier clock) {
        this.resolver = resolver;
        this.clock = clock;
        if (trustedProxies == null) {
            return;
        }
        for (String entry : trustedProxies.split(",")) {
            String value = entry.trim();
            if (value.isEmpty()) {
                continue;
            }
            Range range = parseRange(value);
            if (range != null) {
                ranges.add(range);
            } else if (value.matches("[A-Za-z0-9.-]+")) {
                hostnames.add(value);
            } else {
                throw new IllegalArgumentException("TRUSTED_PROXIES has an entry that isn't an IP, a range or a hostname: " + value);
            }
        }
    }

    /**
     * @param remoteAddress the address the connection actually came from
     * @param forwardedFor  the X-Forwarded-For header, or null
     */
    public String resolve(String remoteAddress, String forwardedFor) {
        InetAddress remote = parseIp(remoteAddress);
        if (remote == null || !isTrusted(remote)) {
            return remoteAddress;
        }
        String client = remote.getHostAddress();
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return client;
        }
        String[] hops = forwardedFor.split(",");
        int stop = Math.max(0, hops.length - MAX_HOPS);
        for (int i = hops.length - 1; i >= stop; i--) {
            InetAddress hop = parseIp(hops[i].trim());
            if (hop == null) {
                // A garbled entry: we can't tell who's behind it, so stop at the last address we trust.
                return client;
            }
            client = hop.getHostAddress();
            if (!isTrusted(hop)) {
                return client;
            }
        }
        return client;
    }

    private boolean isTrusted(InetAddress address) {
        for (Range range : ranges) {
            if (range.contains(address)) {
                return true;
            }
        }
        for (InetAddress host : resolvedHosts()) {
            if (Arrays.equals(host.getAddress(), address.getAddress())) {
                return true;
            }
        }
        return false;
    }

    private InetAddress[] resolvedHosts() {
        if (hostnames.isEmpty()) {
            return resolvedHosts;
        }
        long now = clock.getAsLong();
        if (now >= nextLookupAt) {
            List<InetAddress> found = new ArrayList<>();
            for (String host : hostnames) {
                found.addAll(Arrays.asList(resolver.apply(host)));
            }
            resolvedHosts = found.toArray(InetAddress[]::new);
            nextLookupAt = now + HOSTNAME_CACHE_MS;
        }
        return resolvedHosts;
    }

    /** Only our own configured hostnames are ever looked up, never anything a client sent. */
    private static InetAddress[] lookUp(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            // Not up yet (e.g. the frontend container starts after the backend). Trust nothing for now.
            return new InetAddress[0];
        }
    }

    /**
     * Parses an IP address without ever doing a DNS lookup, so a client can't make us look up
     * names by putting them in a header. Returns null if it isn't an IP address.
     */
    static InetAddress parseIp(String value) {
        if (value == null || value.isEmpty() || value.length() > 47) {
            return null;
        }
        // IPv6 sometimes comes in brackets, like [::1]
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            return InetAddress.ofLiteral(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The address in standard form for storing in the database, or null if it isn't an IP address,
     * so a strange value can never make an INSERT fail.
     */
    public static String normalize(String value) {
        InetAddress address = parseIp(value);
        return address == null ? null : address.getHostAddress();
    }

    static Range parseRange(String value) {
        int slash = value.indexOf('/');
        InetAddress address = parseIp(slash < 0 ? value : value.substring(0, slash));
        if (address == null) {
            return null;
        }
        int maxBits = address.getAddress().length * 8;
        int prefix = maxBits;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(value.substring(slash + 1));
            } catch (NumberFormatException e) {
                return null;
            }
            if (prefix < 0 || prefix > maxBits) {
                return null;
            }
        }
        return new Range(address.getAddress(), prefix);
    }
}
