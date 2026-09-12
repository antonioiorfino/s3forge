package it.iorfino.s3forge;


import it.iorfino.s3forge.support.AwsClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the {@link it.iorfino.s3forge.store.FileSystemStore}
 * backend.
 *
 * <p>These tests verify that:</p>
 * <ul>
 *   <li>Buckets and objects are materialized as real directories and files
 *       on disk, in a layout that matches the path-style S3 addressing
 *       scheme.</li>
 *   <li>Data written to disk survives a server restart with the same root
 *       directory.</li>
 *   <li>Path traversal attempts in keys are rejected before touching the
 *       filesystem.</li>
 *   <li>Bucket deletion refuses to remove non-empty directories.</li>
 *   <li>Deleting an object cleans up now-empty intermediate directories.</li>
 * </ul>
 *
 * <p>Each test runs against a fresh {@link TempDir} so that tests cannot
 * interfere with one another and so that the on-disk state is inspectable
 * from the assertions.</p>
 *
 * @since 0.1.0
 */
class FileSystemStoreTest {

    @TempDir
    Path root;

    private S3Forge forge;
    private S3Client client;

    /**
     * Starts a fresh {@code S3Forge} instance on an ephemeral port, backed by
     * the per-test {@link TempDir}.
     *
     * @throws IOException if the server fails to start
     */
    @BeforeEach
    void setup() throws IOException {
        forge = S3Forge.builder().port(0).fileSystem(root).build();
        forge.start();
        client = AwsClientFactory.forPort(forge.port());
    }

    /**
     * Stops the server and closes the client, releasing all resources.
     */
    @AfterEach
    void teardown() {
        if (client != null) client.close();
        if (forge != null) forge.close();
    }

    // ------------------------------------------------------------------
    // Filesystem layout
    // ------------------------------------------------------------------

    /**
     * Verifies that creating a bucket materializes a directory named after
     * the bucket inside the configured root directory.
     */
    @Test
    void bucketIsMaterializedAsDirectory() {
        client.createBucket(CreateBucketRequest.builder().bucket("mybucket").build());

        Path expected = root.resolve("mybucket");
        assertTrue(Files.isDirectory(expected),
            "Expected directory at " + expected);
    }

    /**
     * Verifies that putting an object writes its payload to a file whose
     * path mirrors {@code <root>/<bucket>/<key>}, and that the file content
     * matches the uploaded bytes.
     *
     * @throws IOException if reading the file back fails
     */
    @Test
    void objectIsWrittenToDisk() throws IOException {
        client.createBucket(CreateBucketRequest.builder().bucket("disk").build());
        client.putObject(PutObjectRequest.builder()
                .bucket("disk").key("hello.txt").build(),
            RequestBody.fromString("hi"));

        Path expected = root.resolve("disk").resolve("hello.txt");
        assertTrue(Files.isRegularFile(expected),
            "Expected file at " + expected);
        assertEquals("hi", Files.readString(expected));
    }

    /**
     * Verifies that keys containing slashes produce the expected nested
     * directory structure on disk, with forward slashes mapped to path
     * separators.
     *
     * @throws IOException if reading the file back fails
     */
    @Test
    void nestedKeyCreatesIntermediates() throws IOException {
        client.createBucket(CreateBucketRequest.builder().bucket("nested").build());
        client.putObject(PutObjectRequest.builder()
                .bucket("nested").key("a/b/c/file.txt").build(),
            RequestBody.fromString("deep"));

        Path expected = root.resolve("nested").resolve("a").resolve("b")
            .resolve("c").resolve("file.txt");
        assertTrue(Files.isRegularFile(expected),
            "Expected file at " + expected);
        assertEquals("deep", Files.readString(expected));
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /**
     * Verifies that objects written by one server instance are readable by a
     * second instance started on the same root directory, simulating a
     * process restart.
     *
     * @throws IOException if the second server fails to start
     */
    @Test
    void dataSurvivesRestart() throws IOException {
        client.createBucket(CreateBucketRequest.builder().bucket("persist").build());
        client.putObject(PutObjectRequest.builder()
                .bucket("persist").key("survive.txt").build(),
            RequestBody.fromString("survived"));

        // Simulate a restart: shut down, start again on the same root.
        forge.close();
        client.close();

        forge = S3Forge.builder().port(0).fileSystem(root).build();
        forge.start();
        client = AwsClientFactory.forPort(forge.port());

        String content = client.getObjectAsBytes(GetObjectRequest.builder()
            .bucket("persist").key("survive.txt").build()).asUtf8String();
        assertEquals("survived", content);
    }

    /**
     * Verifies that buckets created before a restart are still listed after
     * the server is restarted on the same root directory.
     *
     * @throws IOException if the second server fails to start
     */
    @Test
    void bucketsSurviveRestart() throws IOException {
        client.createBucket(CreateBucketRequest.builder().bucket("alpha").build());
        client.createBucket(CreateBucketRequest.builder().bucket("beta").build());

        forge.close();
        client.close();

        forge = S3Forge.builder().port(0).fileSystem(root).build();
        forge.start();
        client = AwsClientFactory.forPort(forge.port());

        List<String> names = client.listBuckets().buckets().stream()
            .map(b -> b.name()).sorted().toList();
        assertEquals(List.of("alpha", "beta"), names);
    }

    // ------------------------------------------------------------------
    // Security
    // ------------------------------------------------------------------

    /**
     * Verifies that a key containing a path-traversal sequence
     * ({@code ../}) does not allow writing outside the configured root
     * directory. The server must reject the request before touching the
     * filesystem.
     *
     * <p>Note: the AWS SDK may normalize or reject some keys before sending
     * them; this test asserts that, whatever reaches the server, no file
     * escapes the root.</p>
     */
    @Test
    void pathTraversalIsRejected() {
        client.createBucket(CreateBucketRequest.builder().bucket("sec").build());

        // Attempt to escape the bucket directory via a crafted key.
        assertThrows(Exception.class, () ->
            client.putObject(PutObjectRequest.builder()
                    .bucket("sec").key("../escaped.txt").build(),
                RequestBody.fromString("should-not-exist")));

        Path escaped = root.getParent().resolve("escaped.txt");
        assertFalse(Files.exists(escaped),
            "Path traversal escaped the root directory: " + escaped);
    }

    // ------------------------------------------------------------------
    // Bucket semantics
    // ------------------------------------------------------------------

    /**
     * Verifies that deleting a bucket which still contains objects fails
     * with {@code BucketNotEmpty} and leaves the directory intact.
     */
    @Test
    void deleteNonEmptyBucketFails() {
        client.createBucket(CreateBucketRequest.builder().bucket("full").build());
        client.putObject(PutObjectRequest.builder()
                .bucket("full").key("x.txt").build(),
            RequestBody.fromString("data"));

        S3Exception ex = assertThrows(S3Exception.class, () ->
            client.deleteBucket(DeleteBucketRequest.builder()
                .bucket("full").build()));

        // S3 restituisce HTTP 409 per BucketNotEmpty [citation:1][citation:16]
        assertEquals(409, ex.statusCode());
        assertEquals("BucketNotEmpty", ex.awsErrorDetails().errorCode());

        assertTrue(Files.isDirectory(root.resolve("full")),
            "Bucket directory must not have been removed");
    }

    /**
     * Verifies that creating a bucket that already exists is idempotent and
     * does not fail.
     */
    @Test
    void createExistingBucketIsIdempotent() {
        client.createBucket(CreateBucketRequest.builder().bucket("idem").build());
        // Second call must not throw.
        client.createBucket(CreateBucketRequest.builder().bucket("idem").build());

        assertTrue(Files.isDirectory(root.resolve("idem")));
    }

    // ------------------------------------------------------------------
    // Object semantics
    // ------------------------------------------------------------------

    /**
     * Verifies that deleting an object removes its file from disk and cleans
     * up any intermediate directories that become empty as a result.
     *
     * @throws IOException if stat calls fail
     */
    @Test
    void deleteObjectCleansEmptyDirectories() throws IOException {
        client.createBucket(CreateBucketRequest.builder().bucket("clean").build());
        client.putObject(PutObjectRequest.builder()
                .bucket("clean").key("a/b/c/file.txt").build(),
            RequestBody.fromString("data"));

        Path dirC = root.resolve("clean").resolve("a").resolve("b").resolve("c");
        assertTrue(Files.isDirectory(dirC));

        client.deleteObject(DeleteObjectRequest.builder()
            .bucket("clean").key("a/b/c/file.txt").build());

        assertFalse(Files.exists(dirC),
            "Empty intermediate directory should have been removed");
    }

    /**
     * Verifies that deleting a non-existent object is a no-op and does not
     * raise an error, matching S3 semantics.
     */
    @Test
    void deleteMissingObjectIsIdempotent() {
        client.createBucket(CreateBucketRequest.builder().bucket("idem2").build());

        // Must not throw.
        client.deleteObject(DeleteObjectRequest.builder()
            .bucket("idem2").key("never-existed.txt").build());
    }

    /**
     * Verifies that getting a non-existent key from an existing bucket
     * produces {@code NoSuchKey}.
     */
    @Test
    void getMissingObjectThrows() {
        client.createBucket(CreateBucketRequest.builder().bucket("getmiss").build());

        assertThrows(NoSuchKeyException.class, () ->
            client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket("getmiss").key("missing.txt").build()));
    }

    // ------------------------------------------------------------------
    // Listing parity with InMemoryStore
    // ------------------------------------------------------------------

    /**
     * Verifies that {@code listObjectsV2} returns the same set of keys as the
     * in-memory backend for a simple flat listing.
     */
    @Test
    void listObjectsMatchesInMemorySemantics() {
        client.createBucket(CreateBucketRequest.builder().bucket("list").build());
        for (String key : List.of("a.txt", "b.txt", "c.txt")) {
            client.putObject(PutObjectRequest.builder()
                    .bucket("list").key(key).build(),
                RequestBody.fromString("data"));
        }

        List<String> keys = client.listObjectsV2(ListObjectsV2Request.builder()
                .bucket("list").build())
            .contents().stream()
            .map(o -> o.key())
            .toList();

        assertEquals(List.of("a.txt", "b.txt", "c.txt"), keys);
    }

    /**
     * Verifies that delimiter grouping works identically to the in-memory
     * backend when running against the filesystem.
     */
    @Test
    void listObjectsWithDelimiterGroupsPrefixes() {
        client.createBucket(CreateBucketRequest.builder().bucket("grp").build());
        for (String key : List.of("a/1.txt", "a/2.txt", "b/1.txt", "top.txt")) {
            client.putObject(PutObjectRequest.builder()
                    .bucket("grp").key(key).build(),
                RequestBody.fromString("x"));
        }

        var res = client.listObjectsV2(ListObjectsV2Request.builder()
            .bucket("grp").delimiter("/").build());

        List<String> keys = res.contents().stream().map(o -> o.key()).toList();
        List<String> prefixes = res.commonPrefixes().stream()
            .map(p -> p.prefix()).toList();

        assertEquals(List.of("top.txt"), keys);
        assertEquals(List.of("a/", "b/"), prefixes);
    }
}
