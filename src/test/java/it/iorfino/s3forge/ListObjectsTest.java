package it.iorfino.s3forge;

import it.iorfino.s3forge.support.AwsClientFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListObjectsTest {

    private static final String BUCKET = "list-tests";

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
    void emptyBucketReturnsNoObjects() {
        ListObjectsV2Response res = client.listObjectsV2(
            ListObjectsV2Request.builder().bucket(BUCKET).build());
        assertTrue(res.contents().isEmpty());
        assertFalse(res.isTruncated());
        assertEquals(0, res.keyCount());
    }

    @Test
    void listsAllObjectsInLexicographicOrder() {
        put("b.txt");
        put("a.txt");
        put("c.txt");

        List<String> keys = client.listObjectsV2(
                ListObjectsV2Request.builder().bucket(BUCKET).build())
            .contents().stream().map(o -> o.key()).toList();

        assertEquals(List.of("a.txt", "b.txt", "c.txt"), keys);
    }

    @Test
    void prefixFiltersKeys() {
        put("logs/2024/app.log");
        put("logs/2025/app.log");
        put("data/file.csv");

        List<String> keys = client.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(BUCKET).prefix("logs/").build())
            .contents().stream().map(o -> o.key()).toList();

        assertEquals(List.of("logs/2024/app.log", "logs/2025/app.log"), keys);
    }

    @Test
    void delimiterGroupsCommonPrefixes() {
        put("a/1.txt");
        put("a/2.txt");
        put("b/1.txt");
        put("top.txt");

        ListObjectsV2Response res = client.listObjectsV2(ListObjectsV2Request.builder()
            .bucket(BUCKET).delimiter("/").build());

        List<String> keys = res.contents().stream().map(o -> o.key()).toList();
        List<String> prefixes = res.commonPrefixes().stream()
            .map(p -> p.prefix()).toList();

        assertEquals(List.of("top.txt"), keys);
        assertEquals(List.of("a/", "b/"), prefixes);
    }

    @Test
    void prefixAndDelimiterTogether() {
        put("logs/2024/01.log");
        put("logs/2024/02.log");
        put("logs/2025/01.log");
        put("logs/readme.md");

        ListObjectsV2Response res = client.listObjectsV2(ListObjectsV2Request.builder()
            .bucket(BUCKET).prefix("logs/").delimiter("/").build());

        List<String> keys = res.contents().stream().map(o -> o.key()).toList();
        List<String> prefixes = res.commonPrefixes().stream()
            .map(p -> p.prefix()).toList();

        assertEquals(List.of("logs/readme.md"), keys);
        assertEquals(List.of("logs/2024/", "logs/2025/"), prefixes);
    }

    @Test
    void maxKeysTruncatesResult() {
        for (int i = 0; i < 5; i++) put("file-" + i + ".txt");

        ListObjectsV2Response res = client.listObjectsV2(ListObjectsV2Request.builder()
            .bucket(BUCKET).maxKeys(2).build());

        assertEquals(2, res.contents().size());
        assertTrue(res.isTruncated());
        assertTrue(res.nextContinuationToken() != null
            && !res.nextContinuationToken().isEmpty());
    }

    @Test
    void continuationTokenResumesIteration() {
        for (int i = 0; i < 5; i++) put("file-" + i + ".txt");

        ListObjectsV2Response page1 = client.listObjectsV2(ListObjectsV2Request.builder()
            .bucket(BUCKET).maxKeys(2).build());
        assertEquals(2, page1.contents().size());
        assertTrue(page1.isTruncated());

        ListObjectsV2Response page2 = client.listObjectsV2(ListObjectsV2Request.builder()
            .bucket(BUCKET).maxKeys(2)
            .continuationToken(page1.nextContinuationToken()).build());
        assertEquals(2, page2.contents().size());
        assertTrue(page2.isTruncated());

        ListObjectsV2Response page3 = client.listObjectsV2(ListObjectsV2Request.builder()
            .bucket(BUCKET).maxKeys(2)
            .continuationToken(page2.nextContinuationToken()).build());
        assertEquals(1, page3.contents().size());
        assertFalse(page3.isTruncated());
    }
}
