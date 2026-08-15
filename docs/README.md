# Surprising-EX 文档索引

本目录只保留需要长期维护的架构、数据、测试和部署说明。单次任务记录、提交清单、失败轮报告和
带日期的压测输出不进入仓库；压测脚本默认把报告写到 `/tmp`。
仓库文档统一使用中文；代码标识、协议字段、配置键、命令和外部产品名称保留原文。各模块只维护
标准入口 `README.md`，不再维护额外的语言版本。

## 架构与数据

- [Exchange Core 单一盘口与内存交易核心最终方案](exchange-core-single-book-execution-plan.md)
- [Aeron 统一交易核心迁移实施方案](aeron-unified-trading-core-migration-plan.md)
- [ADR-0001：按产品线部署统一 Aeron 复制状态机](adr/0001-aeron-unified-trading-core.md)
- [Aeron 统一交易核心术语表](aeron-unified-trading-core-glossary.md)
- [产品线架构](product-line-architecture.md)
- [账户资金单写者与单用户串行](account-single-writer-command-lane.md)
- [数据库设计](database.md)
- [撮合交易对分片与容量](matching-symbol-sharding-and-capacity.md)
- [永续合约业务与实现说明](perpetual-contract-tutorial.md)
- [内存与无锁热点路径](in-memory-acceleration.md)
- [费率配置与 JVM 快照](fee-schedule-jvm-snapshot.md)
- [永续 JVM 单写者迁移执行计划](linear-perpetual-jvm-migration-plan.md)
- [高并发与资金安全改造执行计划](high-concurrency-stability-execution-plan.md)

## Redis 读模型与索引

- [持仓 Redis 读模型](position-redis-cache.md)
- [未完成订单 Redis 投影](open-order-redis-cache.md)

用户分区 WAL、Owner Thread reducer 和可重放的本地状态是在线账户与订单事实源。撮合本地事实、
资金费结算 WAL 和产品线 Kafka 事件承担各自业务边界的恢复输入；PostgreSQL 只承担异步投影、查询、
审计、恢复基线和对账。持仓读模型故障时用户查询返回 503；未完成订单 Redis 投影故障时只能回退到
可重建的本地订单快照或返回不可用，不能把 PostgreSQL 查询重新引入交易裁决。触发单、ADL 和强平
candidate 的 Redis ZSET 只做候选过滤、排序、lease 和重试调度，最终资金命令仍由产品线账户 Owner
按版本和数量校验。

正式上线前仍必须完成四条产品线各自的资金对账、节点重启恢复、Kafka 重平衡和多节点 fencing 演练；
未通过验收不得把测试环境的单机结果当作生产可用性证明。

## 测试

- [产品线测试与资金守恒](product-line-testing-and-funds-reconciliation.md)
- [生产级全链路验收方案](production-grade-trading-acceptance-plan.md)
- [生产级性能测试方案与环境自适应规则](production-performance-test-plan.md)
- [生产级独立安全测试方案](production-security-test-plan.md)
- [本地 Homebrew 中间件](local-homebrew-infra.md)

真实运行报告应保存在 CI 制品、对象存储或临时目录。若结论需要长期保留，应把稳定参数、阈值或
操作规则整理进对应主题文档，不要提交原始报告。

## 部署

- [通用部署、Topic 和运行约束](deployment.md)
- [SPOT 现货产品线部署 Runbook](runbook-spot.md)
- [LINEAR_PERPETUAL U 本位永续产品线部署 Runbook](runbook-linear-perpetual.md)
- [LINEAR_DELIVERY U 本位交割产品线部署 Runbook](runbook-linear-delivery.md)
- [OPTION 欧式期权产品线部署 Runbook](runbook-option.md)
- [LINEAR_PERPETUAL AWS 生产基线](linear-perpetual-aws-production-deployment.md)

四份 Runbook 必须独立执行，一次只部署一条产品线。每条线均使用自己的 `PRODUCT_LINE`、Topic
命名空间、consumer group、client id、协调节点和 gateway 路由；账户命令、DLT 和结果 Topic 固定
32 分区，已有 symbol-keyed Topic 不允许直接增加分区。通用 Topic 清单和校验命令以
[deployment.md](deployment.md) 为准。
