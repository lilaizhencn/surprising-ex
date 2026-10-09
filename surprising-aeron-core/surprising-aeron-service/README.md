# 核心提交日志回放

`CommittedTradeReplay` 由独立导出进程持有核心状态，只应用 Archive 已提交的命令；不会向在线 Cluster 发送交易命令。订单和成交沿用实时捕获，资金费通过 `PerpetualFundingCommands` 的回放观察接口交出实际支付结果，形成不可变的 `CommittedFundingPage`。该对象的生命周期仅为当前完整命令，下一次 apply 清空；没有第二份资金账本或长期支付缓存。

资金费页保留产品线、日志位置、核心时间、实际命令费率、完成游标及支付明细。重复或被拒绝的命令不产生支付页。在线异步 Account Lane 路径不安装观察接口、不读取数据库，资金计算、快照格式和核心权威状态不变。查询投影在 realtime 模块持久化，SQL 成功后才能推进导出 checkpoint。

HotSpot JDK 27 下 `CommittedFundingReplayTest` 覆盖 U 本位及币本位永续的实际多空支付、分页、快照恢复和重复命令；原有 funding processor、异步 funding、订单成交回放及权利金/资金费回归一并执行。此改动供独立回放使用，不要求重启在线核心。
