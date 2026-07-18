# 用户持仓 Redis 读模型

## 目标与边界

用户侧的 `GET /api/v1/accounts/position`、`/positions` 和 `/position-margin` 只读取 Redis，避免高频持仓查询直接压到 PostgreSQL。PostgreSQL 仍是余额、持仓、保证金、风控和强平的唯一事实源；订单校验、成交结算、资金费、风控和强平不能把 Redis 当作正确性锁。

这不是“Redis 取代数据库”，而是把已经提交的数据库状态投影成一个可高并发读取的用户读模型。管理员持仓查询继续走 PostgreSQL，便于审计和排障。

## 键和产品线隔离

每个用户、每条产品线使用同一个 Redis Cluster hash tag，三个 Hash 可由一段 Lua 一次更新：

```text
surprising:position:v1:{LINEAR_PERPETUAL:1001}:state
surprising:position:v1:{LINEAR_PERPETUAL:1001}:margin
surprising:position:v1:{LINEAR_PERPETUAL:1001}:revision
```

Hash field 固定为 `SYMBOL|MARGIN_MODE|POSITION_SIDE`。`state` 保存数量、开仓价、开仓价值、已实现盈亏、合约版本、更新时间和 revision；`margin` 保存结算资产、持仓保证金、更新时间和 revision；`revision` 保存最终比较值。产品线在 key、事件、Kafka topic 和 outbox 行中都显式保存，不能跨线读取或发布。

平仓后的零仓位保留在 Hash 中，而不是 `HDEL`。这样已实现盈亏可保留，且延迟到达的旧事件不能把已经关闭的仓位“复活”；`/positions` 仅过滤返回数量非零的字段。

## 一致性设计

`account_positions` 和 `account_position_margins` 都有数据库分配的 `cache_revision`。两张表的 insert/update/delete 触发器在同一个 PostgreSQL 事务中写入 `account_outbox_events`：

```text
业务更新持仓/保证金
  -> PostgreSQL 分配递增 revision
  -> 同一事务写 POSITION_CACHE_PROJECTED outbox
  -> 提交后 account outbox 发布 Kafka
  -> account 缓存消费者执行 Redis Lua CAS
  -> 原子写 state + margin + revision
```

Lua 只接受比当前 revision 更大的事件。Kafka、outbox 和消费者均可以至少一次重试；重复消息和乱序消息会被安全忽略。账户服务对成交、正常平仓、强平成交、资金费、ADL、交割/行权和手工逐仓保证金调整注册 `afterCommit` 加速投影，降低用户刚发生资金变更后的读取延迟。它只是加速器：失败不会回滚已提交资金，Kafka/outbox 会继续恢复。资金费和 ADL 模块不再直接更新保证金或持仓，而是通过账户指令由 account-provider 统一写入；数据库触发器仍会覆盖所有已提交变更。

因此不使用 XA。PostgreSQL 与 Redis 不被当作一个全局事务：数据库事实与 outbox 必须原子提交，Redis 是可重放的 revision 投影。这个边界既避免跨系统两阶段提交的锁和故障复杂度，也保证 Redis 故障不会造成余额或持仓事实丢失。

反向开仓必须先释放旧仓保证金、再转入新仓保证金；否则按平仓比例释放时会误把新仓保证金一起释放。该顺序有专门回归测试保护。

## 启动、恢复和失败策略

1. 缓存服务启动时，若当前产品线没有 readiness marker，会通过 token lease 由一个节点分页扫描 PostgreSQL 并重建 Redis。
2. 扫描、Kafka 回放和周期校验都使用同一 revision CAS，因此旧扫描页不会覆盖新成交。
3. 构建完成才写 `ready` marker；用户查询在 marker 缺失或 Redis 异常时返回 HTTP 503，**不回退到数据库**，避免数据库被缓存故障放大。
4. ready 节点周期性刷新 marker，并循环校验一个数据库分页；健康检查、Prometheus counter 会暴露应用、陈旧事件、失败和重建条数。

Redis 必须使用持久化，并配置 `maxmemory-policy noeviction`。如果 Redis 被清空，marker 会消失，用户读暂时 503，重建完成后恢复；不要将“丢 key”解释成“用户没有仓位”。

## 主题与运维配置

每条产品线使用独立缓存 topic，例如：

```text
surprising.linear-perp.account.position-cache.events.v1
surprising.inverse-perp.account.position-cache.events.v1
surprising.linear-delivery.account.position-cache.events.v1
```

Account provider 的主要配置位于 `surprising.account.position-cache`：

```yaml
surprising:
  account:
    position-cache:
      key-prefix: surprising:position:v1
      rebuild-batch-size: 1000
      reconcile-delay-ms: 10000
      ready-ttl: 30s
      lock-ttl: 30s
```

未上线环境可直接使用 `v1` 新键和新 topic；不要为旧缓存值、旧 outbox payload 或旧事件格式保留兼容分支。若需要重置测试环境，清空该 key prefix 后让服务自动重建即可。

## 优势与取舍

- 用户读由 `HGET/HVALS` 完成，按用户聚合，显著减少 `account_positions`、`instruments` 和保证金表的重复查询。
- 业务写仍是单一 PostgreSQL 事务，不把资金正确性依赖在缓存可用性上。
- 所有写入者共享数据库触发器，新增资金费、ADL 或维护逻辑时不会因遗漏一个应用层缓存调用而产生陈旧仓位。
- revision 让重试、重放、滚动重启和重建可预测；租约只避免重复工作，不承担业务锁职责。
- 代价是缓存是异步读模型，极短暂延迟由 after-commit 加速和 Kafka 回放共同降低；Redis 不可用时用户持仓读会明确失败而非返回可能错误的零仓位。
