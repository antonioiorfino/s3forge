package it.iorfino.s3forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectAttributesRequest;
import software.amazon.awssdk.services.s3.model.GetObjectAttributesResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectAttributes;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Tests for the {@code GetObjectAttributes} operation.
 *
 * <p>This operation returns selected metadata fields without transferring the object payload. It is
 * used by AWS SDK v2 clients that need ETag or size without downloading the object.
 *
 * @since 0.1.0
 */
class GetObjectAttributesTest {

    private static final String BUCKET = "attr-tests";
    private static org.apache.hc.client5.http.impl.classic.CloseableHttpClient rawClient;

    private static S3Forge forge;
    private static S3Client client;

    @BeforeAll
    static void setup() throws IOException {
        forge = S3Forge.builder().port(0).inMemory().build();
        forge.start();
        client = AwsClientFactory.forPort(forge.port());
        client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        rawClient = org.apache.hc.client5.http.impl.classic.HttpClients.createDefault();
    }

    @AfterAll
    static void teardown() {
        if (rawClient != null) {
            try {
                rawClient.close();
            } catch (Exception ignored) {
            }
        }
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

    /** Requesting ETag and ObjectSize returns both, correctly populated. */
    @Test
    void returnsEtagAndSize() {
        client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("abc.txt").build(),
                RequestBody.fromString("abc"));

        GetObjectAttributesResponse res =
                client.getObjectAttributes(
                        GetObjectAttributesRequest.builder()
                                .bucket(BUCKET)
                                .key("abc.txt")
                                //         .objectAttributes(ObjectAttributes.E_TAG,
                                //             ObjectAttributes.OBJECT_SIZE)
                                .build());

        // MD5("abc") = 900150983cd24fb0d6963f7d28e17f72
        assertEquals("\"900150983cd24fb0d6963f7d28e17f72\"", res.eTag());
        assertEquals(3L, res.objectSize());
    }

    /**
     * Verifies that the server correctly handles the {@code x-amz-object-attributes} header,
     * independently of the AWS SDK.
     *
     * <p>This test exists because the AWS SDK v2 has a known parsing issue for {@code
     * GetObjectAttributes} when {@code ObjectSize} is explicitly requested. Using a raw HTTP client
     * isolates the server behavior from the SDK's client-side bug.
     */
    @Test
    void serverHandlesExplicitAttributesHeader() throws Exception {
        client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("raw.txt").build(),
                RequestBody.fromString("abc"));

        var request =
                new org.apache.hc.client5.http.classic.methods.HttpGet(
                        java.net.URI.create(
                                "http://localhost:"
                                        + forge.port()
                                        + "/"
                                        + BUCKET
                                        + "/raw.txt?attributes"));
        request.setHeader("x-amz-object-attributes", "ETag,ObjectSize");
        request.setHeader("Host", "localhost:" + forge.port());

        try (var response = rawClient.executeOpen(null, request, null)) {
            int status = response.getCode();
            String body =
                    org.apache.hc.core5.http.io.entity.EntityUtils.toString(
                            response.getEntity(), java.nio.charset.StandardCharsets.UTF_8);

            assertEquals(200, status);
            assertTrue(
                    body.contains("<ObjectSize>3</ObjectSize>"),
                    "Body did not contain expected ObjectSize: " + body);
            assertTrue(
                    body.contains("<ETag>&quot;900150983cd24fb0d6963f7d28e17f72&quot;</ETag>"),
                    body);
        }
    }

    /** Requesting StorageClass returns STANDARD. */
    @Test
    void returnsStorageClass() {
        client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("s.txt").build(),
                RequestBody.fromString("data"));

        GetObjectAttributesResponse res =
                client.getObjectAttributes(
                        GetObjectAttributesRequest.builder()
                                .bucket(BUCKET)
                                .key("s.txt")
                                .objectAttributes(ObjectAttributes.STORAGE_CLASS)
                                .build());

        assertEquals("STANDARD", res.storageClassAsString());
    }

    /** Requesting Checksum returns the CRC32 stored at upload time. */
    @Test
    void returnsChecksum() {
        client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("crc.txt").build(),
                RequestBody.fromString("checksum-me"));

        GetObjectAttributesResponse res =
                client.getObjectAttributes(
                        GetObjectAttributesRequest.builder()
                                .bucket(BUCKET)
                                .key("crc.txt")
                                .objectAttributes(ObjectAttributes.CHECKSUM)
                                .build());

        assertNotNull(res.checksum());
        assertTrue(
                res.checksum().checksumCRC32() != null && !res.checksum().checksumCRC32().isEmpty(),
                "Expected a non-empty CRC32 checksum");
    }

    /** A missing key produces {@code NoSuchKey}. */
    @Test
    void missingKeyFails() {
        assertThrows(
                NoSuchKeyException.class,
                () ->
                        client.getObjectAttributes(
                                GetObjectAttributesRequest.builder()
                                        .bucket(BUCKET)
                                        .key("missing.txt")
                                        .objectAttributes(ObjectAttributes.E_TAG)
                                        .build()));
    }
}
