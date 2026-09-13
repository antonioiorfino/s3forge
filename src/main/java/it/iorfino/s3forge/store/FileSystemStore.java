package it.iorfino.s3forge.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Filesystem-backed implementation of {@link MultipartStore}.
 *
 * <p>Buckets are represented as directories under a configurable root, and objects as files within
 * those directories. Keys containing forward slashes map to nested directories, so a key {@code
 * a/b/c.txt} becomes {@code <root>/<bucket>/a/b/c.txt} on disk.
 *
 * <p>Object metadata (ETag, content type, CRC32 checksum, and user metadata headers) is persisted
 * in a sidecar {@code .properties} file under a hidden {@code
 * .s3forge-meta/<bucket>/<key>.properties} tree, so it survives server restarts. The hidden tree is
 * never exposed as a bucket by {@link #listBuckets()}.
 *
 * <p>Multipart uploads buffer their parts in memory and only write the concatenated object to disk
 * at completion time. This keeps the implementation simple at the cost of memory proportional to
 * the total size of in-flight multipart uploads.
 *
 * <p>All operations are safe for concurrent use.
 *
 * @since 0.1.0
 */
public final class FileSystemStore implements MultipartStore {

    /** Directory name under the root that holds all sidecar metadata files. */
    private static final String META_DIR = ".s3forge-meta";

    /** Root directory containing all bucket directories. */
    private final Path root;

    /** uploadId -> in-progress multipart upload (parts held in memory). */
    private final Map<String, MultipartUpload> uploads = new ConcurrentHashMap<>();

    /**
     * Creates a new store rooted at the given directory.
     *
     * <p>The directory is not created here; it is created lazily by {@link #createBucket} when the
     * first bucket is added.
     *
     * @param root the root directory; must not be {@code null}
     */
    public FileSystemStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * Resolves the on-disk directory for a bucket.
     *
     * @param bucket the bucket name
     * @return the bucket directory path
     * @throws SecurityException if the resolved path escapes the root
     */
    private Path bucketPath(String bucket) {
        Path p = root.resolve(bucket).normalize();
        if (!p.startsWith(root)) throw new SecurityException("invalid bucket");
        return p;
    }

    /**
     * Resolves the on-disk file for an object.
     *
     * @param bucket the bucket name
     * @param key the object key
     * @return the object file path
     * @throws SecurityException if the resolved path escapes the bucket
     */
    private Path objectPath(String bucket, String key) {
        Path p = bucketPath(bucket).resolve(key).normalize();
        if (!p.startsWith(bucketPath(bucket))) {
            throw new SecurityException("path traversal detected: " + key);
        }
        return p;
    }

    /**
     * Resolves the metadata directory for a bucket.
     *
     * @param bucket the bucket name
     * @return the metadata directory path
     */
    private Path metaBucketPath(String bucket) {
        return root.resolve(META_DIR).resolve(bucket);
    }

    /**
     * Resolves the sidecar metadata file for an object.
     *
     * @param bucket the bucket name
     * @param key the object key
     * @return the metadata file path
     * @throws SecurityException if the resolved path escapes the metadata tree
     */
    private Path metaObjectPath(String bucket, String key) {
        Path p = metaBucketPath(bucket).resolve(key + ".properties").normalize();
        if (!p.startsWith(metaBucketPath(bucket))) {
            throw new SecurityException("path traversal detected: " + key);
        }
        return p;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IOException with message {@code "InvalidBucketName"} if the bucket name collides with
     *     the reserved metadata directory
     */
    @Override
    public void createBucket(String bucket) throws IOException {
        if (META_DIR.equals(bucket)) {
            throw new IOException("InvalidBucketName");
        }
        Files.createDirectories(bucketPath(bucket));
        Files.createDirectories(metaBucketPath(bucket));
    }

    /** {@inheritDoc} */
    @Override
    public boolean bucketExists(String bucket) {
        return Files.isDirectory(bucketPath(bucket));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Also removes the corresponding metadata directory on a best-effort basis. Failures to
     * remove individual metadata files are ignored, since stale metadata for a deleted bucket is
     * harmless.
     */
    @Override
    public void deleteBucket(String bucket) throws IOException {
        Path b = bucketPath(bucket);
        if (!Files.isDirectory(b)) throw new IOException("NoSuchBucket");
        try (Stream<Path> entries = Files.list(b)) {
            if (entries.findAny().isPresent()) throw new IOException("BucketNotEmpty");
        }
        Files.delete(b);

        Path meta = metaBucketPath(bucket);
        if (Files.isDirectory(meta)) {
            try (Stream<Path> walk = Files.walk(meta)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(
                                p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException ignored) {
                                        /* best effort */
                                    }
                                });
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The hidden metadata directory is excluded from the listing.
     */
    @Override
    public List<String> listBuckets() throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> !META_DIR.equals(p.getFileName().toString()))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Writes the payload to a file and persists the metadata to a sidecar {@code .properties}
     * file. Existing files are replaced.
     */
    @Override
    public void putObject(
            String bucket,
            String key,
            InputStream data,
            long contentLength,
            String contentType,
            String etag,
            String checksumCrc32,
            Map<String, String> metadata)
            throws IOException {
        Path p = objectPath(bucket, key);
        Files.createDirectories(p.getParent());
        Files.copy(data, p, StandardCopyOption.REPLACE_EXISTING);

        new ObjectMetadata(etag, contentType, checksumCrc32, metadata)
                .write(metaObjectPath(bucket, key));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads the payload size, last-modified timestamp, and all metadata fields from the sidecar
     * file. If no sidecar exists (for example, for objects created before metadata persistence was
     * introduced), the content type is probed from the filesystem and the other fields are left
     * empty.
     */
    @Override
    public Optional<StoredObject> getObject(String bucket, String key) throws IOException {
        Path p = objectPath(bucket, key);
        if (!Files.isRegularFile(p)) return Optional.empty();

        ObjectMetadata meta = ObjectMetadata.read(metaObjectPath(bucket, key));
        String contentType =
                meta.contentType().isEmpty() ? Files.probeContentType(p) : meta.contentType();

        return Optional.of(
                new StoredObject(
                        bucket,
                        key,
                        Files.size(p),
                        meta.etag(),
                        contentType,
                        Files.getLastModifiedTime(p).toInstant(),
                        meta.checksumCrc32(),
                        meta.metadata(),
                        Files.newInputStream(p)));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Removes both the object file and its sidecar metadata file, and prunes any now-empty
     * intermediate directories in both trees.
     */
    @Override
    public void deleteObject(String bucket, String key) throws IOException {
        Path p = objectPath(bucket, key);
        Files.deleteIfExists(p);
        ObjectMetadata.delete(metaObjectPath(bucket, key));

        // Prune empty intermediate directories in the object tree.
        Path parent = p.getParent();
        Path b = bucketPath(bucket);
        while (parent != null && !parent.equals(b) && Files.isDirectory(parent)) {
            try (Stream<Path> s = Files.list(parent)) {
                if (s.findAny().isPresent()) break;
            }
            Files.delete(parent);
            parent = parent.getParent();
        }

        // Prune empty intermediate directories in the metadata tree.
        Path metaParent = metaObjectPath(bucket, key).getParent();
        Path metaBucket = metaBucketPath(bucket);
        while (metaParent != null
                && !metaParent.equals(metaBucket)
                && Files.isDirectory(metaParent)) {
            try (Stream<Path> s = Files.list(metaParent)) {
                if (s.findAny().isPresent()) break;
            }
            Files.delete(metaParent);
            metaParent = metaParent.getParent();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void deleteObjects(String bucket, List<String> keys) throws IOException {
        for (String k : keys) deleteObject(bucket, k);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Walks the bucket directory recursively, collecting all regular files whose relative path
     * (with forward slashes) starts with the given prefix. Keys are then sorted lexicographically
     * and filtered through the optional delimiter, grouping matching keys into common prefixes.
     *
     * <p>Metadata for each listed object is read from its sidecar file, so the returned summaries
     * carry the same metadata as a full {@link #getObject} would.
     */
    @Override
    public ListResult listObjects(
            String bucket,
            String prefix,
            String delimiter,
            int maxKeys,
            String marker,
            String continuationToken)
            throws IOException {
        Path b = bucketPath(bucket);
        if (!Files.isDirectory(b)) return ListResult.empty();

        String p = prefix == null ? "" : prefix;
        String startAfter = continuationToken != null ? continuationToken : marker;

        List<String> keys = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(b)) {
            walk.filter(Files::isRegularFile)
                    .map(file -> b.relativize(file).toString().replace('\\', '/'))
                    .filter(k -> k.startsWith(p))
                    .filter(k -> startAfter == null || k.compareTo(startAfter) > 0)
                    .sorted()
                    .forEach(keys::add);
        }

        List<StoredObject> objects = new ArrayList<>();
        List<String> commonPrefixes = new ArrayList<>();
        boolean limit = maxKeys > 0;
        String lastEmitted = null;

        for (String key : keys) {
            String entry;
            boolean isCommonPrefix = false;
            if (delimiter != null && !delimiter.isEmpty()) {
                int idx = key.indexOf(delimiter, p.length());
                if (idx >= 0) {
                    entry = key.substring(0, idx + delimiter.length());
                    isCommonPrefix = true;
                } else {
                    entry = key;
                }
            } else {
                entry = key;
            }
            if (isCommonPrefix && commonPrefixes.contains(entry)) continue;

            int currentCount = objects.size() + commonPrefixes.size();
            if (limit && currentCount >= maxKeys) {
                return ListResult.truncated(objects, commonPrefixes, lastEmitted, lastEmitted);
            }

            if (isCommonPrefix) {
                commonPrefixes.add(entry);
            } else {
                Path file = b.resolve(key);
                ObjectMetadata m = ObjectMetadata.read(metaObjectPath(bucket, key));
                String contentType =
                        m.contentType().isEmpty() ? Files.probeContentType(file) : m.contentType();
                objects.add(
                        new StoredObject(
                                bucket,
                                key,
                                Files.size(file),
                                m.etag(),
                                contentType,
                                Files.getLastModifiedTime(file).toInstant(),
                                m.checksumCrc32(),
                                m.metadata(),
                                null));
            }
            lastEmitted = entry;
        }

        return ListResult.of(objects, commonPrefixes);
    }

    // ------------------------------------------------------------------
    // Multipart
    // ------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Part payloads are buffered in memory until the upload is completed, at which point the
     * concatenated object is written to disk via {@link #putObject}.
     */
    @Override
    public MultipartUpload initiateMultipart(
            String bucket, String key, String contentType, Map<String, String> metadata)
            throws IOException {
        if (!bucketExists(bucket)) throw new IOException("NoSuchBucket");
        String uploadId = java.util.UUID.randomUUID().toString();
        MultipartUpload up = new MultipartUpload(uploadId, bucket, key, contentType, metadata);
        uploads.put(uploadId, up);
        return up;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<MultipartUpload> getMultipart(String bucket, String uploadId) {
        MultipartUpload up = uploads.get(uploadId);
        if (up == null || !up.bucket().equals(bucket)) return Optional.empty();
        return Optional.of(up);
    }

    /** {@inheritDoc} */
    @Override
    public void uploadPart(String bucket, String uploadId, int partNumber, byte[] data, String etag)
            throws IOException {
        MultipartUpload up =
                getMultipart(bucket, uploadId).orElseThrow(() -> new IOException("NoSuchUpload"));
        up.putPart(partNumber, data, etag);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Buffers the concatenation in memory, computes the S3 multipart ETag and CRC32, and writes
     * the result to disk through {@link #putObject}. The metadata recorded at initiation time is
     * applied to the completed object.
     */
    @Override
    public StoredObject completeMultipart(String bucket, String uploadId, List<Integer> partNumbers)
            throws IOException {
        MultipartUpload up =
                getMultipart(bucket, uploadId).orElseThrow(() -> new IOException("NoSuchUpload"));

        List<Integer> sorted = new ArrayList<>(partNumbers);
        java.util.Collections.sort(sorted);
        if (!sorted.equals(partNumbers)) throw new IOException("InvalidPartOrder");

        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.security.MessageDigest md5OfMd5s;
        try {
            md5OfMd5s = java.security.MessageDigest.getInstance("MD5");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }

        for (int pn : partNumbers) {
            byte[] part = up.part(pn);
            if (part == null) throw new IOException("InvalidPart");
            buf.write(part);
            md5OfMd5s.update(hexToBytes(up.partEtag(pn)));
        }

        byte[] body = buf.toByteArray();
        String finalEtag =
                java.util.HexFormat.of().formatHex(md5OfMd5s.digest()) + "-" + partNumbers.size();

        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(body);
        byte[] crcBytes =
                new byte[] {
                    (byte) (crc.getValue() >>> 24),
                    (byte) (crc.getValue() >>> 16),
                    (byte) (crc.getValue() >>> 8),
                    (byte) crc.getValue()
                };
        String crc32 = java.util.Base64.getEncoder().encodeToString(crcBytes);

        String contentType =
                up.contentType() != null ? up.contentType() : "application/octet-stream";

        try (var in = new java.io.ByteArrayInputStream(body)) {
            putObject(
                    bucket,
                    up.key(),
                    in,
                    body.length,
                    contentType,
                    finalEtag,
                    crc32,
                    up.metadata());
        }

        uploads.remove(uploadId);
        return getObject(bucket, up.key()).orElseThrow();
    }

    /** {@inheritDoc} */
    @Override
    public void abortMultipart(String bucket, String uploadId) throws IOException {
        MultipartUpload up = uploads.remove(uploadId);
        if (up == null || !up.bucket().equals(bucket)) {
            throw new IOException("NoSuchUpload");
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<MultipartUpload> listMultipartUploads(String bucket) {
        return uploads.values().stream()
                .filter(u -> u.bucket().equals(bucket))
                .sorted(Comparator.comparing(MultipartUpload::initiated))
                .toList();
    }

    /**
     * Converts a lowercase hex string into its byte representation.
     *
     * @param hex the hex string; must have even length
     * @return the decoded bytes
     */
    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] =
                    (byte)
                            ((Character.digit(hex.charAt(i), 16) << 4)
                                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }
}
