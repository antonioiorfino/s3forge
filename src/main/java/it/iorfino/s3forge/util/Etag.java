package it.iorfino.s3forge.util;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Utility for computing S3-compatible ETag values.
 *
 * <p>AWS S3 defines the ETag of a non-multipart object as the hexadecimal MD5
 * digest of its payload, wrapped in double quotes in HTTP headers (e.g.
 * {@code "d41d8cd98f00b204e9800998ecf8427e"}). This class produces the raw
 * hex digest; the quoting is applied by the HTTP layer.</p>
 *
 * @since 0.1.0
 */
public final class Etag {

    private Etag() {
        // utility class
    }

    /**
     * Computes the MD5 hex digest of the given bytes.
     *
     * @param data the payload; must not be {@code null}
     * @return the lowercase hexadecimal MD5 digest, 32 characters long
     */
    public static String of(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            // MD5 is guaranteed by the Java platform spec
            throw new IllegalStateException("MD5 not available", e);
        }
    }

    /**
     * Computes the MD5 hex digest of the given stream, fully consuming it.
     *
     * <p>The stream is <strong>not</strong> closed by this method.</p>
     *
     * @param in the input stream; must not be {@code null}
     * @return the lowercase hexadecimal MD5 digest
     * @throws IOException if reading the stream fails
     */
    public static String of(InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }

    /**
     * Wraps an ETag hex digest in double quotes, as required by S3 HTTP
     * headers.
     *
     * @param hexDigest the raw hex digest; must not be {@code null}
     * @return the quoted ETag, e.g. {@code "\"d41d8...\""}
     */
    public static String quoted(String hexDigest) {
        return "\"" + hexDigest + "\"";
    }
}
