package it.iorfino.s3forge.support;

import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

public final class AwsClientFactory {

    private AwsClientFactory() {}

    public static S3Client forPort(int port) {
        return S3Client.builder()
                .endpointOverride(URI.create("http://localhost:" + port))
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create("test", "test")))
                .forcePathStyle(true)
                .build();
    }
}
