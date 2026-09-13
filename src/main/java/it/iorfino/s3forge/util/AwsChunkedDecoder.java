package it.iorfino.s3forge.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Decoder for the {@code aws-chunked} content encoding used by AWS SDKs when transmitting objects
 * with trailing checksums.
 *
 * <p>Starting with AWS SDK for Java v2 version 2.30.0, the SDK enables CRC32 checksums by default
 * for operations such as {@code PutObject} and {@code UploadPart}. To avoid interleaving binary
 * checksum data with the object payload, the SDK wraps the body using the {@code aws-chunked}
 * transfer encoding and emits the checksums as HTTP trailers at the end of the stream.
 *
 * <p>The wire format consists of a sequence of chunks, each prefixed by its size in hexadecimal
 * followed by CRLF, then the chunk data followed by CRLF. A zero-sized chunk terminates the stream,
 * optionally followed by trailer headers (e.g. {@code x-amz-checksum-crc32}) and a final CRLF. Each
 * chunk may carry extensions after a semicolon, most commonly a {@code chunk-signature} used by AWS
 * Signature Version 4 streaming uploads:
 *
 * <pre>{@code
 * 1a;chunk-signature=abc123\r\n
 * <26 bytes of payload>\r\n
 * 0;chunk-signature=def456\r\n
 * x-amz-checksum-crc32:YABb/g==\r\n
 * \r\n
 * }</pre>
 *
 * <p>This decoder is tolerant of both signed and unsigned chunks: chunk extensions are parsed but
 * ignored, and trailer header names are normalized to lowercase for consistent lookup.
 *
 * <p>Instances of this class are stateless; all state is local to the {@link #decode(InputStream)}
 * call, making the decoder safe for concurrent use.
 *
 * @since 0.1.0
 */
public final class AwsChunkedDecoder {

    /**
     * Result of decoding an {@code aws-chunked} stream.
     *
     * @param payload the decoded object bytes, with all chunk framing and trailers removed
     * @param trailers trailer headers extracted from the end of the stream; keys are lowercase,
     *     values are trimmed
     * @since 0.1.0
     */
    public record Result(byte[] payload, Map<String, String> trailers) {}

    private AwsChunkedDecoder() {
        // utility class
    }

    /**
     * Decodes an {@code aws-chunked} stream.
     *
     * <p>The input stream is fully consumed but <strong>not</strong> closed; callers retain
     * ownership.
     *
     * @param in the raw request body; must not be {@code null}
     * @return the decoded payload and trailers; never {@code null}
     * @throws IOException if the stream is malformed (truncated chunk, invalid hexadecimal size,
     *     missing CRLF)
     * @throws NumberFormatException wrapped in {@link IOException} if a chunk size line is not
     *     valid hexadecimal
     */
    public static Result decode(InputStream in) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream(8192);
        Map<String, String> trailers = new HashMap<>();

        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) break;

            // Chunk size may have extensions after a semicolon
            // (e.g. "1a;chunk-signature=..."). We parse but ignore them.
            int semi = sizeLine.indexOf(';');
            String hexSize = (semi < 0 ? sizeLine : sizeLine.substring(0, semi)).trim();
            if (hexSize.isEmpty()) {
                throw new IOException("Empty chunk size line");
            }

            int size;
            try {
                size = Integer.parseInt(hexSize, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid chunk size: " + hexSize, e);
            }
            if (size < 0) {
                throw new IOException("Negative chunk size: " + size);
            }

            if (size == 0) {
                // Terminal chunk: read trailers until an empty line.
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

            // Read exactly `size` bytes of payload.
            byte[] chunk = in.readNBytes(size);
            if (chunk.length < size) {
                throw new IOException(
                        "Truncated aws-chunked payload: expected "
                                + size
                                + " bytes, got "
                                + chunk.length);
            }
            payload.write(chunk);

            // Consume the CRLF that terminates the chunk data.
            readLine(in);
        }

        return new Result(payload.toByteArray(), trailers);
    }

    /**
     * Reads a CRLF-terminated line from the stream.
     *
     * <p>A trailing carriage return is stripped if present, so the returned string contains only
     * the line content. Returns {@code null} on end-of-stream before any byte is read.
     *
     * @param in the input stream
     * @return the line content without CRLF, or {@code null} at end of stream
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
