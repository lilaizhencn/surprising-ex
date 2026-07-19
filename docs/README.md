# Surprising-EX 文档索引

本目录只保留需要长期维护的架构、数据、测试和部署说明。单次任务记录、提交清单、失败轮报告和
带日期的压测输出不进入仓库；压测脚本默认把报告写到 `/tmp`。

## 架构与数据

- [产品线架构（中文）](product-line-architecture_CN.md) /
  [Product-line architecture](product-line-architecture.md)
- [账户资金单写者与单用户串行](account-single-writer-command-lane_CN.md)
- [Database design](database.md)
- [Matching symbol 分片与容量](matching-symbol-sharding-and-capacity_CN.md)
- [永续合约业务与实现说明](perpetual-contract-tutorial_CN.md)

## Redis 读模型与索引

- [持仓 Redis 读模型（中文）](position-redis-cache_CN.md) /
  [Position Redis read model](position-redis-cache.md)
- [未完成订单 Redis 投影（中文）](open-order-redis-cache_CN.md) /
  [Open-order Redis projection](open-order-redis-cache.md)

PostgreSQL 始终是业务事实源。持仓读模型故障时用户查询返回 503；未完成订单投影故障时整页回退
PostgreSQL。触发单、ADL 和强平 candidate 的 Redis ZSET 只做候选过滤、排序、lease 和重试调度，
最终状态必须由 PostgreSQL 复核和条件更新。

## 测试

- [产品线测试与资金守恒（中文）](product-line-testing-and-funds-reconciliation_CN.md) /
  [Product-line testing and reconciliation](product-line-testing-and-funds-reconciliation.md)
- [本地 Homebrew 中间件](local-homebrew-infra.md)

真实运行报告应保存在 CI 制品、对象存储或临时目录。若结论需要长期保留，应把稳定参数、阈值或
操作规则整理进对应主题文档，不要提交原始报告。

## 部署

- [通用部署、Topic 和运行约束](deployment.md)
- [LINEAR_PERPETUAL AWS 生产基线](linear-perpetual-aws-production-deployment_CN.md)

生产永续首发使用产品线 Topic、32 分区、RF=3 和 `min.insync.replicas=2`。账户命令、DLT 和结果
Topic 固定 32 分区；已有 symbol-keyed Topic 不允许直接增加分区。精确 Topic 清单和校验命令以
[deployment.md](deployment.md) 为准。
