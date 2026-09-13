package it.iorfino.s3forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import it.iorfino.s3forge.support.AwsClientFactory;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

class CopyObjectTest {

    private static final String SRC_BUCKET = "copy-src";
    private static final String DST_BUCKET = "copy-dst";

    private static S3Forge forge;
    private static S3Client client;

    @BeforeAll
    static void setup() throws IOException {
        forge = S3Forge.builder().port(0).inMemory().build();
        forge.start();
        client = AwsClientFactory.forPort(forge.port());
        client.createBucket(CreateBucketRequest.builder().bucket(SRC_BUCKET).build());
        client.createBucket(CreateBucketRequest.builder().bucket(DST_BUCKET).build());
    }

    @AfterAll
    static void teardown() {
        if (client != null) client.close();
        if (forge != null) forge.close();
    }

    @BeforeEach
    void clearBuckets() {
        for (String bucket : new String[] {SRC_BUCKET, DST_BUCKET}) {
            ListObjectsV2Response existing =
                    client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build());
            existing.contents()
                    .forEach(
                            o ->
                                    client.deleteObject(
                                            DeleteObjectRequest.builder()
                                                    .bucket(bucket)
                                                    .key(o.key())
                                                    .build()));
        }
    }

    private void put(String bucket, String key, String content) {
        client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromString(content));
    }

    @Test
    void copyWithinSameBucket() {
        put(SRC_BUCKET, "original.txt", "hello");

        CopyObjectResponse res =
                client.copyObject(
                        CopyObjectRequest.builder()
                                .sourceBucket(SRC_BUCKET)
                                .sourceKey("original.txt")
                                .destinationBucket(SRC_BUCKET)
                                .destinationKey("copy.txt")
                                .build());

        assertNotNull(res.copyObjectResult().eTag());

        String copied =
                client.getObjectAsBytes(
                                GetObjectRequest.builder()
                                        .bucket(SRC_BUCKET)
                                        .key("copy.txt")
                                        .build())
                        .asUtf8String();
        assertEquals("hello", copied);
    }

    @Test
    void copyAcrossBuckets() {
        put(SRC_BUCKET, "cross.txt", "cross-bucket content");

        client.copyObject(
                CopyObjectRequest.builder()
                        .sourceBucket(SRC_BUCKET)
                        .sourceKey("cross.txt")
                        .destinationBucket(DST_BUCKET)
                        .destinationKey("cross.txt")
                        .build());

        String copied =
                client.getObjectAsBytes(
                                GetObjectRequest.builder()
                                        .bucket(DST_BUCKET)
                                        .key("cross.txt")
                                        .build())
                        .asUtf8String();
        assertEquals("cross-bucket content", copied);
    }

    @Test
    void copyMissingSourceFails() {
        assertThrows(
                NoSuchKeyException.class,
                () ->
                        client.copyObject(
                                CopyObjectRequest.builder()
                                        .sourceBucket(SRC_BUCKET)
                                        .sourceKey("does-not-exist")
                                        .destinationBucket(DST_BUCKET)
                                        .destinationKey("x")
                                        .build()));
    }

    @Test
    void copyToSelfWithoutReplaceFails() {
        put(SRC_BUCKET, "self.txt", "data");

        S3Exception ex =
                assertThrows(
                        S3Exception.class,
                        () ->
                                client.copyObject(
                                        CopyObjectRequest.builder()
                                                .sourceBucket(SRC_BUCKET)
                                                .sourceKey("self.txt")
                                                .destinationBucket(SRC_BUCKET)
                                                .destinationKey("self.txt")
                                                .build()));

        assertEquals(400, ex.statusCode());
    }

    @Test
    void copyToSelfWithReplaceSucceeds() {
        put(SRC_BUCKET, "self-replace.txt", "data");

        client.copyObject(
                CopyObjectRequest.builder()
                        .sourceBucket(SRC_BUCKET)
                        .sourceKey("self-replace.txt")
                        .destinationBucket(SRC_BUCKET)
                        .destinationKey("self-replace.txt")
                        .metadataDirective(MetadataDirective.REPLACE)
                        .contentType("text/plain")
                        .build());

        String content =
                client.getObjectAsBytes(
                                GetObjectRequest.builder()
                                        .bucket(SRC_BUCKET)
                                        .key("self-replace.txt")
                                        .build())
                        .asUtf8String();
        assertEquals("data", content);
    }

    @Test
    void copyPreservesEtag() {
        put(SRC_BUCKET, "etag-source.txt", "abc");
        // MD5("abc") = 900150983cd24fb0d6963f7d28e17f72
        String expectedEtag = "\"900150983cd24fb0d6963f7d28e17f72\"";

        CopyObjectResponse res =
                client.copyObject(
                        CopyObjectRequest.builder()
                                .sourceBucket(SRC_BUCKET)
                                .sourceKey("etag-source.txt")
                                .destinationBucket(DST_BUCKET)
                                .destinationKey("etag-copy.txt")
                                .build());

        assertEquals(expectedEtag, res.copyObjectResult().eTag());

        String copiedEtag =
                client.headObject(b -> b.bucket(DST_BUCKET).key("etag-copy.txt")).eTag();
        assertEquals(expectedEtag, copiedEtag);
    }

    @Test
    void copyKeyWithSlashes() {
        put(SRC_BUCKET, "a/b/c/orig.txt", "nested");

        client.copyObject(
                CopyObjectRequest.builder()
                        .sourceBucket(SRC_BUCKET)
                        .sourceKey("a/b/c/orig.txt")
                        .destinationBucket(DST_BUCKET)
                        .destinationKey("x/y/z/copy.txt")
                        .build());

        String copied =
                client.getObjectAsBytes(
                                GetObjectRequest.builder()
                                        .bucket(DST_BUCKET)
                                        .key("x/y/z/copy.txt")
                                        .build())
                        .asUtf8String();
        assertEquals("nested", copied);
    }
}
