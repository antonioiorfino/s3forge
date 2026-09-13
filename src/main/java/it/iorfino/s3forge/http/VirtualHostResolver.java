package it.iorfino.s3forge.http;

import java.util.Optional;

/**
 * Resolves the effective bucket from the {@code Host} header when virtual-host style addressing is
 * enabled.
 *
 * <p>For a configured base domain {@code D}, a request is considered virtual-host style when its
 * {@code Host} header (with any port stripped) ends with {@code .D} and has a non-empty prefix. The
 * prefix is returned as the bucket name. Any other host — including plain {@code D}, IP addresses,
 * and hosts unrelated to {@code D} — falls back to path-style addressing, in which case this
 * resolver returns {@link Optional#empty()}.
 *
 * <p>Examples with {@code D = "localhost"}:
 *
 * <ul>
 *   <li>{@code Host: photos.localhost} → bucket {@code photos}
 *   <li>{@code Host: photos.localhost:8001} → bucket {@code photos}
 *   <li>{@code Host: a.b.localhost} → bucket {@code a.b}
 *   <li>{@code Host: localhost} → path-style
 *   <li>{@code Host: localhost:8001} → path-style
 *   <li>{@code Host: 127.0.0.1} → path-style
 *   <li>{@code Host: example.com} → path-style
 * </ul>
 *
 * @since 0.1.0
 */
public final class VirtualHostResolver {

    private final String baseDomain;

    /**
     * Creates a new resolver.
     *
     * @param baseDomain the base domain, already lowercased and trimmed; must not be {@code null}
     *     or empty
     */
    public VirtualHostResolver(String baseDomain) {
        if (baseDomain == null || baseDomain.isBlank()) {
            throw new IllegalArgumentException("baseDomain must not be blank");
        }
        this.baseDomain = baseDomain.toLowerCase();
    }

    /**
     * Extracts the bucket name from a {@code Host} header value, if the host is a virtual-host
     * style subdomain of the configured base domain.
     *
     * @param hostHeader the raw value of the {@code Host} header; may be {@code null}
     * @return an {@link Optional} containing the bucket name, or {@link Optional#empty()} if the
     *     request uses path-style addressing
     */
    public Optional<String> bucketFromHost(String hostHeader) {
        if (hostHeader == null || hostHeader.isEmpty()) {
            return Optional.empty();
        }

        String host = hostHeader;

        // Strip port, being careful about IPv6 literals like [::1]:8001.
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            if (end > 0) host = host.substring(0, end + 1);
        } else {
            int colon = host.lastIndexOf(':');
            if (colon >= 0) host = host.substring(0, colon);
        }

        host = host.toLowerCase();

        String suffix = "." + baseDomain;
        if (!host.endsWith(suffix)) {
            return Optional.empty();
        }

        String bucket = host.substring(0, host.length() - suffix.length());
        if (bucket.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(bucket);
    }
}
