# 产品线测试与资金守恒核对

[English](product-line-testing-and-funds-reconciliation.md) | 简体中文

## 目标

产品线 smoke 不是只验证接口返回 200，而是模拟用户通过 API 完成真实交易流程，并在每条线结束时独立核对账务。任何一个单位的资产差异都必须暴露出来。

当前脚本：

- `scripts/product-line-api-flow-smoke.sh`：按产品线启动必要 provider，执行用户下单、撮合、持仓、平仓、强平、生命周期事件和做市流程。
- `scripts/product-line-funds-reconcile.sh`：独立 SQL 核对脚本，对余额表、流水表、资金费、强平费、交割/行权、保险基金和冻结预占逐项对平。

## 一次只跑一条线

每次只启动当前产品线所需 provider，不需要把四条撮合业务全部启动：

```bash
PRODUCT_LINES=LINEAR_PERPETUAL BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
PRODUCT_LINES=LINEAR_DELIVERY BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
PRODUCT_LINES=OPTION BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
PRODUCT_LINES=SPOT BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
```

钱包服务不参与本地 smoke。测试资金通过 account/admin 产品账户调整接口或脚本 fixture 注入，核对脚本会把这些调整作为期初之后的 `adjustment_units` 单独列出。

## API 全流程覆盖

| 流程 | 现货 | 永续 | 交割 | 期权 |
| --- | --- | --- | --- | --- |
| 用户充值/调整 | 是 | 是 | 是 | 是 |
| 做市账号持续报价 | 是 | 是 | 是 | 是 |
| 普通用户 API 下单吃单 | 是 | 是 | 是 | 是 |
| 撮合成交与订单状态 | 是 | 是 | 是 | 是 |
| 现货资产互换 | 是 | 否 | 否 | 否 |
| 持仓形成 | 否 | 是 | 是 | 是 |
| 用户主动平仓 | 否 | 是 | 是 | 是 |
| 风控扫描 | 否 | 是 | 是 | 是 |
| 强平 | 否 | 是 | 是 | 是 |
| 强平费入保险基金 | 否 | 是 | 是 | 是 |
| 资金费 | 否 | 是 | 否 | 否 |
| 到期交割 | 否 | 否 | 是 | 否 |
| 到期行权 | 否 | 否 | 否 | 是 |
| 冻结/预占释放 | 是 | 是 | 是 | 是 |
| 独立资金核对 | 是 | 是 | 是 | 是 |

## 资金核对口径

`product-line-funds-reconcile.sh` 使用数据库整数单位核对，不做浮点或四舍五入。

对每个产品线、每个用户、每个资产，报告列包括：

- `opening_units`：第一条流水之前的期初余额，smoke 默认要求为 0。
- `adjustment_units`：充值、后台调整、fixture 注资。
- `trade_units`：现货本金变化、合约 PnL、期权权利金。
- `fee_units`：现货手续费和合约交易手续费。
- `funding_units`：永续资金费。
- `liquidation_fee_units`：用户强平费扣款。
- `delivery_settlement_units`：交割合约到期现金结算。
- `option_exercise_units`：期权到期自动行权结算。
- `transfer_units`：产品账户转账。
- `margin_adjustment_units`：持仓保证金迁移和释放。
- `final_ledger_units`：最后一条流水记录的余额。
- `final_available_units + final_locked_units - final_deficit_units`：余额表期末净额。

期末必须满足：

```text
opening
  + adjustment
  + trade
  + fee
  + funding
  + liquidation fee
  + delivery settlement
  + option exercise
  + transfer
  + margin adjustment
= final ledger balance
= available + locked - deficit
```

## 失败条件

任意一项出现即失败：

- 流水 running balance 不连续。
- 期末流水余额与余额表不一致。
- `available_units`、`locked_units`、`deficit_units` 或持仓保证金出现非法负值。
- 资金费 payment 与账务流水不一致，或同一 funding settlement 总和不为 0。
- 现货同一成交的 base/quote 本金不守恒。
- 期权同一成交的 `OPTION_PREMIUM` 不守恒。
- 衍生品 `TRADE_PNL + OPTION_PREMIUM + DELIVERY_SETTLEMENT + OPTION_EXERCISE` 总现金不守恒。
- 用户 `LIQUIDATION_FEE` 扣款没有保险基金同额入账。
- 手续费、强平费、交割、行权 reference 格式错误或找不到对应成交/生命周期记录。
- 现货未完成订单冻结额与 `account_spot_order_reservations` 不一致。
- 持仓保证金大于余额表 locked 金额，或保证金预占释放超过预占。

## 单独运行资金核对

```bash
PRODUCT_LINES="LINEAR_PERPETUAL LINEAR_DELIVERY OPTION SPOT" \
DB_NAME=surprising_product_line_smoke \
./scripts/product-line-funds-reconcile.sh
```

常用变量：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DB_HOST` | `localhost` | PostgreSQL 主机 |
| `POSTGRES_PORT` | `5432` | PostgreSQL 端口 |
| `DB_USER` / `DB_PASSWORD` | `surprising` | 数据库账号 |
| `DB_NAME` | `surprising_product_line_smoke` | smoke 数据库 |
| `PRODUCT_LINES` | `LINEAR_PERPETUAL LINEAR_DELIVERY OPTION SPOT` | 需要核对的产品线 |
| `STRICT_ZERO_OPENING` | `true` | 是否要求测试用户期初为 0 |
| `MAX_REPORT_ROWS` | `200` | 失败明细最大输出行数 |

## 最新验证记录

最近一次验证中，四条线逐线通过：

- `LINEAR_PERPETUAL`：API 下单、撮合、持仓、主动平仓、强平、资金费、保险基金入账、资金核对通过。
- `LINEAR_DELIVERY`：API 下单、撮合、持仓、主动平仓、强平、交割事件、持仓归零、资金核对通过。
- `OPTION`：API 下单、撮合、持仓、主动平仓、强平、行权事件、持仓归零、资金核对通过。
- `SPOT`：API 下单、撮合、资产互换、冻结释放、无衍生品持仓、资金核对通过。

所有已跑场景的资金核对 violations 为 0。当前高并发基线已合并到
[四产品线资金与性能压测报告](full-chain-funds-performance-report.md)。
