# realtime 已提交成交审计

后台 `/api/v1/admin/gateway/trading-orders/trades` 通过现有本地路由调用
`AdminOrderRequestService.trades` → `OrderService.adminTrades` → `TradeProjectionRepository.search`。
查询校验管理员和当前产品线，支持用户、合约、订单、`eventTime.asc` / `eventTime.desc`，每页最多 1000 笔。
用户与订单一起筛选时，必须属于同一成交侧；自成交仍只返回一笔撮合。
游标绑定全部筛选与排序，按成交时间、已提交日志位置和该命令内成交序号分页。

`CommittedTradeExporter.ExportBatch` 将同一已提交命令的 TRADE、EXECUTION 和 ORDER 帧交给
`CommittedOrderProjectionRepository`。临时配对只限当前有界 SQL 批次，并按命令分组，
从两侧订单的实际状态取得用户、订单、持仓方向与费率，避免后续改单的字段覆盖先前成交。
普通订单、条件单、成交、回执和现有订单可见水位在同一事务提交；随后才提交公开 Kafka 成交和导出检查点。
重复重放按真实 tradeId 及命令位置幂等校验，身份或内容冲突会回滚，不覆盖既有成交。

复用原有 `core_execution_projection`，不新增成交缓存或后台聚合服务。
`trade_id` 与公开 WebSocket/REST 成交相同，私有用户、订单字段只由后台接口返回。
`export_sequence` 在本表表示已提交命令的日志位置；`execution_index` 是该命令内从零开始的成交序号，
不依赖非成交帧的 ordinal。两侧均为做市账户的成交也会保留，报价订单过滤不影响成交历史。
行情帧不携带 instrument 配置版本，因此 `instrument_change_id` 可空，不伪造版本；本接口保留原始 ticks/steps，
不把跨合约原始乘积当作统一币值。成交与费用已经由 Core 提交，本投影不参与撮合、扣费或结算。

## 部署和历史重建

先应用 `deployment/migrations/20261020-execution-read-projection.sql`，新库入口已同步到 `init.sql`。
迁移新增真实 tradeId 和两侧持仓方向，并调整配置版本列的可空性。
当前旧导出实现未写入这张表；若环境中存在更早版本留下的非空执行表，新增非空列会明确失败。
此时先备份并从完整 Archive 在隔离库重建，确认后切换，不能给历史行填假 ID，也不能直接删业务数据。

首次启动若没有该产品的 `core_execution_projection_initialization`：

1. 主导出检查点为 0 时直接标记空前缀已就绪，后续消费已提交命令。
2. 主检查点大于 0 时，realtime 同一导出线程使用独立 `.execution-repair` 检查点，
   从 Archive 起点补齐到主检查点的有限终点。补齐只写成交，不写普通订单/回执/水位，不创建 Kafka producer。
3. 全部补齐后提交 SQL 就绪标记，删除这轮独立补齐检查点，再从原主检查点继续正常导出。
   中断保留独立检查点供重试；缺少完整 Archive 时明确失败，不能跳过旧成交。

补齐前或中途查询返回 503 `TRADE_READ_VIEW_INITIALIZING`，SQL 故障返回
`TRADE_READ_VIEW_UNAVAILABLE`，损坏记录返回 `TRADE_READ_VIEW_INVALID`，不回退到在线 Core。
查询读取已提交前缀，允许正常导出延迟。计划重建时先停对应产品 realtime，保留主检查点和完整 Archive，
同时清除该产品的成交投影与初始化标记；不要修改主检查点或重新发单。未完成的独立补齐检查点也需与其 SQL 进度一起处理。

本轮仅补齐后台成交列表。`/api/v1/admin/trading/metrics` 的完整统计数据源仍需后续实现；
不能把活动订阅中的部分持仓或已过滤报价订单当作全平台统计。
实测与清理记录见 `docs/validation/execution-realtime-queries-20261020.json`。
