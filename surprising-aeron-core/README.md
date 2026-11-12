# Aeron Trading Core

The module group contains protocol, client, service, tools, and benchmarks. Each of the six product lines runs an isolated Cluster, account state, and order books. Core consumes the replicated command log and owns matching, account settlement, idempotency, snapshots, and recovery. HTTP, database workflows, quote generation, price production, and lifecycle scheduling live outside Core.

## Modules

| Module | Responsibility |
| --- | --- |
| [Protocol](surprising-aeron-protocol/README.md) | Commands, responses, queries, and realtime frames |
| [Client](surprising-aeron-client/README.md) | Cluster sessions, identities, response correlation, and realtime transport |
| [Service](surprising-aeron-service/README.md) | Runtime ownership, admission, matching, accounting, and recovery |
| [Tools](surprising-aeron-tools/README.md) | Probes, diagnostics, and replay utilities |
| [Benchmarks](surprising-aeron-benchmarks/README.md) | Local benchmarks and real-Cluster qualification |

## Build and startup

Use HotSpot JDK 27; run Maven from the repository root.

```bash
mvn -pl surprising-aeron-core/surprising-aeron-service -am package -DskipTests
```

Prefer the [single-node launcher](../deployment/test-single-node/README.md) for an integrated environment. Explicitly configure product line, member hosts, node ID, and separate data directories. `SurprisingCoreApplication` → configuration → `AeronCoreLifecycle` → `AeronTradingClusterService.onStart` → `TradingCoreOwner.start` forms the startup path.

## Ownership and execution

Owner schedules commands and retires them in original log order. Matcher shards own native books. Account Lanes own user balances, holds, reservations, orders, positions, and risk. Treasury and control funds verification run on Owner. `OrderBookRouter` runs Matcher shard threads. `SettlementLaneWorker` runs Account Lane threads.

`OrderCommandQueue.IngressWindow` retains command leases, decoded slots, dependency indices, and response metadata. Account commits route frozen after-values to the relevant Lanes. Order metadata uses captured command changes before consulting active indices; terminal orders must not depend on still being active. Matcher and Account Lane progress use padded slots with explicit publication semantics.

## Snapshots and recovery

Drain the required prefix, establish a snapshot barrier, freeze consistent account and business state, and capture Matcher state at its fence. Audit the complete frozen image before encoding. Releasing a waiting barrier does not cancel a Matcher capture already running; do not admit new trading or snapshots before capture finishes.

`SectionedCoreSnapshotRecovery` validates format, CRC, byte budgets, product line, topology, business/funds hashes, and Matcher pairing. Restore books, Treasury, command identities, source progress, and business cursors from one boundary. Unsupported formats fail explicitly. Application databases are not the recovery authority.

## Verification

```bash
mvn -pl surprising-aeron-core/surprising-aeron-service -am test
```

See [performance requirements](../AGENTS-performance.md). Check funds, holds, positions, ownership, ordering, leases, terminal results, and snapshot/log recovery for the affected path.

## Architecture reference

See the [project architecture and class-level trading flows](../README.md). Historical validation notes describe their recorded versions, not guarantees for the current implementation.
