# 优化与过度设计整改核查报告（2026-10-10）

本报告逐项核查 `OPTIMIZATION_AND_OVERENGINEERING_ANALYSIS.md` 第一至第七节，并交叉检查两份阶段验收报告和 `PERFORMANCE_VALIDATION.md`。结论是：**若干代码优化已经落地，但“阶段一、二、三全部完成且已通过验收”的结论不成立。最新源码的基准模块无法编译，最新针对性回归仍有 7 项失败或错误，尚不能把旧压测成绩归到当前实现。**

## 1. 核查基线与状态定义

| 项目 | 本轮基线 |
| --- | --- |
| 源码时间 | 2026-10-10 12:18:23（北京时间）；初轮 11:57:43，12:13:38 与 12:18:23 分别跟进工作区变化并复测 |
| 分支 / HEAD | `master` / `e54341b9be0a1ec4aedb873b11e74597a9e07503` |
| 实际对象 | HEAD 加当时工作区未提交、已暂存删除及相关未跟踪文件；不是只检查 HEAD |
| 隔离方式 | 复制源文件到本轮独立目录，重新编译和执行测试；不使用工作区旧 `target/` 作为本轮产物 |
| 源码指纹 | 797 个核心源码、构建文件及相关报告 SHA-256，见同名 JSON 的 `sourceManifest` |
| 工具 | Corretto HotSpot JDK 27.0.0（27+33-FR）、Maven 3.9.16 |
| 当前机器 | Intel Core i9-9880H / x86_64 / macOS 26.7.2；不能假定阶段报告中的 Apple Silicon 成绩可直接比较 |
| 索引使用 | 使用 CodeGraph 定位结构；索引存在待同步修改，最终实现和行号以隔离源码为准 |
| 本轮边界 | 核心协议、客户端热日志、Owner、Matcher、账户 Lane、回滚、提交投影、基准及相关测试 |
| 未执行 | 测试服务器部署、线上采样、全产品线端到端验收、最新源码性能压测、手机/Web 功能测试 |

“已完成”表示相应代码目标已经实现；“部分完成”表示仅有局部替代或优化；“未完成”表示原目标仍未实现；“未验证”表示缺少同一源码、可复现的有效验收证据。**代码完成不等于功能验收或性能验收完成。** 文档中互为替代的路径 A / B 单独列示，不要求两条同时实施，也不按拆分条目计算虚假的完成百分比。

这是一次检查和报告交付，没有修复业务代码。下列异常来自隔离源码测试，不能直接推断测试服务器已经出现资金损失或线上故障。

## 2. 逐项整改清单：完成情况、当前收益、剩余工作

### 2.1 阶段一

| 编号 / 原目标 | 当前状态 | 源码依据与当前收益 | 未完成内容 / 验收要求 |
| --- | --- | --- | --- |
| A1 移除 Owner 每命令 `core.start/end` 同步日志 | **已完成代码**；追踪完整性待验收 | `TradingCoreOwner` 已移除这些日志调用及相应记录方法。减少这些调用的格式化、日志队列或输出成本；不能等同于全部业务 I/O 已消失 | 保留 traceId 传播，补齐可检索的开始、结束、失败记录或有界异步事件；对错误路径和生命周期做追踪验收。删除日志本身不等于满足原来的全链路追踪要求 |
| A1a 客户端每命令 Aeron 日志及计时 | **已完成代码**（补充项） | `AeronClientPool` 删除逐请求日志、`traceStartedNanos` 及 `traceEnd` 等逻辑；减少对应日志调用和计时状态。`TraceScope`、传输边界记录仍保留 | 不应宣称所有 `nanoTime()` 都已删除；超时与传输统计仍需要计时。确保成功、拒绝、超时能按相同 traceId 关联 |
| A2 跳过终态订单的 Owner 镜像插入、删除 | **已完成指定镜像路径的代码**；正确性未通过验收 | 四张镜像已整体删除，故该镜像上的 put/remove 不再发生。`MatcherSettlementChanges` 在实际移除订单返回非空时记录路由变更 | Lane 权威订单表、路由、预留和终态事实仍必须正确变更。历史 65.04% 是一组删除未命中比例，不是端到端成本降低比例；不能报告“吞吐因此提升 65%” |
| A3 消除提交序列号的 `ByteBuffer.allocate(8)` | **部分完成** | `RealtimeStateCapture.encodeLittleEndianLong` 直接编码，去掉该序列号路径的 `HeapByteBuffer` 包装对象；仍每次 `new byte[8]` | 其他捕获路径仍有 `ByteBuffer.allocate`。若继续复用数组，必须明确消息持有期和消费者完成条件；不能用一个可变静态数组承载同时在途的不同序列号 |
| A4 冲突检测从反向扫描改为 O(1) | **用户冲突查询已完成代码**；专项验收不足 | `ClusterCommandWindow.userSlots` 记录用户最近槽位，`conflictingPrefixSize` 用户部分由 O(窗口大小) 扫描变成哈希查询的平均 O(1) | 订单冲突部分仍遍历当前命令涉及的订单，整个方法不保证严格 O(1)。用户在途“数量”不能直接代替“最后冲突槽位”。需覆盖环绕、同用户多命令、部分出队、clear、批量订单冲突 |

阶段一原承诺“吞吐增加 30%～40%、P99 毛刺消除”：**最新源码没有有效前后对照，收益百分比未验证。** 当前可确认的是若干日志调用、镜像哈希操作、包装对象和窗口扫描已减少。

### 2.2 阶段二

| 编号 / 原目标 | 当前状态 | 源码依据与当前收益 | 未完成内容 / 验收要求 |
| --- | --- | --- | --- |
| B1 真正的 DirectBuffer 零拷贝解码 | **部分完成；零拷贝目标未完成** | `CoreMessageFlyweightDecoder` 直接读取部分头字段、复用空 payload，trace 解码方式有调整；`CoreMessage.owned` 可避免再次防御性复制，但该机制在 HEAD 已存在，不全是此次新增收益 | 仍分配 UUID、trace 的 char[] / String、payload byte[]、header 和消息对象，并复制 payload。Owner 有异步入队，必须先解决 Aeron 输入缓冲区的有效期，不能简单保存 DirectBuffer 引用 |
| B2 币对字符串改为数值 symbolId / 固定编码 | **部分完成** | `TradingCommandCodec` 引入 256 槽符号缓存，命中时复用 instrument 字符串；降低重复币对解码的 String 分配 | 线协议仍携带字符串；缓存未命中、冲突替换仍创建字节数组、String、缓存项，订单命令对象和其他字符串仍分配。数值 symbolId 的协议与注册、恢复一致性未实现 |
| B3 删除全部 Undo-log 与 12 个全局 Map | **未完成**；原建议需修正后实施 | `RuntimeGlobalRollback` 的 12 个 `ConcurrentHashMap` 仍在；`RuntimeAccountRollback` 仍保留。当前优化未消除整个回滚框架 | 先区分可预校验的正常拒绝、变更中失败、部分批次成功及撮合不可逆分歧。日志重放不能替代运行中一次拒绝的原子性；不得直接删除后继续处理脏状态 |
| B3a 去掉 `Before` 等包装分配 | **部分完成** | 全局 `Before` 包装类被 Object 加空值哨兵替代；部分账户前像改为可复用 primitive 捕获，降低相应包装成本 | `PatchOrderBefore`、`PatchReservationBefore` 等创建仍在，Map 装箱、前像复制与散列表维护仍在。`containsKey` 加 `putIfAbsent` 并非一次查找；“回滚零分配”不成立 |
| B4 投影每 64 条批次化或迁到异步 Projection | **未完成原方案**；已有命令内合并 | `CommitPublication` 有 defer/dirty 与命令内完成边界；`publish()` 仍在有变更的提交时执行 `factIndexes.applyCurrent`，不是独立 64 条命令合并。该文件在本轮基线相对 HEAD 未变化 | 若实施跨命令批次化，必须同步定义查询可见水位、资金/订单响应时机、快照和恢复边界。不能把已有命令内合并计为本轮新实现，也没有“投影开销下降 80%”的新证据 |

阶段二原承诺“内存分配下降 80% 以上、稳态超过 50 万 ops/s”：**最新源码未验证。** 字符串缓存命中、少部分包装删除是真实局部收益；与整链路零分配、整体 GC 消失有明显距离。

### 2.3 阶段三

| 编号 / 原目标 | 当前状态 | 源码依据与当前收益 | 未完成内容 / 验收要求 |
| --- | --- | --- | --- |
| C1 账户事实以 `AccountLaneState` 为权威 | **指定账户状态已完成代码**；所有权检查有缺口 | users/orders/reservations/positions 直接保存在账户 Lane，Owner 不再维护这四类完整发布镜像；减少一份完整状态维护 | `AccountLaneState.bindOwner/assertOwner` 已变成空方法；`TradingRuntimeState.assertOwner` 在 Lane scope 非空时允许通过。需要证明所有变更由 Owner 串行执行，并恢复跨线程误用的拒绝能力 |
| C2 删除四张 `published*` 镜像 | **已完成生产代码**；基准未同步 | `publishedUsers`、`publishedOrders`、`publishedReservations`、`publishedPositions` 以及 `LanePublishedMap` 已删除；消除指定表的重复更新、复制和容量占用 | 基准仍引用被删除字段而无法编译。还有算法单、触发单、清算、风险快照的发布表；它们不属于本条四张表，但“系统完全没有镜像”不能成立 |
| C3 查询下沉 Lane | **以 Owner 内联方式完成直接访问**；不是 Lane 线程执行 | `onLane()` 直接进入 Lane scope，不创建任务、不等待 `task.await()`，账户 Lane 无后台工作线程；用户查询可按用户路由直接访问 | 无 Lane scope 的 order/reservation/position 查询仍有逐 Lane 查找，约 O(Lane 数量)，不能称“完全消除跨 Lane 探测”。区分合法 Owner 全局查询与非法 Lane 内跨界访问；避免增加第二套事实表 |
| C4 Owner 只负责定序、水位，不做业务 | **未按原文完成**；目标应重述 | Owner 现在执行账户变更、结算、投影、事实发布，并非仅定序 | 这与路径 A 的 Owner 承担业务目标本身存在冲突。应明确“Owner 是账户事实单写者，Matcher 负责独立撮合”的当前边界，而不是同时承诺两种职责描述 |
| C5 路径 A：Cluster / Owner 撮合记账全内联 | **部分完成** | `SettlementLaneWorker` 已删除，账户执行统一内联；按原来配置的 L 个账户 Lane，可减少 L 个账户工作线程（原 4 个配置对应减少 4 个）；去掉对应派发与等待 | `TradingOwnerLoop` 仍有独立 Owner 线程及 ingress 队列，`MatcherCommandPipeline` 仍创建独立 Matcher 线程，结果仍回到 Cluster Egress。因此没有实现单个 Cluster 回调线程完成全部撮合、记账、回包 |
| C6 路径 B：按 Account / Symbol 切物理 Cluster | **未实施；可选替代方案** | 当前有产品线隔离及内部分片，不等于 Account / Symbol 独立物理 Cluster | 仅在容量和隔离需求证明必要时考虑；先处理跨币对保证金、跨账户成交和全局资金事实。它不是路径 A 实施后仍必须补做的“欠项” |

**阶段三第一点的直接回答：四张账户镜像删除和账户事实直接访问已经做到；整个第一点不能标为“完整验收通过”。** Owner 职责与原文有出入，线程所有权保护、旧基准和相关失败测试还需要整改；本轮最新触发单及 revision 针对性测试已通过，但没有完整跨产品线恢复验收。

当前线程路径为：

```text
Cluster 回调 → ingress 队列 → Owner → Matcher 线程 → Owner 账户 Lane 内联应用
                                               → 事实/投影提交 → Cluster Egress
```

这里保留了 Matcher 与 Owner 的线程边界。`SINGLE_WRITER_INLINE=true` 只描述账户执行，不证明全交易链路单线程或零跨核等待。

### 2.4 文档正文中的其他问题

| 编号 / 正文问题 | 当前状态 | 当前收益 / 仍需整改 |
| --- | --- | --- |
| D1 重放双写与 RTO | **部分原因已消除；RTO 收益未验证** | 删除四张账户镜像可减少其重放维护；回滚结构和其他发布表仍在。旧实现三组 Archive 重放/快照恢复通过，只能说明当时一致性，未证明当前恢复耗时降低 |
| D2 精细调度规则 | **未完成精简** | `admissionsSinceHeadCheck` 的 8 次检查、matching notification、冲突前缀与提交控制仍在。A4 只优化用户依赖查找，不能把整个调度器认作已简化；需实测具体分支的成本和顺序作用 |
| D3 撮合适配层多重包装 | **未完成整体收敛** | 原协议、解析、撮合输入、结果、结算事件、变更缓冲边界仍存在；部分直接 native 结果路径并不能证明所有包装消失。应按实际分配记录删透传层，保留产品语义、不可变交接和外部核心适配边界 |
| D4 五层命令容器 | **仍存在；是否冗余未证明** | `PendingClusterIngress`、`ClusterCommandWindow`、`PendingMatchingRing`、`CommandSlotRing`、`PendingCommandIdIndex` 仍承担不同阶段与检索作用。用户在途数量与窗口冲突槽位不同；“数量为五”不是删除依据，应检查同一事实是否重复、容量是否一致、清理是否完整 |
| D5 Owner park/unpark | **账户同步查询路径已消除；全局绝无等待未实现** | 最新 `onLane()` 无任务 await；生命周期 join、匹配完成等待及背压控制仍有等待逻辑，不能一概当作无用阻塞。需要按热路径、空闲策略、关闭/恢复边界分类 |
| D6 高分配率、GC 与尾延迟 | **未完成最新量化验收** | 多处可见分配仍存在。旧 JFR 有高分配证据，但本轮没有当前实现 B/op、GC 暂停和业务延迟的同源码测量，无法证明原问题已消失 |
| D7 “全部完成、1049 测试全通过”结论 | **当前不成立** | 本轮重新编译基准失败，187 项针对性测试有 1 failure、6 error、1 skipped；旧全量结果不能继承为当前源码验收。详见第 4、5 节 |

## 3. 原文全覆盖索引

同一问题在正文、阶段方案和总结中重复出现，已归并核查；下表防止只检查三个阶段而漏掉原始问题。

| 原文位置 | 对应本报告 |
| --- | --- |
| 一.1 线程跳跃 | C3、C5、当前线程路径 |
| 一.2 Park/Unpark | C3、D5、所有权整改 |
| 一.3 DirectBuffer 堆拷贝 | B1、B2、D6 |
| 一.4 恢复 RTO | B3、C2、D1、历史恢复证据 |
| 二 伪并发、状态双写与 68.55% | C1～C5、历史比例纠正 |
| 三.1 回滚框架 | B3、B3a、资金失败测试 |
| 三.2 调度规则 | A4、D2 |
| 三.3 适配包装 | D3 |
| 四.1 终态增删 | A2、65.04% 比例纠正 |
| 四.2 五层在途容器 | A4、D4 |
| 五.1 Flyweight 分配 | B1 |
| 五.2 字符串 | B2 |
| 五.3 ByteBuffer | A3 |
| 五.4 260 MB/s 与 P99 | D6、历史采样纠正 |
| 六.阶段一全部四项与收益 | A1～A4、阶段一收益说明 |
| 六.阶段二全部三项与收益 | B1～B4、阶段二收益说明 |
| 六.阶段三两项及 A/B | C1～C6、LMAX 结论纠正 |
| 七.全面完成与所有测试/压测声明 | D7、第 4、5 节 |

## 4. 本轮新编译、新测试结果

### 4.1 构建：核心依赖编译通过，基准模块失败

在隔离源目录使用 JDK 27、Maven 离线构建，未复用旧 target：

```sh
mvn -o -f <isolated-source>/pom.xml \
  -pl surprising-aeron-core/surprising-aeron-benchmarks -am \
  -DskipTests compile
```

最新构建完成时间 `2026-10-10T12:20:30+08:00`，退出码 1，耗时 26.544 秒；变更服务源码已在复测阶段重新编译。初轮构建完成于 11:58:10，耗时 24.119 秒，同样在这两处字段失败。服务及其上游参与模块编译通过，但这不是整个仓库全部模块编译通过。

`surprising-aeron-benchmarks/src/main/java/com/surprising/aeron/service/state/OwnerPublicationBenchmark.java`：

| 行号 | 编译错误 | 整改 |
| --- | --- | --- |
| 65 | 引用已删除 `publishedUsers` | 重写为测量当前账户权威状态或当前提交职责的基准；不恢复冗余表来迁就旧基准 |
| 96 | 引用已删除 `publishedPositions` | 同上，核对所有旧发布路径基准的实际被测工作 |

**这是性能验收的直接阻塞项。** 在基准构建恢复、业务回归通过之前，本轮不继续生成一个不可归因的性能成绩。

### 4.2 最新针对性测试：187 项，179 通过，7 异常，1 跳过

```sh
mvn -o -f <isolated-source>/pom.xml \
  -pl surprising-aeron-core/surprising-aeron-service -am \
  -Dtest=TradingCommandCodecTest,CoreMessageCodecTest,CoreMessageFlyweightDecoderTest,LaneEntityMembershipTest,TradingRuntimeStateTest,CoreOrderedOrderBatchTest,CoreMatchingStateTest,MatcherSettlementPlanTest,TriggerOrderIndexTest,ControlLaneDispatcherTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

最新结束时间 `2026-10-10T12:19:49+08:00`，退出码 1，耗时约 84 秒，实际执行 **10 个 suite**。窗口相关测试应定位到实际存在的 `ClusterCommandPipelineTest` 等用例，本轮未执行，不能声称该专项验收通过。

初轮 11:57:43 源码执行 184 项，7 failure、10 error、1 skipped；其指定名单中的 `ClusterCommandWindowTest` 没有对应 suite。随后工作区三文件变更：`ControlLaneDispatcher` 恢复 pending/collect 及延迟 revision；`LaneCommitEvent` 把触发单取消移到完成收集；`TradingRuntimeState` 调整订单/预留直接引用与查询可见性。检查这些改动并复测后，原 17 项异常有 10 项不再出现，保留 7 项，另出现 1 项 `control Lane work is unfinished`。第二轮总计 187 项、1 failure / 7 error / 1 skipped。之后两个文件再次调整：控制命令改为同步执行的 `active` 标记、`pending()` 恒 false，revision 仍延迟到 collect；12:18:23 快照第三次复测，`control Lane work is unfinished` 不再出现，剩余 7 项异常。三轮原始摘要均保存在 JSON，避免将已通过的预留回滚、触发单、revision 或控制任务测试继续列为当前失败。

| Suite | Tests | Failure | Error | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `CoreMessageCodecTest` | 9 | 0 | 0 | 0 |
| `TradingCommandCodecTest` | 14 | 0 | 0 | 0 |
| `CoreMatchingStateTest` | 46 | 0 | 4 | 0 |
| `CoreMessageFlyweightDecoderTest` | 2 | 0 | 0 | 0 |
| `CoreOrderedOrderBatchTest` | 32 | 1 | 1 | 1 |
| `ControlLaneDispatcherTest` | 3 | 0 | 0 | 0 |
| `LaneEntityMembershipTest` | 3 | 0 | 0 | 0 |
| `MatcherSettlementPlanTest` | 11 | 0 | 1 | 0 |
| `TradingRuntimeStateTest` | 62 | 0 | 0 | 0 |
| `TriggerOrderIndexTest` | 5 | 0 | 0 | 0 |
| **合计** | **187** | **1** | **6** | **1** |

这些是最新一轮 XML 的重新汇总，没有使用工作区历史 surefire 报告。跳过的是 `singleCancelFlushesMatchingPhaseStatisticsAndReleasesFunds`，原因是未设置 `surprising.aeron.matching-phase-log-interval` 系统属性；该统计路径仍未在本轮执行验收。`LaneEntityMembershipTest` 本轮 3 项通过，不把旧报告中的失败重复算入当前结果。

### 4.3 失败逐项清单与整改方向

| # | Suite / 测试方法 | 本轮异常 | 整改方向 |
| --- | --- | --- | --- |
| 1 | `CoreMatchingStateTest.fiveMillionNotionalCanFormPositionsWhenTestCapitalAndDepthAreAvailable(ProductLine)[1]` | error：direct result is not published | 检查 direct result 发布、Owner 派发与结果消费顺序 |
| 2 | `CoreMatchingStateTest.fiveMillionNotionalCanFormPositionsWhenTestCapitalAndDepthAreAvailable(ProductLine)[2]` | error：direct result is not published | 检查 direct result 发布、Owner 派发与结果消费顺序 |
| 3 | `CoreMatchingStateTest.replaceLosesPriorityCanMatchAndRestoresToSameExchangeCoreHash` | error：direct result is not published | 检查 direct result 发布、Owner 派发与结果消费顺序 |
| 4 | `CoreMatchingStateTest.amendPatchReadsOriginalInsideCoreAndReturnsReplacement` | error：direct result is not published | 检查 direct result 发布、Owner 派发与结果消费顺序 |
| 5 | `CoreOrderedOrderBatchTest.spotAmendLaneFailureStopsTheBatchAndRecoversFromPriorSnapshotAndLog` | failure：Expecting actual throwable to be an instance of: com.surprising.aeron.service.exception.FatalMatchingDivergenceException but was: java.lang.ArithmeticException: long overflow at java.base/ja… | 核对溢出后的致命异常转换与恢复边界 |
| 6 | `CoreOrderedOrderBatchTest.amendPartialMatcherFailureMustFailStickyBeforeRecordingBusinessRejection` | error：Cannot invoke "com.surprising.aeron.service.state.OrderRuntime.status()" because the return value of "com.surprising.aeron.service.orchestration.TradingCoreRuntime.runtimeOrder(long)" is nul… | 核对部分撮合失败后原单可见性及不可逆分歧处理 |
| 7 | `MatcherSettlementPlanTest.directEventCannotRecycleBeforeReservedNonTradingLanesConsumeTheirTickets` | error：invalid direct settlement Lane queue | 重新明确内联模型的完成条件与事件回收时机 |

不同失败可能共有一个根因，不能据此认定存在 7 个独立业务 bug。也不能直接把它们全部定性为旧测试错误：

- `direct result is not published` 需要核对 Matcher 结果发布、Owner 结算派发及事件回收顺序；不能通过放宽未就绪结果读取解决。
- 当前失败涉及改单原单可见性、溢出之后 sticky fatal 等，需要恢复业务语义和资金不变量。预留回滚、部分批次收益、显式 Core/Matcher 分歧以及控制任务结束用例最新已通过，仍需后续扩大金融与恢复矩阵。
- 原队列票据/事件回收测试可能需要适配内联模型；适配时仍要证明一次消费、事件不能提前复用。触发单和 revision 的最新针对性测试已通过，不能据此继承旧模型的所有集成验收。
- 对每一项要给出“实现回归”或“测试假设已过时”的证据，并保留相同资金、终态及恢复要求；不能仅删断言或跳过测试。

### 4.4 验收范围的限制

本轮测试包含协议、改单/批量下单、状态及回滚、触发单、结算计划等受影响路径。没有执行完整 `ClusterCommandPipelineTest` 六产品线套件、benchmark 套件、最新源码的 Archive 恢复与主备一致性矩阵。编译和针对性回归已经给出阻塞结论；因此这些范围仍是待验收，不能称“全面通过”。

## 5. 历史性能证据能说明什么

### 5.1 最新入档压测：旧源码的有效功能诊断，无效容量验收

[`perpetual-current-20261010.json`](perpetual-current-20261010.json) 自身明确记录：

- 构建为 `ff5a03da` 加当时捕获的工作区修改，运行期间源码继续变化，**不是本轮最新源码**；后续记录提交 `e54341b9` 也不自动把这次构建变成 e543 或当前工作区。
- 8 轮工作负载通过、79 项针对性测试通过、3 组数据的实际 Archive 重放和快照恢复通过，只适用于该捕获版本。
- `formalPerformance = INVALID (host CPU throttling)`，`overall = PARTIAL_VALIDATION; superseded source scope, rerun pending current edits`。
- 主轮无 profiler，GC / JFR 轮单独运行；不能把不同轮的吞吐、分配和延迟拼成同一个成绩。

三轮主测试的历史观测如下，**不是当前实现的容量、收益或线上延迟**：

| 历史轮次 | 业务操作/秒 | Core 消息/秒 | 各业务类型 P99 的最大值 |
| --- | ---: | ---: | ---: |
| main1 | 252,686.452 | 24,180.537 | 169.082 ms |
| main2 | 265,113.117 | 25,364.524 | 124.583 ms |
| main3 | 289,176.367 | 27,656.452 | 43.220 ms |

三轮业务速率均值约 268,991.979 ops/s，最大最小差约均值的 13.6%。限频、30 秒预热未证明 JIT 稳定、闭环全局窗口 256、窗口等待约 86.5%～87.8% 均限制解释。P99 为提交到终态，缺少 accepted 分段；没有 Gateway / HTTP / Kafka / WebSocket 应用链路。这些数值**不能证明当前页面的推送延迟，也不能证明优化令 P99 稳定在微秒级**。

600 秒 soak 和有限 NMT/RSS 端点观测不能证明不存在泄漏。旧 JFR 仍包含四个账户 Lane 工作线程，也进一步说明不属于当前已删除 worker 的源码。历史 GC 存在，不能据“热点局部少了对象”推导整链路零 GC。

### 5.2 原文比例与测量单位的纠正

| 原文/阶段报告的说法 | 核查结论 |
| --- | --- |
| 镜像占 68.55%，删掉便得到同等总收益 | `PERFORMANCE_VALIDATION.md` 约 2900 行是样本内 Lane merge 的 118.073ms / 172.249ms，不是整个交易请求或全部 CPU 时间的 68.55%。额外计时探针也有成本；只能证明该局部路径当时值得优化 |
| “幻影插入再删除”，删除未命中 65.04% | 约 4187 行的 9,560 / 14,698 是一个删除样本。立即终态订单当时已可能不插入 Owner 表，因此 miss 不足以证明同一对象经历无用插入再删除；不能把 miss 比例变成 CPU 或吞吐收益 |
| Flyweight 导致全部 260 MB/s，直接摧毁 P99 | 约 4750 行汇总 Owner 61.668 + Matcher 56.717 + 4×约37.34 MB/s，是历史线程分配采样。它不等于解码器分配，不是归一化的 bytes/业务操作，也未单独证明这些分配与每个长尾的因果关系 |
| 2,493 万 / 2,559 万 decode ops/s 证明整链路零分配 | 是阶段报告列出的局部微基准声明；缺少可复核的同版本完整输出、fork/预热、分配 profiler 和样本输入说明。源码仍创建 payload、命令等对象，不足以支持“零分配” |
| 约 50 万吞吐、JMH batch ops/s 是同一指标 | 不同阶段、不同基准不可直接比较。`SingleWriterInlineBenchmark.fullWindowCommit` 一次调用处理 512 个业务操作；需分别看 JMH 调用次数、业务辅助计数器及 `OperationsPerInvocation` 配置，不能无条件按一个 Score 当订单/秒或再乘 512 |
| 投影维护下降 80% 以上 | 缺少当前源码前后数据；原独立 64 命令/异步投影方案未实现，已有命令内 defer 合并不等于新优化 |
| 五个边界×380ns，因此物理极限约 51 万 ops/s | 把串行单请求延迟倒数当流水线吞吐上限，忽略并行阶段、在途窗口、各阶段服务时间与资源竞争；推导不成立 |
| Cache 99.9%、调用 2～5ns、业务 20～50ns、单节点必达 100～200 万 | 没有当前 workload 的硬件计数器或分段实测依据。Aeron/LMAX 设计原则不为具体交易业务的耗时背书；数值不得用于容量规划 |
| 1049 项全部通过、核心全量无任何问题 | 阶段报告同时记录 skipped：1049 套件有 2 项跳过，276 pipeline / 301 benchmark 套件也出现跳过；即使历史 BUILD SUCCESS，也不是每个用例运行通过，更不能覆盖当前新源码 |
| 当前资金“绝对安全”、没有阻塞、没有积压 | 有限测试和历史压测不能证明绝对结论。最新改单与发布顺序等测试未通过，必须完成对应业务验收 |

### 5.3 Aeron / LMAX 方案本身的适用性

单写者有助于减少共享可变状态和复杂交接；本项目目前把账户 Lane 内联到 Owner，方向可以成立，但仍应由本项目的正确性和测量结果决定下一步。Aeron 要求业务逻辑确定性、避免阻塞式业务 I/O，并建议前置校验；它不意味着任意业务都能做到几十纳秒，也不证明日志重放可以替代所有运行时拒绝的原子性。[Aeron 官方业务逻辑指南](https://aeron.io/docs/aeron-cluster/efficient-business-logic/)

LMAX Disruptor 的核心业务单消费者模型也可搭配日志、复制等其他消费者；是否保留独立 Matcher，需要比较服务时间、尾延迟和所有权复杂度。Disruptor 的等待策略包含阻塞和 busy-spin 等选择，忙等需要 CPU 资源，不适合仅凭“任何 park 都灾难性”在资源有限的虚拟机上统一配置忙等。[LMAX Disruptor 官方指南](https://lmax-exchange.github.io/disruptor/user-guide/)

## 6. 本轮另外发现的整改项

| 优先级 | 问题与证据 | 推荐整改 | 验收要求 |
| --- | --- | --- | --- |
| P0 | 基准引用删除字段；隔离 compile 失败 | 同步删除/改写旧镜像基准，确保测量当前真实职责 | service、protocol、client、benchmarks 的直接依赖重新构建通过；不得依赖旧 jar |
| P0 | 新执行顺序下 direct result 未发布即被要求消费 | 检查 Matcher ready 与 Owner 派发/结算顺序、异常清理和对象复用 | 四项相关场景通过；未完成事件不得读取、提交或回收 |
| P0 | 改单异常传播、原单可见性未通过 | 按正常拒绝和不可逆分歧分别恢复原子性、保留先前已提交项、sticky fatal | 冻结/可用余额、预留两套索引、订单终态、Core/Matcher hash、快照日志重放一致 |
| P0 | Lane 所有权方法为空，Lane scope 可绕过 runtime Owner 检查 | 用实际 Owner 线程约束 Lane 访问；清晰限定 scope 入口，避免仅以 ThreadLocal 非空作为授权 | 非 Owner 线程进入/修改必须被拒绝；合法 Owner 查询和恢复交接仍通过。当前是静态保护缺口，尚未证明生产发生竞态 |
| P1 | 已删除镜像的 after-image 捕获还保留 | `OrderChangeBuffer` 私有字段数组仍在逐项复制订单值；`ReservationChangeBuffer.released/consumed` 仍写入/扩容，但没有读取路径。核对当前消费者后删掉失去用途的复制，而保留真实事件/恢复所需值 | 复用、终态、回滚和索引测试通过；JFR 验证捕获成本减少，不新增状态副本 |
| P1 | 老线程模型残留配置和状态 | `singleWriterInline` 字段可变但 getter 恒 true / setter 空；`publishMirrorEnabled` setter 空；`Object[] laneWorkers` 等占位残留。删除或准确重述实际可配置行为 | 配置不能表面生效实际忽略；指标区分逻辑 Lane 和真实线程，测试按真实模型执行 |
| P1 | 冷状态发布与触发单可见性待明确 | 触发单/算法单/清算/风险快照还有发布状态；初轮触发单及 revision 断言失败，三文件变更后相关专项测试已通过；跨产品线恢复验收仍未完成 | 以提交水位明确前后可见性，触发一次、删除一次、复用不重复、恢复后相同 |
| P1 | 热日志删除后请求生命周期检索缺口 | 保留已有 TraceScope/边界结构，在需要的开始、终态、失败处记录有界事件；正常日志低成本，不再每笔同步文件输出 | 同一 traceId 可定位入口、Matcher、结算、回包/超时；队满行为、错误保存与两天日志保留可验证 |
| P1 | 旧验收报告把范围和预期写成确定结果 | 本报告作为当前更正依据；后续刷新原验收报告时必须带源码指纹、命令、原始成绩和限制 | 不再使用“全部零分配/绝对安全/全量通过”覆盖未测范围 |
| P2 | 无法量化当前优化净收益 | 正确性修复后，进行同硬件、同源码、同参数的可复现前后对照 | 业务/消息速率、bytes/业务操作、P50/P99/P99.9、CPU、GC、队列等待分别记录 |
| P2 | 继续零拷贝、numeric symbol、调度/容器/包装简化 | 以新热点与对象归因为依据逐项实施；先解决缓冲区寿命、协议映射及提交可见性 | 每项都有受影响路径的正确性证明和局部/整链路测量，不用增加框架代替删掉无用工作 |

## 7. 建议整改与验收顺序

1. **先恢复可构建和正确性。** 同步旧基准，逐项处理第 4.3 节失败，明确 Owner 是账户唯一写者，检查事件结果发布、回收、回滚与终态语义。
2. **清理已经失去作用的代码。** 删除无读取用途的 after-image 捕获、空 setter、假开关及旧 worker 占位；保留索引、不可变跨线程事件和确定性恢复等真实边界。
3. **对当前固定版本进行扩大验收。** 覆盖六产品线相关账户与核心协议，现货冻结/解冻、衍生品持仓保证金、资金费、强平/ADL/保险、交割/行权边界；批量部分成功、重复命令、拒绝不改资金、致命分歧停止后恢复；Archive 重放、实际快照恢复及主备 hash 一致。报告标清各矩阵适用产品线。
4. **再测性能收益。** 固定提交加完整差异指纹；先检查 CPU 限频和争用，预热至稳定，多 fork；无 profiler 主轮与 GC/JFR 诊断分开。明确每个业务操作、Core 消息与 JMH 调用单位，避免 512 操作批次混算；记录流量模型、在途数、排队和 offered rate。对比相同硬件的基线，不拿旧机器或解码微基准替代整链路容量。
5. **按实测决定完整线程收敛。** 对比保留独立 Matcher 与 Owner 内联 Matcher 的具体成本；只有需求支持时才规划物理 Cluster 分区。配置等待策略要符合测试服务器 CPU 条件。

本轮没有修复运行时代码，不能把上述步骤标记为已执行。最新构建和回归仍有阻塞项，当前应停留在“代码优化已部分落地、验收未完成”。

## 8. 当前收益可以怎样报告

| 可以确认的收益 | 可以确认到什么程度 | 当前不能给出的数字 |
| --- | --- | --- |
| 删除四张账户完整镜像 | 指定表的双写、镜像复制及其容器不再存在 | 减少多少 MB、整链路快多少、RTO 缩短多少 |
| 删除账户工作线程 | 由原配置 L 个 worker 变为 0；原 4-Lane 配置可少 4 个线程，逻辑 Lane 数据分区仍保留 | CPU 利用率下降多少、跨核等待总量降低多少 |
| onLane 直接访问 | 不再创建此查询任务和等待它的 park/unpark | 所有请求零等待、所有回包延迟减少多少 |
| 用户窗口冲突哈希查询 | 此查询从窗口长度相关扫描变为平均 O(1) | 所有依赖检测严格 O(1)、总体吞吐提升比例 |
| 删除热日志调用 | 对应逐笔日志调用与部分计时状态不再执行 | 全业务零 I/O、完整追踪仍已验收、P99 毛刺消失 |
| 手写 8 字节序列编码 | 该路径少一个 ByteBuffer 包装；byte[8] 仍在 | 此捕获或全链路零分配 |
| 符号缓存与部分回滚包装简化 | 命中避免重复币对 String；全局 Before 包装删除 | 未命中零分配、整链路分配下降 80% |

**本轮可确认的是源码成本和结构变化；最新版本的净吞吐、净分配、P99 和恢复收益均尚无有效实测百分比。** 已完成项值得保留，但必须完成构建、资金语义与恢复验收后才能报告性能交付收益。

## 9. 关键源码入口与证据

下面均为本轮固定源码的路径/行号，后续修改可能移动行号；文件指纹可在 JSON 中核对。

| 主题 | 源码入口 |
| --- | --- |
| Owner 调度、日志 | [TradingCoreOwner](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/TradingCoreOwner.java#L140) |
| 客户端传输追踪 | [AeronClientPool](../../surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronClientPool.java) |
| 窗口最近用户槽位 | [ClusterCommandWindow](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/ClusterCommandWindow.java#L103) |
| Decoder payload/trace 分配 | [CoreMessageFlyweightDecoder](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/ingress/CoreMessageFlyweightDecoder.java#L48) |
| 符号缓存 | [TradingCommandCodec](../../surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/TradingCommandCodec.java#L21) |
| 序列号编码 | [RealtimeStateCapture](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/realtime/RealtimeStateCapture.java#L104) |
| 内联开关、onLane、Owner 检查 | [TradingRuntimeState](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/TradingRuntimeState.java#L170)，`onLane` 约 468 行，`assertOwner` 约 1791 行 |
| 账户权威事实与空 Owner 方法 | [AccountLaneState](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/AccountLaneState.java#L115) |
| Lane 变化发布 | [LaneCommitDelta](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/LaneCommitDelta.java#L171) |
| Matcher 就绪与结算事件 | [MatcherSettlementEvent](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/MatcherSettlementEvent.java#L521) |
| 独立 Owner / Matcher 线程 | [TradingOwnerLoop](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/TradingOwnerLoop.java#L67)、[MatcherCommandPipeline](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/matcher/MatcherCommandPipeline.java#L104) |
| 全局与账户回滚 | [RuntimeGlobalRollback](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimeGlobalRollback.java#L36)、[RuntimeAccountRollback](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/RuntimeAccountRollback.java) |
| 仍逐次维护事实索引 | [CommitPublication](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/orchestration/CommitPublication.java#L98) |
| 无读取用途的 primitive 捕获 | [OrderChangeBuffer](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/OrderChangeBuffer.java)、[ReservationChangeBuffer](../../surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/state/ReservationChangeBuffer.java) |
| 当前不能编译的旧基准 | [OwnerPublicationBenchmark](../../surprising-aeron-core/surprising-aeron-benchmarks/src/main/java/com/surprising/aeron/service/state/OwnerPublicationBenchmark.java#L65) |

结构化原始核查结果：[optimization-rectification-audit-20261010.json](optimization-rectification-audit-20261010.json)。包含源码 manifest、环境、构建错误、最新 10 个测试 suite 与 7 项异常堆栈摘要，以及前两轮 17 项 / 8 项异常记录、执行命令和历史数据的适用范围；这是追溯当前结论的依据。

归档前源码复核：2026-10-10T12:21:17.184286+08:00，797 个文件指纹与 12:18:23 捕获源码一致（漂移 0）。本轮隔离 Maven 任务全部退出；已清理本轮临时源码、构建产物及原始临时日志，结构化编译/测试证据和源码指纹保存在同名 JSON。未启动或停止服务器、钱包、外部集群、手机任务。
