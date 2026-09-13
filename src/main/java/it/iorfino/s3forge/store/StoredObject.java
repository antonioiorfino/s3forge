package it.iorfino.s3forge.store;

import java.io.InputStream;
import java.time.Instant;

/**
 * Immutable metadata and (optionally) payload of a stored object.
 *
 * <p>The {@link #data} field is {@code null} for "summary" instances returned by listing
 * operations, where the payload is not needed. When non-null, the stream is positioned at the start
 * of the payload and belongs to the caller, who must close it.
 *
 * <p>The {@link #checksumCrc32} field stores the S3-compatible CRC32 checksum as a Base64-encoded
 * big-endian string, ready to be echoed back in the {@code x-amz-checksum-crc32} response header.
 * It is computed once at upload time and never recomputed.
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
        InputStream data) {
    /**
     * Compact constructor applying default values for nullable fields.
     *
     * <p>{@code etag}, {@code contentType} and {@code checksumCrc32} are normalized to empty
     * strings when {@code null}, so that downstream code never has to perform null checks.
     */
    public StoredObject {
        if (etag == null) etag = "";
        if (contentType == null) contentType = "application/octet-stream";
        if (checksumCrc32 == null) checksumCrc32 = "";
    }

    /**
     * Returns a copy of this object without the payload stream, suitable for list responses.
     *
     * <p>The returned instance preserves all metadata, including the CRC32 checksum, so that a
     * client which only performed a listing can still retrieve the checksum later without a full
     * {@code GET}.
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
                null);
    }
}
