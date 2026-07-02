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
- 这个服务只能部署在内网。不要把做市商控制接口暴露给普通公网用户。
- HTTP `X-Trace-Id` 会被接收并透传到下游 Feign 调用。定时策略会生成 traceId，最后一轮的值会暴露在 `/strategies` 返回里。

## API

Provider 端口：`9096`

```bash
curl 'http://localhost:9096/api/v1/market-maker/strategies'
curl -X POST 'http://localhost:9096/api/v1/market-maker/strategies/btc-usdt-mm-a/pause'
curl -X POST 'http://localhost:9096/api/v1/market-maker/strategies/btc-usdt-mm-a/resume'
curl -X POST 'http://localhost:9096/api/v1/market-maker/run-once' \
  -H 'X-Trace-Id: trace-mm-manual-1' \
  -H 'Content-Type: application/json' \
  -d '{"strategyId":"btc-usdt-mm-a","symbol":"BTC-USDT"}'
```

通过 gateway 时走私有路由：

```bash
curl 'http://localhost:9094/api/v1/gateway/market-maker/strategies' -H 'X-User-Id: ops'
```

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
4. 围绕锚点生成多档 post-only 买卖报价。
5. 按库存偏移和库存上限调整报价数量和方向。
6. 撤掉过期或偏离目标的自有订单，只补齐缺失报价。
7. 把本轮 traceId 透传给 order-provider，后续订单事件、撮合事件、账户结算、风控事件和私有 WebSocket 推送都可以关联排查。

## 多节点部署

可以多节点部署同一份配置。租约 key 是 `strategyId + symbol`，同一个策略的同一个合约同一时间只会由一个节点报价。生产环境建议配置稳定的 `node-id`，方便排查日志和租约。

如果要跑多个做市商账号，可以使用同一个策略的多个 `account-ids`，也可以拆成多个策略。不要让多个策略同时控制同一个账号和合约，除非库存上限已经按合并风险设计。

## 构建和测试

```bash
mvn -pl :surprising-market-maker-provider -am test
mvn -pl :surprising-market-maker-provider -am spring-boot:run
```
