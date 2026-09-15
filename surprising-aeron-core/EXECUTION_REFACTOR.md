# 交易执行链路改造状态

本批改动已通过正确性验证，尚未进行性能验收；不代表全部业务已经完成迁移。

## 已修改的业务路径

| 路径 | 执行顺序 | Owner 终态前的工作 |
|---|---|---|
| 普通下单 | Lane 准入 → Matcher → 预先排队的 Lane 结算事件 → 有序提交 | 准入和依赖顺序派发 |
| 普通撤单 | 入口检查订单身份 → Matcher → Lane 接受则解冻撤单、拒绝则不改订单 → 有序提交 | 准备订单引用和 Lane 顺序位置；不再收集撮合结果后派发撤单 |
| 触发单撮合 | 子单准入 → Matcher 构建成交及触发终态 → Lane 一次应用 → 有序提交 | 准入和依赖顺序派发；不再在撮合后构建触发终态 |
| 普通撤改单 | Matcher 撤旧单并撮合新单 → Lane 解冻、冻结、应用成交 → 有序提交 | 提前解析替换输入；删除 LaneReplaceEvent 和 Owner 替换准备续步 |
| 批内改单 | 逐项准入 → Matcher → 同一次 Lane 撤旧、冻结新单和结算 | 保留下一项依赖上一项完成的校验；删除撮合后的第二次冻结任务 |
| 批量撤单 | 同 Matcher 的撤单段 → Matcher 一次发布该段结果 → Lane 依序撤单 | 收集完成的段及逐项响应；最后一段实际撤单同时推进 Lane 序号，不再增加终态提交任务 |
| 其他撮合结果 | Matcher 直接发布到 CommandSlot | 回收传输槽；仍有下述业务续步 |

入口：`orchestration/TradingCoreRuntime.submitMatching`。撮合到 Lane 的事件：`state/MatcherSettlementEvent`。有序提交：`orchestration/OrderedCommitCoordinator`。

撤改单的新单对象提前构造、由 Lane 直接安装；同一事件内完成冻结及成交，不再创建中间 pending reservation 索引。失败的新单不写入账户；只应用 Matcher 已接受的撤单。

撤单事件不解析成交资产、持仓身份或当前合约配置。拒绝的撤单不写订单终态，也不补写订单时间戳。触发单保留已有成功、拒绝和资金处理语义。

## 并发边界

- Matcher 传输槽只由 Owner 回收，先清理内容再推进回收游标。
- Matcher 先构建直达结果、释放命令路由，最后发布 Lane 就绪信号；批内构建结果不会提前放行 Lane。
- Lane 发布完成位后，不再读取可能已被 Owner 回收的事件字段。
- 空的命令结果轮询不得清空 Matcher 并发发布的结果。
- Lane 队列仍为单生产者队列：Owner 排定位置，Matcher 发布该位置的数据；没有让两条线程并发写入原 SPSC 队列。

## 尚未完成

1. 批量命令仍有逐项 Owner 准入推进；逐项改单仍有批终态 Lane 提交。批量撤单已合并最后一次 Lane 提交；下一项准入及其余批终态元数据补写仍待整合。
2. 清算、资金费、交割和期权结算仍使用控制续步。需要把账户业务推进交给对应执行方，Owner 仅在最终阶段合并全局资金及有序事实；回滚、分页和跨账户汇总不能直接删除。
3. 通用命令仍使用 `LaneCommitEvent` 补写订单元数据、取消关联触发单、推进账户序号。需要逐业务合入最后一次实际 Lane 修改，不能提前发布未完成的账户状态。

## 正确性验证（2026-09-15，通过）

按用户最新要求先执行正确性测试，压测留到最后。HotSpot JDK 25；没有新的吞吐结论。

已修复本轮测试发现的问题：

| 问题 | 原因与修复 |
|---|---|
| 触发子订单缺少提交边界 | 内部登记未携带父命令日志时间、位置；登记时一次绑定，再交 Matcher。 |
| 批量撤单多一次 Lane 任务 | 直达事件未承担终态序号；最后实际撤单段同时提交，保留缺失尾项拒绝和跨 Matcher 分段顺序。 |
| 撤单完成后仍有未发布修改 | 删除末尾提交任务后遗漏变更发布；收集直达撤单事件时登记发布，最终 Owner 提交消费。 |
| 批内 post-only 改单拒绝被当成致命错误 | 结果核对只查普通改单准入；改为读取当前批项的原单信息，只认可已授权的撤单结果。 |
| 致命错误被底层异常遮蔽 | 健康检查先探测 Lane 再看已记录错误；优先抛已记录的致命错误，后续请求保持一致停止。 |

测试修正：结果等待读取直达事件；终态订单断言使用活跃索引删除语义；故障在 Matcher→Lane 真实交接边界注入；余额溢出注入到真实账户且在提交前完成。保留资金守恒、禁止错误提交、重复请求和快照重放断言。

最终按测试类最新有效报告去重：141 个类、1,320 个用例，失败/错误/跳过均为 0。

| 范围 | 用例 |
|---|---:|
| Core 服务：六产品线资金、订单/批量、触发、清算/ADL、资金费、交割/期权、槽位复用、故障和快照恢复 | 952 |
| 协议、客户端 | 156 |
| 产品与合约公共模块 | 24 |
| 9 个受影响基准驱动的功能测试类：完成计数、账户资金、持仓、触发、恢复 | 188 |

服务完整测试首次有 5 个旧辅助断言错误（引用已删除的 `currentAdmission`）；替换为真实未完成工作检查后，恢复类 8 个用例复跑通过。其余完整测试结果保留，不重复计算复跑用例。

```sh
mvn -q -pl surprising-aeron-core/surprising-aeron-service -am test
mvn -q -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=RuntimeCommitRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -q -pl surprising-aeron-core/surprising-aeron-benchmarks -am -DskipTests test-compile
mvn -q -pl surprising-aeron-core/surprising-aeron-benchmarks -am -Dtest=LinearPerpetualBenchmarkSupportTest,ClusteredBatchTradingBenchmarkTest,RealtimeWorkloadTest,SpotMixedWorkloadTest,DerivativeMixedWorkloadTest,CorePerpetualEndToEndBenchmarkTest,AccountLaneCommitBenchmarkTest,TriggerCommitBenchmarkTest,ContinuousOwnerBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false test
```

基准驱动测试仅验证业务结果，没有启动 JMH 计时或吞吐验收。未测 JFR/分配率、单节点持续饱和吞吐、长稳和 GCP；不推断性能提升。其余外围服务未受本次 Core 内部交接变更影响，未启动 wallet、Kafka 等完整业务环境。上述“尚未完成”的架构迁移不因正确性测试通过而视为完成。

每轮失败及清理记录见根目录 `PERFORMANCE_VALIDATION.md` 的 2026-09-15 正确性验证条目。


## 短时性能诊断（2026-09-15）

按用户追加要求完成真实本机单成员Aeron短测：1 Matcher、4 Lane、BUSY_SPIN、G1、128 symbols、MIXED batch20、在途256，plain/JFR各预热30s+测量30s。plain前两个稳定区间约17.6/18.1万business ops/s，下单p99=26.296ms；驱动含排空汇总17.96万。完成性与资金核对通过，未达30万和5ms参考线。

采样窗口Owner约98%单核、Matcher56%、Lane实际业务执行约25%；Owner仍以结果索引、批量终态及状态发布为主要受限路径。Core分配估算336MB/s、约2.0KB/business op。短轮受IDEA和JIT影响，不证明性能回退或最终容量；详见根目录PERFORMANCE_VALIDATION.md新增记录。未做长稳、GCP，前述架构剩余项不变。
