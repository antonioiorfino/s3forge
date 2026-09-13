package it.iorfino.s3forge.store;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable metadata and (optionally) payload of a stored object.
 *
 * <p>The {@link #data} field is {@code null} for "summary" instances returned by listing
 * operations, where the payload is not needed. When non-null, the stream is positioned at the start
 * of the payload and belongs to the caller, who must close it.
 *
 * <p>The {@link #metadata} map holds the S3 object metadata headers that were supplied at upload
 * time: the five standard headers ({@code Cache-Control}, {@code Content-Disposition}, {@code
 * Content-Encoding}, {@code Content-Language}, {@code Expires}) and any user-defined {@code
 * x-amz-meta-*} entries. Keys are lowercase, matching S3 normalization. The map is never {@code
 * null}; it may be empty.
 *
 * @param bucket owning bucket name
 * @param key object key
 * @param size payload size in bytes
 * @param etag ETag value (hex MD5, without surrounding quotes); never {@code null}, defaults to
 *     empty string
 * @param contentType MIME type; never {@code null}, defaults to {@code application/octet-stream}
 * @param lastModified last modification instant
 * @param checksumCrc32 Base64-encoded big-endian CRC32 checksum, or empty string if not available;
 *     never {@code null}
 * @param metadata object metadata headers, lowercase keys; never {@code null}, possibly empty
 * @param parts multipart part descriptors, ordered by part number; never {@code null}, empty for
 *     single-part objects
 * @param data payload stream, or {@code null} for summaries
 * @since 0.1.0
 */
public record StoredObject(
        String bucket,
        String key,
        long size,
        String etag,
        String contentType,
        Instant lastModified,
        String checksumCrc32,
        Map<String, String> metadata,
        List<PartInfo> parts,
        InputStream data) {
    /** Compact constructor applying default values and defensive copies. */
    public StoredObject {
        if (etag == null) etag = "";
        if (contentType == null) contentType = "application/octet-stream";
        if (checksumCrc32 == null) checksumCrc32 = "";
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        parts = parts == null ? List.of() : List.copyOf(parts);
    }

    /**
     * Returns whether this object was created via a multipart upload.
     *
     * <p>An object is considered multipart if at least one part descriptor is present. Single-part
     * objects uploaded with {@code PutObject} always return {@code false}.
     *
     * @return {@code true} if the object was assembled from multiple parts
     */
    public boolean isMultipart() {
        return !parts.isEmpty();
    }

    /**
     * Returns a copy of this object without the payload stream, suitable for list responses.
     *
     * <p>The returned instance preserves all metadata, including the {@link #metadata} map and the
     * {@link #parts} list, so that a client which only performed a listing can still inspect them
     * without a full {@code GET}.
     *
     * @param full the source object; must not be {@code null}
     * @return a summary instance with {@code data == null}
     */
    public static StoredObject summary(StoredObject full) {
        return new StoredObject(
                full.bucket,
                full.key,
                full.size,
                full.etag,
                full.contentType,
                full.lastModified,
                full.checksumCrc32,
                full.metadata,
                full.parts,
                null);
    }
}
