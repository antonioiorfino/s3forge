package it.iorfino.s3forge.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Persisted metadata for a stored object.
 *
 * <p>The filesystem backend writes one metadata file per object, in a dedicated {@code
 * .s3forge-meta/<bucket>/<key>.properties} sidecar. This allows ETag, checksum, and content type to
 * survive server restarts, which the raw object file alone cannot convey.
 *
 * <p>The on-disk format is a plain {@link Properties} file, chosen for its built-in escaping and
 * zero-dependency availability in the JDK. The keys used are documented in the individual accessor
 * methods.
 *
 * @param etag the ETag value (hex MD5, no quotes); never {@code null}, possibly empty
 * @param contentType the MIME type; never {@code null}, defaults to {@code
 *     application/octet-stream}
 * @param checksumCrc32 the Base64 big-endian CRC32 checksum; never {@code null}, possibly empty
 * @since 0.1.0
 */
public record ObjectMetadata(String etag, String contentType, String checksumCrc32) {

    /** Properties key for the ETag. */
    private static final String KEY_ETAG = "etag";

    /** Properties key for the content type. */
    private static final String KEY_CONTENT_TYPE = "contentType";

    /** Properties key for the CRC32 checksum. */
    private static final String KEY_CHECKSUM_CRC32 = "checksumCrc32";

    /** Canonical constructor, applying defaults for null fields. */
    public ObjectMetadata {
        if (etag == null) etag = "";
        if (contentType == null) contentType = "application/octet-stream";
        if (checksumCrc32 == null) checksumCrc32 = "";
    }

    /**
     * Reads metadata from a sidecar file.
     *
     * <p>If the file does not exist, an empty metadata record is returned so that callers can
     * transparently handle objects created before sidecar persistence was introduced.
     *
     * @param file the sidecar file path; must not be {@code null}
     * @return the parsed metadata; never {@code null}
     * @throws IOException if the file exists but cannot be read
     */
    public static ObjectMetadata read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new ObjectMetadata("", null, "");
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        return new ObjectMetadata(
                p.getProperty(KEY_ETAG, ""),
                p.getProperty(KEY_CONTENT_TYPE),
                p.getProperty(KEY_CHECKSUM_CRC32, ""));
    }

    /**
     * Writes metadata to a sidecar file, creating parent directories as needed and replacing any
     * previous content.
     *
     * @param file the sidecar file path; must not be {@code null}
     * @throws IOException if the file cannot be written
     */
    public void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Properties p = new Properties();
        p.setProperty(KEY_ETAG, etag);
        p.setProperty(KEY_CONTENT_TYPE, contentType);
        p.setProperty(KEY_CHECKSUM_CRC32, checksumCrc32);
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "S3Forge object metadata");
        }
    }

    /**
     * Deletes the sidecar file if it exists.
     *
     * @param file the sidecar file path; must not be {@code null}
     * @throws IOException if deletion fails for a reason other than the file not existing
     */
    public static void delete(Path file) throws IOException {
        Files.deleteIfExists(file);
    }
}
