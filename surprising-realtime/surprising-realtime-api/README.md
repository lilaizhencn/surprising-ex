# Realtime Contracts and Read Views

Shared realtime codecs, routing directory contracts, Valkey query adapters, and candle events. `CandleUpdatedEvent`/`CandleStatus` remain in `com.surprising.candlestick.api.model`. This library does not own trading decisions or price calculations.

## Valkey writes

`ValkeyReadViewStore.applyCommits` preserves per-user/per-commit atomic DELTA scripts and version/snapshot fences. A single update uses EVAL. Multiple updates share a connection/pipeline: SCRIPT LOAD once, followed by EVALSHA per update. It does not introduce a cross-batch financial cache, new connection, or new thread.

Loading once per batch tolerates script eviction/flush between batches. A concurrent external SCRIPT FLUSH inside the batch can still fail. Partial pipeline errors must not be guessed or blindly replayed; use the existing source/snapshot recovery path and do not advance normal routing on an unknown result.

## Targeted reads

`ValkeyUserQueries.balance/position` uses `readFields`/HMGET for the baseline snapshot, target BALANCE/POSITION delta, source epoch, snapshot time, invalid flag, fence, and export watermark. Preserve freshness and `minExportSequence` checks. Missing snapshots or unavailable data return 503, not a database/Core fallback. Do not decode unrelated order fields for a targeted balance query.

## Verification

```bash
mvn -pl surprising-realtime/surprising-realtime-api -am test
```

Check version ordering, duplicate/stale commits, snapshot fences, script failures, zeroed balances/positions, source switches, export lag, and six-product isolation. Local single-user byte/CPU savings are not whole-system throughput claims.

## Architecture reference

See the [project architecture and class-level trading flows](../../README.md). Historical validation notes describe their recorded versions, not guarantees for the current implementation.
