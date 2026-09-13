package it.iorfino.s3forge.store;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link MultipartStore}.
 *
 * <p>All bucket and object data is held in {@link ConcurrentHashMap} instances and is lost when the
 * store is discarded. This backend is intended for fast, isolated unit tests where persistence
 * across restarts is not needed.
 *
 * <p>The store keeps two parallel maps per bucket:
 *
 * <ul>
 *   <li>{@link #buckets} maps object keys to raw byte arrays, holding the payloads;
 *   <li>{@link #objectMetadata} maps object keys to {@link StoredObject} instances, holding the
 *       metadata (ETag, content type, checksum, user metadata).
 * </ul>
 *
 * <p>Splitting payloads from metadata allows {@link #getObject} to return a fresh {@link
 * ByteArrayInputStream} on every call without copying the payload on each read, while still keeping
 * the two views consistent.
 *
 * <p>All operations are safe for concurrent use.
 *
 * @since 0.1.0
 */
public final class InMemoryStore implements MultipartStore {

    /** bucket -> key -> payload bytes. */
    private final Map<String, Map<String, byte[]>> buckets = new ConcurrentHashMap<>();

    /** bucket -> key -> stored metadata. */
    private final Map<String, Map<String, StoredObject>> objectMetadata = new ConcurrentHashMap<>();

    /** uploadId -> in-progress multipart upload. */
    private final Map<String, MultipartUpload> uploads = new ConcurrentHashMap<>();

    /**
     * {@inheritDoc}
     *
     * <p>Creates the bucket if it does not exist. This method is idempotent.
     */
    @Override
    public void createBucket(String bucket) {
        buckets.computeIfAbsent(bucket, k -> new ConcurrentHashMap<>());
        objectMetadata.computeIfAbsent(bucket, k -> new ConcurrentHashMap<>());
    }

    /** {@inheritDoc} */
    @Override
    public boolean bucketExists(String bucket) {
        return buckets.containsKey(bucket);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IOException with message {@code "NoSuchBucket"} if the bucket does not exist, or
     *     {@code "BucketNotEmpty"} if it still contains objects
     */
    @Override
    public void deleteBucket(String bucket) throws IOException {
        Map<String, byte[]> b = buckets.get(bucket);
        if (b == null) throw new IOException("NoSuchBucket");
        if (!b.isEmpty()) throw new IOException("BucketNotEmpty");
        buckets.remove(bucket);
        objectMetadata.remove(bucket);
    }

    /** {@inheritDoc} */
    @Override
    public List<String> listBuckets() {
        return new ArrayList<>(buckets.keySet());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Stores the payload as a defensive copy of the request bytes and records the associated
     * metadata. The metadata map is stored as an immutable copy; callers may reuse their own map
     * afterwards.
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
        Map<String, byte[]> b = buckets.get(bucket);
        if (b == null) throw new IOException("NoSuchBucket");

        byte[] bytes = data.readAllBytes();
        b.put(key, bytes);
        objectMetadata
                .get(bucket)
                .put(
                        key,
                        new StoredObject(
                                bucket,
                                key,
                                bytes.length,
                                etag,
                                contentType,
                                Instant.now(),
                                checksumCrc32,
                                metadata,
                                null));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns a fresh {@link ByteArrayInputStream} over the stored bytes on every call, so that
     * concurrent readers do not interfere with each other.
     */
    @Override
    public Optional<StoredObject> getObject(String bucket, String key) {
        Map<String, byte[]> b = buckets.get(bucket);
        if (b == null) return Optional.empty();
        byte[] bytes = b.get(key);
        if (bytes == null) return Optional.empty();

        StoredObject meta = objectMetadata.get(bucket).get(key);
        return Optional.of(
                new StoredObject(
                        bucket,
                        key,
                        bytes.length,
                        meta.etag(),
                        meta.contentType(),
                        meta.lastModified(),
                        meta.checksumCrc32(),
                        meta.metadata(),
                        new ByteArrayInputStream(bytes)));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Removing a non-existent key is a no-op.
     */
    @Override
    public void deleteObject(String bucket, String key) throws IOException {
        Map<String, byte[]> b = buckets.get(bucket);
        if (b == null) throw new IOException("NoSuchBucket");
        b.remove(key);
        objectMetadata.get(bucket).remove(key);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Non-existent keys are silently ignored.
     */
    @Override
    public void deleteObjects(String bucket, List<String> keys) throws IOException {
        Map<String, byte[]> b = buckets.get(bucket);
        if (b == null) throw new IOException("NoSuchBucket");
        keys.forEach(
                k -> {
                    b.remove(k);
                    objectMetadata.get(bucket).remove(k);
                });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Iterates the keyspace in lexicographic order using a {@link TreeMap} snapshot, so that
     * concurrent writes do not disturb an in-progress listing.
     */
    @Override
    public ListResult listObjects(
            String bucket,
            String prefix,
            String delimiter,
            int maxKeys,
            String marker,
            String continuationToken) {
        Map<String, StoredObject> meta = objectMetadata.get(bucket);
        if (meta == null) return ListResult.empty();

        String p = prefix == null ? "" : prefix;
        String startAfter = continuationToken != null ? continuationToken : marker;

        TreeMap<String, StoredObject> sorted = new TreeMap<>(meta);

        List<StoredObject> objects = new ArrayList<>();
        List<String> commonPrefixes = new ArrayList<>();
        boolean limit = maxKeys > 0;
        String lastEmitted = null;

        for (var e : sorted.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(p)) continue;
            if (startAfter != null && key.compareTo(startAfter) <= 0) continue;

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
                objects.add(StoredObject.summary(e.getValue()));
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
     * <p>Generates a fresh upload id using {@link java.util.UUID} and records the upload in an
     * internal map keyed by that id.
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
     * <p>Concatenates the bytes of each requested part in order. The final ETag follows the S3
     * multipart convention: the MD5 of the concatenation of the parts' binary MD5 digests, suffixed
     * with {@code -<partCount>}. The metadata recorded at initiation time is applied to the
     * completed object.
     */
    @Override
    public StoredObject completeMultipart(String bucket, String uploadId, List<Integer> partNumbers)
            throws IOException {
        MultipartUpload up =
                getMultipart(bucket, uploadId).orElseThrow(() -> new IOException("NoSuchUpload"));

        List<Integer> sorted = new ArrayList<>(partNumbers);
        java.util.Collections.sort(sorted);
        if (!sorted.equals(partNumbers)) {
            throw new IOException("InvalidPartOrder");
        }

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

        return objectMetadata.get(bucket).get(up.key());
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
                .sorted(java.util.Comparator.comparing(MultipartUpload::initiated))
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
