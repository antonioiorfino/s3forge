package it.iorfino.s3forge.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed representation of a single HTTP {@code Range} specification.
 *
 * <p>Only the {@code bytes} unit is supported, which is the only unit used by S3. Multi-range
 * requests ({@code bytes=0-9,20-29}) are reduced to the first range; this is documented as a
 * limitation of S3Forge.
 *
 * <p>A {@link RangeSpec} is <em>resolved</em> against a total length using {@link #resolve(long)}
 * to obtain concrete byte offsets. This separation allows the same spec to be applied to a body
 * whose size is only known at read time.
 *
 * @since 0.1.0
 */
public final class RangeSpec {

    private static final Pattern RANGE_PATTERN = Pattern.compile("bytes=(\\d*)-(\\d*)");

    private final Long start; // null means "from end" (suffix range)
    private final Long end; // null means "to end"

    private RangeSpec(Long start, Long end) {
        this.start = start;
        this.end = end;
    }

    /**
     * Parses the value of an HTTP {@code Range} header.
     *
     * @param header the raw header value; may be {@code null}
     * @return an {@link Optional} containing the parsed spec, or {@link Optional#empty()} if the
     *     header is missing, uses an unsupported unit, or is syntactically invalid. Callers should
     *     treat an empty result as "serve the full body".
     */
    public static Optional<RangeSpec> parse(String header) {
        if (header == null || header.isEmpty()) return Optional.empty();

        // Reduce multi-range to the first range.
        int comma = header.indexOf(',');
        String first = comma >= 0 ? header.substring(0, comma) : header;

        Matcher m = RANGE_PATTERN.matcher(first.trim());
        if (!m.matches()) return Optional.empty();

        String s = m.group(1);
        String e = m.group(2);

        // At least one of start/end must be present.
        if (s.isEmpty() && e.isEmpty()) return Optional.empty();

        try {
            Long start = s.isEmpty() ? null : Long.parseLong(s);
            Long end = e.isEmpty() ? null : Long.parseLong(e);
            // bytes=5-3 is invalid
            if (start != null && end != null && start > end) {
                return Optional.empty();
            }
            return Optional.of(new RangeSpec(start, end));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /**
     * Resolves this spec against a total body length.
     *
     * @param totalLength the total size of the object in bytes
     * @return a {@link Resolved} instance with concrete offsets, or {@link Optional#empty()} if the
     *     range cannot be satisfied (e.g. {@code start >= totalLength} or a zero-length suffix on
     *     an empty body)
     */
    public Optional<Resolved> resolve(long totalLength) {
        if (totalLength < 0) return Optional.empty();

        long resolvedStart;
        long resolvedEnd;

        if (start == null) {
            // suffix range: bytes=-N → last N bytes
            long n = end == null ? 0 : end;
            if (n == 0) return Optional.empty();
            if (n >= totalLength) {
                resolvedStart = 0;
            } else {
                resolvedStart = totalLength - n;
            }
            resolvedEnd = totalLength - 1;
        } else {
            resolvedStart = start;
            if (resolvedStart >= totalLength) return Optional.empty();
            resolvedEnd = (end == null || end >= totalLength) ? totalLength - 1 : end;
        }

        return Optional.of(new Resolved(resolvedStart, resolvedEnd, totalLength));
    }

    /**
     * Concrete byte offsets obtained by resolving a {@link RangeSpec} against a known total length.
     *
     * @param start first byte offset, inclusive
     * @param end last byte offset, inclusive
     * @param total total length of the object
     * @since 0.1.0
     */
    public record Resolved(long start, long end, long total) {

        /**
         * Returns the number of bytes covered by this range.
         *
         * @return {@code end - start + 1}
         */
        public long length() {
            return end - start + 1;
        }

        /**
         * Returns the value for the {@code Content-Range} response header.
         *
         * @return e.g. {@code "bytes 0-99/1000"}
         */
        public String contentRange() {
            return "bytes " + start + "-" + end + "/" + total;
        }
    }
}
