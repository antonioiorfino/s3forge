package it.iorfino.s3forge.http;

import com.sun.net.httpserver.HttpExchange;
import it.iorfino.s3forge.model.S3Error;
import it.iorfino.s3forge.store.ListResult;
import it.iorfino.s3forge.store.Store;
import it.iorfino.s3forge.store.StoredObject;
import it.iorfino.s3forge.xml.XmlEscaper;
import it.iorfino.s3forge.xml.XmlWriter;

import java.io.IOException;
import java.util.List;

public final class BucketHandler {

    private final Store store;

    public BucketHandler(Store store) {
        this.store = store;
    }

    // GET /
    public void listBuckets(HttpExchange ex) throws IOException {
        List<String> buckets = store.listBuckets();
        XmlWriter w = new XmlWriter()
            .header()
            .open("ListAllMyBucketsResult")
            .open("Buckets");
        for (String b : buckets) {
            w.open("Bucket").element("Name", b).close("Bucket");
        }
        w.close("Buckets").close("ListAllMyBucketsResult");
        ResponseWriter.xml(ex, 200, w.toString());
    }

    // PUT /{bucket}
    public void createBucket(HttpExchange ex, String bucket) throws IOException {
        if (!isValidBucketName(bucket)) {
            ResponseWriter.error(ex, S3Error.INVALID_BUCKET_NAME);
            return;
        }
        if (store.bucketExists(bucket)) {
            // AWS reale restituisce 200 se posseduto dallo stesso owner;
            // per il mock accettiamo e ritorniamo 200 idempotente.
            ResponseWriter.empty(ex, 200);
            return;
        }
        store.createBucket(bucket);
        ResponseWriter.empty(ex, 200);
    }

    // DELETE /{bucket}
    public void deleteBucket(HttpExchange ex, String bucket) throws IOException {
        if (!store.bucketExists(bucket)) {
            ResponseWriter.error(ex, S3Error.NO_SUCH_BUCKET);
            return;
        }
        try {
            store.deleteBucket(bucket);
            ResponseWriter.empty(ex, 204);
        } catch (IOException e) {
            if ("BucketNotEmpty".equals(e.getMessage())) {
                ResponseWriter.error(ex, S3Error.BUCKET_NOT_EMPTY);
            } else {
                ResponseWriter.error(ex, S3Error.INTERNAL_ERROR);
            }
        }
    }

    // HEAD /{bucket}
    public void headBucket(HttpExchange ex, String bucket) throws IOException {
        if (!store.bucketExists(bucket)) {
            ResponseWriter.empty(ex, 404);
            return;
        }
        ResponseWriter.empty(ex, 200);
    }

    /**
     * Handles a {@code GET /{bucket}} request, producing a v1
     * ({@code ListObjects}) or v2 ({@code ListObjectsV2}) response depending
     * on the {@code list-type} query parameter.
     *
     * <p>Supported query parameters:</p>
     * <ul>
     *   <li>{@code list-type=2} — selects the v2 response schema</li>
     *   <li>{@code prefix} — filter by key prefix</li>
     *   <li>{@code delimiter} — group keys by common prefixes</li>
     *   <li>{@code max-keys} — maximum entries per page</li>
     *   <li>{@code marker} — v1 pagination cursor</li>
     *   <li>{@code continuation-token} — v2 pagination cursor</li>
     * </ul>
     *
     * @param ex     the HTTP exchange
     * @param bucket the bucket name
     * @param query  the parsed query parameters
     * @throws IOException on I/O failure
     */
    public void listObjects(HttpExchange ex, String bucket, QueryParams query)
        throws IOException {
        if (!store.bucketExists(bucket)) {
            ResponseWriter.error(ex, S3Error.NO_SUCH_BUCKET);
            return;
        }

        boolean v2 = "2".equals(query.get("list-type"));
        String prefix = query.get("prefix");
        String delimiter = query.get("delimiter");
        int maxKeys = query.getInt("max-keys", 1000);

        String marker = v2 ? null : query.get("marker");
        String continuationToken = v2 ? query.get("continuation-token") : null;

        ListResult result = store.listObjects(bucket, prefix, delimiter,
            maxKeys, marker, continuationToken);

        String body = v2
            ? renderV2(bucket, prefix, delimiter, maxKeys, result,
            query.get("continuation-token"))
            : renderV1(bucket, prefix, delimiter, maxKeys, result,
            query.get("marker"));

        ResponseWriter.xml(ex, 200, body);
    }

    /**
     * Renders a {@code ListObjects} (v1) XML response.
     */
    private static String renderV1(String bucket, String prefix, String delimiter,
                                   int maxKeys, ListResult result, String marker) {
        XmlWriter w = new XmlWriter()
            .header()
            .open("ListBucketResult")
            .element("Name", bucket)
            .element("Prefix", prefix == null ? "" : prefix)
            .element("Marker", marker == null ? "" : marker)
            .element("MaxKeys", maxKeys)
            .element("IsTruncated", Boolean.toString(result.truncated()));

        if (result.truncated() && result.nextMarker() != null) {
            w.element("NextMarker", result.nextMarker());
        }
        if (delimiter != null && !delimiter.isEmpty()) {
            w.element("Delimiter", delimiter);
        }

        for (StoredObject o : result.objects()) {
            w.open("Contents")
                .element("Key", o.key())
                .element("LastModified", o.lastModified().toString())
                .element("ETag", "\"" + o.etag() + "\"")
                .element("Size", o.size())
                .element("StorageClass", "STANDARD")
                .close("Contents");
        }
        for (String cp : result.commonPrefixes()) {
            w.open("CommonPrefixes").element("Prefix", cp).close("CommonPrefixes");
        }
        w.close("ListBucketResult");
        return w.toString();
    }

    /**
     * Renders a {@code ListObjectsV2} XML response.
     */
    private static String renderV2(String bucket, String prefix, String delimiter,
                                   int maxKeys, ListResult result,
                                   String continuationToken) {
        XmlWriter w = new XmlWriter()
            .header()
            .open("ListBucketResult")
            .element("Name", bucket)
            .element("Prefix", prefix == null ? "" : prefix)
            .element("KeyCount", result.objects().size() + result.commonPrefixes().size())
            .element("MaxKeys", maxKeys)
            .element("IsTruncated", Boolean.toString(result.truncated()));

        if (result.truncated() && result.nextContinuationToken() != null) {
            w.element("NextContinuationToken", result.nextContinuationToken());
        }
        if (continuationToken != null) {
            w.element("ContinuationToken", continuationToken);
        }
        if (delimiter != null && !delimiter.isEmpty()) {
            w.element("Delimiter", delimiter);
        }

        for (StoredObject o : result.objects()) {
            w.open("Contents")
                .element("Key", o.key())
                .element("LastModified", o.lastModified().toString())
                .element("ETag", "\"" + o.etag() + "\"")
                .element("Size", o.size())
                .element("StorageClass", "STANDARD")
                .close("Contents");
        }
        for (String cp : result.commonPrefixes()) {
            w.open("CommonPrefixes").element("Prefix", cp).close("CommonPrefixes");
        }
        w.close("ListBucketResult");
        return w.toString();
    }

    private static boolean isValidBucketName(String name) {
        if (name == null || name.length() < 3 || name.length() > 63) return false;
        if (!name.matches("[a-z0-9][a-z0-9.-]*[a-z0-9]")) return false;
        return !name.contains("..");
    }

    /**
     * Handles a {@code GET /{bucket}?location} request.
     *
     * <p>Returns a {@code LocationConstraint} XML document. When no region
     * is configured, the constraint is an empty string, which all S3 clients
     * treat as {@code us-east-1}, matching AWS behavior for the default
     * region.</p>
     *
     * @param ex     the HTTP exchange
     * @param bucket the bucket name
     * @param region the configured region, or {@code null} for default
     * @throws IOException on I/O failure
     */
    public void getBucketLocation(HttpExchange ex, String bucket, String region)
        throws IOException {
        if (!store.bucketExists(bucket)) {
            ResponseWriter.error(ex, S3Error.NO_SUCH_BUCKET);
            return;
        }
        String body = new XmlWriter()
            .header()
            .open("LocationConstraint")
            .raw(XmlEscaper.escape(region == null ? "" : region))
            .close("LocationConstraint")
            .toString();
        ResponseWriter.xml(ex, 200, body);
    }
}
