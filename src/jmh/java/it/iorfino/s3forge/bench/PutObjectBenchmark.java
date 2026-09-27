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
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Measures {@code PutObject} throughput and latency.
 *
 * <p>Two parameters are explored:</p>
 * <ul>
 *   <li>{@code backend} — {@code memory} or {@code filesystem}</li>
 *   <li>{@code size} — payload size in bytes (1 KB, 100 KB, 1 MB)</li>
 * </ul>
 *
 * <p>Client concurrency is not a {@code @Param} because JMH fixes the
 * thread count before any {@code @Setup} runs. To explore different
 * concurrency levels, run the benchmark multiple times with the JMH
 * command-line flag {@code -t}, for example:</p>
 *
 * <pre>{@code
 * java -jar target/benchmarks.jar PutObjectBenchmark -t 1
 * java -jar target/benchmarks.jar PutObjectBenchmark -t 8
 * }</pre>
 *
 * <p>Each iteration uses a fresh key, so a benchmark never overwrites the
 * same object twice within a run.</p>
 *
 * @since 0.3.0
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(1)
public class PutObjectBenchmark {

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

        /** Pre-allocated payload, reused across iterations. */
        byte[] payload;

        /** Monotonic counter used to generate unique keys. */
        AtomicInteger counter;

        /**
         * Starts the server, creates the bucket, and allocates the
         * payload.
         *
         * @throws IOException if the server fails to start
         */
        @Setup(Level.Trial)
        public void setup() throws IOException {
            running = "filesystem".equals(backend)
                ? BenchmarkSupport.startFileSystem()
                : BenchmarkSupport.startInMemory();
            running.client().createBucket(b -> b.bucket("bench"));
            payload = BenchmarkSupport.payload(size);
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
     * Uploads a single object with a fresh key.
     *
     * @param state the benchmark state
     */
    @Benchmark
    public void put(BenchState state) {
        String key = "obj-" + state.counter.incrementAndGet();
        state.running.client().putObject(
            PutObjectRequest.builder()
                .bucket("bench").key(key).build(),
            RequestBody.fromBytes(state.payload));
    }
}
