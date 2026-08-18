# 统一交易命令入口

普通订单和止盈止损触发订单共享同一条 Product Core/Aeron 命令边界，因此合并为单一 `surprising-command-provider` 进程；保留原有 API 路径和内部 package，取消两进程之间的 HTTP/Feign 往返，降低部署复杂度和触发撤单的故障面。

## Consequences

- 每条 ProductLine 只启动一个交易命令 Provider，端口固定为 `9084`。
- `CancelAllAfterService` 直接调用进程内 `TriggerOrderService`，不再依赖 Trigger Provider 的网络可用性。
- 普通订单和触发订单仍按原 API、Core 命令和产品线隔离规则运行；合并不改变 Core 的资金与状态权威。
