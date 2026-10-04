# surprising-maker

本机单节点脚本通过 `SURPRISING_MARKET_MAKER_KAFKA_BOOTSTRAP_SERVERS` 指向本轮 Kafka，并给每条产品线的合约快照消费者设置独立 consumer group。做市启动时从 gateway 拉取合约快照，运行中继续消费 `surprising.instrument.events.v1` 更新本地合约规则；只看到策略 `RUNNING` 不代表该增量消费者已连通，需同时检查消费者分区分配和连接告警。


内网做市商服务，用于控制盘口流动性、稳定报价中心、长期运行压测和完整交易链路验证。

这个模块不会绕过撮合。它和普通客户端一样调用订单入口 RPC，因此价格保护、保证金检查、post-only、订单 outbox、exchange-core 撮合、账户结算、风控、WebSocket、强平、资金费率和保险基金链路都会正常被验证。

## 模块

`surprising-maker` 是单模块 Spring Boot 服务，内部同时包含做市 REST/RPC 契约、定时报价策略和订单对账执行器。

## 运行安全

- `surprising.market-maker.engine.enabled` 默认是 `false`，显式开启前不会定时真实下单。已启用策略仍可以通过私有 `run-once` API 手动执行一轮报价。
- 所有报价单都是 `LIMIT + GTX + postOnly=true`。
- 默认只做被动报价，不主动发起 IOC 扫单；主动交易模式仅可在测试配置中显式开启。
- 每个策略完成一轮后立即继续，开放订单以本地快照为主，并按 `order-reconciliation-interval` 周期通过 REST 修复，避免每轮重复查询订单服务。
- 应用就绪后 `MarketMakerTask.start` 为每个配置策略启动独立报价虚拟线程，显式启用模拟交易时另启动一个吃单虚拟线程，分别调用 `MarketMakerService.runScheduledStrategy` / `runScheduledTrades` 连续执行，完成本轮立即开始下一轮，不设置 cycle-delay，也不等待其他策略完成。暂停、租约失败或本轮异常时退让 100ms，避免空转重试；关闭时中断本服务工作线程。报价和模拟吃单各自保留策略合约周期锁与租约，手动执行也受相应锁保护。模拟吃单每次读取真实盘口、持仓和标记价后经普通订单入口提交 IOC；不会把无成交撤单算作成交。实际频率仍取决于网络和交易核心耗时。
- 报价价差会根据 mark/order-book 锚点的 EWMA 绝对变动自动扩大，并受最大波动价差限制；没有复杂的策略版本传播或跨服务状态编排。
- `MarketMakerService.reconcile` 每轮按目标盘口撤销过期挂单并补齐报价，不设置订单操作总量上限；`placeBatch` 按接口每批 20 单、换单按当前挂单数的 10%（至少 1 单、最多 20 单）逐批撤销，确认后立即补齐再处理下一批，不会先撤完整个梯子。撤单结果不确定或补单拒绝时停止后续撤单，保留剩余挂单；风控主动缩减目标报价仍可撤掉不再允许的方向。状态不确定时保留原订单槽位，不重复补单。
- 策略每轮都会查询账户持仓。账户状态不可用时，本轮 fail closed，不继续报价。
- 当前净仓位达到 `maxInventorySteps` 后，会停止继续增加该方向风险的报价。
- 自己的做市订单通过 `clientOrderId` 前缀识别；过期、偏离目标价格或不再需要的订单会撤掉。
- 可选参考市场校准按轮次 fail closed：如果开启的 WebSocket/REST 外部 source 不可用且没有新鲜缓存，provider 会回退到本地 mark/order-book 报价模型，不使用过期外部深度继续报价。
- 这个服务只能部署在内网。不要把做市商控制接口暴露给普通公网用户。
- HTTP `X-Trace-Id` 会被接收并透传到下游 Feign 调用。定时策略会生成 traceId，最后一轮的值会暴露在 `/strategies` 返回里。

## 接口

Provider 端口：`9096`

普通内网 API：

```bash
curl 'http://localhost:9096/api/v1/market-maker/strategies'
curl -X POST 'http://localhost:9096/api/v1/market-maker/strategies/btc-usdt-mm-a/pause'
curl -X POST 'http://localhost:9096/api/v1/market-maker/strategies/btc-usdt-mm-a/resume'
curl -X POST 'http://localhost:9096/api/v1/market-maker/run-once' \
  -H 'X-Trace-Id: trace-mm-manual-1' \
  -H 'Content-Type: application/json' \
  -d '{"strategyId":"btc-usdt-mm-a","instrumentId":"604"}'
```

后台管理 API 使用独立 admin path，必须由 gateway 注入管理员身份头：

```bash
curl 'http://localhost:9096/api/v1/admin/market-maker/strategies' -H 'X-Admin-User-Id: 1001'
curl 'http://localhost:9096/api/v1/admin/market-maker/metrics?limit=200' -H 'X-Admin-User-Id: 1001'
curl 'http://localhost:9096/api/v1/admin/market-maker/strategy-logs?limit=200&sort=createdAt.desc' -H 'X-Admin-User-Id: 1001'
curl 'http://localhost:9096/api/v1/admin/market-maker/strategies/btc-usdt-mm-a/config' -H 'X-Admin-User-Id: 1001'
curl -X POST 'http://localhost:9096/api/v1/admin/market-maker/strategies/btc-usdt-mm-a/config' \
  -H 'X-Admin-User-Id: 1001' \
  -H 'Content-Type: application/json' \
  -d '{"baseQuantitySteps":25,"spreadTicks":40,"orderLevels":2,"reason":"quote tuning"}'
```

admin-web 通过统一后台 gateway 调用，gateway 会把 `market-maker` 后台路由转发到 `/api/v1/admin/market-maker`：

```bash
curl 'http://localhost:9094/api/v1/admin/gateway/market-maker/strategies' -H 'Authorization: Bearer <admin-token>'
curl 'http://localhost:9094/api/v1/admin/gateway/market-maker/metrics?limit=200' -H 'Authorization: Bearer <admin-token>'
curl 'http://localhost:9094/api/v1/admin/gateway/market-maker/strategy-logs?limit=200&sort=createdAt.desc' -H 'Authorization: Bearer <admin-token>'
```

`/metrics` 聚合策略/账号/Symbol 维度的库存占用、owned live 挂单、目标报价覆盖率、缺失报价、陈旧报价、偏离目标报价、盘口价差、TraceId 和异常列表。异常类型包含 `NO_LIVE_QUOTES`、`MISSING_DESIRED_QUOTES`、`STALE_QUOTES`、`OFF_TARGET_QUOTES`、`INVENTORY_LIMIT_REACHED`、`INSTRUMENT_NOT_TRADING` 和 `METRIC_COLLECTION_FAILED`。

做市 PnL 归因接口已从交易主库移除。订单、成交、手续费流水和持仓的关联属于财务运营查询；后续应由
独立财务运营模块消费领域事件，在独立数据库建立投影并提供归因、对账和运营报表。

`/strategy-logs` 读取 `market_maker_strategy_run_events`，记录 cycle 成功/失败、报价对账、IOC 交易提交/拒绝、跳过轮次、错误信息、计数器、节点 id 和 TraceId。接口支持 `limit/cursor/sort` 游标分页，排序白名单为 `createdAt.desc`、`createdAt.asc`，响应保留 `events/count` 并额外返回 `nextCursor`、`hasMore`、`sort`、`limit`。事件写入是 best-effort，不会阻断报价循环。

策略运行事件与参考行情样本分别由独立 Repository 访问各自物理表，`MarketMakerService` 负责业务聚合。
`MarketMakerLeaseRepository` 只负责 `market_maker_strategy_leases`，租约协调接口保留在 Service 层。

`/strategies/{strategyId}/config` 读取和写入 `market_maker_strategy_overrides`。只支持热更新 enabled、基础报价数量、保证金模式、价差、层间距、库存上限/偏斜阈值和报价层数；账号和交易对仍由部署配置管理。请求体里为 `null` 的字段会回退到 `application.yml` 基线配置，全部可编辑字段为 `null` 会清除覆盖。

## 配置

```yaml
surprising:
  clients:
    account:
      base-url: http://localhost:9086
    instrument:
      base-url: http://localhost:9080
    mark-price:
      base-url: http://localhost:9082
    matching:
      base-url: http://localhost:9081
    trading:
      base-url: http://localhost:9084
  market-maker:
    engine:
      enabled: false
      node-id: mm-node-a
    coordination:
      enabled: true
      lease-duration: 5s
    quoting:
      order-book-depth: 20
      order-levels: 3
      min-spread-ticks: 10
      level-spacing-ticks: 10
      refresh-threshold-ticks: 2
      max-open-orders-per-account-symbol: 30
      max-price-deviation-ppm: 5000
      order-reconciliation-interval: 500ms
      volatility-spread-multiplier-ppm: 500000
      max-volatility-spread-ticks: 100
    risk:
      max-inventory-steps: 10000
      max-inventory-skew-ppm: 800000
    trade:
      enabled: false
    reference-market:
      enabled: true
      websocket-enabled: true
      refresh-interval: 500ms
      max-age: 3s
      request-timeout: 2s
      reconnect-backoff: 5s
      depth-levels: 20
      quantity-scale-ppm: 1000000
      min-quantity-steps: 1
      max-quantity-steps: 1000
      sources:
        - name: BINANCE_USDM
          enabled: true
          instrument-id: "604"
          external-symbol: BTCUSDT
          url: https://fapi.binance.com/fapi/v1/depth?symbol={externalSymbol}&limit=20
          parser: BINANCE_DEPTH
          websocket-url: wss://fstream.binance.com/ws/{externalSymbolLower}@depth20@100ms
          websocket-parser: BINANCE_DEPTH_STREAM
        - name: OKX_SWAP
          enabled: true
          instrument-id: "604"
          external-symbol: BTC-USDT-SWAP
          url: https://www.okx.com/api/v5/market/books?instId={externalSymbol}&sz=20
          parser: OKX_BOOKS
          websocket-url: wss://ws.okx.com:8443/ws/v5/public
          websocket-subscribe-message: '{"op":"subscribe","args":[{"channel":"books","instId":"{externalSymbol}"}]}'
          websocket-parser: OKX_BOOKS_WS
        - name: BYBIT_LINEAR
          enabled: true
          instrument-id: "604"
          external-symbol: BTCUSDT
          url: https://api.bybit.com/v5/market/orderbook?category=linear&symbol={externalSymbol}&limit=50
          parser: BYBIT_ORDERBOOK
          websocket-url: wss://stream.bybit.com/v5/public/linear
          websocket-subscribe-message: '{"op":"subscribe","args":["orderbook.50.{externalSymbol}"]}'
          websocket-parser: BYBIT_ORDERBOOK_WS
    strategies:
      - strategy-id: btc-usdt-mm-a
        enabled: true
        account-ids: [900001, 900002]
        instrument-ids: ["604"]
        base-quantity-steps: 10
        margin-mode: CROSS
```

## 报价机制

每个 `strategyId + instrumentId` 的流程：

1. 如果开启多节点协调，先在 PostgreSQL 的 `market_maker_strategy_leases` 获取租约。
2. 读取合约配置、最新盘口、最新标记价格和做市账号当前持仓。
3. 优先使用 mark price 作为报价锚点；mark 不可用时回退盘口中价。
4. 根据锚点的绝对价格变动维护进程内 EWMA 波动值，用它扩大报价半价差；波动价差始终受配置上限和本地价格偏离限制。
5. 默认要求新鲜参考盘口。`RestReferenceMarketProvider` 先按行情源的 `quantity-scale-ppm` 将外部数量换算为基础币，再按 U 本位合约的 `contractMultiplierPpm` 换算为本地张数；BTC-USDT-SWAP 的 OKX 合约每张为 0.01 BTC，行情源使用 10000 ppm。`QuotePlanner` 按参考盘口相邻档距和每档张数报价，继续受本地价格偏离、post-only、数量和库存上限保护。
6. 没有新鲜参考盘口时，`MarketMakerService` 不新增报价，并撤销当前策略拥有的旧报价。仅显式关闭 `reference-market.enabled` 的独立测试场景才使用本地 spread 和 spacing 报价。
7. 按库存偏移和库存上限调整报价数量和方向。
8. 首次读取开放订单后在本地缓存；缓存更新由下单、批量撤单结果和短周期 REST 对账共同驱动。撤单结果不完整或请求超时时，保留未确认订单，不在同一轮重复补单。
9. 每轮先撤旧单再补缺口，不设置每轮订单操作总数上限；开放订单数、批量协议大小和资金风控仍分别校验。
10. 把本轮 traceId 透传给 trading provider，后续订单事件、撮合事件、账户结算、风控事件和私有 WebSocket 推送都可以关联排查。

参考市场校准在 `websocket-enabled=true` 时优先维护 WebSocket 本地订单簿；没有新鲜流式盘口时，回退 REST 深度快照。内置解析器覆盖 Binance depth stream、OKX books 和 Bybit V5 orderbook 消息，足够让压测时的本地盘口档位、档间距和每档数量跟随主流交易所深度；生产前仍需要补一轮启用流式 source 的长时间真实进程压测证据。

## 多节点部署

可以多节点部署同一份配置。租约 key 是 `strategyId + instrumentId`，同一个策略的同一个合约同一时间只会由一个节点报价。生产环境建议配置稳定的 `node-id`，方便排查日志和租约。

如果要跑多个做市商账号，可以使用同一个策略的多个 `account-ids`，也可以拆成多个策略。不要让多个策略同时控制同一个账号和合约，除非库存上限已经按合并风险设计。

## 构建和测试

```bash
mvn -pl :surprising-maker -am test
mvn -pl :surprising-maker -am spring-boot:run
```

## 模拟成交节奏

`MarketMakerService.maybeTrade` 由各策略独立模拟成交工作线程连续尝试 IOC 成交，与报价工作线程分离，不再提供 `trade.min-interval-ms`，也不维护上次成交时间作为限频状态。实际频率仍受铺单、查询、下单耗时与可成交盘口影响；库存、数量和价格校验继续生效。

## Hummingbot PMM 报价逻辑移植（2026-09-26）

策略由本模块的 `QuotePlanner` 与 `MarketMakerService` 执行。依据 Hummingbot Pure Market Making 的外部价格源、分层报价、库存数量偏移、报价刷新容差与预算约束实现 Java 版本；没有引入 Python 常驻服务，也不宣称实现了 PMM Dynamic 的 MACD/NATR 或跨所对冲。策略参考版本为 Hummingbot `9af100d6822da7d2d0291a906c730ef172284ee2`，入口：
- https://github.com/hummingbot/hummingbot/blob/9af100d6822da7d2d0291a906c730ef172284ee2/hummingbot/strategy/pure_market_making/pure_market_making.pyx
- https://hummingbot.org/strategies/v1-strategies/strategy-configs/order-refresh-tolerance/

业务步骤：
1. 读取合约、有效标记价、外部盘口、当前仓位及内部活跃订单；外部盘口中价作为 PMM 参考价，数量保留各层外部深度比例，库存偏移控制双边数量。
2. `half-spread-ppm` 给出相对价格的最小单边价差；`refresh-tolerance-ppm` 与 tick 容差共同控制保留订单，过期判断使用创建时间，部分成交不能无限延长旧单寿命。
3. 计算避免吃单的价格时，扣除本账户已知报价占据的盘口数量；若同一价位仍有其他用户数量，继续避让。不会让自身旧卖单把新买价永久锁住。
4. U 本位永续/交割按合约 OI 额度下限和最大持仓名义额度，对双边目标数量按比例缩放，预留 10% 价格变化空间；实际资金、杠杆、OI 及只减仓判断仍由核心逐笔校验。其他产品线不套用该线性公式。
5. 优先分批撤掉阻挡目标价的旧报价，补单前检查尚未撤掉的相反方向自有报价，确认后继续补齐；撤单失败或补单拒绝停止继续减薄盘口。

内部调用仍使用现有 `OrderRpcApi`、`AccountRpcApi`、`MarketDataRpcApi` 到内部 gateway，再由 Aeron 进入核心；所有成交来自正常撮合。测试吃单属于本地模拟负载，独立于被动 PMM 策略。未实现直接由 maker 发 Aeron 命令，不能把当前 HTTP 内部调用描述为已去掉网关。

JDK 27：maker 54 项测试通过，新增自身旧盘口锁价、同价其他用户订单保护、相对价差、缺失标记价拒绝、整层报价额度约束回归。当前本机仍有 Aeron 建连/查询超时，尚未通过每币对每秒至少 3 笔成交及持续双边 50 档验收。

测试吃单补充：本地模拟使用普通 `MARKET/IOC`（要求合约启用市价单），避免引用查询时旧价格的限价 IOC 在移动盘口中频繁零成交。事件区分 `TRADE_EXECUTED` 与 `TRADE_NO_FILL`；提交计数不等于成交计数，频率验收仍读取公共真实成交事件。54 项 maker 测试通过，覆盖市价请求类型与零成交不能计为执行。

批量模拟补充：启用测试吃单时，`trade.orders-per-batch` 默认 8（正常核心批量接口上限 20），按可吃盘口数量缩小批次；这是一次请求的订单数，不是每轮全部订单操作上限。每张订单均有独立客户端 ID，核心逐笔执行资金、仓位与风控校验。拒绝记录保留核心原因。成功批量回执缺少终态订单详情时，只记录 `TRADE_SUBMITTED`，不能据此推断零成交或拒单；实际成交频率仍以公共成交事件为准。

已有数据库需执行 [审计事件类型更新](../docs/validation/maker-trade-event-types-20260926.sql)，新库 `init.sql` 已包含相同约束。此 SQL 只扩大审计事件类型，保留原事件，不修改资金和订单。

2026-09-26 后续验证：JDK 27 下 maker 57 项测试通过；覆盖批量独立订单 ID、核心拒绝原因、成功但无订单详情不误判拒绝或零成交。核心保留原 Archive 恢复后，20 币对一次 30 秒公共 WS 样本各有 195～224 笔实际成交，但逐秒仍有空窗，末次双边深度约 33～50 档，尚未达到持续每秒 3 笔且双边始终 50 档。PMM 的被动报价本身不能保证真实用户或外部市场的成交频率。

### 报价拒绝与运行状态

`MarketMakerService.runStrategySymbol` 汇总本轮各账户的补单结果：只要存在拒单，
保留已经成功的订单，将策略标为 `DEGRADED`，记录带核心拒绝原因的 `CYCLE_FAILED`，
不记录该轮 `CYCLE_SUCCESS`。后续正常周期继续按原风控重试，补单成功后才恢复 `RUNNING`。
余额不足不能用 RPC 正常返回掩盖，也不会触发自动补资。
`MarketMakerService.placeMissingQuotes` 补成交缺档时，先扣除仍未撤销的同侧旧报价张数，
新旧报价合计不能超过该侧目标梯度的总张数；旧报价分批撤销后再补齐。
这避免旧报价临时占满 Core 的未平仓量额度时反复触发 `OPEN_INTEREST_LIMIT_EXCEEDED`，
Core 的实际下单风控仍是最终校验。

### 逐单模拟负载（2026-09-27）

`MarketMakerService.simulatedOrders` 在读取当前盘口和持仓后，逐单抽取方向及整数数量，
数量取两次均匀抽样的较小值，使小单更常见；标记价偏差和库存只影响买入概率，取消强制买卖交替。
买卖分别预留本批可吃数量和库存空间，即使只有单边成交也不能因为假设对冲成功而越过配置阈值。
超限库存只允许减小敞口。下单仍走普通 MARKET/IOC 接口和 Core 风控；不会伪造行情、保证每笔成交
或把模拟订单当成自然用户订单。删除了不再需要的 lastTradeSides 状态。
本地配置每侧 50 档，最多 100 个活跃订单。`QuotePlanner.levelQuantity` 在 `quantity-variation-ppm > 0` 时，将稳定分档数量与外部数量各混合一半；稳定种子由策略、买卖方向和档位构成，同一参考盘口不会每轮随机重挂。外延档位循环使用参考数量分布。默认 0 保持直接参考数量，其他部署未开启不改变行为。预算使用标记价与报价最高价的较大值，避免外侧报价超过名义额度。`MarketMakerService.reconcile` 先补成交缺档，再分批撤换；本地关闭 500ms 活跃订单缓存，每轮核对实际订单。
参考盘口因小于一张的外部数量而只剩少量可用档位时，`QuotePlanner.orderLevels` 仍按策略配置生成目标档数：已有档位沿用参考价格间距，外侧按配置间距扩展并循环使用已观察到的分档数量；不能把 50 档目标静默缩到 5～10 档。
`MarketMakerService.matchesQuote` 还按 `refresh-tolerance-ppm` 和 `quantity-refresh-tolerance-ppm` 判断已有报价是否需要撤补；后者默认 0 保持精确数量匹配，测试部署可配置有限偏差，不再因为挂单年龄强制撤单重挂；新鲜度由参考行情的 `max-age` 判断。已成交后数量差超出阈值的订单仍会补齐，Core 下单风控保持最终权威。
JDK 27 maker 61 项测试通过，新增固定随机种子的逐单变化、单侧流动性与部分成交库存边界验证。

`MarketMakerTask` 的报价由参考盘口、标记价格变更和已提交成交事件唤醒，同一策略最多一个待处理信号。
执行期间的新事件在本轮结束后立即处理；不同产品线和策略隔离。`engine.quote-watchdog-interval`
默认 1 秒，仅在无事件时检查行情过期、连接和租约恢复；不因检查本身撤换仍然匹配的订单。
原 `engine.quote-interval` 不再控制报价。模拟吃单仍使用 `trade-interval`，异常退让至少 100ms。
成交唤醒使用独立进程消费组、latest 起点和 read_committed，通知不承担持仓/资金状态恢复职责。
行情回调只发信号，不在 WebSocket/Kafka 消费线程中调用交易 RPC。

## 线性合约百万级订单与 1bp 验收（2026-10-01）

### 做市杠杆与已持仓账户扩容

`QuotePlanner.sizeLinearLiquidityBand` 按价格带内实际可用 tick 档位分散目标数量，
从近到远的权重为 N、N+1、…、2N-1（N 为该侧价格带内档数），逐档向上取整。
最优档不再承载全部目标；外围报价保留原数量，所有档位仍受单笔限额、库存和 Core 预算限制。
若 1bp 内只有两个 tick，就只能分到两档；不足的限额不会被重新集中到最优档。
`MarketMakerService.reconcile` 对带内订单使用已有批量改单接口，每笔改单保持 Core 原子替换，
外围小单继续分批撤补。批量命令不表示整批全成：逐项校验回执、索引和订单身份，
部分失败或回执不确定时结束本轮、清除缓存，下轮查询实际订单，不盲目重新下单。
价格跳过整个价格带时，分批更新仍可能暂时不足目标，必须通过持续容量采样评估，不能保证零缺口。

做市账户的报价档数、频率、库存预算可独立配置，但不免除 Core 的保证金和风险档位校验。
线上种子风险档位在 100 万 USDT 以上最多允许 50 倍，在 500 万以上最多允许 20 倍；
沿用默认 100 倍时，单纯加资或提高库存数量仍会收到 `LEVERAGE_EXCEEDS_RISK_BRACKET`。
扩容先确认资金、持仓、挂单与风险档位，再选择可覆盖目标规模的低杠杆；不能调高风险档位来掩盖拒单。

杠杆请求支持显式 `repriceCrossMargin: true`：只允许全仓降低杠杆，必须先暂停策略、
撤完该账户该合约的全仓挂单。`DerivativeAccountCommandProcessor` 在账户 Lane 内先校验
全部相关持仓、风险档位、结算/强平状态与可用资金，按入场价和标记价计算保证金取较大者，
只补足、不释放现有保证金，然后原子更新余额冻结、持仓保证金和杠杆。任何业务拒绝均发生在写入前。
调整成功后再恢复策略；操作超时应先查询杠杆与余额，禁止重复增加资金。

`UpdateLeverageCommand` 保留原无扩展字段的字节编码和阻止带敞口调整的语义，
只有显式附加版本 2 的命令开启重估；旧日志不会因升级被重新解释。
复用现有余额、持仓和杠杆快照字段，无需迁移日志或更改快照布局。
本机运维接口 `GET /internal/v1/operations/liquidity/leverage` 读取实际杠杆，
和写接口采用相同的本机密钥与产品线校验。管理后台提供对应的补保证金选项。

`market_maker_strategy_overrides.order_levels` 的 1～50 约束只限制单个做市策略每侧主动维护的报价档数，
不是交易所整个盘口的价格档数上限，也不限制用户订单可以占据多少个价格档位。
行情接口查询的前 50/100 档同样只是返回窗口；窗口以外的有效挂单仍保留在撮合簿内，
价格变化或前方订单成交后可以进入可见窗口并成交，不能因为超出展示深度而删除或取消。

目标为线性永续/交割合约，按订单名义价值检查 10 万、100 万、300 万、500 万 USDT 的买卖两侧，
滑点阈值 0.01% = 1bp = 100ppm。平均滑点和最差成交价滑点均相对下单前该方向最优价；
另列相对盘口中间价的成本，不能把买卖价差隐藏在滑点口径之外。手续费另计。
这是一项待真实成交验收的目标，不是现有系统已达到的承诺。

通用启动脚本 `scripts/start-product-line-providers.sh` 对 `LINEAR_PERPETUAL` 和 `LINEAR_DELIVERY`
默认配置每侧 50 档、每账号每合约 100 单，并设置 `MM_ORDER_RECONCILIATION_INTERVAL=0ms`，
每轮读取实际开放订单，避免原先 500ms 缓存延迟发现成交缺档。其他产品线保留原默认值，环境变量仍可覆盖。
增加档数不增加资金预算；查询频率增加也会增加 gateway/Core 查询负载，必须实测周期耗时和补单延迟。
本地 `deployment/local-perpetual/application-local.yml` 已经是 50 档和 0ms 对账，并保留 100ms 轮后间隔。
本次没有通过缩短该间隔、扩大账号额度或自动补资来宣称容量达标。

补单业务顺序仍在 `MarketMakerService`：查询真实订单 → 先补成交缺档 → 小批撤旧补新。
本次修复批量补单异常、成功响应缺少订单或缺少批量项时仍继续撤换的问题：中止本轮、标记失败、
清除该账号的订单查询缓存，下一轮先查实际订单。空/缺失查询响应不再被当成“没有挂单”。
此保护覆盖“订单已接受但响应丢失后查询可见”的窗口；仍需真实集群测试查询滞后和在途命令未提交的窗口。

只读验收脚本（输入为原生 `InstrumentResponse` 和 `OrderBookSnapshotResponse` JSON）：

```bash
python3 scripts/check-linear-liquidity.py \
  --instrument-json /tmp/instrument.json --book-json /tmp/book.json \
  --quote-scale-units 100000000 \
  --notionals 100000 1000000 3000000 5000000 --max-slippage-ppm 100
```

`quote-scale-units` 必须取该报价资产实际 `assets.scale_units`，示例为种子 USDT 的倍率。
订单数量由名义金额除以下单前最优价对应每 step 名义金额、向上取整；返回实际可成交金额、均价滑点、
最差滑点、1bp 价格带内数量，以及单笔合约数量/名义金额限制是否允许。
返回码 0 要求每个场景深度完整、最差滑点达标且合约单笔限制允许；1 表示至少一个场景不满足；
2 表示输入过期、格式错误、非线性产品、盘口交叉等，不能作为达标证据。
默认拒绝超过 3 秒或未来时间的盘口；仅对输入中的可见档位作判断，缺档不会外推成流动性。
这不预占盘口，也不验证用户余额、持仓、OI、并发抢单或核心成交延迟；真实 MARKET/IOC 成交、持仓、
成交后再次扫单和补单 P95/P99 仍需单独验证。交割合约种子默认没有启用 MARKET，也不能用盘口达标代替订单可用。

当前源码和配置检查结论：

- `QuotePlanner.applyQuoteBudget` 取最大持仓名义额度和用户 OI 保底额度的较小值，并预留约 10%。
  `init.sql` 的 USDT 倍率为 1e8、OI 保底为 25000000000000 units，即 25 万 USDT。
  零净仓位且配置未改时，单个账号单边整梯度预算不超过约 22.5 万 USDT；本地每币对一个报价账号，
  通用默认两个。1bp 以内的可成交深度只是整梯度的一部分，当前配置不足以承诺百万级订单。
- `QuotePlanner` 在线性合约上按 instrument maker 费率和 `MM_MAKER_FEE_RESERVE_PPM` 的较大值
  计算最低价差，再加入 `MM_MIN_NET_HALF_SPREAD_PPM`（通用线性启动默认 100ppm）的目标净边际。
  报价价格带无法覆盖成本时停止生成该批报价；负费率返佣不预支为收益。
  整侧所有挂单数量共同占用库存预算，不能每档各自通过却合计超限。此约束不替代 Core 保证金校验。
  策略尚无跨市场对冲，行情跳变、资金费和库存损益仍可能亏损，不能承诺账号必然盈利。
- maker 后台 metrics 新增 `liquidity`：1bp 内买卖可成交名义金额、盘口序号/时间、新鲜度和目标。
  `MM_LINEAR_LIQUIDITY_TARGET_NOTIONAL_UNITS` 默认关闭；通用线性脚本按种子 USDT 倍率设置
  500000000000000（500 万 U）。其他资产必须按实际倍率配置。深度不足或盘口过期会使策略
  qualityStatus 为 CRITICAL，即使工作线程仍 RUNNING。该目标同时用于带内报价数量规划、验收和告警，不增加账户余额或 OI。
- 线性 MARKET 在 `DeterministicExchangeCoreAdapter` 同一撮合线程内读取实时最优对手价，
  把实际成交价格限制到该价的 100ppm，且仍受原标记价风控边界约束；取整向最优价收紧。
  IOC 只成交价格带内数量、余量取消。原生引擎尚不支持普通 FOK，本次不把其拒绝行为当作全成能力；
  限价单沿用用户限价。
  这是撮合时最优价口径，不能防止从客户端发单到撮合期间的整体行情移动，也不含价差和手续费。
  深度不足时不会为追求全成而越过价格边界，不能把此保护称为百万订单全额成交保证。
- 2026-10-01 本机 `127.0.0.1:9094` 未监听，未启动或改变已有运行环境；尚无本轮真实百万级成交、
  连续扫单、补单延迟或 1bp 深度持续覆盖率证据。不得把单元测试或静态预算计算写成实盘验收通过。

验证：JDK 27 的报价规划、做市服务、工作线程、配置绑定和后台控制器测试；
`python3 -B -m unittest discover -s scripts/tests -p test_linear_liquidity.py` 覆盖 1bp 边界、
部分成交、均价达标但最差价超限、单笔限制、过期/错误盘口和资产倍率。

前一轮结果：maker 相关 62 项 Java 测试、盘口计算 6 项 Python 测试通过；同时相关 Core 36 项、
gateway 15 项测试通过。没有运行真实大额订单，没有把配置变更部署到已有进程；临时文本日志记录结果后清理。

本轮最终验证：maker 全部 77 项通过，Core 980 项通过/2 项跳过，gateway 627 项通过/42 项跳过。
新增覆盖手续费下限、整侧挂单库存预算、深度不足告警、买卖两侧 1bp 边界与 tick 取整、
部分成交的账户守恒及线性永续/交割快照恢复；另以合成资金和充足盘口覆盖两条线的 500 万 U 名义金额全成交、持仓和恢复。普通 FOK 不受支持，没有计为全成能力验收。

`MarketSlippageGuardBenchmark` 本机短时 JMH/JFR（1 fork，1×1s 预热、2×1s 测量），
单次为挂一笔 maker 单再由 market 单吃完：未启用价格带约 4,214,510 次/秒，100ppm 约
2,565,407 次/秒；吞吐下降约 39%，折合这段局部串行路径每次增加约 153ns。
JFR 可见新增 `L2MarketData` 和 `MatcherResult`/数组分配；保护不是零成本。
样本短且含 profiling 开销，不包括网关、账户结算、复制、大额多档成交或网络，不能据此承诺生产 TPS。

重现局部性能采样（JDK 27；先构建 benchmarks 包）：

```bash
java --enable-native-access=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \
  -jar surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar \
  '.*(FundsCommandIndexBenchmark|MarketSlippageGuardBenchmark).*' \
  -f 1 -wi 1 -i 2 -w 1s -r 1s -prof jfr:dir=/tmp/maker-funds-profile
```

本轮临时文本日志、JSON 和 JFR 记录结果后清理，原有测试报告和运行目录保留。
当前仍未提供真实做市资金预算、与资金匹配的合约/OI 额度以及多进程大额成交环境，
因此百万级立即全成、持续 1bp 深度覆盖和净盈利均未验收通过。


### 内部做市按 1bp 目标补充数量（2026-10-01）

已有数据库先执行 `deployment/migrations/20261001-maker-depth-levels.sql`，将策略覆盖表的档数约束
从 1–20 同步为 1–50；新库 `init.sql` 已同步。否则通过后台保存 50 档会触发数据库约束失败。

`QuotePlanner.sizeLinearLiquidityBand` 在生成价格后，按配置的名义金额目标和账号数计算每账号
所需合约张数，仅补充最优价起 `liquiditySlippagePpm` 范围内的档位。数量向上取整，沿用库存偏斜；
随后仍执行单笔数量/名义金额限制和整侧库存/OI 预算，最终由 Core 校验保证金及风险档位。
目标为 0 时关闭此功能；仅作用于线性永续与线性交割，不改现货、反向合约或期权。
库存接近上限时会缩减同方向流动性，不能把目标当成无条件保证，也不能把内部余额调整当成外部储备增加。

`MarketMakerService.quoteRequest` 为每次新挂单尝试分配进程内递增序号，加上进程 nonce。
明确拒绝后，同轮撤旧单再补单使用新订单标识；已构造的请求本身保持标识不变。
响应不确定时仍中止该轮并查询权威订单，禁止把超时当拒绝后盲目重复挂单。
此序号只标识请求，由各策略共享的原子计数器拥有，生命周期为 maker 进程，不属于交易恢复状态。

后台流动性指标及只读验收工具在查询边界排序原生盘口，兼容买盘升序输出；
工具仍拒绝重复价格、无效数量、交叉盘口和过期数据。报价轮后等待 100ms 不等于实测 10Hz，
HTTP 查询、批量撤挂与 Core 响应耗时需另外观测。扩大余额与限额必须走正式资金/配置命令，
记录调整前后余额和唯一业务引用；不能直接更新余额表。

本轮 JDK 27：maker 全部 82 项测试通过，直接 API/price-consumer 依赖 52 项通过；
Python 盘口验收计算 8 项通过；CoreMatchingStateTest 46 项通过（含两条线性产品的 500 万 U 合成资金成交、持仓、守恒与恢复）。新增覆盖带内数量目标、多账号分摊、单笔及库存限制、
同轮拒绝后订单标识唯一、原生升序买盘，以及重复价位拒绝。
线上扩容与持续深度验收单独记录，以上测试结果不代表线上 500 万 U 容量已经达标。

### 深度目标与实时挂量

启用线性合约 `linear-liquidity-target-notional-units` 后，`QuotePlanner.sizeLinearLiquidityBand`
在滑点范围内分配目标总量。每档基础权重仍从内到外递增，参考档位数量相对同侧均量的变化
会将权重调整到基础值的 0.8～1.2 倍，再归一化分配同侧总量。缺少对应参考档位时保持基础权重。
这样中价不变、参考数量变化也会生成新的目标挂量；相同盘口只更新时间不会产生新的挂量。
单档上限、库存预算及核心风控继续生效，外部单档巨量不能把全部资金集中到买一或卖一。
这不保证每条行情都会产生可见变化：数量步长和 `quantity-refresh-tolerance-ppm` 仍抑制无意义改单。

### 参考压力与库存定价

QuotePlanner 在有效参考盘口的前五档计算 (买量－卖量)/(买量＋卖量)，
以 reference-pressure-skew-ppm 限制价格倾斜；当前持仓/库存上限产生反方向的
inventory-price-skew-ppm 倾斜。两项默认 0，明确启用后仅作用于线性永续/交割。
最大幅度向上取整到价格步长，信号四舍五入到整数 tick；粗价格步长下可能仍不变。

合成压力为正时上移卖报价，为负时下移买报价；参考中间价移动仍带动整组报价。
保留相对原始参考价的手续费覆盖和最小净价差，禁止以压力信号突破成本底线。
最大偏离边界继续生效，深度目标按实际新报价重新分配。
相同参考深度和库存产生相同报价；不依赖计时器、不引入新的历史状态或快照格式。
