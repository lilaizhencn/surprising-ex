# Redis Position Read Model

User position endpoints read a Redis projection, while PostgreSQL remains the sole authority for balances, positions, collateral, risk checks, liquidation, and audit queries. The detailed Chinese design and operating guide is [position-redis-cache_CN.md](position-redis-cache_CN.md).

The projection is keyed by product line and user, uses one Redis Cluster hash tag for position, collateral, and revision hashes, and applies Kafka events through a Lua compare-and-set. PostgreSQL triggers allocate revisions and append `POSITION_CACHE_PROJECTED` rows to the account outbox in the same transaction for every position or position-collateral write, including account settlement, funding, and ADL.

Redis is deliberately not part of an XA transaction. The database transaction plus outbox is durable; the cache is a replayable, revisioned read model. A post-commit account-side write accelerates normal trade visibility, while Kafka/outbox remains recovery. If the readiness marker is absent or Redis fails, user reads fail closed with HTTP 503 rather than falling back to PostgreSQL.
