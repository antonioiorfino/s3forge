package it.iorfino.s3forge.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class EtagTest {

    @Test
    void etagFromStreamMatchesEtagFromBytes() throws IOException {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        assertEquals(Etag.of(data), Etag.of(new ByteArrayInputStream(data)));
    }
}
