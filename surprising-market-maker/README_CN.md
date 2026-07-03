# surprising-market-maker

[English](README.md)

内网做市商服务，用于控制盘口流动性、模拟深度、长期运行压测和完整交易链路验证。

这个模块不会绕过撮合。它和普通客户端一样调用订单入口 RPC，因此价格保护、保证金检查、post-only、订单 outbox、exchange-core 撮合、账户结算、风控、WebSocket、强平、资金费率和保险基金链路都会正常被验证。

## 模块

- `surprising-market-maker-api`：内网 RPC 契约。
- `surprising-market-maker-provider`：定时报价策略和订单对账执行器。

## 运行安全

- `surprising.market-maker.engine.enabled` 默认是 `false`，显式开启前不会定时真实下单。已启用策略仍可以通过私有 `run-once` API 手动执行一轮报价。
- 所有报价单都是 `LIMIT + GTX + postOnly=true`。
- 策略每轮都会查询账户持仓。账户状态不可用时，本轮 fail closed，不继续报价。
- 当前净仓位达到 `maxInventorySteps` 后，会停止继续增加该方向风险的报价。
- 自己的做市订单通过 `clientOrderId` 前缀识别；过期、偏离目标价格或不再需要的订单会撤掉。
- 可选参考市场校准按轮次 fail closed：如果开启的外部 source 不可用且没有新鲜缓存，provider 会回退到本地 mark/order-book 报价模型，不使用过期外部深度继续报价。
- 这个服务只能部署在内网。不要把做市商控制接口暴露给普通公网用户。
- HTTP `X-Trace-Id` 会被接收并透传到下游 Feign 调用。定时策略会生成 traceId，最后一轮的值会暴露在 `/strategies` 返回里。

## API

Provider 端口：`9096`

普通内网 API 保持兼容：

```bash
curl 'http://localhost:9096/api/v1/market-maker/strategies'
curl -X POST 'http://localhost:9096/api/v1/market-maker/strategies/btc-usdt-mm-a/pause'
curl -X POST 'http://localhost:9096/api/v1/market-maker/strategies/btc-usdt-mm-a/resume'
curl -X POST 'http://localhost:9096/api/v1/market-maker/run-once' \
  -H 'X-Trace-Id: trace-mm-manual-1' \
  -H 'Content-Type: application/json' \
  -d '{"strategyId":"btc-usdt-mm-a","symbol":"BTC-USDT"}'
```

后台管理 API 使用独立 admin path，必须由 gateway 注入管理员身份头：

```bash
curl 'http://localhost:9096/api/v1/admin/market-maker/strategies' -H 'X-Admin-User-Id: 1001'
curl 'http://localhost:9096/api/v1/admin/market-maker/metrics?limit=200' -H 'X-Admin-User-Id: 1001'
curl 'http://localhost:9096/api/v1/admin/market-maker/pnl-attribution?windowHours=24&limit=200' -H 'X-Admin-User-Id: 1001'
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
curl 'http://localhost:9094/api/v1/admin/gateway/market-maker/pnl-attribution?windowHours=24&limit=200' -H 'Authorization: Bearer <admin-token>'
curl 'http://localhost:9094/api/v1/admin/gateway/market-maker/strategy-logs?limit=200&sort=createdAt.desc' -H 'Authorization: Bearer <admin-token>'
```

`/metrics` 聚合策略/账号/Symbol 维度的库存占用、owned live 挂单、目标报价覆盖率、缺失报价、陈旧报价、偏离目标报价、盘口价差、TraceId 和异常列表。异常类型包含 `NO_LIVE_QUOTES`、`MISSING_DESIRED_QUOTES`、`STALE_QUOTES`、`OFF_TARGET_QUOTES`、`INVENTORY_LIMIT_REACHED`、`INSTRUMENT_NOT_TRADING` 和 `METRIC_COLLECTION_FAILED`。

`/pnl-attribution` 只返回部署配置中 strategy/account/symbol 范围内的只读归因行。它使用做市 `clientOrderId` 前缀归集自有订单，关联 `trading_match_trades` 统计 Maker/Taker 成交，关联 `account_ledger_entries` 中 `TRADE_FEE` 手续费流水计算净手续费，并带出当前 `account_positions` 的已实现盈亏和有符号库存快照。

`/strategy-logs` 读取 `market_maker_strategy_run_events`，记录 cycle 成功/失败、报价对账、IOC 交易提交/拒绝、跳过轮次、错误信息、计数器、节点 id 和 TraceId。接口支持 `limit/cursor/sort` 游标分页，排序白名单为 `createdAt.desc`、`createdAt.asc`，响应保留 `events/count` 并额外返回 `nextCursor`、`hasMore`、`sort`、`limit`。事件写入是 best-effort，不会阻断报价循环。

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
      base-url: http://localhost:9083
    matching:
      base-url: http://localhost:9085
    order:
      base-url: http://localhost:9084
  market-maker:
    engine:
      enabled: false
      cycle-delay-ms: 250
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
      stale-order-max-age: 30s
      max-price-deviation-ppm: 5000
    risk:
      max-inventory-steps: 10000
      max-inventory-skew-ppm: 800000
    reference-market:
      enabled: false
      refresh-interval: 500ms
      max-age: 3s
      request-timeout: 2s
      depth-levels: 20
      quantity-scale-ppm: 1000000
      min-quantity-steps: 1
      max-quantity-steps: 1000
      sources:
        - name: BINANCE_USDM
          enabled: true
          symbol: BTC-USDT
          external-symbol: BTCUSDT
          url: https://fapi.binance.com/fapi/v1/depth?symbol={symbol}&limit=20
          parser: BINANCE_DEPTH
        - name: OKX_SWAP
          enabled: true
          symbol: BTC-USDT
          external-symbol: BTC-USDT-SWAP
          url: https://www.okx.com/api/v5/market/books?instId={symbol}&sz=20
          parser: OKX_BOOKS
        - name: BYBIT_LINEAR
          enabled: true
          symbol: BTC-USDT
          external-symbol: BTCUSDT
          url: https://api.bybit.com/v5/market/orderbook?category=linear&symbol={symbol}&limit=50
          parser: BYBIT_ORDERBOOK
    strategies:
      - strategy-id: btc-usdt-mm-a
        enabled: true
        account-ids: [900001, 900002]
        symbols: [BTC-USDT]
        base-quantity-steps: 10
        margin-mode: CROSS
```

## 报价机制

每个 `strategyId + symbol` 的流程：

1. 如果开启多节点协调，先在 PostgreSQL 的 `market_maker_strategy_leases` 获取租约。
2. 读取合约配置、最新盘口、最新标记价格和做市账号当前持仓。
3. 优先使用 mark price 作为报价锚点；mark 不可用时回退盘口中价。
4. 如果 `reference-market.enabled=true`，抓取新鲜的 Binance/OKX/Bybit 风格盘口快照，把外部每档相对中间价的距离和该档数量映射成本地 ticks/steps，同时仍受本地价格偏离、post-only、数量和库存上限保护。
5. 没有新鲜参考盘口时，继续按配置的 spread 和 spacing 围绕锚点生成对称 post-only 报价。
6. 按库存偏移和库存上限调整报价数量和方向。
7. 撤掉过期或偏离目标的自有订单，只补齐缺失报价。
8. 把本轮 traceId 透传给 order-provider，后续订单事件、撮合事件、账户结算、风控事件和私有 WebSocket 推送都可以关联排查。

参考市场校准当前使用 REST 深度快照和短内存缓存，足够让压测时的本地盘口档位、档间距和每档数量跟随主流交易所快照。生产做市仍建议维护 WebSocket 本地订单簿，以获得更低延迟和更强容错。

## 多节点部署

可以多节点部署同一份配置。租约 key 是 `strategyId + symbol`，同一个策略的同一个合约同一时间只会由一个节点报价。生产环境建议配置稳定的 `node-id`，方便排查日志和租约。

如果要跑多个做市商账号，可以使用同一个策略的多个 `account-ids`，也可以拆成多个策略。不要让多个策略同时控制同一个账号和合约，除非库存上限已经按合并风险设计。

## 构建和测试

```bash
mvn -pl :surprising-market-maker-provider -am test
mvn -pl :surprising-market-maker-provider -am spring-boot:run
```
