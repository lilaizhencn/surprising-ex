# 产品线架构与业务边界

[English](product-line-architecture.md) | 简体中文

## 当前状态

`surprising-ex` 已从单一永续链路推进到按产品线隔离运行的交易系统。当前共享同一套 Java 模块和数据库 schema，但通过 `ProductLine`、账户类型、Kafka topic、consumer group、provider 启动参数和 gateway 路由把业务逻辑隔离到不同产品线。

当前主要运行和测试覆盖的四条线：

| 产品线 | `ProductLine` | 账户类型 | 合约类型 | Topic 命名空间 | 当前状态 |
| --- | --- | --- | --- | --- | --- |
| 现货 | `SPOT` | `SPOT` | `SPOT` | `surprising.spot.*.v1` | 已接入下单、撮合、资产互换、冻结释放、产品账户流水 |
| U 本位永续 | `LINEAR_PERPETUAL` | `USDT_PERPETUAL` | `LINEAR_PERPETUAL` | `surprising.linear-perp.*.v1` | 已接入保证金、资金费、风控、强平、保险基金、ADL |
| U 本位交割 | `LINEAR_DELIVERY` | `USDT_DELIVERY` | `LINEAR_DELIVERY` | `surprising.linear-delivery.*.v1` | 已接入交割 instrument、独立撮合链路、现金交割事件和持仓归零 |
| 欧式期权 | `OPTION` | `OPTION` | `VANILLA_OPTION` | `surprising.option.*.v1` | 已接入期权 instrument、权利金/保证金账户链路、欧式自动行权事件和持仓归零 |

`INVERSE_PERPETUAL` 和 `INVERSE_DELIVERY` 的枚举、账户类型和 topic 映射已经保留，当前 smoke 主覆盖集中在 `SPOT`、`LINEAR_PERPETUAL`、`LINEAR_DELIVERY`、`OPTION` 四条线。

## 设计原则

- 共享的是通用能力：产品线枚举、instrument 元数据、long 定点数、撮合封装、事件模型、gateway 路由、测试工具。
- 隔离的是业务状态：订单簿、Kafka topic、consumer group、账户类型、结算策略、风险扫描、强平候选、生命周期事件。
- 现阶段不做大规模模块重构。provider 二进制仍复用，通过 `product-line` 和 `product-topics-enabled` 启动为不同产品线实例。
- 资金安全优先于抽象纯度。每条线必须独立验证余额、流水、持仓、保证金、强平费、资金费、交割/行权和保险基金入账。

## 运行边界

```text
SPOT:
  trading-entry/order-provider -> matching-provider -> account spot settlement

LINEAR_PERPETUAL:
  trading-entry/order-provider -> matching-provider -> account derivative settlement
  -> margin-ops/risk -> liquidation -> insurance -> adl
  -> funding

LINEAR_DELIVERY:
  trading-entry/order-provider -> matching-provider -> account derivative settlement
  -> margin-ops/risk -> liquidation -> insurance -> adl
  -> delivery settlement event

OPTION:
  trading-entry/order-provider -> matching-provider -> account derivative settlement
  -> margin-ops/risk -> liquidation -> insurance -> adl
  -> option exercise event
```

撮合 provider 仍使用同一个 `exchange-core` 封装，但每条产品线实例只消费当前产品线的 `order.commands`，只发布当前产品线的 `match.results`、`match.trades` 和 `orderbook.depth`。consumer 会校验实际 Kafka topic 与当前 `ProductLine` 一致，避免跨线误消费。

## 共享组件

- `surprising-product-api`：`ProductLine`、账户类型映射、合约类型映射、产品线 topic 和 consumer group 生成。
- `surprising-instrument`：统一管理 `SPOT`、`PERPETUAL`、`DELIVERY`、`OPTION` instrument，包含到期、交割、行权价、期权类型等字段。
- `surprising-trading`：订单入口、条件单、算法单、exchange-core 撮合封装和产品线 topic 路由。
- `surprising-account`：基础账户、产品账户、余额流水、产品流水、持仓、保证金、资金费、交割/行权账务。
- `surprising-margin-ops`：保证金产品共用的风控、强平、资金费、保险基金和 ADL 链路，按产品线和账户类型隔离。
- `surprising-edge`：客户端使用 `productLine` 路由 REST 和订阅实时推送；`surprising-gateway` 和 `surprising-websocket` 仍保留在 edge 模块下，可按生产容量独立部署。

## 交割合约执行模型

交割合约复用永续的下单、撮合、保证金、风控和强平链路，但没有资金费。区别在生命周期：

1. instrument 带 `expiry_time`、`delivery_time` 和 `settlement_method`。
2. 到期前进入只减仓窗口，禁止新增开仓。
3. 到期时停止对应 symbol 的撮合入口，撤销未成交挂单。
4. lifecycle 事件发布到 `surprising.linear-delivery.delivery.settlements.v1`。
5. account 按结算价把未平仓位现金结算为 `DELIVERY_SETTLEMENT` 流水，释放保证金，持仓归零。
6. gateway、WebSocket 和后台展示交割状态、结算价、交割流水和最终余额。

## 期权执行模型

当前期权路线是现金结算欧式期权，先不做提前行权：

1. instrument 使用 `VANILLA_OPTION`，包含标的、到期日、交割时间、行权价、看涨/看跌、行权风格。
2. 买方成交时支付权利金，最大亏损为权利金。
3. 卖方冻结保证金，后续可升级组合保证金和希腊值风控。
4. 到期自动行权，CALL payoff 为 `max(标的结算价 - 行权价, 0)`，PUT payoff 为 `max(行权价 - 标的结算价, 0)`。
5. lifecycle 事件发布到 `surprising.option.option.exercises.v1`。
6. account 写入 `OPTION_PREMIUM` 和 `OPTION_EXERCISE` 流水，释放保证金，持仓归零。

## Topic 模型

产品线 topic 统一由 `ProductTopicNames` 生成：

```text
surprising.<product-segment>.order.commands.v1
surprising.<product-segment>.order.events.v1
surprising.<product-segment>.trigger-order.events.v1
surprising.<product-segment>.match.results.v1
surprising.<product-segment>.match.trades.v1
surprising.<product-segment>.orderbook.depth.v1
surprising.<product-segment>.trade.events.v1
surprising.<product-segment>.candle.events.v1
surprising.<product-segment>.index.price.v1
surprising.<product-segment>.book.ticker.v1
surprising.<product-segment>.mark.price.v1
surprising.<product-segment>.account.position.events.v1
surprising.<product-segment>.account.liquidation-fee.events.v1
surprising.<product-segment>.account.user.commands.v1
surprising.<product-segment>.account.user.commands.dlt.v1
surprising.<product-segment>.account.command.results.v1
surprising.<product-segment>.risk.account.events.v1
surprising.<product-segment>.risk.position.events.v1
surprising.<product-segment>.liquidation.candidates.v1
surprising.<product-segment>.funding.rate.v1
surprising.<product-segment>.delivery.settlements.v1
surprising.<product-segment>.option.exercises.v1
```

不同产品线只创建适用的 Topic。永续生产的精确清单和 32 分区约束见
[部署文档](deployment.md)。

本地创建 topic：

```bash
DRY_RUN=true ./scripts/create-topics.sh
PRODUCT_TOPIC_LINES="spot linear-perp linear-delivery option" ./scripts/create-topics.sh
```

## 本地验证

每次只跑一条业务线，不需要同时启动四条撮合链路：

```bash
PRODUCT_LINES=LINEAR_PERPETUAL BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
PRODUCT_LINES=LINEAR_DELIVERY BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
PRODUCT_LINES=OPTION BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
PRODUCT_LINES=SPOT BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
```

脚本覆盖：

- 做市 provider 持续报价，普通用户通过 API 下单吃单。
- 普通开仓、主动平仓、撤单、控制类订单 API。
- 保证金产品的持仓形成、风险扫描、强平、强平费和保险基金入账。
- 永续资金费。
- 交割合约交割事件和持仓归零。
- 期权行权事件、权利金/行权流水和持仓归零。
- 现货资产互换、无衍生品持仓、冻结释放。
- 每条线结束后执行独立资金核对脚本。

资金核对详见 [产品线资金守恒与账账核对](product-line-testing-and-funds-reconciliation_CN.md)。

## 能力边界

- 币本位永续和币本位交割的产品线枚举已存在，但还需要补齐同等级真实流程 smoke。
- 期权当前是单腿欧式现金结算模型，组合保证金、希腊值、波动率曲面、风险限额和更复杂的做市报价仍是后续增强。
- 交割合约需要生产级到期调度、结算价多源 TWAP、人工复核、任务重跑和异常报表。
- 客户端需要保持 web、admin web、Flutter iOS/Android 的 `productLine` 参数、订单面板、持仓/流水/生命周期页面完全一致。
- 生产上线前还需要多节点、多 broker、长时间压测、故障演练、监控阈值和真实钱包/清结算边界复核。
