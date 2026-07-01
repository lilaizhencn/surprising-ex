# surprising-instrument

[English](README.md) | [简体中文](README_CN.md)

Surprising Exchange 合约基础配置模块。它是交易系统的产品规则中心，后续撮合、风控、账户、K 线、指数价格、标记价格、资金费率都应该从这里获取 symbol 和交易规则。

## 模块

- `surprising-instrument-api`：RPC 合约、DTO 和事件模型。
- `surprising-instrument-provider`：配置持久化、查询、管理接口和 Kafka 变更事件发布。

## 核心职责

- 合约基础信息：`symbol`、base/quote/settle asset、合约类型、合约面值。
- 价格/数量规则：tick size、step size、最小/最大下单数量、notional 限制、精度。
- 下单规则：支持的订单类型、time in force、post-only、reduce-only、market order 开关。
- 风险规则：最大杠杆、初始保证金率、维持保证金率、风险限额档位。
- 交易手续费配置：maker/taker 费率使用 ppm。正数表示向用户收费，负数表示返佣。
- 资金费率配置：funding interval、interest rate、cap/floor、impact notional。
- 指数价格成分源：外部现货源 REST/WS 配置、权重、USD/USDT 换算规则。
- 版本管理：每次变更生成新 `version`，通过 `instrument_current_versions` 切换当前版本。
- 多节点安全：`instrument_symbol_sequences` 为同一个 symbol 原子分配版本号，避免多个 admin 请求并发时 version 冲突。

## long 单位模型

instrument 配置按 exchange-core 友好的 long 单位保存：

- `price_tick_units`：一个价格 tick 对应的 quote asset 最小单位。
- `quantity_step_units`：一个数量 step 对应的 base asset 最小单位。
- `min_quantity_steps` / `max_quantity_steps`：下单数量边界，已经是 step。
- `min_notional_units` / `max_notional_units`：long notional 边界。`LINEAR_PERPETUAL` 使用结算资产最小单位；`INVERSE_PERPETUAL` 使用报价币合约面值单位。
- `notional_multiplier_units`：`LINEAR_PERPETUAL` 表示每个 `priceTick * quantityStep` 对应的结算资产最小单位；`INVERSE_PERPETUAL` 表示每个合约 step 的报价币面值单位。
- `contract_type` 不只是展示字段，账户、风控、资金费、强平和 ADL 的公式都会按它分支。
- `maker_fee_rate_ppm` / `taker_fee_rate_ppm`：账户结算会按成交事件中双方各自的 instrument version 读取费率，并写入 `TRADE_FEE` ledger。正数扣用户余额，负数给用户返佣。
- `*_rate_ppm`、`max_leverage_ppm`、`weight_ppm`：费率、杠杆、权重统一使用 ppm。

`surprising-instrument-api` 同时提供 `PerpetualContractMath`，作为线性/反向合约 notional、未实现 PnL、每 step notional 和维持保证金的共享 long 公式实现。risk、funding、liquidation、ADL 应调用这个共享 math，不要在各自 SQL 里重复实现合约公式。

admin API 应直接提交这些整数字段。人类可读的小数格式放在后台 UI 或 API gateway 边界转换。

## 动态配置链路

```text
instrument-provider
  -> PostgreSQL instruments / instrument_current_versions
  -> surprising.instrument.events.v1
  -> candlestick / price / future matching / risk local cache
```

当前已接入：

- `surprising-candlestick-provider` strict 模式从 `instruments` 当前版本读取启用 symbol。
- `surprising-index-price-provider` 从 `instruments + instrument_index_sources` 动态读取 symbol 和指数源；`application.yml` 中的静态 BTC/ETH 配置只作为数据库未初始化时的兜底。

## 状态语义

- `PRE_TRADING`：允许行情预热，通常不允许真实撮合。
- `TRADING`：正常交易和行情计算。
- `HALT`：暂停撮合，行情历史服务仍可识别该 symbol。
- `SETTLING`：结算中。
- `CLOSED`：下线，不再处理新业务。

## API

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

更新状态：

```bash
curl -X POST 'http://localhost:9080/api/v1/instruments/admin/BTC-USDT/status?status=HALT'
```

完整 upsert 使用 `POST /api/v1/instruments/admin/upsert`，body 为 `InstrumentUpsertRequest`。生产应只允许后台管理系统或运维内网调用 admin API。

## Kafka

```text
surprising.instrument.events.v1
```

事件 key 使用 `symbol`。事件内容包含新版本的完整 `InstrumentResponse` 快照，下游可以直接替换本地缓存。

## 数据库

根目录 [init.sql](../init.sql) 创建：

- `instruments`
- `instrument_current_versions`
- `instrument_symbol_sequences`
- `instrument_risk_brackets`
- `instrument_index_sources`

默认已写入：

- `BTC-USDT`
- `ETH-USDT`

## 本地运行

```bash
docker compose up -d postgres kafka
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
./scripts/create-topics.sh
mvn -pl :surprising-instrument-provider -am spring-boot:run
```

## 生产注意事项

- Instrument 是全系统唯一产品配置源，不要在撮合、风控、行情服务里再维护第二套 symbol 规则。
- 查询接口无状态，可以多节点水平部署；写接口共享 PostgreSQL，通过 `instrument_symbol_sequences` 保证同 symbol 版本号单调递增。
- 下游核心服务不要每笔请求查数据库，应通过本地缓存消费 instrument 快照。
- 修改 tick/step、杠杆、状态时必须生成新版本，不能原地覆盖历史版本。
- 修改 maker/taker 手续费率也必须生成新版本。旧挂单和旧持仓继续使用它们被接受时绑定的版本。
- 影响撮合和风控的配置变更需要审批流、审计日志和灰度生效时间。
- 新增 symbol 时，先写 instrument，再创建/确认 Kafka partition，再启动外部价格源，最后开放交易。

## 验证

```bash
mvn -pl :surprising-instrument-provider -am test
```
