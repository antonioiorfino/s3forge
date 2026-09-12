# Changelog

All notable changes to S3Forge are documented in this file.

The format is based on Keep a Changelog (https://keepachangelog.com/),
and this project adheres to Semantic Versioning (https://semver.org/).

## [Unreleased]

### Added

- Initial public API: `S3Forge.builder().port(n).inMemory()|.fileSystem(path).build()`
- Bucket operations: create, delete, head, list
- Object operations: put, get, head, delete, copy
- `ListObjects` (v1) and `ListObjectsV2` with prefix, delimiter, pagination
- `DeleteObjects` batch with `Quiet` mode
- `Range` header support on `GET` and `HEAD`
- Multipart upload: initiate, upload part, complete, abort, list parts,
  list multipart uploads
- In-memory and filesystem storage backends
- Metadata persistence on filesystem backend via `.s3forge-meta/` sidecar
- `aws-chunked` decoding and `x-amz-checksum-crc32` trailer support
- Virtual-host style addressing (opt-in)
- Command-line launcher (`Main`)
- Executable JAR, `jlink` runtime image, multi-stage Dockerfile
- GitHub Actions CI
