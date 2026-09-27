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
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulates a mixed workload at high client concurrency: 70% GET, 20% PUT,
 * 10% LIST. This is close to what a real integration test suite running
 * against S3Forge looks like.
 *
 * <p>Two parameters are explored:</p>
 * <ul>
 *   <li>{@code backend} — {@code memory} or {@code filesystem}</li>
 * </ul>
 *
 * <p>The bucket is preloaded with a fixed number of objects so that GET
 * and LIST have data to operate on. PUTs write new keys with a monotonic
 * counter, growing the bucket slowly across iterations. This growth is
 * intentional: it exercises the listing code on a bucket that is not
 * static.</p>
 *
 * <p>Client concurrency is controlled at runtime with the JMH
 * command-line flag {@code -t}. Because the workload is probabilistic,
 * meaningful measurements require more than one client thread. The
 * suggested levels are 8, 32, and 64:</p>
 *
 * <pre>{@code
 * java -jar target/benchmarks.jar MixedWorkloadBenchmark -t 8
 * java -jar target/benchmarks.jar MixedWorkloadBenchmark -t 32
 * java -jar target/benchmarks.jar MixedWorkloadBenchmark -t 64
 * }</pre>
 *
 * @since 0.3.0
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(1)
public class MixedWorkloadBenchmark {

    /** Objects preloaded into the bucket before the run. */
    private static final int SEED = 1_000;

    /** Probability, in percent, that a single operation is a GET. */
    private static final int GET_PERCENT = 70;

    /** Probability, in percent, that a single operation is a PUT. */
    private static final int PUT_PERCENT = 20;

    // LIST gets the remaining 10%.

    /** Benchmark-scoped state, created once per trial. */
    @State(Scope.Benchmark)
    public static class BenchState {

        /** Storage backend under test. */
        @Param({"memory", "filesystem"})
        public String backend;

        /** Running server and its client. */
        BenchmarkSupport.Running running;

        /** Monotonic counter used to generate unique PUT keys. */
        AtomicInteger putCounter;

        /** Payload reused across all PUT operations (1 KB). */
        byte[] payload;

        /**
         * Starts the server, creates the bucket, and preloads it with
         * {@link #SEED} objects.
         *
         * @throws IOException if the server fails to start
         */
        @Setup(Level.Trial)
        public void setup() throws IOException {
            running = "filesystem".equals(backend)
                ? BenchmarkSupport.startFileSystem()
                : BenchmarkSupport.startInMemory();
            running.client().createBucket(b -> b.bucket("bench"));

            payload = BenchmarkSupport.payload(BenchmarkSupport.SIZE_1_KB);
            for (int i = 0; i < SEED; i++) {
                running.client().putObject(
                    PutObjectRequest.builder()
                        .bucket("bench").key(BenchmarkSupport.key(i)).build(),
                    RequestBody.fromBytes(payload));
            }
            putCounter = new AtomicInteger();
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
     * Performs one operation drawn from the mixed workload distribution.
     *
     * @param state the benchmark state
     * @return the operation result, or {@code null} for PUT, returned to
     *         prevent dead-code elimination
     */
    @Benchmark
    public Object mixed(BenchState state) {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < GET_PERCENT) {
            int i = ThreadLocalRandom.current().nextInt(SEED);
            return state.running.client().getObjectAsBytes(
                GetObjectRequest.builder()
                    .bucket("bench").key(BenchmarkSupport.key(i)).build());
        } else if (roll < GET_PERCENT + PUT_PERCENT) {
            String key = "new-" + state.putCounter.incrementAndGet();
            state.running.client().putObject(
                PutObjectRequest.builder()
                    .bucket("bench").key(key).build(),
                RequestBody.fromBytes(state.payload));
            return null;
        } else {
            return state.running.client().listObjectsV2(
                ListObjectsV2Request.builder()
                    .bucket("bench").maxKeys(100).build());
        }
    }
}
