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
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Measures {@code GetObject} throughput and latency.
 *
 * <p>Two parameters are explored:</p>
 * <ul>
 *   <li>{@code backend} — {@code memory} or {@code filesystem}</li>
 *   <li>{@code size} — payload size in bytes (1 KB, 100 KB, 1 MB)</li>
 * </ul>
 *
 * <p>Before the run, the bucket is preloaded with a fixed number of objects
 * of the given size. Each iteration reads a key chosen round-robin from the
 * preloaded set, so that reads are spread across many objects rather than
 * hitting a single hot key.</p>
 *
 * <p>Client concurrency is controlled at runtime with the JMH command-line
 * flag {@code -t}. For example:</p>
 *
 * <pre>{@code
 * java -jar target/benchmarks.jar GetObjectBenchmark -t 1
 * java -jar target/benchmarks.jar GetObjectBenchmark -t 8
 * }</pre>
 *
 * @since 0.3.0
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(1)
public class GetObjectBenchmark {

    /** Number of objects preloaded into the bucket before the run. */
    private static final int OBJECTS = 1_000;

    /** Benchmark-scoped state, created once per trial. */
    @State(Scope.Benchmark)
    public static class BenchState {

        /** Storage backend under test. */
        @Param({"memory", "filesystem"})
        public String backend;

        /** Payload size in bytes. */
        @Param({"1024", "102400", "1048576"})
        public int size;

        /** Running server and its client. */
        BenchmarkSupport.Running running;

        /** Round-robin index over the preloaded objects. */
        AtomicInteger counter;

        /**
         * Starts the server, creates the bucket, and preloads it with
         * {@link #OBJECTS} objects of the given size.
         *
         * @throws IOException if the server fails to start
         */
        @Setup(Level.Trial)
        public void setup() throws IOException {
            running = "filesystem".equals(backend)
                ? BenchmarkSupport.startFileSystem()
                : BenchmarkSupport.startInMemory();
            running.client().createBucket(b -> b.bucket("bench"));

            byte[] payload = BenchmarkSupport.payload(size);
            for (int i = 0; i < OBJECTS; i++) {
                running.client().putObject(
                    PutObjectRequest.builder()
                        .bucket("bench").key(BenchmarkSupport.key(i)).build(),
                    RequestBody.fromBytes(payload));
            }
            counter = new AtomicInteger();
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
     * Reads a single object, rotating through the preloaded set.
     *
     * @param state the benchmark state
     * @return the object payload as a byte array
     */
    @Benchmark
    public byte[] get(BenchState state) {
        int i = Math.floorMod(state.counter.getAndIncrement(), OBJECTS);
        return state.running.client().getObjectAsBytes(
                GetObjectRequest.builder()
                    .bucket("bench").key(BenchmarkSupport.key(i)).build())
            .asByteArray();
    }
}
