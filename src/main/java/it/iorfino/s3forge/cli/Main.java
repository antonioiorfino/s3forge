package it.iorfino.s3forge.cli;

import it.iorfino.s3forge.S3Forge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Command-line launcher for the S3Forge embedded server.
 *
 * <p>Parses a minimal set of flags, constructs an {@link S3Forge} instance
 * with the corresponding backend, and blocks until the JVM receives a
 * termination signal. Intended for use in shell scripts, CI pipelines, and
 * the provided Docker image.</p>
 *
 * <p>Supported flags:</p>
 * <ul>
 *   <li>{@code --port N} — TCP port to listen on; default {@code 8001}</li>
 *   <li>{@code --in-memory} — use in-memory storage (default)</li>
 *   <li>{@code --file-system PATH} — use filesystem storage at {@code PATH}</li>
 *   <li>{@code --virtual-host DOMAIN} — enable virtual-host addressing</li>
 *   <li>{@code --help} — print usage and exit</li>
 * </ul>
 *
 * @since 0.1.0
 */
public final class Main {

    private Main() {
        // entry point only
    }

    /**
     * Process entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        Map<String, String> opts;
        try {
            opts = parseArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            printUsage(System.err);
            System.exit(2);
            return;
        }

        if (opts.containsKey("help")) {
            printUsage(System.out);
            return;
        }

        int port = Integer.parseInt(opts.getOrDefault("port", "8001"));
        String fsPath = opts.get("file-system");
        String vhost = opts.get("virtual-host");

        var builder = S3Forge.builder().port(port);
        if (fsPath != null) {
            builder.fileSystem(Path.of(fsPath));
        } else {
            builder.inMemory();
        }
        if (vhost != null) {
            builder.virtualHostDomain(vhost);
        }

        S3Forge forge = builder.build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("S3Forge: shutting down...");
            forge.close();
        }, "s3forge-shutdown"));

        try {
            forge.start();
        } catch (IOException e) {
            System.err.println("Failed to start S3Forge: " + e.getMessage());
            System.exit(1);
            return;
        }

        System.out.println("S3Forge: listening on port " + forge.port());
        if (fsPath != null) {
            System.out.println("S3Forge: filesystem backend at " + fsPath);
        } else {
            System.out.println("S3Forge: in-memory backend");
        }
        if (vhost != null) {
            System.out.println("S3Forge: virtual-host domain " + vhost);
        }

        // Block forever; shutdown hook will close the server.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Parses the command-line arguments into a key/value map.
     *
     * <p>Recognizes {@code --flag value} and {@code --flag=value} forms.
     * Boolean flags are stored with an empty string value.</p>
     *
     * @param args the raw arguments
     * @return a mutable map of parsed options; never {@code null}
     * @throws IllegalArgumentException if an unknown flag is encountered or
     *                                  a flag requiring a value is missing it
     */
    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + a);
            }
            String key;
            String value = "";
            int eq = a.indexOf('=');
            if (eq >= 0) {
                key = a.substring(2, eq);
                value = a.substring(eq + 1);
            } else {
                key = a.substring(2);
                // Known value-bearing flags consume the next argument.
                if (isValueFlag(key)) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(
                            "missing value for --" + key);
                    }
                    value = args[++i];
                }
            }
            out.put(key, value);
        }
        return out;
    }

    /**
     * Returns whether the given flag requires a value argument.
     *
     * @param key the flag name (without leading {@code --})
     * @return {@code true} if a value is expected
     */
    private static boolean isValueFlag(String key) {
        return switch (key) {
            case "port", "file-system", "virtual-host" -> true;
            default -> false;
        };
    }

    /**
     * Prints usage information to the given stream.
     *
     * @param out the destination stream
     */
    private static void printUsage(java.io.PrintStream out) {
        out.println("""
                S3Forge — embedded S3 mock server

                Usage:
                  java -jar s3forge.jar [options]

                Options:
                  --port N                 TCP port (default: 8001; 0 = ephemeral)
                  --in-memory              use in-memory storage (default)
                  --file-system PATH       use filesystem storage at PATH
                  --virtual-host DOMAIN    enable virtual-host addressing
                  --help                   print this message and exit
                """);
    }
}
