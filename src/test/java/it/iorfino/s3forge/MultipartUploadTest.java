package it.iorfino.s3forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.iorfino.s3forge.support.AwsClientFactory;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

/**
 * End-to-end tests for the S3 multipart upload operations.
 *
 * <p>Covers the full lifecycle (initiate, upload part, complete, abort), error handling, and the
 * interaction with subsequent {@code GET} requests. Because both backends share the same completion
 * logic in {@link it.iorfino.s3forge.store.MultipartStore}, a single test suite against the
 * in-memory backend provides adequate coverage; the filesystem backend is exercised separately in
 * {@code FileSystemStoreTest}.
 *
 * @since 0.1.0
 */
class MultipartUploadTest {

    private static final String BUCKET = "multipart";

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

    /**
     * Uploads a single part and completes the upload. Verifies that the final object content
     * matches the concatenation of the parts.
     */
    @Test
    void initiateUploadSinglePart() {
        CreateMultipartUploadResponse init =
                client.createMultipartUpload(
                        CreateMultipartUploadRequest.builder()
                                .bucket(BUCKET)
                                .key("single.bin")
                                .build());

        var part =
                client.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(BUCKET)
                                .key("single.bin")
                                .uploadId(init.uploadId())
                                .partNumber(1)
                                .build(),
                        RequestBody.fromString("hello world"));

        client.completeMultipartUpload(
                CompleteMultipartUploadRequest.builder()
                        .bucket(BUCKET)
                        .key("single.bin")
                        .uploadId(init.uploadId())
                        .multipartUpload(
                                CompletedMultipartUpload.builder()
                                        .parts(
                                                CompletedPart.builder()
                                                        .partNumber(1)
                                                        .eTag(part.eTag())
                                                        .build())
                                        .build())
                        .build());

        String got =
                client.getObjectAsBytes(
                                GetObjectRequest.builder().bucket(BUCKET).key("single.bin").build())
                        .asUtf8String();
        assertEquals("hello world", got);
    }

    /** Uploads three parts in order and verifies the concatenated content. */
    @Test
    void uploadThreeParts() {
        CreateMultipartUploadResponse init =
                client.createMultipartUpload(
                        CreateMultipartUploadRequest.builder()
                                .bucket(BUCKET)
                                .key("three.bin")
                                .build());

        var p1 =
                client.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(BUCKET)
                                .key("three.bin")
                                .uploadId(init.uploadId())
                                .partNumber(1)
                                .build(),
                        RequestBody.fromString("AAA"));
        var p2 =
                client.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(BUCKET)
                                .key("three.bin")
                                .uploadId(init.uploadId())
                                .partNumber(2)
                                .build(),
                        RequestBody.fromString("BBB"));
        var p3 =
                client.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(BUCKET)
                                .key("three.bin")
                                .uploadId(init.uploadId())
                                .partNumber(3)
                                .build(),
                        RequestBody.fromString("CCC"));

        client.completeMultipartUpload(
                CompleteMultipartUploadRequest.builder()
                        .bucket(BUCKET)
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
                                                        .build(),
                                                CompletedPart.builder()
                                                        .partNumber(3)
                                                        .eTag(p3.eTag())
                                                        .build())
                                        .build())
                        .build());

        String got =
                client.getObjectAsBytes(
                                GetObjectRequest.builder().bucket(BUCKET).key("three.bin").build())
                        .asUtf8String();
        assertEquals("AAABBBCCC", got);
    }

    /**
     * Verifies that the final ETag uses the S3 multipart convention: {@code
     * "<md5-of-md5s>-<partCount>"}.
     */
    @Test
    void finalEtagHasMultipartFormat() {
        CreateMultipartUploadResponse init =
                client.createMultipartUpload(
                        CreateMultipartUploadRequest.builder()
                                .bucket(BUCKET)
                                .key("etag.bin")
                                .build());

        var p1 =
                client.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(BUCKET)
                                .key("etag.bin")
                                .uploadId(init.uploadId())
                                .partNumber(1)
                                .build(),
                        RequestBody.fromString("x"));
        var p2 =
                client.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(BUCKET)
                                .key("etag.bin")
                                .uploadId(init.uploadId())
                                .partNumber(2)
                                .build(),
                        RequestBody.fromString("y"));

        var res =
                client.completeMultipartUpload(
                        CompleteMultipartUploadRequest.builder()
                                .bucket(BUCKET)
                                .key("etag.bin")
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

        assertTrue(
                res.eTag().matches("\"[0-9a-f]{32}-2\""),
                "Unexpected multipart ETag: " + res.eTag());
    }

    /**
     * Verifies that listing parts returns the uploaded parts with their correct numbers and sizes.
     */
    @Test
    void listPartsReturnsUploadedParts() {
        CreateMultipartUploadResponse init =
                client.createMultipartUpload(
                        CreateMultipartUploadRequest.builder()
                                .bucket(BUCKET)
                                .key("list.bin")
                                .build());

        client.uploadPart(
                UploadPartRequest.builder()
                        .bucket(BUCKET)
                        .key("list.bin")
                        .uploadId(init.uploadId())
                        .partNumber(1)
                        .build(),
                RequestBody.fromString("aaaa"));
        client.uploadPart(
                UploadPartRequest.builder()
                        .bucket(BUCKET)
                        .key("list.bin")
                        .uploadId(init.uploadId())
                        .partNumber(2)
                        .build(),
                RequestBody.fromString("bb"));

        var parts =
                client.listParts(
                                ListPartsRequest.builder()
                                        .bucket(BUCKET)
                                        .key("list.bin")
                                        .uploadId(init.uploadId())
                                        .build())
                        .parts();

        assertEquals(2, parts.size());
        assertEquals(1, parts.get(0).partNumber());
        assertEquals(4, parts.get(0).size());
        assertEquals(2, parts.get(1).partNumber());
        assertEquals(2, parts.get(1).size());
    }

    /**
     * Verifies that completing a multipart upload with a missing part number fails with {@code
     * InvalidPart}.
     */
    @Test
    void completeWithMissingPartFails() {
        CreateMultipartUploadResponse init =
                client.createMultipartUpload(
                        CreateMultipartUploadRequest.builder()
                                .bucket(BUCKET)
                                .key("missing.bin")
                                .build());

        client.uploadPart(
                UploadPartRequest.builder()
                        .bucket(BUCKET)
                        .key("missing.bin")
                        .uploadId(init.uploadId())
                        .partNumber(1)
                        .build(),
                RequestBody.fromString("x"));

        var ex =
                assertThrows(
                        software.amazon.awssdk.services.s3.model.S3Exception.class,
                        () ->
                                client.completeMultipartUpload(
                                        CompleteMultipartUploadRequest.builder()
                                                .bucket(BUCKET)
                                                .key("missing.bin")
                                                .uploadId(init.uploadId())
                                                .multipartUpload(
                                                        CompletedMultipartUpload.builder()
                                                                .parts(
                                                                        CompletedPart.builder()
                                                                                .partNumber(7)
                                                                                .eTag("\"x\"")
                                                                                .build())
                                                                .build())
                                                .build()));

        assertEquals(400, ex.statusCode());
    }

    /** Verifies that completing an unknown upload id fails with {@code NoSuchUpload}. */
    @Test
    void completeUnknownUploadFails() {
        assertThrows(
                NoSuchUploadException.class,
                () ->
                        client.completeMultipartUpload(
                                CompleteMultipartUploadRequest.builder()
                                        .bucket(BUCKET)
                                        .key("nope.bin")
                                        .uploadId("00000000-0000-0000-0000-000000000000")
                                        .multipartUpload(
                                                CompletedMultipartUpload.builder()
                                                        .parts(
                                                                CompletedPart.builder()
                                                                        .partNumber(1)
                                                                        .eTag("\"x\"")
                                                                        .build())
                                                        .build())
                                        .build()));
    }

    /**
     * Verifies that aborting a multipart upload discards all its parts, so that a subsequent
     * completion attempt fails and the target key is not created.
     */
    @Test
    void abortDiscardsUpload() {
        CreateMultipartUploadResponse init =
                client.createMultipartUpload(
                        CreateMultipartUploadRequest.builder()
                                .bucket(BUCKET)
                                .key("abort.bin")
                                .build());

        client.uploadPart(
                UploadPartRequest.builder()
                        .bucket(BUCKET)
                        .key("abort.bin")
                        .uploadId(init.uploadId())
                        .partNumber(1)
                        .build(),
                RequestBody.fromString("payload"));

        client.abortMultipartUpload(
                AbortMultipartUploadRequest.builder()
                        .bucket(BUCKET)
                        .key("abort.bin")
                        .uploadId(init.uploadId())
                        .build());

        // Completing must now fail: upload is gone.
        assertThrows(
                NoSuchUploadException.class,
                () ->
                        client.completeMultipartUpload(
                                CompleteMultipartUploadRequest.builder()
                                        .bucket(BUCKET)
                                        .key("abort.bin")
                                        .uploadId(init.uploadId())
                                        .multipartUpload(
                                                CompletedMultipartUpload.builder()
                                                        .parts(
                                                                CompletedPart.builder()
                                                                        .partNumber(1)
                                                                        .eTag("\"x\"")
                                                                        .build())
                                                        .build())
                                        .build()));

        // And the object was never created.
        assertThrows(
                software.amazon.awssdk.services.s3.model.NoSuchKeyException.class,
                () ->
                        client.getObjectAsBytes(
                                GetObjectRequest.builder()
                                        .bucket(BUCKET)
                                        .key("abort.bin")
                                        .build()));
    }

    /** Verifies that a part uploaded under an unknown upload id is rejected. */
    @Test
    void uploadPartUnknownUploadFails() {
        assertThrows(
                NoSuchUploadException.class,
                () ->
                        client.uploadPart(
                                UploadPartRequest.builder()
                                        .bucket(BUCKET)
                                        .key("phantom.bin")
                                        .uploadId("11111111-1111-1111-1111-111111111111")
                                        .partNumber(1)
                                        .build(),
                                RequestBody.fromString("x")));
    }

    /**
     * Uploads enough parts to demonstrate ordering by part number, even when uploaded in a
     * non-sequential order.
     */
    @Test
    void partsAreOrderedByPartNumber() {
        CreateMultipartUploadResponse init =
                client.createMultipartUpload(
                        CreateMultipartUploadRequest.builder()
                                .bucket(BUCKET)
                                .key("order.bin")
                                .build());

        // Upload out of order on purpose.
        var p3 =
                client.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(BUCKET)
                                .key("order.bin")
                                .uploadId(init.uploadId())
                                .partNumber(3)
                                .build(),
                        RequestBody.fromString("3"));
        var p1 =
                client.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(BUCKET)
                                .key("order.bin")
                                .uploadId(init.uploadId())
                                .partNumber(1)
                                .build(),
                        RequestBody.fromString("1"));
        var p2 =
                client.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(BUCKET)
                                .key("order.bin")
                                .uploadId(init.uploadId())
                                .partNumber(2)
                                .build(),
                        RequestBody.fromString("2"));

        client.completeMultipartUpload(
                CompleteMultipartUploadRequest.builder()
                        .bucket(BUCKET)
                        .key("order.bin")
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

        String got =
                client.getObjectAsBytes(
                                GetObjectRequest.builder().bucket(BUCKET).key("order.bin").build())
                        .asUtf8String();
        assertEquals("123", got);
    }
}
