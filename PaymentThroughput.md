# Payment Throughput Benchmark

Measures the performance improvement of the layer0 MCMC optimizations using a 10-client payment simulation.

## Quick Start

```bash
# Run all performance tests (9 unit benchmarks)
mvn test -pl layer0-mcmc -Dtest="Layer0PerformanceTest"

# Run the 10-client payment throughput simulation
mvn test -pl layer0-mcmc -Dtest="perf.PaymentThroughputBenchmark"
```

## What It Measures

Each "payment" simulates the hot-path operations that happen during a real blockchain payment:

| Operation | Count per payment | Optimized |
|-----------|-------------------|-----------|
| JSON serialize (ObjectMapper) | 1x | Singleton mapper |
| JSON deserialize (ObjectMapper) | 1x | Singleton mapper |
| MCMC transition weight (exp) | 8x | Lookup table |
| Hash set lookup | 1x | Batch operations |

Two implementations run sequentially with 10 concurrent clients × 200 payments each:

- **OLD**: `new ObjectMapper()` per call + `Math.exp()`
- **NEW**: `Json.jsonmapper()` singleton + `fastExp()` lookup table

## 10-Client End-to-End Benchmark (Requires External Server)

```bash
# Terminal 1: Start test server (fresh DB)
docker exec -e PGPASSWORD=test1234 test-bigtangle-postgres psql -U root -d postgres \
  -c "DROP DATABASE IF EXISTS layer0;"
docker exec -e PGPASSWORD=test1234 test-bigtangle-postgres psql -U root -d postgres \
  -c "CREATE DATABASE layer0;"

nohup mvn spring-boot:run -pl layer0-server \
  -Dspring-boot.run.jvmArguments="-Dservice.schedule.mcmc=true" \
  -Dspring-boot.run.arguments="--server.net=Test --server.port=8089 \
    --server.mineraddress=mj61qqqkFDcXFx6P5bMtspDH7tJZ7jVHL4" \
  > /tmp/server.log 2>&1 &

# Wait for startup, then:
mvn exec:java -pl layer0-mcmc -Dexec.classpathScope=test \
  -Dexec.mainClass=net.bigtangle.mcmc.test.benchmark.BenchmarkRunner \
  -Dexec.args="http://localhost:8089/"
```

> **Note**: The end-to-end benchmark requires the server genesis key to match `testPriv`. The capitalization `--server.net=Test` (capital T) is essential — `TestParams` checks `"Test"` not `"test"`.

## Test Results (measured)

| Test | Throughput | Speedup |
|------|-----------|---------|
| `Layer0PerformanceTest.testObjectMapperConcurrentAccess` | 20 threads × 5000 ops | **42.9x** |
| `Layer0PerformanceTest.testSharedExecutorOverhead` | 5 threads × 100 requests | **32.0x** |
| `Layer0PerformanceTest.testBlockMCMCDirectVsJson` | 20000 iterations | **155.5x** |
| **`PaymentThroughputBenchmark`** | **10 clients × 200 payments** | **18.7x** |

### PaymentThroughputBenchmark Detailed Output

```
==============================================
  Payment Throughput (10 clients x 200 payments)
==============================================
  OLD (per-call ObjectMapper + Math.exp):
    Time: 10649 ms
    Throughput: 187 tx/s

  NEW (singleton ObjectMapper + fastExp):
    Time: 569 ms
    Throughput: 3511 tx/s

  Speedup: 18x
==============================================
```

## Test Files

| File | Purpose |
|------|---------|
| `layer0-mcmc/.../test/perf/Layer0PerformanceTest.java` | 9 unit benchmarks (no server needed) |
| `layer0-mcmc/.../test/perf/PaymentThroughputBenchmark.java` | 10-client payment simulation |
| `layer0-mcmc/.../performance/MultiClientPerformanceTest.java` | Multi-threaded microbenchmarks |
| `layer0-mcmc/.../benchmark/BenchmarkRunner.java` | End-to-end benchmark (needs server) |
