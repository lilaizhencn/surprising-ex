# 用户未完成订单 Redis 投影

`surprising-order-provider` 用可重放的 Redis 投影承接普通用户未完成订单查询；PostgreSQL 始终是订单状态和资金变更的唯一权威。

## Key 与产品线隔离

同一用户、同一产品线的可变 key 共用 Redis Cluster hash tag：

```
surprising:order:v1:{LINEAR_PERPETUAL:1001}:open
surprising:order:v1:{LINEAR_PERPETUAL:1001}:open:orders
surprising:order:v1:{LINEAR_PERPETUAL:1001}:open:revisions
surprising:order:v1:{LINEAR_PERPETUAL:1001}:open:epoch
```

- `:open` 为 ZSET；member 与 score 都是单调递增的 `orderId`。score 只允许不超过 `2^53 - 1` 的精确整数，服务会拒绝不能精确表示的订单号。
- `:open:orders` 是以 `orderId` 为 field 的完整 `OrderRecord` 快照 Hash。
- `:open:revisions` 保存每个订单最新 revision。终态删除 ZSET/hash 快照后仍保留 revision tombstone，延迟 Kafka 事件不能把关闭订单重新写回。
- `:open:epoch` 是此用户 key 所属的重建 epoch；产品级 epoch、ready、重建时间和 lease key 也分别按产品线隔离。

仅 `ACCEPTED` 与 `PARTIALLY_FILLED` 会留在投影中。`CANCEL_REQUESTED` 已经是状态迁移请求，不作为未完成订单返回；所有终态都删除 ZSET member 和快照。

## 写入与恢复

1. 订单创建和每次状态变更都在同一个 PostgreSQL 事务中更新 `trading_orders` 并写入既有 outbox。`trading_orders.revision` 从 1 开始；订单状态和撮合成交的每次直接更新都会递增它。
2. 缓存消费者接收已提交的订单事件、撮合结果和成交事件，重新读取权威订单行，校验产品线后执行一次 Lua revision CAS。
3. Lua 脚本只在用户仍属旧 epoch 时清空旧 key；只接受更大的 revision，并原子更新 ZSET、快照 Hash 和 revision Hash。
4. token lease 保护分页重建：先创建新 epoch，再扫描活跃用户及其活跃订单。实时 Kafka 投影可以并行运行并由更高 revision 胜出；扫描结束后才标记 ready。ready 会定时刷新，默认最长五分钟完成一次完整重建。

Redis 异常或数据不完整只会让本次查询回退 PostgreSQL，不会授权任何资金或订单动作。生产 Redis 应启用持久化并使用 `maxmemory-policy noeviction`；不要手工单独修改一个用户的某个 key。

## 查询约定

`GET /orders/open` 接受 `userId`、可选 `symbol`、`limit` 和可选不透明 `cursor`。按 `orderId` 倒序返回，响应带有 `nextCursor`、`hasMore` 和 `sort = orderId.desc`。

查询从 ZSET 顺序读取并取同 key slot 下的 Hash 快照。缺失/损坏快照、用户或产品线不匹配、epoch 过期、Redis 异常，或无法安全确认分页完整性时，整页使用 PostgreSQL 的产品线隔离 keyset 查询；不会把部分 Redis 数据和数据库行拼在同一页。

## 运行配置

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

监控 Redis 延迟/错误、缓存消费者 lag、订单 outbox 积压、重建耗时、数据库回退率和陈旧 revision 数。缓存丢失只允许增加数据库读负载，绝不能决定撤单、余额变动、成交或强平。
