package it.iorfino.s3forge.cli;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the command-line argument parser.
 *
 * <p>The parser is intentionally small, but its behavior must be predictable
 * because the CLI is the primary integration point for shell scripts and the
 * Docker image.</p>
 *
 * @since 0.1.0
 */
class MainTest {

    @Test
    void parsesSeparatedFlags() {
        Map<String, String> m = Main.parseArgs(new String[]{
                "--port", "9000",
                "--file-system", "/tmp/data",
                "--virtual-host", "localhost"
        });
        assertEquals("9000", m.get("port"));
        assertEquals("/tmp/data", m.get("file-system"));
        assertEquals("localhost", m.get("virtual-host"));
    }

    @Test
    void parsesEqualsSyntax() {
        Map<String, String> m = Main.parseArgs(new String[]{
                "--port=9001",
                "--file-system=/var/s3"
        });
        assertEquals("9001", m.get("port"));
        assertEquals("/var/s3", m.get("file-system"));
    }

    @Test
    void parsesBooleanFlags() {
        Map<String, String> m = Main.parseArgs(new String[]{"--in-memory"});
        assertTrue(m.containsKey("in-memory"));
        assertEquals("", m.get("in-memory"));
    }

    @Test
    void rejectsUnknownPositional() {
        assertThrows(IllegalArgumentException.class,
                () -> Main.parseArgs(new String[]{"positional"}));
    }

    @Test
    void rejectsMissingValue() {
        assertThrows(IllegalArgumentException.class,
                () -> Main.parseArgs(new String[]{"--port"}));
    }

    @Test
    void helpFlagIsParsed() {
        Map<String, String> m = Main.parseArgs(new String[]{"--help"});
        assertTrue(m.containsKey("help"));
    }
}
