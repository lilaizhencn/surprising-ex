# LINEAR_PERPETUAL 压测失败轮报告（2026-07-19）

## 结论

本轮未通过，不能用于给出稳定容量结论。20 symbols / 2000 taker / 并发 128 的 open 阶段完成了全部 2000 次提交，其中 1818 单成交并完成账户双边结算，随后 182 单因 `mark price unavailable` 被拒；close 阶段未执行。

本轮同时暴露了两个独立问题：Kafka factory 开启 batch 后三个 listener 仍使用单条 `ConsumerRecord` 签名，确实造成转换异常和事件丢弃；但后续在该问题修复后复测，仍能在没有转换异常时复现 `mark price unavailable`，因此不能把价格过期归因于 listener 日志风暴。价格过期的直接原因是压测脚本每轮刷新都重新启动 Kafka console producer，在高负载主机上完整快照间隔超过 15 秒新鲜度窗口。最终修复为全程复用一个 producer，并把指数/标记价格服务放到所有下游 consumer 之后启动。

> 复测更正（2026-07-19）：本文件保留失败轮数据用于问题追踪；最终通过结果和准确容量数据见 `docs/linear-perpetual-stress-rerun-20260719.md`。

## 场景与环境

- 产品线：`LINEAR_PERPETUAL`，未启动 wallet、现货、交割、期权。
- 规模：20 symbols，20 maker accounts，2000 taker users。
- 并发：taker 128，maker 32；做市进程在有效压测阶段持续运行。
- 本机环境：Java 21.0.10、PostgreSQL 18.4、Redis 8.8.0、Kafka 4.3.1 单 broker。
- provider：按当前源码重新构建后运行；初次发现的 Java 17 运行时不兼容和陈旧 JAR/schema 不一致均发生在压测流量前，不计入本轮。
- 运行日志：`/tmp/surprising-product-line-api.MncAMw`。
- 旁路采样：`/tmp/linear-perpetual-observability-20260719-run3.log`。

## 链路延迟

单位均为 ms。下单与成交结算只统计成功的 1818 个 taker open 订单；撤单统计压测期间持续运行的 market-maker 撤单。

| 指标 | 样本 | p50 | p95 | p99 | max |
|---|---:|---:|---:|---:|---:|
| 下单 `created -> ACCEPTED` | 1818 | 2076.430 | 2877.053 | 3100.176 | 3717.173 |
| 撤单 `CANCEL_REQUESTED -> matching SUCCESS` | 2729 | 41.816 | 131.279 | 3895.620 | 9749.200 |
| 成交结算 `ACCEPTED -> 双边 account completed` | 1818 | 31824.177 | 47302.113 | 47713.246 | 47858.462 |
| matching command lag `ACCEPTED -> match event` | 1818 | 31660.681 | 47154.693 | 47591.838 | 47715.847 |
| account user-command lag `occurred -> started` | 7632 | 140.955 | 2532.993 | 4104.144 | 17482.499 |
| account processing `started -> completed` | 7632 | 21.508 | 91.950 | 202.744 | 651.787 |

压测停止前最后一次人工观测到 trading outbox pending=15、最老年龄 1.136s；account/risk outbox pending=0。进程因失败轮被主动停止后，剩余 trading outbox 不再具有容量判定意义。

## PostgreSQL

- 观测窗口平均 TPS：478.78（`xact_commit + xact_rollback` 增量 / 347.79s，包含本轮 provider 运行和事后查询，不代表纯业务 TPS）。
- WAL 增量：298.03 MiB；`wal_buffers_full` 增量 0。
- deadlock：0；conflict：0；临时文件增量 0。
- provider 日志未发现 deadlock、lock timeout 或 Hikari connection timeout。
- trading-entry Hikari：pending 峰值 43，connection acquire max 1180ms；其余 provider pending 峰值 0，acquire max 为 19ms 至 153ms。
- 本轮连续 PostgreSQL lock-wait 探针包含 SQL 语法错误，不能据此声明锁等待峰值为 0。
- `pg_stat_statements` 未安装，`log_min_duration_statement=-1`，因此本轮没有可信的慢 SQL 排名；需在下一轮前启用。

## Redis

- 本轮增量 keyspace 命中率：92.498%；miss 比例 7.502%。后者只能作为数据库回退比例上界代理，当前代码没有独立的 DB fallback counter。
- 业务命令中最差 p99：`EVAL` 274.431us（0.274ms），`EVALSHA` 268.287us。
- 单节点 `role=master`，`connected_slaves=0`，主从复制延迟不适用。

## Kafka

- broker JMX 滚动指标：Produce total-time p99 40.71ms，FetchConsumer total-time p99 649ms。该 JMX 直方图从 broker 启动后累计/滚动，未在本轮开始时重置。
- 停止时 consumer lag：matching sum=1/max partition=1；account user-command sum=2/max partition=1；account position-cache sum=0。
- LINEAR_PERPETUAL topic 共 680 partitions，单副本 ISR 均为 broker 1；空 ISR=0，异常 ISR=0，`UnderReplicatedPartitions=0`。本地单 broker 无法验证多副本 ISR 收缩/恢复。

## JVM 与连接池

allocation rate 为旁路采样期间 `jvm_gc_memory_allocated_bytes_total` 的平均增速。

| 服务（端口） | GC pause max | allocation rate | CPU max | direct memory max | Hikari pending max | acquire max |
|---|---:|---:|---:|---:|---:|---:|
| trading-entry (9084) | 41ms | 21.34 MiB/s | 21.07% | 4.418 MiB | 43 | 1180ms |
| matching (9085) | 16ms | 10.01 MiB/s | 13.18% | 0.362 MiB | 0 | 73ms |
| account (9086) | 18ms | 21.43 MiB/s | 19.04% | 2.689 MiB | 0 | 153ms |
| margin-ops (9088) | 32ms | 24.92 MiB/s | 19.37% | 2.612 MiB | 0 | 88ms |
| edge (9094) | 12ms | 25.15 MiB/s | 12.15% | 0.296 MiB | 0 | 63ms |
| market-maker (9096) | 9ms | 5.83 MiB/s | 10.89% | 0.278 MiB | 0 | 19ms |

## 资金守恒

`scripts/product-line-funds-reconcile.sh` 返回 `OK`，violations=0。原始摘要位于 `/tmp/linear-perpetual-failed-run-funds-reconcile-20260719.txt`。

| 项目 | USDT units |
|---|---:|
| 期初 | 0 |
| 充值/调整 | 404000000000000000 |
| 成交净额 | 0 |
| 手续费 | -225498630 |
| 资金费 | 0 |
| 强平费 | 0 |
| 保险基金余额/流水 | 0 / 0 |
| ADL covered/remaining/events | 0 / 0 / 0 |
| 期末应有 | 403999999774501370 |
| 期末实际（available + locked） | 403999999774501370 |
| 差额 | 0 |

失败停止时 available=403999591326023570、locked=408448477800、position margin=12343203600、deficit=0。由于 close 阶段未执行，仍有 1838 条 position row、gross position steps=3636；这是失败轮未清仓的状态，不是资金不守恒。

## 下一轮前置条件

1. **已完成（2026-07-19）**：`OrderAccountCommandResultConsumer`、`OrderPositionMaintenanceConsumer`、`AdlRiskPositionConsumer` 已统一为批量 `ConsumerRecord` 签名，并通过相关模块测试。
2. **已完成（2026-07-19）**：mark-price refresher 改为复用单个 Kafka producer；指数/标记价格 provider 最后启动，最终 20-symbol / 2000-user 开平仓复测通过。
3. 启用 `pg_stat_statements` 或慢 SQL 日志，并修复 lock-wait 旁路探针。
4. 在多副本 Kafka 和带 Redis replica 的环境复测 ISR 变化及复制延迟；本机单节点结果不能替代这两项。
