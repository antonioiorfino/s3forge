package it.iorfino.s3forge.http;

import com.sun.net.httpserver.HttpExchange;
import it.iorfino.s3forge.model.S3Error;
import it.iorfino.s3forge.store.Store;
import it.iorfino.s3forge.xml.XmlReader;
import it.iorfino.s3forge.xml.XmlWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Element;

/**
 * Handler for the S3 {@code DeleteObjects} batch operation.
 *
 * <p>Route: {@code POST /{bucket}?delete}. The request body is an XML document listing the keys to
 * delete:
 *
 * <pre>{@code
 * <Delete>
 *   <Object><Key>a.txt</Key></Object>
 *   <Object><Key>b.txt</Key><VersionId>xyz</VersionId></Object>
 *   <Quiet>false</Quiet>
 * </Delete>
 * }</pre>
 *
 * <p>Response is a {@code DeleteResult} listing each deleted key and, when {@code Quiet} is false,
 * the successfully deleted objects. S3 semantics are followed: deleting a non-existent key is not
 * an error and appears in {@code <Deleted>}.
 *
 * @since 0.1.0
 */
public final class DeleteObjectsHandler {

    /** Maximum number of keys accepted in a single request, as in real S3. */
    private static final int MAX_KEYS = 1000;

    private final Store store;

    /**
     * Creates a new handler.
     *
     * @param store the storage backend; must not be {@code null}
     */
    public DeleteObjectsHandler(Store store) {
        this.store = store;
    }

    /**
     * Handles a {@code POST /{bucket}?delete} request.
     *
     * @param ex the HTTP exchange
     * @param bucket the bucket name
     * @throws IOException on I/O failure
     */
    public void handle(HttpExchange ex, String bucket) throws IOException {
        if (!store.bucketExists(bucket)) {
            ResponseWriter.error(ex, S3Error.NO_SUCH_BUCKET);
            return;
        }

        byte[] body;
        try (var in = ex.getRequestBody()) {
            body = in.readAllBytes();
        }

        XmlReader xml;
        try {
            xml = XmlReader.parse(body);
        } catch (IOException e) {
            ResponseWriter.error(ex, S3Error.MALFORMED_XML);
            return;
        }

        Element root = xml.root();
        if (!"Delete".equals(root.getNodeName())) {
            ResponseWriter.error(ex, S3Error.MALFORMED_XML);
            return;
        }

        List<Element> objectNodes = XmlReader.children(root, "Object");
        if (objectNodes.size() > MAX_KEYS) {
            ResponseWriter.error(ex, S3Error.TOO_MANY_KEYS);
            return;
        }

        boolean quiet = "true".equalsIgnoreCase(XmlReader.text(root, "Quiet"));

        // Collect keys; S3 ignores VersionId since we don't version.
        List<String> keys = new ArrayList<>(objectNodes.size());
        for (Element obj : objectNodes) {
            String key = XmlReader.text(obj, "Key");
            if (key == null || key.isEmpty()) {
                ResponseWriter.error(ex, S3Error.MALFORMED_XML);
                return;
            }
            keys.add(key);
        }

        // Perform the deletion. Our Store#deleteObjects ignores missing keys.
        store.deleteObjects(bucket, keys);

        // Build DeleteResult.
        XmlWriter w = new XmlWriter().header().open("DeleteResult");
        if (!quiet) {
            for (String key : keys) {
                w.open("Deleted").element("Key", key).close("Deleted");
            }
        }
        w.close("DeleteResult");

        ResponseWriter.xml(ex, 200, w.toString());
    }
}
