# Surprising-EX Domain Context

## Canonical terms

- **Product Core**：一条产品线的唯一确定性状态机，包含账户、余额、冻结、持仓、活动订单、订单簿、风险、资金费、保险基金、强平、ADL、触发单和结算状态。
- **User State**：用户在 Product Core 内的可用余额、冻结余额、持仓、活动订单和未完成生命周期工作；它不是数据库查询结果。
- **Book State**：exchange-core 维护的价格时间优先订单簿；不存在第二本可执行订单簿。
- **Core Fact**：Product Core 裁决后产生的不可变事件事实，按 Aeron Cluster position 排序并写入 Cluster Log/Archive。
- **Online Query**：通过 Aeron command/query 从内存状态读取当前数据，不访问 PostgreSQL、Valkey 或 Kafka。
- **Audit Exporter**：从 Product Core 的 replicated outbox 读取 Core Fact 并发布到 Kafka 的独立进程；它不连接 PostgreSQL。
- **History Projector**：消费 Kafka 审计 topic 并幂等写入 PostgreSQL 的独立进程；它不是交易裁决来源。
- **Instrument State**：由 Instrument 服务通过 PostgreSQL 管理的产品配置和生命周期状态；交易所需版本通过 Aeron command 导入 Product Core，命令执行不回查数据库。
- **Risk Scan Control**：Product Core 内控制风险扫描启停、续跑间隔和单批工作上限的版本化策略；它不包含 Instrument 风险参数，也不由数据库配置覆盖。
- **Trading Provider**：统一承载普通订单和触发订单 HTTP/API、校验、幂等和 Aeron 命令提交的交易入口服务；触发订单不再通过独立进程或 HTTP 回调调用普通订单服务。
- **Trading Identity**：用户一次明确交易意图的稳定身份；相同用户意图的重试或进程重启仍指向同一订单，普通订单、条件单和算法父单属于不同身份域。
- **Runtime State**：Product Core owner 线程独占的交易热路径状态；允许使用 primitive/hash 索引和原地更新，但不能被异步回调或外围服务直接写入。
- **Snapshot State**：由 Runtime State 按确定性顺序生成的不可变业务状态；用于 Cluster 快照、恢复、状态 hash 和对账，不等同于在线查询投影。
- **Reservation**：订单或持仓对结算资产可用余额的权威冻结记录；冻结、消耗和释放必须与订单状态在同一 Product Core command 内裁决。

## Boundary

在线交易当前态的唯一权威是 Aeron Cluster 的确定性状态、Cluster Log、Archive 和快照。PostgreSQL 不参与同步交易裁决，但继续服务 Instrument 管理以及 Kafka 历史投影。审计链路固定为 Product Core -> Audit Exporter -> Kafka -> History Projector -> PostgreSQL。
