# Aeron Qualification and Diagnostics

Use HotSpot JDK 27 and read [the performance requirements](../../AGENTS-performance.md) first. Local JMH, in-process fixtures, and real network/Cluster qualification measure different boundaries.

| Script | Purpose |
| --- | --- |
| `bin/qualify-aeron-async-stages.sh` | Real single-member stages; one Matcher and global window 256 by default; optional separate GC/JFR diagnostics |
| `bin/qualify-linear-perpetual-scale.sh` | Business scale and stable-window measurements with terminal/funds checks |
| `bin/qualify-linear-perpetual-scenarios.sh` | Selected perpetual scenarios against a real node |
| `bin/analyze-owner-commit-jfr.sh` | Recording/window-aligned CPU, latency, allocation, and coverage analysis |
| `bin/summarize-aeron-async-stage.py` | Stage-log and recording summaries |
| `bin/verify-surefire-reports.sh` | Validate actual Maven test results |
| `bin/test-business-latency-contract-static.sh` | Latency classification, weighting, and merged-percentile contracts |
| `bin/test-verify-surefire-reports.sh`, `bin/tests` | Script tests |
| `bin/generate-oldobject-jfc.sh` | Object-retention recording configuration |

## Measurement boundaries

`ClusterCapacityMain` supplies `MATCH_STREAM` and `MATCH_BATCH_STREAM` workloads. `ClusterOperationalBenchmark` accepts `MIXED` or `FILL_HEAVY` trading profiles; these are different parameter namespaces. Ordinary baseline orders use bidirectional GTC stream traffic. Count orders/items, matched pairs, business operations, and protocol messages separately.

Warmup, stable measurement, and drain are separate phases. Boundary drain waits up to 30 seconds for actual queues and requests. Topology mismatch fails immediately. Report drain separately; stable throughput counts terminal increments only within the stable window. Primary throughput has no profiler; GC/JFR runs are separate. Closed-loop in-flight limits can hide overload latency and do not establish Core saturation.

`LinearPerpetualBenchmarkSupport` advances real commands and waits for actual asynchronous account/Matcher completion. Copy borrowed batch response bytes before returning arena leases. `InProcessTradingAllocationBenchmark` does not include UDP, Consensus, or Archive. Local Treasury receipts do not imply a separate ledger thread. Do not retain unused production interfaces solely for a benchmark.

## Acceptance

Require offered/accepted/terminal consistency, no unfinished work or backlog, funds conservation, correct holds/positions, and recovery for the claimed scope. JMH launcher exit zero is insufficient if its benchmark fork failed. DataLoss, swapping, throttling, mismatched recording windows, and driver limits must be disclosed. Zero allocation samples do not prove zero allocation.

```bash
mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am test-compile -DskipTests
```

Recorded results and validity limits are in [PERFORMANCE_VALIDATION.md](../../PERFORMANCE_VALIDATION.md), including the November 3 M5 Pro full-window and expanded-window diagnostics. Historical values are not current capacity guarantees.

## Architecture reference

See the [project architecture and class-level trading flows](../../README.md). Historical validation notes describe their recorded versions, not guarantees for the current implementation.
