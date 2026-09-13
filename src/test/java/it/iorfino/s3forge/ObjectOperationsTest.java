package it.iorfino.s3forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.iorfino.s3forge.support.AwsClientFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class ObjectOperationsTest {

    private static final String BUCKET = "object-tests";

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

    @Test
    void putAndGetObjectRoundTrip() {
        String content = "hello s3forge";
        client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("greeting.txt").build(),
                RequestBody.fromString(content, StandardCharsets.UTF_8));

        ResponseBytes<GetObjectResponse> got =
                client.getObjectAsBytes(
                        GetObjectRequest.builder().bucket(BUCKET).key("greeting.txt").build());

        assertEquals(content, got.asUtf8String());
        assertNotNull(got.response().eTag());
        assertTrue(got.response().contentLength() > 0);
    }

    @Test
    void putObjectReturnsEtag() {
        var response =
                client.putObject(
                        PutObjectRequest.builder().bucket(BUCKET).key("with-etag.txt").build(),
                        RequestBody.fromString("abc"));
        // MD5("abc") = 900150983cd24fb0d6963f7d28e17f72
        assertEquals("\"900150983cd24fb0d6963f7d28e17f72\"", response.eTag());
    }

    @Test
    void headObjectReturnsMetadataWithoutBody() {
        String content = "metadata check";
        client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("head.txt").build(),
                RequestBody.fromString(content));

        HeadObjectResponse head =
                client.headObject(
                        HeadObjectRequest.builder().bucket(BUCKET).key("head.txt").build());

        assertEquals(content.length(), head.contentLength());
        assertNotNull(head.eTag());
        assertNotNull(head.lastModified());
    }

    @Test
    void getMissingKeyThrowsNoSuchKey() {
        assertThrows(
                NoSuchKeyException.class,
                () ->
                        client.getObjectAsBytes(
                                GetObjectRequest.builder()
                                        .bucket(BUCKET)
                                        .key("does-not-exist")
                                        .build()));
    }

    @Test
    void deleteObjectRemovesIt() {
        client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("to-delete.txt").build(),
                RequestBody.fromString("bye"));

        client.deleteObject(
                DeleteObjectRequest.builder().bucket(BUCKET).key("to-delete.txt").build());

        assertThrows(
                NoSuchKeyException.class,
                () ->
                        client.getObjectAsBytes(
                                GetObjectRequest.builder()
                                        .bucket(BUCKET)
                                        .key("to-delete.txt")
                                        .build()));
    }

    @Test
    void deleteMissingKeyIsIdempotent() {
        // S3 semantics: deleting a non-existent key returns 204
        client.deleteObject(
                DeleteObjectRequest.builder().bucket(BUCKET).key("never-existed.txt").build());
    }

    @Test
    void putObjectOverwritesExisting() {
        client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("overwrite.txt").build(),
                RequestBody.fromString("v1"));
        client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("overwrite.txt").build(),
                RequestBody.fromString("v2-longer"));

        var got =
                client.getObjectAsBytes(
                        GetObjectRequest.builder().bucket(BUCKET).key("overwrite.txt").build());
        assertEquals("v2-longer", got.asUtf8String());
    }

    @Test
    void keyWithSlashesIsPreserved() {
        client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("a/b/c/file.txt").build(),
                RequestBody.fromString("nested"));

        var got =
                client.getObjectAsBytes(
                        GetObjectRequest.builder().bucket(BUCKET).key("a/b/c/file.txt").build());
        assertEquals("nested", got.asUtf8String());
    }

    @Test
    void emptyObjectIsSupported() {
        client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("empty.txt").build(),
                RequestBody.empty());

        var got =
                client.getObjectAsBytes(
                        GetObjectRequest.builder().bucket(BUCKET).key("empty.txt").build());
        assertEquals(0, got.asByteArray().length);
        // MD5("") = d41d8cd98f00b204e9800998ecf8427e
        assertEquals("\"d41d8cd98f00b204e9800998ecf8427e\"", got.response().eTag());
    }
}
