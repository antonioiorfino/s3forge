package it.iorfino.s3forge;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.iorfino.s3forge.support.AwsClientFactory;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

class BucketOperationsTest {

    private static S3Forge forge;
    private static S3Client client;

    @BeforeAll
    static void setup() throws IOException {
        forge = S3Forge.builder().port(0).inMemory().build();
        forge.start();
        client = AwsClientFactory.forPort(forge.port());
    }

    @AfterAll
    static void teardown() {
        if (client != null) client.close();
        if (forge != null) forge.close();
    }

    @Test
    void createAndListBuckets() {
        client.createBucket(CreateBucketRequest.builder().bucket("test-bucket-1").build());
        client.createBucket(CreateBucketRequest.builder().bucket("test-bucket-2").build());

        ListBucketsResponse res = client.listBuckets();
        List<String> names = res.buckets().stream().map(Bucket::name).toList();
        assertTrue(names.contains("test-bucket-1"));
        assertTrue(names.contains("test-bucket-2"));
    }

    @Test
    void headBucketWorks() {
        client.createBucket(CreateBucketRequest.builder().bucket("head-test").build());
        // non deve lanciare
        client.headBucket(HeadBucketRequest.builder().bucket("head-test").build());
    }

    @Test
    void deleteBucketRemovesIt() {
        client.createBucket(CreateBucketRequest.builder().bucket("to-delete").build());
        client.deleteBucket(DeleteBucketRequest.builder().bucket("to-delete").build());
        assertThrows(
                NoSuchBucketException.class,
                () -> client.headBucket(HeadBucketRequest.builder().bucket("to-delete").build()));
    }

    @Test
    void createBucketIsIdempotent() {
        client.createBucket(CreateBucketRequest.builder().bucket("idem").build());
        // secondo create non deve fallire
        client.createBucket(CreateBucketRequest.builder().bucket("idem").build());
    }

    @Test
    void deleteNonExistingBucketFails() {
        assertThrows(
                NoSuchBucketException.class,
                () ->
                        client.deleteBucket(
                                DeleteBucketRequest.builder().bucket("does-not-exist").build()));
    }
}
