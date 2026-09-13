# S3Forge

[![CI](https://github.com/antonioiorfino/s3forge/actions/workflows/ci.yml/badge.svg)](https://github.com/antonioiorfino/s3forge/actions/workflows/ci.yml)
[![Version](https://img.shields.io/badge/version-0.1.0-blue.svg)](https://github.com/antonioiorfino/s3forge/releases/tag/v0.1.0)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)

**S3Forge** is an embedded S3 mock server written in **pure Java**, with
**zero framework dependencies**. It implements a practical subset of the
AWS S3 HTTP API and is designed for integration testing of applications
that talk to S3 — without hitting real AWS endpoints.

Everything runs on the JDK standard library: `com.sun.net.httpserver` for
the HTTP layer, virtual threads for request handling, and the built-in
DOM parser for XML. No Spring, no Vert.x, no Akka. Just Java.

## Highlights

- **Pure Java** — no runtime dependencies beyond the JDK
- **Two storage backends**, fully interchangeable:
    - `InMemoryStore` — everything in RAM, ideal for fast isolated tests
    - `FileSystemStore` — buckets mapped to directories, data survives restarts
- **Virtual threads** — one virtual thread per request, no thread pool tuning
- **AWS SDK v2 compatible** — handles `aws-chunked` transfer encoding and
  `x-amz-checksum-crc32` trailers out of the box
- **Both addressing styles** — path-style (`/bucket/key`) and virtual-host
  (`bucket.host/key`), configurable
- **Executable JAR and Docker image** — run it standalone or embed it

## Supported operations

| Operation | Notes |
|---|---|
| `CreateBucket` | idempotent |
| `DeleteBucket` | fails on non-empty buckets |
| `HeadBucket` | |
| `ListBuckets` | |
| `ListObjects` (v1) | `prefix`, `delimiter`, `marker`, `max-keys` |
| `ListObjectsV2` | `prefix`, `delimiter`, `continuation-token`, `max-keys` |
| `PutObject` | `aws-chunked` decoding, CRC32 trailer support, object metadata |
| `GetObject` | `Range` header support (`206 Partial Content`), metadata headers |
| `HeadObject` | `Range` header support, metadata headers |
| `DeleteObject` | idempotent |
| `DeleteObjects` | batch, `Quiet` mode, 1000-key limit |
| `CopyObject` | `COPY` and `REPLACE` metadata directives |
| `CreateMultipartUpload` | |
| `UploadPart` | with `aws-chunked` support |
| `CompleteMultipartUpload` | S3-style multipart ETag |
| `AbortMultipartUpload` | |
| `ListParts` | |
| `ListMultipartUploads` | |
| `GetObjectAttributes` | Server-side complete; AWS SDK v2 has a known `ObjectSize` parsing issue |

> **Note on `GetObjectAttributes`:** the AWS SDK for Java v2 has a known
> parsing issue when `ObjectSize` is explicitly requested via
> `x-amz-object-attributes`. S3Forge returns the correct XML regardless;
> the issue is purely client-side.

### Not implemented (by design)

- Authentication — any credentials are accepted; signatures are not verified
- Bucket policies, ACLs, versioning
- Object tagging
- Cross-region replication
- SSE-C / SSE-KMS
- Object lock

These may be added later; see the issue tracker.

## Getting started

S3Forge is not published to a public artifact repository yet. To use it,
build it locally and install it into your Maven cache:

    git clone https://github.com/antonioiorfino/s3forge.git
    cd s3forge
    mvn clean install

Then declare it as a test dependency in your project:

    <dependency>
        <groupId>it.iorfino</groupId>
        <artifactId>s3forge</artifactId>
        <version>0.1.0</version>
        <scope>test</scope>
    </dependency>

### As a test dependency (Gradle)

    testImplementation("it.iorfino:s3forge:0.1.0")

### Embedded in a test (JUnit 5)

    import it.iorfino.s3forge.S3Forge;
    import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
    import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
    import software.amazon.awssdk.core.sync.RequestBody;
    import software.amazon.awssdk.regions.Region;
    import software.amazon.awssdk.services.s3.S3Client;

    import java.net.URI;

    class MyS3Test {

        @Test
        void uploadAndDownload() throws Exception {
            try (S3Forge forge = S3Forge.builder()
                    .port(0)
                    .inMemory()
                    .build()) {

                forge.start();

                S3Client s3 = S3Client.builder()
                        .endpointOverride(URI.create("http://localhost:" + forge.port()))
                        .region(Region.US_EAST_1)
                        .credentialsProvider(StaticCredentialsProvider.create(
                                AwsBasicCredentials.create("test", "test")))
                        .forcePathStyle(true)
                        .build();

                s3.createBucket(b -> b.bucket("my-bucket"));
                s3.putObject(
                        b -> b.bucket("my-bucket").key("hello.txt"),
                        RequestBody.fromString("hello"));
                // ... assertions
            }
        }
    }

The `.port(0)` trick lets the OS pick an ephemeral port, so tests never
collide on port 8001.

### Filesystem persistence

    try (S3Forge forge = S3Forge.builder()
            .port(0)
            .fileSystem(Path.of("/tmp/s3forge-data"))
            .build()) {
        forge.start();
        // data written here survives across restarts
    }

### Virtual-host addressing

    S3Forge forge = S3Forge.builder()
            .port(8001)
            .inMemory()
            .virtualHostDomain("localhost")
            .build();

With this configuration, a request to `http://mybucket.localhost:8001/key`
targets `mybucket` directly, while `http://localhost:8001/mybucket/key`
keeps working in path-style.

## Command-line usage

S3Forge ships as a self-contained executable JAR:

    java -jar target/s3forge.jar --port 8001 --in-memory

Or with filesystem persistence:

    java -jar target/s3forge.jar --port 8001 --file-system /var/lib/s3forge

### Options

| Flag | Description |
|---|---|
| `--port N` | TCP port (default 8001; 0 = ephemeral) |
| `--in-memory` | Use in-memory storage (default) |
| `--file-system PATH` | Use filesystem storage at PATH |
| `--virtual-host DOMAIN` | Enable virtual-host addressing |
| `--help` | Print usage and exit |

## Docker

    # In-memory
    docker run --rm -p 8001:8001 s3forge --port 8001 --in-memory

    # Filesystem, persisted to a volume
    docker run --rm -p 8001:8001 \
      -v $PWD/s3data:/data \
      s3forge --port 8001 --file-system /data

The image uses a `jlink`-generated runtime, so the final layer is under
~80 MB and contains only the modules S3Forge actually needs.

## Building from source

Requirements:

- JDK 21 or later
- Maven 3.9 or later

Build:

    mvn clean package

The build produces `target/s3forge.jar` (executable).

Run the tests:

    mvn test

## Design notes

### Why no framework?

S3Forge targets a narrow use case — an embedded S3 mock for integration
tests. The JDK already provides everything needed:

- `com.sun.net.httpserver.HttpServer` for the HTTP server
- Virtual threads for concurrency
- `javax.xml.parsers` for XML parsing
- `java.util.zip.CRC32` and `java.security.MessageDigest` for integrity

Adding Spring Boot, Vert.x, or Akka would introduce a large dependency
tree, longer startup time, and version conflicts in the host project.
Pure Java keeps S3Forge lightweight and predictable.

### Metadata persistence

The filesystem backend writes a sidecar `.properties` file under a hidden
`.s3forge-meta/` directory for each object, holding its ETag, content type
and CRC32 checksum. Without it, a server restart would lose these values
and break clients that validate them on read.

### aws-chunked decoding

Since AWS SDK for Java v2.30.0, the SDK sends `PutObject` and
`UploadPart` requests with `Content-Encoding: aws-chunked` and embeds
checksums as HTTP trailers. S3Forge decodes this format transparently
and stores the extracted checksum alongside the object, so subsequent
`GET` and `HEAD` responses carry the correct `x-amz-checksum-crc32`.

## Project status

**0.1.0** — first public release. The API surface is stable for the
operations listed above, but the project has not yet reached a 1.0
release, so minor versions may introduce breaking changes. Feedback
and contributions are welcome.

## License

Apache License 2.0. See `LICENSE` for the full text.

S3Forge is a clean-room reimplementation inspired by the concept of
findify/s3mock (MIT). No source code from that project is included.
See `NOTICE` for details.

## Contributing

See `CONTRIBUTING.md`.
