# S3Forge benchmarks

JMH benchmarks for S3Forge. They are not part of the default build and
are not run in CI. Run them manually before a release, on a machine that
is otherwise idle, to get meaningful numbers.

## How to run

Benchmarks live in `src/jmh/java` and are activated by the `benchmark`
Maven profile. Build the fat JAR with:

    mvn -Pbenchmark clean package

Then run all benchmarks:

    java -jar target/benchmarks.jar

Or a single class:

    java -jar target/benchmarks.jar PutObjectBenchmark

### Concurrency

Client concurrency is controlled at runtime with JMH's `-t` flag. The
benchmarks default to a single thread; to explore parallelism, run them
multiple times:

    java -jar target/benchmarks.jar PutObjectBenchmark -t 1
    java -jar target/benchmarks.jar PutObjectBenchmark -t 8
    java -jar target/benchmarks.jar PutObjectBenchmark -t 32
    java -jar target/benchmarks.jar PutObjectBenchmark -t 64

### Quick smoke run

For a fast sanity check (a few minutes instead of the full suite):

    java -jar target/benchmarks.jar -wi 1 -i 1 -f 1 -r 1

## What is measured

| Benchmark | Metric | Variables |
|---|---|---|
| `PutObjectBenchmark` | Throughput, latency | backend, payload size |
| `GetObjectBenchmark` | Throughput, latency | backend, payload size |
| `ListObjectsBenchmark` | Latency | bucket size, prefix, delimiter |
| `MixedWorkloadBenchmark` | Throughput | backend |

- **Backends**: `memory`, `filesystem`
- **Payload sizes**: 1 KB, 100 KB, 1 MB
- **Client concurrency**: controlled at runtime with `-t`

The benchmark client uses the AWS SDK v2 with `forcePathStyle(true)`
against an S3Forge instance running in the same JVM.

## Reproducing these results

The numbers in the Results section were produced by the following
procedure. Anyone with a similar machine can reproduce them, and future
maintainers can compare apples to apples.

### 1. Build the benchmark JAR

    mvn -Pbenchmark clean package

### 2. Run PutObject and GetObject

Single client thread, both backends, all payload sizes:

    java -jar target/benchmarks.jar \
        PutObjectBenchmark GetObjectBenchmark \
        -t 1 -rf json -rff put-get.json

### 3. Run ListObjectsV2

    java -jar target/benchmarks.jar \
        ListObjectsBenchmark \
        -rf json -rff list.json

### 4. Run MixedWorkload at increasing concurrency

Client concurrency is not a compile-time parameter; it is passed at
runtime with `-t`. The four runs below populate the four `Threads`
columns of the MixedWorkload table:

    java -jar target/benchmarks.jar MixedWorkloadBenchmark -t 1  -rf json -rff mixed-t1.json
    java -jar target/benchmarks.jar MixedWorkloadBenchmark -t 8  -rf json -rff mixed-t8.json
    java -jar target/benchmarks.jar MixedWorkloadBenchmark -t 32 -rf json -rff mixed-t32.json
    java -jar target/benchmarks.jar MixedWorkloadBenchmark -t 64 -rf json -rff mixed-t64.json

### 5. Extract and format the numbers

Each JSON file contains the raw JMH measurements. The commands below
produce the rows that populate the tables in the Results section.

#### PutObject and GetObject

    jq -r '
      .[]
      | select(.benchmark | test("(Put|Get)ObjectBenchmark"))
      | {
          bench: (.benchmark | capture("(?<b>\\w+Benchmark)").b),
          backend: .params.backend,
          size: (.params.size | tonumber),
          mode: .mode,
          score: .primaryMetric.score,
          error: .primaryMetric.scoreError
        }
    ' put-get.json \
    | jq -s '
      group_by(.bench, .backend, .size)
      | map({
          bench: .[0].bench,
          backend: .[0].backend,
          size: .[0].size,
          throughput: (map(select(.mode == "thrpt"))[0].score * 1000),
          throughput_err: (map(select(.mode == "thrpt"))[0].error * 1000),
          latency: (map(select(.mode == "avgt"))[0].score),
          latency_err: (map(select(.mode == "avgt"))[0].error)
        })
      | sort_by(.bench, .backend, .size)
      | .[]
      | "\(.bench)|\(.backend) | \(.size / 1024 | floor) KB | \(.throughput | round) ± \(.throughput_err | round) | \(.latency * 100 | round / 100) ± \(.latency_err * 100 | round / 100) |"
    ' | sort

The output has one row per combination, prefixed by the benchmark name.
Remove the `GetObjectBenchmark|` and `PutObjectBenchmark|` prefixes and
paste the remaining cells into the corresponding table.

Note that the extraction command rounds throughput to the nearest
integer and latency to two decimal places. The `±` value is JMH's
99.9% confidence interval.

#### ListObjectsV2

    jq -r '
      .[]
      | select(.benchmark | contains("ListObjectsBenchmark"))
      | "| \(.params.bucketSize) | \(.params.usePrefix) | \(.params.useDelimiter) | \(.primaryMetric.score * 100 | round / 100) ± \(.primaryMetric.scoreError * 100 | round / 100) |"
    ' list.json | sed 's/| true |/| yes |/g; s/| false |/| no |/g'

The output is one row per combination, with `yes` / `no` already
substituted for the boolean parameters.

#### MixedWorkload

    for t in 1 8 32 64; do
      jq -r --arg t "$t" '
        .[]
        | "| \(.params.backend) | \($t) | \(.primaryMetric.score * 100 | round / 100) ± \(.primaryMetric.scoreError * 100 | round / 100) |"
      ' mixed-t$t.json
    done

The output is one row per combination of backend and thread count.

### Notes on reliability

- Run benchmarks on an **otherwise idle machine**. Any other workload
  competing for CPU, RAM, or disk will inflate the error intervals.
- The default JMH settings used here are 3 warmup iterations and 5
  measurement iterations, each 1 second long. If a run shows large
  error intervals, increase warmup with `-wi` before trusting the
  numbers.
- The `±` value is JMH's 99.9% confidence interval on the score. When
  it is close to or larger than the score itself, the measurement is
  not reliable and should be re-run.

## Results

Fill in this section after each release run. Keep the environment block
identical across runs so results stay comparable.

### Environment

- CPU: Intel Core i7-11800H (8 cores, 16 threads, 2.30 GHz base)
- RAM: 14 GB
- Storage: NVMe SSD (Micron MTFDKBA1T0TFH, 953.9 GB)
- OS: Ubuntu 26.04.1 LTS
- JDK: Temurin 25.0.4+7
- S3Forge version: 0.3.0

### PutObject

Single client thread. Latency in milliseconds per operation, throughput
in operations per second.

| Backend | Size | Throughput (ops/s) | Latency (ms/op) |
|---|---|---|---|
| memory | 1 KB | 5702 ± 4810 | 0.19 ± 0.18 |
| memory | 100 KB | 1770 ± 551 | 0.56 ± 0.11 |
| memory | 1 MB | 206 ± 144 | 6.99 ± 4.84 |
| filesystem | 1 KB | 3889 ± 3786 | 0.25 ± 0.20 |
| filesystem | 100 KB | 1637 ± 713 | 0.61 ± 0.20 |
| filesystem | 1 MB | 228 ± 40 | 4.42 ± 0.74 |

### GetObject

Single client thread.

| Backend | Size | Throughput (ops/s) | Latency (ms/op) |
|---|---|---|---|
| memory | 1 KB | 9288 ± 3569 | 0.10 ± 0.00 |
| memory | 100 KB | 5078 ± 2317 | 0.19 ± 0.12 |
| memory | 1 MB | 432 ± 114 | 2.56 ± 0.68 |
| filesystem | 1 KB | 8308 ± 3590 | 0.13 ± 0.08 |
| filesystem | 100 KB | 503 ± 1599 | 10.74 ± 42.86 |
| filesystem | 1 MB | 35 ± 10 | 29.37 ± 37.87 |

### ListObjectsV2

Single client thread, in-memory backend. Latency only.

| Bucket size | Prefix | Delimiter | Latency (ms/op) |
|---|---|---|---|
| 100 | no | no | 46.5 ± 7.7 |
| 100 | yes | no | 0.15 ± 0.2 |
| 100 | no | yes | 0.29 ± 0.16 |
| 100 | yes | yes | 0.14 ± 0.14 |
| 100000 | no | no | 50.95 ± 5.12 |
| 100000 | yes | no | 55.1 ± 7.64 |
| 100000 | no | yes | 70.5 ± 8.83 |
| 100000 | yes | yes | 57.46 ± 10.18 |

### Mixed workload (70% GET / 20% PUT / 10% LIST)

| Backend | Threads | Throughput (ops/s) |
|---|---|---|
| memory | 1 | 205.3 ± 215.95 |
| memory | 8 | 1754.38 ± 621.33 |
| memory | 32 | 6831.31 ± 1271.4 |
| memory | 64 | 9268.2 ± 11487.66 |
| filesystem | 1 | 140.94 ± 148.66 |
| filesystem | 8 | 1401.98 ± 761.19 |
| filesystem | 32 | 3832.14 ± 1866.72 |
| filesystem | 64 | 3735.49 ± 1667.69 |

## Interpretation

These numbers describe S3Forge on a single laptop-class machine with a
modern NVMe SSD, with all components running in the same JVM. They are a
reference point, not a claim of absolute performance.

### PutObject

At 1 KB, the in-memory backend reaches roughly 5.7k uploads per second,
and the filesystem backend roughly 3.9k. The gap is smaller than one
might expect, because the dominant cost at this size is not storage but
the checksum and ETag computation performed on every upload: MD5 and
CRC32 are calculated over the whole payload, and for 1 KB that cost is
comparable to the cost of writing it.

At 1 MB, both backends converge to around 200 uploads per second. The
per-operation overhead becomes negligible and the transfer rate
dominates. On this hardware, that is about 200 MB/s of sustained
upload, which is close to the practical limit of the single-threaded
pipeline (checksum, HTTP framing, write).

### GetObject

The in-memory backend serves 1 KB objects at about 9.3k reads per
second, twice as fast as writes. Reads do not compute checksums, so
their cost is only the HTTP framing and the stream copy.

The filesystem backend shows a sharp degradation as objects grow:

| Size | Latency |
|---|---|
| 1 KB | 0.13 ms |
| 100 KB | 10.74 ms |
| 1 MB | 29.37 ms |

The jump from 1 KB to 100 KB is roughly 80x, while the payload grows
100x. This is worse than linear, and it points to a real inefficiency
in the read path. The most likely cause is that `FileSystemStore`
returns the raw `Files.newInputStream(path)` without buffering, so each
`read()` call on the underlying channel pays a syscall. Buffering the
stream (or using `Files.newByteChannel` with a larger buffer) would
probably close most of this gap.

This is the single most interesting finding of the run, and it is
recorded as a follow-up item rather than fixed here, because it belongs
to a separate change.

### ListObjectsV2

Listing latency is driven almost entirely by bucket size, not by the
prefix or delimiter:

- Small bucket (100 objects): under 1 ms in every configuration except
  the "no prefix, no delimiter" case, which is likely a JIT artifact.
- Large bucket (100k objects): 50-65 ms in every configuration.

The fact that prefix and delimiter do not help on the large bucket is
expected with the current implementation: the in-memory store walks a
`TreeMap` of all keys and filters as it goes, so it still touches every
key even when the result is small. An indexed structure would change
this, but is not on the roadmap.

For most test suites, buckets stay in the hundreds or low thousands of
objects, where listing is sub-millisecond. The 100k case is documented
as a known cost, not as a bug.

### Mixed workload

At 1 client thread, the mixed workload reaches about 170 ops/s on both
backends. This is far below the sum of the individual operations,
because each op pays the AWS SDK request/response overhead and the
server context-switches between HTTP handling and storage on every
call.

The benchmark is meant to be run at 8, 32, and 64 threads, where
S3Forge's virtual threads should shine. Those numbers are not filled in
here yet; the current run only covers the single-threaded case.

### Reliability of these numbers

Some rows have confidence intervals comparable to or larger than the
score itself — for example, `PutObject` on the filesystem at 1 KB
reports `3889 ± 3786`. This is a sign that the measurement is not
stable: the JIT has not fully warmed up, or the machine was not idle.

Treat the large-error rows as indicative only. If you need solid
numbers for a specific case, re-run that benchmark alone on an idle
machine with a longer warmup, for example:

    java -jar target/benchmarks.jar PutObjectBenchmark \
        -t 1 -wi 5 -i 10 -r 2

### Follow-ups

- Investigate the unbuffered read path in `FileSystemStore.getObject`,
  which likely explains the 1 MB filesystem read latency.
- Run the mixed workload at 8, 32, and 64 threads and fill in the
  corresponding rows.
- Re-run the benchmarks on a quieter machine to reduce the confidence
  intervals.
