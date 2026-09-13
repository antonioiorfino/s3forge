# Changelog

All notable changes to S3Forge are documented in this file.

The format is based on Keep a Changelog (https://keepachangelog.com/),
and this project adheres to Semantic Versioning (https://semver.org/).

## [Unreleased]

### Added

- Support for standard object metadata headers on `PutObject`,
  `GetObject`, `HeadObject`, and `CopyObject`: `Cache-Control`,
  `Content-Disposition`, `Content-Encoding`, `Content-Language`,
  `Expires`, and user-defined `x-amz-meta-*` entries (#5).

## [0.1.0] - 2026-09-13

### Added

- Initial public API: `S3Forge.builder().port(n).inMemory()|.fileSystem(path).build()`
- Bucket operations: create, delete, head, list, location
- Object operations: put, get, head, delete, copy
- `ListObjects` (v1) and `ListObjectsV2` with prefix, delimiter, pagination
- `DeleteObjects` batch with `Quiet` mode
- `Range` header support on `GET` and `HEAD`
- `GetObjectAttributes` (server-side complete; see note below)
- Multipart upload: initiate, upload part, complete, abort, list parts,
  list multipart uploads
- In-memory and filesystem storage backends
- Metadata persistence on filesystem backend via `.s3forge-meta/` sidecar
- `aws-chunked` decoding and `x-amz-checksum-crc32` trailer support
- Virtual-host style addressing (opt-in)
- Command-line launcher (`Main`)
- Executable JAR, `jlink` runtime image, multi-stage Dockerfile
- GitHub Actions CI

### Known issues

- The AWS SDK for Java v2 has a parsing bug in `GetObjectAttributes` when
  `ObjectSize` is explicitly requested via `x-amz-object-attributes`. The
  S3Forge server returns the correct XML; the issue is purely client-side.

[Unreleased]: https://github.com/antonioiorfino/s3forge/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/antonioiorfino/s3forge/releases/tag/v0.1.0
