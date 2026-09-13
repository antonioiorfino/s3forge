package it.iorfino.s3forge.xml;

/**
 * Minimal, allocation-friendly XML builder used to produce S3-style response bodies.
 *
 * <p>This class intentionally does not aim to be a general-purpose XML serializer. It only supports
 * the subset of XML needed by S3Forge: a declaration header, nested elements, and text content. All
 * text passed to {@link #element(String, String)} is escaped via {@link XmlEscaper}.
 *
 * <p>Instances are <strong>not</strong> thread-safe.
 *
 * @since 0.1.0
 */
public final class XmlWriter {

    private final StringBuilder sb = new StringBuilder(256);

    /**
     * Appends the XML declaration {@code <?xml version="1.0" encoding="UTF-8"?>}.
     *
     * @return this writer, for chaining
     */
    public XmlWriter header() {
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        return this;
    }

    /**
     * Opens an element with the given tag name.
     *
     * @param tag the tag name; must not be {@code null}
     * @return this writer, for chaining
     */
    public XmlWriter open(String tag) {
        sb.append('<').append(tag).append('>');
        return this;
    }

    /**
     * Closes the most recently opened element with the given tag name.
     *
     * @param tag the tag name; must match the corresponding {@link #open}
     * @return this writer, for chaining
     */
    public XmlWriter close(String tag) {
        sb.append("</").append(tag).append('>');
        return this;
    }

    /**
     * Appends a complete element with escaped text content.
     *
     * @param tag the tag name; must not be {@code null}
     * @param value the text content; may be {@code null} (treated as empty)
     * @return this writer, for chaining
     */
    public XmlWriter element(String tag, String value) {
        sb.append('<')
                .append(tag)
                .append('>')
                .append(XmlEscaper.escape(value))
                .append("</")
                .append(tag)
                .append('>');
        return this;
    }

    /**
     * Appends a complete element with a numeric value.
     *
     * @param tag the tag name; must not be {@code null}
     * @param value the numeric content
     * @return this writer, for chaining
     */
    public XmlWriter element(String tag, long value) {
        sb.append('<').append(tag).append('>').append(value).append("</").append(tag).append('>');
        return this;
    }

    /**
     * Appends a raw, pre-formatted XML fragment without escaping.
     *
     * <p>Use with care: the caller is responsible for ensuring the fragment is well-formed and
     * safe.
     *
     * @param s the raw fragment; must not be {@code null}
     * @return this writer, for chaining
     */
    public XmlWriter raw(String s) {
        sb.append(s);
        return this;
    }

    /**
     * Returns the accumulated XML document.
     *
     * @return the XML string; never {@code null}
     */
    @Override
    public String toString() {
        return sb.toString();
    }
}
