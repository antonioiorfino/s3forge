package it.iorfino.s3forge.store;


import java.util.List;

/**
 * Result of a {@link Store#listObjects} call.
 *
 * <p>Contains the matched object summaries, any common prefixes derived from
 * delimiter grouping, and pagination information.</p>
 *
 * @param objects               matched object summaries; never {@code null}
 * @param commonPrefixes        grouped prefixes; never {@code null}
 * @param truncated             whether the result was cut short by the
 *                              {@code maxKeys} limit
 * @param nextMarker            for v1 listings, the marker to pass in the next
 *                              request to continue; {@code null} when not
 *                              truncated
 * @param nextContinuationToken for v2 listings, the token to pass in the next
 *                              request; {@code null} when not truncated
 * @since 0.1.0
 */
public record ListResult(
    List<StoredObject> objects,
    List<String> commonPrefixes,
    boolean truncated,
    String nextMarker,
    String nextContinuationToken
) {
    /**
     * Returns an empty, non-truncated result.
     *
     * @return a shared empty {@link ListResult}
     */
    public static ListResult empty() {
        return new ListResult(List.of(), List.of(), false, null, null);
    }

    /**
     * Returns a non-truncated result with the given content.
     *
     * @param objects        matched object summaries
     * @param commonPrefixes grouped prefixes
     * @return a non-truncated {@link ListResult}
     */
    public static ListResult of(List<StoredObject> objects, List<String> commonPrefixes) {
        return new ListResult(objects, commonPrefixes, false, null, null);
    }

    /**
     * Returns a truncated result with the given pagination markers.
     *
     * @param objects               matched object summaries
     * @param commonPrefixes        grouped prefixes
     * @param nextMarker            v1 marker for the next page
     * @param nextContinuationToken v2 token for the next page
     * @return a truncated {@link ListResult}
     */
    public static ListResult truncated(List<StoredObject> objects,
                                       List<String> commonPrefixes,
                                       String nextMarker,
                                       String nextContinuationToken) {
        return new ListResult(objects, commonPrefixes, true,
            nextMarker, nextContinuationToken);
    }
}
