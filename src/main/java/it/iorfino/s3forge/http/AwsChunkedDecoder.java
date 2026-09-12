package it.iorfino.s3forge.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Decoder for the {@code aws-chunked} content encoding used by AWS SDKs
 * when transmitting objects with trailing checksums.
 *
 * <p>The wire format consists of a sequence of chunks, each prefixed by its
 * size in hexadecimal followed by CRLF, then the chunk data followed by CRLF.
 * A zero-sized chunk terminates the stream, optionally followed by trailer
 * headers (e.g. {@code x-amz-checksum-crc32}) and a final CRLF.</p>
 *
 * @since 0.1.0
 */
public final class AwsChunkedDecoder {

    /**
     * Result of decoding an {@code aws-chunked} stream.
     *
     * @param payload  the decoded object bytes
     * @param trailers trailer headers extracted from the end of the stream;
     *                 keys are lowercase
     */
    public record Result(byte[] payload, Map<String, String> trailers) {}

    private AwsChunkedDecoder() {}

    /**
     * Decodes an {@code aws-chunked} stream.
     *
     * @param in the raw request body; must not be {@code null}
     * @return the decoded payload and trailers; never {@code null}
     * @throws IOException if the stream is malformed
     */
    public static Result decode(InputStream in) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream(8192);
        Map<String, String> trailers = new HashMap<>();

        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) break;

            // Chunk size may have extensions after a semicolon (e.g. "1a;chunk-signature=...")
            int semi = sizeLine.indexOf(';');
            String hexSize = semi < 0 ? sizeLine : sizeLine.substring(0, semi);
            int size = Integer.parseInt(hexSize.trim(), 16);

            if (size == 0) {
                // Read trailers
                String trailerLine;
                while ((trailerLine = readLine(in)) != null && !trailerLine.isEmpty()) {
                    int colon = trailerLine.indexOf(':');
                    if (colon > 0) {
                        String name = trailerLine.substring(0, colon).trim().toLowerCase();
                        String value = trailerLine.substring(colon + 1).trim();
                        trailers.put(name, value);
                    }
                }
                break;
            }

            // Read chunk data
            byte[] chunk = in.readNBytes(size);
            if (chunk.length < size) {
                throw new IOException("Truncated aws-chunked payload");
            }
            payload.write(chunk);

            // Consume trailing CRLF
            readLine(in);
        }

        return new Result(payload.toByteArray(), trailers);
    }

    /**
     * Reads a CRLF-terminated line from the stream.
     *
     * @param in the input stream
     * @return the line without CRLF, or {@code null} at end of stream
     * @throws IOException on I/O error
     */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = buf.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') {
                    len--;
                }
                return new String(bytes, 0, len, StandardCharsets.UTF_8);
            }
            buf.write(b);
        }
        return buf.size() == 0 ? null : buf.toString(StandardCharsets.UTF_8);
    }
}
