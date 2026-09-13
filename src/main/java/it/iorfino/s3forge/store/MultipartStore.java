package it.iorfino.s3forge.store;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Extension of {@link Store} adding support for the S3 multipart upload operations.
 *
 * <p>Backends that do not support multipart may simply not implement this interface; the HTTP layer
 * will then respond with a {@code NotImplemented} error to multipart requests.
 *
 * <p>Lifecycle of an upload:
 *
 * <ol>
 *   <li>{@link #initiateMultipart(String, String, String)} returns a fresh {@link MultipartUpload}
 *       with a server-generated id.
 *   <li>{@link #uploadPart(String, String, int, byte[], String)} registers each part.
 *   <li>Either {@link #completeMultipart(String, String, List)} finalizes the object and removes
 *       the upload, or {@link #abortMultipart(String, String)} discards it.
 * </ol>
 *
 * @since 0.1.0
 */
public interface MultipartStore extends Store {

    /**
     * Initiates a new multipart upload.
     *
     * @param bucket the destination bucket; must exist
     * @param key the destination key; must not be {@code null}
     * @param contentType the MIME type to assign at completion, or {@code null} for a default
     * @return the newly created upload state; never {@code null}
     * @throws IOException with message {@code "NoSuchBucket"} if the bucket does not exist
     */
    MultipartUpload initiateMultipart(String bucket, String key, String contentType)
            throws IOException;

    /**
     * Returns an in-progress upload by id.
     *
     * @param bucket the bucket name
     * @param uploadId the upload identifier
     * @return an {@link Optional} containing the upload, or {@link Optional#empty()} if not found
     */
    Optional<MultipartUpload> getMultipart(String bucket, String uploadId);

    /**
     * Stores a part of an in-progress upload.
     *
     * @param bucket the bucket name
     * @param uploadId the upload identifier
     * @param partNumber the 1-based part number
     * @param data the part payload
     * @param etag the part ETag (hex MD5, no quotes)
     * @throws IOException with message {@code "NoSuchUpload"} if the upload is unknown
     */
    void uploadPart(String bucket, String uploadId, int partNumber, byte[] data, String etag)
            throws IOException;

    /**
     * Completes a multipart upload, concatenating the listed parts in order and storing the
     * resulting object under the upload's bucket and key.
     *
     * <p>The final ETag is computed using the S3 multipart convention: {@code
     * "<md5-of-concatenated-part-md5s>-<partCount>"}, with the digest rendered as lowercase hex and
     * surrounded by double quotes.
     *
     * @param bucket the bucket name
     * @param uploadId the upload identifier
     * @param partNumbers the ordered list of part numbers to include
     * @return the completed object metadata
     * @throws IOException with message {@code "NoSuchUpload"} if the upload is unknown, or {@code
     *     "InvalidPart"} if a listed part is missing
     */
    StoredObject completeMultipart(String bucket, String uploadId, List<Integer> partNumbers)
            throws IOException;

    /**
     * Aborts an in-progress upload, discarding all its parts.
     *
     * @param bucket the bucket name
     * @param uploadId the upload identifier
     * @throws IOException with message {@code "NoSuchUpload"} if the upload is unknown
     */
    void abortMultipart(String bucket, String uploadId) throws IOException;

    /**
     * Lists all in-progress uploads for a bucket.
     *
     * @param bucket the bucket name
     * @return the list of active uploads; never {@code null}, possibly empty
     */
    List<MultipartUpload> listMultipartUploads(String bucket);
}
