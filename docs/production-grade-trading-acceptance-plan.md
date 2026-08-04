# 生产级交易系统全链路验收方案

## 1. 目标与发布门槛

本方案验证四条产品线从真实用户 API 请求进入 gateway，到 Kafka、订单状态、撮合、账户结算、风控、读模型、WebSocket 推送和最终对账的完整链路：

```text
用户 API
  -> gateway
  -> 产品线 topic / order command
  -> order / matching
  -> match result / account command
  -> account 单用户单写者
  -> ledger、余额、冻结、持仓和风险读模型
  -> WebSocket 私有与公共事件
  -> 结算、恢复、资金守恒
```

生产发布必须同时满足以下条件：

- 四条产品线分别执行，测试过程中只启动当前产品线 provider；做市进程始终运行。
- 交易热路径不依赖同步数据库读写；数据库只承担事实落盘、恢复、异步投影和对账职责。
- 任意订单、成交、资金命令和结算事件都具备可重放的唯一键；重复投递不能重复扣款、重复加仓或重复结算。
- 订单、成交、余额、冻结、持仓、资金费、强平、交割和行权在测试结束后全部达到终态，Kafka 相关 consumer group 最终 lag 为 0。
- 每个用户、每种资产都满足资金守恒；任何一笔账差、流水断档、负冻结、重复结算或跨产品线串账都直接阻断发布。
- 节点重启、消费者重平衡、重复消息和数据库恢复后，最终状态与无故障基线一致。
- 所有 p99、吞吐、恢复时间和积压阈值均在报告中给出原始数据，不能只报告平均值或 HTTP 200。

本方案不把 wallet 服务纳入本地交易 smoke；测试资金由 account admin 产品账户调整接口注入，并在资金对账中作为 `adjustment_units` 单独核算。生产环境应另行执行充值、提现、链上确认和 wallet 故障演练。

## 2. 固定测试规则

每轮只使用一条产品线、一个独立 `RUN_ID`、一组新测试用户和一个独立证据目录。不要把不同产品线的用户、账户类型、instrument、topic 或 consumer group 混用。

做市用户使用脚本内置的 market-maker provider 持续报价；普通用户必须通过 gateway HTTP API 下单、撤单和改单，不能直接写数据库或伪造成交结果。每轮保留 provider 日志、API 请求/响应、Kafka lag、数据库对账结果、WebSocket 事件和故障时间线。

固定入口：

```bash
PRODUCT_LINES=SPOT \
BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true RESET_KAFKA=true \
KAFKA_RESET_SHARED_TOPICS=true \
KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true \
./scripts/product-line-api-flow-smoke.sh

PRODUCT_LINES=LINEAR_PERPETUAL \
BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true RESET_KAFKA=true \
KAFKA_RESET_SHARED_TOPICS=true \
KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true \
./scripts/product-line-api-flow-smoke.sh

PRODUCT_LINES=LINEAR_DELIVERY \
BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true RESET_KAFKA=true \
KAFKA_RESET_SHARED_TOPICS=true \
KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true \
./scripts/product-line-api-flow-smoke.sh

PRODUCT_LINES=OPTION \
BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true RESET_KAFKA=true \
KAFKA_RESET_SHARED_TOPICS=true \
KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true \
./scripts/product-line-api-flow-smoke.sh
```

上述命令中的 `RESET_KAFKA=true`、`CREATE_KAFKA_TOPICS=true` 和
`KAFKA_RESET_SHARED_TOPICS=true` 必须成组使用。否则旧产品 Topic 的 earliest 消息可能与本轮新的本地 WAL、数据库快照混合，测试会被恢复保护拒绝，不能把这种结果当作生产链路通过。

需要把 WebSocket 纳入同一轮 API 验收时，增加 `RUN_WEBSOCKET_SMOKE=true`；脚本会在做市报价和用户真实下单期间运行公共/私有订阅、鉴权、用户隔离、ping/pong、取消订阅和事件顺序检查，并把 JSON 证据保存在本轮临时目录。

脚本的默认账户和 instrument 映射为：

| 产品线 | 账户类型 | 典型 instrument |
| --- | --- | --- |
| `SPOT` | `SPOT` | `BTC-USDT-SPOT` |
| `LINEAR_PERPETUAL` | `USDT_PERPETUAL` | `BTC-USDT` |
| `LINEAR_DELIVERY` | `USDT_DELIVERY` | `BTC-USDT-260925` |
| `OPTION` | `OPTION` | `BTC-USDT-260925-59000-C` |

本地启动前必须确认 PostgreSQL 和 Kafka 已就绪；不要关闭已有中间件，也不要递归清理共享目录。脚本创建的测试 provider、临时 topic、临时数据库和日志只允许在确认 `RUN_ID` 后清理。

## 3. 功能验收矩阵

| 验收项 | 现货 | 永续 | 交割 | 期权 | 必须观察的结果 |
| --- | --- | --- | --- | --- | --- |
| 用户入金/调整与初始余额 | ✓ | ✓ | ✓ | ✓ | 调整流水、余额和可用额一致 |
| 做市报价与普通用户吃单 | ✓ | ✓ | ✓ | ✓ | 双边订单、成交和手续费完整 |
| 限价、市价、post-only、IOC/FOK | ✓ | ✓ | ✓ | ✓ | 不符合条件的订单不产生隐性成交 |
| 撤单、改单、重复 clientOrderId | ✓ | ✓ | ✓ | ✓ | 幂等返回与最终订单状态稳定 |
| 资产冻结与释放 | ✓ | ✓ | ✓ | ✓ | 未成交冻结、成交扣减、撤单解冻逐笔对平 |
| 成交与公共 WebSocket | ✓ | ✓ | ✓ | ✓ | depth/trades 与 REST 状态一致 |
| 私有订单、成交回报、持仓事件 | ✓ | ✓ | ✓ | ✓ | 只推送给所属用户，product line 不串线 |
| 持仓形成与主动平仓 | - | ✓ | ✓ | ✓ | 数量、方向、开平标志和保证金正确 |
| 风控快照与强平 | - | ✓ | ✓ | ✓ | candidate、强平单、保险基金和最终风险状态正确 |
| 永续资金费 | - | ✓ | - | - | 结算批次幂等，付款方与收款方守恒 |
| 交割到期结算 | - | - | ✓ | - | 到期后持仓归零、交割流水一次且状态终态 |
| 期权权利金 | - | - | - | ✓ | 买方扣款、卖方入账、手续费和成交引用一致 |
| 期权行权/失效 | - | - | - | ✓ | ITM/ATM/OTM、CALL/PUT 结果符合规则 |
| 保险基金与强平费 | - | ✓ | ✓ | - | 用户扣款与基金入账同额、同引用 |
| 最终资金独立核对 | ✓ | ✓ | ✓ | ✓ | ledger、余额、冻结、持仓和事件逐项对平 |

期权至少执行 `CALL/PUT × ITM/ATM/OTM` 六个到期场景，并增加买方、卖方、提前行权拒绝或允许、到期自动行权、到期失效和重复结算场景。交割至少执行到期前撤单、持仓到期、交割后查询、重复到期事件和节点重启后恢复场景。

## 4. WebSocket 全链路验收

`scripts/product-line-websocket-smoke.mjs` 使用 Node 22 内置 WebSocket，不依赖 npm 包，连接 edge provider 的 `ws://localhost:9094/ws/v1`。它验证：

- 主用户订阅公共 `depth` 和当前产品线的私有 `orders`、`executionReports`、衍生品 `positions`。
- 第二用户订阅同样私有频道但不能看到主用户的私有事件。
- 匿名用户订阅私有频道必须收到认证错误。
- ping/pong、订阅确认、产品线字段和退订确认正确。
- 主用户在真实交易期间最终观察到所有要求的公共/私有频道事件。

先执行无服务契约门禁：

```bash
bash -n scripts/tests/product-line-websocket-smoke-test.sh
scripts/tests/product-line-websocket-smoke-test.sh
```

真实运行时，将 `--user-id` 设置为当前 smoke 的普通用户，将 `--other-user-id` 设置为另一个用户，并保存 `--evidence` JSON。WebSocket 探针连接失败、事件超时、私有事件泄漏、产品线不匹配或匿名订阅未拒绝都属于失败。

## 5. 资金守恒与账账核对

每个产品线执行：

```bash
PRODUCT_LINES="SPOT" DB_NAME=surprising_product_line_smoke \
./scripts/product-line-funds-reconcile.sh
```

四条线合并核对时仍须保证用户和账户类型隔离：

```bash
PRODUCT_LINES="LINEAR_PERPETUAL LINEAR_DELIVERY OPTION SPOT" \
DB_NAME=surprising_product_line_smoke \
./scripts/product-line-funds-reconcile.sh
```

对每个用户、资产和产品线验证：

```text
期初
  + 调整/充值
  + 成交本金或 PnL
  + 手续费
  + 资金费
  + 强平费
  + 交割结算
  + 期权权利金与行权
  + 转账和保证金迁移
= 最终流水余额
= available + locked - deficit
```

同时验证 running balance 连续、余额版本单调、锁定金额非负、持仓保证金不超过锁定额、现货 base/quote 双边守恒、永续资金费和保险基金守恒、交割/行权 reference 可追溯、重复事件不会重复入账。任何 SQL 查询出现 `N/A`、超时、空结果或只验证汇总不验证用户明细，都不能判定通过。

## 6. 故障恢复和重启演练

每条产品线至少执行以下故障点，故障发生时记录 Kafka offset、outbox 状态、数据库版本、provider PID 和 UTC 时间：

1. order provider 在订单已接受但 command 尚未发布时重启。
2. matching provider 在撮合完成但 match result 尚未完全发布时重启。
3. account provider 在一侧或双侧 account command 已落盘、投影尚未完成时重启。
4. consumer 被 kill 后恢复，验证重复消息、重平衡和 outbox 重试。
5. delivery 到期结算或 option 行权批次中途重启，验证批次重放只产生一次结果。
6. WebSocket edge 重启，验证客户端重连、订阅恢复和私有事件不串用户。

每个故障场景必须满足：

- API 不返回两个互相矛盾的终态；同一个 `clientOrderId` 和 event key 可安全重试。
- 恢复后所有相关 topic 最终 lag 为 0，outbox 无未处理记录，订单/成交/账户/持仓终态与无故障基线一致。
- 资金对账逐用户通过，不能以“重启后最终余额看起来正确”替代流水和引用核对。
- 本地发布基线建议恢复时间不超过 300 秒；生产阈值必须按容量压测结果写入发布配置并在 CI 中强制执行。

## 7. 性能、稳定性和容量门槛

性能测试分三层执行：

1. 单 symbol 基线：验证 API、订单、撮合、账户和 WebSocket 的 p50/p95/p99 以及端到端延迟。
2. 多 symbol 分片：使用 `scripts/run-linear-perpetual-stress-matrix.sh`，覆盖 baseline、scale8、scale16，uniform、hot1、hot3 和不同目标 TPS。
3. 故障压测：在持续下单、撤单、成交和风险扫描期间重启一个 provider，验证吞吐退化、积压恢复和资金不变。

永续示例：

```bash
MATRIX_DRY_RUN=false \
MATRIX_PROFILES="baseline scale8 scale16" \
MATRIX_TRAFFIC_MODES="uniform hot1 hot3" \
MATRIX_TARGET_TPS_LIST="30 50 80 120" \
MATRIX_REPEATS=3 \
./scripts/run-linear-perpetual-stress-matrix.sh
```

每个报告至少包含 API 成功率、订单状态延迟、撮合延迟、account command 延迟、各 outbox owner 的 p50/p95/p99/max、consumer peak/final lag、错误率、GC/堆、CPU、内存、重启恢复时间和资金对账结果。没有明确容量目标的环境不能把“压测脚本执行完成”当作性能通过；目标 TPS、p99、最大 lag 和恢复时间必须由部署规格确定，并在报告首页声明。

## 8. 执行顺序、证据与 Go/No-Go

执行顺序固定为：

```text
静态边界门禁
  -> Maven 模块测试
  -> WebSocket 契约门禁
  -> 四条产品线逐条真实 API smoke
  -> 每条线独立资金核对
  -> 重启/重放/重平衡
  -> 永续容量与热点压测
  -> 汇总报告与人工复核
```

基础门禁：

```bash
./scripts/check-entry-layer-boundaries.sh
./scripts/check-persistence-boundaries.sh
./scripts/check-account-single-writer.sh
mvn -q -pl surprising-event-store,surprising-product-api -am test
```

证据目录必须至少包括：

- 每条线的 `RUN_ID`、配置快照、用户和 instrument 映射。
- API 请求/响应、订单/成交/账户/风险/结算状态快照。
- Kafka topic、partition、consumer group、offset 和 lag 时间线。
- provider 日志、重启时间线、恢复耗时和 WebSocket JSON 证据。
- `product-line-funds-reconcile.sh` 原始输出与逐用户失败明细。
- 压测原始报告、资源指标和最终清理记录。

Go：所有上述门禁通过，四条线逐条功能和资金核对通过，故障恢复后状态一致，性能达到部署规格，且人工抽查至少一笔完整订单和一笔完整结算流水。No-Go：任一资金差异、topic 串线、私有事件泄漏、重复结算、未解释的 lag、重启后状态不一致或性能阈值缺少原始证据。
