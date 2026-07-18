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

Hash field 固定为 `SYMBOL|MARGIN_MODE|POSITION_SIDE`。`state` 保存数量、开仓价、开仓价值、已实现盈亏、合约版本、更新时间和 revision；`margin` 保存结算资产、持仓保证金、更新时间和 revision；`revision` 保存最终比较值。产品线在 key 和快照中显式保存，不能跨线读取或写入。

平仓后的零仓位保留在 Hash 中，而不是 `HDEL`。这样已实现盈亏可保留，且延迟到达的旧事件不能把已经关闭的仓位“复活”；`/positions` 仅过滤返回数量非零的字段。

## 一致性设计

`account_positions` 和 `account_position_margins` 都有数据库分配的 `cache_revision`。account-provider 在每个资金事务中收集发生变化的持仓键，并在提交前按
`productLine + userId + symbol + marginMode + positionSide` 去重。每个键只查询一次事务内最终持仓和保证金状态，并分配全局递增 revision：

```text
业务更新持仓/保证金
  -> PostgreSQL 分配递增 revision
  -> 提交前按持仓键合并
  -> 同一事务读取一份最终快照，不写 outbox
  -> 提交后放入本机有界合并队列
  -> 后台线程执行 Redis Lua CAS
  -> 原子写 state + margin + revision
```

Lua 只接受比当前 revision 更大的快照，重复或乱序写入会被安全忽略。事务提交后，同一份最终快照只放入内存有界队列，由后台线程写 Redis，资金事务不等待 Redis。热点持仓的待处理快照按 revision 合并；队列满或 Redis 写失败时会删除对应产品线 readiness marker，用户查询暂时返回 503，现有协调器从 PostgreSQL 重建后恢复。资金费和 ADL 模块不直接更新保证金或持仓，而是通过账户指令由 account-provider 统一写入。

因此不使用 XA。PostgreSQL 与 Redis 不被当作一个全局事务：数据库是唯一事实，Redis 是可重建的 revision 快照。这个边界既避免跨系统两阶段提交的锁和故障复杂度，也保证 Redis 故障不会造成余额或持仓事实丢失。进程在数据库提交后、快照入队前退出的极小窗口由启动和周期 PostgreSQL 对账修复。

反向开仓必须先释放旧仓保证金、再转入新仓保证金；否则按平仓比例释放时会误把新仓保证金一起释放。该顺序有专门回归测试保护。

## 启动、恢复和失败策略

1. 缓存服务启动时，若当前产品线没有 readiness marker，会通过 token lease 由一个节点分页扫描 PostgreSQL 并重建 Redis。
2. 扫描、提交后写入和周期校验都使用同一 revision CAS，因此旧扫描页不会覆盖新成交。
3. 构建完成才写 `ready` marker；用户查询在 marker 缺失或 Redis 异常时返回 HTTP 503，**不回退到数据库**，避免数据库被缓存故障放大。
4. ready 节点周期性刷新 marker，并循环校验一个数据库分页；健康检查、Prometheus counter 会暴露应用、陈旧事件、失败和重建条数。

Redis 必须使用持久化，并配置 `maxmemory-policy noeviction`。如果 Redis 被清空，marker 会消失，用户读暂时 503，重建完成后恢复；不要将“丢 key”解释成“用户没有仓位”。

## 运维配置

缓存链路没有 Kafka topic，也没有缓存 consumer group。Account provider 的主要配置位于 `surprising.account.position-cache`：

```yaml
surprising:
  account:
    position-cache:
      key-prefix: surprising:position:v1
      rebuild-batch-size: 1000
      reconcile-delay-ms: 10000
      ready-ttl: 30s
      lock-ttl: 30s
      accelerator-threads: 4
      accelerator-queue-capacity: 10000
```

未上线环境可直接使用 `v1` 新键；不要为旧缓存值或旧缓存事件保留兼容分支。若需要重置测试环境，清空该 key prefix 后让服务自动重建即可。

## 优势与取舍

- 用户读由 `HGET/HVALS` 完成，按用户聚合，显著减少 `account_positions`、`instruments` 和保证金表的重复查询。
- 业务写仍是单一 PostgreSQL 事务，不把资金正确性依赖在缓存可用性上。
- 所有持仓和保证金写入都收口在 account-provider；新增写路径必须注册事务级持仓投影，单写者边界审计禁止其他模块直接修改账户表。
- revision 让异步更新、滚动重启和重建可预测；租约只避免重复工作，不承担业务锁职责。
- 代价是缓存是允许短暂丢更新的异步读模型；提交后有界队列降低正常延迟，周期对账负责最终修复。Redis 不可用或队列溢出时用户持仓读会明确失败，而不是返回可能错误的零仓位。
