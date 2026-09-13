package it.iorfino.s3forge.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import it.iorfino.s3forge.model.S3Error;
import it.iorfino.s3forge.store.Store;
import it.iorfino.s3forge.store.StoredObject;
import it.iorfino.s3forge.util.Etag;
import it.iorfino.s3forge.util.RangeSpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * HTTP handler for S3 object-level operations.
 *
 * <p>Covers the following routes (path-style addressing):
 *
 * <ul>
 *   <li>{@code PUT /{bucket}/{key}} — put object or copy object
 *   <li>{@code GET /{bucket}/{key}} — get object or get attributes
 *   <li>{@code HEAD /{bucket}/{key}} — head object
 *   <li>{@code DELETE /{bucket}/{key}} — delete object
 * </ul>
 *
 * <p>All responses set the standard S3 headers: {@code Content-Type}, {@code Content-Length},
 * {@code ETag} and {@code Last-Modified}. Object metadata headers (standard and {@code
 * x-amz-meta-*}) are propagated from the stored object on read.
 *
 * @since 0.1.0
 */
public final class ObjectHandler {

    private static final DateTimeFormatter RFC_1123 =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    /** The set of standard S3 metadata headers, lowercase. */
    private static final Set<String> STANDARD_METADATA_HEADERS =
            Set.of(
                    "cache-control",
                    "content-disposition",
                    "content-encoding",
                    "content-language",
                    "expires");

    private final Store store;

    /**
     * Creates a new handler bound to the given storage backend.
     *
     * @param store the storage backend; must not be {@code null}
     */
    public ObjectHandler(Store store) {
        this.store = store;
    }

    // ------------------------------------------------------------------
    // Metadata helpers
    // ------------------------------------------------------------------

    /**
     * Extracts the S3 object metadata headers from a request.
     *
     * <p>Recognizes the five standard headers and any header whose name starts with {@code
     * x-amz-meta-}. All keys are normalized to lowercase, matching S3 behavior. Values are returned
     * as sent.
     *
     * <p>Header names that start with {@code x-amz-} but are not user metadata (e.g. {@code
     * x-amz-checksum-crc32}) are intentionally excluded, since they are transport-level fields
     * handled elsewhere.
     *
     * @param req the request headers; must not be {@code null}
     * @return a map of metadata headers, keyed by lowercase name; never {@code null}, possibly
     *     empty
     */
    private static Map<String, String> extractMetadata(Headers req) {
        Map<String, String> out = new LinkedHashMap<>();
        for (var entry : req.entrySet()) {
            String name = entry.getKey().toLowerCase();
            boolean standard = STANDARD_METADATA_HEADERS.contains(name);
            boolean userMeta = name.startsWith("x-amz-meta-");
            if (!standard && !userMeta) continue;
            String value = entry.getValue().isEmpty() ? "" : entry.getValue().get(0);
            out.put(name, value);
        }
        return out;
    }

    /**
     * Writes the object metadata headers into a response.
     *
     * @param res the response headers; must not be {@code null}
     * @param metadata the metadata map, lowercase keys; may be {@code null}
     */
    private static void writeMetadata(Headers res, Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) return;
        for (var e : metadata.entrySet()) {
            res.set(e.getKey(), e.getValue());
        }
    }

    // ------------------------------------------------------------------
    // PUT
    // ------------------------------------------------------------------

    /**
     * Handles a {@code PUT /{bucket}/{key}} request, storing the request body as an object.
     *
     * <p>Reads the full body, computes the MD5-based ETag and the CRC32 checksum, extracts any
     * object metadata headers, and delegates to {@link Store#putObject}.
     *
     * <p>When the request uses {@code Content-Encoding: aws-chunked} (the default for AWS SDK v2
     * from version 2.30.0 onwards), the body is decoded on the fly, trailer headers are extracted,
     * and the resulting checksum is stored alongside the object.
     *
     * @param ex the HTTP exchange
     * @param bucket the target bucket
     * @param key the object key
     * @throws IOException on I/O failure
     */
    public void putObject(HttpExchange ex, String bucket, String key) throws IOException {
        if (!store.bucketExists(bucket)) {
            ResponseWriter.error(ex, S3Error.NO_SUCH_BUCKET);
            return;
        }

        var reqHeaders = ex.getRequestHeaders();

        // 1. Read raw body.
        byte[] rawBody;
        try (var in = ex.getRequestBody()) {
            rawBody = in.readAllBytes();
        }

        // 2. Decode aws-chunked if present.
        byte[] body;
        String checksumFromTrailer = null;
        String contentEncoding = reqHeaders.getFirst("Content-Encoding");
        if (contentEncoding != null && contentEncoding.toLowerCase().contains("aws-chunked")) {
            try (var in = new ByteArrayInputStream(rawBody)) {
                var decoded = it.iorfino.s3forge.util.AwsChunkedDecoder.decode(in);
                body = decoded.payload();
                checksumFromTrailer = decoded.trailers().get("x-amz-checksum-crc32");
            }
        } else {
            body = rawBody;
        }

        // 3. ETag.
        String etag = Etag.of(body);

        // 4. Validate Content-MD5 if present.
        String contentMd5 = reqHeaders.getFirst("Content-MD5");
        if (contentMd5 != null && !contentMd5.isEmpty()) {
            String computedMd5Base64 =
                    java.util.Base64.getEncoder().encodeToString(hexToBytes(etag));
            if (!computedMd5Base64.equals(contentMd5)) {
                ResponseWriter.error(ex, S3Error.BAD_DIGEST);
                return;
            }
        }

        // 5. Determine CRC32.
        String crc32Base64 = checksumFromTrailer;
        if (crc32Base64 == null) {
            crc32Base64 = reqHeaders.getFirst("x-amz-checksum-crc32");
        }
        if (crc32Base64 == null) {
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(body);
            crc32Base64 =
                    java.util.Base64.getEncoder()
                            .encodeToString(longToBigEndianBytes(crc.getValue()));
        }

        // 6. Extract metadata headers.
        Map<String, String> metadata = extractMetadata(reqHeaders);

        // 7. Persist.
        String contentType = reqHeaders.getFirst("Content-Type");
        if (contentType == null) contentType = "application/octet-stream";

        try (var data = new ByteArrayInputStream(body)) {
            store.putObject(
                    bucket, key, data, body.length, contentType, etag, crc32Base64, metadata);
        }

        // 8. Response headers.
        var resHeaders = ex.getResponseHeaders();
        resHeaders.set("ETag", Etag.quoted(etag));
        resHeaders.set("x-amz-checksum-crc32", crc32Base64);

        ex.sendResponseHeaders(200, -1);
        ex.close();
    }

    // ------------------------------------------------------------------
    // GET
    // ------------------------------------------------------------------

    /**
     * Handles a {@code GET /{bucket}/{key}} request, streaming the object payload back to the
     * client.
     *
     * <p>If the request carries a satisfiable {@code Range} header, the response is {@code 206
     * Partial Content} with only the requested bytes and a {@code Content-Range} header.
     *
     * <p>Sets {@code Content-Type}, {@code Content-Length}, {@code ETag}, {@code Last-Modified},
     * {@code Accept-Ranges}, any stored metadata headers, and (when available) {@code
     * x-amz-checksum-crc32}. Responds with {@code 404 NoSuchKey} if the key does not exist.
     *
     * @param ex the HTTP exchange
     * @param bucket the bucket name
     * @param key the object key
     * @throws IOException on I/O failure
     */
    public void getObject(HttpExchange ex, String bucket, String key) throws IOException {
        Optional<StoredObject> maybe = store.getObject(bucket, key);
        if (maybe.isEmpty()) {
            ResponseWriter.error(ex, S3Error.NO_SUCH_KEY);
            return;
        }
        StoredObject obj = maybe.get();

        var headers = ex.getResponseHeaders();
        headers.set("Content-Type", obj.contentType());
        headers.set("ETag", Etag.quoted(obj.etag()));
        headers.set("Last-Modified", RFC_1123.format(obj.lastModified()));
        headers.set("Accept-Ranges", "bytes");
        if (!obj.checksumCrc32().isEmpty()) {
            headers.set("x-amz-checksum-crc32", obj.checksumCrc32());
        }
        writeMetadata(headers, obj.metadata());

        var rangeOpt = RangeSpec.parse(ex.getRequestHeaders().getFirst("Range"));

        if (rangeOpt.isEmpty()) {
            ex.sendResponseHeaders(200, obj.size());
            try (OutputStream os = ex.getResponseBody();
                    var in = obj.data()) {
                in.transferTo(os);
            }
            return;
        }

        var resolved = rangeOpt.get().resolve(obj.size());
        if (resolved.isEmpty()) {
            headers.set("Content-Range", "bytes */" + obj.size());
            try (var in = obj.data()) {
                /* drain */
            }
            ResponseWriter.error(ex, S3Error.INVALID_RANGE);
            return;
        }

        var r = resolved.get();
        headers.set("Content-Range", r.contentRange());
        headers.set("Content-Length", Long.toString(r.length()));

        ex.sendResponseHeaders(206, r.length());
        try (OutputStream os = ex.getResponseBody();
                var in = obj.data()) {
            in.skipNBytes(r.start());
            byte[] buf = new byte[8192];
            long remaining = r.length();
            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                int n = in.read(buf, 0, toRead);
                if (n < 0) break;
                os.write(buf, 0, n);
                remaining -= n;
            }
        }
    }

    // ------------------------------------------------------------------
    // HEAD
    // ------------------------------------------------------------------

    /**
     * Handles a {@code HEAD /{bucket}/{key}} request, returning object metadata without the
     * payload.
     *
     * <p>Sets the same headers as {@link #getObject} but sends no body. Responds with {@code 404}
     * if the key does not exist.
     *
     * @param ex the HTTP exchange
     * @param bucket the bucket name
     * @param key the object key
     * @throws IOException on I/O failure
     */
    public void headObject(HttpExchange ex, String bucket, String key) throws IOException {
        Optional<StoredObject> maybe = store.getObject(bucket, key);
        if (maybe.isEmpty()) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        StoredObject obj = maybe.get();
        try (var in = obj.data()) {
            /* no body for HEAD */
        }

        var headers = ex.getResponseHeaders();
        headers.set("Content-Type", obj.contentType());
        headers.set("ETag", Etag.quoted(obj.etag()));
        headers.set("Last-Modified", RFC_1123.format(obj.lastModified()));
        headers.set("Accept-Ranges", "bytes");
        if (!obj.checksumCrc32().isEmpty()) {
            headers.set("x-amz-checksum-crc32", obj.checksumCrc32());
        }
        writeMetadata(headers, obj.metadata());

        var rangeOpt = RangeSpec.parse(ex.getRequestHeaders().getFirst("Range"));
        if (rangeOpt.isPresent()) {
            var resolved = rangeOpt.get().resolve(obj.size());
            if (resolved.isEmpty()) {
                headers.set("Content-Range", "bytes */" + obj.size());
                ex.sendResponseHeaders(416, -1);
                ex.close();
                return;
            }
            var r = resolved.get();
            headers.set("Content-Range", r.contentRange());
            headers.set("Content-Length", Long.toString(r.length()));
            ex.sendResponseHeaders(206, -1);
            ex.close();
            return;
        }

        headers.set("Content-Length", Long.toString(obj.size()));
        ex.sendResponseHeaders(200, -1);
        ex.close();
    }

    // ------------------------------------------------------------------
    // DELETE
    // ------------------------------------------------------------------

    /**
     * Handles a {@code DELETE /{bucket}/{key}} request.
     *
     * <p>Deleting a non-existent key returns {@code 204 No Content}, matching real S3 semantics.
     * Responds with {@code 404 NoSuchBucket} if the bucket does not exist.
     *
     * @param ex the HTTP exchange
     * @param bucket the bucket name
     * @param key the object key
     * @throws IOException on I/O failure
     */
    public void deleteObject(HttpExchange ex, String bucket, String key) throws IOException {
        if (!store.bucketExists(bucket)) {
            ResponseWriter.error(ex, S3Error.NO_SUCH_BUCKET);
            return;
        }
        store.deleteObject(bucket, key);
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    // ------------------------------------------------------------------
    // COPY
    // ------------------------------------------------------------------

    /**
     * Handles a {@code PUT /{bucket}/{key}} request carrying an {@code x-amz-copy-source} header,
     * implementing the S3 CopyObject operation.
     *
     * <p>By default the source object's content type and metadata are copied verbatim ({@code
     * x-amz-metadata-directive: COPY}). If the client passes {@code REPLACE}, the destination's
     * content type and metadata are taken from the request headers instead.
     *
     * @param ex the HTTP exchange
     * @param bucket the destination bucket
     * @param key the destination key
     * @throws IOException on I/O failure
     */
    public void copyObject(HttpExchange ex, String bucket, String key) throws IOException {
        if (!store.bucketExists(bucket)) {
            ResponseWriter.error(ex, S3Error.NO_SUCH_BUCKET);
            return;
        }

        var reqHeaders = ex.getRequestHeaders();
        String rawSource = reqHeaders.getFirst("x-amz-copy-source");

        CopySource source = CopySource.parse(rawSource);
        if (source == null) {
            ResponseWriter.error(ex, S3Error.INVALID_ARGUMENT);
            return;
        }

        String directive = reqHeaders.getFirst("x-amz-metadata-directive");
        boolean replace = "REPLACE".equalsIgnoreCase(directive);

        if (source.bucket().equals(bucket) && source.key().equals(key) && !replace) {
            ResponseWriter.error(ex, S3Error.INVALID_REQUEST_COPY_SELF);
            return;
        }

        Optional<StoredObject> maybe = store.getObject(source.bucket(), source.key());
        if (maybe.isEmpty()) {
            ResponseWriter.error(ex, S3Error.NO_SUCH_KEY);
            return;
        }
        StoredObject src = maybe.get();

        String contentType =
                replace
                        ? defaultIfNull(
                                reqHeaders.getFirst("Content-Type"), "application/octet-stream")
                        : src.contentType();

        Map<String, String> destMetadata = replace ? extractMetadata(reqHeaders) : src.metadata();

        String etag = src.etag();
        String checksum = src.checksumCrc32();

        try (var data = src.data()) {
            store.putObject(
                    bucket, key, data, src.size(), contentType, etag, checksum, destMetadata);
        }

        Instant now = Instant.now();
        String body =
                new it.iorfino.s3forge.xml.XmlWriter()
                        .header()
                        .open("CopyObjectResult")
                        .element("ETag", Etag.quoted(etag))
                        .element("LastModified", now.toString())
                        .close("CopyObjectResult")
                        .toString();

        ResponseWriter.xml(ex, 200, body);
    }

    // ------------------------------------------------------------------
    // GetObjectAttributes
    // ------------------------------------------------------------------

    /**
     * Handles a {@code GET /{bucket}/{key}?attributes} request, returning object metadata without
     * transferring the payload.
     *
     * @param ex the HTTP exchange
     * @param bucket the bucket name
     * @param key the object key
     * @throws IOException on I/O failure
     */
    public void getObjectAttributes(HttpExchange ex, String bucket, String key) throws IOException {
        Optional<StoredObject> maybe = store.getObject(bucket, key);
        if (maybe.isEmpty()) {
            ResponseWriter.error(ex, S3Error.NO_SUCH_KEY);
            return;
        }
        StoredObject obj = maybe.get();
        try (var in = obj.data()) {
            /* metadata only */
        }

        String requested = ex.getRequestHeaders().getFirst("x-amz-object-attributes");
        var wanted =
                requested == null
                        ? Set.<String>of()
                        : java.util.Arrays.stream(requested.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .collect(java.util.stream.Collectors.toSet());

        var w = new it.iorfino.s3forge.xml.XmlWriter().header().open("GetObjectAttributesOutput");

        if (wanted.isEmpty() || wanted.contains("ETag")) {
            w.element("ETag", Etag.quoted(obj.etag()));
        }
        if (wanted.isEmpty() || wanted.contains("ObjectSize")) {
            w.element("ObjectSize", obj.size());
        }
        if (wanted.isEmpty() || wanted.contains("StorageClass")) {
            w.element("StorageClass", "STANDARD");
        }
        if ((wanted.isEmpty() || wanted.contains("Checksum")) && !obj.checksumCrc32().isEmpty()) {
            w.open("Checksum").element("ChecksumCRC32", obj.checksumCrc32()).close("Checksum");
        }
        w.close("GetObjectAttributesOutput");
        ResponseWriter.xml(ex, 200, w.toString());
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Returns {@code value} if non-null and non-empty, otherwise the given fallback.
     *
     * @param value the value to test
     * @param fallback the fallback; must not be {@code null}
     * @return the resolved value; never {@code null}
     */
    private static String defaultIfNull(String value, String fallback) {
        return (value == null || value.isEmpty()) ? fallback : value;
    }

    /**
     * Encodes a 64-bit CRC value as a 4-byte big-endian array, as required by the S3 {@code
     * x-amz-checksum-crc32} header.
     *
     * @param value the CRC32 value returned by {@link java.util.zip.CRC32}
     * @return a 4-byte big-endian representation
     */
    private static byte[] longToBigEndianBytes(long value) {
        return new byte[] {
            (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
        };
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

    /**
     * Parsed representation of an {@code x-amz-copy-source} header value.
     *
     * @param bucket the source bucket
     * @param key the source key
     * @since 0.1.0
     */
    private record CopySource(String bucket, String key) {

        /**
         * Parses a raw {@code x-amz-copy-source} header value.
         *
         * @param raw the raw header value; may be {@code null}
         * @return a parsed {@link CopySource}, or {@code null} if the value is missing or malformed
         */
        static CopySource parse(String raw) {
            if (raw == null || raw.isEmpty()) return null;

            String s = raw.startsWith("/") ? raw.substring(1) : raw;

            int q = s.indexOf('?');
            if (q >= 0) s = s.substring(0, q);

            int slash = s.indexOf('/');
            if (slash <= 0 || slash == s.length() - 1) return null;

            String b = s.substring(0, slash);
            String k = s.substring(slash + 1);

            try {
                b = java.net.URLDecoder.decode(b, java.nio.charset.StandardCharsets.UTF_8);
                k = java.net.URLDecoder.decode(k, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
            return new CopySource(b, k);
        }
    }
}
