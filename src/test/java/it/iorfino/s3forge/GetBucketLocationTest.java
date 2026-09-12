package it.iorfino.s3forge;

import it.iorfino.s3forge.support.AwsClientFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@code GetBucketLocation} operation.
 *
 * <p>This operation is used by the AWS SDK during region discovery. Even
 * though S3Forge has only a single region, the operation must be
 * implemented so that SDK clients can complete their initialization
 * sequence without errors.</p>
 *
 * @since 0.1.0
 */
class GetBucketLocationTest {

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

    /**
     * An existing bucket returns an empty location constraint, which AWS
     * clients universally map to {@code us-east-1}.
     */
    @Test
    void defaultRegionIsEmpty() {
        client.createBucket(CreateBucketRequest.builder().bucket("loc-default").build());

        String location = client.getBucketLocation(
                GetBucketLocationRequest.builder().bucket("loc-default").build())
            .locationConstraintAsString();

        // Empty constraint is returned as either null or an empty string,
        // depending on the SDK version. Both mean "us-east-1".
        assertTrue(location == null || location.isEmpty(),
            "Expected an empty or null location constraint, got: " + location);
    }

    /**
     * A missing bucket produces {@code NoSuchBucket}.
     */
    @Test
    void missingBucketFails() {
        assertThrows(NoSuchBucketException.class, () ->
            client.getBucketLocation(GetBucketLocationRequest.builder()
                .bucket("does-not-exist").build()));
    }
}
