# 永续合约交易教程和系统实现说明

这份文档整理合约交易的基础概念，以及本项目里下单、撮合、持仓、指数价格、标记价格、风险扫描、爆仓、WebSocket 推送等模块的实现方式。

## 1. 现货和合约的区别

现货买入 BTC，是用 USDT 买到真实 BTC。成交后你持有 BTC，亏损最多是 BTC 价格归零。

合约买入 BTC-USDT 永续，不是买到真实 BTC，而是建立一个以 BTC 价格为标的的仓位。你持有的是一份风险敞口：

- 买入开多：价格上涨赚钱，价格下跌亏钱。
- 卖出开空：价格下跌赚钱，价格上涨亏钱。
- 杠杆会放大仓位名义价值，也会让爆仓价离开仓价更近。

合约里的“卖出开空”不是卖出你已经持有的 BTC，而是建立一个负方向仓位。系统里通常用 signed position 表达：

- `signedQuantitySteps > 0`：多仓。
- `signedQuantitySteps < 0`：空仓。
- `signedQuantitySteps = 0`：无仓位。

## 2. 买 1 手是什么意思

“1 手”不是固定等于 1 BTC。每个合约都有自己的合约面值、数量步长、价格 tick 和精度配置。

在本项目里，撮合和账户全部使用 long 定点数：

- `priceTicks`：价格 tick 数。
- `quantitySteps`：数量 step 数。
- `price_tick_units`：一个价格 tick 对应多少 quote asset 最小单位。
- `quantity_step_units`：一个数量 step 对应多少 base asset 最小单位。
- `notional_multiplier_units`：计算名义价值的乘数。
- `ppm`：百万分比，`1_000_000 ppm = 100%`。

这样可以和 exchange-core 的 long 模型保持一致，避免 `BigDecimal` 在高频撮合链路里的性能和精度风险。

## 3. 杠杆和初始保证金

10 倍杠杆不是“花 6000 USDT 买入，然后还要再额外准备 6000 USDT 保证金”。正确理解是：

```text
仓位名义价值 = 60000 USDT
杠杆 = 10x
初始保证金 = 60000 / 10 = 6000 USDT
```

这 6000 USDT 是为了开这个 60000 USDT 仓位而锁住的初始保证金。成交后，订单保证金会迁移成仓位保证金。

如果 BTC 从 60000 跌到 57000，你开了 1 BTC 多仓：

```text
未实现亏损 = (57000 - 60000) * 1 = -3000 USDT
权益大约 = 6000 - 3000 = 3000 USDT
```

继续下跌，权益会继续减少。当权益不足以覆盖维持保证金和强平费用缓冲时，就会触发爆仓。

## 4. 维持保证金率和保证金率

这两个名词很容易混淆。

### 4.1 维持保证金率

维持保证金率是产品风控参数，用来计算一个仓位最低必须保留多少保证金。

```text
维持保证金 = 仓位名义价值 * 维持保证金率
```

例如：

```text
仓位名义价值 = 60000 USDT
维持保证金率 = 0.5%
维持保证金 = 60000 * 0.5% = 300 USDT
```

主流交易所会按风险限额档位配置维持保证金率。仓位越大，风险档位越高，最大可用杠杆越低，维持保证金率越高。

本项目的初始化配置在根目录 `init.sql`：

- `instruments.initial_margin_rate_ppm`：基础初始保证金率。
- `instruments.maintenance_margin_rate_ppm`：基础维持保证金率。
- `instrument_risk_brackets`：风险限额档位，包含每档名义价值范围、最大杠杆、初始保证金率、维持保证金率。

当前 BTC-USDT/ETH-USDT 默认第一档：

```text
max_leverage_ppm = 100000000 -> 100x
initial_margin_rate_ppm = 10000 -> 1%
maintenance_margin_rate_ppm = 5000 -> 0.5%
```

### 4.2 保证金率

交易所界面里常见的“保证金率”通常是风险状态指标，不是产品配置。

本项目 risk-provider 使用：

```text
equityUnits = walletBalanceUnits + unrealizedPnlUnits
marginRatioPpm = maintenanceMarginUnits / equityUnits * 1_000_000
```

含义是：当前账户权益覆盖维持保证金的紧张程度。

- `marginRatioPpm < 800000`：正常。
- `marginRatioPpm >= 800000`：预警。
- `marginRatioPpm >= 1000000`：进入强平候选。

换成人话就是：

```text
保证金率 = 维持保证金 / 账户权益
保证金率达到 100% 时，账户权益已经不足以支撑最低维持保证金
```

所以你会爆仓，不是因为初始保证金还剩多少，而是因为标记价格变化导致未实现亏损扩大，最后账户权益低于维持保证金要求。

### 4.3 “保证金亏完”和“维持保证金”不是两条路径

“保证金亏完”是口语说法，严格来说系统不应该等保证金归零才强平。保证金是权益的来源之一，亏损会让权益下降；维持保证金是系统允许仓位继续存在的最低安全线。

逐仓可以这样理解：

```text
仓位权益 = 逐仓保证金 + 未实现盈亏
触发强平条件约等于：仓位权益 <= 维持保证金 + 强平费用缓冲
```

全仓可以这样理解：

```text
账户权益 = 合约账户余额 + 所有仓位未实现盈亏
触发强平条件约等于：账户权益 <= 所有仓位维持保证金之和 + 强平费用缓冲
```

因此，初始保证金 6000 USDT 不代表可以亏满 6000 USDT。假设维持保证金是 300 USDT，系统可能在亏到 5600 多 USDT 时就开始强平。剩下的维持保证金和费用缓冲，是为了让强平单有机会成交并降低穿仓概率。

## 5. 指数价格、最新成交价和标记价格

### 5.1 最新成交价

最新成交价是本交易所撮合出来的最后一笔成交价格。它适合展示市场最后成交，也用于实际成交和已实现盈亏。

但最新成交价可能被单个平台的短时大单、盘口薄、异常成交影响，所以不适合直接做爆仓触发。

### 5.2 指数价格

指数价格是多个外部主流交易所现货价格的加权平均。它的目标是提供一个更难被单一交易所操纵的公平参考价。

本项目的 index-price provider 从 instrument 配置读取指数源，支持：

- Binance
- OKX
- Bybit
- Coinbase
- Kraken

同时支持 USD/USDT 稳定币换算、源超时剔除、最小有效源数量和权重折扣。

### 5.3 标记价格

标记价格用于计算未实现盈亏和爆仓，不直接等于最新成交价。

主流方案通常是：

```text
标记价 = 指数价 + 基差移动平均
基差 = 合约中间价 - 指数价
合约中间价 = (买一价 + 卖一价) / 2
```

Binance 还会把资金费率、合约价、买一卖一、指数价放进保护公式里。OKX 也采用“指数价 + 基差移动平均”的思路。

本项目里：

- index-price provider 生成 `surprising.perp.index.price.v1`。
- mark-price provider 消费指数价、合约盘口最优价、资金费率，生成 `surprising.perp.mark.price.v1`。
- risk-provider 使用最新标记价计算未实现盈亏、维持保证金和强平状态。

## 6. 爆仓是怎么发生的

多仓亏损方向：

```text
开多后，标记价下跌 -> 未实现亏损扩大 -> 账户权益下降 -> 保证金率上升 -> 爆仓
```

空仓亏损方向：

```text
开空后，标记价上涨 -> 未实现亏损扩大 -> 账户权益下降 -> 保证金率上升 -> 爆仓
```

核心判断：

```text
equityUnits <= maintenanceMarginUnits
```

项目里用 ppm 表达：

```text
marginRatioPpm >= liquidationMarginRatioPpm
```

默认：

```text
liquidationMarginRatioPpm = 1000000
```

也就是保证金率达到 100%。

## 7. 风险检测频率和负责业务

当前项目由 `surprising-risk-provider` 负责检测用户持仓风险。

配置位置：

```yaml
surprising:
  risk:
    calculation:
      scan-delay-ms: 1000
      warning-margin-ratio-ppm: 800000
      liquidation-margin-ratio-ppm: 1000000
      max-mark-age: 10s
```

含义：

- 默认每 `1000ms` 扫描一次。
- 只使用 `10s` 内更新过的 mark price。
- 保证金率达到 `800000 ppm` 进入预警。
- 保证金率达到 `1000000 ppm` 生成爆仓候选。

风险扫描链路：

```text
account_positions
  + account_balances
  + account_deficits
  + price_mark_ticks
  + instruments
  + account_asset_scales
  -> risk_account_snapshots
  -> risk_position_snapshots
  -> risk_liquidation_candidates
  -> surprising.perp.liquidation.candidates.v1
```

多节点部署时，risk-provider 通过 `risk_scan_leases` 按 `userId + settleAsset` 抢扫描租约。默认租约 `15s`。同一个账户资产组同一时间只允许一个节点写风险快照和强平候选，节点挂掉后其他节点等租约过期接管。

当前实现注意点：

- risk-provider 使用持仓锁定的 `instrument_version` 计算风险，不会用最新合约版本重新解释旧仓位。
- risk-provider 会按当前仓位名义价值匹配 `instrument_risk_brackets` 中最高适用档位的维持保证金率。
- 如果某个合约版本没有配置风险档位，risk-provider 会回退使用 `instruments.maintenance_margin_rate_ppm`。
- liquidation sizing 也会使用风险档位地板做分阶段减仓，优先把高风险大仓位降到更低档位附近。

## 8. 强平执行由哪个业务做

risk-provider 只负责发现风险和生成候选，不直接下强平单。

强平由 `surprising-liquidation-provider` 执行：

```text
surprising.perp.liquidation.candidates.v1
  -> liquidation-provider 消费候选
  -> 抢占 candidate: NEW -> PROCESSING
  -> 复核最新 risk snapshot
  -> 先撤用户已有 reduce-only 平仓单
  -> 创建 reduce-only MARKET IOC 强平单
  -> 进入 order/matching/account 正常链路
```

强平单规则：

- 多仓爆仓：提交 `SELL` 平仓。
- 空仓爆仓：提交 `BUY` 平仓。
- `orderType = MARKET`
- `timeInForce = IOC`
- `reduceOnly = true`
- `postOnly = false`

强平不直接改持仓。它仍然走统一订单、撮合、成交、账户结算链路。这样资金变化、手续费、已实现盈亏、持仓变化都由 account-provider 统一处理。

## 9. reduce-only 是什么

`reduceOnly` 表示这个订单只能减少仓位，不能增加仓位或反向开仓。

例如你有 1 BTC 多仓：

- `SELL reduceOnly 0.5 BTC`：允许，减少多仓。
- `SELL reduceOnly 2 BTC`：最多只能减少已有仓位，不能额外开空。
- `BUY reduceOnly`：方向不对，不能减少多仓。

强平单必须是 reduce-only，避免强平过程中因为异常逻辑给用户开出新仓位。

## 10. 资金费率

永续合约没有交割日，所以需要资金费率让合约价格长期靠近指数价格。

一般逻辑：

- 多头和空头之间互相支付资金费。
- 平台原则上不赚资金费，只负责结算。
- 资金费率通常由 premium index、利率、资金费上限/下限等计算。
- 结算周期常见为 8 小时，也可以按产品配置。

本项目里：

- funding provider 计算并发布 `surprising.perp.funding.rate.v1`。
- mark-price provider 会使用 funding rate 参与标记价计算。
- funding settlement 会按持仓 notional 在账户侧扣收或发放。

资金费结算不能消耗用户未成交挂单的 order margin，只能影响可用余额、仓位保证金或形成 deficit。

## 11. K 线服务主流做法

主流交易所不会给每个交易对启动一个独立消费者线程再每笔成交 read-modify-write 数据库。

更常见的做法：

- Kafka 按 `symbol` 分区。
- 聚合状态放在内存、RocksDB、Redis、Kafka Streams/Flink state store。
- DB/ClickHouse/Timescale 主要做最终或准实时落盘。
- 每笔成交带 `tradeId/sequence/offset`，支持幂等和重放。
- K 线计算和 WebSocket 推送分层。
- 新增交易对通过动态配置和任务调度接入。

本项目 `surprising-candlestick` 采用 per-symbol Kafka key，Kafka Streams state store 保存 K 线聚合状态，PostgreSQL 承接查询落盘。

## 12. WebSocket 和 API 网关

前端 REST 请求走 `surprising-gateway`：

```text
frontend -> gateway -> instrument/order/account/risk/market APIs
```

实时推送走 `surprising-websocket`：

```text
Kafka domain events -> websocket provider local fanout -> frontend
```

公共频道：

- K 线
- 最新成交
- 盘口深度
- 指数价格
- 标记价格
- 资金费率

私有频道：

- 订单状态
- 撮合成交
- 持仓变化

WebSocket 多节点部署时，每个 websocket 节点使用自己的 Kafka consumer group，让公共行情广播到每个节点。用户连接状态保存在当前 websocket 节点内存里，私有事件在节点本地按 `userId` 过滤后推送给该节点上的连接。

## 13. outbox 是什么

outbox 是“先落库、再可靠发布 Kafka”的模式。

业务事务里同时写：

```text
业务表
outbox_events
```

然后后台 publisher 扫描未发布 outbox，发送 Kafka，成功后标记 `published_at`。

这样可以避免：

- 数据库写成功但 Kafka 没发出去。
- Kafka 发出去了但数据库回滚。
- 服务重启导致事件丢失。

下游消费者仍然必须幂等，因为 outbox 是至少一次投递。

## 14. 用户充值后资金如何流转

外部充值不会先进撮合引擎。正确链路是：

```text
链上/支付系统确认充值
  -> account-provider 记账
  -> account_balances.available_units 增加
  -> account_ledger_entries 记录流水
  -> 用户可用余额增加
```

撮合引擎只负责订单撮合，不持有用户真实余额。

下单时：

```text
order-provider 校验余额和风险
  -> 锁定 initial margin 到 account_margin_reservations
  -> 订单命令进入 Kafka
  -> matching-provider / exchange-core 撮合
  -> account-provider 消费成交
  -> 迁移订单保证金到仓位保证金或释放剩余保证金
```

## 15. RocksDB 在项目里的角色

RocksDB 适合做本地状态存储，不适合代替账户权威账本。

本项目里 RocksDB 主要用于 Kafka Streams 这类状态层，例如 K 线聚合 state store。账户余额、仓位、保证金、风险快照、强平候选仍以 PostgreSQL 作为当前权威状态。

撮合引擎 exchange-core 自身支持快照和 journal 思路；项目里的 matching provider 还会从 open orders 恢复 order book。生产环境需要继续完善 exchange-core snapshot/journal 的持久化和快速恢复策略。

## 16. 当前项目模块分工

```text
instrument-provider
  合约配置、交易规则、风险档位、指数源配置

index-price-provider
  外部现货价格采集、USD/USDT 换算、指数价

mark-price-provider
  指数价 + 合约盘口 + 资金费率 -> 标记价

order-provider
  下单校验、余额和保证金预占、订单事件、撮合命令 outbox

matching-provider
  exchange-core 撮合、成交、盘口深度、撮合结果 outbox

account-provider
  成交结算、余额、持仓、手续费、保证金迁移、持仓推送

risk-provider
  定时扫描持仓风险、写风险快照、生成强平候选

liquidation-provider
  消费强平候选、复核风险、生成 reduce-only 强平单

funding-provider
  资金费率发布和资金费结算

insurance-provider
  保险基金、穿仓亏损覆盖

adl-provider
  保险基金不足时的自动减仓

websocket-provider
  行情、订单、成交、持仓实时推送

gateway-provider
  前端统一 REST API 入口
```

## 17. 新手最容易误解的点

- 合约买入不是买现货 BTC，而是开多仓。
- 卖出开空不需要先持有 BTC。
- 保证金不是消费掉的钱，是为仓位锁住的抵押。
- 杠杆越高，初始保证金越少，但爆仓价越近。
- 爆仓看标记价，不看最新成交价。
- 止损如果按最新成交价触发，可能标记价先到爆仓价，导致先被强平。
- reduce-only 只减仓，不开新仓。
- risk-provider 发现爆仓风险，liquidation-provider 执行强平。
- 强平也走正常订单和撮合链路，不能直接改账户数字。

## 18. 双向持仓、逐仓和全仓

### 18.1 单向持仓和双向持仓

单向持仓也叫净持仓模式。同一个用户在同一个合约上只有一个仓位数量：

```text
positionQty > 0  表示多仓
positionQty < 0  表示空仓
positionQty = 0  表示无仓
```

用户买入会增加多仓或减少空仓，用户卖出会增加空仓或减少多仓。这个模式最容易保证资金正确，撮合、账户、风控、强平链路也最简单。

双向持仓也叫 hedge mode。同一个用户在同一个合约上可以同时存在两个仓位：

```text
BTC-USDT LONG
BTC-USDT SHORT
```

这两个仓位不能在账户里简单相减。它们要有独立的 `positionSide`，订单、成交、持仓、资金费、风险快照、强平候选都要带这个字段。否则用户同时持有 10 BTC 多仓和 10 BTC 空仓时，如果系统只看净仓 0，就会漏算维持保证金、手续费、资金费和强平风险。

### 18.2 逐仓是什么意思

逐仓是把保证金分配到单个仓位。风险边界通常是：

```text
userId + symbol + positionSide
```

例如用户有 BTC-USDT 多仓逐仓保证金 1000 USDT，ETH-USDT 仓位和合约账户里的其他余额不会自动给这个 BTC 多仓兜底。BTC 多仓亏损到保证金不足时，只强平这个逐仓仓位。

逐仓必须支持：

- 开仓时把初始保证金锁进该仓位。
- 用户手动追加保证金。
- 用户在安全范围内减少保证金。
- 资金费优先从该仓位保证金或规则指定账户扣减。
- 强平只关闭该 `symbol + positionSide` 的仓位。

### 18.3 全仓是什么意思

全仓是同一个结算资产下的合约账户共享权益。风险边界通常是：

```text
userId + settleAsset
```

例如 USDT 本位全仓里，BTC-USDT、ETH-USDT、SOL-USDT 的未实现盈亏、余额和维持保证金会汇总计算。一个仓位亏损时，其他仓位盈利和可用余额可以暂时支撑它；但如果总权益低于总维持保证金，整个 USDT 合约账户会进入强平风险。

全仓强平不是随便平所有仓位。更合理的路径是：

- 先撤销会继续占用保证金的开仓挂单。
- 汇总 `userId + settleAsset` 的权益、未实现盈亏、维持保证金。
- 找出亏损最大、杠杆最高、风险贡献最高或流动性最好的仓位优先减仓。
- 每次减仓后重新计算风险，恢复安全后停止。
- 如果仍然穿仓，再进入保险基金和 ADL。

### 18.4 双向持仓下如何爆仓

双向持仓 + 逐仓：

- 多仓和空仓是两个独立风险桶。
- 多仓亏损只检查 `LONG` 的逐仓保证金。
- 空仓亏损只检查 `SHORT` 的逐仓保证金。
- 爆仓时只对亏损侧提交 reduce-only 平仓单。
- 另一侧盈利仓位不能被系统自动拿来抵扣，除非产品规则允许自动转保证金。

双向持仓 + 全仓：

- 多仓和空仓仍然是两个仓位，但权益在同一个结算资产账户里共享。
- 未实现盈亏可以在账户层汇总，但维持保证金不能简单按净仓为 0 处理。
- 风控应按两侧风险敞口计算维持保证金，再在账户层汇总。
- 触发强平后，系统应优先减少导致风险升高的一侧，而不是直接把多空内部对冲掉。

### 18.5 逐仓/全仓是用户选还是平台定

成熟交易所一般让用户选择逐仓或全仓，但平台控制默认值、可选范围和切换限制。公开规则里，Binance Futures 支持用户在 Cross 和 Isolated 之间切换；OKX futures 文档也说明用户可选择 cross margin 或 isolated margin。

平台应该这样设计：

- 平台配置默认保证金模式，例如新手默认全仓或默认逐仓。
- 每个合约配置是否支持逐仓、全仓、双向持仓。
- 用户可以在下单前选择保证金模式。
- 已有仓位或挂单时，不允许随意切换，或者只允许在满足风险校验后切换。
- API、订单、持仓、风控、强平都必须持久化 `marginMode` 和 `positionSide`，不能只放在前端状态里。

本项目当前建议路线：

1. 当前交易链路支持单向净持仓下的 `CROSS` 和 `ISOLATED`，保证金模式会从订单、撮合、账户、风控、资金费一直传到强平。
2. 用户杠杆配置已经落在 `trading_leverage_settings`，按 `userId + symbol + marginMode` 生效。设置时先校验 instrument 最大杠杆，下单冻结保证金前再按订单 notional 和当前同保证金模式持仓 notional 匹配风险档位，超过该档最大杠杆会拒单。
3. 逐仓已经具备基础隔离：逐仓亏损、手续费和资金费只消耗该 `userId + symbol + asset + ISOLATED` 的仓位保证金，亏穿后进入 deficit，不会动用全仓可用余额。
4. 全仓仍按同一结算资产聚合：全仓风险使用 cross 可用余额、cross 仓位保证金和全仓未实现盈亏计算。
5. 后续还需要增加用户手动追加/减少逐仓保证金、逐仓/全仓模式切换约束、双向持仓 `positionSide`、破产价/接管价、强平清算费和更完整的异常保护模式。

当前项目不是组合保证金或双向持仓系统。它是“单向净持仓 + 可选全仓/逐仓”的合约基础模型，适合作为生产系统继续扩展，但还不能等同 Binance/OKX 的统一账户或 hedge mode。

## 19. 和主流交易所强平规则的差异

主流交易所的强平不是一个简单的“亏到某个价格就全仓市价平掉”。更完整的实现通常包含：

- 标记价触发：爆仓判断使用 mark price，不使用最新成交价。
- 阶梯维持保证金：仓位 notional 越大，风险档位越高，维持保证金率越高。
- 逐仓和全仓：逐仓只使用该仓位保证金；全仓会共享合约账户可用权益。
- 保证金模式和仓位模式：单向持仓、双向持仓、组合保证金会有不同强平路径。
- 强平前撤单：触发强平时，先撤销或拒绝会继续占用保证金的新订单。
- 分阶段减仓：先降低风险档位或释放保证金压力，只有严重亏损时才全量关闭。
- 破产价和接管价：强平系统接管仓位后，会用破产价/接管价计算保险基金收入或亏损。
- 强平手续费/清算费：强平单通常收 taker fee，部分交易所还有 liquidation clearance fee。
- 保险基金：强平成交后如果穿仓，先由保险基金覆盖亏损。
- ADL：保险基金不足时，对盈利且高杠杆用户执行自动减仓。
- 保护模式：行情异常、标记价异常、指数源异常时，可能进入 reduce-only、暂停强平、特殊标记价计算等保护状态。

本项目当前已经实现的部分：

- 标记价触发风险计算。
- 指数价 + 标记价服务独立于撮合成交价。
- long 定点数账户、持仓、PnL、手续费、保证金。
- 风险扫描生成账户/持仓快照。
- 按 `userId + settleAsset` 做多节点风险扫描租约。
- 按风险档位计算维持保证金率。
- 生成强平候选后由 liquidation-provider 复核最新风险。
- 强平单使用 reduce-only MARKET IOC。
- 强平前撤销用户已有 reduce-only 平仓单。
- 分阶段强平 sizing：优先降到低风险档位附近，严重风险才全量关闭。
- 强平成交继续走 order/matching/account 正常链路。
- 保险基金和 ADL 模块已拆分。

与 Binance/OKX 这类成熟交易所仍有差距的部分：

- 逐仓/全仓已经在账户、风控、资金费和强平链路按 `marginMode` 分开，用户杠杆设置也已经接入下单保证金和风险档位校验，但还没有用户手动追加/减少逐仓保证金、模式切换约束和 hedge mode `positionSide`。
- 组合保证金、跨币种抵押折扣、统一账户风险抵扣还没有完整实现。
- 破产价、接管价、强平清算费、保险基金收入的精细规则还需要继续补齐。
- 强平撮合后的剩余亏损分摊链路已经有 insurance/ADL 模块，但还需要更大规模极端行情测试。
- 风险扫描目前是定时扫描，默认 1 秒；主流交易所通常会结合 mark price 事件驱动、账户变更事件和高频风控队列。
- 标记价异常保护、指数源异常暂停强平、reduce-only-only market mode 等保护模式还需要继续做得更细。
- liquidation executor 的分片、优先级队列、强平吞吐和失败恢复还需要压测后继续优化。

因此，当前项目已经覆盖了“能跑通的核心强平链路”和基础逐仓/全仓隔离，但还没有完全等同主流交易所的全部清算规则。后续要继续补齐的重点是：逐仓保证金手动调整、保证金模式切换、双向持仓、破产价/接管价/强平费、事件驱动风控、异常保护模式、极端行情压测和资金安全审计。
