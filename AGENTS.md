# AGENTS.md

Surprising-EX 是交易所后端核心项目。改动必须严谨，资金安全优先于交付速度。

## 项目边界

- 这是 Java / Maven 多模块项目，业务包括现货、永续、交割、期权。
- 保持现有架构统一：ProductLine、instrument、Kafka topic、账户、撮合、风控、WebSocket、结算等边界不要随意重构。
- 新逻辑优先沿用现有模块、事件模型、repository、outbox、Kafka topic 和 Maven 测试。
- 除非任务明确需要，交易后端测试不要启动 wallet 服务。
- 产品未上线，不要在代码逻辑里 fallback，legacy

## 六产品线规则

- 六条业务线必须隔离：现货、永续（U本位，币本位）、交割（U本位，币本位）、期权不能混用订单逻辑、账户类型、topic、instrument、风险模型。
- 永续需要重点验证资金费、标记价、强平、ADL、保险基金。止盈止损
- 交割需要重点验证到期结算、交割流水、持仓归零或结算后状态。止盈止损
- 期权需要重点验证权利金、行权、到期失效、买卖方权益和风险边界。止盈止损
- 现货需要重点验证买卖资产冻结、成交扣减、解冻和余额准确。止盈止损

## 设计约束：禁止过度设计

- 交易主链路只保留完成当前业务所必需的状态、索引和事件容器；不得为了抽象、未来扩展或“统一模型”增加重复的 Map/List/Set、全表扫描、排序、临时聚合、状态副本或逐命令快照。
- 每个容器都必须明确所有权、生命周期、写入方和读取方，并证明它处于必要边界；能直接消费不可变事件、使用 primitive 数组/集合或复用固定容量缓冲区时，不得改用 boxed 集合或动态临时集合。
- 排序、canonicalization、rolling hash、导出视图和快照物化只允许出现在协议、查询、恢复或持久化边界；不得把边界逻辑回流到 matcher、settlement 或 Account Lane 热路径。
- 新增数据结构或处理阶段前必须有调用方、协议、恢复或正确性需求证据，并通过受影响路径的 JMH/JFR 证明其分配和延迟成本可接受；没有证据就不实现，不为未发生的失败或未来场景预留复杂度。
- 代码评审应优先删除不必要的中间对象、转换层和线程任务；一次确定性的成交事件应直接交给对应 owner lane 串行应用，不能拆成无业务意义的额外任务、barrier 或 commit 阶段。

## 测试要求

- 后续所有 Java 构建、Maven 测试、集成测试、JMH 基准和性能采样必须统一使用 HotSpot JDK 25；执行前检查 `java -version` 和 `mvn -version`，确认实际 JVM 为 HotSpot 兼容实现。
- 禁止使用 OpenJ9 执行本项目的测试或性能采样，也不能把 OpenJ9 结果作为 smoke、对照或验收证据；当前环境没有 HotSpot JDK 25 时不得自动降级，必须明确记录环境阻塞。
- 任何影响交易主链路功能或性能的改动，包括下单、撤单、撮合、成交、冻结/解冻、持仓、风险检查、强平、资金费、ADL、保险基金、结算、命令编解码、Lane、Snapshot 或 Core Fact，都必须新增或更新能覆盖真实改动路径的 JMH 场景；不能只运行与改动无关的既有基准。
- 交易主链路改动完成后必须在 HotSpot JDK 25 上执行受影响产品线的 JMH 基准和 JFR（Java Flight Recorder）采样；详细口径遵循下方“交易主链路性能验收指标”。
- JMH/JFR 采样必须使用能触发实际业务逻辑的场景，并同时验证资金守恒、余额/持仓/冻结正确、订单生命周期终态和快照恢复。基准结果、JFR artifact 路径、已测产品线、未测范围及理由必须写入交付说明；缺少 JMH、JFR 或资金不变量证据时，不得宣称交易主链路改动验收完成。

### 性能验证档位与记录

- 后续只验证当前 `master` 代码，不再检出、构建或重跑旧版本进行性能对照，也不再将历史版本结果用于新一轮性能比较。下述记录中的“对照 commit”统一填写“不适用（仅验证当前 master）”；历史记录保持原样，验收依据为采集前锁定的通过阈值及正确性要求。
- 后续每次性能验证的 in-flight 必须固定为 `256`；不得采集、补跑、横向比较或以 `64`、`512`、`1024` 等其他 in-flight 档位形成 smoke、基线或验收结论。历史其他档位数据只能作为历史背景，不能与新的 `256 in-flight` 结果直接比较。
- 性能采集开始前，必须先在根目录固定文件 `PERFORMANCE_VALIDATION.md` 的当次记录中写明并锁定基准标准与测试场景。基准标准至少包含通过阈值、对照 commit、JDK/JVM、机器与 CPU、JVM 参数、GC、JMH/JFR 参数、预热/测量/冷却时长和数据有效性条件；测试场景至少包含产品线、业务动作及比例、负载模型与到达率、活跃用户、连接、symbol、Account Lane、matcher/risk engine、做市状态、资金与持仓初态以及快照恢复检查。
- 基准标准和测试场景一旦开始采集不得修改；如确需修改，必须终止该轮并新建一条记录，重新完成采集前定义。没有预先定义标准与场景的数据只能标记为诊断数据，不能作为性能验收结果。
- 所有性能验证结果只能按时间顺序追加到 `PERFORMANCE_VALIDATION.md`，不得分散记录到其他文件或覆盖、改写历史记录。每条记录必须包含采集时间、被测 git commit、对照 commit、修改点、预先定义的基准标准与场景、执行命令、全部采集指标、原始 artifact 路径与校验信息、问题/异常、未测范围、结论；失败和无效轮次也必须如实追加。

### 交易主链路性能验收指标

- 吞吐量统一使用明确单位，禁止只写含义不明的 TPS：
  - API 接入层报告 `requests/s`，并区分普通单、批量单、撤单、查询等请求类型。
  - Product Core 主指标使用 `terminal business ops/s`；一次下单、撤单、改单、触发执行或风险/结算业务动作各算一个 business operation，批量命令按 batch item 展开计数。
  - 同时报告 `terminal Core messages/s`、`fills/s` 或 `trades/s`；一个订单产生多笔 fill 时不能把 fill 数混入订单业务操作数。
  - 批量接口同时报告 `batches/s`、`items/s`、平均及最大 batch size，不能只用 batch 数放大或缩小吞吐结论。
  - 必须满足 `acceptedBusinessOperations == terminalBusinessOperations`、accepted/terminal Core messages 相等、两个 `unfinished*` 为零；同时报告最大及期末 backlog、拒绝率、错误率和超时率。
- 并发能力不能用 TPS 或 ops/s 代替，必须记录活跃用户数、并发连接数、固定 `256 in-flight`、活跃 symbol/产品线数量、Account Lane、matcher/risk engine 数量，以及 maker/taker、下单/撤单/成交/风险重操作比例。容量结论必须表述为“在 256 in-flight 及指定并发和负载组合下的持续终态 ops/s 与尾延迟”。
- 延迟必须按业务类型分别统计入口到 accepted、accepted 到 terminal、入口到 terminal 三段；至少报告 p50、p90、p95、p99、p99.9 和 max，并记录样本数、直方图区间、超时上限及时间单位。下单、吃单成交、撤单、批量命令、触发单、风险扫描、强平、资金费、ADL、结算和 snapshot fence 不能混成一个平均值。
- 并发与尾延迟测试优先使用 open-loop 或恒定到达率负载，必须说明是否修正 coordinated omission；报告预热、稳定运行和冷却时长。只报告平均延迟、客户端排队后延迟或短时峰值吞吐不能通过验收。
- JMH 报告必须包含完整参数、fork、warmup、measurement、线程数、JVM 参数、GC、机器/CPU、负载模型和业务操作计数口径；至少输出主分数、误差/置信区间、`terminalBusinessOperations`、`terminalCoreMessages`、accepted/terminal 差值、unfinished、backlog，以及 `-prof gc` 的分配率、每操作分配字节、GC 次数和 GC 时间。带 profiler 的数值用于归因，不能替代无 profiler 的主吞吐结果；仅报告当前 `master` 在锁定场景下的实测表现，不作旧版本性能对照。
- 每次交易主链路 JFR 采样至少检查并报告：
  - CPU 与热点：进程/机器 CPU、各线程 CPU load、execution samples、墙钟热点及 top methods/stacks；按交易 owner、matcher、风险、snapshot/projection、Core Fact、Aeron/Kafka/外围线程分组，禁止只给全 JVM 汇总。
  - Java 分配：总分配率（bytes/s）、每 business op 分配字节、对象数/operation、TLAB 与非 TLAB 分配、最大对象、top allocation class/thread/site，以及 `ObjectAllocationSample`、`ObjectAllocationInNewTLAB`、`ObjectAllocationOutsideTLAB`、`ThreadAllocationStatistics` 等可用事件。
  - Heap 与 GC：heap committed/used、GC 前后占用、live set/old generation 趋势、young/full/concurrent GC 次数与原因、总 GC 时间和时间占比、pause p50/p95/p99/max、最长 GC phase、晋升/疏散失败和 allocation requiring GC。
  - 堆外与 Native Memory：启用 HotSpot Native Memory Tracking 后报告 JVM native reserved/committed 及各 NMT category、峰值和测试前后增量；同时报告 Direct/Mapped ByteBuffer、Aeron/Netty/Chronicle 或其他 native buffer/pool 的当前值、峰值、分配/释放差值。只看 Java heap 不能得出“无内存泄漏”。
  - 泄漏证据：短 JFR 只用于分配热点，不能证明无泄漏。涉及长期状态、缓存、订单簿、snapshot/outbox 或 native buffer 的改动必须增加稳定负载长稳测试，比较多轮 GC 后 live set、old object/class 增长斜率、线程数、Direct/native committed、文件描述符和 buffer/pool 余额；疑似泄漏时再启用 `OldObjectSample` 和 `path-to-gc-roots`，并记录其额外停顿风险。
  - 线程、锁与调度：thread start/end、线程数峰值、RUNNABLE/BLOCKED/WAITING/PARKED 时间、monitor enter/wait、thread park/sleep、锁竞争对象与阻塞栈、上下文切换或 CPU throttling；busy-spin 必须单独报告其线程 CPU 占用，不能误判为业务热点。
  - Safepoint 与 VM operation：safepoint 次数、原因、到达 safepoint 时间、停顿时长，VM operation 类型与耗时；任何接近或超过业务 p99/p99.9 的停顿都必须解释。
  - JIT 与代码：compilation 次数/总时长/最长编译、code cache、deoptimization、类加载/卸载及 metaspace 趋势，确认采样窗口已越过主要预热和编译阶段。
  - I/O 与异常：file/socket read/write 次数、字节、阻塞时长和 top stack，异常/错误数量与 top throw site；交易 owner 出现同步文件、网络或数据库 I/O 直接判定主链路验收失败。
  - 系统与容器：OS/JVM 参数、可用 CPU、CPU load、物理内存、swap/page fault、容器 CPU 配额/节流、容器内存和同机干扰进程；发生明显 throttling、swap 或 JFR `DataLoss` 时该轮数据无效。
- JFR 必须使用明确的 recording 配置并保存原始 `.jfr` artifact；交付至少附 `jfr summary`、相关 `jfr view`/JMC 聚合结果、记录时长、事件配置、artifact 路径和文件大小。`profile.jfc` 或自定义配置的额外开销必须说明，不能在不同配置之间直接比较绝对吞吐。
- 最终性能结论必须同时给出吞吐、并发、尾延迟、GC/分配、heap/native memory、热点/阻塞、长稳泄漏和资金正确性；任何一项缺失、指标口径变化、测试中积压未清零或 profiler 数据丢失，都只能标记为部分验证。

- 测试范围按改动影响面分级，不默认执行全量测试：
  - 文档、注释、格式或不影响运行时的配置改动：执行 `git diff --check` 和必要的静态检查即可。
  - 单模块、纯函数、DTO、编解码或局部业务逻辑改动：只运行受影响模块及对应测试类，必要时包含其 Maven 依赖模块。
  - 跨模块 API、事件、repository、账户、撮合、风控、WebSocket 或持久化边界改动：运行所有直接受影响模块的测试，并补充对应集成测试。
  - 共享协议、根 POM、公共 topic、核心状态模型，或无法可靠界定影响范围的改动：再扩大到相关产品线和全量测试。
- 影响面判断以调用方、依赖方和数据/事件边界为准；不能因为改动文件少就认定影响小。使用 CodeGraph、Maven 依赖和测试失败证据确定范围。
- 每次只启动受影响的一个产品线，不需要六个撮合业务全部启动；只有共享组件或跨产品线改动才扩大产品线范围。
- 正式交易链路验收默认使用 1 个 matching engine；允许为 matcher 扩展性和瓶颈归因执行独立诊断压测，但必须固定 `256 in-flight`、明确记录 matcher 数量，不得与单 matcher 结果混合为同一验收结论。
- 做市进程在交易链路测试中应保持运行。
- 只有交易链路、账户、持仓、撮合或风控改动才要求用模拟用户 API 覆盖下单、撤单、撮合、成交、持仓形成、主动平仓、强平、风控事件和 WebSocket 推送；局部改动不强制执行完整链路。
- 只要改动可能影响资金或持仓，就必须验证用户账号和做市账号资金、持仓正确，并逐项核对资金守恒：期初、充值/调整、成交、手续费、资金费、强平费、交割/行权流水、期末余额。
- 未执行更大范围测试时，必须在交付说明中记录已测范围、未测范围和判断依据；不能用“全量测试耗时”作为跳过受影响测试的理由。
- 端到端验证优先使用对应 Maven 模块测试；产品线服务、数据库和 Kafka 的启动方式待脚本重新整理后补充，
  不要引用已经删除的 `scripts/` 路径。

## 验证命令

- 局部改动优先运行精确测试：`mvn -pl <module> -Dtest=<TestClass> test`；若需要验证模块依赖，再使用 `mvn -pl <module> -am test`。
- 跨账户、撮合、风控、WebSocket 的改动要跑直接受影响模块的集成测试；只有影响跨模块公共契约或无法界定影响时才执行更大范围或全量测试。
- Kafka topic 或产品线 topic 改动后，至少检查 `ProductTopicNames`、Topic 初始化配置、consumer group、key 校验和 WebSocket fanout；若 topic 被多个产品线共享，再扩大到所有消费者测试。
- 任何未能启动的集成环境、未执行的影响范围或已知测试缺口，都必须在结果中明确记录。

## 文档

- 当前分支已移除 `docs/` 和 `scripts/`，正在重新整理文档与验证脚本；新增说明应先同步根目录 `README.md` 或对应模块 README，不能链接到不存在的路径。
- 新增或调整产品线、资金模型、撮合、风控、交割、期权、WebSocket、Kafka Topic 后，要同步中文 README 和相关文档。
- 说明要结合源码路径和关键类，避免只写概念。

## 提交

- 后续仅在 `master` 分支开发、提交和推送；未经用户明确要求，不再创建或切换开发分支。
- 每完成一个模块并通过测试后 commit and push。
- 不提交 `.idea/`、`.local-logs/`、`data/`、本地运行产物。
