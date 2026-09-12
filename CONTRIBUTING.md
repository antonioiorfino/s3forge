# Contributing to S3Forge

Thanks for your interest in improving S3Forge. This guide covers the
basics: how to build, test, and submit changes.

## Code of conduct

By participating in this project you agree to abide by the
Code of Conduct. Be kind, be patient, assume good faith.

## Prerequisites

- JDK 21
- Maven 3.9+

## Building

    mvn clean package

## Running the tests

    mvn test

Individual test classes:

    mvn test -Dtest=BucketOperationsTest
    mvn test -Dtest='ListObjects*'

## Coding conventions

- **Java 21** features are welcome: records, sealed types, pattern
  matching, virtual threads, text blocks.
- **Javadoc in English**, on every public class and method. Class-level
  Javadoc should explain *why* the class exists, not just *what* it does.
- **No new runtime dependencies** unless there is a compelling reason.
  The project's main selling point is that it runs on the JDK alone.
- **Tests use the AWS SDK v2**, not raw HTTP, whenever possible. This
  validates real client-server compatibility.
- **No wildcard imports.** Explicit imports only.
- **Line length**: aim for 90 characters, hard limit 100.

## Project layout

    src/main/java/io/github/s3forge/
    ├── S3Forge.java              public entry point
    ├── S3ForgeServer.java        HTTP server wrapper
    ├── cli/Main.java             command-line launcher
    ├── config/                   configuration records
    ├── http/                     handlers and routing
    ├── model/                    S3 error definitions
    ├── store/                    storage backends
    ├── util/                     helpers (ETag, Range, aws-chunked)
    └── xml/                      XML reading and writing

## Adding a new S3 operation

1. If the operation touches storage, extend the `Store` interface (and
   `MultipartStore` for multipart operations).
2. Implement it in `InMemoryStore` and `FileSystemStore`.
3. Add a handler method in `http/`, or extend an existing handler.
4. Route the request in `Router`.
5. Add an end-to-end test using the AWS SDK v2.
6. Update the supported-operations table in `README.md`.

## Submitting changes

1. Fork the repository.
2. Create a feature branch: `git checkout -b feature/my-change`.
3. Commit with a clear message (see below).
4. Push and open a pull request against `main`.

### Commit messages

Use the imperative mood and a short subject line:

    Add support for GetBucketLocation

    The AWS SDK calls this operation during region discovery. Returning a
    static "us-east-1" is enough for a mock.

    Closes #42.

## Reporting bugs

Open an issue with:

- S3Forge version
- JDK version
- Minimal reproduction (ideally a failing JUnit test)
- Expected vs. actual behavior

## License

By contributing, you agree that your contributions are licensed under
the Apache License 2.0, without any additional terms or conditions.
