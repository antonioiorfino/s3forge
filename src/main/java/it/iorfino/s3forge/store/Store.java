package it.iorfino.s3forge.store;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Storage abstraction used by S3Forge.
 *
 * <p>The HTTP layer only ever talks to this interface, never to a concrete implementation. This
 * makes the in-memory and filesystem backends fully interchangeable and allows new backends (e.g.
 * an on-disk embedded database) to be added without touching the request-handling code.
 *
 * <p><strong>Contract for implementations:</strong>
 *
 * <ul>
 *   <li>Methods that target a non-existent bucket must throw {@link IOException} with message
 *       {@code "NoSuchBucket"}.
 *   <li>{@link #deleteBucket(String)} must throw {@link IOException} with message {@code
 *       "BucketNotEmpty"} if the bucket still contains objects.
 *   <li>{@link #getObject(String, String)} returns {@link Optional#empty()} when the key does not
 *       exist.
 *   <li>All methods must be safe for concurrent use.
 * </ul>
 *
 * @since 0.1.0
 */
public interface Store {

    // ------------------------------------------------------------------
    // Bucket operations
    // ------------------------------------------------------------------

    /**
     * Creates a new bucket. If the bucket already exists, this method is a no-op (idempotent).
     *
     * @param bucket the bucket name; must not be {@code null}
     * @throws IOException if the bucket cannot be created
     */
    void createBucket(String bucket) throws IOException;

    /**
     * Checks whether a bucket exists.
     *
     * @param bucket the bucket name; must not be {@code null}
     * @return {@code true} if the bucket exists, {@code false} otherwise
     */
    boolean bucketExists(String bucket);

    /**
     * Deletes an empty bucket.
     *
     * @param bucket the bucket name; must not be {@code null}
     * @throws IOException with message {@code "NoSuchBucket"} if the bucket does not exist, or
     *     {@code "BucketNotEmpty"} if it still contains objects
     */
    void deleteBucket(String bucket) throws IOException;

    /**
     * Returns the names of all existing buckets, in unspecified order.
     *
     * @return a list of bucket names; never {@code null}, possibly empty
     * @throws IOException if the underlying storage cannot be read
     */
    List<String> listBuckets() throws IOException;

    // ------------------------------------------------------------------
    // Object operations
    // ------------------------------------------------------------------

    /**
     * Stores an object, replacing any existing object with the same key.
     *
     * <p>The stream is fully consumed by this call; callers must not rely on it being readable
     * afterwards.
     *
     * <p>The {@code etag}, {@code checksumCrc32} and {@code metadata} parameters are computed by
     * the HTTP layer (see {@code ObjectHandler}) and stored verbatim so that subsequent {@code GET}
     * and {@code HEAD} requests can return them without recomputation.
     *
     * <p>The {@code metadata} map holds the S3 object metadata headers: the five standard headers
     * ({@code cache-control}, {@code content-disposition}, {@code content-encoding}, {@code
     * content-language}, {@code expires}) and any user-defined {@code x-amz-meta-*} entries. Keys
     * must be lowercase. The map may be {@code null}, in which case it is treated as empty.
     *
     * @param bucket the target bucket; must exist
     * @param key the object key; must not be {@code null}
     * @param data the object payload; must not be {@code null}
     * @param contentLength the payload length in bytes, or {@code -1} if unknown
     * @param contentType the MIME type, or {@code null} for a default
     * @param etag the pre-computed ETag value (hex MD5, without surrounding quotes), or {@code
     *     null}
     * @param checksumCrc32 the pre-computed CRC32 checksum encoded as a Base64 big-endian string,
     *     or {@code null}
     * @param metadata object metadata headers, lowercase keys; may be {@code null} (treated as
     *     empty)
     * @throws IOException with message {@code "NoSuchBucket"} if the bucket does not exist, or on
     *     any I/O error
     */
    void putObject(
            String bucket,
            String key,
            InputStream data,
            long contentLength,
            String contentType,
            String etag,
            String checksumCrc32,
            Map<String, String> metadata)
            throws IOException;

    /**
     * Retrieves an object along with its metadata.
     *
     * <p>The returned {@link StoredObject} carries a fresh {@link InputStream} positioned at the
     * beginning of the payload. Callers are responsible for closing it.
     *
     * @param bucket the bucket name
     * @param key the object key
     * @return an {@link Optional} containing the object if found, or {@link Optional#empty()}
     *     otherwise
     * @throws IOException on any I/O error
     */
    Optional<StoredObject> getObject(String bucket, String key) throws IOException;

    /**
     * Deletes a single object. Deleting a non-existent key is a no-op (S3 semantics).
     *
     * @param bucket the bucket name; must exist
     * @param key the object key; must not be {@code null}
     * @throws IOException with message {@code "NoSuchBucket"} if the bucket does not exist
     */
    void deleteObject(String bucket, String key) throws IOException;

    /**
     * Deletes multiple objects in a single call.
     *
     * <p>Non-existent keys are silently ignored, matching the semantics of {@code DeleteObjects} in
     * the S3 API.
     *
     * @param bucket the bucket name; must exist
     * @param keys the keys to delete; must not be {@code null}
     * @throws IOException with message {@code "NoSuchBucket"} if the bucket does not exist
     */
    void deleteObjects(String bucket, List<String> keys) throws IOException;

    /**
     * Lists objects in a bucket with prefix filtering, delimiter grouping and pagination, matching
     * the semantics of the S3 {@code ListObjectsV2} API.
     *
     * <p>Iteration is lexicographic by key. Pagination works as follows:
     *
     * <ul>
     *   <li>If {@code continuationToken} is non-null, iteration starts at the first key strictly
     *       greater than the token.
     *   <li>Otherwise, if {@code marker} is non-null, iteration starts at the first key strictly
     *       greater than the marker (v1 semantics).
     *   <li>Otherwise, iteration starts at the first matching key.
     * </ul>
     *
     * <p>The {@code maxKeys} limit applies to {@code objects.size() + commonPrefixes.size()},
     * matching S3 behavior. When the limit is hit and more keys remain, the result is marked
     * truncated and the appropriate next marker/token is populated.
     *
     * @param bucket the bucket name; must exist
     * @param prefix filter for keys starting with this string, or {@code null}/empty for no
     *     filtering
     * @param delimiter grouping character (typically {@code "/"}), or {@code null}/empty for a flat
     *     listing
     * @param maxKeys maximum number of entries to return; {@code 0} or negative means "no limit"
     * @param marker v1 pagination marker, or {@code null}
     * @param continuationToken v2 pagination token, or {@code null}
     * @return a {@link ListResult}; never {@code null}
     * @throws IOException on any I/O error
     */
    ListResult listObjects(
            String bucket,
            String prefix,
            String delimiter,
            int maxKeys,
            String marker,
            String continuationToken)
            throws IOException;
}
