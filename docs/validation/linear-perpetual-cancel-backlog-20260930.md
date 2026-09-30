# U 本位永续 Core 积压与批量撤单续步（2026-09-30）

## 现场

服务器 `surprising-ex` 在恢复 Aeron 日志时，Core 连续多次停在已处理约 451,557 条命令的位置。2026-09-30 04:15:35 CEST 的运行诊断显示：Owner 队首为序号 1,094,129 的 `CANCEL_ORDER_BATCH`，生命周期 `SUBMITTED`，等待 `MATCHER_RESULT`；Matcher 提交、完成队列均为 0，Account Lane 控制任务也没有未完成项。随后 Owner 入口和 Cluster Service 入口各积压 8,192 条命令，04:16:00 CEST 由 30 秒边界超时终止。

同一时刻的堆快照显示，这个 9 项撤单批次已处理到索引 7，前一段处理到索引 6；批次仍处于提交状态，但其 shard 首次提交占位已经释放（`submissionShards[head] = -1`），Matcher 结果槽为空，Matcher 线程处于空闲等待。此前线程采样出现过风控扫描，不足以解释最终卡点；以队首命令和各执行队列的同一时刻状态为准。

## 原因与处理

`OrderBatchExecutor.startOrderBatchItem` 在前段完成后继续校验、提交下一项。`CoreMatchingFlow.submitMatchingInCommandScope` 对续段仍调用 `MatchingPipelineProgress.submissionDeferred`；后者检查已经释放的首次 shard 占位，返回延迟提交。于是下一段根本没有进入 Matcher，Owner 却持续等待它的结果。队首无法退休，两个有界入口队列逐级填满。队列长度是结果，内存和 CPU 资源不足不是本次卡点。

首次修正让批次越过原卡点（恢复进度由 451,557 增至 560,263 条），随后暴露第二个顺序错误：一个 `PLACE` 的原生 Matcher 序号比 Owner 已应用序号落后 9。批次首次提交过早释放 shard 占位，后续命令可以在剩余撤单片段之前执行；只补投续段会让原生序号与有序提交顺序分叉。

最终修正位于 `CoreMatchingFlow`、`MatchingPipelineProgress` 和 `OrderBatchExecutor`：已开始提交的批次在游标进入后续项时继续投递后续片段；尚有未投递片段时保留首次 shard 的提交占位，并只挡住后续片段可能使用的其它 shard。全部片段已投递后释放占位并唤醒等待命令，首次提交生命周期标记只写一次。保留占位后，Owner 曾在已提交的批次准入分支无条件循环；现在清除本轮唤醒位并返回有序提交循环。其它 shard 上无关批次仍可并行派发。每项校验、Matcher 结果、Account Lane 结算和 `OrderedCommitCoordinator` 的有序终态保持原有业务路径。

## 验证与清理

HotSpot JDK 27 在服务器完成 `mvn -pl surprising-aeron-core/surprising-aeron-service -am package -DskipTests -q`。本地运行 `mvn -pl surprising-aeron-core/surprising-aeron-service -am test -q`；Core service 的 96 个测试类共 958 项，956 通过、2 个按原有条件跳过，失败和错误均为 0。新增了 9 项异步批量撤单跨缺失订单续段的回归用例；六条产品线独立 shard 的并行派发场景也通过。`git diff --check` 通过。修复候选版本于 2026-09-30 05:07 CEST 启动，05:22:33 Core Cluster 就绪，05:28:37 六个 jar 均就绪，启动脚本给出 `PRODUCT_LINE_RUNTIME=PASS`。BTC 的 1 分钟 K 线已推进到 05:30，05:31 之前 10 分钟的 K 线覆盖 20 个币对。Maker 的“标记价尚不可用”告警只出现在 05:28 启动阶段，之后未继续出现。这里只确认 U 本位永续单节点恢复与行情推进；其它五条产品线及完整资金守恒路径没有在本轮运行。

05:33:54 CEST 再次确认六个 jar 存活，U 本位永续服务为 `active`，最近 10 分钟的 K 线仍覆盖 20 个币对，最新开盘时间推进到 05:32。此时运行的是修复候选版本；随后完整 Core 模块测试发现它阻塞了六条产品线上的独立批次。调整为仅阻塞剩余批次涉及的 shard 后，完整 Core 模块测试通过，最终版本于 05:51:21 CEST 在服务器重新构建并启动。按用户后续指令，于 06:01 CEST 停止重放，未等待该轮恢复结束。`tenant-demo` 两个服务均为 `inactive`。现场证据采集使用的临时 JFR、线程转储、堆转储、采集脚本与进程，以及本机 MAT 解析文件均已清理；服务器上的本轮临时目录 `/var/lib/surprising/linear-perpetual/incident-20260930` 仅保留为本记录中的历史定位路径。

## 测试环境清理历史业务数据

按用户要求不再重放旧 Core 状态。停止服务后，在 `surprising_exchange` 中清空交易、资金流水、Core 投影、资金费、行情、K 线、做市运行记录和临时认证会话等业务表，并清除本轮 U 本位永续的 Aeron Cluster、K 线 Kafka Streams、本地成交导出状态及 Valkey 业务缓存。清理前后均确认 `gateway_users=22`、`instruments=1151`、`assets=610`，用户和产品配置未变；清理后 `candlestick_candles=0`、`core_order_projection=0`。Kafka 保留 topic，清除 U 本位永续业务 topic 及 K 线 changelog 共 427 个分区的消息；检查每个分区的最早与最新 offset 相等。K 线 changelog 的 `cleanup.policy` 在清理后恢复为原有的 `compact`。全局产品配置事件 topic `surprising.instrument.events.v1` 保留，供配置同步使用。Valkey 中仅移除本产品线的实时路由、持仓与 ADL 缓存 55 个键，保留其它 3 个键。

从空 Core 状态启动后，已通过产品线内部余额调整命令向保留的测试用户（ID 1）及 21 个做市用户（ID 2–22）分别注入 100,000 USDT，22 次请求均返回成功；每次使用独立的重置引用号，资金由 Core 命令应用，没有直接写数据库余额。

清理 Valkey 缓存后于 06:14:45 CEST 重新启动，06:14:58 Core Cluster 就绪，无旧日志重放等待；06:20:26 六个 jar 均就绪，`PRODUCT_LINE_RUNTIME=PASS`，systemd 为 `active`。06:23:33 确认 20 个币对均产生新 K 线，最新开盘时间为 06:22。BCH 初期因标记价尚不可用而短暂拒绝报价，之后标记价更新，做市记录出现 `TRADE_EXECUTED` 和 `CYCLE_SUCCESS`。本次空状态启动不验证旧订单、旧余额或旧持仓的恢复；还需跨分钟观察新业务状态持续推进。
