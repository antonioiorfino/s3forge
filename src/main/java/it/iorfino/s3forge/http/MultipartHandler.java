package it.iorfino.s3forge.http;

import com.sun.net.httpserver.HttpExchange;
import it.iorfino.s3forge.model.S3Error;
import it.iorfino.s3forge.store.MultipartStore;
import it.iorfino.s3forge.store.MultipartUpload;
import it.iorfino.s3forge.store.Store;
import it.iorfino.s3forge.util.Etag;
import it.iorfino.s3forge.xml.XmlReader;
import it.iorfino.s3forge.xml.XmlWriter;
import org.w3c.dom.Element;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * HTTP handler for the S3 multipart upload operations.
 *
 * <p>Routes handled (all on {@code /{bucket}/{key}}):</p>
 * <ul>
 *   <li>{@code POST ?uploads} — initiate multipart upload</li>
 *   <li>{@code PUT  ?partNumber=N&uploadId=X} — upload a part</li>
 *   <li>{@code POST ?uploadId=X} — complete multipart upload</li>
 *   <li>{@code DELETE ?uploadId=X} — abort multipart upload</li>
 *   <li>{@code GET  ?uploadId=X} — list parts</li>
 * </ul>
 *
 * @since 0.1.0
 */
public final class MultipartHandler {

    private final MultipartStore store;

    /**
     * Creates a new handler.
     *
     * @param store the storage backend; must implement {@link MultipartStore}
     * @throws IllegalArgumentException if the store does not support multipart
     */
    public MultipartHandler(Store store) {
        if (!(store instanceof MultipartStore ms)) {
            throw new IllegalArgumentException(
                "Store does not support multipart upload: "
                    + store.getClass().getName());
        }
        this.store = ms;
    }

    /**
     * {@code POST /{bucket}/{key}?uploads}
     *
     * <p>Initiates a multipart upload and returns the upload id in an
     * {@code InitiateMultipartUploadResult} XML document.</p>
     *
     * @param ex     the HTTP exchange
     * @param bucket the bucket name
     * @param key    the destination key
     * @throws IOException on I/O failure
     */
    public void initiate(HttpExchange ex, String bucket, String key)
        throws IOException {
        if (!store.bucketExists(bucket)) {
            ResponseWriter.error(ex, S3Error.NO_SUCH_BUCKET);
            return;
        }
        String contentType = ex.getRequestHeaders().getFirst("Content-Type");

        MultipartUpload up;
        try {
            up = store.initiateMultipart(bucket, key, contentType);
        } catch (IOException e) {
            ResponseWriter.error(ex, S3Error.INTERNAL_ERROR);
            return;
        }

        String body = new XmlWriter()
            .header()
            .open("InitiateMultipartUploadResult")
            .element("Bucket", bucket)
            .element("Key", key)
            .element("UploadId", up.uploadId())
            .close("InitiateMultipartUploadResult")
            .toString();
        ResponseWriter.xml(ex, 200, body);
    }

    /**
     * {@code PUT /{bucket}/{key}?partNumber=N&uploadId=X}
     *
     * <p>Stores one part and returns its ETag. If the request uses
     * {@code aws-chunked}, the body is decoded before being hashed.</p>
     *
     * @param ex         the HTTP exchange
     * @param bucket     the bucket name
     * @param key        the destination key
     * @param partNumber the 1-based part number
     * @param uploadId   the upload identifier
     * @throws IOException on I/O failure
     */
    public void uploadPart(HttpExchange ex, String bucket, String key,
                           int partNumber, String uploadId) throws IOException {
        Optional<MultipartUpload> up = store.getMultipart(bucket, uploadId);
        if (up.isEmpty() || !up.get().key().equals(key)) {
            ResponseWriter.error(ex, S3Error.NO_SUCH_UPLOAD);
            return;
        }

        byte[] raw;
        try (var in = ex.getRequestBody()) {
            raw = in.readAllBytes();
        }

        byte[] data = decodeIfChunked(ex, raw);

        String etag = Etag.of(data);
        try {
            store.uploadPart(bucket, uploadId, partNumber, data, etag);
        } catch (IOException e) {
            ResponseWriter.error(ex, S3Error.NO_SUCH_UPLOAD);
            return;
        }

        ex.getResponseHeaders().set("ETag", Etag.quoted(etag));
        ex.sendResponseHeaders(200, -1);
        ex.close();
    }

    /**
     * {@code POST /{bucket}/{key}?uploadId=X}
     *
     * <p>Completes a multipart upload. The request body lists the parts to
     * include, in ascending order of part number:</p>
     *
     * <pre>{@code
     * <CompleteMultipartUpload>
     *   <Part><PartNumber>1</PartNumber><ETag>"..."</ETag></Part>
     *   ...
     * </CompleteMultipartUpload>
     * }</pre>
     *
     * @param ex       the HTTP exchange
     * @param bucket   the bucket name
     * @param key      the destination key
     * @param uploadId the upload identifier
     * @throws IOException on I/O failure
     */
    public void complete(HttpExchange ex, String bucket, String key,
                         String uploadId) throws IOException {
        Optional<MultipartUpload> up = store.getMultipart(bucket, uploadId);
        if (up.isEmpty() || !up.get().key().equals(key)) {
            ResponseWriter.error(ex, S3Error.NO_SUCH_UPLOAD);
            return;
        }

        byte[] raw;
        try (var in = ex.getRequestBody()) {
            raw = in.readAllBytes();
        }

        List<Integer> partNumbers;
        try {
            XmlReader xml = XmlReader.parse(raw);
            Element root = xml.root();
            if (!"CompleteMultipartUpload".equals(root.getNodeName())) {
                ResponseWriter.error(ex, S3Error.MALFORMED_XML);
                return;
            }
            partNumbers = new ArrayList<>();
            for (Element part : XmlReader.children(root, "Part")) {
                String pn = XmlReader.text(part, "PartNumber");
                if (pn == null) {
                    ResponseWriter.error(ex, S3Error.MALFORMED_XML);
                    return;
                }
                partNumbers.add(Integer.parseInt(pn));
            }
        } catch (IOException | NumberFormatException e) {
            ResponseWriter.error(ex, S3Error.MALFORMED_XML);
            return;
        }

        try {
            var stored = store.completeMultipart(bucket, uploadId, partNumbers);
            String body = new XmlWriter()
                .header()
                .open("CompleteMultipartUploadResult")
                .element("Location", "http://localhost/" + bucket + "/" + key)
                .element("Bucket", bucket)
                .element("Key", key)
                .element("ETag", Etag.quoted(stored.etag()))
                .close("CompleteMultipartUploadResult")
                .toString();
            ResponseWriter.xml(ex, 200, body);
        } catch (IOException e) {
            switch (e.getMessage()) {
                case "InvalidPart"      -> ResponseWriter.error(ex, S3Error.INVALID_PART);
                case "InvalidPartOrder" -> ResponseWriter.error(ex, S3Error.INVALID_PART_ORDER);
                case "NoSuchUpload"     -> ResponseWriter.error(ex, S3Error.NO_SUCH_UPLOAD);
                default                 -> ResponseWriter.error(ex, S3Error.INTERNAL_ERROR);
            }
        }
    }

    /**
     * {@code DELETE /{bucket}/{key}?uploadId=X}
     *
     * <p>Aborts a multipart upload and discards all its parts.</p>
     *
     * @param ex       the HTTP exchange
     * @param bucket   the bucket name
     * @param key      the destination key
     * @param uploadId the upload identifier
     * @throws IOException on I/O failure
     */
    public void abort(HttpExchange ex, String bucket, String key,
                      String uploadId) throws IOException {
        Optional<MultipartUpload> up = store.getMultipart(bucket, uploadId);
        if (up.isEmpty() || !up.get().key().equals(key)) {
            ResponseWriter.error(ex, S3Error.NO_SUCH_UPLOAD);
            return;
        }
        try {
            store.abortMultipart(bucket, uploadId);
        } catch (IOException e) {
            ResponseWriter.error(ex, S3Error.NO_SUCH_UPLOAD);
            return;
        }
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    /**
     * {@code GET /{bucket}/{key}?uploadId=X}
     *
     * <p>Lists the parts uploaded so far for the given upload.</p>
     *
     * @param ex       the HTTP exchange
     * @param bucket   the bucket name
     * @param key      the destination key
     * @param uploadId the upload identifier
     * @throws IOException on I/O failure
     */
    public void listParts(HttpExchange ex, String bucket, String key,
                          String uploadId) throws IOException {
        Optional<MultipartUpload> maybe = store.getMultipart(bucket, uploadId);
        if (maybe.isEmpty() || !maybe.get().key().equals(key)) {
            ResponseWriter.error(ex, S3Error.NO_SUCH_UPLOAD);
            return;
        }
        MultipartUpload up = maybe.get();

        XmlWriter w = new XmlWriter()
            .header()
            .open("ListPartsResult")
            .element("Bucket", bucket)
            .element("Key", key)
            .element("UploadId", uploadId);
        for (var e : up.sortedEtags().entrySet()) {
            byte[] part = up.part(e.getKey());
            w.open("Part")
                .element("PartNumber", e.getKey())
                .element("ETag", Etag.quoted(e.getValue()))
                .element("Size", part == null ? 0 : part.length)
                .close("Part");
        }
        w.close("ListPartsResult");
        ResponseWriter.xml(ex, 200, w.toString());
    }

    /**
     * Decodes an {@code aws-chunked} body if the request carries the
     * corresponding {@code Content-Encoding}, otherwise returns the input
     * unchanged.
     *
     * @param ex  the HTTP exchange
     * @param raw the raw request body
     * @return the decoded body, or {@code raw} if no decoding was needed
     * @throws IOException if decoding fails
     */
    private static byte[] decodeIfChunked(HttpExchange ex, byte[] raw)
        throws IOException {
        String enc = ex.getRequestHeaders().getFirst("Content-Encoding");
        if (enc == null || !enc.toLowerCase().contains("aws-chunked")) {
            return raw;
        }
        try (var in = new java.io.ByteArrayInputStream(raw)) {
            return it.iorfino.s3forge.util.AwsChunkedDecoder.decode(in).payload();
        }
    }

    /**
     * {@code GET /{bucket}?uploads}
     *
     * <p>Lists the in-progress multipart uploads for a bucket.</p>
     *
     * @param ex     the HTTP exchange
     * @param bucket the bucket name
     * @throws IOException on I/O failure
     */
    public void listUploads(HttpExchange ex, String bucket) throws IOException {
        if (!store.bucketExists(bucket)) {
            ResponseWriter.error(ex, S3Error.NO_SUCH_BUCKET);
            return;
        }
        var uploads = store.listMultipartUploads(bucket);
        XmlWriter w = new XmlWriter()
            .header()
            .open("ListMultipartUploadsResult")
            .element("Bucket", bucket);
        for (MultipartUpload up : uploads) {
            w.open("Upload")
                .element("Key", up.key())
                .element("UploadId", up.uploadId())
                .element("Initiated", up.initiated().toString())
                .close("Upload");
        }
        w.close("ListMultipartUploadsResult");
        ResponseWriter.xml(ex, 200, w.toString());
    }
}
