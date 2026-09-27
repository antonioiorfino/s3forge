package it.iorfino.s3forge.cli;

import java.io.PrintStream;

/**
 * ASCII banner printed by the command-line launcher at startup.
 *
 * <p>Intentionally minimal: a fixed ASCII art, the version string, and a
 * one-line summary. The banner is not printed by the embedded library
 * API ({@link it.iorfino.s3forge.S3Forge}), only by the CLI, so that tests
 * using S3Forge never see it in their output.</p>
 *
 * @since 0.3.0
 */
final class Banner {

    private Banner() {
        // utility class
    }

    /**
     * Prints the S3Forge banner to the given stream.
     *
     * @param out the destination, usually {@code System.out}
     */
    static void print(PrintStream out) {
        String version = version();
        boolean tty = System.console() != null;
        if (tty) {
            out.println("""
                ███████╗██████╗ ███████╗ ██████╗ ██████╗  ██████╗ ███████╗
                ██╔════╝╚════██╗██╔════╝██╔═══██╗██╔══██╗██╔════╝ ██╔════╝
                ███████╗ █████╔╝█████╗  ██║   ██║██████╔╝██║  ███╗█████╗
                ╚════██║ ╚═══██╗██╔══╝  ██║   ██║██╔══██╗██║   ██║██╔══╝
                ███████║██████╔╝██║     ╚██████╔╝██║  ██║╚██████╔╝███████╗
                ╚══════╝╚═════╝ ╚═╝      ╚═════╝ ╚═╝  ╚═╝ ╚═════╝ ╚══════╝
                """);
        }
        out.println("S3Forge " + version);
        out.println("Embedded S3 mock server · pure Java · zero dependencies");
        out.println("https://github.com/antonioiorfino/s3forge");
        out.println();
    }

    /**
     * Returns the S3Forge version, read from the JAR manifest when running
     * from a packaged JAR, or {@code "dev"} otherwise.
     *
     * @return the version string; never {@code null}
     */
    private static String version() {
        String v = Banner.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }
}
