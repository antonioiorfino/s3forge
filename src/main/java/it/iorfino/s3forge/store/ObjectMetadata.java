package it.iorfino.s3forge.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Persisted metadata for a stored object.
 *
 * <p>The filesystem backend writes one metadata file per object, in a dedicated {@code
 * .s3forge-meta/<bucket>/<key>.properties} sidecar. This allows the ETag, checksum, content type,
 * and object metadata headers to survive server restarts, which the raw object file alone cannot
 * convey.
 *
 * <p>The on-disk format is a plain {@link Properties} file, chosen for its built-in escaping and
 * zero-dependency availability in the JDK. The built-in fields use their own keys ({@code etag},
 * {@code contentType}, {@code checksumCrc32}); object metadata headers are serialized with a {@code
 * meta.} key prefix to avoid collisions.
 *
 * @param etag the ETag value (hex MD5, no quotes); never {@code null}, possibly empty
 * @param contentType the MIME type; never {@code null}, defaults to {@code
 *     application/octet-stream}
 * @param checksumCrc32 the Base64 big-endian CRC32 checksum; never {@code null}, possibly empty
 * @param metadata object metadata headers, lowercase keys; never {@code null}, possibly empty
 * @since 0.1.0
 */
public record ObjectMetadata(
        String etag, String contentType, String checksumCrc32, Map<String, String> metadata) {

    /** Properties key for the ETag. */
    private static final String KEY_ETAG = "etag";

    /** Properties key for the content type. */
    private static final String KEY_CONTENT_TYPE = "contentType";

    /** Properties key for the CRC32 checksum. */
    private static final String KEY_CHECKSUM_CRC32 = "checksumCrc32";

    /** Prefix used for object metadata header keys in the sidecar file. */
    private static final String KEY_META_PREFIX = "meta.";

    /**
     * Canonical constructor applying default values and defensive copies.
     *
     * <p>The {@code metadata} map is copied defensively; callers may reuse their own map afterwards
     * without affecting this record.
     */
    public ObjectMetadata {
        if (etag == null) etag = "";
        if (contentType == null) contentType = "application/octet-stream";
        if (checksumCrc32 == null) checksumCrc32 = "";
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * Convenience constructor for objects with no custom metadata.
     *
     * <p>Equivalent to calling the canonical constructor with an empty map.
     *
     * @param etag the ETag value
     * @param contentType the MIME type
     * @param checksumCrc32 the CRC32 checksum
     */
    public ObjectMetadata(String etag, String contentType, String checksumCrc32) {
        this(etag, contentType, checksumCrc32, Map.of());
    }

    /**
     * Reads metadata from a sidecar file.
     *
     * <p>If the file does not exist, an empty metadata record is returned so that callers can
     * transparently handle objects created before sidecar persistence was introduced, or before the
     * metadata field was added.
     *
     * @param file the sidecar file path; must not be {@code null}
     * @return the parsed metadata; never {@code null}
     * @throws IOException if the file exists but cannot be read
     */
    public static ObjectMetadata read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new ObjectMetadata("", null, "", Map.of());
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        Map<String, String> meta = new TreeMap<>();
        for (String name : p.stringPropertyNames()) {
            if (name.startsWith(KEY_META_PREFIX)) {
                meta.put(name.substring(KEY_META_PREFIX.length()), p.getProperty(name));
            }
        }
        return new ObjectMetadata(
                p.getProperty(KEY_ETAG, ""),
                p.getProperty(KEY_CONTENT_TYPE),
                p.getProperty(KEY_CHECKSUM_CRC32, ""),
                meta);
    }

    /**
     * Writes metadata to a sidecar file, creating parent directories as needed and replacing any
     * previous content.
     *
     * <p>The output is sorted by key to produce stable, diffable files across runs, which
     * simplifies debugging and manual inspection.
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
        for (var e : new TreeMap<>(metadata).entrySet()) {
            p.setProperty(KEY_META_PREFIX + e.getKey(), e.getValue());
        }
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
