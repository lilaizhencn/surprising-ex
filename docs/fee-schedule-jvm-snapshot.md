# 费率配置与 JVM 快照

## 当前功能

费率由订单模块统一管理，支持以下来源：

- `VIP`：用户 VIP 档位费率；
- `MARKET_MAKER`：做市商费率；
- `PROMOTION`：活动费率；
- `USER_OVERRIDE`：人工为用户设置的费率；
- `RISK_OVERRIDE`：风控紧急覆盖。

当前表的 `user_id` 必须为正数，因此“全局费率”实际含义是“该用户在所有交易对的费率”（`symbol IS NULL`），不是所有用户共享的系统默认费率。没有覆盖时使用 Instrument 快照中的 maker/taker 默认费率。

## 表结构

事实表是 `trading_fee_schedules`，由
`surprising-trading/surprising-order-provider` 的
`OrderFeeRepository` 单表负责读写。重要字段如下：

| 字段 | 含义 |
| --- | --- |
| `product_line` | 现货、永续、交割、期权隔离边界 |
| `user_id` | 费率所属用户 |
| `symbol` | 交易对；为空表示该用户所有交易对 |
| `maker_fee_rate_ppm` / `taker_fee_rate_ppm` | 百万分比费率，`200` 表示 `0.02%` |
| `source_type` | 费率来源和覆盖层级 |
| `tier_code` | VIP 或活动档位编码 |
| `effective_time` / `expire_time` | 生效和失效时间 |
| `status` | `ACTIVE` 或 `DISABLED` |

## 统一选择规则

订单和清算模块都使用 `FeeScheduleSnapshotCache` 的同一套比较器，不在各自模块重新组装查询：

1. 只看当前产品线、当前用户、当前交易对匹配的有效记录；
2. 先按来源优先级选择：`RISK_OVERRIDE` > `USER_OVERRIDE` > `PROMOTION` > `MARKET_MAKER` > `VIP`，确保风控紧急覆盖可以压过其他设置；
3. 同一来源下，交易对专属记录（`symbol` 有值）优先于用户级记录（`symbol` 为空）；
4. 同一作用域选择最近一次已生效的 `effective_time`；
5. 仍相同时选择较大的 `fee_schedule_id`；
6. 未生效、已过期、已禁用或快照不可用时，回退 Instrument 默认费率。

下单时最终 maker/taker 费率会写入订单费率快照，后续撮合、结算不再重新计算历史订单费率。

## 用户单独设置如何同步到 JVM

管理入口只有 `TradingFeeService`：

1. `TradingFeeController` 校验产品线和请求参数；
2. `TradingFeeService` 在事务内调用 `OrderFeeRepository` 写入费率表；
3. 同一事务向 Outbox 写入完整的 `FeeScheduleEvent`；
4. Outbox 发布到产品线费率 Topic：
   `surprising.<product-line>.fee.schedule.events.v1`；
5. 每个模块的 `FeeScheduleSnapshotConsumer` 消费事件，按记录版本幂等更新本模块 JVM 快照；
6. 订单模块启动时从本模块事实表恢复快照，其他模块启动时只通过内部 RPC
   `GET /internal/v1/trading/fees/snapshot?productLine=...` 初始化，Kafka 仅负责增量通知，不作为查询接口。

事件携带完整费率记录和 `UPSERTED`/`DISABLED` 类型。快照整体替换使用原子引用，订单热路径只做内存读取；费率快照未恢复、损坏或事件延迟时，统一使用 Instrument 默认费率，避免下单因数据库抖动阻塞。

## 标记价和指数价重启安全

实时标记价、指数价缓存消费者现在统一使用：

- `auto.offset.reset=latest`；
- 分区重新分配时 `seekToEnd`，即使旧消费组有已提交位点也不回放历史价格；
- 缓存写入按序列号和事件时间只接受更新版本；
- 使用前强制检查事件时间、状态和最大允许年龄，过期价格直接视为不可用。

因此重启后缓存为空是安全状态：必须等待新的实时价格消息才会继续使用价格，不能拿旧标记价或旧指数价触发风控、强平或结算。审计消费者仍可从 `earliest` 读取历史消息，但它不参与实时计算。
