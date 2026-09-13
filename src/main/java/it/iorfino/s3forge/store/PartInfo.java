package it.iorfino.s3forge.store;

/**
 * Immutable description of a single part within a multipart object.
 *
 * <p>Recorded when a multipart upload is completed, so that subsequent {@code
 * GetObject?partNumber=N} and {@code HeadObject?partNumber=N} requests can resolve the part's byte
 * range without re-reading the whole object.
 *
 * @param partNumber the 1-based part number, as uploaded
 * @param startOffset inclusive byte offset of the part within the object
 * @param size part size in bytes
 * @param etag the part's ETag (hex MD5, no quotes)
 * @since 0.3.0
 */
public record PartInfo(int partNumber, long startOffset, long size, String etag) {

    /**
     * Returns the exclusive end offset of this part within the object.
     *
     * @return {@code startOffset + size}
     */
    public long endOffset() {
        return startOffset + size;
    }

    /**
     * Returns the value for the {@code Content-Range} response header.
     *
     * @param totalSize the total size of the object
     * @return e.g. {@code "bytes 0-4/26"}
     */
    public String contentRange(long totalSize) {
        return "bytes " + startOffset + "-" + (endOffset() - 1) + "/" + totalSize;
    }
}
