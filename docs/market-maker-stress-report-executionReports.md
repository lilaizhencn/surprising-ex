# 做市商模拟交易压测报告

时间：2026-07-05T11:05:53Z

## 环境

- Git：df0502d
- CPU：16 cores
- 内存：16.0 GiB
- Java：java version "25.0.1" 2025-10-21 LTS
- PostgreSQL/Kafka：本机 Docker 容器 `surprising-ex-postgres`、`surprising-ex-kafka`
- Provider：order、matching、account、websocket、gateway 真实进程
- 临时日志目录：`/tmp/surprising-mm-stress.4589nj`

## Provider 拓扑

- Provider nodes：matching=1, account=1, websocket=1, order=1, gateway=1
- Kafka group members：surprising-matching-v1/order.commands=1, surprising-account-v1/match.trades=2
- Kafka client ids：matching=mm-stress-1783249370477466000-matching-1-0, account=mm-stress-1783249370477466000-account-1-0,mm-stress-1783249370477466000-account-1-1
- Matching owner：symbol=BTC-USDT order.commands.partition=14 clientId=mm-stress-1783249370477466000-matching-1-0
- Matching owner：symbol=ETH-USDT order.commands.partition=17 clientId=mm-stress-1783249370477466000-matching-1-0

## 故障场景

- Node failure scenario：none

## 压测参数

- 做市商账号数：2
- 做市商初始深度：每个 symbol、每侧 10 档，每档 20 steps
- 做市商刷新挂单：每个 symbol、每侧 5 档，每档 10 steps
- 做市商连续刷新轮数：1，轮间隔 1s
- 做市商入口：gateway `/trading/batch`，batchSize=5，makerConcurrency=6
- 普通用户订单数：200
- 普通用户每单数量：1 steps
- 并发度：64
- WebSocket 私有 fanout 订阅用户数：3
- WebSocket 捕获窗口：600s
- Consumer 并发：matching 每节点 1，account 每节点 2
- Hikari：order 40/3000ms，matching 20/3000ms，account 30/3000ms，gateway 20/3000ms
- 等待窗口：takerFilled=300s，tradesWritten=300s，accountSettled=420s
- Symbols：BTC-USDT, ETH-USDT

## 结果

- 初始做市商挂单：80
- 并发刷新挂单：40
- 普通用户 taker 订单：200
- 撮合成交笔数：200
- account 已结算普通用户成交：200
- 客户端并发提交耗时：6965.789 ms
- 客户端提交吞吐：28.71 orders/s
- matching event-time 吞吐：10.25 trades/s
- WebSocket：btc_depth=160 eth_depth=160 private_orders=1 private_positions=1 private_executionReports=3 private_user_0_orders=1 private_user_0_positions=1 private_user_0_executionReports=3 private_user_1_orders=1 private_user_1_positions=1 private_user_1_executionReports=3 private_user_2_orders=1 private_user_2_positions=1 private_user_2_executionReports=3

## 压测中发现的瓶颈和修复

- 64 并发下单最初会把 order-provider 的 Hikari 连接池打满，表现为 PostgreSQL 连接获取超时和 HTTP 500。根因是高频 ID 分配使用 `trading_sequences` 表计数器热点行，并且 order/matching outbox 发布在数据库事务内等待 Kafka ACK。
- 已改为 PostgreSQL native sequence 分配交易链路 ID；order/matching outbox 改为先租约 claim DB 行，再在事务外发送 Kafka。修复后同样 64 并发没有再出现连接池 500。
- 本地压测直接用脚本写 `price_mark_ticks`，不是启动完整 mark-price provider。高并发下 `docker exec psql` 写 mark 会出现数秒级空洞；脚本已改为单 SQL 批量写 BTC/ETH mark，并把 LIMIT 订单价格保护的 `limit-price-max-mark-age-ms` 配到 15 秒。生产环境仍应由 mark-price provider 保证秒级刷新，不能靠放宽窗口掩盖行情故障。
- 当前 account-provider 持仓更新路径会复用成交结算时已经锁定的旧持仓数量，避免每个成交侧额外一次 `SELECT ... FOR UPDATE` 和一次更新后持仓回查。
- 当前 account-provider match-trade listener 使用 Kafka batch delivery 和 `AckMode.BATCH` 降低 per-record offset commit 开销；批次内每条成交仍独立走事务和 `account_processed_trades(symbol, trade_id)` 幂等。
- 当前 account-provider 余额结算只在 `deficit_units` 发生变化时写 `account_deficits`，普通开仓手续费、未穿仓平仓和强平费封顶扣款会跳过无变化 deficit 更新，减少成交热路径 SQL 写放大。
- 本轮把 maker 与 taker 都切到 gateway 后，压测暴露出线性 SELL 限价开空在缺 fresh mark 时可能只按委托价预占保证金；已改为只有可能开仓/翻仓且成交价改善会增加保证金需求时，才强制 fresh mark 并按保护价预占，否则作为业务拒单返回。
- 高一档压测把 maker 初始深度提升到 30 档、taker 并发提升到 96 时，逐笔并发 maker 挂单会再次打满 order-provider 连接池。脚本已改为 maker 经 gateway 批量下单并使用独立 maker 并发上限；普通 taker 仍按真实用户逐笔经 gateway 进入，所有 POST 继续使用 `clientOrderId` 幂等重试。
- L4 10000 用户压测首次运行暴露 fixture 入金瓶颈：逐用户调用 admin balance-adjustment HTTP 使准备阶段耗时数分钟，且这不是交易链路瓶颈。脚本已把初始 maker/taker 资金准备改为一次批量 SQL，同时写入 `account_balances`、`account_ledger_entries` 和 `account_admin_balance_adjustments`；用户下单、maker batch、taker 单笔、撮合、account 结算仍全部走真实 gateway/Kafka/PostgreSQL 链路。

## 延迟

单位：ms，字段顺序为 `count min p50 p95 p99 max`。

- order accepted -> matching result：200 830.647 12095.306 18028.051 18399.766 18418.211
- order accepted -> account processed trade：200 1606.064 14860.389 23244.121 23736.648 23859.574

## Account Prometheus 指标

- match-trade events：processed=200 duplicate=0 failed=0
- processed processing：avg=0.214322s max=0.386074s eventLagAvg=2.979174s eventLagMax=6.436838s
- duplicate processing：avg=n/a max=n/a eventLagAvg=n/a eventLagMax=n/a
- failed processing：avg=n/a max=n/a eventLagAvg=n/a eventLagMax=n/a

## Kafka Consumer Lag

- Kafka lag 采样日志：/tmp/surprising-mm-stress.4589nj/kafka-lag.log
- surprising-account-v1 / surprising.perp.match.trades.v1：maxLag=62 lastLag=0 lastSample=2026-07-05T11:05:30Z

## PostgreSQL 指标

- PostgreSQL delta：commits=3136 rollbacks=0 inserted=4236 updated=7771 deleted=2 fetched=304139 returned=837281 blocksRead=32 blocksHit=374366 hitRatio=99.99% tempFiles=0 tempBytes=0 deadlocks=0
- 表行数：orders=320 orderEvents=320 matchResults=320 matchTrades=200 processedTrades=200 accountLedgers=202 positions=204 openTradingOutbox=0 openAccountOutbox=0

## Provider Prometheus 摘要

- order：httpCount=224 httpAvg=0.745535s httpMax=3.483352s hikariActive=1 hikariPending=0 hikariMax=40 processCpu=0 systemCpu=0 heap=58.24MiB/4096.0MiB
- matching：httpCount=0 httpAvg=n/a httpMax=n/a hikariActive=1 hikariPending=0 hikariMax=20 processCpu=0 systemCpu=0 heap=89.36MiB/4096.0MiB
- account：httpCount=0 httpAvg=n/a httpMax=n/a hikariActive=0 hikariPending=0 hikariMax=30 processCpu=0.006226 systemCpu=0.561921 heap=40.32MiB/4096.0MiB
- websocket：httpCount=5 httpAvg=0.014765s httpMax=0.033845s hikariActive=n/a hikariPending=n/a hikariMax=n/a processCpu=0 systemCpu=0 heap=66.19MiB/4096.0MiB
- gateway：httpCount=0 httpAvg=n/a httpMax=n/a hikariActive=0 hikariPending=0 hikariMax=20 processCpu=0 systemCpu=0 heap=56.11MiB/4096.0MiB

## 裸撮合基准

`exchange-core` 裸撮合封装层性能用 `./scripts/matching-engine-benchmark.sh` 单独测量。这个 benchmark 不包含 HTTP、order 入库、outbox、Kafka、matching 结果落库、account 结算和 WebSocket fanout。

## 相关验证入口

- 本报告覆盖做市商挂单、做市商刷新挂单和普通用户 taker 全部经 gateway 入口进入的真实 order/matching/account/websocket 链路；maker 多档报价走 gateway 批量接口，taker 用户订单走 gateway 单笔接口。
- 全 provider 链路、资金费、爆仓、保险基金和 ADL 结果见 `docs/integration-report.md` / `docs/integration-report_CN.md`。
- exchange-core 裸撮合封装层性能用 `./scripts/matching-engine-benchmark.sh` 单独测量，不应与本报告的端到端吞吐直接比较。

## 关键观察

- 客户端并发提交耗时包含 40 笔 maker 刷新挂单和 200 笔普通用户 IOC 吃单；吞吐按普通用户 taker 订单数计算。
- matching event-time 吞吐是完整服务链路中的撮合成交写入速度，不是 exchange-core 裸引擎基准。当前端到端主要受 order 入库/outbox、Kafka、本机 PostgreSQL 和 account 结算影响。
- account processed trade 延迟明显高于 matching result 延迟，说明账户结算和 position/ledger/margin 写库是本轮最大后置瓶颈。account-provider 暴露 `surprising.account.match_trade.processing`、`surprising.account.match_trade.event_lag` 和 `surprising.account.match_trade.events{outcome=...}`，后续压测可以直接按 processed/duplicate/failed 拆分定位。
- Provider Prometheus 摘要中的 HTTP、Hikari、CPU 和 heap 指标是压测进程运行期累计/采样值，用来辅助判断入口服务、数据库连接池或 JVM 是否先成为瓶颈；并发阶段 PostgreSQL delta 用来观察事务量、写放大、缓存命中和临时文件。
- WebSocket 私有事件订阅前 3 个普通用户账号，并逐个断言 orders/positions/executionReports 只推送给对应认证用户；公网生产压测仍应继续把该数值扩大到真实长连接规模。

## 一致性检查

- 普通用户订单全部 `FILLED`
- 成交全部被 account 消费，`account_processed_trades(symbol, trade_id)` 去重键生效
- 压测用户余额未出现负 available/locked
- 产品账户、仓位保证金、预占释放、account/product deficit 均无异常
- trading/account outbox 均清空
- 订单簿仍有双边深度
- WebSocket 收到 BTC/ETH depth 事件，并且前 3 个普通用户都收到各自的私有 orders/positions/executionReports 事件
- `trading_symbol_open_interest` 由账户结算维护；压测会从 `account_positions` 重算 long/short/open interest 并对账

## 结论

这次压测验证的是单机、单 Kafka broker、单 PostgreSQL、matching=1、account=1、websocket=1 的真实进程链路。matching 本身可以完成本报告中的并发撮合；端到端延迟还包含 order 入库、outbox、Kafka、account 结算和 WebSocket fanout。

后续要继续提高压力，应把 `TAKER_ORDER_COUNT` 和 `LOAD_CONCURRENCY` 逐级上调，并同时观察 order-provider HTTP 指标、Hikari pending/active、Kafka lag、matching event-time、account-provider settlement lag、PostgreSQL delta、CPU 和 IO。
