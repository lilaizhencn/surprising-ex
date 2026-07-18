# Open-order Redis projection

`surprising-order-provider` serves ordinary user open-order reads from a replayable Redis projection. PostgreSQL remains the authority for every order and funds transition.

## Keys and product isolation

For one user and product line, all mutable keys use the same Redis Cluster hash tag:

```
surprising:order:v1:{LINEAR_PERPETUAL:1001}:open
surprising:order:v1:{LINEAR_PERPETUAL:1001}:open:orders
surprising:order:v1:{LINEAR_PERPETUAL:1001}:open:revisions
surprising:order:v1:{LINEAR_PERPETUAL:1001}:open:epoch
```

- `:open` is a ZSET. The member is `orderId`; the score is the same positive monotonic `orderId`. This is exact only up to `2^53 - 1`; the service rejects an unrepresentable id rather than silently corrupting pagination.
- `:open:orders` is a hash of complete `OrderRecord` snapshots keyed by `orderId`.
- `:open:revisions` is a hash keyed by `orderId`. It retains a terminal revision after the ZSET/hash entry is deleted, preventing a delayed event from restoring a closed order.
- `:open:epoch` records the product-line rebuild epoch used by this user's keys. Product-level epoch, ready, rebuild-time, and lease keys are kept separate per product line.

The projection stores only `ACCEPTED` and `PARTIALLY_FILLED` orders. `CANCEL_REQUESTED` is not returned as an open order because it is already a state-transition request; all terminal statuses delete the snapshot and ZSET member.

## Write and recovery flow

1. Order entry and every state mutation update `trading_orders` and write the existing transactional outbox in the same PostgreSQL transaction. `trading_orders.revision` starts at 1 and each direct order-state or matching fill mutation increments it.
2. The cache consumer receives committed order events, matching results, and match trades, reloads the row from PostgreSQL, verifies the configured product line, and invokes one Lua revision compare-and-set.
3. The Lua script clears a user's old epoch only once, ignores a revision not newer than the retained revision, and updates the ZSET/hash/revision hash atomically.
4. A token-leased, paged rebuild scans active users and their active orders. It starts a new epoch, lets live Kafka updates safely overlap, and marks the line ready only after the scan completes. The ready marker is refreshed and a complete rebuild is required at most every configured `rebuild-max-age` (five minutes by default).

Redis errors or incomplete data make the cache unavailable for that request; the endpoint falls back to PostgreSQL. Redis must use persistence and `maxmemory-policy noeviction` in production. Do not manually edit one key of a user's key group.

## Query contract

`GET /orders/open` accepts `userId`, optional `symbol`, `limit`, and optional opaque `cursor`. Results are ordered by descending `orderId`. The response includes `nextCursor`, `hasMore`, and `sort = orderId.desc`.

The cache scans the ordered ZSET and reads corresponding hash snapshots. A missing/corrupt snapshot, wrong product line/user, stale epoch, Redis error, or a scan that cannot safely establish the page makes it use the PostgreSQL product-line-scoped keyset query instead. It never mixes a partial Redis page with database rows.

## Operational configuration

```yaml
surprising:
  trading:
    order:
      redis-index:
        key-prefix: surprising:order:v1
        reconcile-delay-ms: 10000
        rebuild-batch-size: 1000
        rebuild-max-age: 5m
        ready-ttl: 30s
        lock-ttl: 30s
```

Monitor Redis failures and latency, order cache consumer lag, order outbox backlog, rebuild duration, database-fallback rate, and the rate of stale revisions. A cache loss only raises PostgreSQL read load; it must never authorize a cancellation, balance movement, fill, or liquidation action.
