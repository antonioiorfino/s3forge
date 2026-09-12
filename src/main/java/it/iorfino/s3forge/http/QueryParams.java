package it.iorfino.s3forge.http;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Small immutable wrapper around HTTP query parameters.
 *
 * <p>Parses a raw query string (the part after {@code ?} in a URL) into a
 * case-sensitive key/value map. All keys and values are URL-decoded using
 * UTF-8. Duplicated keys keep the first occurrence, matching the behavior of
 * most S3 query parameters (which are unique by contract).</p>
 *
 * @since 0.1.0
 */
public final class QueryParams {

    private static final QueryParams EMPTY = new QueryParams(Map.of());

    private final Map<String, String> values;

    private QueryParams(Map<String, String> values) {
        this.values = values;
    }

    /**
     * Parses a raw query string.
     *
     * @param rawQuery the raw query string (may be {@code null} or empty)
     * @return a {@link QueryParams} instance; never {@code null}
     */
    public static QueryParams parse(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return EMPTY;
        Map<String, String> map = new HashMap<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            map.putIfAbsent(decode(k), decode(v));
        }
        return new QueryParams(Map.copyOf(map));
    }

    /**
     * Returns an empty {@link QueryParams} instance.
     *
     * @return the shared empty instance
     */
    public static QueryParams empty() {
        return EMPTY;
    }

    /**
     * Returns the raw value of a parameter, or {@code null} if absent.
     *
     * @param key the parameter name
     * @return the value, or {@code null}
     */
    public String get(String key) {
        return values.get(key);
    }

    /**
     * Returns the value of a parameter, or {@code defaultVal} if absent or
     * empty.
     *
     * @param key        the parameter name
     * @param defaultVal the fallback value
     * @return the value or the fallback; never {@code null}
     */
    public String getOrDefault(String key, String defaultVal) {
        String v = values.get(key);
        return v == null ? defaultVal : v;
    }

    /**
     * Returns whether the parameter is present (even with an empty value).
     *
     * @param key the parameter name
     * @return {@code true} if present
     */
    public boolean contains(String key) {
        return values.containsKey(key);
    }

    /**
     * Parses a parameter as a non-negative integer.
     *
     * @param key        the parameter name
     * @param defaultVal the value to return if the parameter is absent
     * @return the parsed integer or the default
     * @throws IllegalArgumentException if the value is present but not a
     *                                  non-negative integer
     */
    public int getInt(String key, int defaultVal) {
        String v = values.get(key);
        if (v == null || v.isEmpty()) return defaultVal;
        try {
            int n = Integer.parseInt(v);
            if (n < 0) throw new NumberFormatException("negative");
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid integer for parameter '" + key + "': " + v, e);
        }
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
