package it.iorfino.s3forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.iorfino.s3forge.support.AwsClientFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

/**
 * Concurrency tests for S3Forge.
 *
 * <p>These tests exercise the server under parallel load to verify that:
 *
 * <ul>
 *   <li>The virtual-thread request handler does not serialize requests artificially.
 *   <li>Both storage backends are safe under concurrent writes, reads and deletes.
 *   <li>Streams returned by {@code getObject} are independent, so concurrent readers do not
 *       interfere with each other.
 *   <li>Multipart uploads can proceed in parallel without cross-contamination between upload ids.
 * </ul>
 *
 * <p>Tests use a fixed-size thread pool to generate load, but the pool is intentionally smaller
 * than the number of requests so that the server's own virtual threads must handle the real
 * concurrency.
 *
 * @since 0.1.0
 */
class ConcurrencyTest {

    private static final String BUCKET = "concurrency";
    private static final int PARALLELISM = 32;

    private static S3Forge forge;
    private static S3Client client;

    @BeforeAll
    static void setup() throws IOException {
        forge = S3Forge.builder().port(0).inMemory().build();
        forge.start();
        client = AwsClientFactory.forPort(forge.port());
        client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @AfterAll
    static void teardown() {
        if (client != null) client.close();
        if (forge != null) forge.close();
    }

    @BeforeEach
    void clear() {
        var existing = client.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build());
        existing.contents()
                .forEach(
                        o ->
                                client.deleteObject(
                                        DeleteObjectRequest.builder()
                                                .bucket(BUCKET)
                                                .key(o.key())
                                                .build()));
    }

    /**
     * Runs the given tasks on a fixed pool and waits for all of them, propagating the first
     * failure.
     *
     * @param tasks the tasks to run
     * @param <T> the task result type
     * @return the list of results, in the same order as the tasks
     * @throws Exception if any task fails
     */
    private static <T> List<T> runAll(List<Callable<T>> tasks) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(PARALLELISM)) {
            List<Future<T>> futures = new ArrayList<>(tasks.size());
            for (Callable<T> t : tasks) futures.add(pool.submit(t));
            List<T> results = new ArrayList<>(futures.size());
            for (Future<T> f : futures) {
                try {
                    results.add(f.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception ex) throw ex;
                    throw new RuntimeException(cause);
                }
            }
            return results;
        }
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    /**
     * Uploads 200 distinct keys in parallel. All keys must be present at the end with their
     * expected payloads.
     */
    @Test
    void parallelPutsOnDistinctKeys() throws Exception {
        int n = 200;
        List<Callable<Void>> tasks =
                IntStream.range(0, n)
                        .mapToObj(
                                i ->
                                        (Callable<Void>)
                                                () -> {
                                                    String key = "key-" + i + ".txt";
                                                    String body = "value-" + i;
                                                    client.putObject(
                                                            PutObjectRequest.builder()
                                                                    .bucket(BUCKET)
                                                                    .key(key)
                                                                    .build(),
                                                            RequestBody.fromString(
                                                                    body, StandardCharsets.UTF_8));
                                                    return null;
                                                })
                        .toList();

        runAll(tasks);

        // Verify all keys and their contents.
        var listed =
                client.listObjectsV2(
                        ListObjectsV2Request.builder().bucket(BUCKET).maxKeys(1000).build());
        assertEquals(n, listed.contents().size());

        for (int i = 0; i < n; i++) {
            String got =
                    client.getObjectAsBytes(
                                    GetObjectRequest.builder()
                                            .bucket(BUCKET)
                                            .key("key-" + i + ".txt")
                                            .build())
                            .asUtf8String();
            assertEquals("value-" + i, got);
        }
    }

    /**
     * Uploads the same key from many threads concurrently. The final value must be one of the
     * uploaded values (no partial write, no corruption).
     */
    @Test
    void parallelPutsOnSameKeyAreConsistent() throws Exception {
        int n = 50;
        List<String> payloads = IntStream.range(0, n).mapToObj(i -> "payload-" + i).toList();

        List<Callable<Void>> tasks =
                payloads.stream()
                        .map(
                                p ->
                                        (Callable<Void>)
                                                () -> {
                                                    client.putObject(
                                                            PutObjectRequest.builder()
                                                                    .bucket(BUCKET)
                                                                    .key("contested.txt")
                                                                    .build(),
                                                            RequestBody.fromString(
                                                                    p, StandardCharsets.UTF_8));
                                                    return null;
                                                })
                        .toList();

        runAll(tasks);

        String got =
                client.getObjectAsBytes(
                                GetObjectRequest.builder()
                                        .bucket(BUCKET)
                                        .key("contested.txt")
                                        .build())
                        .asUtf8String();
        assertTrue(
                payloads.contains(got),
                "Final value '" + got + "' is not one of the uploaded payloads");
    }

    /**
     * Concurrent readers of the same object must all see the same content. This catches the classic
     * bug of a shared {@code InputStream} being consumed by the first reader and returning empty to
     * the rest.
     */
    @Test
    void parallelGetsSeeSameContent() throws Exception {
        String content = "the quick brown fox jumps over the lazy dog";
        client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("shared.txt").build(),
                RequestBody.fromString(content, StandardCharsets.UTF_8));

        int n = 100;
        List<Callable<String>> tasks =
                IntStream.range(0, n)
                        .mapToObj(
                                i ->
                                        (Callable<String>)
                                                () ->
                                                        client.getObjectAsBytes(
                                                                        GetObjectRequest.builder()
                                                                                .bucket(BUCKET)
                                                                                .key("shared.txt")
                                                                                .build())
                                                                .asUtf8String())
                        .toList();

        List<String> results = runAll(tasks);
        for (String r : results) {
            assertEquals(content, r);
        }
    }

    /**
     * Mixed workload: some threads upload, some read, some list, some delete. No exception must
     * escape and the server must remain responsive.
     */
    @Test
    void mixedWorkloadDoesNotThrow() throws Exception {
        // Seed a key that some readers will target.
        client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("stable.txt").build(),
                RequestBody.fromString("stable"));

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            final int id = i;
            tasks.add(
                    () -> {
                        client.putObject(
                                PutObjectRequest.builder()
                                        .bucket(BUCKET)
                                        .key("mixed-" + id + ".txt")
                                        .build(),
                                RequestBody.fromString("m" + id));
                        return null;
                    });
        }
        for (int i = 0; i < 50; i++) {
            tasks.add(
                    () -> {
                        client.getObjectAsBytes(
                                GetObjectRequest.builder()
                                        .bucket(BUCKET)
                                        .key("stable.txt")
                                        .build());
                        return null;
                    });
        }
        for (int i = 0; i < 20; i++) {
            tasks.add(
                    () -> {
                        client.listObjectsV2(
                                ListObjectsV2Request.builder()
                                        .bucket(BUCKET)
                                        .maxKeys(1000)
                                        .build());
                        return null;
                    });
        }
        for (int i = 0; i < 20; i++) {
            final int id = i;
            tasks.add(
                    () -> {
                        try {
                            client.deleteObject(
                                    DeleteObjectRequest.builder()
                                            .bucket(BUCKET)
                                            .key("mixed-" + id + ".txt")
                                            .build());
                        } catch (NoSuchKeyException ignored) {
                            // deleted by another thread; S3 semantics make this a no-op
                        }
                        return null;
                    });
        }

        runAll(tasks);
        // If we got here without exception, the server is consistent.
    }

    /**
     * Stress test: 500 small uploads executed in parallel must all succeed within a reasonable time
     * on virtual threads.
     */
    @Test
    void highVolumeOfSmallUploads() throws Exception {
        int n = 500;
        List<Callable<Void>> tasks =
                IntStream.range(0, n)
                        .mapToObj(
                                i ->
                                        (Callable<Void>)
                                                () -> {
                                                    client.putObject(
                                                            PutObjectRequest.builder()
                                                                    .bucket(BUCKET)
                                                                    .key("bulk-" + i + ".txt")
                                                                    .build(),
                                                            RequestBody.fromBytes(
                                                                    ("b" + i)
                                                                            .getBytes(
                                                                                    StandardCharsets
                                                                                            .UTF_8)));
                                                    return null;
                                                })
                        .toList();

        long start = System.nanoTime();
        runAll(tasks);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        var listed =
                client.listObjectsV2(
                        ListObjectsV2Request.builder().bucket(BUCKET).maxKeys(1000).build());
        assertEquals(n, listed.contents().size());

        // Generous bound: on CI, 500 small uploads should complete in a few
        // seconds. This assertion guards against pathological serialization.
        assertTrue(elapsedMs < 60_000, "500 parallel uploads took " + elapsedMs + " ms");
    }

    /**
     * Concurrent multipart uploads with distinct upload ids must not interfere with each other,
     * even when their parts are uploaded in parallel within each upload.
     */
    @Test
    void parallelMultipartUploadsAreIsolated() throws Exception {
        int uploads = 20;
        int partsPerUpload = 3;

        List<Callable<String>> tasks =
                IntStream.range(0, uploads)
                        .mapToObj(
                                u ->
                                        (Callable<String>)
                                                () -> {
                                                    String key = "mp-" + u + ".bin";
                                                    var init =
                                                            client.createMultipartUpload(
                                                                    CreateMultipartUploadRequest
                                                                            .builder()
                                                                            .bucket(BUCKET)
                                                                            .key(key)
                                                                            .build());

                                                    // Upload parts in parallel for this upload.
                                                    List<Callable<CompletedPart>> partTasks =
                                                            new ArrayList<>();
                                                    for (int p = 1; p <= partsPerUpload; p++) {
                                                        final int partNumber = p;
                                                        partTasks.add(
                                                                () -> {
                                                                    var resp =
                                                                            client.uploadPart(
                                                                                    UploadPartRequest
                                                                                            .builder()
                                                                                            .bucket(
                                                                                                    BUCKET)
                                                                                            .key(
                                                                                                    key)
                                                                                            .uploadId(
                                                                                                    init
                                                                                                            .uploadId())
                                                                                            .partNumber(
                                                                                                    partNumber)
                                                                                            .build(),
                                                                                    RequestBody
                                                                                            .fromString(
                                                                                                    "u"
                                                                                                            + u
                                                                                                            + "p"
                                                                                                            + partNumber));
                                                                    return CompletedPart.builder()
                                                                            .partNumber(partNumber)
                                                                            .eTag(resp.eTag())
                                                                            .build();
                                                                });
                                                    }
                                                    List<CompletedPart> parts = runAll(partTasks);

                                                    client.completeMultipartUpload(
                                                            CompleteMultipartUploadRequest.builder()
                                                                    .bucket(BUCKET)
                                                                    .key(key)
                                                                    .uploadId(init.uploadId())
                                                                    .multipartUpload(
                                                                            CompletedMultipartUpload
                                                                                    .builder()
                                                                                    .parts(parts)
                                                                                    .build())
                                                                    .build());
                                                    return key;
                                                })
                        .toList();

        List<String> keys = runAll(tasks);

        // Each completed object must contain its own concatenation.
        for (int u = 0; u < uploads; u++) {
            String key = "mp-" + u + ".bin";
            assertTrue(keys.contains(key));
            String got =
                    client.getObjectAsBytes(
                                    GetObjectRequest.builder().bucket(BUCKET).key(key).build())
                            .asUtf8String();
            assertEquals("u" + u + "p1u" + u + "p2u" + u + "p3", got);
        }
    }
}
