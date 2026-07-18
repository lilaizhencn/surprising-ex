# Redis Position Read Model

User position endpoints read a Redis projection, while PostgreSQL remains the sole authority for balances, positions, collateral, risk checks, liquidation, and audit queries. The detailed Chinese design and operating guide is [position-redis-cache_CN.md](position-redis-cache_CN.md).

The projection is keyed by product line and user and uses one Redis Cluster hash tag for position, collateral, and revision hashes. Account-provider collects changed position keys inside each local transaction and captures one final snapshot per distinct key immediately before commit. Only after commit is that snapshot offered to a bounded, coalescing local worker. Position-cache snapshots never enter the account outbox or Kafka.

Redis is deliberately not part of an XA transaction. PostgreSQL is authoritative and the cache is a rebuildable, revisioned read model. A Lua compare-and-set makes concurrent rebuild and after-commit updates safe. Queue overflow or Redis write failure removes the product-line readiness marker; the existing coordinator then rebuilds from PostgreSQL. If the marker is absent or Redis fails, user reads fail closed with HTTP 503 rather than falling back to PostgreSQL.
