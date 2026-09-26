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

## 双向止盈止损与返回值一致性

- 新增 `PLACE_TRIGGER_OCO_PAIR` 命令，复用触发单列表编码；不增加订单状态副本或新执行阶段。两条腿在同一账户 Lane 校验成功后统一写入，任一校验失败不写入另一条。
- 网关接受 `atomic=true` 的止盈/止损二元组，校验用户、币对、平仓方向、数量、保证金/持仓模式和价格先后关系；稳定命令 ID 保证重试不重复创建。当前来源为标记价。
- 六产品线验证创建、第二条腿失败的原子性、幂等、止盈/止损任一先触发、另一条取消、快照恢复。
- 排查出同步快速结算路径缺少 `materializeChangeAccumulators`，导致订单成功但响应偶尔为空；补齐与异步完成路径相同的结果物化。恢复测试连续 10 次运行。另修正三处测试对线程先后顺序的假定，保留业务状态、响应和资金断言。
- HotSpot JDK 27：`mvn -pl surprising-gateway -am test` 全部通过，核心 932（2 个诊断条件跳过），网关 609（35 个外部环境/显式启用测试跳过），协议 96，客户端 50，其余依赖模块也通过。
- BBO 真实 API：买卖两个方向各 4 个模式，8 笔 IOC 全部返回终态；对手价成交、同向价未成交撤销。请求返回的委托价来自服务端盘口。
- 前端 52 项测试、lint、构建通过；浏览器检查英文/中文、1440/390 宽度、全局无可见滚动条、BBO 选项、单向/双向提示、明暗主题和真实 K 线/量。

本轮未开启最新价/指数价触发，未宣称私有成交推送与大规模资金验收已通过。运行中服务与既有 Archive 为用户演示环境，继续保留；本轮临时测试输出待现场验证结束后清理。

### 真实 API 补充排障

- 用户确认单向/双向止盈止损仅用于已有仓位的平仓保护，保持 reduce-only。
- 本地首次 OCO 实测暴露生命周期批次没有推进触发扫描。`LiquidationService` 已改用
  `CONTINUE_RISK_SCAN` 推进风险及触发工作，随后查询执行强平；38 项 lifecycle 测试通过。
- 20 个高频做市账户各有约 100 个挂单时，原 64 工作单元扫描预算推进不足。本地通过既有管理 API
  将 `RISK_SCAN_CONTROL` 从版本 1 更新为版本 2，预算 4096、间隔 25ms、enabled=true。
  配置属于本地已复制日志中的权威状态；没有关闭风控或改变保证金规则。
- 大触发单 ID 实测可超过 `Long.MAX_VALUE / 2`。`TriggerOrderCommands.triggerChildOrderId`
  改为正奇数空间内的确定性映射和碰撞探测，避免乘二溢出；六产品两种 OCO 胜出方向定向测试通过。
- 完整回归曾暴露三个既有并发测试依赖后台线程速度（窗口数预期 2 实际 1，或准入事件已被收集）。
  测试补充 matcher/account Lane 等待栅栏，保留资金、状态哈希和恢复断言。
- 浏览器复核英文无中文残留，止盈止损问号可聚焦显示规则，全局滚动条隐藏但滚动保留。

- 升级切换时实测暴露账户回滚后的余额发布问题。`RuntimeAccountRollback.restoreLane` 原先只恢复
  Lane 余额，没有把恢复后的值写回同一回滚缓冲的 after-state；写前失败可能没有 after-state，
  写后失败则可能继续发布尝试写入的值。恢复后立即捕获实际 Lane 余额，供原发布边界使用。
  定向覆盖写前失败、写后恢复、异步冻结回滚，另跑六产品流水线回归，266 项无失败（1 条件跳过）。

- 20:02 的持续做市暴露账户线程直接写 Owner 变更缓冲：`reserveOrder → changedOrder → OwnerIndexedChanges.consolidate` 与 Owner 清空并发，造成越界退出。改为复用 LaneCommitDelta 交接，在 collectControlReservation 登记变更键。
  完整核心 935 项（2 条件跳过）及 realtime provider 47 项均无失败；新增确定性栅栏测试在旧实现下失败（账户任务完成前就看到了订单），修复后通过。
  验证日志：`/tmp/control-reservation-full-tests.log`、`/tmp/control-reservation-counterfactual.log`。
