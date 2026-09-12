package it.iorfino.s3forge;

import it.iorfino.s3forge.support.AwsClientFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeleteObjectsTest {

    private static final String BUCKET = "batch-delete";

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
    void clearBucket() {
        ListObjectsV2Response existing = client.listObjectsV2(
            ListObjectsV2Request.builder().bucket(BUCKET).build());
        existing.contents().forEach(o ->
            client.deleteObject(DeleteObjectRequest.builder()
                .bucket(BUCKET).key(o.key()).build()));
    }

    private void put(String key) {
        client.putObject(PutObjectRequest.builder()
                .bucket(BUCKET).key(key).build(),
            RequestBody.fromString("data-" + key));
    }

    @Test
    void deletesMultipleKeys() {
        put("a.txt");
        put("b.txt");
        put("c.txt");

        DeleteObjectsResponse res = client.deleteObjects(DeleteObjectsRequest.builder()
            .bucket(BUCKET)
            .delete(Delete.builder()
                .objects(
                    ObjectIdentifier.builder().key("a.txt").build(),
                    ObjectIdentifier.builder().key("c.txt").build())
                .build())
            .build());

        assertEquals(2, res.deleted().size());
        assertTrue(res.errors().isEmpty());

        // b.txt still exists, a.txt and c.txt gone
        assertEquals("data-b.txt", client.getObjectAsBytes(GetObjectRequest.builder()
            .bucket(BUCKET).key("b.txt").build()).asUtf8String());
        assertThrows(NoSuchKeyException.class, () -> client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(BUCKET).key("a.txt").build()));
    }

    @Test
    void missingKeysAreNotErrors() {
        put("present.txt");

        DeleteObjectsResponse res = client.deleteObjects(DeleteObjectsRequest.builder()
            .bucket(BUCKET)
            .delete(Delete.builder()
                .objects(
                    ObjectIdentifier.builder().key("present.txt").build(),
                    ObjectIdentifier.builder().key("missing.txt").build())
                .build())
            .build());

        assertEquals(2, res.deleted().size());
        assertTrue(res.errors().isEmpty());
    }

    @Test
    void quietModeSuppressesDeleted() {
        put("q1.txt");
        put("q2.txt");

        DeleteObjectsResponse res = client.deleteObjects(DeleteObjectsRequest.builder()
            .bucket(BUCKET)
            .delete(Delete.builder()
                .objects(
                    ObjectIdentifier.builder().key("q1.txt").build(),
                    ObjectIdentifier.builder().key("q2.txt").build())
                .quiet(true)
                .build())
            .build());

        assertTrue(res.deleted().isEmpty(),
            "Quiet mode must not include <Deleted> entries");
    }

    @Test
    void emptyRequestDeletesNothing() {
        put("untouched.txt");

        DeleteObjectsResponse res = client.deleteObjects(DeleteObjectsRequest.builder()
            .bucket(BUCKET)
            .delete(Delete.builder().objects(List.of()).build())
            .build());

        assertTrue(res.deleted().isEmpty());
        assertEquals("data-untouched.txt", client.getObjectAsBytes(GetObjectRequest.builder()
            .bucket(BUCKET).key("untouched.txt").build()).asUtf8String());
    }

    @Test
    void manyKeysInOneRequest() {
        for (int i = 0; i < 50; i++) put("bulk-" + i + ".txt");

        var ids = new ObjectIdentifier[50];
        for (int i = 0; i < 50; i++) {
            ids[i] = ObjectIdentifier.builder().key("bulk-" + i + ".txt").build();
        }

        DeleteObjectsResponse res = client.deleteObjects(DeleteObjectsRequest.builder()
            .bucket(BUCKET)
            .delete(Delete.builder().objects(ids).build())
            .build());

        assertEquals(50, res.deleted().size());
    }
}
