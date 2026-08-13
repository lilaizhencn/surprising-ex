# ADR-0001：按产品线部署统一 Aeron 复制状态机

## 状态

`ACCEPTED`，2026-08-13。

## 背景

当前交易链路分别使用 Kafka 顺序、用户分区本地 WAL、RocksDB 状态、Matching 本地 checkpoint/outbox、
Redis Risk State、Redis 强平候选队列和 PostgreSQL 强平事务维持不同阶段的恢复与幂等。最新代码虽然
增加了 User changelog replay 幂等和 Matching outbox assignment epoch fencing，但仍然存在多个独立
权威边界，故障恢复需要组合多个游标、租约、数据库状态和本地文件。

项目尚未上线，没有生产历史数据、在线迁移窗口和旧版本兼容负担，因此可以一次性建立最终权威边界，
不需要长期运行双写或影子架构。

## 决策

1. 六条产品线分别部署一套三节点 Aeron Cluster。
2. 每套 Cluster 内由一个统一确定性状态机共同持有 User、Order、Book、Risk、Liquidation 和 Export State。
3. Exchange Core 0.5.3 继续作为订单簿和撮合算法，通过 Adapter 嵌入状态机，不独立拥有权威 journal。
4. Aeron Cluster Log、Archive 和 Snapshot 是正常交易核心唯一权威恢复链。
5. Kafka 保留外部输入缓冲和对外事件分发，不作为核心资金状态恢复链。
6. PostgreSQL 保留查询、审计、投影和对账，不参与正常交易裁决。
7. Valkey保留限流和缓存，不保存权威 Risk 或强平状态。
8. Risk 重算、强平决策和强平订单执行进入 Aeron 状态机。
9. 新核心通过功能、资金和恢复门禁后立即删除旧 WAL、Redis Risk 和旧强平链路，删除早于性能压测。
10. 不做长期双写、运行时回退开关或影子集群；通过离线确定性重放、状态哈希和三节点故障测试验证。
11. 性能测试一次只运行一条产品线，且压测前必须证明该线功能正常、资金差异为零。
12. Cluster Log 协议 v1 使用固定小端二进制 envelope；幂等边界由 `commandId` 和
    `(source, sourceId, sourceSequence)` 高水位共同保证，不使用 Java serialization。
13. 核心命令显式携带 instrument version 对应的 base/quote/settle asset；状态机按产品线规则校验
    预占币种，不允许从 symbol 名称或外部缓存猜测资金资产。
14. `commandId` 重试返回传输状态 `DUPLICATE` 时必须同时返回原始 `commandStatus`，调用方不能把重复
    误判为成功或失败。

## 选择统一 Cluster 而不是多 Cluster

用户资金预占、订单创建、订单簿变化、成交结算、风险变化和强平可能在同一业务命令内连续发生。若将
User、Book 和 Risk 拆成独立 Cluster，就需要跨 Cluster Saga、补偿、超时和重复处理，重新引入本次迁移
希望消除的中间状态。每条产品线一套统一 Cluster 用单一提交顺序换取更简单的资金原子性。

产品线之间不共享订单簿和产品账户资金，按产品线拆 Cluster 可以提供清晰故障域，并允许独立压测、
扩容、恢复和发布。

## Kafka 保留的原因

Aeron Cluster 适合低延迟复制状态机，不替代所有数据分发场景。Kafka继续承担 WebSocket、K 线、审计、
报表、通知和数据仓库的可扩展消费，也为 instrument、标记价和生命周期输入提供外围缓冲。可靠 Exporter
使用 at-least-once 事件和稳定 eventId，不声称跨 Aeron/Kafka 的分布式 exactly-once。

## Snapshot 决策

第一版 Aeron Snapshot 保存可精确恢复 Exchange Core 价格时间优先级的开放订单状态，恢复后重建
Exchange Core 并校验规范化 Book State hash。不启用 Exchange Core 的本地磁盘 journal，避免第二权威日志。
运行中的 Member 另用 `StateHashReportQuery` 的 `MATCHING_ORDER_BOOKS` 子模块检查执行器一致性；该内部
hash 包含已成交历史字段，不能作为只保存开放订单的 Snapshot 恢复 hash。

如果生产数据证明重建时间不能满足 RTO，再通过新 ADR 评估将 Exchange Core 原生序列化数据嵌入
Aeron Snapshot。性能问题不能成为偷偷恢复本地权威 WAL 的理由。

## 后果

### 正面

- 单条产品线只有一个权威提交顺序。
- Leader 故障后不需要拼接 Kafka offset、Redis lease、数据库事务和本地 WAL。
- Risk 和强平不再通过多个中间件循环，延迟和故障状态减少。
- 六线共享同一运行、恢复、监控和压测框架。
- 项目未上线，删除旧实现的迁移成本最低。

### 代价

- Clustered Service 必须严格确定性，禁止热路径 I/O、wall clock 和不稳定并发。
- 统一状态可能增大 Snapshot，需要有界索引、批处理游标和实测调优。
- 每条产品线三节点，生产共 18 个 Member，部署和端口规划更严格。
- Exporter 与 Kafka 只能做到业务幂等的 at-least-once，需要所有消费者配合稳定 eventId。
- Exchange Core 的恢复重建和状态哈希必须新增可靠测试。

## 被否决的方案

### 全量使用 Kafka，不引入 Aeron

当前代码已经为 Kafka、本地 WAL、RocksDB、Redis 和 PostgreSQL 补充多层 fencing，但统一资金原子性和
快速故障恢复仍需要组合多个状态。该方案不能满足减少中间件权威边界的目标。

### Aeron 完全替换 Kafka

会把大量非核心消费者、长保留审计和数据分发责任压到 Cluster，扩大状态和运维复杂度，因此否决。

### User、Book、Risk 各自一套 Cluster

需要跨 Cluster 事务和补偿，资金正确性复杂度过高，因此否决。

### 保留旧 WAL 作为长期回退

会产生双权威和两套恢复测试矩阵。项目未上线，没有必要承担长期复杂度，因此否决。

### 六条产品线共用一套 Cluster

故障域、Snapshot、容量和热点互相影响，无法形成清晰的单产品线容量结论，因此否决。

## 验证方式

- 同一命令日志多次离线重放，状态哈希一致。
- 三节点 Leader/Follower 故障矩阵。
- Snapshot、Archive replay 和 Exchange Core book hash。
- Exporter 重复、重启和 Kafka 故障测试。
- 六条产品线逐线功能、恢复和资金守恒测试。
- 功能和资金门禁通过后的单产品线性能测试。

## 关联文档

- [Aeron 统一交易核心迁移实施方案](../aeron-unified-trading-core-migration-plan.md)
- [Aeron 统一交易核心术语表](../aeron-unified-trading-core-glossary.md)
- [产品线测试与资金守恒](../product-line-testing-and-funds-reconciliation.md)
