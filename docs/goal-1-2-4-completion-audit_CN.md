# 缺口 1/2/4 完成审计

日期：2026-07-05

本文按原始目标逐项审计当前代码、真实链路报告和客户端/文章同步状态。结论只基于当前仓库证据，不以单元测试替代全链路验证。

## 1. 缺口 1：交易接口补齐

| 要求 | 当前状态 | 证据 |
| --- | --- | --- |
| 批量接口必须实现 | 已完成 | `OrderRpcApi` / `OrderController` 提供 `/batch`、`/batch-amend`、`/batch-cancel`；`TriggerOrderRpcApi` / `TriggerOrderController` 提供 trigger `/batch`、`/batch-cancel`。报告见 `docs/order-api-production-test-report.md`。 |
| 取消全部/单个 TP/SL | 已完成 | trigger-provider 提供 `/cancel`、`/batch-cancel`、`/cancel-open`；Cancel All After 到期也会联动撤 pending TP/SL。真实回归日志见 `/tmp/surprising-full-stack-real-config.EfyZWo`，报告归档在 `docs/order-api-production-test-report.md`。 |
| 一键平仓 | 已完成 | `POST /api/v1/gateway/trading/close-position` -> order-provider `/close-position`，锁当前持仓并提交 `reduceOnly=true` 的 `MARKET IOC` 平仓单。 |
| 一键测单 | 已完成 | `POST /api/v1/gateway/trading/test` -> order-provider `/test`，只做参数、规则、reduce-only、资金预估校验，不插入订单、不冻结资金、不发撮合命令。 |
| 对标 Binance/OKX 的其他合理方案 | 已完成本轮要求范围 | 普通改单/批量改单采用 cancel-replace；Cancel All After 采用 dead-man switch；高级订单放在 trigger/algo 层，不改 exchange-core；TP/SL 支持 `MARK_PRICE`、`INDEX_PRICE`、`LAST_PRICE` 和 `TRAILING_STOP`；算法单支持 TWAP/Iceberg parent-child。对标依据和接口表见 `docs/order-api-production-test-report.md`。 |
| 不修改 exchange-core | 已完成 | 新能力落在 order-provider、trigger-provider、websocket-provider、account-provider、gateway 和客户端层；`docs/order-api-production-test-report.md` 明确高级订单不进入 exchange-core，子单再走普通撮合链路。 |

## 2. 缺口 2：不改 exchange-core 的增强方案

| 方向 | 当前状态 | 证据 |
| --- | --- | --- |
| 统一执行回报 | 已完成 | websocket-provider 新增私有 `executionReports`，从 order event、match result、match trade 映射 `ORDER_EVENT` / `MATCH_RESULT` / `TRADE`，并区分 maker/taker `liquidityRole`。真实压测报告 `docs/market-maker-stress-report-executionReports.md`、`docs/market-maker-stress-report-executionReports-tier2.md` 覆盖私有 fanout。 |
| TP/SL 触发源增强 | 已完成 | trigger-provider 消费 mark/index/match-trade Kafka 流，不改 exchange-core；`TRAILING_STOP` 在 DB 维护激活价和最高/最低水位。Mark/Index、Last、Trailing Stop 真实链路回归分别记录在 `docs/order-api-production-test-report.md`。 |
| TWAP/Iceberg | 已完成 | order-provider 管理父单；TWAP/Iceberg 子单按普通订单进入 order/matching/account/WebSocket 链路。真实 full-stack smoke 日志 `/tmp/surprising-full-stack-real-config.DsxfGp`，报告 `docs/order-api-production-test-report.md`。 |
| Web/Flutter SDK 同步 | 已完成并推送 | `surprising-ex-web` 最新提交 `a4200f0`；`surprising-client` 最新提交 `aaa0fea`。Web/Flutter 均包含 batch/test/amend/close/cancel-open/cancel-all-after/algo/trailing/executionReports 相关模型或接口。 |
| tokdou 技术文章同步 | 已完成并推送 | `tokdou` 最新提交 `49d5547`；中英文文章补充 executionReports、TWAP/Iceberg、account fast path、metadata cache、真实链路和压测结论。 |

## 3. 缺口 4：单节点 jar 本机全链路压测

| 要求 | 当前状态 | 证据 |
| --- | --- | --- |
| 先定义多级压测方案 | 已完成 | `docs/order-api-production-test-report.md` 记录 L1/L2/L3/L4 分级；`scripts/market-maker-stress.sh` 支持参数化账户数、盘口深度、刷新轮数、taker 数、并发、provider 拓扑和报告文件。 |
| 按真实用户接口全链路测试 | 已完成 | 报告明确 maker 经 gateway `/trading/batch`，普通 taker 经 gateway 单笔 `/trading`，条件单/改单/Cancel All After/algo 均经 gateway 和真实 provider/Kafka/PostgreSQL。 |
| 做市账号持续运行 | 已完成本机样本 | `docs/market-maker-provider-continuous-report.md`、`docs/market-maker-provider-engine-report.md`、`docs/market-maker-reference-market-report.md`、`docs/market-maker-reference-market-sustained-report.md` 覆盖 run-once、scheduled engine、自主报价、参考行情 WebSocket 和 180 秒持续样本。 |
| L4 单节点压力样本 | 已完成 | `docs/market-maker-stress-report-tier4.md`：4 个 maker、50 档盘口、5 轮刷新、10000 笔 taker、128 并发；10000 笔成交和 account 结算完成，Kafka lag 最终 0，trading/account outbox 清空。 |
| 发现瓶颈并修复 | 已完成多轮 | 已修复 sequence 热点、事务内 Kafka ACK、account 持仓额外锁/回查、match-trade Kafka ack 开销、deficit 无变化写放大、maker 逐笔挂单连接池压力、fixture 入金非交易瓶颈、matching 热 symbol 观测、account guarded available-balance fast path、只读元数据 JVM cache。对应报告见 tier2/tier3/tier4、matching-concurrency、account-fastpath、account-instrument-cache。 |
| 评估 Redis/JVM cache/其他方案 | 已完成并按项目取舍 | 未引入 Redis 缓存资金事实源；采用 PostgreSQL 事务作为余额/持仓/保证金/deficit 的唯一事实源，只对只读元数据和强平费上下文使用有界 JVM cache。证据见 `docs/market-maker-stress-report-account-fastpath.md`、`docs/market-maker-stress-report-account-instrument-cache-smoke.md`。 |
| 修改源码后不重复打包未变 jar | 已落实到脚本口径 | full-stack 和 market-maker 压测报告多次记录 `BUILD_SERVICES=auto` 或 `BUILD_SERVICES=false`，脚本可跳过未变 provider jar 打包/重启。 |
| 资金完全正确 | 已完成当前样本验证 | L4、executionReports、account-fastpath、instrument-cache 报告均检查普通用户全部成交、account 去重消费、负 available/locked 为 0、负产品余额/逐仓保证金为 0、account/product deficit 异常为 0、outbox 清空、OI 对账通过。 |
| 测试报告 | 已完成 | 主报告 `docs/order-api-production-test-report.md`；性能/一致性报告 `docs/trading-consistency-and-performance-report_CN.md`；全链路清单 `docs/full-chain-test-plan_CN.md`；各级压测和 provider 报告位于 `docs/market-maker-*.md`。 |

## 4. 当前仍保留的非本轮完成项

这些不是原始缺口 1/2/4 的阻塞项，但报告中保留为后续生产化方向：

- 多节点、多 broker、长时间公网级压测仍需要独立执行。
- 大规模长连接 WebSocket fanout、断线清理、慢连接隔离仍需要更大样本。
- TWAP/Iceberg 已完成，POV/VWAP 等更复杂算法单仍是后续扩展。
- account-provider guarded fast path 和只读元数据缓存降低了部分处理耗时，但端到端 account p50/p95 未完全改善；后续仍要继续优化写库、锁竞争、批处理和 outbox 尾延迟。

## 5. 最近验证命令

```bash
mvn -q -pl :surprising-account-provider -am test
mvn -q -pl :surprising-account-provider -am -DskipTests package
bash -n scripts/market-maker-stress.sh
bash -n scripts/full-stack-real-config-smoke.sh
```

前端和文章仓库最近验证：

```bash
cd ../surprising-ex-web && corepack pnpm build
cd ../surprising-client && flutter analyze && flutter test
cd ../tokdou && npm run build
```

`tokdou` 的 `npm run typecheck` 当前失败在既有 address generator / wallet admin 类型问题，和本次 Surprising-EX 文章更新无关；`npm run build` 已通过。
