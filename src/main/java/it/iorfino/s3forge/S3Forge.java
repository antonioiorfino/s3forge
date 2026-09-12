package it.iorfino.s3forge;

import it.iorfino.s3forge.config.S3ForgeConfig;
import it.iorfino.s3forge.store.FileSystemStore;
import it.iorfino.s3forge.store.InMemoryStore;
import it.iorfino.s3forge.store.Store;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Public entry point for the S3Forge embedded S3 mock server.
 *
 * <p>S3Forge is a lightweight, framework-free implementation of a subset of the
 * AWS S3 HTTP API, designed for integration testing of applications that talk
 * to S3 without hitting real AWS endpoints. It runs entirely on the JDK 21
 * standard library, using {@link com.sun.net.httpserver.HttpServer} for the
 * HTTP layer and virtual threads for request handling.</p>
 *
 * <p>Two storage backends are supported:</p>
 * <ul>
 *   <li>{@link InMemoryStore} — everything is kept in RAM and lost on shutdown.</li>
 *   <li>{@link FileSystemStore} — buckets are mapped to directories under a
 *       configurable root path, so data survives across runs.</li>
 * </ul>
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 * try (S3Forge forge = S3Forge.builder()
 *         .port(8001)
 *         .inMemory()
 *         .build()) {
 *     forge.start();
 *
 *     S3Client client = S3Client.builder()
 *         .endpointOverride(URI.create("http://localhost:" + forge.port()))
 *         .region(Region.US_EAST_1)
 *         .credentialsProvider(StaticCredentialsProvider.create(
 *             AwsBasicCredentials.create("test", "test")))
 *         .forcePathStyle(true)
 *         .build();
 *
 *     client.createBucket(b -> b.bucket("my-bucket"));
 *     // ... run tests ...
 * }
 * }</pre>
 *
 * <p><strong>Thread safety:</strong> instances of this class are safe to start
 * and stop from multiple threads, but {@link #start()} must be called at most
 * once per instance.</p>
 *
 * @since 0.1.0
 */
public final class S3Forge implements AutoCloseable {

    private final S3ForgeServer server;

    /**
     * Creates a new instance. Use {@link #builder()} instead of calling this
     * constructor directly.
     *
     * @param config the runtime configuration; must not be {@code null}
     * @param store  the storage backend; must not be {@code null}
     */
    private S3Forge(S3ForgeConfig config, Store store) {
        this.server = new S3ForgeServer(config, store);
    }

    /**
     * Starts the embedded HTTP server and begins accepting requests.
     *
     * <p>If the configured port is {@code 0}, the operating system assigns an
     * ephemeral port. Use {@link #port()} after this method returns to learn
     * the actual bound port.</p>
     *
     * @throws IOException if the server socket cannot be bound or started
     * @throws IllegalStateException if the server has already been started
     */
    public void start() throws IOException {
        server.start();
    }

    /**
     * Stops the embedded HTTP server and releases the bound port.
     *
     * <p>This method is idempotent: calling it on an already-stopped instance
     * is a no-op.</p>
     */
    public void stop() {
        server.stop();
    }

    /**
     * Closes this instance, stopping the underlying server.
     *
     * <p>Equivalent to calling {@link #stop()}. Provided so that S3Forge can be
     * used in try-with-resources blocks.</p>
     */
    @Override
    public void close() {
        stop();
    }

    /**
     * Returns the actual port the server is listening on.
     *
     * <p>If {@link Builder#port(int)} was called with {@code 0}, this method
     * returns the ephemeral port chosen by the OS. It is only meaningful after
     * {@link #start()} has returned successfully.</p>
     *
     * @return the bound TCP port
     * @throws IllegalStateException if the server has not been started yet
     */
    public int port() {
        return server.port();
    }

    /**
     * Returns a new {@link Builder} for constructing an {@code S3Forge} instance.
     *
     * @return a fresh builder; never {@code null}
     */
    public static Builder builder() {
        return new Builder();
    }

    // ---------------------------------------------------------------

    /**
     * Fluent builder for {@link S3Forge} instances.
     *
     * <p>A storage backend <strong>must</strong> be configured before calling
     * {@link #build()}: either {@link #inMemory()} or
     * {@link #fileSystem(Path)}. Failing to do so results in an
     * {@link IllegalStateException}.</p>
     *
     * @since 0.1.0
     */
    public static final class Builder {

        private int port = 8001;
        private Store store;
        private String virtualHostDomain;
        private String region;

        private Builder() {}

        /**
         * Sets the TCP port the embedded server should listen on.
         *
         * <p>Use {@code 0} to let the operating system pick an ephemeral port;
         * retrieve the actual port via {@link S3Forge#port()} after startup.
         * Default is {@code 8001}.</p>
         *
         * @param port the TCP port, in the range {@code 0..65535}
         * @return this builder, for chaining
         * @throws IllegalArgumentException if {@code port} is out of range
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * Configures the server to keep all bucket and object data in memory.
         *
         * <p>Data is lost when the server is stopped. Suitable for fast,
         * isolated unit tests.</p>
         *
         * @return this builder, for chaining
         */
        public Builder inMemory() {
            this.store = new InMemoryStore();
            return this;
        }

        /**
         * Configures the server to persist bucket and object data on the
         * local filesystem, under the given root directory.
         *
         * <p>Each bucket is represented as a sub-directory of {@code rootDir},
         * and each object as a file inside it. The directory is created if it
         * does not exist.</p>
         *
         * @param rootDir the root directory for persistence; must not be
         *                {@code null}
         * @return this builder, for chaining
         */
        public Builder fileSystem(Path rootDir) {
            this.store = new FileSystemStore(rootDir);
            return this;
        }

        /**
         * Enables virtual-host style addressing for the given base domain.
         *
         * <p>When enabled, a request whose {@code Host} header is
         * {@code <bucket>.<domain>} is interpreted as targeting the bucket
         * {@code <bucket>}, and the request path is treated as relative to
         * that bucket. Requests whose {@code Host} is exactly the base domain
         * (or an IP address) continue to use path-style addressing.</p>
         *
         * <p>Typical usage in tests: {@code .virtualHostDomain("localhost")}
         * combined with a client configured to resolve
         * {@code <bucket>.localhost}.</p>
         *
         * @param domain the base domain, e.g. {@code "localhost"} or
         *               {@code "s3.test"}; must not be {@code null} or empty
         * @return this builder, for chaining
         * @throws IllegalArgumentException if {@code domain} is null/empty
         */
        public Builder virtualHostDomain(String domain) {
            if (domain == null || domain.isBlank()) {
                throw new IllegalArgumentException(
                    "virtualHostDomain must not be null or empty");
            }
            this.virtualHostDomain = domain;
            return this;
        }

        /**
         * Sets the region reported by {@code GetBucketLocation}.
         *
         * <p>When unset, the server returns an empty
         * {@code <LocationConstraint>}, which S3 clients universally
         * interpret as {@code us-east-1}.</p>
         *
         * @param region the region name, e.g. {@code "eu-west-1"}; must not
         *               be {@code null} or blank
         * @return this builder, for chaining
         * @throws IllegalArgumentException if {@code region} is null or blank
         */
        public Builder region(String region) {
            if (region == null || region.isBlank()) {
                throw new IllegalArgumentException(
                    "region must not be null or empty");
            }
            this.region = region;
            return this;
        }

        public S3Forge build() {
            if (store == null) {
                throw new IllegalStateException(
                    "No storage backend selected: call inMemory() or fileSystem(path)");
            }
            return new S3Forge(
                new S3ForgeConfig(port, virtualHostDomain, region), store);
        }
    }
}
