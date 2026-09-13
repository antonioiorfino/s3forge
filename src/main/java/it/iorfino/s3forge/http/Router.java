package it.iorfino.s3forge.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import it.iorfino.s3forge.config.S3ForgeConfig;
import it.iorfino.s3forge.model.S3Error;
import it.iorfino.s3forge.store.Store;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Central HTTP dispatcher for S3Forge.
 *
 * <p>Parses the request path (path-style addressing) or the {@code Host} header (virtual-host style
 * addressing, when enabled), then dispatches to the appropriate handler based on the HTTP method
 * and query parameters.
 *
 * <p>Path grammar in path-style mode:
 *
 * <ul>
 *   <li>{@code /} → service-level operations
 *   <li>{@code /{bucket}} → bucket-level operations
 *   <li>{@code /{bucket}/{key...}} → object-level operations
 * </ul>
 *
 * <p>In virtual-host mode, {@code /{key...}} is interpreted as an object path inside the bucket
 * derived from the {@code Host} header.
 *
 * @since 0.1.0
 */
public final class Router implements HttpHandler {

    private final S3ForgeConfig config;
    private final BucketHandler bucketHandler;
    private final ObjectHandler objectHandler;
    private final DeleteObjectsHandler deleteObjectsHandler;
    private final MultipartHandler multipartHandler; // nullable
    private final VirtualHostResolver virtualHostResolver; // nullable

    /**
     * Creates a new router.
     *
     * @param store the storage backend; must not be {@code null}
     * @param config the server configuration, used to enable virtual-host addressing and to provide
     *     the region for {@code GetBucketLocation}; must not be {@code null}
     */
    public Router(Store store, S3ForgeConfig config) {
        this.config = config;
        this.bucketHandler = new BucketHandler(store);
        this.objectHandler = new ObjectHandler(store);
        this.deleteObjectsHandler = new DeleteObjectsHandler(store);
        this.multipartHandler =
                store instanceof it.iorfino.s3forge.store.MultipartStore
                        ? new MultipartHandler(store)
                        : null;
        this.virtualHostResolver =
                config.virtualHostDomainOpt().map(VirtualHostResolver::new).orElse(null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Any exception thrown while handling a request is mapped to a {@code 500 InternalError}
     * response, unless the response has already been committed.
     */
    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            QueryParams query = QueryParams.parse(ex.getRequestURI().getRawQuery());

            // ---- Virtual-host resolution --------------------------------
            String host = ex.getRequestHeaders().getFirst("Host");
            Optional<String> vhostBucket =
                    virtualHostResolver == null
                            ? Optional.empty()
                            : virtualHostResolver.bucketFromHost(host);

            String bucket;
            String key = null;

            if (vhostBucket.isPresent()) {
                // Virtual-host: everything in the path is the key.
                bucket = vhostBucket.get();
                List<String> segments = parsePath(path);
                if (!segments.isEmpty()) {
                    key = String.join("/", segments);
                }
            } else {
                // Path-style: first segment is the bucket.
                List<String> segments = parsePath(path);
                if (segments.isEmpty()) {
                    if ("GET".equals(method)) {
                        bucketHandler.listBuckets(ex);
                    } else {
                        ResponseWriter.error(ex, S3Error.INVALID_REQUEST);
                    }
                    return;
                }
                bucket = segments.get(0);
                if (segments.size() >= 2) {
                    key = extractKey(path, bucket);
                }
            }

            // ---- Bucket-only request (no key) ---------------------------
            if (key == null) {
                switch (method) {
                    case "PUT" -> bucketHandler.createBucket(ex, bucket);
                    case "GET" -> {
                        if (query.contains("uploads") && multipartHandler != null) {
                            multipartHandler.listUploads(ex, bucket);
                        } else if (query.contains("location")) {
                            bucketHandler.getBucketLocation(ex, bucket, config.region());
                        } else {
                            bucketHandler.listObjects(ex, bucket, query);
                        }
                    }
                    case "DELETE" -> bucketHandler.deleteBucket(ex, bucket);
                    case "HEAD" -> bucketHandler.headBucket(ex, bucket);
                    case "POST" -> {
                        if (query.contains("delete")) {
                            deleteObjectsHandler.handle(ex, bucket);
                        } else {
                            ResponseWriter.error(ex, S3Error.INVALID_REQUEST);
                        }
                    }
                    default -> ResponseWriter.error(ex, S3Error.INVALID_REQUEST);
                }
                return;
            }

            // ---- Object-level request -----------------------------------
            // GetObjectAttributes: GET with ?attributes (must precede
            // multipart and getObject routing).
            if ("GET".equals(method) && query.contains("attributes")) {
                objectHandler.getObjectAttributes(ex, bucket, key);
                return;
            }

            // Multipart routing.
            if (multipartHandler != null) {
                boolean hasUploads = query.contains("uploads");
                boolean hasUploadId = query.contains("uploadId");
                boolean hasPartNumber = query.contains("partNumber");

                if ("POST".equals(method) && hasUploads) {
                    multipartHandler.initiate(ex, bucket, key);
                    return;
                }
                if ("PUT".equals(method) && hasUploadId && hasPartNumber) {
                    int partNumber = query.getInt("partNumber", -1);
                    multipartHandler.uploadPart(ex, bucket, key, partNumber, query.get("uploadId"));
                    return;
                }
                if ("POST".equals(method) && hasUploadId) {
                    multipartHandler.complete(ex, bucket, key, query.get("uploadId"));
                    return;
                }
                if ("DELETE".equals(method) && hasUploadId) {
                    multipartHandler.abort(ex, bucket, key, query.get("uploadId"));
                    return;
                }
                if ("GET".equals(method) && hasUploadId) {
                    multipartHandler.listParts(ex, bucket, key, query.get("uploadId"));
                    return;
                }
            }

            // Regular object routing.
            switch (method) {
                case "PUT" -> {
                    if (ex.getRequestHeaders().getFirst("x-amz-copy-source") != null) {
                        objectHandler.copyObject(ex, bucket, key);
                    } else {
                        objectHandler.putObject(ex, bucket, key);
                    }
                }
                case "GET" -> objectHandler.getObject(ex, bucket, key, query);
                case "HEAD" -> objectHandler.headObject(ex, bucket, key, query);
                case "DELETE" -> objectHandler.deleteObject(ex, bucket, key);
                default -> ResponseWriter.error(ex, S3Error.INVALID_REQUEST);
            }
        } catch (Exception e) {
            try {
                ResponseWriter.error(ex, S3Error.INTERNAL_ERROR);
            } catch (IOException ignored) {
                // client disconnected
            }
        }
    }

    /**
     * Splits a path into its decoded segments, ignoring empty segments.
     *
     * @param path the raw request path
     * @return an immutable list of decoded segments
     */
    private static List<String> parsePath(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) return List.of();
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        return Arrays.stream(stripped.split("/", -1))
                .filter(s -> !s.isEmpty())
                .map(Router::decode)
                .toList();
    }

    /**
     * Extracts the object key from the request path, preserving slashes.
     *
     * @param path the raw request path
     * @param bucket the already-parsed bucket name (first segment)
     * @return the decoded key, without leading slash
     */
    private static String extractKey(String path, String bucket) {
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        int firstSlash = stripped.indexOf('/');
        if (firstSlash < 0) return "";
        String rawKey = stripped.substring(firstSlash + 1);
        return decode(rawKey);
    }

    /**
     * URL-decodes a path segment using UTF-8.
     *
     * @param s the raw segment
     * @return the decoded string
     */
    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
