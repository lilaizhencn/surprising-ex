# Aeron 本机压测诊断

`AccountQueryProjectionBenchmark` 单独测量显式 Lane 上的用户、订单和 clientOrderId 投影、哈希与编码。
它使用由 JMH 线程持有的冷 Lane 和真实权威表，排除邮箱、并行 worker、网络与交易负载；
不能用其分数推导实际 Lane 线程反查的收益、Core 交易吞吐或百字节目标。
查询 DTO 分配与交易业务项分开报告，实际工作线程及协议兼容性由对应功能测试验证。

`ClientSourceOrderingBenchmark` 覆盖实际命令池请求槽、单 Dispatcher 归属、客户端编码与响应解码；
即时回送的模拟对端替代网络和交易 Core。四个生产者各持有最多 64 个请求，全局窗口为 256，
每个测量区间及排空后检查 accepted 与 terminal 一致。JDK 27 fork 包含 Agrona 所需的模块导出。
该组件基准包含模拟对端分配，不能作为 Core 分配或真实单节点吞吐的验收结果。

执行前遵循仓库根目录的 [AGENTS-performance.md](../../AGENTS-performance.md)，采集前锁定条件并追加到 [PERFORMANCE_VALIDATION.md](../../PERFORMANCE_VALIDATION.md)。正式吞吐与 JFR 诊断分别采集。

## 饱和判定的证据边界

`summarize-aeron-async-stage.py` 将负载事实放在 `loadEvidence`：是否达到窗口上限、是否观察到窗口背压、等待窗口的时间比例，以及队列证据是否只有高水位。峰值不代表持续积压，BUSY_SPIN 的高 CPU 不代表业务处理饱和。

`saturationGate.status` 为 `UNCONFIRMED`（尚不能确认饱和）或 `INVALID`（客户端完成性检查失败或缺失）。当前采集缺少持续队列占用、有效处理/空转/依赖等待的区分和递增负载下的吞吐平台证据，因此四种 stage 均不能输出饱和 `PASS`；`UNCONFIRMED` 也不表示已经证明未饱和。该判定不代替热控、GC、资金和恢复验收。

Lane 字段改为 `executionWallNanos`、`executionWallRatio` 和 `laneExecutionWallRatioMin/Avg`；它们衡量业务执行区间的墙钟时间，可能包含抢占及 GC，不再命名为 `useful`。历史报告保持原样，读取新报告的工具应使用新字段；不再输出旧的 CPU 阈值判定 `thresholds`。

判定回归：`python3 -B -m unittest discover -s surprising-aeron-core/surprising-aeron-benchmarks/bin/tests -v`（仓库根目录运行）。

## UDP 接收缓冲单因素对照

`bin/qualify-aeron-async-stages.sh` 支持 `ASYNC_SOCKET_RCVBUF_BYTES`：

- 未设置或空值：保留 Aeron 默认值。
- 设置为正整数字节数：同时向节点和客户端 JVM 传入 `-Daeron.socket.so_rcvbuf`，在 `stage-config.txt` 记录请求值。最大值为 Java `int` 上限；操作系统仍可能限制或拒绝实际缓冲大小。
- 不修改系统全局 socket 参数。节点和客户端都需要生效；检查实际 socket 接收缓冲，不能只检查启动参数。

例如，在已锁定的相同负载命令前增加 `ASYNC_SOCKET_RCVBUF_BYTES=4194304`，将接收缓冲请求值设为 4MiB；再使用空值恢复默认做反转验证。生产进程可使用 Aeron 原生 JVM 参数，无需新增业务层配置或线程。

2026-09-22 的本机诊断中，4MiB 轮稳定窗口未观察到接收缓冲溢出丢包和节点 NAK，但批量撤单 P99 仍为 24.38ms。因此该设置是缓解缓冲溢出的手段，**尚不是主要 P99 长尾的修复**；完整 A/B/A 证据与限制见性能记录。不能据此修改全产品默认值或宣称吞吐提升。

## 延迟批量响应校验的生命周期

`LinearPerpetualBenchmarkSupport.Harness` 在终态回调中先读取批量响应，再释放 Core 响应 arena 的租约。启用延迟校验时，必须在释放前通过 `CoreResponse.data()` 保存独立 payload；不能把引用 arena 存储的 `CoreResponse` 留到 `scenario.verify()`，否则后续命令复用槽位会覆盖待校验数据。

Harness 只保存最后一份批量 payload，每次新批量终态覆盖它，生命周期与 Harness 相同；生产 Core 的响应池、协议和结算路径不变。该复制属于压测端的验证成本，本次修复未测量吞吐影响。回归覆盖四条衍生品线连续三轮 256 消息窗口，以及全部 benchmark 模块测试。


## 当前账户所有权下的正确性判定

`LinearPerpetualBenchmarkSupport.fill` 核对双方实际成交持仓、账户资金与手续费/保险资金守恒、剩余订单数量和 accepted/terminal 完成性。当前主线由常驻 `SettlementLaneWorker` 执行账户工作；队列高水位只表明队列被使用，跨账户成交仍须核对两侧实际资金、持仓及终态结果。

`qualify-aeron-async-stages.sh` 要求 `summarize-aeron-async-stage.py` 的 `clientPass=true`，即同时出现 `mixedCapacity=PASS` 与 `mixedVerify=PASS`。JMH 启动器即使在 fork 失败后返回 0，脚本也必须以失败退出，并保留录制及日志。资金核对缺失不能记为性能通过。

CPU 汇总的 `jfr.coverage` 记录实际首末 CPU 事件及请求测量边界：`MEASUREMENT`、`PARTIAL_MEASUREMENT`、`UNSCOPED_RECORDING` 或 `MISSING`。周期事件允许 2 秒边界误差；maxsize 滚动即使不产生 DataLoss，也必须标为局部窗口，不能用启动/预热 CPU 或截断样本代表完整稳定测量。

## Matcher 财务回执局部基准

`MatcherTreasuryReceiptBenchmark.completeFinancialReceipt` 使用常驻 Account workers 和原 Treasury 的专属账本线程，测量复用事实投递、账户完成位、资金回执和槽位回收。每次无成交撤单事实只涉及一个账户 Lane，1/4 参数表示启动的 worker 数量；它不代表跨 Lane 成交吞吐，也不是 API 容量测试。Core 序号逐次增加，固定 native payload 仅作为不可变无成交夹具。

JDK 27/G1，1 fork/1 thread，3×1s 预热、5×1s 测量的独立 `-prof gc` 结果约 0.046–0.052B/technical stage op，GC 为 0；另一个 JFR fork覆盖实际财务槽方法，包含启动及预热，不是 Core 稳定窗口。实际跨 Lane 成交的非零手续费由 `MatcherTreasurySettlementTest` 核对。生产接线及全链路分配仍未完成，详见根 `PERFORMANCE_VALIDATION.md`。


### 普通改单 Lane 准入基准

`ReplacementAdmissionBenchmark.admitAmendments` 使用 256 个在途准入槽和 1/4 个实际 Account workers。初始化一次账户和静态原订单，只测 Lane 只读 AMEND 校验、完成通知及池回收；每次新建的不可变业务结果包含在分配统计中，输入和市场元数据是常驻夹具。它不执行 Matcher 或资金结算，也不代表 Core 吞吐。无 profiler 和独立 `-prof gc` 两轮使用 JDK 27/G1，1 fork/1 thread，3×1s 预热、5×1s 测量；完整 Core 分配另由真实单节点 JFR 核对。


压测service等待策略可用 `ASYNC_SERVICE_IDLE_STRATEGY=BACKOFF` 做独立诊断，默认YIELDING，实际值写入strategy.txt和node.command。只改变既有生产idle策略，不能与业务代码改动混做吞吐归因。2026-10-11同代码两轮40.96/41.85万业务项/s且有限频，第三轮未执行，不据此确认收益或改变默认。


### 批量改单迁移的受影响性能证据

`ReplacementAdmissionBenchmark` 新增 LINEAR_PERPETUAL/SPOT 参数，测量实际 Account workers 上的改单只读准入，包含现货撮合前的资金判断。`PendingOpenInterestBenchmark` 测量0/128项已完成不可变持仓变更上的批次OI计算，初始化及市场输入是常驻夹具；这些技术阶段都不代表整链路容量。当前独立gc约152B/永续准入、128B/现货准入，OI计算接近零分配。

`ClusterSequentialBatchBenchmark.proceedsAndAmendOnLane` 使用规范数字 instrumentId，在真实单成员现货节点验证128组交易收入再冻结、改单、撤单及资金核对。SingleShot含初始化/查询并逐请求等待，只作为改动路径功能/JFR诊断，不计持续吞吐。当前永续固定MIXED窗口观测42.15万business/s，但限频导致容量验收无效，正式窗口不持续执行AMEND。整链路JFR约1172B/业务项、DataLoss=0，未达全链路零堆分配，也未做长稳。记录见根 PERFORMANCE_VALIDATION.md 和 [验证JSON](../../docs/validation/batch-amend-lane-admission-20261011.json)。


## 同进程交易流水线分配诊断

`InProcessTradingAllocationBenchmark` 用借用协议帧进入真实 `TradingOwnerLoop` 线程，经一个native Matcher、四个配置Account Lane和SystemLedgerLane，最后由真实egress编码交给消费响应的ClientSession夹具。两用户分别买卖同一instrument，实际占用其路由到的两个Lane；全局在途上限256，每次调用连续提交4096条Core消息并排空。每条业务订单都使用唯一订单号和客户端单号，避免撞上终态身份保留规则。

默认覆盖 `streamMode=OPEN_ONLY/REVERSE` 和 `batchSize=1/20`；反向场景每256条Core消息切换买卖方向，任一非APPLIED项都会终止该轮。结束时用Lane用户查询核对数量、可用加冻结总额和空预约，不执行快照。冻结余额已包含持仓保证金，核对不得再次加保证金。

基准内嵌HotSpot 27所需访问参数、1GiB固定G1和BUSY_SPIN设置。JMH `gc.alloc.rate.norm` 单位为字节/Core消息，除以batchSize才是字节/业务订单。自定义 `surprising.InProcessAllocationInterval` 记录每个预热/测量窗口的终态计数，JFR归因应只选择MEASUREMENT窗口。它不包含实际Aeron网络、consensus、Archive和realtime输出，不能作为真实单节点容量或完整Core分配验收。

分配诊断在每个迭代结束后输出一次 `pipelineInterval`，包含epoch起止时间及终态计数，便于与同机CPU/swap采样对齐。该输出位于交易循环之外，JFR事件也保留相同边界；仅使用正式窗口及其前后系统采样判断环境有效性，启动/预热单独列出。


`InProcessTradingAllocationBenchmark` 新增 `accountLanes` 参数，默认仍为 4，可显式选择 2 作独立诊断。初始化前固定实际拓扑，终止 worker 后恢复原属性；每个正式区间同时记录 Lane 数。该入口覆盖显式 Lane 参数贯穿的真实准入、撮合结算和终态响应，窗口保持 256、Matcher 为 1。它使用同 JVM 的 ClientSession 消费桩，不代表真实 Aeron 网络、Archive 或完整 Core 容量；不同 Lane 配置的结果必须分别报告。

### D1 会话边界适配（2026-10-15）

Owner 的输入及响应接口现在只携带 `sessionId`。`ReplySlotAllocationBenchmark`、`InProcessTradingAllocationBenchmark` 的本地消费器实现已有 `SessionEgress`，去掉完整 `ClientSession` 的伪实现；会话注册和真实服务使用同一 ID 路由与出口环。批量及线性夹具同步更新无客户端哨兵值和响应参数。新增 `D1BenchmarkBoundaryTest` 只做有限功能调用及终态资金核对，没有执行 JMH 计时、JFR 或压测。连同服务的必要回归共 111 项通过，见 [D1 完成验证](../../docs/validation/d1-complete-20261015.json)。


### D2 基准夹具接线清理（2026-10-15）

`MatcherTreasuryReceiptBenchmark` 改为预留 Matcher 发布槽并使用实际 Matcher→Lane 邮箱，删除旧 Owner 派发调用；`ActiveOrderIndexBenchmark` 的清理检查改用现有 primitive API。新增 `MatcherTreasuryReceiptBenchmarkTest`，以 1/4 个 Lane 各执行有限次结算、复用事件并在关闭时核对 Treasury；2 项功能用例通过，详见 [D2 功能验证](../../docs/validation/d2-complete-20261015.json)。本轮未运行 JMH 计时、真实节点压测、快照或恢复，不给出性能结论。

D2 复审中，`MatcherTreasuryReceiptBenchmark` 移除发布模式标记，统一调用 `reserveMatcherPublication()` 后发布。1/4 Lane 的 2 项有限功能用例通过，纳入 [D2 复审的 158 项必要用例](../../docs/validation/d2-review-20261015.json)，没有运行计时压测。
