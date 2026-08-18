# surprising-instrument


Surprising Exchange 产品基础配置模块。它是现货、永续、交割和期权交易系统的产品规则中心，后续撮合、风控、账户、K 线、指数价格、标记价格、资金费率、交割和行权都应该从这里获取 symbol 和交易规则。

## 模块

- `surprising-instrument-api`：RPC 合约、DTO 和事件模型。
- `surprising-instrument-provider`：配置持久化、查询、管理接口和 Kafka 变更事件发布。

## 核心职责

- 产品基础信息：`symbol`、产品线、instrument 类型、base/quote/settle asset、合约类型、合约面值。
- 价格/数量规则：tick size、step size、最小/最大下单数量、notional 限制、精度。
- 下单规则：支持的订单类型、time in force、post-only、reduce-only、market order 开关。
- 风险规则：最大杠杆、初始保证金率、维持保证金率、风险限额档位。
- 交易手续费默认配置：maker/taker 费率使用 ppm。正数表示向用户收费，负数表示返佣；用户/VIP/做市/活动覆盖由 command provider 的 `trading_fee_schedules` 解析后写入订单快照。
- 资金费率配置：永续产品的 funding interval、interest rate、cap/floor、impact notional。
- 生命周期字段：交割和期权产品的到期时间、交割时间、结算方式、标的 symbol、行权价、期权类型和行权风格。
- 指数价格成分源：外部现货源 REST/WS 配置、权重、USD/USDT 换算规则。
- 版本管理：每次变更生成新 `version`，通过 `instrument_current_versions` 切换当前版本。
- 多节点安全：`instrument_symbol_sequences` 为同一个 symbol 原子分配版本号，避免多个 admin 请求并发时 version 冲突。
- 存储边界：每个 Repository 只访问一张表；`InstrumentStorageService` 在事务内聚合主版本、
  全局/产品线当前版本指针、风险档位和指数源，并对附属配置执行批量补全。

## long 单位模型

instrument 配置按 exchange-core 友好的 long 单位保存：

- `price_tick_units`：一个价格 tick 对应的 quote asset 最小单位。
- `quantity_step_units`：一个数量 step 对应的 base asset 最小单位。
- `min_quantity_steps` / `max_quantity_steps`：下单数量边界，已经是 step。
- `min_notional_units` / `max_notional_units`：long notional 边界。`LINEAR_PERPETUAL` 使用结算资产最小单位；`INVERSE_PERPETUAL` 使用报价币合约面值单位。
- `notional_multiplier_units`：`LINEAR_PERPETUAL` 表示每个 `priceTick * quantityStep` 对应的结算资产最小单位；`INVERSE_PERPETUAL` 表示每个合约 step 的报价币面值单位。
- `contract_type` 不只是展示字段，账户、风控、资金费、强平和 ADL 的公式都会按它分支。
- `maker_fee_rate_ppm` / `taker_fee_rate_ppm`：产品默认手续费。订单入口会叠加 `trading_fee_schedules` 覆盖后写入 `trading_orders` 快照；账户结算按订单快照写入 `TRADE_FEE` ledger。正数扣用户余额，负数给用户返佣。默认 BTC/ETH 合约为 maker `200 ppm`、taker `500 ppm`。
- `user_open_interest_limit_rate_ppm` / `user_open_interest_limit_floor_units`：单用户动态持仓量上限配置。command provider 使用 `max(平台 OI notional * rate, floor)` 并再受 `max_position_notional_units` 限制。默认 BTC/ETH 为 `300000 ppm`，固定下限 `25000000000000`，即 250,000 USDT。
- `*_rate_ppm`、`max_leverage_ppm`、`weight_ppm`：费率、杠杆、权重统一使用 ppm。

`surprising-instrument-api` 同时提供 `PerpetualContractMath`，作为线性/反向合约 notional、未实现 PnL、每 step notional 和维持保证金的共享 long 公式实现。risk、funding、liquidation、ADL 应调用这个共享 math，不要在各自 SQL 里重复实现合约公式。

admin API 应直接提交这些整数字段。人类可读的小数格式放在后台 UI 或 API gateway 边界转换。

## 动态配置链路

```text
instrument-provider
  -> PostgreSQL instruments / instrument_current_versions
  -> PostgreSQL instrument_outbox_events
  -> surprising.instrument.events.v1
  -> 各业务 JVM 的不可变合约快照（order / matching / account / risk / price / candlestick / market-maker）
```

当前已接入：

- 下游服务启动时通过 `GET /internal/v1/instruments/snapshot?productLine=...` 一次性加载完整聚合快照。
- Instrument 变更通过 `surprising.instrument.events.v1` 广播；每个产品线使用独立 consumer group，在本 JVM 内原子替换快照。
- 下单、撮合、账户、风控、指数价、标记价、K 线和做市热路径只读 JVM 快照；数据库仅用于 Instrument 服务写入、启动恢复和审计回源。
- 启动加载统一由 `AbstractInstrumentSnapshotInitializer` 执行，增量事件统一由 `InstrumentSnapshotSupport.consume` 解析、校验并更新缓存；模块只保留产品线、消费组和派生配置刷新动作。

## 状态语义

- `PRE_TRADING`：允许行情预热，通常不允许真实撮合。
- `TRADING`：正常交易和行情计算。
- `HALT`：暂停撮合，行情历史服务仍可识别该 symbol。
- `SETTLING`：结算中。
- `CLOSED`：下线，不再处理新业务。

## 接口

查询当前版本：

```bash
curl 'http://localhost:9080/api/v1/instruments/latest?symbol=BTC-USDT'
```

查询指定版本：

```bash
curl 'http://localhost:9080/api/v1/instruments/version?symbol=BTC-USDT&version=1'
```

查询列表：

```bash
curl 'http://localhost:9080/api/v1/instruments/list?type=PERPETUAL&status=TRADING'
```

服务间初始化（业务模块使用内部入口，不应由网关公开）：

```bash
curl -H 'X-Product-Line: LINEAR_PERPETUAL' \
  'http://localhost:9080/internal/v1/instruments/snapshot'
```

后台分页查询当前产品：

```bash
curl 'http://localhost:9080/api/v1/instruments/admin/list?type=PERPETUAL&status=TRADING&limit=100&sort=symbol.asc'
```

后台查询当前产品详情和历史版本：

```bash
curl 'http://localhost:9080/api/v1/instruments/admin/BTC-USDT'
curl 'http://localhost:9080/api/v1/instruments/admin/BTC-USDT/versions?limit=50&sort=version.desc'
```

后台列表支持 `limit/cursor/sort` 游标分页。当前版本列表排序白名单为 `symbol.asc`、`symbol.desc`、`updatedAt.desc`、`updatedAt.asc`、`createdAt.desc`、`createdAt.asc`；历史版本排序白名单为 `version.desc`、`version.asc`。响应保留 `count/instruments`，并返回 `nextCursor`、`hasMore`、`sort`、`limit`。

更新状态：

```bash
curl -X POST 'http://localhost:9080/api/v1/instruments/admin/BTC-USDT/status?status=HALT'
```

完整 upsert 使用 `POST /api/v1/instruments/admin/upsert`，body 为 `InstrumentUpsertRequest`。生产应只允许后台管理系统通过 gateway 后台代理调用 admin API，产品配置和状态变更必须经过审批流、权限校验和操作审计。

## Kafka 事件

```text
surprising.instrument.events.v1
```

事件 key 固定使用 `PRODUCT_LINE:SYMBOL`，事件内容必须包含产品线、序列和完整
`InstrumentResponse` 快照，下游通过版本和更新时间丢弃旧事件，再以不可变引用整体替换本地缓存。
producer 使用 `acks=all`、幂等、`zstd` 和 `max.in.flight.requests.per.connection=5`，让合约版本变更事件和交易、价格链路保持一致的可靠 Kafka 基线。

## 数据库

根目录 [init.sql](../init.sql) 创建：

- `instruments`
- `instrument_current_versions`
- `instrument_product_current_versions`
- `instrument_symbol_sequences`
- `instrument_risk_brackets`
- `instrument_index_sources`
- `instrument_outbox_events`

首发目录使用 BTC、ETH、SOL、XRP、DOGE、BNB、ADA、AVAX、LINK、DOT、LTC、BCH、TRX、TON、SUI、
APT、NEAR、UNI、AAVE、ETC 共 20 个主流资产。`SPOT`、`LINEAR_PERPETUAL`、`INVERSE_PERPETUAL`、
`LINEAR_DELIVERY`、`INVERSE_DELIVERY`、`OPTION` 各初始化 20 个 symbol，总计 120 个；每个 symbol 配置
产品线当前版本，衍生品配置三档风险限额，全部配置 Binance、OKX、Bybit 三个指数源。

为兼容已有 API 和交易脚本，`BTC-USDT`、`ETH-USDT` 仍表示 U 本位永续；其他业务线通过 `-SPOT`、
`-INVERSE-PERP`、到期日及期权 strike 后缀区分。同一底层资产的不同产品线不会共享 instrument 主键或当前版本。

## 本地运行

```bash
brew services start postgresql@18
brew services start kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
# Topic 初始化命令待验证脚本重新整理后补回
mvn -pl :surprising-instrument-provider -am spring-boot:run
```

## 生产注意事项

- Instrument 是全系统唯一产品配置源，不要在撮合、风控、行情服务里再维护第二套 symbol 规则。
- 查询接口无状态，可以多节点水平部署；写接口共享 PostgreSQL，通过 `instrument_symbol_sequences` 保证同 symbol 版本号单调递增。
- instrument 版本、状态变更、交割和行权事件先与业务状态一起写入 `instrument_outbox_events`；发布器收到 Kafka ACK 后才标记成功，失败事件按指数退避重试，同一 `topic + event_key` 在多节点下保持顺序。
- 到期版本进入 `SETTLING` 后，command provider 先排空订单；Aeron Core 内的条件单状态随核心状态机一起收敛，account 再核对预占、成交消耗和释放。只有相同版本的 `ORDER`、`ACCOUNT` 确认全部写入 `instrument_lifecycle_drain_acks`，调度器才允许进入 `CLOSED` 并发布交割或行权事件。
- 生命周期清理确认使用共享 topic `surprising.instrument.lifecycle-drain.v1`，以 symbol 为 key；重复确认按 `(symbol, instrument_version, component)` 幂等。
- Instrument Service 才负责聚合主表、当前版本指针、风险档位、指数源和资产精度；单表 Repository 不执行跨表 JOIN。
- 下游核心服务启动时加载快照，运行中消费 Kafka 增量事件；不要在每笔请求、撮合或风控计算时查询主库。
- 修改 tick/step、杠杆、状态时必须生成新版本，不能原地覆盖历史版本。
- 修改 instrument 默认 maker/taker 手续费率也必须生成新版本。已接受订单继续使用 `trading_orders` 上的费率快照；旧持仓继续使用开仓时绑定的合约数学版本。
- 影响撮合和风控的配置变更需要审批流、审计日志和灰度生效时间。
- 新增 symbol 时，先写 instrument，再创建/确认 Kafka partition，再启动外部价格源，最后开放交易。

## 验证

```bash
mvn -pl :surprising-instrument-provider -am test
```
