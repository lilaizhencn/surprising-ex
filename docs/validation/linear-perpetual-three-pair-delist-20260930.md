# U 本位永续测试环境缩减为三个币对（2026-09-30）

## 业务步骤

入口是服务器 `surprising-ex` 的 U 本位永续单节点测试环境。原有 20 个 `TRADING` 币对，目标只保留 BTC-USDT（604）、ETH-USDT（653）、SOL-USDT（866）。先禁用其余 17 个做市策略，撤销对应挂单；核对所有用户在这 17 个币对上的仓位，并通过 `reduceOnly` 订单让做市用户与测试对手用户正常撮合归零。最后经管理员审批调用产品状态入口改为 `CLOSED`，由 `InstrumentService.updateStatus` 写入变更记录与 `instrument_outbox_events`，通过 `surprising.instrument.events.v1` 向 Core 和其它消费者传播。异常时停止后续下架，保留仍可处理的产品状态，核对订单和仓位后重试。

## 操作与核对

- `MarketMakerService.updateStrategyConfig` 为其余 17 个 `local-*-usdt-maker` 策略写入 `enabled=false`，`market_maker_strategy_overrides` 最终为 17/17 禁用。BTC、ETH、SOL 的策略保持运行。
- 通过 `/api/v1/trading/orders/cancel-open` 撤销停用策略的挂单。测试对手账户（用户 22）和普通测试账户（用户 1）没有这些币对的未完成挂单。
- 下架前，逐账户查询 Core 仓位，并在旧币对上配对提交 `reduceOnly` 平仓订单。最后检查 22 个原有用户在 17 个目标币对上均无非零仓位或未完成订单。17 个做市账户的 USDT 可用余额为正，冻结余额为零。一次 DOT 平仓因标记价过期被拒绝；新标记价到达后重新提交成功。
- 测试环境原先没有管理员。为执行现有双人审批入口，创建内部邮箱的临时管理员用户 23、24，并补齐审计要求的用户名。每项产品状态变更均单独申请、由另一管理员审批，再调用 `/api/v1/admin/gateway/instrument-admin/{instrumentId}/status`。完成后两个账号均删除管理员角色、撤销刷新会话并设为 `FROZEN`；其审计记录和用户行保留。因此数据库现有 24 个用户，其中 22 个为原有测试或做市用户，2 个为冻结的操作审计账号。
- 目标产品最终为 `CLOSED=17`、`TRADING=3`；17 条 `STATUS_CHANGED` outbox 事件均已发布。公开 `/api/v1/gateway/instrument/list?productLine=LINEAR_PERPETUAL&status=TRADING` 只返回 BTC-USDT、ETH-USDT、SOL-USDT。下架币对 XRP-USDT 的测试下单返回 `instrument is not trading`。
- 下架后最近两分钟的做市成功周期、指数价格和 K 线只涉及 604、653、866。2026-09-30 08:36 CEST 六个 jar 所在服务为 `active`，最新 K 线开盘时间为 08:35 CEST。

本轮只更改测试环境配置和业务状态，没有改动 Java 代码。未运行其它五条产品线。历史 K 线和审计记录保留，仅停止已下架币对继续产生新业务数据。
