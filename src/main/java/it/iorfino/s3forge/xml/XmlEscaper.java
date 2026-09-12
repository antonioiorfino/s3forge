package it.iorfino.s3forge.xml;

/**
 * Minimal utility for escaping characters that are illegal in XML text nodes
 * and attribute values.
 *
 * <p>Handles the five predefined XML entities: {@code &amp;}, {@code &lt;},
 * {@code &gt;}, {@code &quot;}, {@code &apos;}.</p>
 *
 * @since 0.1.0
 */
public final class XmlEscaper {

    private XmlEscaper() {
        // utility class
    }

    /**
     * Escapes XML special characters in the given string.
     *
     * @param s the input string; may be {@code null}
     * @return the escaped string, or an empty string if {@code s} is
     *         {@code null}
     */
    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&'  -> out.append("&amp;");
                case '<'  -> out.append("&lt;");
                case '>'  -> out.append("&gt;");
                case '"'  -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default   -> out.append(c);
            }
        }
        return out.toString();
    }
}
