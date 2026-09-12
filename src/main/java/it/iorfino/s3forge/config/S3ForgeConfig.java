package it.iorfino.s3forge.config;

import java.util.Optional;

/**
 * Immutable configuration for an {@link it.iorfino.s3forge.S3Forge} instance.
 *
 * @param port              the TCP port the embedded HTTP server should bind
 *                          to; {@code 0} means "let the OS choose an
 *                          ephemeral port"
 * @param virtualHostDomain the base domain used to detect virtual-host style
 *                          requests, or {@code null} to disable
 * @param region            the region returned by {@code GetBucketLocation};
 *                          may be {@code null} or empty, in which case S3
 *                          clients interpret the response as
 *                          {@code us-east-1}
 * @since 0.1.0
 */
public record S3ForgeConfig(int port, String virtualHostDomain, String region) {

    /**
     * Canonical constructor. Validates the port range and normalizes
     * string fields.
     *
     * @throws IllegalArgumentException if {@code port} is outside
     *                                  {@code 0..65535}
     */
    public S3ForgeConfig {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        if (virtualHostDomain != null) {
            virtualHostDomain = virtualHostDomain.trim().toLowerCase();
            if (virtualHostDomain.isEmpty()) virtualHostDomain = null;
        }
        if (region != null) {
            region = region.trim();
            if (region.isEmpty()) region = null;
        }
    }

    /**
     * Convenience constructor for path-style, no-region configurations.
     *
     * @param port the TCP port
     */
    public S3ForgeConfig(int port) {
        this(port, null, null);
    }

    /**
     * Returns the configured virtual-host base domain, if any.
     *
     * @return an {@link Optional} containing the domain, or
     *         {@link Optional#empty()} when virtual-host addressing is
     *         disabled
     */
    public Optional<String> virtualHostDomainOpt() {
        return Optional.ofNullable(virtualHostDomain);
    }

    /**
     * Returns the configured region, if any.
     *
     * @return an {@link Optional} containing the region, or
     *         {@link Optional#empty()} to let clients default to
     *         {@code us-east-1}
     */
    public Optional<String> regionOpt() {
        return Optional.ofNullable(region);
    }
}
