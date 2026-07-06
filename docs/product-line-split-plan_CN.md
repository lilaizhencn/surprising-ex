# 产品线拆分与交割/期权落地方案

## 目标

把现货、U 本位永续、币本位永续拆成独立产品线运行边界，后续新增交割合约和期权时继续沿用同一套边界。公共部分只保留无业务偏向的模型、撮合内核、事件命名、监控和测试工具。

## 产品线

| 产品线 | 账户类型 | 撮合 topic 前缀 | 保证金 | 资金费 | 到期交割 |
| --- | --- | --- | --- | --- | --- |
| 现货 | `SPOT` | `surprising.spot` | 否 | 否 | 否 |
| U 本位永续 | `USDT_PERPETUAL` | `surprising.linear-perp` | 是 | 是 | 否 |
| 币本位永续 | `COIN_PERPETUAL` | `surprising.inverse-perp` | 是 | 是 | 否 |
| U 本位交割 | `USDT_DELIVERY` | `surprising.linear-delivery` | 是 | 否 | 是 |
| 币本位交割 | `COIN_DELIVERY` | `surprising.inverse-delivery` | 是 | 否 | 是 |
| 欧式期权 | `OPTION` | `surprising.option` | 卖方/组合保证金 | 否 | 是 |

## 运行边界

撮合算法代码可以共享，但运行实例、Kafka topic、consumer group、订单簿内存、恢复流程必须按产品线拆开。

```text
spot-order-provider -> spot-matching-provider -> spot-account-settlement
linear-perp-order-provider -> linear-perp-matching-provider -> linear-perp-account-settlement
inverse-perp-order-provider -> inverse-perp-matching-provider -> inverse-perp-account-settlement
linear-delivery-order-provider -> linear-delivery-matching-provider -> delivery-settlement
inverse-delivery-order-provider -> inverse-delivery-matching-provider -> delivery-settlement
option-order-provider -> option-matching-provider -> option-exercise-settlement
```

第一阶段先拆 topic、consumer group、账户/结算策略和配置；进程可以先复用同一个 provider 二进制，通过 `product-line` 配置启动不同实例。第二阶段再把 provider 类/模块物理拆细。

## 公共共享

- `surprising-product-api`：产品线枚举、账户类型映射、topic 命名。
- instrument 模型：`InstrumentType`、`ContractType`、到期字段、期权行权字段。
- matching-core：订单簿和撮合算法，不直接读取保证金、资金费、强平逻辑。
- event model：订单、成交、盘口事件保持统一字段，但 topic 按产品线隔离。
- 测试工具：资金守恒、无负余额、重复成交幂等、订单簿恢复等断言复用。

## 交割合约

交割合约复用永续的下单、撮合、保证金、强平大部分链路，但没有资金费。到期流程：

1. 到期前进入只减仓窗口，order-provider 拒绝非 reduce-only 开仓。
2. 到期时 matching 停止对应 symbol，撤销未成交挂单。
3. settlement provider 取指数价时间窗口均价作为结算价。
4. account 根据结算价关闭持仓，结算已实现盈亏、手续费、释放保证金。
5. WebSocket 推送订单取消、仓位归零、余额变化和交割事件。
6. Admin 提供交割任务状态、重跑、结算价校准和异常报表。

## 期权

先实现现金结算欧式期权，不做提前行权。

- instrument 使用 `VANILLA_OPTION`，补充到期日、行权价、看涨/看跌、标的 symbol。
- 买方支付权利金，最大亏损为 premium。
- 卖方冻结保证金；第一阶段使用单腿公式，第二阶段升级组合保证金/希腊值。
- 到期自动行权：价内期权按 `max(S-K,0)` 或 `max(K-S,0)` 计算 payoff，价外归零。
- 结算事件独立输出到 `surprising.option.option.exercises.v1`。

## 执行顺序

1. 提取 `surprising-product-api`，让 instrument/account 先使用统一产品线定义。
2. 引入按产品线生成 topic 和 consumer group 的配置，不改变业务逻辑。
3. 将现货、U 本位、币本位匹配命令和成交 topic 拆开，并让 provider 可按 `product-line` 启动。
4. 拆分 account settlement 策略，分别验证现货资产守恒、U 本位保证金、币本位反向盈亏。
5. 加交割合约 instrument 字段、只减仓窗口和交割结算任务。
6. 加期权 instrument 字段、premium/保证金冻结和自动行权结算。
7. 同步用户 Web、Flutter、后台 Web 的产品线筛选、下单面板、持仓/订单/结算展示。
