# Surprising Aeron 统一交易核心

本目录承载按产品线隔离的 Aeron Cluster 交易核心。每个 `ProductLine` 变体使用相同代码、独立 `clusterId`、
端口空间、Archive 和数据卷；一个逻辑 Core 固定由三个 Member 组成，并管理该变体全部 symbol。

W1/W2 已完成单一可执行盘口改造：`TradingCoreRuntime` 是 Core 单写边界，撮合命令只进入 fork 的
exchange-core 0.5.15-emporia；`TradingCoreState` 不再保存 `CoreBookState` 或任何 FIFO priority map。
matcher 恢复导入 Aeron 配对 snapshot 中的原生 `ME0/RE0`，通过 `fromSnapshotOnly` 启动，不逐单回放。

部署基线不按 margin mode 或热点 symbol 分 Core：CROSS 和 ISOLATED 都由同一个 ProductLine Core 裁决；
CROSS 只共享该 Core 内权益，ISOLATED 绑定 position identity。当前仅保留 `coreShardId=default` 和
`routeVersion=1` 的协议语义；字段按主规格 W1/W3 版本化落地，完成前不接受非默认路由，也不启用热点分片
或跨 Core 全仓余额共享。

## 模块

| 模块 | 职责 |
| --- | --- |
| `surprising-aeron-protocol` | schema v1 固定小端二进制 envelope、六线 wire code 和端口布局。 |
| `surprising-aeron-service` | `ClusteredService`、有界幂等状态、Snapshot 和节点启动器。 |
| `surprising-aeron-client` | Leader 自动发现、切换处理和“超时即结果未知”同步客户端。 |
| `surprising-aeron-exporter` | P5 可靠 Exporter 的最小 sink 边界。 |
| `surprising-aeron-tools` | Cluster 探针、状态 hash 查询和只读离线 replay 诊断；不得作为生产恢复或 snapshot 来源。 |

## 本地构建与三节点运行

使用 JDK 25 构建：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="${JAVA_HOME}/bin:${PATH}"
mvn -pl :surprising-aeron-client,:surprising-aeron-tools -am test
```

三节点部署与验证入口正在重新整理。一次只启动一条产品线；删除 Archive 或数据卷前必须先确认目标产品线，
任何迁移工具只能在检测到既有状态时中止，不能自动删除状态。

## 协议约束

- Cluster Log 权威消息禁止 Java serialization 和无版本 JSON。
- v1 header 固定包含 `commandId`、`productLine`、`source`、`sourceId`、`sourceSequence`、`userId`、
  外部提交时间和 `correlationId`。
- Instrument Provider 通过版本化 `UpsertInstrumentCommand` 下发保证金率、risk brackets、最大杠杆和
  最大持仓名义价值；CoreInstrumentState 是运行时唯一参数副本，Risk Provider 只能查询 Core 快照。
- exchange-core 0.5.15-emporia 独占价格树/FIFO；`GTX` 使用原生 post-only 语义，外层不得查 book 后模拟。
  Core 的 `CoreOrderState` 只保存业务元数据和活动状态，不保存可重建 FIFO 的 priority sequence。
- adapter 固定使用 `RiskProcessingMode.MATCHING_ONLY` 并禁用 exchange-core margin trading；内部 user/symbol/risk module 是需随 matcher snapshot 恢复的技术状态，不是业务资金、持仓或保证金权威。
- Core owner 线程只提交 exchange-core 异步命令；撮合结果通过 Cluster timer continuation 按序回到 owner，普通下单、撤单、改单、强平、结算和标记价触发子单均不在 owner 线程等待 ring future。adapter 不再提供同步交易入口；恢复和离线工具也通过显式异步 continuation drain 完成。
- 强平和交割/行权结算的订单撤销均按确定性 cursor 分批执行，单个 Core 命令最多处理 1,024 笔订单；强平 provider
  通过一个 `EXECUTE_LIQUIDATION_BATCH` 同时提交有序 action 和可选 Risk Scan continuation，订单阶段完成后才推进用户阶段。
  每个批次共享最多 `1024` 笔撤单预算，Core 以 `nextCursorOrderId` 保存独占下一页位置。
  生命周期进度保存在 Core 状态中，但 pending matcher continuation 不写入 snapshot。matcher 异常、超时、
  malformed result 或 Core/matcher 分歧直接抛出 `FatalMatchingDivergenceException`，Cluster Member 失败关闭，
  不 rebuild、不 retry、不 resubmit；生命周期期间同 symbol 的普通订单被拒绝，其他 symbol 仍可提交。
- exchange-core 的异步提交按 symbol lane 串行，同一 symbol 保序、不同 symbol 重叠，snapshot/hash/settlement 等全局操作使用 barrier；
  当前物理 matcher 保持 `matchingEnginesNum(1)`，lane dispatcher 使用 adapter 自有线程，不依赖公共 ForkJoinPool。

生命周期批量协议使用 `EXECUTE_LIQUIDATION_BATCH` wire code 43。`CoreLiquidationWorkCodec` 返回 action 的
`ORDERED` cursor 和精确 Risk Scan token；`CoreLiquidationBatchResultCodec` 返回 offered/applied/pending/obsolete/
processed 计数。`LIFECYCLE_IN_PROGRESS` 明确拒绝重叠生命周期。旧的单 action 强平和 `CONTINUE_RISK_SCAN` 命令仅保留给直接工具，
正常 provider 周期不调用它们。

| 生产者 / Core 组合 | 结果 |
| --- | --- |
| 新 Work v2 + 新 Core | 支持批量 action、共享 1,024 撤单预算和 cursor 恢复 |
| 新 Work v2 / batch producer + 旧 Core | 版本校验失败并拒绝，不按旧 payload 猜测解码 |
| 旧单 action producer + 新 Core | 仅在直接工具路径使用旧协议；provider 正常维护周期不走该路径 |
| 旧单 action producer + batch-only provider | 不支持，必须升级 provider 后再启用维护周期 |
- 下单、撮合、资金、风险、强平和生命周期热路径不访问 JDBC、Redis、Kafka 或 HTTP；这些系统只做输入桥、
  异步导出、投影、审计和查询。
- 幂等结果窗口有界；窗口外仍用 `(source, sourceId)` 的序列高水位阻止旧命令再次执行。
- 同步调用超时表示结果未知。调用方必须复用同一 `commandId` 查询或重试，不能生成新 ID。
- `SurprisingAeronClient` 串行提交消息；Gateway 后续通过固定数量的单线程 client agent 扩展吞吐。

## W1/W2 原生快照契约

- fork 坐标为 `exchange.core2:exchange-core:0.5.15-emporia`，Git SHA
  `627ddf68fbb0594b07e4b59a1a0e3377354e26b9`，可复现 JAR SHA-256
  `09e324685e9ae77244939c9f8c4044dc00dda4f03b98b60ff5d48f7e051e2d21`；fork 只允许 clean
  worktree 构建，从该提交的不可变 `git archive` 编译，并在 JAR 生成后重新认证仓库和内嵌 SHA；
  service 的 Maven `validate` 同时校验 provenance 与整包 hash。
  开放订单报告和 Core 对账均为 O(活动订单数)，不做排序。
- `CoreState v6` 是唯一外层快照，配对保存 Core 状态和 exchange-core 的 `MATCHING_ENGINE_ROUTER/0`、
  `RISK_ENGINE/0`；`TradingState v19` 不含盘口。三个 Member 必须运行完全相同的 fork、配置和 schema。
- capture 在 `SymbolMatchingLanes.barrier` 内等待全部 lane 和 callback；pending matching 存在时拒绝发布。
- Aeron fragment 在复制前执行 64 MiB 外层上限；matcher envelope 为 48 MiB、单个原生 module 为 32 MiB，
  超限时 fail closed。修改这些上限必须同步默认 heap 并完成目标活动订单规模的快照容量测试。
  恢复先校验三层 CRC32C、产品线、默认 shard/route、fork/config、symbol/user registry、完整 engine/book hash，
  再以 O(活动订单数) 一次报告逐字段核对 OPEN 订单，全部通过后才允许服务启动完成。
- snapshot、恢复、异步 continuation 的任何不确定失败都走失败关闭路径；没有 clean-start fallback、订单回放、
  隐藏 FIFO、matcher journal 或跨 Member 部分恢复。
- 由于 v6/v19 不兼容未发布的 v5/v18，首次切换必须使用全新 Cluster 并保留旧二进制和旧 Archive 供诊断。
  v6 接受命令后禁止二进制回滚读取新状态，只能用固定制品向前恢复。
