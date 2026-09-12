package it.iorfino.s3forge;

import it.iorfino.s3forge.support.AwsClientFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangeRequestTest {

    private static final String BUCKET = "range-tests";
    private static final String ALPHABET =
        "abcdefghijklmnopqrstuvwxyz"; // 26 bytes

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
    void seed() {
        // wipe + re-upload
        client.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build())
            .contents()
            .forEach(o -> client.deleteObject(DeleteObjectRequest.builder()
                .bucket(BUCKET).key(o.key()).build()));
        client.putObject(PutObjectRequest.builder()
                .bucket(BUCKET).key("alpha.txt").build(),
            RequestBody.fromString(ALPHABET, StandardCharsets.UTF_8));
    }

    @Test
    void rangeFirstN() {
        var got = client.getObjectAsBytes(GetObjectRequest.builder()
            .bucket(BUCKET).key("alpha.txt")
            .range("bytes=0-4")     // "abcde"
            .build());

        assertEquals("abcde", got.asUtf8String());
        assertEquals("bytes 0-4/26", got.response().contentRange());
        assertEquals(5, got.response().contentLength());
    }

    @Test
    void rangeFromOffset() {
        var got = client.getObjectAsBytes(GetObjectRequest.builder()
            .bucket(BUCKET).key("alpha.txt")
            .range("bytes=10-")
            .build());

        assertEquals("klmnopqrstuvwxyz", got.asUtf8String());
        assertEquals("bytes 10-25/26", got.response().contentRange());
    }

    @Test
    void rangeSuffix() {
        var got = client.getObjectAsBytes(GetObjectRequest.builder()
            .bucket(BUCKET).key("alpha.txt")
            .range("bytes=-5")
            .build());

        assertEquals("vwxyz", got.asUtf8String());
        assertEquals("bytes 21-25/26", got.response().contentRange());
    }

    @Test
    void rangeBeyondEndIsClamped() {
        var got = client.getObjectAsBytes(GetObjectRequest.builder()
            .bucket(BUCKET).key("alpha.txt")
            .range("bytes=20-100")
            .build());

        assertEquals("uvwxyz", got.asUtf8String());
        assertEquals("bytes 20-25/26", got.response().contentRange());
    }

    /**
     * Verifies that a range whose start offset exceeds the object size is
     * rejected with a {@code 416 Range Not Satisfiable} response, and that
     * the response carries a {@code Content-Range} header of the form
     * {@code bytes <asterisk>/<total>} as required by RFC 7233.
     *
     * <p>The test does not assert on the {@code errorMessage()} because it
     * may be {@code null} when the server sends a response without a body;
     * the status code and the {@code Content-Range} header are the actual
     * contract.</p>
     */
    @Test
    void rangeStartBeyondSizeIsRejected() {
        S3Exception ex = assertThrows(S3Exception.class, () ->
            client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(BUCKET).key("alpha.txt")
                .range("bytes=100-200")
                .build()));

        assertEquals(416, ex.statusCode());

        String contentRange = ex.awsErrorDetails().sdkHttpResponse()
            .firstMatchingHeader("Content-Range").orElse("");
        assertTrue(contentRange.startsWith("bytes */"),
            "Expected Content-Range of the form 'bytes */<total>', got: "
                + contentRange);
    }

    @Test
    void headWithRangeReportsContentRange() {
        HeadObjectResponse head = client.headObject(HeadObjectRequest.builder()
            .bucket(BUCKET).key("alpha.txt")
            .range("bytes=0-4")
            .build());

        assertEquals(5, head.contentLength());
        assertEquals("bytes 0-4/26", head.contentRange());
    }

    @Test
    void noRangeReturnsFullBody() {
        var got = client.getObjectAsBytes(GetObjectRequest.builder()
            .bucket(BUCKET).key("alpha.txt")
            .build());

        assertEquals(ALPHABET, got.asUtf8String());
        assertEquals(200, got.response().sdkHttpResponse().statusCode());
    }

    /**
     * Verifies that a single-byte range ({@code bytes=0-0}) returns exactly
     * one byte and reports the correct {@code Content-Range}.
     */
    @Test
    void rangeZeroToZeroReturnsSingleByte() {
        var got = client.getObjectAsBytes(GetObjectRequest.builder()
            .bucket(BUCKET).key("alpha.txt")
            .range("bytes=0-0")
            .build());

        assertEquals("a", got.asUtf8String());
        assertEquals("bytes 0-0/26", got.response().contentRange());
    }

    /**
     * Verifies that a range covering the whole object
     * ({@code bytes=0-<size-1>}) is still served as a partial response, with
     * {@code 206 Partial Content} and the expected {@code Content-Range},
     * rather than being collapsed into a {@code 200 OK}.
     */
    @Test
    void rangeFullLengthIsEquivalentToFullBody() {
        var got = client.getObjectAsBytes(GetObjectRequest.builder()
            .bucket(BUCKET).key("alpha.txt")
            .range("bytes=0-25")
            .build());

        assertEquals(ALPHABET, got.asUtf8String());
        assertEquals("bytes 0-25/26", got.response().contentRange());
    }

    /**
     * Verifies that a syntactically invalid {@code Range} header is silently
     * ignored and the full body is served with a {@code 200 OK}, matching
     * HTTP semantics. Servers must not reject malformed ranges with an error.
     */
    @Test
    void rangeMalformedIsIgnoredAndFullBodyServed() {
        var got = client.getObjectAsBytes(GetObjectRequest.builder()
            .bucket(BUCKET).key("alpha.txt")
            .range("bytes=abc-def")
            .build());

        assertEquals(ALPHABET, got.asUtf8String());
        assertEquals(200, got.response().sdkHttpResponse().statusCode());
    }
}
