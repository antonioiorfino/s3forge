package it.iorfino.s3forge;

import it.iorfino.s3forge.support.AwsClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that object metadata (ETag, content type, CRC32 checksum) is
 * persisted to sidecar files by the filesystem backend and correctly reloaded
 * after a server restart.
 *
 * <p>Without sidecar persistence, a restart would lose the ETag and checksum
 * values, breaking clients that rely on them for integrity checks and
 * conditional requests.</p>
 *
 * @since 0.1.0
 */
class FileSystemMetadataTest {

    @TempDir
    Path root;

    private S3Forge forge;
    private S3Client client;

    /**
     * Starts a server with filesystem persistence on the temporary root.
     *
     * @throws IOException if the server fails to start
     */
    @BeforeEach
    void setup() throws IOException {
        forge = S3Forge.builder().port(0).fileSystem(root).build();
        forge.start();
        client = AwsClientFactory.forPort(forge.port());
    }

    /**
     * Stops the server and client.
     */
    @AfterEach
    void teardown() {
        if (client != null) client.close();
        if (forge != null) forge.close();
    }

    /**
     * Verifies that a sidecar metadata file is created next to each object.
     */
    @Test
    void sidecarFileIsCreated() {
        client.createBucket(CreateBucketRequest.builder().bucket("meta").build());
        client.putObject(PutObjectRequest.builder()
                .bucket("meta").key("doc.txt").build(),
            RequestBody.fromString("content"));

        Path sidecar = root.resolve(".s3forge-meta")
            .resolve("meta").resolve("doc.txt.properties");
        assertTrue(Files.isRegularFile(sidecar),
            "Expected metadata file at " + sidecar);
    }

    /**
     * Verifies that the ETag survives a restart when the filesystem backend
     * is used, since it is reloaded from the sidecar file.
     *
     * @throws IOException if the second server fails to start
     */
    @Test
    void etagSurvivesRestart() throws IOException {
        client.createBucket(CreateBucketRequest.builder().bucket("etag").build());
        var put = client.putObject(PutObjectRequest.builder()
                .bucket("etag").key("x.txt").build(),
            RequestBody.fromString("abc"));

        String originalEtag = put.eTag();
        // MD5("abc") = 900150983cd24fb0d6963f7d28e17f72
        assertEquals("\"900150983cd24fb0d6963f7d28e17f72\"", originalEtag);

        // Restart.
        forge.close();
        client.close();
        forge = S3Forge.builder().port(0).fileSystem(root).build();
        forge.start();
        client = AwsClientFactory.forPort(forge.port());

        String reloadedEtag = client.headObject(HeadObjectRequest.builder()
            .bucket("etag").key("x.txt").build()).eTag();
        assertEquals(originalEtag, reloadedEtag);
    }

    /**
     * Verifies that the CRC32 checksum header is preserved across a restart,
     * since AWS SDK v2 validates it on read.
     *
     * @throws IOException if the second server fails to start
     */
    @Test
    void checksumSurvivesRestart() throws IOException {
        client.createBucket(CreateBucketRequest.builder().bucket("crc").build());
        client.putObject(PutObjectRequest.builder()
                .bucket("crc").key("payload.txt").build(),
            RequestBody.fromString("payload"));

        forge.close();
        client.close();
        forge = S3Forge.builder().port(0).fileSystem(root).build();
        forge.start();
        client = AwsClientFactory.forPort(forge.port());

        // GET must succeed without checksum validation error.
        String got = client.getObjectAsBytes(GetObjectRequest.builder()
            .bucket("crc").key("payload.txt").build()).asUtf8String();
        assertEquals("payload", got);
    }

    /**
     * Verifies that an explicit {@code Content-Type} supplied at upload time
     * is preserved across a restart instead of being overwritten by the
     * filesystem's own MIME detection.
     *
     * @throws IOException if the second server fails to start
     */
    @Test
    void contentTypeSurvivesRestart() throws IOException {
        client.createBucket(CreateBucketRequest.builder().bucket("ctype").build());
        client.putObject(PutObjectRequest.builder()
                .bucket("ctype").key("data.bin")
                .contentType("application/x-custom")
                .build(),
            RequestBody.fromString("data"));

        forge.close();
        client.close();
        forge = S3Forge.builder().port(0).fileSystem(root).build();
        forge.start();
        client = AwsClientFactory.forPort(forge.port());

        String contentType = client.headObject(HeadObjectRequest.builder()
            .bucket("ctype").key("data.bin").build()).contentType();
        assertEquals("application/x-custom", contentType);
    }

    /**
     * Verifies that deleting an object removes its sidecar file as well, so
     * that recreating a key with different content does not resurrect stale
     * metadata.
     */
    @Test
    void deleteRemovesSidecar() {
        client.createBucket(CreateBucketRequest.builder().bucket("del").build());
        client.putObject(PutObjectRequest.builder()
                .bucket("del").key("temp.txt").build(),
            RequestBody.fromString("temp"));

        Path sidecar = root.resolve(".s3forge-meta")
            .resolve("del").resolve("temp.txt.properties");
        assertTrue(Files.isRegularFile(sidecar));

        client.deleteObject(b -> b.bucket("del").key("temp.txt"));

        assertTrue(Files.notExists(sidecar),
            "Sidecar should have been deleted along with the object");
    }

    /**
     * Verifies that the hidden metadata directory is not exposed as a bucket
     * via {@code ListBuckets}.
     */
    @Test
    void metaDirIsNotListedAsBucket() {
        client.createBucket(CreateBucketRequest.builder().bucket("visible").build());
        client.putObject(PutObjectRequest.builder()
                .bucket("visible").key("x.txt").build(),
            RequestBody.fromString("x"));

        var names = client.listBuckets().buckets().stream()
            .map(b -> b.name()).toList();
        assertTrue(names.contains("visible"));
        assertTrue(!names.contains(".s3forge-meta"),
            "Metadata directory must not appear as a bucket");
    }
}
