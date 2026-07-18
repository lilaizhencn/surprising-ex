# Redis Position Read Model

User position endpoints read a Redis projection, while PostgreSQL remains the sole authority for balances, positions, collateral, risk checks, liquidation, and audit queries. The detailed Chinese design and operating guide is [position-redis-cache_CN.md](position-redis-cache_CN.md).

The projection is keyed by product line and user, uses one Redis Cluster hash tag for position, collateral, and revision hashes, and applies Kafka events through a Lua compare-and-set. Account-provider collects changed position keys inside each local transaction and captures one final `POSITION_CACHE_PROJECTED` outbox snapshot per distinct key immediately before commit. Intermediate position and collateral updates do not emit separate projection rows.

Redis is deliberately not part of an XA transaction. The database transaction plus outbox is durable; the cache is a replayable, revisioned read model. After commit, the exact captured snapshot is offered to a bounded, coalescing worker so the Kafka command thread performs no database or Redis I/O. Kafka/outbox remains recovery when the accelerator is overloaded or Redis fails. If the readiness marker is absent or Redis fails, user reads fail closed with HTTP 503 rather than falling back to PostgreSQL.
