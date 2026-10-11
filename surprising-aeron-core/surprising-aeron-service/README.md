# 核心提交日志回放

`CommittedTradeReplay` 由独立导出进程持有核心状态，只应用 Archive 已提交的命令；不会向在线 Cluster 发送交易命令。订单和成交沿用实时捕获，资金费通过 `PerpetualFundingCommands` 的回放观察接口交出实际支付结果，形成不可变的 `CommittedFundingPage`。该对象的生命周期仅为当前完整命令，下一次 apply 清空；没有第二份资金账本或长期支付缓存。

资金费页保留产品线、日志位置、核心时间、实际命令费率、完成游标及支付明细。重复或被拒绝的命令不产生支付页。在线异步 Account Lane 路径不安装观察接口、不读取数据库，资金计算、快照格式和核心权威状态不变。查询投影在 realtime 模块持久化，SQL 成功后才能推进导出 checkpoint。

HotSpot JDK 27 下 `CommittedFundingReplayTest` 覆盖 U 本位及币本位永续的实际多空支付、分页、快照恢复和重复命令；原有 funding processor、异步 funding、订单成交回放及权利金/资金费回归一并执行。此改动供独立回放使用，不要求重启在线核心。


## Matcher 结果与账户结算交接

账户分区按 `LaneTopology` 隔离，由对应的 `SettlementLaneWorker` 多线程消费。主线采用实验目录已修复的实现：Owner 定序并准备命令，Matcher 产生确定性撮合结果，`MatcherSettlementDispatcher.publishMatcherOwned` 通过每个 Lane 的分片 SPSC 队列直接发布成交及撤单结果，所属 Lane 串行应用账户结算；完成回执到达后，Owner 按提交顺序发布终态响应。

Matcher 在 `MatcherSettlementEvent.notifyDirectPublication()` 中校验结果并完成 Lane 投递，再发布就绪位。池化事件必须等实际消费者完成后才能复用。工作线程异常传给 Owner，避免命令在消费者退出后继续等待。全局控制命令沿用该实现已有的 Lane 所有权交接边界。

单笔及批量撤单的 Owner 入口只通过现有 `ActiveOrderIndex` 校验归属并准备路由，不读取 Lane 正在增删的 `orders` 普通 HashMap。`cancelIdentityAndPreparationNeverReadLaneOrderTables` 确定性禁止这类读取；跨用户仍拒绝，缺失订单仍返回 `ORDER_NOT_FOUND`，实际取消和释放冻结由所属 Lane 完成。编码层符号缓存、DirectBuffer 读取及实验目录已有低分配改动一并保留。

HotSpot JDK 27 的干净构建及 167 项撮合、资金、并发顺序、撤单、协议和实时投影契约回归通过；其中六产品线的单笔和批量撤单均有覆盖。本轮未执行 Aeron Cluster 快照或恢复场景，也未启动 wallet。吞吐和限频记录见主 README 的本次采用说明；本轮未据此宣称全链路零堆分配。
