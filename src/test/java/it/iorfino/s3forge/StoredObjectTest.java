package it.iorfino.s3forge;

import static org.junit.jupiter.api.Assertions.*;

import it.iorfino.s3forge.store.StoredObject;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class StoredObjectTest {

    @Test
    void metadataIsDefensivelyCopied() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("a", "1");

        StoredObject obj = new StoredObject("b", "k", 0, "", null, null, "", mutable, null);

        mutable.put("b", "2");
        assertNull(obj.metadata().get("b"));
        assertEquals("1", obj.metadata().get("a"));
    }

    @Test
    void nullMetadataBecomesEmptyMap() {
        StoredObject obj = new StoredObject("b", "k", 0, "", null, null, "", null, null);
        assertTrue(obj.metadata().isEmpty());
    }
}
