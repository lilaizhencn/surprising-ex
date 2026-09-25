# 本机六 JAR Aeron 单节点联调（2026-09-25）

## 范围与环境

- 工作树：`master`，HEAD `ad1afca09f22ea3df22e46ff3ad98c9b71da1aea`。开始前 `TradingCoreRuntime.java` 与 `CoreSnapshotManifest.java` 已有用户未提交修改；本轮未改动这两处源码。
- HotSpot Corretto JDK 27、Maven 3.9.16、macOS x86_64、16 GiB 内存；测试前与运行中磁盘可用空间约 528–535 GiB。
- 用本轮独立 PostgreSQL、Redis、Kafka 数据目录，以及 `LINEAR_PERPETUAL` 单节点 Core。未启动 wallet；六个业务 JVM 为 Core、gateway、price、realtime、derivatives-lifecycle、maker。
- 构建命令：`mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-tools,surprising-price/surprising-price-provider,surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,surprising-realtime/surprising-realtime-provider,surprising-gateway,surprising-maker -am package -DskipTests -q`，退出码 0。本轮验证以真实进程和 HTTP/WS 请求为主，未运行 Maven 测试类。
- 隔离运行目录（清理后仅作历史定位）：`/tmp/surprising-ex-local-qa-20260925`。JFR 关闭；本轮生成的日志、Archive、Kafka、数据库及脚本临时程序属于此目录。

## 实测结果

| 项目 | 结果 | 证据摘要 |
| --- | --- | --- |
| 六进程启动 | 部分通过 | 六个进程先后就绪、五个 HTTP `/actuator/health` 均为 `UP`；Core 后续出现 Aeron MediaDriver 约 80 秒 keepalive 超时并被 launchd 重启，启动器保存的 PID 随之失效。不能判定持续稳定运行。 |
| Kafka Streams/K 线启动 | 需手工准备 | 首次启动时 `surprising.linear-perp.candle.events.v1` 不存在，realtime HTTP 为 `UP`，但 Kafka Streams 因 `MissingSourceTopicException` 停止。手工建 32 分区 topic 并重启后流处理运行。 |
| 真实外部指数/标记价 | 未通过 | `BTC-USDT-SWAP` 最新指数成分中 Binance、Bybit 为 `HEALTHY`，OKX 为 `STALE`；合约要求三路有效数据，price 的 index/mark latest HTTP 返回 503，maker 起初为 `DEGRADED`。 |
| 模拟价下做市 | 部分通过 | 注入标记为 QA 的 100000 ticks Core 标记价与 Kafka 价格事件后，maker 进入 `RUNNING`，盘口出现 99990 买档、100010 卖档，各 40 steps、2 单。策略日志同时持续出现 `OPEN_INTEREST_LIMIT_EXCEEDED`；最终查询累计 888 次提交、884 次撤单、5512 次拒单。 |
| REST 下单、撮合、撤单、平仓 | 通过（模拟价） | 用户 1 买入 1 step 于 100010 ticks 成交，99900 ticks 挂单后撤销，再于 99990 ticks 只减仓卖出；第二组买入/平仓同样 `FILLED`。用户最终持仓 0、锁定 0；做市两账户持仓分别 −2、+2。 |
| 成交 Kafka / K 线 | 通过（模拟价） | `match.trades.v1` 读取到连续 sequence 1–4、每笔 1 step；gateway 的 1m K 线 REST 返回 200，WS `candles` 收到 PARTIAL 和 CLOSED 更新。 |
| WS 公共深度与盘口报价 | 通过（模拟价） | `/ws/v1` 的 `depth`、`bookTicker` 订阅成功，首轮连接分别收到 490 条事件。 |
| WS 逐笔成交 | 未通过 | 两次独立 WS 连接均成功订阅 `trades`；连接期间撮合及 Kafka/K 线确认成交，但两个连接的 `trades` 事件计数均为 0。第二次连接的 `candles` 计数为 4。realtime Router `dropped` 指标为 1；尚不能仅凭此指标定位逐笔缺失原因。 |
| WS 私有状态 | 部分通过 | JWT 认证成功，`positions`、`executionReports`、`accountState` 订阅和初始快照成功；首笔成交后 `positions` 收到用户 +1 仓位。其余私有频道未做逐事件断言。 |
| 风控、资金费 | 部分通过 | 风控持仓查询显示做市账户 ±2 step、`status=NORMAL`；资金费率 latest 返回 `PREDICTED`。到期资金费实际扣付/入账、强平、ADL、保险基金、止盈止损未覆盖。尚无结算时 `funding/settlements/latest` 返回 HTTP 500，服务日志为 `funding settlement not found`。 |
| 资金守恒 | 通过（本轮已执行交易） | 三个账户 USDT 余额总计 2,999,996,800,000,000 units；Core Treasury 手续费 2,800,000,000、clearing PnL 400,000,000；合计 3,000,000,000,000,000，与三个账户各入金 1,000,000,000,000,000 的期初/调整总额一致。用户最后锁定 0。做市账户仍有开放报价，余额中仍有对应锁定。 |

## 运行与测试边界

本轮仅验证 U 本位永续单节点及单个 `BTC-USDT-SWAP`，未验证现货、币本位永续、两种交割、期权，未验证多节点切主、真实前端浏览器页面或长期吞吐。使用 Node WebSocket 客户端模拟前端订阅；真实外部标记价不可用时采用隔离 Core/Kafka 模拟价，因此其后的交易、做市、K 线结论只对模拟价成立。用户与做市资金在本轮独立数据库和 Core 中创建，不涉及真实资产。

## 清理状态

已完成。先由 `linear-perpetual-single-node.sh down` 停止本轮六个业务 JVM，再停止本轮独立 Kafka、Redis、PostgreSQL；复核相关端口与本轮 Java 进程均不存在。随后校验目录路径、本轮 owner 标记和独立 PostgreSQL/Kafka 数据目录身份，删除 `/tmp/surprising-ex-local-qa-20260925`。本轮临时集群、Archive、行情 checkpoint、K 线状态、日志和临时测试程序均已清理；该路径仅作历史定位。工作区中原有两处未提交 Core 修改保持原状。

## 修复后持续运行复测（同日 23:06 CST）

上节记录的是已清理的首次隔离环境。本节是新的隔离环境 `/tmp/surprising-ex-local-live-20260925`，为方便人工查看页面，六服务、PostgreSQL、Redis、Kafka、前端和防休眠进程仍在本机运行；该路径是当前有效数据，不属于上节已清理目录。JDK 27、Maven 3.9.16，运行中磁盘可用约 531 GiB，业务日志约 57 MiB。前端地址 `http://127.0.0.1:5174/trade/usd-perpetual`。

### 修复与业务顺序

1. `init.sql` 的 OKX 指数源改用 `index-tickers` 和指数频道解析；`surprising-price-provider` 暴露已有 REST 兜底开关，本机启用该开关，BTC 指数和标记价均返回 200。外部行情和做市保持实时运行。
2. `OrderedCommitCoordinator.dispatchPartitionSettlements` 只检查批量撮合的实际结果数，再派发账户 Lane 结算。旧逻辑遍历固定容量数组的空槽触发 NPE，留下未完成撮合，导致 `CommittedTradeReplay` 导出中断；修复后成交 Kafka 和 K 线继续推进。
3. `CandlestickStreamConfiguration` 用当前产品线 Kafka 连接创建 32 分区 K 线事件 topic，使 Kafka Streams 正常运行；gateway `RealtimeWebSocketBridge` 按实际 EXECUTION/TRADE 帧解码，使公共逐笔和私有执行报告恢复推送。
4. 资金费尚未产生结算时，`FundingController` 返回 404。前端 API 保留 64 位订单号的十进制文本，接受网关实际的嵌套订单结果和成交终态；页面交易周期默认 1m，单根真实 K 线可显示。
5. 单节点脚本默认 `BTC-USDT-SWAP`，把本机 Redis、价格兜底和做市周期配置传到对应服务。只运行 `LINEAR_PERPETUAL`，不启动 wallet。

### 实测证据

| 项目 | 结果 |
| --- | --- |
| 六 JAR 和依赖 | Core、gateway、price、realtime、derivatives-lifecycle、maker 均为 `RUNNING`；五个 HTTP health 均为 200，Core 由脚本 Aeron probe 确认。Kafka Streams 进入 `RUNNING`。 |
| 真实价格和做市 | BTC 指数/标记价与资金费率最新接口均为 200；策略 `btc-usdt-mm-a` 为 `RUNNING`，盘口持续有买卖档。策略有少量撮合拒绝，风险校验未放宽。 |
| 交易与账户 | 通过前端 Vite 代理、真实登录 JWT 执行买入成交、只减仓卖出成交、GTX 挂单与撤单；用户最终签名持仓 0、冻结 0。超过 JavaScript 安全整数的订单号以字符串提交撤单，接口返回 `CANCELED`。 |
| Kafka、K 线与 WS | 成交导出修复后 PostgreSQL 有 2 根 1m K 线、共 4 笔成交；K 线 REST 200。一次交易 WS 连接收到 `trades=3`、`depth=42`、`bookTicker=42`、`candles=5`、`positions=4`、`executionReports=4`、`accountState=2`。 |
| 前端 | Chrome 页面截图位于本轮日志 `trade-page-final.png`，可见真实 1m K 线、指数价、标记价和做市盘口；前端 lint、build 与两个 API 测试文件 8 项通过。登录接口 200。 |
| 资金守恒 | Core 三账户余额（含冻结）分别为 1,000,000,361,745,000、999,994,267,476,000、999,998,325,222,000 units；Treasury 手续费 23,475,557,000、清算盈亏 −16,430,000,000。总和为 3,000,000,000,000,000，与三笔初始入金一致。 |

后端受影响的 `FundingServiceTest`、`CandlestickPropertiesTest`、`CommittedTradeExportIntegrationTest`、`CoreOrderedOrderBatchTest` 已在 HotSpot JDK 27 下通过，相关六 JAR 打包通过。UI 仅验证 U 本位永续 BTC；现货、币本位永续、两种交割、期权，以及实际资金费扣付、强平、ADL、保险基金和长期稳定性未在本轮覆盖。首次运行因 macOS 系统休眠发生 Aeron keepalive 超时；本次由本轮 `caffeinate` 维持运行，长期无休眠环境仍需另行验证。`Recent trade` 卡片只显示连接后的真实 WS 成交，刷新后等待下一笔，不再请求不存在的历史最新成交接口。

本节环境按用户要求保留在线以便查看。验证结束后应先执行 `scripts/linear-perpetual-single-node.sh down`，再停止本轮独立基础设施并删除仅属于本轮的临时数据；当前未执行清理。
