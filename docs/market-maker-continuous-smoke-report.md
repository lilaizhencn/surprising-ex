# 做市商模拟交易压测报告

时间：2026-07-03T18:10:14Z

## 环境

- Git：9475697
- CPU：16 cores
- 内存：16.0 GiB
- Java：java version "25.0.1" 2025-10-21 LTS
- PostgreSQL/Kafka：本机 Docker 容器 `surprising-ex-postgres`、`surprising-ex-kafka`
- Provider：order、matching、account、websocket、gateway 真实进程
- 临时日志目录：`/tmp/surprising-mm-stress.qiFDht`

## Provider 拓扑

- Provider nodes：matching=1, account=1, websocket=1, order=1, gateway=1
- Kafka group members：surprising-matching-v1/order.commands=1, surprising-account-v1/match.trades=2
- Kafka client ids：matching=mm-stress-1783102051475266000-matching-1-0, account=mm-stress-1783102051475266000-account-1-0,mm-stress-1783102051475266000-account-1-1
- Matching owner：symbol=BTC-USDT order.commands.partition=14 clientId=mm-stress-1783102051475266000-matching-1-0
- Matching owner：symbol=ETH-USDT order.commands.partition=17 clientId=mm-stress-1783102051475266000-matching-1-0

## 故障场景

- Node failure scenario：none

## 压测参数

- 做市商账号数：1
- 做市商初始深度：每个 symbol、每侧 2 档，每档 50 steps
- 做市商刷新挂单：每个 symbol、每侧 1 档，每档 20 steps
- 做市商连续刷新轮数：2，轮间隔 1s
- 普通用户订单数：4
- 普通用户每单数量：2 steps
- 并发度：2
- Consumer 并发：matching 每节点 1，account 每节点 2
- Symbols：BTC-USDT, ETH-USDT

## 结果

- 初始做市商挂单：8
- 并发刷新挂单：8
- 普通用户 taker 订单：4
- 撮合成交笔数：4
- account 已结算普通用户成交：4
- 客户端并发提交耗时：6235.777 ms
- 客户端提交吞吐：0.64 orders/s
- matching event-time 吞吐：1.10 trades/s
- WebSocket：btc_depth=10 eth_depth=10 private_orders=1 private_positions=1

## 压测中发现的瓶颈和修复

- 64 并发下单最初会把 order-provider 的 Hikari 连接池打满，表现为 PostgreSQL 连接获取超时和 HTTP 500。根因是高频 ID 分配使用 `trading_sequences` 表计数器热点行，并且 order/matching outbox 发布在数据库事务内等待 Kafka ACK。
- 已改为 PostgreSQL native sequence 分配交易链路 ID；order/matching outbox 改为先租约 claim DB 行，再在事务外发送 Kafka。修复后同样 64 并发没有再出现连接池 500。
- 本地压测直接用脚本写 `price_mark_ticks`，不是启动完整 mark-price provider。高并发下 `docker exec psql` 写 mark 会出现数秒级空洞；脚本已改为单 SQL 批量写 BTC/ETH mark，并把 LIMIT 订单价格保护的 `limit-price-max-mark-age-ms` 配到 15 秒。生产环境仍应由 mark-price provider 保证秒级刷新，不能靠放宽窗口掩盖行情故障。
- 当前 account-provider 持仓更新路径会复用成交结算时已经锁定的旧持仓数量，避免每个成交侧额外一次 `SELECT ... FOR UPDATE` 和一次更新后持仓回查。
- 当前 account-provider match-trade listener 使用 Kafka batch delivery 和 `AckMode.BATCH` 降低 per-record offset commit 开销；批次内每条成交仍独立走事务和 `account_processed_trades(symbol, trade_id)` 幂等。
- 当前 account-provider 余额结算只在 `deficit_units` 发生变化时写 `account_deficits`，普通开仓手续费、未穿仓平仓和强平费封顶扣款会跳过无变化 deficit 更新，减少成交热路径 SQL 写放大。

## 延迟

单位：ms，字段顺序为 `count min p50 p95 p99 max`。

- order accepted -> matching result：4 164.901 252.345 363.628 363.628 363.628
- order accepted -> account processed trade：4 293.461 304.334 1138.327 1138.327 1138.327

## Account Prometheus 指标

- match-trade events：processed=4 duplicate=0 failed=0
- processed processing：avg=0.348033s max=0.480909s eventLagAvg=0.844138s eventLagMax=1.373559s
- duplicate processing：avg=n/a max=n/a eventLagAvg=n/a eventLagMax=n/a
- failed processing：avg=n/a max=n/a eventLagAvg=n/a eventLagMax=n/a

## Kafka Consumer Lag

- Kafka lag 采样日志：/tmp/surprising-mm-stress.qiFDht/kafka-lag.log
- surprising-account-v1 / surprising.perp.match.trades.v1：maxLag=0 lastLag=0 lastSample=2026-07-03T18:09:44Z

## PostgreSQL 指标

- PostgreSQL delta：commits=295 rollbacks=0 inserted=167 updated=237 deleted=1 fetched=7418 returned=8858 blocksRead=52 blocksHit=16607 hitRatio=99.69% tempFiles=0 tempBytes=0 deadlocks=0
- 表行数：orders=20 orderEvents=20 matchResults=20 matchTrades=4 processedTrades=4 accountLedgers=5 positions=6 openTradingOutbox=0 openAccountOutbox=0

## Provider Prometheus 摘要

- order：httpCount=20 httpAvg=0.156728s httpMax=0.490204s hikariActive=0 hikariPending=0 hikariMax=30 processCpu=0 systemCpu=0 heap=47.3MiB/4096.0MiB
- matching：httpCount=0 httpAvg=n/a httpMax=n/a hikariActive=0 hikariPending=0 hikariMax=20 processCpu=0 systemCpu=0 heap=92.16MiB/4096.0MiB
- account：httpCount=5 httpAvg=0.079569s httpMax=0.2459s hikariActive=0 hikariPending=0 hikariMax=20 processCpu=0.012714 systemCpu=0.362492 heap=84.0MiB/4096.0MiB
- websocket：httpCount=3 httpAvg=0.08192s httpMax=0.081976s hikariActive=n/a hikariPending=n/a hikariMax=n/a processCpu=0 systemCpu=0 heap=94.52MiB/4096.0MiB
- gateway：httpCount=0 httpAvg=n/a httpMax=n/a hikariActive=0 hikariPending=0 hikariMax=10 processCpu=0 systemCpu=0 heap=66.86MiB/4096.0MiB

## 裸撮合基准

`exchange-core` 裸撮合封装层性能用 `./scripts/matching-engine-benchmark.sh` 单独测量。这个 benchmark 不包含 HTTP、order 入库、outbox、Kafka、matching 结果落库、account 结算和 WebSocket fanout。

## 相关验证入口

- 本报告只覆盖做市商和普通用户 taker 的真实 order/matching/account/websocket/gateway 进程链路。
- 全 provider 链路、资金费、爆仓、保险基金和 ADL 结果见 `docs/integration-report.md` / `docs/integration-report_CN.md`。
- exchange-core 裸撮合封装层性能用 `./scripts/matching-engine-benchmark.sh` 单独测量，不应与本报告的端到端吞吐直接比较。

## 关键观察

- 客户端并发提交耗时包含 8 笔 maker 刷新挂单和 4 笔普通用户 IOC 吃单；吞吐按普通用户 taker 订单数计算。
- matching event-time 吞吐是完整服务链路中的撮合成交写入速度，不是 exchange-core 裸引擎基准。当前端到端主要受 order 入库/outbox、Kafka、本机 PostgreSQL 和 account 结算影响。
- account processed trade 延迟明显高于 matching result 延迟，说明账户结算和 position/ledger/margin 写库是本轮最大后置瓶颈。account-provider 暴露 `surprising.account.match_trade.processing`、`surprising.account.match_trade.event_lag` 和 `surprising.account.match_trade.events{outcome=...}`，后续压测可以直接按 processed/duplicate/failed 拆分定位。
- Provider Prometheus 摘要中的 HTTP、Hikari、CPU 和 heap 指标是压测进程运行期累计/采样值，用来辅助判断入口服务、数据库连接池或 JVM 是否先成为瓶颈；并发阶段 PostgreSQL delta 用来观察事务量、写放大、缓存命中和临时文件。
- WebSocket 私有事件只订阅了第一个普通用户账号，用于验证多节点网关路径下的私有 orders/positions 推送可达；公网生产压测需要额外增加多用户长连接 fanout。

## 一致性检查

- 普通用户订单全部 `FILLED`
- 成交全部被 account 消费，`account_processed_trades(symbol, trade_id)` 去重键生效
- 压测用户余额未出现负 available/locked
- 订单簿仍有双边深度
- WebSocket 收到 BTC/ETH depth 事件和普通用户私有 orders/positions 事件
- `trading_symbol_open_interest` 由账户结算维护，表约束保证 open=max(long, short)

## 结论

这次压测验证的是单机、单 Kafka broker、单 PostgreSQL、matching=1、account=1、websocket=1 的真实进程链路。matching 本身可以完成本报告中的并发撮合；端到端延迟还包含 order 入库、outbox、Kafka、account 结算和 WebSocket fanout。

后续要继续提高压力，应把 `TAKER_ORDER_COUNT` 和 `LOAD_CONCURRENCY` 逐级上调，并同时观察 order-provider HTTP 指标、Hikari pending/active、Kafka lag、matching event-time、account-provider settlement lag、PostgreSQL delta、CPU 和 IO。
