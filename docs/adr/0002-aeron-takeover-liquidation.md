# ADR-0002：使用 Aeron 原子接管强平

强平采用 `Takeover Liquidation`：Aeron Core 以当前已提交 mark 和相同 trigger sequence 为执行价，在
一条命令内重新校验 Risk 与完整仓位身份、撤销该用户同 symbol 开放单、关闭仓位，并同步结算 PnL、
实际可收强平费、Insurance Treasury 和 deficit。外围 Liquidation Coordinator 只查询有界
`Liquidation Work`、使用稳定 `commandId` 重试并续跑每 symbol Risk Scan；PostgreSQL 只保存查询投影。

这一选择放弃“Kafka candidate → Redis lease → PG 行锁 → MARKET IOC 强平订单 → Matching/Account 回环”的
外部 Saga。订单簿成交模型更贴近公开市场，但会重新引入多权威状态、部分成交窗口、恢复游标和极端行情
无法成交的问题；项目尚未上线，因此优先选择单一复制日志、确定性恢复和简单资金原子性。对手盘损失由
Core Liquidation State 后续进入 Insurance/ADL，而不是伪造一个已经成交的外部强平订单。

陈旧 trigger sequence、仓位数量/方向/模式/version 变化必须取消或拒绝，且不得先撤用户开放单。
管理员不能在 Risk 仍为 `LIQUIDATION` 时绕过 Core 强制取消计划。强平费只记录实际从抵押品收取的金额，
并与同一命令增加的 Insurance Treasury 完全一致。
