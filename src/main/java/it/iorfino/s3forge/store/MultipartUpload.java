package it.iorfino.s3forge.store;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable state for an in-progress S3 multipart upload.
 *
 * <p>An upload is identified by a server-generated {@link #uploadId} and is bound to a destination
 * bucket and key. Parts are stored keyed by their 1-based part number; the S3 API allows up to
 * 10,000 parts per upload.
 *
 * <p>The content type and metadata map recorded at initiation time are applied to the completed
 * object when {@link MultipartStore#completeMultipart} is called, matching the behavior of real S3
 * where the {@code CreateMultipartUpload} request carries the object metadata.
 *
 * <p>This class is thread-safe: parts may be uploaded concurrently, and the maps are backed by
 * {@link ConcurrentHashMap}. Ordering is enforced at completion time by iterating a {@link TreeMap}
 * view.
 *
 * @since 0.1.0
 */
public final class MultipartUpload {

    /** Maximum number of parts allowed per upload, matching S3. */
    public static final int MAX_PARTS = 10_000;

    private final String uploadId;
    private final String bucket;
    private final String key;
    private final String contentType;
    private final Map<String, String> metadata;
    private final Instant initiated;

    private final Map<Integer, byte[]> parts = new ConcurrentHashMap<>();
    private final Map<Integer, String> partEtags = new ConcurrentHashMap<>();

    /**
     * Creates a new multipart upload.
     *
     * @param uploadId the server-generated upload identifier
     * @param bucket the destination bucket
     * @param key the destination key
     * @param contentType the MIME type to assign at completion, or {@code null} for a default
     * @param metadata object metadata headers to assign at completion, lowercase keys; may be
     *     {@code null} (treated as empty)
     */
    public MultipartUpload(
            String uploadId,
            String bucket,
            String key,
            String contentType,
            Map<String, String> metadata) {
        this.uploadId = uploadId;
        this.bucket = bucket;
        this.key = key;
        this.contentType = contentType;
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        this.initiated = Instant.now();
    }

    /**
     * Returns the upload identifier.
     *
     * @return the upload id; never {@code null}
     */
    public String uploadId() {
        return uploadId;
    }

    /**
     * Returns the destination bucket.
     *
     * @return the bucket name; never {@code null}
     */
    public String bucket() {
        return bucket;
    }

    /**
     * Returns the destination key.
     *
     * @return the key; never {@code null}
     */
    public String key() {
        return key;
    }

    /**
     * Returns the MIME type to assign to the completed object.
     *
     * @return the content type, or {@code null}
     */
    public String contentType() {
        return contentType;
    }

    /**
     * Returns the metadata headers to assign to the completed object.
     *
     * @return an immutable map of metadata headers, lowercase keys; never {@code null}, possibly
     *     empty
     */
    public Map<String, String> metadata() {
        return metadata;
    }

    /**
     * Returns the instant at which the upload was initiated.
     *
     * @return the initiation timestamp; never {@code null}
     */
    public Instant initiated() {
        return initiated;
    }

    /**
     * Stores the payload and ETag for a given part number, replacing any existing value for the
     * same part.
     *
     * @param partNumber the 1-based part number; must be in {@code 1..MAX_PARTS}
     * @param data the part payload; must not be {@code null}
     * @param etag the part ETag (hex MD5, no quotes); must not be {@code null}
     * @throws IllegalArgumentException if {@code partNumber} is out of range
     */
    public void putPart(int partNumber, byte[] data, String etag) {
        if (partNumber < 1 || partNumber > MAX_PARTS) {
            throw new IllegalArgumentException("Invalid part number: " + partNumber);
        }
        parts.put(partNumber, data);
        partEtags.put(partNumber, etag);
    }

    /**
     * Returns the payload of a part, or {@code null} if absent.
     *
     * @param partNumber the 1-based part number
     * @return the payload, or {@code null}
     */
    public byte[] part(int partNumber) {
        return parts.get(partNumber);
    }

    /**
     * Returns the ETag of a part, or {@code null} if absent.
     *
     * @param partNumber the 1-based part number
     * @return the hex MD5 ETag, or {@code null}
     */
    public String partEtag(int partNumber) {
        return partEtags.get(partNumber);
    }

    /**
     * Returns the number of parts received so far.
     *
     * @return the part count
     */
    public int partCount() {
        return parts.size();
    }

    /**
     * Returns a snapshot of the parts received so far, keyed by part number and ordered ascending.
     *
     * @return a sorted map view of parts; never {@code null}
     */
    public TreeMap<Integer, byte[]> sortedParts() {
        return new TreeMap<>(parts);
    }

    /**
     * Returns a snapshot of the part ETags received so far, keyed by part number and ordered
     * ascending.
     *
     * @return a sorted map of part ETags; never {@code null}
     */
    public TreeMap<Integer, String> sortedEtags() {
        return new TreeMap<>(partEtags);
    }
}
