package it.iorfino.s3forge.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Measures {@code ListObjectsV2} latency against buckets of different sizes,
 * with and without prefix and delimiter.
 *
 * <p>Three parameters are explored:</p>
 * <ul>
 *   <li>{@code bucketSize} — 100 or 100,000 objects</li>
 *   <li>{@code usePrefix} — whether to filter by a prefix</li>
 *   <li>{@code useDelimiter} — whether to group by a delimiter</li>
 * </ul>
 *
 * <p>Only latency ({@code AverageTime}) is measured. Throughput in ops/s
 * is not meaningful for listings: each call returns a full page of results,
 * so the interesting number is how long a single listing takes.</p>
 *
 * <p>Keys are laid out in a two-level hierarchy,
 * {@code prefix-NN/key-YYYYYY}, so that prefix and delimiter filtering have
 * something to act on.</p>
 *
 * @since 0.3.0
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(1)
public class ListObjectsBenchmark {

    /** Benchmark-scoped state, created once per trial. */
    @State(Scope.Benchmark)
    public static class BenchState {

        /** Number of objects preloaded into the bucket. */
        @Param({"100", "100000"})
        public int bucketSize;

        /** Whether to pass a prefix to the listing request. */
        @Param({"false", "true"})
        public boolean usePrefix;

        /** Whether to pass a delimiter to the listing request. */
        @Param({"false", "true"})
        public boolean useDelimiter;

        /** Running server and its client. */
        BenchmarkSupport.Running running;

        /**
         * Starts the server, creates the bucket, and preloads it with
         * {@code bucketSize} objects laid out in a two-level hierarchy.
         *
         * @throws IOException if the server fails to start
         */
        @Setup(Level.Trial)
        public void setup() throws IOException {
            running = BenchmarkSupport.startInMemory();
            running.client().createBucket(b -> b.bucket("bench"));

            for (int i = 0; i < bucketSize; i++) {
                String key = String.format("prefix-%02d/key-%06d", i % 100, i);
                running.client().putObject(
                    PutObjectRequest.builder()
                        .bucket("bench").key(key).build(),
                    RequestBody.fromString("x"));
            }
        }

        /**
         * Stops the server and releases all resources.
         */
        @TearDown(Level.Trial)
        public void teardown() {
            running.close();
        }
    }

    /**
     * Runs a single {@code ListObjectsV2} call with the configured prefix
     * and delimiter.
     *
     * @param state the benchmark state
     * @return the listing response, returned to prevent dead-code
     *         elimination
     */
    @Benchmark
    public ListObjectsV2Response list(BenchState state) {
        var b = ListObjectsV2Request.builder().bucket("bench");
        if (state.usePrefix) {
            b.prefix("prefix-42/");
        }
        if (state.useDelimiter) {
            b.delimiter("/");
        }
        return state.running.client().listObjectsV2(b.build());
    }
}
