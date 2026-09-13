package it.iorfino.s3forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.iorfino.s3forge.support.AwsClientFactory;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

/**
 * End-to-end tests for the {@code partNumber} query parameter on {@code GetObject} and {@code
 * HeadObject}.
 *
 * <p>These tests verify the S3-compatible behavior for multipart objects:
 *
 * <ul>
 *   <li>{@code GET ?partNumber=N} returns only that part's bytes, with {@code 206 Partial Content},
 *       {@code Content-Range} reflecting the part's range within the whole object, and {@code
 *       x-amz-mp-parts-count} set to the total part count.
 *   <li>The {@code ETag} returned is always the object's own ETag, never the part's.
 *   <li>An out-of-range part number produces {@code 416 InvalidPartNumber}.
 *   <li>On a single-part object, {@code partNumber=1} returns the whole object as {@code 200 OK},
 *       while {@code partNumber>1} produces {@code 400 InvalidPart}.
 * </ul>
 *
 * <p>The in-memory backend is used for the core tests. The persistence of part descriptors across
 * restarts is covered separately, using the filesystem backend.
 *
 * @since 0.2.0
 */
class GetObjectPartNumberTest {

    private static final String BUCKET = "part-number-tests";

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
        client.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build())
                .contents()
                .forEach(
                        o ->
                                client.deleteObject(
                                        DeleteObjectRequest.builder()
                                                .bucket(BUCKET)
                                                .key(o.key())
                                                .build()));
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    /**
     * Creates a three-part multipart object with parts "AAA", "BBB", "CCC" under the given key.
     *
     * @param key the destination key
     * @return the ETag of the completed object
     */
    private String createThreePartObject(String key) {
        var init =
                client.createMultipartUpload(
                        CreateMultipartUploadRequest.builder().bucket(BUCKET).key(key).build());

        var p1 =
                client.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(BUCKET)
                                .key(key)
                                .uploadId(init.uploadId())
                                .partNumber(1)
                                .build(),
                        RequestBody.fromString("AAA"));
        var p2 =
                client.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(BUCKET)
                                .key(key)
                                .uploadId(init.uploadId())
                                .partNumber(2)
                                .build(),
                        RequestBody.fromString("BBB"));
        var p3 =
                client.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(BUCKET)
                                .key(key)
                                .uploadId(init.uploadId())
                                .partNumber(3)
                                .build(),
                        RequestBody.fromString("CCC"));

        var completed =
                client.completeMultipartUpload(
                        CompleteMultipartUploadRequest.builder()
                                .bucket(BUCKET)
                                .key(key)
                                .uploadId(init.uploadId())
                                .multipartUpload(
                                        CompletedMultipartUpload.builder()
                                                .parts(
                                                        CompletedPart.builder()
                                                                .partNumber(1)
                                                                .eTag(p1.eTag())
                                                                .build(),
                                                        CompletedPart.builder()
                                                                .partNumber(2)
                                                                .eTag(p2.eTag())
                                                                .build(),
                                                        CompletedPart.builder()
                                                                .partNumber(3)
                                                                .eTag(p3.eTag())
                                                                .build())
                                                .build())
                                .build());
        return completed.eTag();
    }

    // ------------------------------------------------------------------
    // GetObject
    // ------------------------------------------------------------------

    /**
     * Verifies that {@code GET ?partNumber=1} on a three-part object returns only the first part's
     * bytes, with the correct status code, content range, and content length.
     */
    @Test
    void getPartReturnsOnlyThatPart() {
        createThreePartObject("three.bin");

        ResponseBytes<GetObjectResponse> part1 =
                client.getObjectAsBytes(
                        GetObjectRequest.builder()
                                .bucket(BUCKET)
                                .key("three.bin")
                                .partNumber(1)
                                .build());

        assertEquals("AAA", part1.asUtf8String());
        assertEquals(206, part1.response().sdkHttpResponse().statusCode());
        assertEquals("bytes 0-2/9", part1.response().contentRange());
        assertEquals(3, part1.response().contentLength());
    }

    /** Verifies that part 2 and part 3 are resolved correctly, with the expected byte ranges. */
    @Test
    void getPart2And3() {
        createThreePartObject("three.bin");

        var p2 =
                client.getObjectAsBytes(
                        GetObjectRequest.builder()
                                .bucket(BUCKET)
                                .key("three.bin")
                                .partNumber(2)
                                .build());
        assertEquals("BBB", p2.asUtf8String());
        assertEquals("bytes 3-5/9", p2.response().contentRange());

        var p3 =
                client.getObjectAsBytes(
                        GetObjectRequest.builder()
                                .bucket(BUCKET)
                                .key("three.bin")
                                .partNumber(3)
                                .build());
        assertEquals("CCC", p3.asUtf8String());
        assertEquals("bytes 6-8/9", p3.response().contentRange());
    }

    /**
     * Verifies that the {@code x-amz-mp-parts-count} response header is present and carries the
     * total number of parts.
     */
    @Test
    void partsCountHeaderIsPresent() {
        createThreePartObject("three.bin");

        var res =
                client.getObjectAsBytes(
                        GetObjectRequest.builder()
                                .bucket(BUCKET)
                                .key("three.bin")
                                .partNumber(1)
                                .build());

        String count =
                res.response()
                        .sdkHttpResponse()
                        .firstMatchingHeader("x-amz-mp-parts-count")
                        .orElse(null);
        assertEquals("3", count);
    }

    /**
     * Verifies that the ETag returned for a part request is the object's own multipart ETag ({@code
     * <md5-of-md5s>-<N>}), not the part's ETag. This matches real S3 behavior.
     */
    @Test
    void etagIsObjectLevel() {
        String objectEtag = createThreePartObject("three.bin");

        var res =
                client.getObjectAsBytes(
                        GetObjectRequest.builder()
                                .bucket(BUCKET)
                                .key("three.bin")
                                .partNumber(1)
                                .build());

        assertEquals(objectEtag, res.response().eTag());
        assertTrue(
                objectEtag.matches("\"[0-9a-f]{32}-3\""),
                "Unexpected multipart ETag format: " + objectEtag);
    }

    /** Verifies that an out-of-range part number produces {@code 416 InvalidPartNumber}. */
    @Test
    void outOfRangePartReturnsInvalidPartNumber() {
        createThreePartObject("three.bin");

        var ex =
                assertThrows(
                        S3Exception.class,
                        () ->
                                client.getObjectAsBytes(
                                        GetObjectRequest.builder()
                                                .bucket(BUCKET)
                                                .key("three.bin")
                                                .partNumber(99)
                                                .build()));

        assertEquals(416, ex.statusCode());
    }

    /**
     * Verifies that, on a single-part object, {@code partNumber=1} returns the whole object as
     * {@code 200 OK}, matching AWS behavior.
     */
    @Test
    void nonMultipartPartNumber1ReturnsWholeObject() {
        client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("single.txt").build(),
                RequestBody.fromString("whole"));

        var res =
                client.getObjectAsBytes(
                        GetObjectRequest.builder()
                                .bucket(BUCKET)
                                .key("single.txt")
                                .partNumber(1)
                                .build());

        assertEquals("whole", res.asUtf8String());
        assertEquals(200, res.response().sdkHttpResponse().statusCode());
    }

    /**
     * Verifies that, on a single-part object, {@code partNumber>1} produces {@code 400
     * InvalidPart}.
     */
    @Test
    void nonMultipartPartNumberGreaterThan1Fails() {
        client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("single2.txt").build(),
                RequestBody.fromString("whole"));

        var ex =
                assertThrows(
                        S3Exception.class,
                        () ->
                                client.getObjectAsBytes(
                                        GetObjectRequest.builder()
                                                .bucket(BUCKET)
                                                .key("single2.txt")
                                                .partNumber(2)
                                                .build()));

        assertEquals(400, ex.statusCode());
    }

    // ------------------------------------------------------------------
    // HeadObject
    // ------------------------------------------------------------------

    /**
     * Verifies that {@code HEAD ?partNumber=N} returns the part's range and length headers without
     * a body.
     */
    @Test
    void headPartReturnsMetadataNoBody() {
        createThreePartObject("three.bin");

        HeadObjectResponse head =
                client.headObject(
                        HeadObjectRequest.builder()
                                .bucket(BUCKET)
                                .key("three.bin")
                                .partNumber(1)
                                .build());

        assertEquals(3, head.contentLength());
        assertEquals("bytes 0-2/9", head.contentRange());
        assertEquals(
                "3",
                head.sdkHttpResponse().firstMatchingHeader("x-amz-mp-parts-count").orElse(null));
    }

    /**
     * Verifies that {@code HEAD} with an out-of-range part number produces {@code 416
     * InvalidPartNumber}, matching {@code GET}.
     */
    @Test
    void headOutOfRangePartFails() {
        createThreePartObject("three.bin");

        var ex =
                assertThrows(
                        S3Exception.class,
                        () ->
                                client.headObject(
                                        HeadObjectRequest.builder()
                                                .bucket(BUCKET)
                                                .key("three.bin")
                                                .partNumber(99)
                                                .build()));

        assertEquals(416, ex.statusCode());
    }

    /**
     * Verifies that {@code HEAD} with {@code partNumber>1} on a single-part object produces {@code
     * 400 InvalidPart}, matching {@code GET}.
     */
    @Test
    void headSinglePartGreaterThanOneFails() {
        client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("single-head.txt").build(),
                RequestBody.fromString("whole"));

        var ex =
                assertThrows(
                        S3Exception.class,
                        () ->
                                client.headObject(
                                        HeadObjectRequest.builder()
                                                .bucket(BUCKET)
                                                .key("single-head.txt")
                                                .partNumber(2)
                                                .build()));

        assertEquals(400, ex.statusCode());
    }

    // ------------------------------------------------------------------
    // Filesystem persistence
    // ------------------------------------------------------------------

    /**
     * Verifies that multipart part descriptors survive a server restart on the filesystem backend,
     * so that {@code GetObject?partNumber=N} keeps working after a restart.
     *
     * <p>This exercises the {@code part.<N>.*} serialization in the sidecar metadata file.
     *
     * @param root the temporary root directory for the test
     * @throws IOException if a server fails to start
     */
    @Test
    void partsSurviveRestartOnFilesystem(@TempDir Path root) throws IOException {
        // First run: create the multipart object.
        try (S3Forge fsForge = S3Forge.builder().port(0).fileSystem(root).build()) {
            fsForge.start();
            try (S3Client fsClient = AwsClientFactory.forPort(fsForge.port())) {
                fsClient.createBucket(CreateBucketRequest.builder().bucket("pnp").build());

                var init =
                        fsClient.createMultipartUpload(
                                CreateMultipartUploadRequest.builder()
                                        .bucket("pnp")
                                        .key("three.bin")
                                        .build());
                var p1 =
                        fsClient.uploadPart(
                                UploadPartRequest.builder()
                                        .bucket("pnp")
                                        .key("three.bin")
                                        .uploadId(init.uploadId())
                                        .partNumber(1)
                                        .build(),
                                RequestBody.fromString("AAA"));
                var p2 =
                        fsClient.uploadPart(
                                UploadPartRequest.builder()
                                        .bucket("pnp")
                                        .key("three.bin")
                                        .uploadId(init.uploadId())
                                        .partNumber(2)
                                        .build(),
                                RequestBody.fromString("BBB"));
                fsClient.completeMultipartUpload(
                        CompleteMultipartUploadRequest.builder()
                                .bucket("pnp")
                                .key("three.bin")
                                .uploadId(init.uploadId())
                                .multipartUpload(
                                        CompletedMultipartUpload.builder()
                                                .parts(
                                                        CompletedPart.builder()
                                                                .partNumber(1)
                                                                .eTag(p1.eTag())
                                                                .build(),
                                                        CompletedPart.builder()
                                                                .partNumber(2)
                                                                .eTag(p2.eTag())
                                                                .build())
                                                .build())
                                .build());
            }
        }

        // Second run: read a part from the same root.
        try (S3Forge fsForge = S3Forge.builder().port(0).fileSystem(root).build()) {
            fsForge.start();
            try (S3Client fsClient = AwsClientFactory.forPort(fsForge.port())) {
                var part2 =
                        fsClient.getObjectAsBytes(
                                GetObjectRequest.builder()
                                        .bucket("pnp")
                                        .key("three.bin")
                                        .partNumber(2)
                                        .build());

                assertEquals("BBB", part2.asUtf8String());
                assertEquals("bytes 3-5/6", part2.response().contentRange());
                assertEquals(
                        "2",
                        part2.response()
                                .sdkHttpResponse()
                                .firstMatchingHeader("x-amz-mp-parts-count")
                                .orElse(null));
            }
        }
    }

    /**
     * Verifies that {@code HEAD ?partNumber=N} returns the part's range and length headers without
     * a body, matching the behavior of GET.
     */
    @Test
    void headPartReturnsRangeHeaders() {
        createThreePartObject("head-range.bin");

        var head =
                client.headObject(
                        HeadObjectRequest.builder()
                                .bucket(BUCKET)
                                .key("head-range.bin")
                                .partNumber(2)
                                .build());

        assertEquals(3, head.contentLength());
        assertEquals("bytes 3-5/9", head.contentRange());
    }

    /**
     * Verifies that {@code HEAD ?partNumber=1} on a single-part object returns the whole object's
     * metadata as 200 OK.
     */
    @Test
    void headSinglePart1ReturnsWholeObject() {
        client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("single-head1.txt").build(),
                RequestBody.fromString("whole"));

        var head =
                client.headObject(
                        HeadObjectRequest.builder()
                                .bucket(BUCKET)
                                .key("single-head1.txt")
                                .partNumber(1)
                                .build());

        assertEquals(5, head.contentLength());
        assertEquals(200, head.sdkHttpResponse().statusCode());
    }
}
