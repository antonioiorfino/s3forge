package it.iorfino.s3forge.bench;

import it.iorfino.s3forge.S3Forge;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Shared infrastructure for S3Forge JMH benchmarks.
 *
 * <p>Each benchmark runs S3Forge in the same JVM as the client, so that
 * measured latency reflects S3Forge's own overhead and not loopback
 * networking. A single {@link S3Forge} instance and a single
 * {@link S3Client} are created per benchmark trial, then torn down at the
 * end.</p>
 *
 * <p>The AWS SDK is configured with path-style addressing, fake static
 * credentials, and the US-EAST-1 region, matching the configuration used
 * by the integration tests.</p>
 *
 * @since 0.3.0
 */
public final class BenchmarkSupport {

    /** Payload sizes used by the benchmarks, in bytes. */
    public static final int SIZE_1_KB   = 1 * 1024;
    public static final int SIZE_100_KB = 100 * 1024;
    public static final int SIZE_1_MB   = 1 * 1024 * 1024;

    private BenchmarkSupport() {
        // utility class
    }

    /**
     * Starts an in-memory S3Forge on an ephemeral port and returns a client
     * pointing to it.
     *
     * @return a running server and its matching client
     * @throws IOException if the server fails to start
     */
    public static Running startInMemory() throws IOException {
        S3Forge forge = S3Forge.builder().port(0).inMemory().build();
        forge.start();
        return new Running(forge, clientFor(forge.port()), null);
    }

    /**
     * Starts a filesystem-backed S3Forge on an ephemeral port and returns a
     * client pointing to it. The temporary directory is created by this
     * method and removed by {@link Running#close()}.
     *
     * @return a running server, its matching client, and the temp directory
     * @throws IOException if the server fails to start or the temp dir
     *                     cannot be created
     */
    public static Running startFileSystem() throws IOException {
        Path root = Files.createTempDirectory("s3forge-bench-");
        S3Forge forge = S3Forge.builder().port(0).fileSystem(root).build();
        forge.start();
        return new Running(forge, clientFor(forge.port()), root);
    }

    /**
     * Builds an S3 client pointed at the given local port.
     *
     * @param port the local port where S3Forge is listening
     * @return a configured {@link S3Client}
     */
    private static S3Client clientFor(int port) {
        return S3Client.builder()
            .endpointOverride(URI.create("http://localhost:" + port))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("bench", "bench")))
            .forcePathStyle(true)
            .build();
    }

    /**
     * Creates a bucket and preloads it with the given number of small
     * objects, using keys of the form {@code key-000000}.
     *
     * @param client the S3 client
     * @param bucket the bucket name
     * @param count  number of objects to preload
     */
    public static void seedBucket(S3Client client, String bucket, int count) {
        client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        for (int i = 0; i < count; i++) {
            client.putObject(PutObjectRequest.builder()
                    .bucket(bucket).key(key(i)).build(),
                RequestBody.fromString("seed"));
        }
    }

    /**
     * Empties a bucket by deleting every object it contains.
     *
     * @param client the S3 client
     * @param bucket the bucket name
     */
    public static void emptyBucket(S3Client client, String bucket) {
        var listed = client.listObjectsV2(
            ListObjectsV2Request.builder().bucket(bucket).build());
        listed.contents().forEach(o ->
            client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket).key(o.key()).build()));
    }

    /**
     * Returns a zero-padded object key for the given index.
     *
     * @param i the index
     * @return a key like {@code key-000042}
     */
    public static String key(int i) {
        return String.format("key-%06d", i);
    }

    /**
     * Returns a deterministic payload of the given size, filled with a
     * repeating pattern so that compression or deduplication cannot skew
     * measurements.
     *
     * @param size the payload size in bytes
     * @return a byte array of exactly {@code size} bytes
     */
    public static byte[] payload(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ('a' + (i % 26));
        }
        return data;
    }

    /**
     * A running S3Forge instance and its matching client, closed together.
     *
     * @param forge   the server
     * @param client  the S3 client
     * @param dataDir the filesystem root, or {@code null} for in-memory runs
     */
    public record Running(S3Forge forge, S3Client client, Path dataDir)
        implements AutoCloseable {

        @Override
        public void close() {
            client.close();
            forge.close();
            if (dataDir != null) {
                try (var walk = Files.walk(dataDir)) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); }
                            catch (IOException ignored) { /* best effort */ }
                        });
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            }
        }
    }
}
