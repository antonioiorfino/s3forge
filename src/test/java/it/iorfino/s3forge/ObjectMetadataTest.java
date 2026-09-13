package it.iorfino.s3forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import it.iorfino.s3forge.support.AwsClientFactory;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * End-to-end tests for standard object metadata headers and user-defined {@code x-amz-meta-*}
 * entries.
 *
 * <p>Covers the full lifecycle: put with metadata, get and head returning it, copy with the COPY
 * directive preserving it, copy with the REPLACE directive replacing it, and absence of metadata
 * when none was set.
 *
 * <p>Uses the in-memory backend. Persistence across restarts on the filesystem backend is covered
 * separately in {@link FileSystemMetadataTest}.
 *
 * @since 0.2.0
 */
class ObjectMetadataTest {

    private static final String BUCKET = "metadata-tests";

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
     * Verifies that the five standard metadata headers survive a put/head round-trip with their
     * values intact.
     */
    @Test
    void standardMetadataHeadersRoundTrip() {
        client.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET)
                        .key("std.txt")
                        .cacheControl("max-age=3600")
                        .contentDisposition("attachment; filename=\"report.pdf\"")
                        .contentEncoding("gzip")
                        .contentLanguage("en-US")
                        .expires(Instant.parse("2030-01-01T00:00:00Z"))
                        .build(),
                RequestBody.fromString("hello"));

        HeadObjectResponse head =
                client.headObject(
                        HeadObjectRequest.builder().bucket(BUCKET).key("std.txt").build());

        assertEquals("max-age=3600", head.cacheControl());
        assertEquals("attachment; filename=\"report.pdf\"", head.contentDisposition());
        assertEquals("gzip", head.contentEncoding());
        assertEquals("en-US", head.contentLanguage());
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), head.expires());
    }

    /** Verifies that user-defined {@code x-amz-meta-*} headers survive a put/head round-trip. */
    @Test
    void userMetadataRoundTrip() {
        client.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET)
                        .key("user.txt")
                        .metadata(
                                Map.of(
                                        "tenant-id", "acme",
                                        "version", "42",
                                        "category", "reports"))
                        .build(),
                RequestBody.fromString("payload"));

        HeadObjectResponse head =
                client.headObject(
                        HeadObjectRequest.builder().bucket(BUCKET).key("user.txt").build());

        assertEquals("acme", head.metadata().get("tenant-id"));
        assertEquals("42", head.metadata().get("version"));
        assertEquals("reports", head.metadata().get("category"));
    }

    /** Verifies that GetObject also returns the stored metadata headers. */
    @Test
    void getReturnsMetadataHeaders() {
        client.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET)
                        .key("getmeta.txt")
                        .cacheControl("no-cache")
                        .metadata(Map.of("owner", "test-suite"))
                        .build(),
                RequestBody.fromString("data"));

        var response =
                client.getObjectAsBytes(
                                GetObjectRequest.builder()
                                        .bucket(BUCKET)
                                        .key("getmeta.txt")
                                        .build())
                        .response();

        assertEquals("no-cache", response.cacheControl());
        assertEquals("test-suite", response.metadata().get("owner"));
    }

    /**
     * Verifies that CopyObject with the default directive preserves the source object's content
     * type and metadata.
     */
    @Test
    void copyPreservesMetadataByDefault() {
        client.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET)
                        .key("src.txt")
                        .contentType("text/plain")
                        .cacheControl("max-age=60")
                        .metadata(Map.of("tag", "original"))
                        .build(),
                RequestBody.fromString("content"));

        client.copyObject(
                CopyObjectRequest.builder()
                        .sourceBucket(BUCKET)
                        .sourceKey("src.txt")
                        .destinationBucket(BUCKET)
                        .destinationKey("dst.txt")
                        .build());

        HeadObjectResponse head =
                client.headObject(
                        HeadObjectRequest.builder().bucket(BUCKET).key("dst.txt").build());

        assertEquals("text/plain", head.contentType());
        assertEquals("max-age=60", head.cacheControl());
        assertEquals("original", head.metadata().get("tag"));
    }

    /**
     * Verifies that CopyObject with the REPLACE directive replaces the source's content type and
     * metadata with the values from the request.
     */
    @Test
    void copyWithReplaceReplacesMetadata() {
        client.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET)
                        .key("src2.txt")
                        .contentType("text/plain")
                        .cacheControl("max-age=60")
                        .metadata(Map.of("tag", "original"))
                        .build(),
                RequestBody.fromString("content"));

        client.copyObject(
                CopyObjectRequest.builder()
                        .sourceBucket(BUCKET)
                        .sourceKey("src2.txt")
                        .destinationBucket(BUCKET)
                        .destinationKey("dst2.txt")
                        .metadataDirective(MetadataDirective.REPLACE)
                        .contentType("application/json")
                        .cacheControl("no-store")
                        .metadata(Map.of("tag", "replaced"))
                        .build());

        HeadObjectResponse head =
                client.headObject(
                        HeadObjectRequest.builder().bucket(BUCKET).key("dst2.txt").build());

        assertEquals("application/json", head.contentType());
        assertEquals("no-store", head.cacheControl());
        assertEquals("replaced", head.metadata().get("tag"));
    }

    /** Verifies that an object uploaded without metadata returns none on subsequent reads. */
    @Test
    void metadataIsAbsentWhenNotSet() {
        client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("bare.txt").build(),
                RequestBody.fromString("x"));

        HeadObjectResponse head =
                client.headObject(
                        HeadObjectRequest.builder().bucket(BUCKET).key("bare.txt").build());

        assertNull(head.cacheControl());
        assertEquals(0, head.metadata().size());
    }
}
