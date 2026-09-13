package it.iorfino.s3forge;

import static org.junit.jupiter.api.Assertions.*;

import it.iorfino.s3forge.store.StoredObject;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StoredObject}, focused on the defensive copying behavior of its compact
 * constructor.
 *
 * <p>These tests do not exercise the HTTP layer; they verify the record's invariants directly,
 * which matters because the record is shared by both storage backends and by the HTTP handlers.
 *
 * @since 0.2.0
 */
public class StoredObjectTest {

    /**
     * Verifies that the metadata map passed to the constructor is copied defensively, so later
     * mutations of the caller's map do not affect the record.
     */
    @Test
    void metadataIsDefensivelyCopied() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("a", "1");

        StoredObject obj = new StoredObject("b", "k", 0, "", null, null, "", mutable, null, null);

        mutable.put("b", "2");
        assertNull(obj.metadata().get("b"));
        assertEquals("1", obj.metadata().get("a"));
    }

    /**
     * Verifies that a {@code null} metadata map is normalized to an empty immutable map, so callers
     * never have to null-check the accessor.
     */
    @Test
    void nullMetadataBecomesEmptyMap() {
        StoredObject obj = new StoredObject("b", "k", 0, "", null, null, "", null, null, null);
        assertTrue(obj.metadata().isEmpty());
    }
}
