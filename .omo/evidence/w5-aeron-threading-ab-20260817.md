# W5 Aeron threading mode A/B

- branch: `codex/w5-aeron-threading-ab`
- baseline: `da68f238bb4833bfbdb4c0d8061a8787c839d6a2`
- experiment commits: `8ef0f81`, `516bc8c`
- product line: `LINEAR_PERPETUAL`
- JDK: Homebrew OpenJDK `25.0.4`
- PostgreSQL: `18`
- exchange-core: `0.5.15-emporia`, SHA-256 `09e324685e9ae77244939c9f8c4044dc00dda4f03b98b60ff5d48f7e051e2d21`

## SHARED

Run ID: `w5-ab-shared-5`.

PostgreSQL, Kafka, migrations, topics and the initial three-member state query passed. During normal stack startup all three Core logs emitted `DriverTimeoutException: MediaDriver ... has been shutdown`. The run failed before the W5 fault driver and cleanup completed with `SCENARIO_EXIT=2`.

## DEDICATED

Run ID: `w5-ab-dedicated-1`.

Core and client MediaDrivers used `DEDICATED`. PostgreSQL, Kafka, migrations, topics and the initial three-member state query passed. The cluster remained available beyond the SHARED failure window and five additional independent `STATE_HASH_QUERY` requests returned `status=OK` with stable state hash `4088be5c763d0f67`. No MediaDriver timeout occurred before controlled shutdown. Timeout/termination messages emitted after `run.sh down` are shutdown artifacts and are not counted as runtime failure.

## Decision

Default both embedded client and Core MediaDrivers to `DEDICATED`. Keep explicit `AERON_CLIENT_THREADING_MODE` and `AERON_CORE_THREADING_MODE` overrides only for controlled diagnostics. Archive threading remains unchanged so the A/B changes one failure boundary at a time.

This experiment does not mark W5 complete. The runtime reached a separate Gateway schema/readiness blocker before the complete Kafka/PG/exporter/projector/Gateway/WebSocket fault matrix, so W5 remains partial.
