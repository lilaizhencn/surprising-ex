# 本地盘口中断与 BBO 验证（2026-09-26）

## 撮合中断

本地核心反复出现 `another owner commit context is active`，调用链为
`OrderedCommitCoordinator.completeDispatchedMatcherSettlement` →
`MatchingPipelineProgress.submitDeferredMatchingAfterBatch` →
`OrderBatchExecutor.activateOrderBatch` → `TradingCoreRuntime.restoreMatchingCommitContext`。

单笔成交或撤单已完成账户结算、资金守恒检查及结果保存，却在释放本笔 fact context 前恢复下一批订单。
修复将本笔 context 的释放提前到下一批恢复之前；保留原有结算顺序和资金校验，不跳过保护性断言。
同时覆盖普通、异步成交和异步撤单三条完成路径。

验证使用 HotSpot Corretto JDK 27：核心服务 917 项测试，0 失败，2 项条件性跳过。
新增交错单笔、批量下单及批量撤单 30 轮回归，每轮检查预占清空、余额还原。
全量首次运行发现一条旧测试只匹配错误提示全文；现有提示已追加诊断字段，调整为匹配固定前缀，资金及状态断言不变。
本地已更新核心 JAR，原有数据保留，恢复结果另行追加。

## BBO 下单契约

`PlaceOrderRequest.bboPriceMode` 可选 `OPPONENT_1`、`OPPONENT_5`、`SAME_SIDE_1`、`SAME_SIDE_5`。
BBO 只允许 `LIMIT` 且 `priceTicks=0`。`OrderService.normalize` 读取当前产品线的 Core 盘口，
按买盘降序、卖盘升序的不同价格档位排序，确定一次性限价后沿用原有验证和资金冻结流程。
买入的同向价取买盘，对手价取卖盘；卖出相反。深度不足明确拒绝，不改用其他价格。
它不是持续跟价单，价格取样与订单进入撮合之间盘口可能变化。

网关定向测试 91 项通过，含六产品线、两个方向、四种模式及深度不足、参数冲突场景。
BBO 不改变 Core 下单协议、账户类型、资金模型或 Kafka topic。
