# 性能验证记录

本文件是项目唯一的性能验证结果记录。所有新记录只允许追加，禁止覆盖或改写历史记录。正式采集只允许使用 `256 in-flight`；其他档位的数据不得写成基线、对照或验收结果。

## 记录规则

1. 每次采集前先复制“追加模板”，填写“采集前锁定”部分，并提交或保留可审计的时间戳。未完成该部分不得开始采集。
2. 开始采集后，基准标准和测试场景不得修改；配置变化时终止当前轮，记录问题，再追加一条新记录。
3. 每次运行都要记录，包括通过、失败、中止、受环境干扰或数据无效的轮次；不得只保留最优结果。
4. 原始 JMH JSON、JFR、GC/safepoint、NMT、系统监控和对账 artifact 保存在当次命令指定的位置，本文件记录路径、文件大小和 SHA-256，不提交大体积本地产物。
5. 基线与修改后必须使用同一套已锁定参数，并且都固定为 `256 in-flight`；历史其他档位结果不可直接比较。

## 追加模板

<!-- 每次采集复制以下完整区块并追加到文件末尾；不要修改已有记录。 -->

### YYYY-MM-DD HH:mm:ss Z — `<被测 commit>` — `<结果：待采集/通过/失败/无效/部分验证>`

#### 采集前锁定

- 记录创建时间：`YYYY-MM-DD HH:mm:ss Z`
- 被测 git commit：`<full SHA>`
- 对照 git commit：`<full SHA>`
- 修改点：`<涉及模块、关键类/方法、预期性能影响>`
- 验证目标：`<本轮要证明或排除的具体结论>`
- in-flight：`256（固定，不得修改）`
- 通过标准：
  - 吞吐：`<各业务类型 terminal business ops/s、terminal Core messages/s、fills/s 或 trades/s 阈值及允许回归幅度>`
  - 正确性：`accepted == terminal；unfinished == 0；期末 backlog == 0；资金、余额、冻结、持仓、订单终态和快照恢复全部正确`
  - 延迟：`<各业务类型三段延迟 p50/p90/p95/p99/p99.9/max 阈值、单位、样本数与超时上限>`
  - 稳定性：`<拒绝率、错误率、超时率、最大 backlog、JFR DataLoss、CPU throttling、swap 等阈值>`
  - 资源：`<分配率、bytes/op、GC、heap、native memory、线程、锁、safepoint、I/O 和异常阈值>`
  - 长稳/泄漏：`<持续时间及 live set、old generation、native committed、线程、FD、buffer/pool 增长阈值；不适用时说明理由>`
- 测试场景：
  - 产品线与 symbol：`<仅启动的受影响产品线、symbol 数和标识>`
  - 业务动作与比例：`<maker/taker、下单/撤单/改单/成交/触发/风险/强平/资金费/ADL/结算/snapshot fence 比例>`
  - 负载模型：`<open-loop/恒定到达率、目标 offered rate、是否修正 coordinated omission>`
  - 并发：`<活跃用户、连接、in-flight=256、Account Lane、matcher、risk engine 数>`
  - 批量参数：`<平均/最大 batch size；不适用时写 N/A>`
  - 阶段时长：`<预热、稳定测量、冷却、长稳时长>`
  - 做市状态：`<进程、账户、报价和运行状态>`
  - 初始状态：`<用户/做市资金、余额、冻结、持仓、活动订单、盘口>`
  - 终态检查：`<资金守恒、余额/冻结/持仓、订单生命周期、盘口、Core Fact、快照恢复>`
- 固定环境与参数：
  - 机器/CPU/内存/容器：`<型号、核数、内存、CPU 配额、绑核、同机干扰>`
  - OS：`<版本与内核>`
  - JDK/JVM：`<HotSpot JDK 25 完整版本；附 java -version 与 mvn -version>`
  - JVM/GC/NMT 参数：`<完整参数>`
  - JMH：`<benchmark、fork、warmup、measurement、threads、完整参数>`
  - JFR：`<JFC、时长、事件配置及预期开销>`
  - 代码与配置：`<分支、dirty 状态、配置文件及 SHA-256>`
- 执行命令：
  - `<完整命令 1>`
  - `<完整命令 2>`

#### 采集结果

- 实际采集时间：`YYYY-MM-DD HH:mm:ss Z` 至 `YYYY-MM-DD HH:mm:ss Z`
- 结果状态：`<通过/失败/无效/部分验证>`
- 吞吐与业务计数：
  - `<按业务类型列出 requests/s、batches/s、items/s、terminal business ops/s、terminal Core messages/s、fills/s/trades/s、样本数>`
  - `<accepted/terminal business operations、accepted/terminal Core messages、unfinished、最大/期末 backlog、拒绝/错误/超时>`
- 延迟：
  - `<按业务类型和入口→accepted、accepted→terminal、入口→terminal 三段分别列出 p50/p90/p95/p99/p99.9/max、样本数、区间、超时上限、单位>`
- GC 与 Java 分配：`<分配率、bytes/op、对象数/op、TLAB/非 TLAB、top class/thread/site、GC 次数/原因/总时间、pause 分位及 max>`
- Heap 与 native memory：`<heap committed/used/live set/old generation；NMT reserved/committed 分类、Direct/Mapped/native buffer 当前/峰值/增量>`
- CPU、热点与阻塞：`<机器/进程/线程 CPU；owner、matcher、risk、snapshot/projection、Core Fact、Aeron/Kafka 分组热点；锁、park、上下文切换、throttling>`
- Safepoint、VM、JIT：`<safepoint/VM operation；编译、code cache、deoptimization、类加载和 metaspace>`
- I/O 与异常：`<file/socket 次数、字节、阻塞和 top stack；异常数量和 top throw site；owner 同步 I/O>`
- 长稳与泄漏：`<多轮 GC 后趋势、线程、FD、native/buffer/pool 余额、OldObjectSample 结果或不适用理由>`
- 系统有效性：`<CPU load、内存、swap/page fault、容器节流、干扰进程、JFR DataLoss；本轮是否有效>`
- 正确性与资金不变量：`<用户/做市期初、调整、成交、手续费、资金费、强平费、交割/行权、期末；余额/冻结/持仓/订单终态/盘口/快照恢复>`
- 原始 artifact：
  - `<路径> — <类型> — <大小> — SHA-256 <值>`
- 基线对比：`<同场景、同参数、同为 256 in-flight 的绝对值与变化百分比>`
- 问题与异常：`<问题、发生时间、影响、根因/假设、是否使结果无效、跟进项；无则写“无”>`
- 未测范围及理由：`<产品线、场景、指标或环境缺口>`
- 结论：`<逐项对照采集前标准；缺项时只能写部分验证>`

## 最近历史记录

以下三条从根目录 `README.md` 的最近性能记录迁录，只保留 `256 in-flight` 数据。它们发生在本文件和“采集前锁定”规则建立之前，因此统一标记为历史部分验证；缺失字段保持缺失，不能作为后续正式验收记录的填写示例。

### 2026-08-29（原记录未提供具体时分秒）— `61be7c53b7cdea09bec7e0aa682a519df093bb93` — `部分验证`

#### 采集前定义

- 被测 git commit：`61be7c53b7cdea09bec7e0aa682a519df093bb93`
- 修改点：ready matcher result 按全局 Core sequence 批量提交；runtime mutation 使用 hash scratch 和 dirty-key 排序；Core Fact 在线程外从 typed journal 构造；Audit ACK 绕过无关 matching/projection 等待。
- 基准标准：原记录未在采集前单独锁定；以 accepted=terminal、unfinished=0、资金和活动订单不变量通过、对同口径旧值无回退作为当时判断依据，生产目标仍为单产品线 `100k terminal business ops/s`。
- 场景：线性永续，HotSpot JDK 25.0.1、ZGC、8 GiB heap、4 Account Lane、4 matcher、10,000 用户、512 个活跃 symbol、每 invocation 16,384 条 maker/taker 指令、BUSY_SPIN、`256 in-flight`；无 profiler 主运行、独立 JFR/NMT。
- 对照 commit：原记录未提供完整 SHA。

#### 采集结果

- 采集时间：`2026-08-29`；原记录未提供具体开始/结束时间和时区。
- 吞吐：BUSY_SPIN `4238.495 terminal business ops/s`；相对同口径旧值提高约 `42.1%`。独立 JFR/NMT 轮为 `4129.358 ops/s`。
- 业务完整性：accepted=terminal、unfinished=0；teardown 逐轮核对期初/期末资金和活动订单一致。
- matcher backlog：平均 `200.5/256`，最大 `256`；原记录未提供期末 backlog。
- 延迟：四个 invocation 的完成延迟范围为 p50 `32.3–47.5 ms`、p99 `45.7–71.9 ms`、p99.9 `47.3–84.9 ms`；p90、p95、max、三段拆分、样本数和超时上限未记录。
- GC/内存：43 次 ZGC 暂停合计 `0.615 ms`，最大 `0.0602 ms`，allocation stall/OOM 为 0，DirectBuffer 为 `0–1 byte`；heap、NMT 分类和精确 bytes/op 未记录。
- CPU/热点：27,062 个执行样本中 exchange-core/Disruptor wait/cursor 约占 `86.8%`；14 个等待线程与 owner、projection、fact worker 在 8 核主机上争用 CPU。owner 主要成本为 matcher completion、业务 hash、runtime map 查询/更新及相关集合分配。
- 问题：本机线程数超过物理核承载，BUSY_SPIN 与 owner 明显争用；吞吐仍远低于 `100k/s`，不能认定生产容量达标。
- Artifact：原记录仅说明存在独立 JFR/NMT 轮，未记录路径、大小和 SHA-256。
- 未测范围：Aeron Cluster、HTTP、Kafka、WebSocket、生产同型隔离 CPU、40 分钟长稳，以及非线性永续产品线。
- 结论：正确性门禁通过，`256 in-flight` 性能方向改善；因未预锁标准、artifact 不完整且环境受 CPU 争用影响，只能作为历史部分验证。

### 2026-08-29（原记录未提供具体时分秒）— `2fd9fb77890b9cfb933d0e69674cbdb1c8b949ab` — `部分验证`

#### 采集前定义

- 被测 git commit：`2fd9fb77890b9cfb933d0e69674cbdb1c8b949ab`
- 修改点：空 mutation family 共享不可变空值；dirty value 使用紧凑查询；空资金 posting 共享；projection 复用未变化 map/root；Core Fact 使用确定性循环和 primitive ID 去重；修复 Audit ACK 与异步终态订单登记竞态。
- 基准标准：原记录未在采集前单独锁定；以 accepted=terminal、unfinished=0、资金和活动订单不变量通过、JFR DataLoss=0、无 allocation stall/OOM 作为有效性检查，生产目标仍为单产品线 `100k terminal business ops/s`。
- 场景：线性永续，HotSpot JDK 25.0.1、ZGC、8 GiB heap、4 Account Lane、4 matcher、10,000 用户、512 个活跃 symbol、每 invocation 16,384 条 maker/taker 指令、`256 in-flight`；BUSY_SPIN/YIELDING 本机连续矩阵及独立 BUSY_SPIN JFR。
- 对照 commit：上一同配置 JFR 的 commit 未在原记录中注明。

#### 采集结果

- 采集时间：`2026-08-29`；原记录未提供具体开始/结束时间和时区。
- 吞吐：首次 BUSY_SPIN `5421.945 terminal business ops/s`，首次 YIELDING `5064.447 ops/s`；竞态修复后同源码连续运行中 BUSY_SPIN 降为 `2745.905 ops/s`，同轮 YIELDING 回升至 `5884.217 ops/s`，随后 BUSY_SPIN JFR 单轮为 `4911.514 ops/s`。
- 业务完整性：所有运行 accepted=terminal、unfinished=0；teardown 的资金总量和活动订单不变量通过。
- 延迟：四个 16,384 指令 invocation 为 p50 `24.4–44.2 ms`、p99 `30.7–82.5 ms`、p99.9 `32.0–105.6 ms`；p90、p95、max、三段拆分、样本数和超时上限未记录。
- Java 分配：相对上一同配置 JFR 的 allocation sample weight，owner `7.379→7.023 GiB`（约 `-4.8%`）、projection `2.799→2.153 GiB`（约 `-23.1%`）、fact materializer `1.817→1.253 GiB`（约 `-31.0%`）；这是采样权重，不是精确分配率。
- GC/JFR：JFR DataLoss=0；7 次 ZGC 最大暂停 `0.275 ms`，allocation stall/OOM 为 0。
- CPU/热点：owner 2,218 个执行样本中 completion publication cursor 等待为 504（`22.7%`），随后为 runtime `TreeMap`、rolling hash 与 small `CompactValueMap`；projection/fact 分别为 344/263 个样本。
- 问题：同源码结果在连续 8 分钟测试中大幅漂移，8 核本机的 busy-spin 线程争用使绝对吞吐无容量代表性；该轮不能判断 BUSY_SPIN/YIELDING 的生产差异，最佳结果仍低于 `6k/s`。
- Artifact：原记录未给出 JMH JSON/JFR/NMT 的路径、大小和 SHA-256。
- 未测范围：精确三段延迟全集、生产同型隔离 CPU、40 分钟长稳、端到端链路及其他产品线。
- 结论：资金与生命周期正确性、竞态回归和分配热点方向得到验证；吞吐受环境漂移影响，只能作为历史部分验证，不能作为固定容量基线。

### 2026-08-29（原记录未提供具体时分秒）— `b285557f9cb2182039d37452663ae83b79d0aa94` — `部分验证`

#### 采集前定义

- 被测 git commit：`b285557f9cb2182039d37452663ae83b79d0aa94`；结果首次整理于后续文档 commit `14b664fe834e7cadc89fe45b4a6ecdd40a66fa1f`。
- 对照 git commit：`2fd9fb77890b9cfb933d0e69674cbdb1c8b949ab`。
- 修改点：matcher 前完成触发单/replacement 身份校验；completion 改为按 Core sequence 定位的预分配 mailbox；Core Fact order view/编码在线程外生成；runtime mutation/commit/funds ledger 使用 primitive 容器；修复 mailbox 发布/depth 竞态和 overflow fail-closed 顺序。
- 基准标准：固定基线 `5419.854 ± 475.267 terminal business ops/s`；正确性要求 accepted=terminal、unfinished=0、资金不变量通过；原记录未预先给出尾延迟、GC、内存和长稳的量化通过阈值。
- 场景：线性永续，HotSpot JDK 25.0.1、ZGC、8 GiB、4 Account Lane、4 matcher、10,000 用户、512 个活跃 symbol、16,384 条指令、BUSY_SPIN、`256 in-flight`；主 A/B 为 5×5 秒预热、5×5 秒计量、3 forks；另有 1 fork 复测、3 分钟 soak、`-prof gc` 和 JFR。

#### 采集结果

- 采集时间：`2026-08-29`；原记录未提供具体开始/结束时间和时区。
- 主吞吐：`5744.730 ± 276.257 terminal business ops/s`，对照 `5419.854 ± 475.267 ops/s`，均值提高 `5.99%`；99.9% 区间重叠，不声明统计显著提升。加入最终 fail-closed 门禁后的同配置 1 fork 为 `5858.194 ops/s`。
- 业务完整性：accepted=terminal、unfinished=0；3 分钟 soak 摘要 `fundsInvariant=true`。service 模块 380 项测试、benchmark 模块 10 项测试均 0 失败。
- 长稳：3 分钟 10k×512 soak 完成 `1,400,899` 个终态业务操作，平均 `7509.324 ops/s`；snapshot `27,947,612 bytes`，恢复 `3416.690 ms`，期末 heap 低于期初，direct memory 为 0。
- GC/分配：`-prof gc` 重生命周期场景分配率 `400.255 MB/s`，约 `139,571 B/terminal business op`；JFR DataLoss=0、ZGC allocation stall/OOM 为 0，暂停主要为 `0.01–0.06 ms`，最大 `0.163 ms`。
- CPU/热点：有效分配热点为 reservation rolling hash、`CompactKeyList`、mutation capture 和 persistent tree；全线程执行样本约 `84.6%` 为 exchange-core/Disruptor wait/cursor，反映 8 物理核上的等待线程与 owner CPU 争用。
- 延迟/backlog：原记录未提供 p50/p90/p95/p99/p99.9/max、三段拆分、样本数、超时上限、最大及期末 backlog。
- 问题：均值虽正向但置信区间重叠；CPU 争用明显；3 分钟仅为诊断 soak，未达到正式 40 分钟门禁；吞吐远低于单产品线 `100k/s`。
- Artifact：原记录未给出 JMH JSON、JFR、GC、NMT、soak 文件的路径、大小和 SHA-256。
- 未测范围：40 分钟长稳、生产同型隔离 CPU、端到端 Aeron/HTTP/Kafka/WebSocket、其余五条产品线。
- 结论：`256 in-flight` 下未发现可确认性能回退，正确性和短时稳定性门禁通过；因缺少预锁标准、完整延迟/backlog 与原始 artifact 索引，只能作为历史部分验证，不能标记生产认证完成。

### 2026-08-31 10:35:55 +08:00 — `PV-20260831-256-01` — `采集前锁定`

- 记录创建时间：`2026-08-31 10:35:55 +08:00`（`2026-08-31T02:35:55Z`）
- 被测代码：git HEAD `5d18e77a9f6e259856f304fe1c187538187b3988`；最近代码 commit `21778a3c39d08dec7e2ffc2c3fb0cd043d66d049`；工作区包含未提交实现，tracked diff SHA-256 `a9a39603788c2a71a4d8f5c469c25677f597e22f299ac6f8c65c5de86e788f6c`，22 个相关 untracked 文件的内容清单 SHA-256 `9944b8aabce3a42d662500afde3ac3a677ddca61905ce5737dc0db37e3cc29d5`。本轮结果只对这组代码指纹有效。
- 对照 git commit：`b285557f9cb2182039d37452663ae83b79d0aa94`；历史同场景主分数 `5744.730 ± 276.257 terminal business ops/s`。
- 修改点：当前 dirty 代码包含 owner commit patch/journal/index、admission reservation、projection/Core Fact v10、snapshot/recovery、exporter/projector、gateway/market-data consumer，以及饱和补料和业务延迟采集改造。
- 验证目标：确认当前代码在固定 `256 in-flight` 下相对最近正式基线没有超过 5% 的主吞吐回退，并采集正确性、三段尾延迟、backlog、GC/分配、JFR/NMT、热点和短/长稳证据。
- in-flight：`256（固定；本轮不运行其他档位）`
- 通过标准：
  - 主吞吐：无 profiler、无 NMT、3 forks 的 `terminal business ops/s >= 5457.494`；报告 score、误差/置信区间、`terminal Core messages/s` 和 fills/trades。
  - 正确性：accepted business operations 等于 terminal business operations，accepted Core messages 等于 terminal Core messages，两个 unfinished 均为 0，期末 backlog 为 0，producer starvation 为 0；teardown 的资金、余额、冻结、持仓、活动订单及快照恢复检查全部通过。
  - 延迟：按业务类型报告 entry→accepted、accepted→terminal、entry→terminal 的 p50/p90/p95/p99/p99.9/max、样本数、区间、超时和单位；entry→terminal 要求 p99 `<=100 ms`、p99.9 `<=125 ms`、max `<=250 ms`。
  - backlog：最大值 `<=256`，期末为 0；拒绝率、错误率和超时率均为 0。
  - JFR/系统有效性：`DataLoss=0`、容器 CPU throttling=0、swap 使用为 0、owner 同步 File/Socket I/O 为 0、异常为 0。
  - GC/内存：ZGC allocation stall/OOM 为 0，GC pause max `<=1 ms`；记录 allocation rate、bytes/op、heap/live set、NMT、Direct/Mapped buffer。历史没有严格同场景的精确 allocation 基线，本轮只采集数值，不据此单独判定回退。
  - 长稳/泄漏：同一 `256 in-flight` 场景持续 40 分钟，至少取得 3 个真实 post-GC 点；live set、native committed、线程、FD 与 buffer/pool 不得出现无法解释的单调增长，结束时资金和业务终态检查通过。
- 测试场景：
  - 产品线：仅 `LINEAR_PERPETUAL`；512 个挂牌且活跃 symbol。
  - 业务动作：`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`，跨全部 symbol 连续 maker/taker 配对，反向前设置 drain/ACK fence；不把未实际触发的风险、强平、资金费、ADL、结算或 snapshot recovery 延迟伪装为已测。
  - 负载模型：open-loop constant-arrival，offered rate `100,000 business operations/s`，计划到达时间包含排队并修正 coordinated omission。
  - 并发：10,000 活跃用户、`256 in-flight`、4 Account Lane、4 matcher、1 risk engine、单 JMH worker；每用户最多 5 个持仓和 10 个未成交单。
  - 批量/调用：16,384 operations/invocation；export ACK interval 1,024。
  - 主吞吐阶段：5×5 秒预热、5×5 秒测量、3 forks；GC 归因：2×5 秒预热、3×5 秒测量、1 fork；JFR/NMT：2×5 秒预热、1×30 秒测量、fork 0；长稳：30 秒预热、40 分钟测量、fork 0。
  - 做市与初态：benchmark 内部基础设施账户持续提供对手盘；正式运行前由真实 Product Core 命令构建 10k×512 模板和资金/持仓/挂单初态。
  - 终态检查：benchmark trial teardown 执行 scenario verify/close，核对 accepted/terminal、unfinished、资金守恒、活动订单不增长和快照恢复；缺失的检查必须在结果中列为缺口。
- 固定环境：
  - 机器：Intel Core i9-9880H，8 物理核/16 线程，16 GiB；macOS 26.7 / Darwin 25.6.0 x86_64；非容器、未绑核。
  - 干扰：采集前存在 Terminal、Codex、WindowServer、Clash Verge 和一个后台 Java 进程；不停止用户进程，结果只作为同机诊断对比。发生 swap、明显热/调度漂移或 DataLoss 时该轮无效。
  - JDK/JVM：Oracle GraalVM HotSpot JDK `25.0.1+8.1`；Maven `3.9.16` 明确通过该 JAVA_HOME 运行。默认系统 Java 是 OpenJ9 25.0.2，本轮禁止使用。
  - JVM：`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`，开放 `jdk.internal.misc/ref`，4 Lane/4 matcher、matcher BUSY_SPIN、settlement BLOCKING、completion spins 16,384、projection PARKING、projection batch 64/4 MiB、commit journal 65,536/1 GiB、export pending 256 MiB。
  - JFR：`owner-commit-profile.jfc`，SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`；启用 NMT summary、GC/safepoint 日志。
  - 资格脚本代码 SHA-256：`0abb6cfd48ae851772531c771b847e7c86a727ac32de01fee20a635b16651182`；其 saturation/owner-commit 路径仍硬编码 1,024，本轮不调用这些模式，只复用 HotSpot 前置审计、测试、打包参数和 analyzer。
- artifact 目录：`target/qualification/20260831T023555Z-current-256/`。
- 执行入口：
  - 目标测试：`SURPRISING_JAVA_HOME=/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home QUALIFICATION_ARTIFACT_DIR=<artifact> qualify-linear-perpetual-scale.sh tests`
  - 打包：使用相同 JAVA_HOME 执行 `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am clean package -DskipTests`。
  - 主吞吐/GC/JFR/长稳：直接运行 shaded benchmark JAR 的 `LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`，公共参数固定 `activeUsers=10000, listedSymbols=512, activeSymbols=512, maxPositionsPerUser=5, maxOpenOrdersPerUser=10, maxInFlight=256, operationsPerInvocation=16384, targetOperationsPerSecond=100000, threads=1`，阶段参数严格使用上述锁定值。

### 2026-08-31 11:36:00 +08:00 — `PV-20260831-256-01` — `失败（主门禁）/部分验证（完整验收）`

#### 采集结果

- 实际采集时间：目标测试 `2026-08-31 10:37:53 +08:00` 开始；主吞吐 `11:06:52–11:12:44`，GC 归因 `11:13:26–11:14:33`，JFR/NMT `11:16:44–11:18:02`；聚合分析完成于 `11:36:00`。
- 被测代码：最近代码 commit `21778a3c39d08dec7e2ffc2c3fb0cd043d66d049`；采集前文档 HEAD `1dc25349f765f1cc1f2036cd335e9319302af5bb`。实现仍为 dirty working tree，tracked diff SHA-256 `a9a39603788c2a71a4d8f5c469c25677f597e22f299ac6f8c65c5de86e788f6c`，22 个相关 untracked 文件内容清单 SHA-256 `9944b8aabce3a42d662500afde3ac3a677ddca61905ce5737dc0db37e3cc29d5`；结果不代表仅 checkout 任一 commit 的状态。
- in-flight：只运行 `256`；未运行 64、128、512、1024 或其他档位。
- 结果状态：主吞吐与 entry→terminal 尾延迟均未达到采集前锁定门禁，判定失败；fills/trades、完整资金流水、40 分钟长稳和泄漏斜率缺失，因此完整性能验收只能标记为部分验证。

##### 吞吐、并发与业务计数

- 无 profiler 主结果：`4738.952 ± 704.371 terminal business ops/s`，置信区间 `[4034.580, 5443.323]`；3 个 fork 均值依次为 `4324.267 / 5179.335 / 4713.253 ops/s`，15 个 measurement iteration 范围 `2885.169–5446.668 ops/s`，波动明显。
- 同轮 `terminal Core messages/s=4743.580`；`acceptedBusinessOperations=terminalBusinessOperations=4738.952 ops/s`，`acceptedCoreMessages=terminalCoreMessages=4743.580 ops/s`，两个 `unfinished*=0`，producer starvation 为 0。teardown 未抛出验证异常，窗口被排空。
- JFR 归因轮为 fork 0、单次 measurement，`6920.316 terminal business ops/s`、`6927.075 terminal Core messages/s`；该数值受不同 fork/阶段及 profiler 配置影响，不能替代或抬高正式主分数。
- 归因轮每 invocation 16,384 个 business operations、最大 matching backlog `256`、平均 `232.0`，满窗口比例 `81.25%`，completion mailbox 高水位 `256/4096`；期末由 verify/close 排空。
- 活跃用户 10,000、活跃/挂牌 symbol 512、4 Account Lane、4 matcher、1 risk engine、1 JMH worker、`256 in-flight`；offered rate 100,000 business operations/s，open-loop constant-arrival，coordinated omission corrected。
- 本 benchmark 未输出独立 `fills/s`、`trades/s`、拒绝率或 batch 指标；这几项不得从 Core message 数推算，记为未采集。

##### 三段延迟

- JFR measurement 的最后 13 个 invocation 共 `212,992` 个样本。事件保存每个 invocation 的精确分位和 64 桶直方图，未保存可合并的原始样本；以下 p50–p99.9 是 13 个 invocation 对应分位的中位数，max 是这些 invocation 的全局最坏值。直方图区间 `1 ns–30 s`，超时上限 `30 s`，单位均为墙钟时间，负载模型包含计划到达排队时间。
- entry→accepted：p50 `995.724 ms`、p90 `1821.589 ms`、p95 `1927.446 ms`、p99 `1999.450 ms`、p99.9 `2016.078 ms`、max `3174.445 ms`。
- accepted→terminal：p50 `27.170 ms`、p90 `31.184 ms`、p95 `32.543 ms`、p99 `35.863 ms`、p99.9 `38.066 ms`、max `174.736 ms`。
- entry→terminal：p50 `1022.351 ms`、p90 `1852.421 ms`、p95 `1955.296 ms`、p99 `2028.160 ms`、p99.9 `2042.346 ms`、max `3199.434 ms`。
- entry→terminal 的 p99/p99.9/max 分别超过锁定的 `100/125/250 ms` 门禁，尾延迟失败；主要时间在 entry→accepted 排队段，而不是 accepted→terminal 执行段。

##### GC、Java 分配、heap 与 native memory

- `-prof gc` 归因轮：`6524.991 terminal business ops/s`；分配率 `628.671 ± 130.675 MB/s`，`1,671,050,809.333 B/JMH invocation`，折算 `101,992.847 B/terminal business op`；20 次 GC，JMH `gc.time=3956 ms` 为并发收集总时间，不能解释为 STW pause。
- JFR 75 秒记录：13 次 ZGC、53 个 pause，总 pause `1.09 ms`；pause p50 `0.0123 ms`、p90 `0.0413 ms`、p95 `0.0613 ms`、p99/p99.9/max `0.104 ms`，通过 `max<=1 ms`；allocation stall、OOM、promotion/evacuation failure 和 allocation-requiring-GC 均为 0。
- 分配线程：JMH worker `26.6 GiB (68.86%)`，两个 Core Fact materializer 合计约 `10.4 GiB (26.89%)`，两个 internal core-commit projector 合计约 `1.15 GiB (2.91%)`。top allocation class 为 `Object[]`、`long[]`、`byte[]`、`Long`、immutable list iterator、HashMap/TreeMap 和 stream 对象；top site 包括 `HashMap.putVal/resize`、stream pipeline、`CoreProbeState.mergeTreasuryDeltas`、`RuntimeCommitPatch.Builder` 和 `RuntimeCommitPatch.tombstones`。
- 记录到 `158,983` 个 new-TLAB、`4,034` 个 outside-TLAB 和 `159,730` 个 allocation sample；未生成可靠的对象数/business op，记为缺口。
- Java heap committed 固定 `8 GiB`；各次 GC 后占用由早期 `52 MiB` 到后期约 `722 MiB`，进程退出前因最后一段分配为 `3758 MiB`。短记录没有稳定 post-GC 斜率，不能据此声明无 heap 泄漏。
- NMT 退出时总 reserved `147,599,146,853 B`、committed `8,808,161,125 B`；summary diff 为 committed `+165,681 KiB`，主要来自 JVM 初始化、class/code/GC/NMT/tracing。JFR 分类峰值：GC committed `210.1 MiB`、code `51.2 MiB`、metaspace `36.6 MiB`、tracing `30.8 MiB`。DirectBuffer count `0–1`、memory used `0–1 B`；Mapped buffer 没有独立指标。

##### CPU、热点、线程、锁、Safepoint 与 JIT

- JFR 期间 JVM user CPU 平均 `54.16%`、system `1.36%`，机器总 CPU 平均 `59.56%`、最大 `72.58%`。线程峰值 24；无 CPU 容器配额或 throttling 事件。
- execution sample 热点主要是 matcher/Disruptor 等待：`ProcessingSequenceBarrier.checkAlert 57.23%`、`WaitSpinningHelper.tryWaitFor 10.16%`、`Util.getMinimumSequence 7.79%`、`ProcessingSequenceBarrier.getCursor 7.73%`。业务侧可见 rolling business/funds hash、Core state hash、`RuntimeProjectionState.apply/prevalidateUser`、TreeMap/HashMap/stream。
- internal `core-commit-projector-linear_perpetual`、`core-fact-materializer` 和 snapshot encoder 均在本进程/JFR 中；独立 `surprising-aeron-exporter`、Kafka history projection 和 PostgreSQL 不在主 JMH 进程，也不计入 terminal business ops/s。
- `ThreadPark=1,476,606`；主要 contention 是 exchange-core affinity 线程初始化，单次最长 `530 ms`，发生在 measurement 前。未发现 measurement 主链路锁竞争证据；busy-spin matcher 线程消耗了显著 CPU，且机器只有 8 个物理核。
- 63 次 safepoint begin；排除进程退出的 indefinite 记录，最长 safepoint `0.975 ms`。VM operation 最长 `0.593 ms`，均未接近本轮业务 p99。
- compilation 10,899 次、最长 `887 ms`，主要长编译均发生在 measurement 前；deoptimization 596 次，class load 4,351 次。metaspace 最后观测 `36.6 MiB`；未取得完整 code-cache 时间序列。

##### I/O、异常与系统有效性

- 全记录 FileRead 3,449、FileWrite 3,601，来源主要为 benchmark JAR/class loading、JFR/JMH JSON 和 native library 临时文件。进入最后 13 个 measurement invocation 后，仅主线程/JMH 输出有 3,461 次写、86,868 B；交易 owner、matcher、internal projection 和 Core Fact 均无同步文件 I/O。SocketRead/SocketWrite 均为 0。
- 全记录异常主要是 JDK/Chronicle/JNR/JMH 启动期反射探测；measurement 业务事件结束后 `333 ms` 出现 1 次 `InterruptedException`，栈为 `LinkedBlockingQueue.take`，属于 teardown interrupt。measurement 业务窗口未见异常/error throw。
- `JFR DataLoss=0`、容器 throttling 事件 0、系统 swap 使用 0，采样后未见 thermal/CPU performance warning。Terminal、Codex、WindowServer、Clash Verge 和后台 Java 等同机干扰未隔离，且主结果跨 iteration 波动很大；本轮对“发现回退/失败”有效，但不能外推生产容量。

##### 正确性、测试范围与外部组件

- HotSpot JDK 25 目标测试：protocol `CoreExportCodecTest` 18 项、service 403 项、benchmark support 13 项，合计 434 项，0 failure/0 error；benchmark 主、GC、JFR 三轮 teardown 均完成，accepted/terminal 相等、unfinished 为 0。
- service 测试覆盖资金、持仓、风险、快照、Core Fact、commit patch/journal/recovery 等受影响路径；但 artifact 没有逐账户输出用户/做市期初、手续费、资金费、强平费和期末明细，不能把“测试通过”扩写成完整资金对账表。
- 资格脚本的 tests 模式还误带了 exporter consumer suite：其中 23 项通过，`JdbcCoreEventProjectorPostgresTest` 仅因 Docker daemon 未运行而 1 error。该测试属于独立 Audit Exporter/History Projection→PostgreSQL 边界，不属于同步交易主链路吞吐；没有为它继续启动 Docker，Docker Desktop 已停止。此 error 不使主 JMH 数据无效，但记录为外部异步链路测试缺口。
- 主 JMH 包含 Product Core 内部的 runtime commit projection、Core Fact materializer 和 snapshot worker 成本；不包含独立 exporter 进程、Kafka、PostgreSQL history projector、API gateway、WebSocket 或网络端到端成本。

##### Artifact、对比、问题与结论

- artifact 根目录：`target/qualification/20260831T023555Z-current-256/`；完整文件 SHA-256 清单 `artifact-sha256.txt`（7,042 B，SHA-256 `d4c95a4af9daf9999a67c6b70a54cc454a4436a19dcdcd800ac1d0c424a15fa8`），大小清单 `artifact-sizes.txt`（4,132 B，SHA-256 `7133ea7d086f44acab867ff5dcd5e79567fd1eb893de71d9dbb5229874d4ca83`）。
- `saturation-main-256.json` — JMH 主结果 — 26,357 B — SHA-256 `1839898b3e19ffe66d821c4502a2b683f3a1003bc31c146cb04fadb0d619198d`。
- `saturation-gc-256.json` — JMH GC 归因 — 21,924 B — SHA-256 `ffa9123e4633aeaec4db0cddeddc03d33b4520d98101719736f385666c2e5f98`。
- `saturation-profile-256.jfr` — 原始 JFR — 75,016,954 B — SHA-256 `32f2031e53cc54d1c36e112e150a9d30a2ed633883ff64efc19c12413dba5264`。
- `jfr-summary.txt` — JFR summary — 13,213 B — SHA-256 `51122a043571853100c6bf30e29058f381e192c30703c973bb9399fe76b998c5`；同目录另有 compact `jfr-view-*`、自定义 saturation event JSON、GC log 和 NMT diff。
- `saturation-profile-256-nmt-summary.diff.txt` — NMT summary diff — 4,394 B — SHA-256 `19a0748debbbafdb0d9f3a35d2f310ee03737da3c443c2b63b3abfd0a3bef68d`。
- 基线对比：当前正式主分数 `4738.952` 相对 `b285557f` 的 `5744.730 terminal business ops/s` 下降 `17.508%`，低于锁定门禁 `5457.494` 达 `718.542 ops/s`；当前置信区间上界仍比门禁低 `14.171 ops/s`，判定吞吐失败。
- 主要问题：主吞吐回退且 iteration 漂移大；entry→accepted 排队使业务尾延迟超标；约 `101,993 B/business op` 的分配仍高；8 核机器上的 busy-spin/异步 worker CPU 争用显著；dirty working tree 不能仅靠 commit 重现；fills/trades、拒绝率、逐账户资金表和 mapped/native pool 余额未输出。
- 长稳与泄漏：未运行锁定的 40 分钟长稳。原因是主吞吐和延迟门禁已失败，继续长稳不能改变本轮验收结论；因此 live set、old generation、native committed、FD、线程和 buffer/pool 长期增长斜率全部未验证，不声明无泄漏。
- 未测范围：其余五条产品线、强平/资金费/ADL/保险基金/结算/触发单独立场景、API/Aeron Cluster/Kafka/WebSocket/PostgreSQL 端到端、生产同型隔离 CPU、40 分钟长稳和 exporter PostgreSQL 集成。
- 结论：当前 `256 in-flight` 工作区快照未通过锁定的主吞吐和尾延迟门禁；正确性、GC pause、JFR 数据完整性及同步主链路无 I/O 等证据通过，但缺少长稳、完整资金明细和若干业务计数，只能记录为“主门禁失败、完整验收部分验证”，不得宣称性能验收完成。

### 2026-08-31 12:17:50 +08:00 — `PV-20260831-256-02` — `采集前锁定`

- 记录创建时间：`2026-08-31 12:17:50 +08:00`（`2026-08-31T04:17:50Z`）。本条只追加、不修改此前记录；任何参数或门禁变更都必须终止本轮并另建记录。
- 被测代码：git HEAD `1f3a24cf8bc6085239f6ae014db256a2d22fb066`；工作区含用户既有未提交修改及本轮 P0/P1 实现，采集前 tracked binary diff SHA-256 `1b49f3e812772661a8ff9de2d51566fd14954b2e63564c305b2dc1b0b4986e20`，全部非忽略 untracked 文件内容清单 SHA-256 `67aa03c58e9dfd2d5a27cd43b342139909afe1e7f0781d48944913f9cde9d343`。结果只对该工作区指纹与本条性能记录追加有效。
- 对照：同机同 JDK、4 matcher 的 `PV-20260831-256-01` 主结果 `4738.952 ± 704.371 terminal business ops/s`；历史 commit `b285557f9cb2182039d37452663ae83b79d0aa94` 的同类 4 matcher 结果 `5744.730 ± 276.257 ops/s`。matcher=1 与 matcher=4 的本轮比较必须使用同一代码、同一参数和独立 JVM，不把旧结果直接当成本轮 A/B 样本。
- 修改点：PendingMatching O(1) sequence/command/user 索引；Core Fact materialization 从 `ArrayBlockingQueue + CompletableFuture/Task` 改为有界 SPSC slot 与原位完成状态；commit patch/journal 去除 provisional patch、Record、共享 backlog 原子热点；Lane completion 去除 monitor，生命周期 Lane task/worker 复用并懒启动；响应直接编码到 session scratch；terminal retention owner-confined；primitive journal/index 构建；匹配 barrier 后按日志顺序恢复独立 matcher pipeline；修复 Aeron session ID 复用时旧 egress 实例滞留。
- 验证问题：在 8 物理核本机、512 个活跃 symbol、固定 `256 in-flight` 下，`matching-engines=1` 是否因减少 exchange-core/Disruptor busy-spin 线程与跨 matcher 协调而高于 `matching-engines=4`；同时确认 P0/P1 改动没有破坏资金、订单终态、快照恢复或 Core Fact 顺序。
- in-flight：严格固定 `256`；本轮不采集、不补跑、不比较任何其他 in-flight 档位。
- 通过标准：
  - 正确性：每个 JMH/soak 运行均满足 accepted business operations = terminal business operations、accepted Core messages = terminal Core messages、两个 unfinished 为 0、期末 backlog 为 0；拒绝、错误、超时和 producer starvation 为 0；teardown 的余额/冻结/持仓/活动订单、资金守恒及 snapshot recovery 全部通过。
  - 主吞吐：matcher=1 与 matcher=4 各自采用无 profiler、无 NMT、3 forks 的相同主场景；报告 score、error/置信区间与逐 fork 数值。优化版本门禁仍为 `terminal business ops/s >= 5457.494`（历史 5744.730 的 -5%）；A/B 只有在置信区间、逐 fork 方向和同机系统有效性共同支持时才声明某 matcher 数更快，否则结论为无显著差异。
  - 延迟：按业务事件报告 entry→accepted、accepted→terminal、entry→terminal 的 p50/p90/p95/p99/p99.9/max、样本数、`1 ns–30 s` 直方图区间与 30 秒超时；entry→terminal p99 `<=100 ms`、p99.9 `<=125 ms`、max `<=250 ms`。
  - backlog：最大 matching backlog `<=256`、期末 0；同时报告平均值、满窗口比例与 completion mailbox 高水位。
  - GC/分配：独立 `-prof gc` 报告 allocation rate、bytes/JMH invocation、折算 bytes/terminal business op、GC count/time；ZGC allocation stall/OOM 为 0，JFR pause max `<=1 ms`。
  - JFR/NMT：matcher=1 与 matcher=4 都采集原始 JFR、GC/safepoint log 与 NMT baseline/diff；要求 `DataLoss=0`、swap=0、CPU throttling=0、交易 owner 同步 file/socket/database I/O=0，并按 owner、matcher、risk、projection、Core Fact、snapshot、外围线程报告 CPU、分配、锁/park、safepoint、JIT 与异常。
  - 长稳/泄漏：选择本轮主吞吐较高且正确性通过的 matcher 配置，在相同 `256 in-flight` 场景执行 40 分钟 soak；至少 3 个 post-GC 点，live set、native committed、线程数、FD 和 Direct/Mapped/pool 余额不得出现无法解释的单调增长，结束时资金与 snapshot recovery 检查通过。若主门禁先失败，仍运行长稳用于本次涉及 outbox/snapshot 长期状态改动的泄漏证据，但最终结论保持失败或部分验证。
- 固定场景：
  - 产品线仅 `LINEAR_PERPETUAL`；10,000 活跃用户，512 挂牌且活跃 symbol，4 Account Lane，1 risk engine，单 JMH worker；maker/taker 连续配对，基础设施/做市账户持续提供对手盘；每用户最多 5 个持仓、10 个未成交单。
  - `LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`：open-loop constant-arrival、offered rate `100,000 business operations/s`、修正 coordinated omission、16,384 operations/invocation、export ACK interval 1,024、BUSY_SPIN matcher；唯一 A/B 变量是 `matching-engines=1` 或 `4`。
  - 主吞吐每配置 5×5 秒预热、5×5 秒测量、3 forks；GC 每配置 2×5 秒预热、3×5 秒测量、1 fork；JFR/NMT 每配置 2×5 秒预热、1×30 秒测量、fork 0。为控制热/调度漂移，运行顺序记录在 artifact，不并行运行两个配置。
  - `OwnerCommitPatchBenchmark.*` 采用同一 10k×512、4 Lane、`256 in-flight`、16,384 operations/invocation、100k offered rate，覆盖 commit patch/journal、snapshot recovery；无 profiler主轮 2×2 秒预热、3×3 秒测量、3 forks，另跑 `-prof gc` 与 10 秒 JFR/NMT。
  - `CoreResponseEncodingBenchmark.*` 采用 dataBytes=0/4096、单线程、5×1 秒预热、5×1 秒测量、3 forks，并跑 1 fork `-prof gc`；该微基准只归因响应编码分配，不替代真实 Product Core 场景。
  - 40 分钟 soak：30 秒预热、2,400 秒测量、10 秒采样，配置与获胜 matcher 主场景一致；不运行 wallet、Kafka、PostgreSQL、API 或 WebSocket。
- 固定环境：Intel Core i9-9880H（8C/16T），16 GiB，macOS 26.7 / Darwin 25.6.0 x86_64，非容器、未绑核；同机用户进程不主动终止，出现 swap、明显 thermal/调度漂移或 JFR DataLoss 时该轮无效。
- JDK/Maven：Oracle GraalVM `25.0.1+8.1`，`Java HotSpot(TM) 64-Bit Server VM`；Maven `3.9.16` 明确通过该 JAVA_HOME 运行，禁止 OpenJ9 与自动降级。
- JVM：`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`，开放 `jdk.internal.misc/ref`；settlement BLOCKING、completion spins 16,384、projection PARKING、projection batch 64/4 MiB、commit journal 65,536/1 GiB、export pending 256 MiB。matcher 数仅按上述 A/B 改为 1 或 4。
- JFR：显式 `owner-commit-profile.jfc`，SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`；qualification script SHA-256 `570d46277f891aa4d37eb838469a2404baf1f2278d762b4b46f7da7f891b8d1b`；启用 NMT summary、GC/safepoint 日志，记录 profiler 额外开销且不与无 profiler 主分数混比。
- artifact 根目录：`target/qualification/20260831T041750Z-p0p1-256/`，子目录 `matcher-1/`、`matcher-4/`、`owner-commit/`、`response-encoding/`、`soak/`；结束后生成大小与 SHA-256 清单并把成功、失败和无效轮次按时间追加回本文件。

### 2026-08-31 12:46:47 +08:00 — `PV-20260831-256-02` — `终止/无效（场景缺陷与 swap）`

- 本轮在 owner-commit JMH setup 发现 `business order before-value mismatch`，定位为基准订单 ID 从 `10001` 起、与 10k×512 初始订单簿已有 ID 冲突，却把 before-value 标为 null。已中止剩余 forks；随后修复基准为从初始最大 order ID 之后连续分配，并新增 dense 初态回归。由于采集开始后改变了基准场景代码，本轮不得与后续结果拼接或作为验收结论。
- 采样前 swap 已为 `573.25 MiB`，结束检查仍为 `541.25 MiB`，违反本轮锁定的 swap=0 有效性条件；同时存在用户 Kafka JVM，未擅自停止。因此本轮全部数据仅作诊断。
- 诊断 A/B（固定 256，主轮均 3 forks、15 measurement）：matcher=4 `8391.376 ± 114.765 terminal business ops/s`，matcher=1 `8788.731 ± 114.451 ops/s`，1 比 4 高 `4.735%`；两者 accepted=terminal、unfinished=0。GC 轮分别约 `101,954` 与 `101,184 B/terminal business op`。
- 响应编码诊断：data=0 为 `25,783,351.485 ± 718,423.126 ops/s`，data=4096 为 `9,987,377.273 ± 125,956.904 ops/s`；`-prof gc` 两者约 `0.001 B/op`、GC count≈0。
- owner-commit：在第一个 benchmark setup 即失败，没有主分数、GC 或 JFR/NMT；失败 artifact 保留在 `target/qualification/20260831T041750Z-p0p1-256/owner-commit/`。
- 结论：不能作为性能验收或 matcher 配置定论；仅提示本机该场景 matcher=1 可能优于 4，必须在修复后的新记录中重跑。

### 2026-08-31 12:46:47 +08:00 — `PV-20260831-256-03` — `采集前锁定`

- 被测代码：git HEAD `1f3a24cf8bc6085239f6ae014db256a2d22fb066`；tracked binary diff SHA-256 `ec5db2debb27c766bf48ce10cf9cd9781c3e0ef142bcf22a208bfa1516d7841d`，全部非忽略 untracked 内容清单 SHA-256 `0f9716d8201b1a41ebcadb058e8d87f9f1674b332f8c6e4f7fe25172b80fad49`。相对 02 唯一新增实现是 owner-commit 基准 ID 修复及其测试；生产 P0/P1 代码未再改变。
- 对照：历史正式 4 matcher `5744.730 ± 276.257 terminal business ops/s`，门禁仍为其 -5% 即 `5457.494`；02 的 matcher=1/4 数值仅作诊断，不作为本轮样本。
- in-flight：只允许 `256`，不运行任何其他档位。
- 固定业务场景：`LINEAR_PERPETUAL`，`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`，10,000 活跃用户、512 挂牌/活跃 symbol、4 Account Lane、1 risk engine、单 JMH worker、每用户最多 5 持仓/10 未成交单；open-loop constant-arrival 100,000 offered business ops/s，coordinated omission corrected，16,384 operations/invocation，ACK interval 1,024，BUSY_SPIN；A/B 唯一变量为 matcher=4 或 1。
- 主轮：每配置 5×5 秒预热、5×5 秒测量、3 forks；要求 accepted business=terminal business、accepted Core=terminal Core、unfinished=0、期末 backlog=0、最大 backlog<=256、拒绝/错误/超时/starvation=0，并报告 terminal business ops/s、terminal Core messages/s、fills/trades 缺口、逐 fork、error/区间。
- 延迟门禁：entry→accepted、accepted→terminal、entry→terminal 均报告 p50/p90/p95/p99/p99.9/max、样本数、`1 ns–30 s` histogram 和 30 秒 timeout；entry→terminal p99<=100 ms、p99.9<=125 ms、max<=250 ms。
- GC/JFR：每配置 `-prof gc` 为 2×5 秒预热、3×5 秒测量、1 fork；JFR/NMT 为 2×5 秒预热、1×30 秒测量、fork 0。要求 ZGC allocation stall/OOM=0、pause max<=1 ms、JFR DataLoss=0、交易 owner 同步 I/O=0，报告按线程组 CPU/等待/锁、allocation class/site/thread、heap/live set、NMT/native/direct/mapped、safepoint、JIT、异常、系统 CPU/throttling/swap。
- owner-commit：修复后的 `OwnerCommitPatchBenchmark.*`，10k×512、4 Lane、256 in-flight、16,384 ops/invocation；2×2 秒预热、3×3 秒测量、3 forks，另有 `-prof gc` 和 10 秒 JFR/NMT；必须覆盖 patch seal/publish/apply、fanout、incremental hash、Core Fact 与 snapshot recovery，资金/哈希/指纹/恢复一致。
- response encoding：dataBytes=0/4096，5×1 秒预热、5×1 秒测量、3 forks，加 1 fork `-prof gc`；期望复用 destination 下近零 B/op，不替代真实 Core 场景。
- 长稳：选择主吞吐较高且正确性通过的 matcher，在相同 256 场景执行 30 秒预热+2,400 秒测量、10 秒采样；至少 3 个 post-GC 点，live set/native committed/thread/FD/Direct/Mapped/pool 不得有无法解释的单调增长，结束时资金与 snapshot recovery 通过。
- 环境/JVM：Intel i9-9880H 8C/16T、16 GiB、macOS 26.7/Darwin 25.6.0、非容器/未绑核；Oracle GraalVM HotSpot 25.0.1+8.1、Maven 3.9.16；`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC`，settlement BLOCKING、completion spins 16,384、projection PARKING/batch 64/4 MiB、journal 65,536/1 GiB、export 256 MiB。JFR 配置 SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。
- 数据有效性：当前 swap 基线 `541.25 MiB` 且用户 Kafka JVM 仍在，按门禁预期本机结果只能是诊断数据；不擅自停止用户进程。仍完整采集以回答相同受扰环境下 matcher 方向，但不得宣称生产容量认证。发生参数变更、代码再改、DataLoss、明显 throttling/thermal 或额外负载时终止并另建记录。
- artifact 根目录：`target/qualification/20260831T044647Z-p0p1-256-r2/`；matcher-1/4、owner-commit、response-encoding、soak 分目录，结束后生成大小与 SHA-256 清单并追加全部成功/失败/无效结果。

### 2026-08-31 23:35:03 +08:00 — `PV-20260831-256-04` — `采集前锁定（诊断）`

- 被测 git commit：`655d7f275c8f7d3e3652b7141904ec425cb4fcaa`，分支 `codex/aeron-unified-core`；tracked working tree clean，仅保留既有非忽略 untracked `openai` 和三个 `.factorypath`，均不进入构建与采样 classpath。
- 对照 git commit：本次修改前的直接父提交 `e7a88397367051953efbf160f3332908b14c5b2c`。不查询或引用更旧历史性能数据；父提交仅有同机、同为 256、带 JFR/GC 的即时诊断快照，无合格的同参数无 profiler 主轮，因此本轮主吞吐只报告绝对值，不做正式 A/B 结论。
- 修改点：撮合命令 fingerprint 单次计算与无克隆读取；pending 幂等从 terminal ledger 移入 pending ring；移除结果贡献 Map 与重复 runtime/order 查询；复用 order view、Core Fact metadata、lane/topology hash；lane apply 改为 primitive long；business/funds rolling hash 使用 primitive map 与 owner `prepareApplied` 单次应用；PatchChain 去临时数组；补齐哈希中途失败时 PreparedChanges 回滚。
- in-flight：严格固定 `256`，不运行、不补跑、不比较任何其他档位。
- 验证范围：只测 `LINEAR_PERPETUAL` Product Core 主链路；不启动 Docker、wallet、exporter、外部 history projection、Kafka/PostgreSQL、API gateway 或 WebSocket。JMH 进程内的 runtime commit projection、Core Fact materializer、snapshot/recovery 校验属于场景本身并保留。
- 固定场景：`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`；10,000 活跃用户，512 挂牌/活跃 symbol，4 Account Lane，1 matcher，1 risk engine，1 JMH worker；maker/taker 连续配对，16,384 business operations/invocation，open-loop 100,000 offered operations/s，coordinated omission corrected，最大持仓 5、最大未成交单 10、ACK interval 1,024、BUSY_SPIN matcher。
- 正确性标准：每轮 accepted business operations = terminal business operations、accepted Core messages = terminal Core messages、两个 unfinished 为 0、期末 backlog 为 0、最大 backlog `<=256`；拒绝、错误、超时和 starvation 为 0；teardown 不抛异常，并通过资金守恒、余额/冻结/持仓、订单终态、活动订单不增长与 snapshot recovery 检查。缺失的 fills/trades 或逐账户资金明细必须明确记录，不能推算。
- 性能与延迟标准：主轮报告无 profiler `terminal business ops/s`、`terminal Core messages/s`、score error/区间及逐 fork；三段延迟报告 p50/p90/p95/p99/p99.9/max、样本数、`1 ns–30 s` histogram 和 30 秒 timeout。诊断参考门禁仍为 entry→terminal p99 `<=100 ms`、p99.9 `<=125 ms`、max `<=250 ms`，但因环境无效不得据此宣称生产验收。
- 主吞吐参数：5×5 秒 warmup、5×5 秒 measurement、3 forks、1 thread；无 JFR、无 NMT、无 GC profiler，输出 JSON。
- 归因参数：2×3 秒 warmup、3×5 秒 measurement、1 fork、1 thread；`-prof gc`，同时启用 JFR、NMT summary 和 GC/safepoint log。JFR 要求 DataLoss=0、ZGC allocation stall/OOM=0、pause max `<=1 ms`、owner 同步 file/socket/database I/O=0，并报告 CPU/热点、分配、heap/GC、native/direct、线程/锁、safepoint、JIT、I/O/异常和系统事件。
- 环境/JVM：Intel Core i9-9880H 8C/16T、16 GiB，macOS 26.7/Darwin 25.6.0 x86_64，非容器、未绑核；Oracle GraalVM HotSpot 25.0.1+8.1，Maven 3.9.16；`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC`，completion spins 16,384、settlement BLOCKING、projection PARKING/batch 64/4 MiB、journal 65,536/1 GiB、export pending 256 MiB。JFR 配置 `owner-commit-profile.jfc` SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。
- 数据有效性：采集前发现并停止旧 JFR JSON 聚合残留（约 91% CPU、55% 内存）；停止后 swap 仍约 1.17 GiB，且用户 Kafka JVM继续运行。因此本轮预先判定只能作为最新代码诊断，不得作为生产容量、无回退或无泄漏认证；若采集中再出现旧分析进程、参数变化、JFR DataLoss 或测试失败，则如实标记失败/无效。
- 已完成的功能门禁：HotSpot JDK 25 下精确回归 `CoreProbeStateTest` 82/82；随后 service 及必要上游统一测试 498/498，0 failure、0 error。
- artifact 根目录：`target/qualification/20260831T153503Z-owner-opt-256-diagnostic/`；包含 Maven package log、无 profiler 主 JSON/log、GC/JFR JSON/log、原始 `.jfr`、GC/safepoint log、JFR summary/view/聚合、NMT 摘要及 artifact size/SHA-256 清单。

### 2026-08-31 23:55:52 +08:00 — `PV-20260831-256-04` — `诊断完成/部分验证`

- 实际采集时间：Maven package 于 `23:37:31–23:38:00 +08:00`；无 profiler 主轮约 `23:39:52–23:45:31 +08:00`；GC/JFR 归因轮记录时间 `23:46:37–23:47:22 +08:00`；分析与清单完成于 `23:55:52 +08:00`。
- 被测代码：生产与测试实现 commit `655d7f275c8f7d3e3652b7141904ec425cb4fcaa`；采集前锁定文档 HEAD `2c19dd2332a75a931846765da864983fd01898e5`。采集中 tracked 代码未变化，只运行固定 `256 in-flight`。
- 正确性门禁：采集前 `CoreProbeStateTest` 82/82、service 及必要上游统一测试 498/498，均 0 failure/0 error。主轮和归因轮均满足 accepted business = terminal business、accepted Core = terminal Core、两个 unfinished=0；teardown 未抛资金、订单终态或 snapshot recovery 验证异常，结束时窗口排空。

#### 吞吐、并发与 backlog

- 无 profiler 主结果：`6260.728 ± 2268.194 terminal business ops/s`，99.9% CI `[3992.534, 8528.921]`；`terminal Core messages/s=6266.842`。accepted 与 terminal 数值完全一致。
- 3 个 fork 均值依次为 `4950.024 / 5161.714 / 8670.445 terminal business ops/s`；15 个 measurement 范围 `4823.255–10494.341 ops/s`。最高单次 `10494.341` 是第三 fork 的末次迭代，不是稳定容量；第三 fork 在 measurement 内从 `5116.668` 连续升到 `10494.341`，说明预热/JIT/环境仍未稳定。
- GC/JFR 归因轮为 `9643.618 terminal business ops/s`、`9653.035 terminal Core messages/s`，3 次 measurement 为 `9394.421 / 9662.195 / 9874.237`；该轮带 JFR、NMT 和 GC profiler，只用于归因，不替代无 profiler 主分数。
- 固定并发：10,000 活跃用户、512 活跃/挂牌 symbol、4 Account Lane、1 matcher、1 risk engine、1 JMH worker、`256 in-flight`、16,384 operations/invocation、100,000 offered operations/s，open-loop constant-arrival 且 coordinated omission corrected。
- JFR measurement 的 9 个 invocation 共 147,456 个 business operations；最大 matching backlog `256`、平均 `232`、满窗口比例 `81.25%`、completion mailbox 高水位 `256`，producer starvation 为 0，teardown 后排空。
- benchmark 未输出独立 fills/s、trades/s、拒绝率、错误率、超时率或 batch 指标；不能从 Core message 数推算，均记为缺口。

#### 三段延迟

- 以下为 JFR measurement 最后 9 个 invocation 的 147,456 个 PLACE_ORDER 样本；p50–p99.9 是各 invocation 对应分位的中位数，max 是全局最坏 invocation 值。直方图区间 `1 ns–30 s`，timeout `30 s`，墙钟时间。
- entry→accepted：p50 `746.598 ms`、p90 `1344.174 ms`、p95 `1423.903 ms`、p99 `1480.136 ms`、p99.9 `1490.784 ms`、max `1571.248 ms`。
- accepted→terminal：p50 `20.312 ms`、p90 `23.574 ms`、p95 `24.541 ms`、p99 `26.225 ms`、p99.9 `28.036 ms`、max `35.681 ms`。
- entry→terminal：p50 `767.675 ms`、p90 `1364.538 ms`、p95 `1443.198 ms`、p99 `1499.356 ms`、p99.9 `1509.248 ms`、max `1589.410 ms`。
- entry→terminal 明显未通过 `100/125/250 ms` 诊断门禁；主要耗时仍在 entry→accepted 排队，不在 accepted→terminal 执行段。100k offered rate 远高于该机可持续接收能力。

#### 分配、GC、heap 与 native memory

- `-prof gc`：分配率 `841.634 MB/s`，`1,510,887,123 B/invocation`，折算 `92,217.232 B/terminal business op`；17 次 GC、并发 GC time `3526 ms`。与本次修改前、同参数即时 JFR/GC 诊断的 `101,002.345 B/business op` 相比下降 `8.698%`；两轮吞吐因旧 analyzer CPU/内存争用差异不可比较。
- JFR 45 秒内 11 次 ZGC、47 个 pause，总暂停 `0.702 ms`；p50 `0.0105 ms`、p90 `0.0263 ms`、p95 `0.0484 ms`、p99/p99.9/max `0.0553 ms`。`DataLoss=0`、ZGC allocation stall/OOM、allocation requiring GC、promotion/evacuation failure 均为 0。
- 分配类别 top：`long[] 8.75%`、`Object[] 6.62%`、`byte[] 6.46%`、stream Head `5.12%`、`Long 4.65%`、HashMap `3.85%`、immutable iterator `3.49%`。top sites 仍是 stream pipeline、primitive LongObjectHashMap 扩容、HashMap resize/put、ArrayList、`mergeTreasuryDeltas`、`RuntimeCommitPatch.tombstones/Builder`、FactViewMerge 和 `UserHash` copy。
- 分配线程：JMH/owner worker `19.7 GiB (67.07%)`；两个 Core Fact materializer 合计约 `8.3 GiB (28.30%)`；两个 internal commit projector 合计约 `738.8 MiB (2.46%)`；snapshot encoder `118.9 MiB`。
- heap committed 固定 8 GiB；JFR 最大 heap used `4.68 GiB`，最后一次 post-GC 为 `700 MiB`。post-GC 序列先升至 `2.68 GiB` 后回落到 `592–700 MiB`；短记录不能证明无 heap 泄漏。
- NMT 分类总 committed 从 `8,671,944,943 B` 到 `8,805,660,409 B`，峰值 `8,907,735,901 B`；末值主要为 heap 8 GiB、GC `77.6 MiB`、code `44.7 MiB`、metaspace `36.9 MiB`、tracing `19.7 MiB`。DirectBuffer 首末均 0，峰值 count 1、memory used 1 B；Mapped/pool 没有独立业务余额。
- Java 线程峰值/末值 `19/17`。系统 swap 在 JFR 前后均约 `921.25 MiB`，违反正式数据有效性条件；未执行长稳，不能对 live set、native committed、FD、线程和 buffer pool 增长斜率作无泄漏结论。

#### CPU、热点、锁、Safepoint、JIT 与 I/O

- JVM user CPU 平均 `40.18%`、system `1.21%`；机器总 CPU 平均 `43.50%`、最大 `73.70%`。八个 exchange-core/Disruptor 通用线程各约 `6.18–6.22%` user CPU；JMH owner worker `4.09%`，snapshot encoder `5.31%`、snapshot audit `2.27%`、internal projection 合计约 `2.88%`、Core Fact materializer 合计约 `1.64%`。
- 9,026 个 execution samples 中，`ProcessingSequenceBarrier.checkAlert 36.74%`、`WaitSpinningHelper.tryWaitFor 16.20%`、`getCursor 13.14%`、`getMinimumSequence 11.09%`，约 77% 仍是 matcher/Disruptor busy-spin 与 cursor 等待。业务侧 top 为 TreeMap、primitive LongInt/LongObject map、`RollingBusinessStateHash.mixOwnerDomain`、`CoreStateHash.mix`、runtime projection prevalidate/apply 和 `RollingFundsStateHash.mixOwnerDomain`。
- fingerprint、pending terminal ledger 写入、结果贡献 Map 和重复 order view/runtime lookup 已不在 top CPU/allocation site，说明删除工作确实离开主要热点；但剩余 owner/hash/projection 分配和 8 核上的 busy-spin 争用仍显著。
- 最长 monitor contention `579 ms` 位于 `AffinityThreadFactory.newThread` 启动阶段；业务 measurement 未见同量级 monitor 阻塞。1,008,748 次 ThreadPark 主要来自后台 worker 等待，不能等同于交易 owner 阻塞。
- 55 次 safepoint 总 `1.769 ms`、最大 `0.204 ms`；到达 safepoint 最大 `0.314 ms`。268 个 VM operations 总 `14.328 ms`、最长 `2.205 ms`，低于业务 accepted→terminal p99。
- compilation 9,718 次，最长 `973 ms`；长编译包括 exchange-core processor、snapshot codec、commit projector 与 prune terminal orders，主要发生在 warmup/measurement 初段。deoptimization 716 次；主轮第三 fork 的持续爬升显示 5×5 秒预热仍不足以获得稳定 fork。
- measurement 窗口的 FileRead 仅发生在 `main`/`Thread-0`，SocketWrite 仅在 `main`；交易 owner、matcher、projection、Core Fact 无同步 file/socket/database I/O。localhost socket 是 JMH/JFR/进程内管理活动，不在 owner 栈。
- measurement 后 `23:47:22` 有 1 次 JMH worker `InterruptedException`，栈为 `LinkedBlockingQueue.take`，发生在最后业务事件结束后约 1.8 秒的 teardown；其余反射/NoSuchMethod 异常集中在启动探测。容器 throttling 事件为 0，测试非容器。

#### Artifact、问题、未测范围与结论

- artifact 根目录：`target/qualification/20260831T153503Z-owner-opt-256-diagnostic/`；大小清单 `artifact-sizes.txt` 4,940 B，SHA-256 `4775366a0c289bade38303ce3eee510646d19cad0da4140dc456cada9b2b5ecc`；内容清单 `artifact-sha256.txt` 8,109 B，SHA-256 `11d11e491cf21568fb40545bf2e8508e95310ea9fb7ed7894f76a0c7a6f6efbf`。
- `main-256.json` 26,419 B，SHA-256 `68ae59ef7b5ff7f6dcdd9025c335fcd79cd6ba15a727e02cc5c904cd1c2291a3`；`attribution-256.json` 22,579 B，SHA-256 `3914b1e19fd53be6453586b404563eb544be24ab9ea11572eec968d518e8367a`。
- 原始 `attribution-256.jfr` 51,140,713 B，SHA-256 `9833a0b72a85929cf1e7a388da9744b152fd4cb44948a3934b337aeb6b05d826`；`jfr-summary.txt` 13,213 B，SHA-256 `a5d2826727c209071b52df34fa31ae7a71b14462550777d94927b366f7583b87`；同目录包含 GC/safepoint log、JFR view、三段延迟、heap/native/DirectBuffer、I/O/异常和 safepoint/VM 聚合 JSON。
- 主要问题：无 profiler 主轮跨 fork/iteration 漂移极大，预热不足或系统调度/JIT尚未稳定；swap 非零且 Kafka/桌面进程未隔离；entry→accepted 排队约 0.7–1.5 秒；约 `92.2 KiB/business op` 分配仍高；busy-spin/wait 仍占 CPU 样本主导；100k offered rate 与当前持续终态吞吐不匹配。
- 未测范围：其余五产品线、独立强平/资金费/ADL/保险基金/结算/触发单、fills/trades、完整逐账户与做市资金流水、API/Aeron Cluster/Kafka/exporter/PostgreSQL/WebSocket、40 分钟长稳、生产同型隔离 CPU 与零 swap 环境。没有启动 Docker、wallet、exporter 或外部 projection。
- 结论：最新代码在固定 256 场景下的已观测最高单次为 `10494.341 terminal business ops/s`，但可报告的无 profiler 聚合仅为 `6260.728 ± 2268.194`，不能视为稳定上限；修改后单位业务分配下降约 `8.70%`，被删除的 fingerprint/ledger/重复 lookup 工作已退出 top 热点。正确性、短时 GC pause、DataLoss 和同步 owner I/O 门禁通过；吞吐稳定性、entry→terminal 延迟、零 swap、长稳泄漏与完整业务指标未通过或缺失，因此只能标记“诊断完成/部分验证”，不得宣称性能验收完成。

### 2026-09-01 07:28:56 +08:00 — `PV-20260901-256-01` — `采集前锁定（同代码重复性诊断）`

- 被测实现：commit `655d7f275c8f7d3e3652b7141904ec425cb4fcaa`；采集前文档 HEAD `165fc079e65c0e3e2adf0c6533908758df0864e9`。tracked working tree clean；复用上一轮由该实现构建的 `product-core-benchmarks.jar`，SHA-256 `3937655195654580a644384d87476d0e28b14223a9a39454dd1cab2009c0c6a4`。
- 对照：只对照同 commit、同 JAR、同参数的紧邻记录 `PV-20260831-256-04`，其无 profiler 主结果为 `6260.728 ± 2268.194 terminal business ops/s`、fork 均值 `4950.024 / 5161.714 / 8670.445`，GC/JFR 归因分配为 `92,217.232 B/business op`。不查询或引用更旧性能历史。
- 目标：验证上一轮跨 fork 大幅漂移是否可复现，并重新采集最新代码的绝对吞吐、三段延迟、GC/分配和 JFR 热点；本轮不修改代码、场景、阈值或 JVM 参数。
- in-flight：严格固定 `256`；不运行、不补跑、不比较其他档位。
- 固定场景：仅 `LINEAR_PERPETUAL` 的 `LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`；10,000 活跃用户、512 挂牌/活跃 symbol、4 Account Lane、1 matcher、1 risk engine、1 JMH worker，maker/taker 连续配对，16,384 business operations/invocation，open-loop constant-arrival 100,000 offered operations/s，coordinated omission corrected，最大持仓 5、最大未成交单 10、ACK interval 1,024、BUSY_SPIN matcher。
- 主轮：无 profiler、无 NMT、5×5 秒 warmup、5×5 秒 measurement、3 forks、1 thread；报告 `terminal business ops/s`、`terminal Core messages/s`、误差/区间、逐 fork、最大/期末 backlog、accepted/terminal、unfinished、starvation 及缺失业务指标。
- GC/JFR 轮：`-prof gc`、2×3 秒 warmup、3×5 秒 measurement、1 fork、1 thread；同时启用 `owner-commit-profile.jfc`、NMT summary 和 GC/safepoint log。JFR 配置 SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。
- 正确性与数据门禁：accepted business=terminal business、accepted Core=terminal Core、两个 unfinished=0、期末 backlog=0、最大 backlog `<=256`、producer starvation=0，teardown 资金/余额/冻结/持仓/订单终态与 snapshot recovery 不得失败；JFR DataLoss、ZGC allocation stall/OOM、owner 同步 I/O 均为 0，pause max `<=1 ms`。
- 延迟门禁：entry→accepted、accepted→terminal、entry→terminal 均报告 p50/p90/p95/p99/p99.9/max、样本数、`1 ns–30 s` histogram 与 30 秒 timeout；entry→terminal p99 `<=100 ms`、p99.9 `<=125 ms`、max `<=250 ms`。
- 环境/JVM：Intel i9-9880H 8C/16T、16 GiB、macOS 26.7/Darwin 25.6.0，非容器、未绑核；Oracle GraalVM HotSpot 25.0.1+8.1；8 GiB ZGC、AlwaysPreTouch、DisableExplicitGC，4 Lane/1 matcher、completion spins 16,384、projection PARKING/batch 64/4 MiB、journal 65,536/1 GiB、export pending 256 MiB。
- 环境有效性：采集前 swap `377.25 MiB`，用户 Kafka JVM继续运行；不停止用户进程。因此本轮预先限定为同机重复性诊断，不能作为生产容量、零回退或无泄漏认证。采集前 Safari SafeBrowsing 的瞬时高 CPU 已自然回落；未发现旧 JFR analyzer 或其他 benchmark 进程。
- 功能证据复用：实现和 benchmark JAR 与上一轮完全一致，复用 HotSpot 25 下 `CoreProbeStateTest` 82/82 及 service/上游统一测试 498/498 的 0 failure/0 error 证据，本轮不重复 Maven 测试。
- 不启动 Docker、wallet、外部 exporter/history projection、Kafka/PostgreSQL 测试、API gateway 或 WebSocket；JMH 进程内 runtime commit projection、Core Fact materializer 与 snapshot/recovery 校验保留。
- artifact 根目录：`target/qualification/20260831T232856Z-owner-opt-256-rerun/`；结果按成功、失败或无效状态追加，不覆盖上一轮。

### 2026-09-01 07:41:43 +08:00 — `PV-20260901-256-01` — `重复性诊断完成/部分验证`

- 实际采集时间：无 profiler 主轮约 `07:31:23–07:35:55 +08:00`；GC/JFR 归因 recording 为 `07:36:15–07:37:05 +08:00`，50 秒；分析和 artifact 清单完成于 `07:41:43 +08:00`。
- 被测代码：生产与 benchmark 实现仍为 commit `655d7f275c8f7d3e3652b7141904ec425cb4fcaa`；JAR SHA-256 `3937655195654580a644384d87476d0e28b14223a9a39454dd1cab2009c0c6a4`，与紧邻上一轮完全相同。采集前锁定文档 commit `2fc0208a`；本轮没有代码修改，只运行 `256 in-flight`。
- JVM 门禁：`java -version` 和 `mvn -version` 均确认 Oracle GraalVM Java HotSpot 25.0.1+8.1、Maven 3.9.16；没有使用 OpenJ9。功能证据复用同 JAR 的 `CoreProbeStateTest` 82/82 和 service/必要上游 498/498，本轮未触发 Maven 或其他后续测试。
- 正确性：主轮与归因轮都满足 accepted business=terminal business、accepted Core=terminal Core、两个 unfinished=0、producer starvation=0；每个 JFR measurement invocation 均终态 16,384 business operations/16,400 Core messages，teardown 未抛资金、余额/冻结/持仓、订单终态或 snapshot recovery 异常。

#### 吞吐、并发与 backlog

- 无 profiler 主结果：`10284.474 ± 194.131 terminal business ops/s`，99.9% CI `[10090.343, 10478.605]`；`terminal Core messages/s=10294.518`。3 个 fork 均值 `10128.839 / 10481.286 / 10243.298`，15 个 measurement 范围 `9991.997–10525.061 ops/s`。
- 最新代码当前已观测最高单次为 `10525.061 terminal business ops/s`。相比紧邻同代码、同 JAR、同参数记录的最高单次 `10494.341` 仅高 `0.293%`；但主聚合比上一轮 `6260.728` 高 `64.270%`。由于代码完全相同，这不是代码性能提升，而是证明上一轮低位 fork/大幅爬升没有复现，上一轮聚合不能代表稳定容量。
- 本轮主轮标准差 `181.590 ops/s`，约为均值 `1.77%`；最低到最高跨度 `5.34%`。三个 fork 都落在约 10.1k–10.5k 区间，重复性明显好于上一轮，但主分数仍受非隔离桌面环境影响。
- GC/JFR 归因轮为 `9268.481 terminal business ops/s`、`9277.533 terminal Core messages/s`，3 次 measurement 为 `9088.266 / 9408.880 / 9308.298`；该轮包含 JFR、NMT 和 `-prof gc` 开销，仅用于归因。
- 并发和负载保持锁定：10,000 活跃用户、512 活跃/挂牌 symbol、4 Account Lane、1 matcher、1 risk engine、1 JMH worker、`256 in-flight`、16,384 business operations/invocation、100,000 offered operations/s、open-loop constant-arrival、coordinated omission corrected。
- 最后 9 个 JFR measurement invocation 共 147,456 business operations、147,600 Core messages；最大 backlog `256`、平均 `232`、满窗口 `81.25%`、completion mailbox 高水位 `256`、producer starvation=0，teardown 后排空。
- benchmark 仍未单独输出 fills/s、trades/s、拒绝率、错误率、超时率和 batch 指标，不能从 Core message 数代推。

#### 三段延迟

- 以下覆盖最后 9 个 measurement invocation 的 147,456 个 PLACE_ORDER 样本；p50–p99.9 为各 invocation 对应分位的中位数，max 为全局最坏 invocation。直方图区间 `1 ns–30 s`，timeout 30 秒，墙钟时间。
- entry→accepted：p50 `766.241 ms`、p90 `1409.745 ms`、p95 `1492.711 ms`、p99 `1553.266 ms`、p99.9 `1566.437 ms`、max `1814.325 ms`。
- accepted→terminal：p50 `21.202 ms`、p90 `23.915 ms`、p95 `24.619 ms`、p99 `28.588 ms`、p99.9 `31.715 ms`、max `124.958 ms`。
- entry→terminal：p50 `791.250 ms`、p90 `1430.919 ms`、p95 `1516.319 ms`、p99 `1575.951 ms`、p99.9 `1587.423 ms`、max `1834.180 ms`。
- entry→terminal 仍未通过 `100/125/250 ms` 门禁，且略差于紧邻上一轮；主要耗时依旧是 offered rate 远高于持续处理能力造成的 entry→accepted 排队。accepted→terminal 的典型分位稳定在约 21–32 ms，但单次最坏值上升到 `124.958 ms`。

#### 分配、GC、heap 与 native memory

- `-prof gc`：分配率 `820.601 MB/s`，`1,535,932,074 B/invocation`，折算 `93,745.854 B/terminal business op`；23 个 profiler GC count、并发 GC time `4048 ms`。单位分配比紧邻上一轮 `92,217.232 B/op` 高 `1.658%`，同代码下可视为重复采样差异，不能解释成代码回退；但约 `91.5 KiB/op` 仍然很高。
- 50 秒 JFR 内 11 次 ZGC、47 个 pause，总暂停 `0.808 ms`；p50 `0.0109 ms`、p90 `0.0486 ms`、p95 `0.0538 ms`、p99/p99.9/max `0.0602 ms`。`DataLoss=0`、ZGC allocation stall/OOM、allocation requiring GC、promotion/evacuation failure均为 0。
- 分配类别 top：`long[] 8.49%`、`Object[] 6.92%`、`byte[] 6.20%`、immutable list iterator `4.59%`、`Long 4.46%`、stream Head `4.29%`、HashMap `3.56%`、stream map `3.05%`。top site 仍包括 stream pipeline、HashMap resize/put、primitive LongObjectMap 扩容、`mergeTreasuryDeltas`、`RuntimeCommitPatch.Builder/tombstones/Changes.seal`、FactViewMerge 和 UserHash。
- 分配线程累计增量 top：JMH owner/driver约 `19.17 GiB`、Core Fact materializer约 `5.36 GiB`、internal commit projector约 `541.8 MiB`；说明主要分配仍在输入/owner和 Core Fact materialization，不是 GC pause 本身。
- heap committed 固定 8 GiB；最大 used `6.59 GiB`，最后 post-GC `682 MiB`，记录内最大 post-GC约 `1.75 GiB`。短 JFR 不能证明 live set 无增长或无泄漏。
- NMT 分类总 committed 从 `8,672,136,949 B` 到 `8,904,944,109 B`，末值主要包含 8 GiB heap、GC约 `175.5 MiB`、code约 `42.9 MiB`、metaspace约 `36.9 MiB`、tracing约 `18.4 MiB`；ZGC reserved address space使总 reserved约 `137.57 GiB`，不等于物理占用。DirectBuffer 首末均 0，峰值 count 1、used 1 B；没有 Mapped/业务 pool 余额证据。
- Java 线程峰值/末值 `19/17`。JFR 和前后 `sysctl` 的 swap 均为 `377.25 MiB`；物理内存接近满载。未执行 40 分钟长稳，不能给出 heap/native/线程/FD/buffer pool 无泄漏结论。

#### CPU、热点、锁、Safepoint、JIT 与 I/O

- JVM user CPU 平均 `37.46%`、system `0.95%`；机器总 CPU 平均 `40.44%`、最大 `50.88%`。八个 exchange-core/Disruptor 通用线程各约 `6.20%` user CPU；snapshot encoder `4.65%`、JMH owner/driver `4.59%`、internal projection合计约 `2.49%`、snapshot audit `1.50%`、Core Fact materializer合计约 `1.71%`。
- 9,407 个 execution samples 中，`ProcessingSequenceBarrier.checkAlert 36.46%`、`WaitSpinningHelper.tryWaitFor 16.50%`、`ProcessingSequenceBarrier.getCursor 12.68%`、`Util.getMinimumSequence 10.32%`，合计约 `75.96%`，仍是 exchange-core/Disruptor busy-spin 和 cursor 等待。业务侧 top 仍为 TreeMap、primitive/ConcurrentHashMap、`CoreStateHash.mix`、rolling business/funds hash、runtime projection prevalidate/apply、`stagePatch` 与少量 `resultEntryDigest`。
- fingerprint、pending terminal ledger 写入、结果贡献 Map 和重复 order lookup依然没有回到 top 热点，说明已删除工作没有被其他路径重新引入；下一阶段真正要优化的是剩余状态复制/stream/Map 分配、hash/projection，以及 8 核机器上 busy-spin 线程配置。
- 最长 monitor contention `318 ms` 在 `AffinityThreadFactory.newThread` 启动阶段。55 次 safepoint 实际停顿总 `2.064 ms`、最大 `0.432 ms`；但到达 safepoint 最大 `93.140 ms`，有 2 次超过 10 ms，说明 busy-spin/调度会拖慢线程汇合，并可能贡献 accepted→terminal 最坏尾部。213 个 VM operations总 `10.347 ms`、最长 `1.056 ms`。
- compilation 10,656 次、各编译线程累计 `39.275 s`，最长单次 `754.779 ms`；measurement 期间仍有 exchange-core processor、snapshot codec、projector/prune 等长编译。deoptimization 516 次，说明 2×3 秒的归因预热不足以完全越过 JIT 阶段，但不影响无 profiler 主轮分数口径。
- 测量窗口仅记录 10 个 I/O 事件、共 4,810 B：`main` 线程 8 个 JAR/localhost 管理 I/O，JMH owner/driver线程 2 个 benchmark JAR FileRead、合计 1,551 B。未发现数据库、业务文件、Kafka或外部网络 I/O；但严格的 owner 同步 I/O=0 门禁因这 2 次延迟类加载读取而未通过，应通过预热类加载或将类加载完成门禁前移后再验。
- 测量窗口 8 个异常都在 `main` 的反射探测（7 NoSuchMethod、1 NoSuchField），不在交易 owner；Container CPU throttling和 JFR DataLoss均为 0。用户既有 Kafka、Redis和PostgreSQL进程继续运行，本轮没有启动或调用它们。

#### Artifact、问题、未测范围与结论

- artifact 根目录：`target/qualification/20260831T232856Z-owner-opt-256-rerun/`，共 55 个文件、约 120 MiB；SHA 清单 `artifact-sha256.txt` 7,983 B，SHA-256 `90dace8e9f6dab97c2bec41c8350a6c2271494a42f8380c5b868b34a787588cc`；size清单 `artifact-sizes.txt` 4,749 B，SHA-256 `fb8a41f8713d23e5d349a435909641efd3cdd751fd276453943c3f54ebe31889`。
- `main-256.json` 26,502 B，SHA-256 `3f79f99e52349969318a65f8a602d4554c946d828455d7dd14f27ab8efe37c80`；`attribution-256.json` 22,687 B，SHA-256 `c36eba915b8f6740f2605ccb6be17f17f5e5614ed1d3a0fc232d2d0de54f0309`。
- 原始 `attribution-256.jfr` 52,827,274 B，SHA-256 `a37559e1dec09c5167d3ad7f4cc9072085ebeca9cb3ad121ffacc8e9df83efc5`；`jfr-summary.txt` 13,213 B，SHA-256 `901cd52bd9266c3a6aca1857e0b4434a9fd0c8efc45e3af06f967f2aa0271f03`。同目录包含 GC/safepoint log、JFR views、三段延迟、heap/native/DirectBuffer、I/O/异常、safepoint/VM/JIT聚合证据。
- 主要问题：约 `91.5 KiB/business op` 分配仍高；100k offered rate造成 0.8–1.8 秒排队；约 76% CPU样本是 busy-spin/cursor等待；到达 safepoint出现 `67.43/93.14 ms` 尾部；归因窗口仍有长 JIT compilation；owner/driver测量窗口存在 2 次 JAR同步读取；swap和用户服务未隔离。
- 未测范围：其他 in-flight级别（按约束不测）、其余五产品线、独立强平/资金费/ADL/保险基金/结算/触发单、fills/trades、完整逐账户与做市资金流水、API/Aeron Cluster/Kafka/exporter/PostgreSQL/WebSocket、40 分钟长稳、隔离 CPU和零 swap环境。没有启动 Docker、wallet、exporter或外部 projection；进程内 runtime commit projection、Core Fact materializer与 snapshot/recovery包含在本场景中。
- 结论：最新代码在固定 `256 in-flight` 下，本轮可报告的无 profiler聚合为 `10284.474 ± 194.131 terminal business ops/s`，当前最高单次 `10525.061`；相同代码下三个 fork都稳定在约 10k，上一轮 6.26k 聚合的低位漂移没有复现。正确性闭环、GC pause、DataLoss和短时内存门禁通过；entry→terminal延迟、strict owner I/O=0、零 swap、长稳泄漏及完整业务指标未通过或缺失，因此仍只能标记“重复性诊断完成/部分验证”，不能宣称正式性能验收完成。

### 2026-09-01 09:59:20 +08:00 — `PV-20260901-256-02` — `采集前锁定（五项 owner 后处理优化）`

- 被测 git commit：`ae27913a58cbb0935746da93d65915497d6eceb5`，分支 `codex/aeron-unified-core`，已推送；对照 commit 为直接父提交 `d33393678c1d75f02863eca47bfa773fca5ff0d0`。遵循本轮要求，不检索、不重跑或比较旧历史性能数据；对照 commit 只用于界定代码差异，本轮采用预先锁定的绝对门禁。
- 修改点：单 patch Core Fact 绕过 `FactViewMerge/TreeMap` 并直接复用 identity slice；Core Fact 用户变化按 user 建 primitive 索引后对 balance/reservation/position/leverage 各扫描一次；projection 将预校验和应用准备合并为一次 user 索引构建；projection、business hash、funds hash 的热路径回滚改为可复用/紧凑 typed journal，移除热路径 lambda 与 `ArrayDeque`；matcher 默认和本场景固定 `YIELDING`，matching engine 固定 1，降低 8 核环境的忙等争用。
- 采集前功能门禁：Oracle GraalVM Java HotSpot 25.0.1+8.1、Maven 3.9.16；精确回归 141/141，通过完整受影响 reactor 的 product API 12、protocol 80、instrument API 13、service 499、benchmark 16，共 620 项，0 failure/0 error。覆盖资金矩阵、撮合、最大 batch、commit/hash 回滚、Core Fact、snapshot/recovery；无 Docker、wallet 或外部服务参与。
- in-flight：所有性能采集严格固定 `256`；不运行、不补跑、不横向比较 `64/512/1024` 或其他档位。
- 通过标准：无 profiler 主轮 `terminal business ops/s >= 10,800`，99.9% 置信区间下界 `>=10,000`，三个 fork 均值均 `>=10,000`；accepted business=terminal business、accepted Core=terminal Core、两个 unfinished=0、期末 backlog=0、最大 backlog `<=256`，producer starvation、拒绝、错误和超时均为 0；teardown 资金守恒、余额/冻结/持仓、订单终态与 snapshot recovery 全部通过。
- 延迟门禁：PLACE_ORDER 分别报告 entry→accepted、accepted→terminal、entry→terminal 的 p50/p90/p95/p99/p99.9/max、样本数、`1 ns–30 s` histogram 和 30 秒 timeout；饱和场景 accepted→terminal p99 `<=35 ms`、p99.9 `<=50 ms`、max `<=150 ms`，entry→terminal p99 `<=1.6 s`、p99.9 `<=1.65 s`、max `<=2 s`。
- GC/分配门禁：独立 `-prof gc` 报告 allocation rate、bytes/invocation、bytes/terminal business op、GC count/time；目标 `<=87,040 B/business op`，ZGC allocation stall/OOM、allocation requiring GC、promotion/evacuation failure为 0，JFR pause max `<=1 ms`。
- JFR/NMT 门禁：保存原始 `.jfr`、配置、GC/safepoint log、NMT summary baseline/diff；要求 `DataLoss=0`、采样期间 swap=0、容器 throttling=0、owner 同步业务 file/socket/database I/O=0。按 owner/driver、matcher/Disruptor、risk、projection、Core Fact、snapshot、外围线程报告 CPU、execution samples、分配 class/site/thread、heap/GC、native/Direct/Mapped、线程/锁/park、safepoint/VM operation、JIT/deopt、I/O/异常。
- 固定业务场景：只测 `LINEAR_PERPETUAL` 的 `LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`；10,000 活跃用户、512 挂牌且活跃 symbol、4 Account Lane、1 matching engine、1 risk engine、1 JMH worker，maker/taker 连续配对，16,384 business operations/invocation，open-loop constant-arrival 100,000 offered operations/s，coordinated omission corrected，每用户最多 5 持仓/10 未成交单，ACK interval 1,024，matcher wait strategy=`YIELDING`，做市状态持续提供对手盘。
- 主轮参数：5×5 秒 warmup、5×5 秒 measurement、3 forks、1 thread，无 profiler、无 NMT；归因轮为 5×5 秒 warmup、3×5 秒 measurement、1 fork、1 thread，同时启用 `-prof gc`、JFR、NMT summary 和 GC/safepoint log。带 profiler 数值只归因，不替代主吞吐。
- 长稳/泄漏：若主轮正确性通过，使用相同 `256 in-flight`、YIELDING、单 matcher 场景执行 10 分钟稳定负载，30 秒预热后每 10 秒采样；至少比较 3 个 post-GC 点的 live set、old/class、native committed、线程、FD、Direct/Mapped/pool 余额。任何无法解释的单调增长均失败；短 JFR 不用于声明无泄漏。
- 固定环境：Intel Core i9-9880H 8C/16T、16 GiB，macOS 26.7 / Darwin 25.6.0 x86_64，非容器、未绑核；采集前 swap=0。已有 Kafka 1 GiB JVM、Redis、PostgreSQL 和桌面进程继续运行，不擅自停止；Docker daemon/容器不启动。出现 swap、明显 thermal/调度漂移、额外 benchmark JVM 或 JFR DataLoss 时，本轮只能标记诊断/无效。
- JVM：`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`，开放 `jdk.internal.misc/ref`；`account-lanes=4`、`matching-engines=1`、matcher `YIELDING`、settlement `BLOCKING`、completion spins 16,384、projection `PARKING`/batch 64/4 MiB、journal 65,536/1 GiB、export pending 256 MiB。
- JFR：配置 `surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc`；采集前复制到 artifact 并记录 SHA-256。JFR 从 fork JVM 启动开始，明确包含 profiler 开销；分析只把最后 3 个 measurement invocation 作为业务窗口，同时保留完整启动/warmup/JIT 证据。
- 执行命令口径：先 `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am -DskipTests package`；随后对同一 JAR 执行 `saturatedMatchingWorkload`，显式传入 `-p maxInFlight=256 -p matcherWaitStrategy=YIELDING` 和上述用户/symbol/Lane/密度/到达率参数；主轮输出 JSON，归因轮额外使用 `-prof gc` 与 `-XX:StartFlightRecording`/NMT/GC log。所有完整命令、stdout/stderr、JVM/Maven版本和进程快照写入 artifact。
- artifact 根目录：`target/qualification/20260901T015920Z-owner-five-256/`；结束后生成文件大小和 SHA-256 清单，并将成功、失败、无效轮次、全部指标、问题和未测范围只追加回本记录，不覆盖历史内容。

### 2026-09-01 10:11:15 +08:00 — `PV-20260901-256-02` — `吞吐失败/终止（YIELDING 调度回退）`

- 实际采集：主轮 `10:01–10:06 +08:00`，归因 JFR `10:07:19–10:08:31 +08:00`、72 秒；被测生产实现仍为 `ae27913a58cbb0935746da93d65915497d6eceb5`，采集前锁定记录 commit `50ccaeb9`，JAR SHA-256 `82618bde96bd795f862c04c16f45e00c0fb7d005137d65104f3eaa1c5684bc8c`。全部运行只有 `maxInFlight=256`、`matcherWaitStrategy=YIELDING`、1 matcher。
- 主吞吐：`6687.975 ± 1891.727 terminal business ops/s`，99.9% CI `[4796.248,8579.702]`，未达到 `10,800` 门禁；terminal Core `6694.506 ± 1893.575 messages/s`。三个 fork 均值 `7071.418 / 6171.025 / 6821.482`，15 个 measurement 范围 `3701.851–9439.308 ops/s`，三个 fork 均未达到 10k，且 iteration 漂移巨大。
- 正确性/并发/backlog：主轮和归因轮 accepted business=terminal business、accepted Core=terminal Core、两个 unfinished=0、producer starvation=0；每个采样 invocation 终态 16,384 business operations/16,400 Core messages，max backlog 256、average 232、满窗口 81.25%、completion mailbox 高水位 256，teardown 未出现资金、余额/冻结/持仓、订单终态或 snapshot recovery 异常。拒绝/错误/超时和 fills/trades 仍无独立计数输出。
- GC/分配归因：归因吞吐 `8498.235 terminal business ops/s`；`gc.alloc.rate=671.923 MB/s`，`1,389,666,614 B/invocation`，折算 `84,818.519 B/business op`，通过 `87,040 B/op` 门禁。JFR 12 次 ZGC、50 个 pause，总 `0.680 ms`，p50 `0.0104 ms`、p90 `0.0290 ms`、p95 `0.0323 ms`、p99/max `0.0574 ms`；allocation stall/OOM、allocation requiring GC、promotion/evacuation failure均为 0。
- 延迟：最后 9 个 measurement invocation 共 147,456 PLACE_ORDER 样本；entry→accepted p50/p90/p95/p99/p99.9/max 为 `754.199/1413.131/1496.763/1555.560/1567.863/2597.456 ms`；accepted→terminal 为 `21.116/23.897/28.942/33.878/36.451/123.997 ms`；entry→terminal 为 `775.137/1434.493/1518.676/1577.360/1587.640/2617.632 ms`。accepted→terminal 分位与 max 通过，entry→terminal p99/p99.9 通过但 max 超过 2 秒门禁。
- CPU/热点归因：2,792 个 execution samples 的业务 top 为 `TreeMap.getEntry 6.59%`、`ConcurrentHashMap.get 3.98%`、business hash owner-domain mix `2.08%`、primitive map lookup 与 `CoreStateHash.mix`；8 个 exchange-core/Disruptor 通用线程各约 `3.68–4.08% user CPU + 1.91–2.23% system CPU`。这是从 JFR 线程 CPU 与 YIELDING 配置作出的归因：频繁 yield/重新调度提高了系统态开销，未换来稳定吞吐；不能把该方向保留为默认性能优化。
- 分配热点：owner/JMH worker `72.79%`，两个 Core Fact materializer 合计 `22.45%`，projection约 `2.76%`；class top 为 `long[] 9.19%`、`Object[] 7.19%`、`byte[] 6.39%`、Long/HashMap/stream/list iterator。site top 仍为 stream pipeline、primitive map扩容、HashMap resize、`mergeTreasuryDeltas`、patch builder、UserHash、identity capture 与 `CoreExportState.Draft.materialize`；前四项优化降低了绝对分配，但这些剩余热点仍未消失。
- heap/native/系统：heap committed 8 GiB，JFR GC 前最高 6.8 GiB、最后 post-GC 692 MiB；NMT末值 committed `8,809,657,779 B`，主要为 heap 8 GiB、GC 84.9 MiB、code 41.6 MiB、metaspace 36.7 MiB、tracing 19.9 MiB。线程峰值 21；DirectBuffer 71 个采样事件，没有长期余额证据。采集前后 swap 均 0，DataLoss=0、container throttling=0；但 Terminal/WindowServer/AOne/WeChat/Codex 造成明显同机 CPU 干扰。
- safepoint/JIT/I/O：GC pause max `0.0574 ms`，正常 safepoint最长约 `1.01 ms`；VM operation最长 `0.600 ms`。10,471 次 compilation 与 519 次 deoptimization覆盖启动/warmup/measurement。全记录 FileRead 3,302、FileWrite 200、SocketWrite 34、SocketRead 3，主要为 JAR/安全配置/native library/JMH-JFR 管理；本失败轮未进一步切出严格 measurement owner I/O 结论。
- artifact：`target/qualification/20260901T015920Z-owner-five-256/`；`main-256.json` 26,424 B、SHA-256 `932cf27c29ed1cc4135090f8a308897b33cf7884696b99f14333fd9f893fe8b6`；`attribution-256.json` 22,640 B、SHA-256 `d012189f391e5339a881672874ec1c29b4050a65418e99079440e11db4cfafbc`；原始 JFR 81,002,541 B、SHA-256 `9eb2791165897b36d88d5b5ac0da26049f8907313cf3d78615753aae531ed73b`；SHA 清单 SHA-256 `0a02c44e58cf97838de635fc3a74da96ef81ed4dad7bab4a9bb95c4e4fecca84`，size 清单 SHA-256 `8c1297d4372c1993c61bc42c3b08b5e477ee59e73508d73329ea15de4cbe4134`。
- 问题、未测与结论：绝对吞吐、置信区间和 fork 门禁失败，YIELDING 被判定为本机不合适的默认策略；因此终止该代码方向，不执行对即将废弃代码无意义的 10 分钟长稳。未测其余五产品线、长稳泄漏、fills/trades、完整逐账户资金流水、API/Aeron Cluster/Kafka/exporter/PostgreSQL/WebSocket。该轮只能证明正确性与分配/GC门禁通过，不能验收五项优化；下一轮必须先恢复吞吐优先的 matcher 策略、重新提交代码，并新建锁定记录，不能与本轮拼接。

### 2026-09-01 10:13:29 +08:00 — `PV-20260901-256-03` — `采集前锁定（BUSY_SPIN 修正轮）`

- 被测 git commit：`8e871653d3f5dedc9339cad62dd8b7dfd31a08d3`，分支 `codex/aeron-unified-core`，已推送；生产 owner/Fact/projection/hash 优化来自 `ae27913a`，本提交只恢复 `BUSY_SPIN` 默认并记录上一失败轮。对照 commit 为修改前直接父提交 `d33393678c1d75f02863eca47bfa773fca5ff0d0`；不检索或重跑旧历史，不把 02 的 YIELDING 失败数值当作本轮对照样本。
- 修改点与验证问题：保留单 patch Core Fact 快路径、Fact 用户一次分组扫描、projection 一次索引/应用、projection/business/funds typed compact journal 四项生产优化；matcher 固定 1 并恢复吞吐优先的 `BUSY_SPIN`，回答移除 YIELDING 调度回退后，最新代码能否达到绝对吞吐、分配、正确性和尾延迟门禁。
- 功能门禁：HotSpot JDK 25 完整受影响 reactor 620/620 已通过；恢复 BUSY_SPIN 后额外运行 `DeterministicExchangeCoreAdapterTest` 13/13 和 `LinearPerpetualBenchmarkSupportTest` 14/14，0 failure/0 error。已有 untracked `openai` 和三个 `.factorypath` 不进入构建或采样 classpath。
- in-flight 与场景：严格只用 `256`；仅 `LINEAR_PERPETUAL` `LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`，10,000 活跃用户、512 挂牌/活跃 symbol、4 Account Lane、1 matcher、1 risk engine、1 JMH worker、maker/taker 连续配对、16,384 business operations/invocation、open-loop 100,000 offered operations/s、coordinated omission corrected、每用户最多 5 持仓/10 未成交单、ACK interval 1,024、`matcherWaitStrategy=BUSY_SPIN`，做市状态持续运行。
- 通过标准：无 profiler `terminal business ops/s >=10,800`，99.9% CI 下界 `>=10,000`，三个 fork 均值均 `>=10,000`；accepted business=terminal business、accepted Core=terminal Core、unfinished=0、期末 backlog=0、最大 backlog<=256，starvation/拒绝/错误/超时=0，teardown 资金、余额/冻结/持仓、订单终态与 snapshot recovery 全部通过。
- 延迟门禁：三段 p50/p90/p95/p99/p99.9/max、样本数、`1 ns–30 s` histogram、30 秒 timeout；accepted→terminal p99<=35 ms、p99.9<=50 ms、max<=150 ms，entry→terminal p99<=1.6 s、p99.9<=1.65 s、max<=2 s。
- GC/JFR门禁：分配 `<=87,040 B/business op`，ZGC stall/OOM/allocation requiring GC/promotion/evacuation failure=0，pause max<=1 ms；JFR DataLoss=0、swap=0、container throttling=0、owner同步业务I/O=0，并按 owner、matcher、risk、projection、Core Fact、snapshot、外围线程报告 CPU/分配/heap/native/Direct/线程锁park/safepoint/JIT/I/O异常。
- 主轮：5×5 秒 warmup、5×5 秒 measurement、3 forks、1 thread、无 profiler/NMT；归因轮：5×5 秒 warmup、3×5 秒 measurement、1 fork、1 thread、`-prof gc`、同一 `owner-commit-profile.jfc`（SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`）、NMT summary、GC/safepoint log。参数开始后不修改。
- 长稳：只有主吞吐、正确性和环境有效性门禁通过才对该最终代码执行 30 秒预热+10 分钟稳定负载；每10秒采样并比较至少3个post-GC点的live set、native committed、线程、FD、Direct/Mapped/pool。主门禁失败则记录未执行，不能声明无泄漏。
- 环境/JVM：Intel i9-9880H 8C/16T、16 GiB、macOS 26.7/Darwin 25.6.0 x86_64、非容器/未绑核，采集前 swap=0；已有 Kafka/Redis/PostgreSQL/桌面进程继续运行且不擅自停止，Docker不启动。Oracle GraalVM HotSpot 25.0.1+8.1；8 GiB ZGC、AlwaysPreTouch、DisableExplicitGC，4 Lane/1 matcher、BUSY_SPIN、completion spins 16,384、projection PARKING/batch 64/4 MiB、journal 65,536/1 GiB、export 256 MiB。
- artifact 根目录：`target/qualification/20260901T021329Z-owner-four-256/`；完整命令、版本、JAR/JFR配置哈希、JSON/log/JFR/NMT/系统证据、大小与 SHA-256 清单全部保留，结果无论成败只追加回本记录。

### 2026-09-01 10:23:14 +08:00 — `PV-20260901-256-03` — `验收失败/部分验证`

- 实际采集：无 profiler 主轮 `10:14–10:19 +08:00`；GC/JFR 归因 recording `10:20:11–10:21:22 +08:00`、71 秒。被测生产实现为 commit `8e871653d3f5dedc9339cad62dd8b7dfd31a08d3`，采集前锁定记录 commit `9e8c7858`；JAR SHA-256 `661721e1c5b71b7a2210ade8eb5c1fa0050e49a3f94aa30f4b311639b1e078c1`。只运行了 `256 in-flight`、BUSY_SPIN、1 matcher。
- 主吞吐：`7904.368 ± 1688.403 terminal business ops/s`，99.9% CI `[6215.965,9592.770]`，未达到 `10,800` 绝对门禁；terminal Core `7912.087 ± 1690.052 messages/s`。三个 fork 均值 `6289.119 / 8320.197 / 9103.787`，15 个 measurement 范围 `4695.195–9590.943 ops/s`；当前最新代码本轮最高单次为 `9590.943 terminal business ops/s`，没有任何 fork 达到 10k。
- 正确性与并发：主轮/归因轮均 accepted business=terminal business、accepted Core=terminal Core、两个 unfinished=0、producer starvation=0；每个 JFR invocation 终态 16,384 business operations/16,400 Core messages，最大 backlog 256、平均 232、满窗口 81.25%、completion mailbox 高水位 256；teardown 未出现资金守恒、余额/冻结/持仓、订单终态或 snapshot recovery 错误。benchmark 未独立输出 fills/trades、拒绝率、错误率、超时率或 batch 指标。
- GC/分配：归因吞吐 `8461.278 terminal business ops/s`；`gc.alloc.rate=683.032 MB/s`，`1,396,921,231 B/invocation`，折算 `85,261.306 B/business op`，通过 `87,040 B/op` 门禁。12 次 ZGC、50 个 pause，总 `0.811 ms`；p50 `0.0104 ms`、p90 `0.0337 ms`、p95 `0.0519 ms`、p99/max `0.0578 ms`；ZGC stall/OOM、allocation requiring GC、promotion/evacuation failure均为0。
- 延迟：最后9个 measurement invocation共147,456 PLACE_ORDER样本；entry→accepted p50/p90/p95/p99/p99.9/max=`803.800/1460.213/1547.305/1613.049/1625.625/2599.033 ms`；accepted→terminal=`22.020/24.607/25.206/27.476/29.698/140.229 ms`；entry→terminal=`825.083/1482.778/1570.586/1635.548/1647.887/2621.652 ms`。accepted→terminal通过；entry→terminal p99和max未通过，排队仍由100k offered rate远高于持续终态吞吐造成。
- CPU/热点：JVM user/system平均 `35.47%/1.22%`，机器总CPU平均/最大 `41.69%/50.88%`。14,545 execution samples中 `ProcessingSequenceBarrier.checkAlert 37.55%`、`WaitSpinningHelper.tryWaitFor 16.77%`、cursor `12.98%`、minimum sequence `10.64%`，约77.94%是exchange-core/Disruptor busy-spin/cursor等待；8个通用线程各约6.17–6.21% user CPU。业务top为TreeMap、CoreStateHash、rolling business/funds hash、primitive/ConcurrentHashMap与stagePatch，说明线程等待仍支配全JVM CPU，但不能作为owner业务吞吐。
- 分配热点：owner/JMH worker `72.87%`，Core Fact materializer合计 `22.42%`，projection约 `2.73%`。class top为 `long[] 9.36%`、`byte[] 7.23%`、`Object[] 6.56%`、Long/iterator/stream/HashMap；site top仍包括primitive map扩容、HashMap put/resize、stream pipeline、patch tombstones/builder、mergeTreasuryDeltas、UserHash、CoreExport编码和identity capture。四项优化后的分配门禁通过，但剩余分配仍约83.3 KiB/op。
- heap/native/线程：heap committed固定8 GiB，最高GC前6.6 GiB，最后post-GC704 MiB；NMT末值 committed `8,789,559,370 B`，主要为heap8 GiB、GC68.8 MiB、code39.9 MiB、metaspace36.7 MiB、tracing19.7 MiB。线程峰值/末值19/17；DirectBuffer有70个统计事件，但短记录不能证明无泄漏或pool余额闭合。
- safepoint/JIT/I/O/环境：GC pause max `0.0578 ms`；采样中正常safepoint可见 `2.08 ms`，低于业务尾延迟；10,594次compilation，最长849 ms，measurement附近仍有snapshot/projector/exchange-core长编译，518次deoptimization。全记录FileRead 3,303、FileWrite 200、SocketWrite 34、SocketRead 3，主要为JAR/native/JFR管理；未切出严格measurement owner I/O=0证据。DataLoss=0、container throttling=0、采集前后swap=0；但Terminal、WindowServer、AOne、AXVisualSupport、WeChat及多个Codex进程持续占CPU，连续8 GiB压测存在明显热/调度漂移。
- artifact：`target/qualification/20260901T021329Z-owner-four-256/`；`main-256.json` 26,418 B、SHA-256 `b997115773459b7eda6955c4d1ce2f6878f346b82df58bb979ca169e9c87fe9c`；`attribution-256.json` 22,581 B、SHA-256 `20cd7bdad7f00fe0bb48905b752eda4cd006af4b2f6097b86b14da3b7e9b0e94`；原始JFR 78,543,122 B、SHA-256 `b330f2069ab7802fc5a5aeb9033faf57e44e0398327d4ec83a31ee38d54a70bc`；SHA清单 SHA-256 `8abebc479e62bdde9def85b8a195dbbd1f864e13a31d6f3fd307c2a3e715eaa3`，size清单 SHA-256 `6f86f55120f67bdc4adaf5df3ea6ad6b5ff71999a946284a3f8aa213e51af5c3`。
- 未测与结论：因主吞吐、CI/fork与entry→terminal延迟门禁失败，按预锁定规则未运行10分钟长稳，不能声明heap/native/FD/线程/buffer无泄漏。未测其余五产品线、独立强平/资金费/ADL/保险基金/结算/触发单、完整逐账户/做市资金流水、API/Aeron Cluster/Kafka/exporter/PostgreSQL/WebSocket。功能正确性、单位分配、GC pause、DataLoss/swap门禁通过；主吞吐和排队尾延迟失败，最终只能标记“验收失败/部分验证”，不能宣称这批优化已获得稳定吞吐提升。

### 2026-09-01 11:04:04 +08:00 — `PV-20260901-256-04` — `采集前锁定（owner/matcher/projection 流水线四项优化）`

- 被测 git commit：`637fa6937faf59c2860cbbb843bbd5612125d75c`，分支 `codex/aeron-unified-core`，已推送；工作区 tracked 文件在采集前为 clean。已知非忽略 untracked `openai` 和三个 `.factorypath` 不进入构建或采样 classpath。
- 对照 git commit：上一轮相同业务场景的生产实现 `8e871653d3f5dedc9339cad62dd8b7dfd31a08d3`；`8baf6bcb` 之前的中间提交仅追加性能记录，不改变生产代码。本轮不检索、不重跑其他历史，也不运行其他 in-flight；对照数值只使用紧邻记录 `PV-20260901-256-03` 的同机、同 JDK、同 `256`、BUSY_SPIN、单 matcher 结果。
- 修改点：① owner 不再等待 matcher，按 ready-prefix 最多 256 条机会式提交，1 ms deterministic timer 仅作推进兜底，并锁定首个日志时间/位置作为 commit fence；② 连续 matcher completion 以一个 owner batch 提交，journal 与 Core Fact materializer 每批只显式唤醒一次；③ owner→projection 与 owner→Fact 的 SPSC 槽改为 `Object[] + VarHandle release/acquire`，保留发布/消费序列的有界背压；④ projection 对一个 journal batch 只创建一次回滚边界、批末 freeze，一项失败回滚整批并保持可重放。
- 功能门禁：HotSpot JDK 25 下，改动直达的 5 个测试类共 136/136 通过；受影响 Reactor 使用每测试类独立 JVM 完成 578/578，0 failure、0 error、0 skipped；快照/FIFO 六产品线参数化场景另以干净 JVM验证 6/6。默认复用单测试 JVM会因大量 busy-spin 测试线程累积到 400+ 而严重争用，故完整功能轮使用 `-DforkCount=1 -DreuseForks=false`；这不改变性能采集 JVM。
- in-flight：严格固定 `256`；不得运行、补跑或横向比较 `64/512/1024` 等其他档位。
- 验证范围：仅 `LINEAR_PERPETUAL` Product Core 的 maker/taker 下单撮合链路；不启动 Docker、wallet、API、Aeron Cluster、Kafka、PostgreSQL、外部 exporter/history projection 或 WebSocket。JMH 进程内部真实执行 owner、exchange-core matcher、risk、runtime commit projection、Core Fact materializer、资金/订单终态和 snapshot/recovery 校验。
- 固定场景：`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`；10,000 活跃用户、512 挂牌且活跃 symbol、4 Account Lane、1 matcher、1 risk engine、1 JMH worker、maker/taker 连续配对、每用户最多 5 持仓/10 未成交单、16,384 terminal business operations/invocation、ACK interval 1,024。负载为 open-loop constant-arrival `100,000 business operations/s`，修正 coordinated omission；matcher/做市进程在整个 invocation 持续运行，wait strategy 固定 `BUSY_SPIN`。
- 资金与状态初态/终态：setup 为 10k 用户和做市对手方创建充足衍生品保证金、512 个 instrument/mark price 与配对订单初态；每轮 teardown 必须满足 accepted business=terminal business、accepted Core=terminal Core、两个 unfinished=0、期末 backlog=0、无活动订单无界增长，并通过余额/冻结/持仓、资金守恒、订单终态、业务/资金 hash 与 snapshot restore 一致性。benchmark 未输出逐账户流水或 fills/trades 时必须记为证据缺口，禁止推算。
- 通过阈值：无 profiler 主结果 `terminal business ops/s >=10,800`，99.9% CI 下界 `>=10,000`，3 个 fork 均值各 `>=10,000`；同时报告 `terminal Core messages/s`、逐 fork/iteration、score error/CI。最大 matching backlog `<=256`、期末 0，报告平均 backlog、满窗口比例和 completion mailbox 高水位；starvation、拒绝、错误和超时必须为 0或由 benchmark 明确输出为 0。
- 延迟阈值：分别报告 entry→accepted、accepted→terminal、entry→terminal 的 p50/p90/p95/p99/p99.9/max、样本数、`1 ns–30 s` histogram 与 30 秒 timeout；accepted→terminal p99 `<=35 ms`、p99.9 `<=50 ms`、max `<=150 ms`，entry→terminal p99 `<=1.6 s`、p99.9 `<=1.65 s`、max `<=2 s`。
- GC/分配阈值：独立 `-prof gc` 报告 allocation rate、B/JMH invocation、折算 B/terminal business op、GC count/time；要求 `<=87,040 B/business op`，ZGC stall/OOM/allocation requiring GC/promotion/evacuation failure=0，JFR GC pause max `<=1 ms`。
- JFR/NMT 标准：要求原始 JFR、summary 与按 CPU/线程/分配/GC/native/锁/park/safepoint/JIT/I/O/异常的 view；按 owner、matcher、risk、projection、Core Fact、snapshot 与外围线程归组。要求 `DataLoss=0`、swap=0、container throttling=0、owner measurement 窗口同步业务 file/socket/database I/O=0；记录 heap committed/used/live、NMT reserved/committed、Direct/Mapped/native pool、线程峰值与 busy-spin CPU。短 JFR 只作热点归因，不用于声明无泄漏。
- 主吞吐参数：5×5 秒 warmup、5×5 秒 measurement、3 forks、1 thread、10 分钟 iteration timeout；无 profiler、无 NMT、无 JFR，JSON 与完整日志落盘。主轮结束后冷却 30 秒再启动归因轮。
- 归因参数：5×5 秒 warmup、3×5 秒 measurement、1 fork、1 thread、`-prof gc`，启用 NMT summary、GC/safepoint log及 `owner-commit-profile.jfc`；JFR 从 fork JVM 启动开始并保留 warmup/JIT证据，绝对吞吐不与无 profiler 主结果混比。
- 长稳/泄漏：只有主吞吐、正确性和环境有效性门禁通过，才执行同一 `256 in-flight` 场景的 30 秒预热+10 分钟稳定负载，每 10 秒采样并比较至少 3 个 post-GC 点的 live set、old/class、native committed、线程、FD、Direct/Mapped/pool 余额。主门禁失败则不运行并明确记录，不能声明无泄漏。
- 固定环境：Intel Core i9-9880H 8C/16T、16 GiB、macOS 26.7/Darwin 25.6.0 x86_64、非容器、未绑核；采集前 swap=`0`。已有桌面与 Codex 进程不擅自终止，Docker daemon/容器不启动；出现 swap、额外 benchmark JVM、JFR DataLoss、明显 thermal/调度漂移或采集期间参数/代码变化，该轮只能标记诊断/无效。
- JDK/JVM：Oracle GraalVM `25.0.1+8.1`、`Java HotSpot(TM) 64-Bit Server VM`，Maven 3.9.16；`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`，开放 `jdk.internal.misc/ref`；4 Lane、1 matcher、BUSY_SPIN、settlement BLOCKING、completion spins 16,384、projection PARKING/batch 64/4 MiB、journal 65,536/1 GiB、export pending 256 MiB。
- 执行命令口径：先 `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am -DskipTests package`；随后对同一 `product-core-benchmarks.jar` 执行 `saturatedMatchingWorkload`，显式传入上述所有参数和 `-p maxInFlight=256`，主轮输出 JSON，归因轮额外使用 `-prof gc`、`-XX:StartFlightRecording`、NMT 与 GC log。完整命令、版本、进程/系统快照及文件哈希保存在 artifact。
- artifact 根目录：`target/qualification/20260901T030404Z-pipeline-four-256/`；JFR 配置在采集前复制并记录 SHA-256，结束后生成 artifact size/SHA-256 清单，所有成功、失败和无效结果只按时间追加回本记录。

### 2026-09-01 11:21:40 +08:00 — `PV-20260901-256-04` — `验收失败/部分验证`

- 实际执行：功能 Reactor 于 `11:00–11:03 +08:00` 完成；benchmark package 于 `11:05:51` 完成；无 profiler 主轮约 `11:06–11:11`；有效归因 JFR recording 为 `11:15:16–11:16:42 +08:00`、86 秒。被测生产实现 commit `637fa6937faf59c2860cbbb843bbd5612125d75c`，采集前锁定记录 commit `040815a8792e1aaf69df25bc3d758946848d00a9`；JAR SHA-256 `4bb58dc5fbf30ec8313a2d5e908f864d90a847ea2369ca4264ba54c00f5559d5`。全部 JMH 只使用 `maxInFlight=256`、1 matcher、BUSY_SPIN。
- 功能正确性：直达 5 个测试类 136/136；受影响 Reactor 以每测试类独立 JVM 完成 578/578、0 failure/error/skipped；快照/FIFO 六产品线参数化场景另行 6/6。主轮和有效归因轮 accepted business=terminal business、accepted Core=terminal Core、两个 unfinished=0、producer starvation=0；每个归因 invocation 终态为 16,384 business operations/16,400 Core messages，teardown 未报资金守恒、余额/冻结/持仓、订单终态、业务/资金 hash 或 snapshot restore 错误。benchmark 仍未独立输出 fills/trades、拒绝率、错误率、超时率、逐账户/做市资金流水，属于验收证据缺口。

#### 吞吐、并发、backlog 与延迟

- 无 profiler 主吞吐：`7236.188 ±1356.358 terminal business ops/s`，99.9% CI `[5879.830,8592.546]`；`terminal Core messages/s=7243.255 ±1357.682`。3 个 fork 均值 `8211.987 / 6502.374 / 6994.204`，15 个 measurement 范围 `4989.271–9082.339 ops/s`；绝对值、CI 下界和每 fork 三项门禁全部失败。
- 与紧邻同场景记录 `PV-20260901-256-03` 的 `7904.368 ops/s` 相比，均值低 `8.453%`；最高 iteration 从 `9590.943` 降为 `9082.339`，低 `5.303%`。两轮区间重叠且本轮有更强同机系统干扰，因此不能把差值认定为稳定代码回退，但可以确定四项修改没有产生可验收的吞吐提升。
- 并发配置为 10,000 活跃用户、512 活跃 symbol、4 Lane、1 matcher、1 risk engine、1连接/JMH worker、固定 256 in-flight；每个 measurement invocation 最大 backlog `256`、平均 `232`、满窗口 `81.25%`、completion mailbox 高水位 `256/4096`、期末 backlog 0。持续满窗口未缓解，说明吞吐仍受 terminal 消费能力而非 producer starvation 限制。
- 有效 JFR 最后 9 个 measurement invocation 共 147,456 PLACE_ORDER 样本。entry→accepted p50/p90/p95/p99/p99.9/max=`845.621/1569.100/1653.891/1718.634/1735.066/1882.312 ms`；accepted→terminal=`22.731/27.347/28.327/29.893/32.289/74.088 ms`；entry→terminal=`870.134/1588.905/1679.286/1744.953/1754.203/1903.942 ms`。accepted→terminal 全部门禁通过；entry→terminal p99/p99.9 失败，max 通过。100k offered rate远高于 7–8k持续终态能力，入口排队仍主导业务尾延迟。

#### GC、内存、CPU、等待与 I/O

- 有效 `-prof gc` 归因吞吐 `8336.971 terminal business ops/s`；allocation rate `669.567 MB/s`，`1,395,980,552 B/JMH invocation`，折算 `85,203.891 B/business op`，通过 `87,040 B/op` 门禁，但相对上一轮 `85,261.306 B/op` 只少约 `0.067%`，实质未降低。JFR 12 次 ZGC、50 个 pause，总 pause `0.951 ms`，p50/p90/p95/p99/max=`0.0137/0.0300/0.0592/0.0641/0.0641 ms`；ZGC stall/OOM、allocation requiring GC、promotion/evacuation failure均为0。
- heap committed固定 8 GiB；JFR GC 前最高约 6.4 GiB，最后 post-GC约 718 MiB。NMT末值 committed `8,796,210,303 B`；主要类别为 heap 8 GiB、GC 73.0 MiB、code 41.9 MiB、metaspace 36.7 MiB、tracing 20.1 MiB。DirectBuffer 从 0到1 byte后回到0，末值 count/capacity/memory均为0；无 mapped/native pool 长稳余额证据。
- 线程峰值/末值 `19/17`。JVM user/system平均 `35.46%/1.16%`，机器总CPU平均/最大 `50.27%/69.44%`；线程 user CPU：8 个 exchange-core/Disruptor通用线程各约 `6.16–6.22%`，owner/JMH worker `5.03%`，snapshot encoder `4.40%`，snapshot audit `1.58%`，projection约 `1.96%`，Core Fact约 `0.63%`。risk没有独立可归组的活跃样本。
- 16,168 个 execution samples 中 `ProcessingSequenceBarrier.checkAlert 36.68%`、`WaitSpinningHelper.tryWaitFor 16.54%`、cursor `12.17%`、minimum sequence `10.27%`、Sequence.get `1.23%`、BusySpin wait `0.70%`，合计约 `77.59%` 仍是 exchange-core/Disruptor busy-spin与游标等待。业务热点仍为 TreeMap、primitive/ConcurrentHashMap、CoreStateHash与 rolling hash。
- 分配按线程：owner/JMH worker `72.84%`，两个 Core Fact materializer合计 `22.42%`，两个 projection线程合计 `2.78%`，snapshot约 `0.44%`。top class为 `long[] 9.62%`、`Object[] 8.05%`、`byte[] 6.45%`、Long/stream/HashMap/List；top site仍为 primitive map扩容、stream pipeline、HashMap resize/put、`mergeTreasuryDeltas`、patch builder、UserHash、Fact identity、matcher settlement plan。SPSC槽和批量唤醒不在主要分配热点内。
- JFR `ThreadPark` 从上一同配置的 `1,594,251` 降为 `1,126,178`（约 `-29.36%`），证明批量 signal减少了唤醒/park事件；但 ready-prefix在当前顺序提交下没有批量大小指标，持续满窗口与未变的 owner/Fact分配表明单次 owner推进仍通常很短，省下的同步开销不足以覆盖每操作 hash/Map/patch/Fact成本。projection分配占比也未从上一轮约2.73%实质下降。
- 锁竞争主要发生在启动期 `AffinityThreadFactory`，最长376 ms；owner业务测量窗口没有同步 monitor热点。正常 safepoint最长 `0.989 ms`（录制结束的 indefinite 行不计业务停顿），最长 VM operation `0.610 ms`。JIT compilation `10,610` 次，最长 `1.02 s`，deoptimization `544`，主要长编译在measurement前完成；CompilationFailure=0。
- 全记录 FileRead/FileWrite/SocketRead/SocketWrite=`3300/200/3/34`，主要为JAR、配置、native library和JMH控制；按最后9个measurement invocation时间窗和 owner线程过滤，同步 I/O=`0 events / 0 bytes`。异常共约1.4k，top为反射/MethodHandle能力探测和native symbol探测；未发现业务异常热点。DataLoss=0、container throttling=0，采集前后 swap均0。

#### 问题、artifact、未测与结论

- 环境问题：主轮前系统 `spotlightknowledged + XProtectService` 一度合计约160% CPU；归因前后 `contactsd/duetexpertd/Spotlight/signpost_reporter/Wallpaper` 等继续占用多核，机器CPU最高69.44%。本轮有明显调度漂移，容量数字只能作为工作站诊断，不是生产容量认证。
- 采集问题：归因第一次启动因未引用 `-Xlog:gc*` 被 zsh 在Java启动前拒绝；第二次JMH完成，但父JVM与fork同时写同一JFR，recording不可读，完整保留为 `attribution-invalid-concurrent.*`。第三次仅把同一JFR/NMT/GC参数正确放入 `-jvmArgsAppend`，得到上述有效归因；场景和in-flight从未变化。
- artifact：`target/qualification/20260901T030404Z-pipeline-four-256/`。`main-256.json` 26,550 B、SHA-256 `c67d05d4f9a74730e7f4c87dee350cbbd1e7bf2e502cd57eeba17d0ee22654e0`；`attribution-256.json` 22,664 B、SHA-256 `663de63836340c650a971c405406d0dd38dcaebd8c49e3d82274c190c129a3ab`；有效原始 JFR 61,249,043 B、SHA-256 `f35b0b48ce845e84c5d137e73325ae08b6abe9283e858436127189a8948841d8`；无效并发写 JFR 60,613,860 B并保留。artifact SHA清单 SHA-256 `761d5672c31fa312fa286bafc6ddd7460b6be0cfd2329f077a584adee27503dd`，size清单 SHA-256 `28e73226ceb737347efb39ac34fef02981a8f202cf6e8ae8c6330bcb1b39831e`；完整命令、JFR summary/views、latency、NMT/GC、系统进程和I/O线程归因均在该目录。
- 长稳与泄漏：因绝对吞吐、CI/fork、entry尾延迟及环境稳定性门禁失败，按预锁定规则未运行10分钟长稳；不能声明 heap/native/FD/线程/buffer无泄漏。
- 未测范围：其余五条产品线，独立撤单/改单/触发单/风险扫描/强平/资金费/ADL/保险基金/结算，完整fills/trades与逐账户/做市资金流水，API/Aeron Cluster/Kafka/外部 exporter/PostgreSQL/WebSocket，以及生产同型隔离CPU环境。
- 结论：四项优化的功能正确性通过，且批量signal确实减少park事件；但每操作分配、owner/Core Fact成本和exchange-core busy-spin占比基本未变，主吞吐和入口尾延迟均未通过锁定门禁。在明显同机干扰下只能给出“验收失败/部分验证”，不得宣称性能提升或完整主链路验收完成。

### 2026-09-01 12:25:58 +08:00 — `PV-20260901-256-05` — `采集前锁定（owner/Core Fact compact commit）`

- 记录创建时间：`2026-09-01 12:25:58 +08:00`（`2026-09-01T04:25:58Z`）。
- 被测 git commit：`72216dc7198502b7b75d05acad0c294d1422931b`，分支 `codex/aeron-unified-core`，已推送；tracked 工作区在锁定前 clean。已知 untracked `openai` 和三个 `.factorypath` 不进入构建、classpath 或 artifact。
- 对照 git commit：`637fa6937faf59c2860cbbb843bbd5612125d75c`（本轮修改前最近的生产代码 commit；中间 commit 只追加性能记录）。按用户要求本轮不检索或重跑旧历史、不采集其他 in-flight，也不以旧记录替代本轮实测；对照 commit 只用于代码影响审计，性能结论使用下述绝对门禁。
- 修改点：① user/balance before-value 改为按 Account Lane 的 primitive journal，复用 `RuntimeCommitPatch.Builder` 与 lane/Treasury scratch；② funds posting 排序后线性归并，business/funds hash 回滚复用同一条 change 的 before/after，不再生成 reverse patch；③ matcher settlement 的用户、订单、remaining quantity 使用小型 primitive 容器；④ changed ID 保持 primitive backing；⑤ Core Fact user/order 直接写最终 event buffer，移除嵌套 `byte[]` 与冗余 payload/list 复制；⑥ JFR workload 增加 completion batch count/items/average/max。
- 功能门禁：Oracle GraalVM HotSpot JDK 25.0.1 下执行 `mvn -pl :surprising-aeron-benchmarks -am test`；product API 12、protocol 80、instrument API 13、service 500、benchmarks 16，共 `621/621` 通过，0 failure、0 error、0 skipped。覆盖资金守恒/幂等、business/funds hash apply/rollback、builder reset、Account Lane、批量订单、Core Fact codec、snapshot/recovery 及六产品线共享 snapshot 契约。
- in-flight：严格固定 `256`；本轮禁止运行、补跑、推算或横向比较 `64/512/1024` 等其他档位。
- 验证范围：仅 `LINEAR_PERPETUAL` Product Core 的 maker/taker 下单撮合链路；不启动 Docker、wallet、API、Aeron Cluster、Kafka、PostgreSQL、外部 exporter/history projection 或 WebSocket。JMH JVM 内真实运行 owner、单 exchange-core matcher、risk、runtime projection、Core Fact materializer、资金/订单终态和 snapshot/recovery 校验。
- 固定场景：`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`；10,000 活跃用户、512 挂牌且活跃 symbol、4 Account Lane、1 matcher、1 risk engine、1 JMH worker/连接、每用户最多 5 持仓和 10 未成交单、16,384 terminal business operations/invocation、export ACK interval 1,024。业务为跨 symbol 连续 maker/taker 配对 PLACE_ORDER；内部做市对手盘全程运行。未实际触发的撤单/改单/触发/强平/资金费/ADL/结算不得标记为已测。
- 负载模型：open-loop constant-arrival，offered rate `100,000 business operations/s`；计划到达时间进入 entry latency 直方图，修正 coordinated omission。直方图范围 `1 ns–30 s`，业务等待超时 30 秒。
- 初态与终态：setup 通过真实 Core 命令创建 10k 用户充足衍生品保证金、512 instrument/mark price 和配对盘口；每轮 teardown 必须满足 accepted business=terminal business、accepted Core=terminal Core、两个 unfinished=0、期末 backlog=0、producer starvation=0，并通过用户/做市余额、冻结、持仓、资金守恒、活动订单、business/funds hash 和 snapshot restore 检查。benchmark 未输出逐账户资金流水或 fills/trades 时记为证据缺口，禁止推算。
- 主吞吐通过标准：无 profiler 结果 `terminal business ops/s >=9,000`，99.9% CI 下界 `>=8,000`，3 个 fork 均值各 `>=8,000`；同时报告 terminal Core messages/s、逐 fork/iteration、score error/CI。最大 matching backlog `<=256`、期末 0、completion mailbox 高水位不超容量；completion batch average `>1.0` 且 max `>1` 只作为流水线诊断，不单独决定通过。
- 延迟通过标准：分别报告 entry→accepted、accepted→terminal、entry→terminal 的 p50/p90/p95/p99/p99.9/max和样本数；accepted→terminal p99 `<=35 ms`、p99.9 `<=50 ms`、max `<=150 ms`，entry→terminal p99 `<=1.6 s`、p99.9 `<=1.65 s`、max `<=2 s`。
- 稳定性标准：拒绝、错误、超时为 0；DataLoss=0、swap=0、container throttling=0、owner measurement 窗口同步 file/socket/database I/O=0；采集期间若出现额外 benchmark JVM、参数/代码变化或长期同机进程占用超过一个物理核，本轮容量结果标记无效/诊断。
- GC/分配标准：独立 `-prof gc` 报告 allocation rate、B/JMH invocation 和 B/terminal business op；要求 `<=76,800 B/business op`，ZGC stall/OOM/allocation requiring GC/promotion/evacuation failure=0，JFR GC pause max `<=1 ms`。报告 TLAB/非 TLAB、top class/thread/site、heap committed/used/live set和GC前后趋势。
- JFR/NMT 标准：保存原始 `.jfr`、summary 与 CPU/线程/分配/GC/native/锁/park/safepoint/JIT/I/O/异常 view，按 owner、matcher、risk、projection、Core Fact、snapshot和外围线程归组；报告 NMT reserved/committed、Direct/Mapped/native pool、线程峰值及 busy-spin CPU。短 JFR 只作归因，不能证明无泄漏。
- 长稳/泄漏：仅当主吞吐、正确性、分配和环境有效性门禁全部通过，才运行同一 `256 in-flight` 场景 30 秒预热+10 分钟稳定负载，每10秒采样，比较至少3个 post-GC 点的 live set、old/class、native committed、线程、FD、Direct/Mapped/pool；否则不运行并明确记录，不能声明无泄漏。
- 固定环境：Intel Core i9-9880H 8C/16T、16 GiB、macOS 26.7/Darwin 25.6.0 x86_64、非容器、未绑核；锁定时 swap=`0`。锁定时 `corespotlightd` 瞬时约120% CPU，采集前必须重新检查；不擅自终止桌面或系统进程，若持续干扰则按上条将结果标记无效。
- JDK/JVM：Oracle GraalVM `25.0.1+8.1`、`Java HotSpot(TM) 64-Bit Server VM`，Maven 3.9.16；`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`，开放 `jdk.internal.misc/ref`；Account Lane=4、matcher=1、BUSY_SPIN、settlement BLOCKING、completion spins=16,384、projection PARKING/batch 64/4 MiB、journal 65,536/1 GiB、export pending 256 MiB。
- 主轮参数：5×5秒 warmup、5×5秒 measurement、3 forks、1 thread、10分钟 iteration timeout；无 profiler/NMT/JFR，JSON与完整日志落盘。主轮后冷却30秒。
- 归因参数：5×5秒 warmup、3×5秒 measurement、1 fork、1 thread、`-prof gc`；仅 fork JVM 通过 `-jvmArgsAppend` 启用 NMT summary、GC/safepoint log与 `owner-commit-profile.jfc`。JFC源文件 SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`；带 profiler 分数只作归因，不替代主吞吐。
- 执行命令口径：`mvn -pl :surprising-aeron-benchmarks -am -DskipTests package`；随后以同一 `product-core-benchmarks.jar` 执行上述 benchmark，并显式传入 `-p maxInFlight=256` 及全部场景参数。主轮 `-wi 5 -w 5s -i 5 -r 5s -f 3 -t 1`；归因轮 `-wi 5 -w 5s -i 3 -r 5s -f 1 -t 1 -prof gc`，JFR/NMT/GC参数仅放在 `-jvmArgsAppend`。
- artifact 根目录：`target/qualification/20260901T042552Z-owner-commit-compact-256/`；保存版本、完整命令、源码/JAR/JFC SHA、系统/进程快照、JMH JSON/log、JFR、GC/NMT、latency/业务计数、JFR views及最终 size/SHA-256 清单。失败、中止或无效轮次也必须在本文件追加结果。

### 2026-09-01 12:55:15 +08:00 — `PV-20260901-256-05` — `长稳失败/验收失败`

- 实际执行：package 于 `12:27:45 +08:00` 完成；无 profiler 主轮约 `12:28–12:33`；归因 JFR 为 `12:34:20–12:35:32 +08:00`、72 秒；长稳于 `12:39` 启动，在 measurement 约 2 分钟时提前失败。被测生产实现为 `72216dc7198502b7b75d05acad0c294d1422931b`，采集记录 commit 为 `831de2dc`；全部 JMH 仅使用 `256 in-flight`、1 matcher、BUSY_SPIN，没有启动 Docker 或外部服务。
- 功能测试：采集前 Reactor `621/621`、0 failure/error/skipped；主轮和归因轮 accepted business=terminal business、accepted Core=terminal Core、两个 unfinished=0、producer starvation=0，teardown 未报资金、余额/冻结/持仓、订单终态、hash 或 snapshot restore 错误。
- 主吞吐：`9306.297 ± 413.745 terminal business ops/s`，99.9% CI `[8892.552, 9720.042]`；`terminal Core messages/s=9315.385 ± 414.149`。3 个 fork 均值为 `9007.226 / 9530.617 / 9381.049 ops/s`，主吞吐绝对门禁通过。最大/平均 matching backlog 为 `256/232`，满窗口 `81.25%`，completion mailbox 高水位 `256/4096`；新增 completion batch 为每 invocation `256 batches / 16384 items`，average/max batch size 均 `64`。
- 归因轮：`9242.075 terminal business ops/s`，只用于归因。`-prof gc` 为 `535.544 MB/s`、`1,005,520,860 B/JMH invocation`，折算 `61,372.367 B/business op`，通过 `76,800 B/op` 门禁；6 次 profiler GC、累计 concurrent GC time `1071 ms`。JFR 9 次 ZGC、41 个 pause，总 pause `1.14 ms`，p50/p90/p95/p99/max=`0.0110/0.0619/0.0682/0.277/0.277 ms`；ZGC allocation stall、OOM、DataLoss、container throttling 均为 0。
- 延迟：最后 9 个 measurement invocation 共 `147,456` 个 PLACE_ORDER 样本，直方图 `1 ns–30 s`、timeout 30 秒、open-loop coordinated-omission corrected。entry→accepted p50/p90/p95/p99/p99.9/max=`770.992/1412.277/1493.626/1555.665/1567.636/1677.897 ms`；accepted→terminal=`21.507/24.640/25.547/27.012/28.908/35.951 ms`；entry→terminal=`796.036/1434.822/1515.568/1576.616/1586.262/1697.173 ms`，预锁定尾延迟门禁通过。
- JFR 热点/内存：execution samples 主要为 exchange-core/Disruptor busy-spin（`ProcessingSequenceBarrier.checkAlert 37.07%`、`WaitSpinningHelper.tryWaitFor 15.92%`、cursor/min-sequence 23.33%）；业务侧 top 为 `TreeMap.getEntry 1.25%`、`ConcurrentHashMap.get 0.86%`、owner business hash mix `0.52%`。分配压力 top 为 `long[] 10.16%`、`byte[] 8.74%`、`Object[] 7.60%`、`Long 5.56%`、`ArrayList 4.34%`；按线程为 JMH owner `75.40%`、Core Fact materializer 合计 `18.04%`、projection 合计 `3.82%`。heap committed 8 GiB，GC 前最高 6.2 GiB，最后 post-GC 1.0 GiB；NMT末值主要为 heap 8 GiB、GC 88.9 MiB、code 40.6 MiB、metaspace 36.6 MiB、tracing 19.4 MiB。线程峰值 19，swap=0。
- 长稳失败：30 秒预热通过后，600 秒 measurement 在约 2 分钟处由 `core-fact-materializer` 抛出 `unknown patch asset id: 1` 并提前终止，无主分数。根因是 command-level `RuntimeFundsDelta` 可比其 `PatchChain` identity slice 存活更久，而异步 materializer 只用当前 patch slice 解析 posting asset；该资产仍存在于稳定 `RuntimeIdentityRegistry`，但未被 draft 保留为回退。失败发生在资金 posting 的协议物化层，不能忽略或声明泄漏/长稳通过。
- 结论与缺口：主吞吐、短轮正确性、尾延迟、分配、GC pause和环境门禁通过，但长稳出现真实 Core Fact 正确性错误，`PV-05` 最终判定验收失败。未完成10分钟泄漏斜率，不能声明 heap/native/FD/线程/buffer 无泄漏；未测其余五产品线及外部 API/Aeron Cluster/Kafka/exporter/PostgreSQL/WebSocket，fills/trades、拒绝/错误/超时和逐账户资金流水仍缺独立计数。
- artifact：`target/qualification/20260901T042552Z-owner-commit-compact-256/`；`main-256.json` SHA-256 `424f3f06ef23bb34118302c1489b108014ad0cedca33c8b0580b6e512608ab12`；`attribution-256.json` SHA-256 `544d78593bc0aec71f27be02098a82b966f1a3729a4d30176ed43c134a10cf86`；原始 attribution JFR SHA-256 `6abeeaff509744eb91c6960d4f16226edb1408de4034b96521afe0fa7d1160e4`；失败 soak JFR SHA-256 `391254ea26359cc19d86fc52cf585ef0fbac932f1cd4bfe21a95d3165933f3a3`。

### 2026-09-01 12:55:15 +08:00 — `PV-20260901-256-06` — `采集前锁定（Core Fact funds identity 修复轮）`

- 记录创建时间：`2026-09-01 12:55:15 +08:00`（`2026-09-01T04:55:15Z`）。被测 git commit `491dd66ef2c9a60d06827ef85ddf6eb72a5889b9`，分支 `codex/aeron-unified-core`，已推送；tracked 工作区 clean，已知 untracked `openai` 与三个 `.factorypath` 不进入构建、classpath 或 artifact。
- 对照 commit：直接父提交 `831de2dc` 只含 `PV-05` 锁定文档；本轮生产修复基于 `72216dc7198502b7b75d05acad0c294d1422931b`。按用户要求不检索或重跑旧历史，也不运行其他 in-flight；结论使用下述绝对门禁。
- 修改点：在 Core Fact draft 中保留稳定 `RuntimeIdentityRegistry` 作为资金 posting asset 解析回退；正常 patch-local identity 命中路径不变，只有 command funds delta 超出对应 patch chain 生命周期时使用回退。新增精确异步 materializer 回归，覆盖“funds delta 有 posting、draft 无 patch identity slice”的 `PV-05` 长稳故障。
- 采集前功能门禁：Oracle GraalVM HotSpot JDK 25.0.1 上 `mvn -pl :surprising-aeron-benchmarks -am test` 完成 product API 12、protocol 80、instrument API 13、service 501、benchmarks 16，共 `622/622`，0 failure/error/skipped；覆盖新增故障回归、资金守恒/hash、Core Fact、恢复、六产品线 snapshot 合同和永续端到端。
- 固定范围/场景：只测 `LINEAR_PERPETUAL` 的 `LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`；10k 活跃用户、512 listed/active symbols、4 Account Lane、1 matcher、1 risk engine、1 JMH thread/连接、每用户最多5持仓/10未成交单、16,384 PLACE_ORDER business operations/invocation、ACK interval 1,024，maker/taker 配对做市持续运行。严格固定 `256 in-flight`，禁止运行或推算其他档位；不启动 Docker、wallet、API、Aeron Cluster、Kafka、PostgreSQL、外部 exporter/history projection 或 WebSocket。
- 负载与正确性：open-loop constant-arrival offered `100,000 business ops/s`，计划到达排队计入 entry latency并修正 coordinated omission，histogram `1 ns–30 s`、timeout 30 秒。每轮要求 accepted business=terminal business、accepted Core=terminal Core、unfinished=0、期末 backlog=0、max backlog<=256、starvation=0，并通过资金守恒、用户/做市余额/冻结/持仓、订单终态、business/funds hash 与 snapshot restore；未输出的 fills/trades、拒绝/错误/超时和逐账户资金明细明确记为缺口。
- 性能门禁：无 profiler `terminal business ops/s >=9000`、99.9% CI 下界 `>=8000`、3 fork 均值各 `>=8000`；报告 terminal Core messages、逐 fork/iteration和区间。accepted→terminal p99/p99.9/max `<=35/50/150 ms`；entry→terminal p99/p99.9/max `<=1.6/1.65/2 s`。completion batch average/max `>1` 仅作诊断。
- GC/JFR门禁：`-prof gc` 分配 `<=76,800 B/business op`；ZGC stall/OOM/allocation requiring GC/promotion/evacuation failure=0、pause max<=1 ms。要求 DataLoss=0、swap=0、container throttling=0、owner measurement 同步 file/socket/database I/O=0；报告 CPU/线程、分配 class/site/thread、heap/GC、NMT/native/Direct/Mapped、锁/park、safepoint、JIT、I/O/异常。短 JFR 只作归因。
- 长稳门禁：仅当主吞吐、正确性、分配、尾延迟和环境有效性全部通过，运行同一 `256 in-flight` 场景 30 秒预热+10分钟 measurement；每10秒采样 NMT/RSS/线程/FD/swap，JFR比较至少3个 post-GC 点的 live set/old、native committed、Direct/Mapped/pool、线程和FD增长；任何 materializer 异常或无法解释的单调增长均失败。
- 固定环境/JVM：Intel i9-9880H 8C/16T、16 GiB、macOS 26.7/Darwin 25.6.0 x86_64、非容器、未绑核；锁定时 swap=0，无后台进程持续超过一个物理核。Oracle GraalVM HotSpot `25.0.1+8.1`、Maven 3.9.16；`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC`，Account Lane=4、matcher=1、BUSY_SPIN、settlement BLOCKING、completion spins=16384、projection PARKING batch64/4MiB、journal65536/1GiB、export pending256MiB。
- 阶段参数：主轮 `5x5s warmup + 5x5s measurement + 3 forks`；冷却30秒；归因 `5x5s warmup + 3x5s measurement + 1 fork -prof gc`，NMT/JFR/GC只放入 `-jvmArgsAppend`。JFR配置仍为 `owner-commit-profile.jfc`，SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。长稳为 `1x30s warmup + 1x600s measurement + 1 fork`，同一业务参数。
- artifact 根目录锁定为 `target/qualification/20260901T045515Z-owner-fact-assets-256/`；保存版本、完整命令、JAR/JFC/源码 SHA、JMH JSON/log、原始 JFR/GC/NMT、10秒长稳样本、latency/业务计数、JFR views以及 size/SHA-256 清单。任何失败/无效轮次均按时间追加，不覆盖本记录。

### 2026-09-01 13:05:45 +08:00 — `PV-20260901-256-06` — `尾延迟失败/部分验证`

- 被测生产实现 `491dd66ef2c9a60d06827ef85ddf6eb72a5889b9`；package `12:57:26 +08:00` 完成，主轮约 `12:57–13:02`，归因 JFR `13:03:13–13:04:24 +08:00`、71 秒。JAR SHA-256 `4c36f06648cc5a91ebaddf3c2636fe753157e21c30fcb1999ef9e42c8fda28cc`。全程只有 `256 in-flight`、1 matcher、BUSY_SPIN，无 Docker/外部服务。
- 功能/吞吐：Reactor `622/622`；主轮 `9285.944 ± 461.113 terminal business ops/s`，99.9% CI `[8824.832, 9747.057]`，3 fork=`9439.843/9073.451/9344.540`，全部吞吐门禁通过；`terminal Core messages/s=9295.013`，accepted=terminal、unfinished=0、starvation=0。
- 归因/分配：归因 `9100.138 terminal business ops/s`；`523.405 MB/s`、`998,067,619 B/invocation`，折算 `60,917.335 B/business op`，分配门禁通过。9 次 ZGC、39 pauses，总 `0.688 ms`、max `0.0545 ms`；DataLoss、ZGC allocation stall、container throttling和swap均为0。allocation top 为 `long[] 10.29%`、`byte[] 8.57%`、`Object[] 7.39%`、`Long 5.40%`、List iterator `5.35%`；owner线程 `75.41%`、Core Fact materializer合计 `17.97%`、projection合计 `3.89%`。
- 延迟：最后9个 measurement invocation、147,456 PLACE_ORDER样本。entry→accepted p50/p90/p95/p99/p99.9/max=`781.251/1459.751/1540.193/1602.087/1615.209/1689.726 ms`；accepted→terminal=`21.974/25.025/25.669/27.248/29.022/40.827 ms`；entry→terminal=`800.509/1480.566/1562.998/1624.131/1634.377/1710.706 ms`。accepted→terminal、entry→terminal p99.9/max通过，但 entry→terminal p99 超出1.6秒门禁 `24.131 ms`，故 `PV-06` 判定尾延迟失败。
- 结论：Core Fact identity 回退的短轮功能、吞吐、分配与GC门禁均通过，归因期间未复现 `unknown patch asset`；但按采集前规则，entry→terminal p99失败后不执行 `PV-06` 验收长稳，因此本轮只能标记部分验证，不能声明完整性能或无泄漏验收通过。

### 2026-09-01 13:05:45 +08:00 — `PV-20260901-256-07` — `采集前锁定（Core Fact故障诊断长稳）`

- 目标与边界：仅验证 commit `491dd66ef2c9a60d06827ef85ddf6eb72a5889b9` 是否修复 `PV-05` 在约2分钟触发的 `RuntimeFundsDelta.materialize -> unknown patch asset id: 1`；该诊断不覆盖 `PV-06` 的尾延迟失败，也不作为生产吞吐验收。
- 固定场景：复用 `PV-06` 的同一 shaded JAR、HotSpot JDK25/ZGC参数、LINEAR_PERPETUAL、10k用户、512 symbol、4 Lane、1 matcher、1 risk engine、BUSY_SPIN、maker/taker PLACE_ORDER、16,384 operations/invocation、100k/s open-loop offered rate；严格且仅使用 `256 in-flight`，不启动Docker或任何外部服务。
- 时长/采样：JMH `1x30s warmup + 1x600s measurement + 1 fork + 1 thread -prof gc`；fork JVM启用 NMT summary、GC/safepoint log和同一 JFC，JFR `maxsize=1G`。每10秒采样 NMT summary.diff、RSS、线程数、FD与swap；原始文件写入 `target/qualification/20260901T045515Z-owner-fact-assets-256/diagnostic-soak-*`。
- 通过标准：完整运行600秒且无 Core Fact/materializer/identity异常；accepted business=terminal business、accepted Core=terminal Core、unfinished=0、starvation=0，teardown资金守恒、余额/冻结/持仓、订单终态、business/funds hash与snapshot restore无异常。至少3个真实 post-GC点；live-set与old-generation Theil–Sen斜率各 `<=1 MiB/s`，Direct/Mapped各 `<=256 KiB/s`，线程/FD/Direct/Mapped pool count各 `<=0.01/s`；DataLoss、swap、throttling、ZGC stall/OOM为0。失败也必须保留并追加。

### 2026-09-01 13:20:04 +08:00 — `PV-20260901-256-07` — `诊断长稳失败/环境无效`

- 实际执行：同一 commit/JAR、`256 in-flight`、1 matcher 的诊断 JFR 从 `13:06:56` 记录至 `13:11:22 +08:00`，共266秒；30秒 warmup 完成，600秒 measurement 未完成。73个 saturation event 在失败前记录 `1,179,863 terminal business operations`、`1,197,200 terminal Core messages`，最大 backlog 256、starvation 0。
- Core Fact故障观察：`PV-05` 的 `unknown patch asset id: 1` 与 `Core Fact materialization failed` 在本轮均为0，运行时间和完成操作数均已超过原故障复现点；说明新增 registry 回退覆盖了原路径，但因未跑满600秒，只能称“本轮未复现”，不能称10分钟长稳通过。
- 终止原因：在 applied command `1,280,488` 时，PLACE_ORDER 被 `STALE_MARK_PRICE` 拒绝。benchmark 的逻辑时间为 `BASE_EPOCH + clusterPosition/256`，setup后不刷新mark；累计约128万命令后必然超过Core固定5秒 freshness bound。这是现有 saturation 长时场景缺少真实mark feed造成的确定性测试缺陷，不是本次资金identity回退错误。
- 环境无效：约2分28秒开始出现swap，随后升至约1.6 GiB；虽然 `DataLoss=0`、container throttling=0、ZGC allocation stall=0且JFR包含18次GC/36个heap summary点，本轮违反预锁定的swap=0条件，禁止用NMT/live-set/FD/线程斜率声明无泄漏。
- 场景修复实验：诊断失败后尝试过benchmark-only真实`APPLY_MARK_PRICE`刷新；精确测试依次暴露未完成risk scan导致GTC残留、错误scan batch被安全拒绝、以及完成scan后仍改变活动订单集合。三次均为失败测试，日志为 `mark-refresh-test*.log`；该未提交实验已全部撤回，tracked源码恢复clean，避免用会改变风险/订单语义的心跳伪造长稳。
- 最终结论：生产修复的622项功能回归、主吞吐、分配、GC与accepted→terminal链路通过；`PV-06`仍因entry→terminal p99失败，`PV-07`因STALE_MARK_PRICE与swap失败。当前可以确认原Core Fact identity异常在更长窗口内未复现，但缺少有效10分钟/长期泄漏证据，完整性能验收仍为部分验证。
- artifact：`target/qualification/20260901T045515Z-owner-fact-assets-256/`；`main-256.json` SHA-256 `3161cfa2a850b82a50119abc782e0c618cef448a9b5d573756e7c5883533a4be`；`attribution-256.json` SHA-256 `dfb473f7d74339674005a5c29a708bfe82365ed915909dbebf35a1ded3df5295`；attribution JFR SHA-256 `619c5ee0490ded9ff74e3ed0fc5811fbce040a140a11aa9d8116afda3c798f85`；diagnostic soak JFR SHA-256 `681c73a55489361c0f05133a87d426b13825ad237fc2e862988053e7bbc151a1`；artifact SHA清单 SHA-256 `dce19f22b1cfae3cf8d0cc0b26af13317614e3456fc4d697a19652c542c3ab2c`。

### 2026-09-01 14:05:11 +08:00 — `PV-20260901-256-08` — `采集前锁定（Aeron 1.53.0 / exchange-core 直接撮合管线）`

- 记录创建时间：`2026-09-01 14:05:11 +08:00`（`2026-09-01T06:05:11Z`）。被测生产 commit `33e8aedf29d80d3ee3fc99d2c372bcd5316a8b3d`，分支 `codex/aeron-unified-core`，已推送；对照 commit `491dd66ef2c9a60d06827ef85ddf6eb72a5889b9` 只用于界定本轮代码修改范围，不检索、不重跑或横向比较旧历史性能数据。本轮采用以下绝对门禁。
- 依赖锁定：Aeron `1.53.0`，来自官方 tag `af20315a6b6323783ae717b075ba4a70c9abbf0c` 本地 `:aeron-all:publishToMavenLocal`，`aeron-all-1.53.0.jar` SHA-256 `1a0c3434416cb7c98716caaffd52f854241f7171780528f501fd0319f67ee0b7`；当前 Maven mirrors 尚无该版本，不能自动降级。exchange-core fork `0.5.17-emporia` commit `a85db2d210c478ec9ba97940db6b48de820f4dd4`，JAR SHA-256 `0f55185e990b9c60e1a48da4171e22210f1def32956549c04ab17535dc3c19be`，已推送。
- 修改点：exchange-core 增加 matching-only `G→ME→E` 直接管线，删除该模式下 R1/R2、risk-module snapshot、API promises map/Future及热路径 matcher user 注册；下单/撤单/改单使用 primitive publish 和有界 correlation completion ring。项目固定 1 matcher、exchange-core risk engine 0，Product Core 的账户/持仓/风控/结算逻辑仍保留。owner-only identity 正向索引改为 `HashMap`，异步 Core Fact 使用的反向索引保持并发；lane revision hash 直接读取 primitive lane hash，终态 ID 收集移除 stream/iterator。未启用 fork 的 `singleProducer`：当前 owner 与 matcher continuation 结果线程都可能发布，强制 SINGLE 会破坏 Disruptor 发布者契约。
- 采集前功能门禁：Oracle GraalVM Java HotSpot 25.0.1+8.1、Maven 3.9.16；fork `mvn clean install` 为 `305/305`，主项目 `mvn -pl :surprising-aeron-benchmarks -am test` 为 product API 12、protocol 80、instrument API 13、service 503、benchmarks 16，共 `624/624`，0 failure/error/skipped。覆盖直接 completion、下单/撤单/改单、资金与持仓、hash/patch、Core Fact、snapshot/recovery、六产品线 snapshot 合同和永续端到端。
- 固定业务场景：只测 `LINEAR_PERPETUAL` 的 `LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`；10,000 活跃用户、512 listed/active symbols、4 Account Lane、1 exchange-core matcher、0 exchange-core risk engine、1 JMH worker/进程内连接。每轮 16,384 个 `PLACE_ORDER` business operations，50% maker GTC + 50% taker IOC，以同 symbol/价格/数量 1 配对成交；maker/taker 做市状态全程运行。每用户最多 5 持仓、10 未成交单，初态由固定 snapshot template 恢复并有足额用户/做市资金。
- 负载与 in-flight：严格且仅使用 `256 in-flight`；不运行、补跑、推算或比较任何其他档位。open-loop constant-arrival offered rate `100,000 business ops/s`，计划到达等待计入 entry latency并修正 coordinated omission；histogram `1 ns–30 s`、progress timeout 30 秒，ACK interval 1,024。外部 exporter/history projection、API、Aeron Cluster、Kafka、数据库、WebSocket、Docker和wallet不启动；进程内 runtime projection、Core Fact materializer、snapshot/recovery包含在场景中。
- 正确性门禁：accepted business=terminal business、accepted Core=terminal Core、两个 unfinished=0、期末 backlog=0、最大 backlog `<=256`、producer starvation=0；拒绝、错误、超时为0，并通过用户/做市余额、冻结、持仓、资金守恒、活动订单、business/funds hash和snapshot restore。若 benchmark 未输出 fills/trades、逐账户资金流水或独立拒绝/错误/超时计数，必须记为证据缺口，不得推算。
- 吞吐门禁：无 profiler 主轮 `terminal business ops/s >=9,000`、99.9% CI下界 `>=8,000`、3个fork均值各 `>=8,000`；同时报告 terminal Core messages/s、逐 fork/iteration、score error/CI、completion mailbox和completion batch。此次优化是否提升只按本轮门禁及代码路径分析判断，不拿旧历史轮次作正式数值对照。
- 延迟门禁：按 PLACE_ORDER 分别报告 entry→accepted、accepted→terminal、entry→terminal 的 p50/p90/p95/p99/p99.9/max和样本数；accepted→terminal p99/p99.9/max `<=35/50/150 ms`，entry→terminal p99/p99.9/max `<=1.6/1.65/2 s`。
- GC/分配门禁：独立 `-prof gc` 报告 allocation rate、B/JMH invocation、B/terminal business op，要求 `<=61,440 B/business op`；ZGC allocation stall/OOM/allocation requiring GC/promotion/evacuation failure=0，JFR GC pause max `<=1 ms`。报告 TLAB/非TLAB、top allocation class/thread/site、heap committed/used和GC前后/live-set趋势。
- JFR/NMT门禁：保存原始 `.jfr`、summary以及 CPU/线程/分配/GC/native/锁/park/safepoint/VM/JIT/I/O/异常聚合；按 owner/driver、matcher/Disruptor、Product Core risk、projection、Core Fact、snapshot、Aeron/外围线程分组。要求 `DataLoss=0`、container throttling=0、owner measurement窗口同步业务file/socket/database I/O=0；报告 NMT reserved/committed、Direct/Mapped/native pool、线程峰值及busy-spin CPU。短 JFR只用于归因，不能证明无泄漏。
- 长稳/泄漏：只有主吞吐、正确性、尾延迟、分配和环境有效性门禁均通过，才运行同一 `256 in-flight` 场景的 30秒 warmup + 10分钟 measurement；每10秒采样 NMT/RSS/线程/FD/swap，要求至少3个post-GC点且live-set/old-generation斜率各 `<=1 MiB/s`，Direct/Mapped各 `<=256 KiB/s`，线程/FD/buffer count各 `<=0.01/s`，没有 Core Fact/materializer/STALE_MARK_PRICE 或其他业务错误。前置门禁失败则不运行并明确记录。
- 固定环境：Intel Core i9-9880H 8C/16T、16 GiB，macOS 26.7 / Darwin 25.6.0 x86_64，非容器、未绑核。锁定时 `vm.swapusage used=744.25 MiB`，因此有效性改为采样窗口 swap used 不增长超过64 MiB且无持续page-in/page-out/内存压力；若发生则本轮内存与容量结论降级为诊断。锁定时Terminal约18.8% CPU、WindowServer约13.6%，没有单个后台进程持续占满物理核；不擅自终止用户进程。
- JVM/JMH：`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`，开放 `jdk.internal.misc/ref`；Account Lane=4、matcher=1、matcher BUSY_SPIN、settlement BLOCKING、completion spins=16,384、projection PARKING/batch64/4MiB、journal65,536/1GiB、export pending256MiB。主轮 `5x5s warmup + 5x5s measurement + 3 forks + 1 thread`，随后冷却30秒；归因轮 `5x5s warmup + 3x5s measurement + 1 fork + 1 thread -prof gc`。
- JFR配置：`surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc`，SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`；仅 fork JVM启用 JFR、NMT summary、GC/safepoint log，profiler分数不替代主吞吐。采集前重新 package并记录新 shaded JAR SHA。
- 执行命令口径：先 `mvn -pl :surprising-aeron-benchmarks -am -DskipTests package`，再以同一个 `product-core-benchmarks.jar` 显式传入以上全部参数执行主轮与归因轮；完整命令、stdout/stderr、JDK/Maven/git/dependency/JAR/JFC SHA、系统和进程快照写入 artifact。
- artifact根目录锁定为 `target/qualification/20260901T060511Z-aeron153-direct-matcher-256/`；任何成功、失败、中止或无效轮次以及所有指标、问题、未测范围均按时间追加到本文件，不覆盖历史记录。

### 2026-09-01 14:30:41 +08:00 — `PV-20260901-256-08` — `尾延迟/环境门禁失败，部分验证`

- 实际执行：被测生产 commit `33e8aedf29d80d3ee3fc99d2c372bcd5316a8b3d`，采集锁定 commit `41f201f4`；package 于 `14:07:23 +08:00` 完成，无 profiler 主轮总时长 `4m14s`，归因 JFR 为 `14:13:42–14:14:59 +08:00`、77秒。shaded JAR SHA-256 `c324bee5d335e61b13177c6b92f2432484d3ffa106364152c9d341c2bb6e2d3c`；JAR内 `AeronVersion.VERSION=1.53.0`、git SHA `af20315a6b`。全程只使用 `256 in-flight`、1 matcher、0 exchange-core risk engine，没有 Docker、wallet 或外部服务。
- 功能与正确性：exchange-core fork `305/305`、主项目受影响 reactor `624/624`，0 failure/error/skipped；主轮和归因轮均正常 teardown，accepted business=terminal business、accepted Core=terminal Core、两个unfinished=0、producer starvation=0。归因事件的最大/平均backlog为 `256/232`，completion mailbox高水位 `256/4096`；9个正式invocation共 `2,304 batches / 147,456 items`，average/max batch size均为64。资金、余额/冻结/持仓、活动订单、business/funds hash和snapshot restore未报异常；benchmark仍缺独立fills/trades、逐账户资金流水及拒绝/错误/超时计数，不能补推。
- 主吞吐：无 profiler `9268.343 ± 351.746 terminal business ops/s`，99.9% CI `[8916.597,9620.089]`；`terminal Core messages/s=9277.394 ± 352.090`。3个fork均值 `9156.169 / 9087.845 / 9561.016`，15个measurement范围 `8614.103–9801.261 ops/s`；吞吐绝对值、CI下界和逐fork门禁通过。没有重跑对照commit，因此不以旧轮次数值声明相对提升。
- 归因吞吐与分配：带 `-prof gc` 的 `terminal business ops/s=8241.206`，只用于归因；`gc.alloc.rate=465.012 MB/s`、`978,002,331.556 B/JMH invocation`，折算 `59,692.525 B/terminal business op`，通过 `61,440 B/op` 门禁。完整JFR记录的TLAB事件为113,151个/27.4 GiB，outside-TLAB为4,184个/664.4 MiB，最大outside-TLAB分配32 MiB；采样事件不能提供可靠精确对象数/op，明确记为N/A。
- 分配归因：线程占比为owner/JMH worker `74.72%`、两个Core Fact materializer合计 `18.52%`、两个projection合计 `4.09%`。class top为 `long[] 10.14%`、`byte[] 8.70%`、`Object[] 7.93%`、immutable list iterator `5.97%`、`Long 5.61%`；site top仍是primitive map扩容 `3.94%`、`HashMap.putVal 3.04%`、`ByteBuffer.allocate 2.84%`、UserHash复制 `2.33%`、`Changes.hasChanges/seal 4.19%`、stream构造、identity slice、Core Fact materialization和projection。直接matcher API删除了ApiCommand/promises-map工作，但端到端主要分配仍在owner/Core Fact/projection。
- 延迟：最后9个正式invocation共147,456个PLACE_ORDER样本，open-loop/coordinated-omission corrected，直方图 `1 ns–30 s`、timeout 30秒。entry→accepted p50/p90/p95/p99/p99.9/max的invocation中位数为 `873.663/1604.118/1692.024/1758.270/1771.117/1771.238 ms`；accepted→terminal为 `24.103/27.668/28.894/31.530/33.280/34.317 ms`，各invocation最坏max为 `38.996 ms`；entry→terminal为 `897.397/1627.017/1715.666/1781.257/1792.960/1794.241 ms`，各invocation最坏max `2014.389 ms`。accepted→terminal门禁通过；entry→terminal p99、p99.9和max分别超过 `1.6/1.65/2s` 门禁，尾延迟失败。100k/s offered rate远高于约9.27k/s持续服务能力，入口计划到达排队是当前主要延迟来源。
- GC/heap：profiler记录6次GC、concurrent GC time `1076 ms`。JFR为9次ZGC（7 major、2 minor），41个pause，总pause `0.607 ms`，p50/p90/p95/p99/max=`0.0111/0.0255/0.0310/0.0638/0.0638 ms`；并发GC总时长4.932秒。`DataLoss=0`，allocation requiring GC、ZGC allocation stall/OOM、promotion/evacuation failure均为0。heap committed固定8 GiB、used最高6.574 GiB，最后post-GC 1.072 GiB；短记录的post-GC点受启动、JIT和状态推进影响，不能作为泄漏结论。
- native/direct：NMT结束总reserved/committed=`147,611,536,138 / 8,814,189,322 B`；主要末值为heap 8 GiB、GC 92.7 MiB、code 39.5 MiB、metaspace 36.4 MiB、tracing 19.4 MiB。ZGC约136 GiB reserved address space不是物理占用。DirectBuffer 76个采样点首末count/used均0，峰值count=1、used=1 B；没有Mapped或业务native pool余额证据。NMT首末跨度包含完整启动和JIT，不能用于声明长期无增长。
- CPU与热点：JVM user/system CPU平均 `28.53%/0.99%`，机器总CPU平均36.01%、最大47.05%。通用exchange-core/Disruptor线程的user CPU约4.10–6.21%，snapshot encoder 4.67%、owner/driver 4.55%、projection合计约2.41%、snapshot audit 1.54%、Core Fact materializer合计约0.67%。12,969个execution samples中 `ProcessingSequenceBarrier.checkAlert 47.20%`、`Sequence.get 24.44%`、minimum-sequence/busy-spin约2.12%，合计约73.76%是Disruptor等待/cursor开销；业务top为TreeMap、HashMap清理/迭代、`resultEntryDigest`、ConcurrentHashMap、business/funds hash和patch stage。当前JFR说明matcher结果适配已不是业务top热点，下一瓶颈仍是owner状态/hash/patch与Core Fact/projection分配。
- 线程、锁与调度：Java线程峰值17；1,162,644个park事件合计并发线程park时长3m54s，p99 0.360ms，包含PARKING projection及JMH/生命周期等待。monitor contention仅18次，最大0.195ms，top为exchange-core affinity线程初始化/释放及JAR类加载，没有持续业务锁竞争。BUSY_SPIN线程CPU已单独列出，不能当作业务计算吞吐。
- Safepoint/JIT：50个完成safepoint总3.005ms、单次SafepointEnd最大0.751ms；51次到达safepoint总4.572ms、最大0.951ms；184个VM operation总7.883ms、最大0.607ms。Compilation 10,255次、各编译线程累计41.968秒、最长762.850ms，code cache三段最大used合计约29.94 MiB、full count=0；deoptimization 550次、class load/unload `4032/1`。measurement开始后仍有Core Fact materializer 316/294ms编译，说明本机5x5秒warmup没有完全越过JIT阶段，是尾延迟和重复性风险之一。
- I/O与异常：完整启动/预热记录含3,284 FileRead/200 FileWrite/3 SocketRead/34 SocketWrite，主要是shaded JAR、JDK配置、JNA/JFFI/LZ4临时native库和JMH localhost控制；严格从最后9个正式invocation起筛选，owner/driver同步I/O为 `0 events / 0 B`，门禁通过。JFR记录1,335 Java exception和147 error，top为NoSuchField/NoSuchMethod/NoSuchMethodError及native symbol探测，top site均为反射/JNR/Chronicle初始化；没有交易业务throw site或benchmark失败。
- 系统有效性：JFR `ContainerCPUThrottling=0`、pages throttled=0。实际采样窗口swap used从744.25 MiB降至680.25 MiB、swapouts保持593,300页，但swapins从382,625增至395,296页（约49.5 MiB）且pageouts增长，违反预锁定的“无持续page-in/page-out”条件，因此容量/内存结论降级为诊断。采样结束后，曾尝试的全量JFR JSON展开将80 MiB原始记录膨胀到约6.5 GiB并引发额外swap；该分析进程已停止，精确删除其不完整巨型中间JSON/CSV，保留原始JFR、summary、37个JDK聚合view和小型证据文件。测后分析造成的swap不计入已结束的采样窗口。
- 长稳：由于entry→terminal尾延迟和系统swap活动前置门禁失败，按锁定规则不执行10分钟长稳；因此没有有效live-set/old/native/FD/线程/pool增长斜率，也不能声明无泄漏。既有长时场景的mark freshness边界本轮没有修改或绕过。
- artifact：`target/qualification/20260901T060511Z-aeron153-direct-matcher-256/`，69个受校验文件、约59 MiB。`main-256.json` SHA-256 `42a4650c451f59473b86bb52aef992ef7061d3200b8eb3edcc9701f4d344a67b`；`attribution-256.json` `795e35861e24401dbb3c0272716cfc9a72976b15e9ee135ba7c77ce8c7e509c1`；原始JFR `85a5d011e07ad62d9bb3469586b476260a6106daf5b427aa8af0dcfbf3c21546`；JFR summary `4f691027ca055eb3d3a48589f0203c33e927cb98d06dc847243db66d02e5b0b7`；latency summary `2fb6ed6d5ee7e02921b619c588eb777c57c63da17c7da3670729794bfbb31de0`。artifact SHA清单 SHA-256 `25c5c3b2671dea04239d4014d7a008be21a5215aca975269f10b55fab237b8bc`，size清单 `5f3843ec1fcb416948368c2cd7224575bc1cf7e224b41ffe9927a7e352fb9541`。
- 未测范围：其余五产品线、取消/改单/批量/触发/风险扫描/强平/资金费/ADL/保险基金/交割结算的独立性能分布、API requests/s与连接、Aeron Cluster复制、Kafka、外部exporter/history projection、数据库和WebSocket；任何其他in-flight档位按约束均未测试。
- 最终结论：Aeron 1.53.0、exchange-core直接管线和owner局部优化的功能回归通过，固定256 in-flight下主吞吐门禁通过，当前聚合为 `9268.343 terminal business ops/s`、最高单iteration `9801.261`；但entry→terminal尾延迟和环境有效性失败，且没有长稳泄漏证据，本轮只能标记“部分验证/性能验收未通过”。当前数据不支持宣称端到端吞吐显著提升；代码层已移除matcher API/promises/R1/R2开销，而JFR表明后续优化重点仍是owner/Core Fact/projection的状态物化与约59.7 KiB/op分配。

### 2026-09-01 15:11:15 +08:00 — `PV-20260901-256-09` — `采集前锁定（owner/Core Fact/projection 状态物化）`

- 记录创建时间：`2026-09-01 15:11:15 +08:00`（`2026-09-01T07:11:15Z`）。被测生产 commit `572e7e79`，分支 `codex/aeron-unified-core`，已推送；对照代码 commit `33e8aedf29d80d3ee3fc99d2c372bcd5316a8b3d` 只用于界定本次七项代码差异，直接父提交 `6233d4dc` 只包含既有性能记录。本轮不检索、不重跑、不引用旧轮性能数值作数值对照，只按下列预先锁定的绝对门禁验收最新代码。
- 七项修改点：① `RollingBusinessStateHash` 非资金状态改为 typed opcode journal，移除逐项 closure；② `UserHash` 对 balance/reservation/position 分域 copy-on-write；③ asset/symbol 使用版本化 append-only identity registry，patch 不再复制稳定字典，恢复时重建版本；④ Core Fact 唯一键与 tombstone 冲突校验使用有界可复用 primitive scratch 和 canonical 二分查找；⑤ projection 直接消费 sealed patch 的排序列表，删除第二层 user/map/set materialization；⑥ Account Lane 写入 lane-local primitive dirty journal，Sequencer 固定顺序刷新到 owner-only primitive published map，移除跨 lane CHM 发布；⑦幂等 ledger 缓存 command-bound digest 与 retention weight，淘汰时不重算。`RuntimeCommitPatch.Changes` 同时用 O(1) changed count 避免无变化列表排序/封装。
- 功能门禁：Oracle GraalVM Java HotSpot 25.0.1+8.1、Maven 3.9.16；`mvn -pl :surprising-aeron-benchmarks -am test` 已通过 service `505/505`、benchmarks `16/16`，共 `521/521`、0 failure/error/skipped。覆盖真实永续撮合、lane settlement、资金/持仓矩阵、typed patch/hash commit/rollback、Core Fact、幂等、identity snapshot restore、projection rollback、snapshot/recovery；未启动 Docker、wallet 或外部服务。
- in-flight 与产品线：全部性能采集严格且仅使用 `256 in-flight`，不运行、补跑、推算或比较 `64/512/1024` 等任何其他档位。只启动/测试 `LINEAR_PERPETUAL`；matching engine 固定 `1`，exchange-core risk engine `0`，Product Core risk engine `1`，Account Lane `4`。
- 固定业务场景：`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`；10,000 活跃用户、512 listed/active symbols、1 JMH worker/进程内连接。每 invocation 16,384 个 `PLACE_ORDER` business operations，50% maker GTC + 50% taker IOC，以同 symbol/价格/数量 1 配对成交；做市对手盘持续运行。每用户最多5持仓/10未成交单；初态从固定 snapshot template 恢复，用户和做市账户有足额资金，结束时检查余额、冻结、持仓、活动订单、business/funds hash、资金守恒和 snapshot restore。
- 负载模型：open-loop constant-arrival offered rate `100,000 business ops/s`，计划到达等待计入 entry latency并修正 coordinated omission；每次最多256在途，ACK interval 1,024，histogram `1 ns–30 s`、progress timeout 30秒。外部 API、Aeron Cluster复制、Kafka、exporter/history projection、数据库、WebSocket、Docker和wallet不启动；进程内 matcher、Product Core账户/风险、runtime projection、Core Fact materializer及 snapshot/recovery均包含。
- 正确性/容量门禁：`acceptedBusinessOperations == terminalBusinessOperations`、accepted Core messages=terminal Core messages、两个 `unfinished*`=0、期末 backlog=0、最大 backlog `<=256`、producer starvation=0；拒绝、错误、超时为0；资金/余额/冻结/持仓/订单终态/hash/snapshot recovery无异常。若场景没有独立输出 fills/trades、逐账户资金流水或拒绝/错误/超时计数，必须记为证据缺口，不能推算。
- 吞吐门禁：无 profiler 主轮 `terminal business ops/s >=10,000`，99.9%置信区间下界 `>=9,000`，3个fork均值分别 `>=9,000`；同时报告 terminal Core messages/s、逐fork/iteration、误差/CI、batch/items、completion mailbox及backlog。带 profiler轮只用于归因，不替代主吞吐结论。
- 延迟门禁：PLACE_ORDER 分别报告 entry→accepted、accepted→terminal、entry→terminal 的 p50/p90/p95/p99/p99.9/max、样本数、直方图区间及timeout。accepted→terminal p99/p99.9/max `<=35/50/150 ms`；entry→terminal p99/p99.9/max `<=1.70/1.75/2.10 s`。
- GC/分配门禁：独立 `-prof gc` 报告 allocation rate、B/JMH invocation和B/terminal business op，要求 `<=51,200 B/business op`；ZGC allocation stall/OOM、allocation requiring GC、promotion/evacuation failure=0，JFR GC pause max `<=1 ms`。同时报告 TLAB/非TLAB、最大对象、top allocation class/thread/site、heap committed/used、GC前后和post-GC趋势。
- JFR/NMT门禁：保存原始 `.jfr`、summary、相关JDK views、GC/safepoint log和NMT summary/diff；按owner/driver、exchange-core/Disruptor、Product Core risk、projection、Core Fact、snapshot、Aeron/外围线程分组报告CPU/execution samples/墙钟热点。报告native reserved/committed、Direct/Mapped/pool、线程峰值与RUNNABLE/BLOCKED/WAITING/PARKED、锁/park、safepoint/VM operation、JIT/deopt/code cache/class/metaspace、I/O和异常；要求 `DataLoss=0`、container throttling=0、owner measurement窗口同步业务file/socket/database I/O=0。
- 长稳/泄漏：因为 identity registry、lane journal、ThreadLocal scratch 和幂等 retention 属于长期状态，只有主吞吐、正确性、尾延迟、分配和环境有效性门禁均通过，才运行同一 `256 in-flight` 场景的30秒预热+10分钟稳定负载，每10秒采样。至少3个真实post-GC点；live-set/old-generation斜率各 `<=1 MiB/s`，Direct/Mapped各 `<=256 KiB/s`，线程/FD/buffer count各 `<=0.01/s`，无 Core Fact/materializer、mark freshness或业务错误。前置门禁失败则不运行并记录缺口，短JFR不用于声明无泄漏。
- 固定环境：Intel Core i9-9880H 8C/16T、16 GiB，macOS 26.7 / Darwin 25.6.0 x86_64，非容器、未绑核。锁定时 swap used `710.50 MiB`；有效性要求采样窗口swap增量不超过64 MiB且没有持续page-in/page-out或明显内存压力。锁定瞬间Spotlight/XProtect有短时高CPU，正式采集前必须确认其已回落；出现持续同机干扰、额外benchmark JVM、thermal/throttling或JFR DataLoss则环境结论无效。既有用户进程不擅自终止。
- JVM/JMH：`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`，开放 `jdk.internal.misc/ref`；Account Lane=4、matcher=1、matcher `BUSY_SPIN`、settlement `BLOCKING`、completion spins=16,384、projection `PARKING`/batch64/4MiB、journal65,536/1GiB、export pending256MiB。主轮 `5x5s warmup + 5x5s measurement + 3 forks + 1 thread`，冷却30秒；归因轮 `5x5s warmup + 3x5s measurement + 1 fork + 1 thread -prof gc`。
- JFR：配置 `surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc`，锁定SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。仅归因fork JVM启用JFR、NMT summary和GC/safepoint log；JFR从fork启动记录，分析时分开启动/预热与最后3个measurement invocation。
- 执行命令口径：先 `mvn -pl :surprising-aeron-benchmarks -am -DskipTests package`；随后对同一 `product-core-benchmarks.jar` 运行上述场景，显式传入全部参数和 `-p maxInFlight=256`。主轮输出JSON，归因轮同时使用 `-prof gc`、`-XX:StartFlightRecording`、NMT和GC日志；完整命令、stdout/stderr、JDK/Maven/git/dependency/JAR/JFC SHA、系统/进程前后快照进入artifact。
- artifact根目录锁定为 `target/qualification/20260901T071115Z-owner-materialization-256/`；所有成功、失败、中止、无效轮次、采集指标、原始artifact路径与校验信息、问题、未测范围和结论只按时间追加回本记录，不覆盖历史内容。

### 2026-09-01 15:26:43 +08:00 — `PV-20260901-256-09` — `吞吐/环境门禁失败，分配与链路延迟通过`

- 实际执行：被测生产 commit `572e7e79`，采集锁定 commit `542e1566`；package 成功，shaded JAR SHA-256 `4edc58ec5b90bac5a2052de19adeb1a884c2fd9172228e848de4ae48a6ec9491`。无 profiler 主轮 `15:14:07–15:18:22 +08:00`、总4分12秒；归因 JFR `15:19:39–15:20:52 +08:00`、约72秒。全部运行只有 `256 in-flight`、4 Account Lane、1 exchange-core matcher、0 exchange-core risk engine、1 Product Core risk engine；Aeron runtime `1.53.0` / git `af20315a6b`。没有启动 Docker、wallet、外部 exporter/history projection或其他产品线。
- 功能与正确性：采集前受影响 reactor `521/521` 通过，0 failure/error/skipped；主轮/归因轮都正常 teardown。主轮 accepted business=`9030.323 ops/s`=terminal business，accepted Core=`9039.142 messages/s`=terminal Core，两个 unfinished=0、producer starvation=0。归因正式9个invocation共147,456 terminal business operations、147,600 terminal Core messages，最大/平均backlog `256/232`，期末backlog=0；2,304 batches/147,456 items，平均/最大batch size均64，completion mailbox高水位 `256/4096`。资金、余额/冻结/持仓、活动订单、business/funds hash与snapshot restore未报异常；独立fills/trades、逐账户资金流水和拒绝/错误/超时计数仍未输出，属于证据缺口。
- 主吞吐：无 profiler `9030.323 ± 345.157 terminal business ops/s`，99.9% CI `[8685.167,9375.480]`；terminal Core `9039.142 ± 345.494 messages/s`。3个fork均值 `9208.170 / 8760.205 / 9122.596`，15个measurement范围 `8119.636–9419.525 ops/s`。未达到 `>=10,000` 聚合、CI下界 `>=9,000`，且第2个fork未达到9,000，吞吐门禁失败；不挑选单iteration最高点作为结论，也没有重跑对照commit。
- 延迟：归因最后9个正式invocation共147,456个PLACE_ORDER样本，open-loop/coordinated-omission corrected，直方图 `1 ns–30 s`、timeout 30秒。entry→accepted p50/p90/p95/p99/p99.9/max的invocation中位数为 `812.955/1488.966/1575.242/1640.496/1652.897/1653.015 ms`；accepted→terminal为 `22.650/25.646/26.315/27.819/29.133/29.544 ms`，各invocation最坏max `42.169 ms`；entry→terminal为 `834.064/1511.869/1598.902/1662.006/1671.992/1673.066 ms`，各invocation最坏max `1888.408 ms`。三段锁定门禁通过，但100k/s offered rate显著高于约9k/s服务能力，entry段主要是计划到达排队，不能用其代替accepted→terminal处理延迟。
- GC/分配：归因吞吐 `8776.009 terminal business ops/s`，只用于归因；`gc.alloc.rate=407.285 MB/s`、`801,813,307.556 B/JMH invocation`，折算 `48,938.801 B/terminal business op`，通过 `<=51,200 B/op` 门禁。JFR TLAB `91,395` events/22.0 GiB，outside-TLAB `4,122` events/718.3 MiB，最大outside-TLAB对象32 MiB；这是完整JFR采样事件，不能作为精确对象数/op。
- 分配归因：线程累计分配占比为owner/JMH worker约 `79.99%`，两个Core Fact materializer合计约 `15.40%`，两个projection合计约 `1.75%`；class top为 `long[] 12.34%`、`byte[] 10.85%`、`Object[] 8.63%`、immutable list iterator `4.97%`、`Long 3.55%`。site top仍包括primitive map扩容 `4.36%`、`ByteBuffer.allocate 3.47%`、`HashMap.putVal 3.46%`、`ArrayList.add 3.37%`、UserHash复制 `2.73%`、UserGroupUpdate `1.93%`、`List.copyOf 1.72%`、identity lookup/capture归因 `1.65%`、canonical校验 `1.42%`、stream `1.33%`、`Changes.seal 1.14%`和Core Fact fragment `1.08%`。七项修改把绝对分配压到门禁内，但剩余容器扩容、列表/stream、UserHash stage与编码缓冲仍是明确优化面。
- GC/heap：JFR 8次ZGC、36个pause，总pause `0.502 ms`，p50/p90/p95/p99/max=`0.0103/0.0273/0.0364/0.0786/0.0786 ms`；最长phase为Pause Mark Start (Major) `0.0786 ms`。heap committed固定8 GiB，GC前最高约6.4 GiB，最后post-GC约1.1 GiB。`AllocationRequiringGC=0`、ZGC allocation stall/OOM和promotion/evacuation failure=0。短记录不能证明live-set稳定或无泄漏。
- native/direct：进程退出NMT总reserved/committed=`147,565,595,067 / 8,769,673,659 B`；committed主要是8 GiB heap、GC 51.2 MiB、code 39.4 MiB、metaspace 36.3 MiB、tracing 18.7 MiB。ZGC约136 GiB reserved address space不是物理占用。DirectBuffer 72个采样点首末count/used均0，峰值count1/used1 B；没有Mapped或业务native pool余额证据。没有长稳斜率，禁止给出native/Direct无泄漏结论。
- CPU与热点：JVM user/system CPU平均 `28.61%/0.97%`，机器总CPU平均 `33.10%`、最大 `46.41%`。8个exchange-core/Disruptor通用线程user CPU约 `5.81–6.22%`，owner/driver `5.68%`，snapshot encoder/audit `2.75%/2.29%`，projection合计约 `1.25%`，Core Fact materializer合计约 `0.83%`。12,277个execution samples中 `ProcessingSequenceBarrier.checkAlert 47.53%`、`Sequence.get 24.28%`、minimum-sequence/busy-spin `1.80%`，约73.61%仍为Disruptor等待/cursor；业务top为TreeMap、HashMap clear/iterator、primitive map遍历/lookup、business/funds hash与`CoreProbeState.completeMatching`。目标状态物化工作已缩小但没有消失，CPU供给仍大量被busy-spin线程占用。
- 线程、锁与调度：Java线程峰值17、记录末13；`ThreadPark=1,130,817` events，主要来自PARKING projection/JMH与生命周期等待。monitor contention 16次，最大 `0.223 ms`，owner/driver 5次最大 `0.159 ms`，没有持续业务锁竞争。BUSY_SPIN线程CPU已单列，不能计作有效撮合计算。
- Safepoint/JIT：45个完成safepoint中最长 `0.980 ms`、最长到达同步 `0.785 ms`；退出时未闭合的最后事件不计。180个VM operation中最长 `0.579 ms`。Compilation `10,047`次，最长 `752 ms`，长编译包含snapshot codec、projector、Core Fact materializer和retention；deoptimization `547`次，class load/unload `3986/1`，三段code cache最大used合计约29.7 MiB且full count=0。5×5秒预热后仍存在业务相关长编译，是重复性风险。
- I/O与异常：完整启动/预热记录含3,274 FileRead、200 FileWrite、3 SocketRead、34 SocketWrite，主要为shaded JAR、JDK配置、JNA/JFFI/LZ4临时库和JMH localhost控制；严格从最后9个正式invocation窗口 `15:20:35.166–15:20:52.006` 过滤，owner/driver同步I/O=`0 events / 0 B`。异常top均为反射/native capability探测，未见交易业务throw site；`DataLoss=0`、container CPU throttling=0、code cache full=0。
- 系统有效性：swap used在主轮和归因轮前后都为 `710.50 MiB`，但全程swapins从524,217增至531,312页、pageouts从141,417增至147,810页；虽然Pages throttled=0，持续page-in/page-out违反预锁定环境条件，因此容量和内存结论降级为诊断。锁定瞬间的Spotlight/XProtect高CPU在正式开始前已回落，但Terminal/WindowServer/Codex/WeChat等用户进程仍未隔离。
- 长稳：吞吐门禁和环境有效性门禁失败，按预锁定规则不执行10分钟长稳。因此identity registry、ThreadLocal scratch、lane journal、幂等retention、live-set/old/native/FD/线程/buffer pool增长斜率均无长期证据，不能声明无泄漏；也未触碰既有mark freshness边界。
- artifact：`target/qualification/20260901T071115Z-owner-materialization-256/`，76个文件、约79 MiB。`main-256.json` SHA-256 `37e452fc3ad6db681da8fca276da342bcee20da60270c697d93e1a7eb02b5e6f`；`attribution-256.json` `6f84c0d0ece9fe82be484ffa484047988f70e74996e581dfaeb8e6d70c9311b9`；原始JFR `d63ab9f55c665e5614a81a65a7dd6c2129b104481db174e396f6c02f0de71604`；JFR summary `391494e8ef857c31b55c4def8fd4edc95e59d2c630c66abc1148ff1fcc63e36a`；latency summary `05083d8cdd0d8476f85eb97e27ba3df06b340e88091a30198f41253f191c1735`；artifact SHA清单 `1aed1f7640b40a6d6e57128d81159451200845d369f82d3c8ac838e65c053651`，size清单 `ebb474020caa0e2828ca66b3291dbe9a580091e6594aa87c491065d0611f8ca2`。
- 未测范围：其余五产品线、撤单/改单/批量/触发/风险扫描/强平/资金费/ADL/保险基金/交割结算的独立性能分布、API requests/s与连接、Aeron Cluster复制、Kafka、外部exporter/history projection、数据库、WebSocket、有效长稳；任何其他in-flight档位按约束均未测试。
- 最终结论：七项代码修改、文档和功能回归均已完成，固定256场景的分配、GC、三段延迟、计数闭环和短时锁/I/O门禁通过；但无profiler持续吞吐仅 `9030.323 terminal business ops/s`，预设吞吐与环境有效性失败，且缺少长稳泄漏证据。因此本轮必须标记“部分验证/性能验收未通过”，不能宣称七项已经被端到端性能证据证明为彻底解决。当前最明确的后续瓶颈是exchange-core/Disruptor线程的CPU占用，以及owner内剩余的primitive/HashMap扩容、UserHash stage、列表/stream和编码buffer物化。

### 2026-09-01 17:58:14 +08:00 — `PV-20260901-256-10` — `采集前锁定（统一 Account Lane mutation/barrier/commit）`

- 记录创建时间：`2026-09-01 17:58:14 +08:00`（`2026-09-01T09:58:14Z`）。被测生产 commit `db7b161cdb7e8a6905f4c3dfc68e14d660e6f488`，分支 `codex/aeron-unified-core`，已推送；对照 commit 为直接父提交 `3711a1485ee6e7e16aa17fd0a9c4dd8d748c2dc8`，仅用于界定代码修改范围，不检索、不重跑、不引用旧性能结果作数值对照。本轮只按以下预先锁定的绝对标准判断。
- 修改点：账户、风险、生命周期和撮合成交统一进入一个 Lane mutation executor；单 Lane/短操作内联，多 Lane 只改变调度并在一次统一 barrier 等待，删除 perpetual journal/replay/异步 continuation 第二路径。worker 直接修改各自 Lane 权威状态，owner 在全部目标 Lane 成功后一次合并 Treasury delta、资金守恒检查和 Core Fact 发布；任一 Lane 失败整命令按 checkpoint 回滚。Lane commit 只保留 `laneMask`，移除 per-Lane commit/revision/hash/owner-group-offset 对象；commit/projection/hash/snapshot 使用同一连续 sequence。patch changes 使用 generation-stamped open-addressed scratch，FundsDelta 用数组排序合并，business/funds rolling hash 删除重复 owner-domain 镜像，Lane 每命令只推进一次。
- 功能门禁：Oracle GraalVM Java HotSpot `25.0.1+8.1`、Maven `3.9.16`；`mvn -pl :surprising-aeron-service,:surprising-aeron-benchmarks -am test` 已通过 product API 12、protocol 80、instrument API 13、service 506、benchmarks 16，共 `627/627`，0 failure/error/skipped。CodeGraph 识别的13个受影响测试文件全部执行；覆盖多 maker/多 fill/跨 Lane 统一 barrier、任一 Lane 失败回滚、资金/持仓、commit/hash、Core Fact、六产品线 snapshot 与恢复。未启动 Docker、wallet 或外围服务。
- in-flight 与范围：所有性能采集严格且仅使用 `256 in-flight`，不运行、补跑、推算或横向比较 `64/512/1024` 等其他档位。只测 `LINEAR_PERPETUAL` Product Core；4 Account Lane、1 exchange-core matching engine、0 exchange-core risk engine、1 Product Core risk engine、1 JMH worker/进程内连接。其余五产品线只保留已通过的功能/快照证据，不形成性能结论。
- 固定业务场景：`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`；10,000 活跃用户、512 listed/active symbol，每 invocation 16,384 个 `PLACE_ORDER` business operations，50% maker GTC + 50% taker IOC，同 symbol/价格/数量配对成交。maker 做市状态持续运行；成交双方按 userId 路由，跨 Lane 时走本轮统一并行 mutation/barrier。每用户最多5持仓/10未成交单；初态由固定 snapshot template 恢复，用户与做市账户资金充足；终态检查用户/做市余额、冻结、持仓、活动订单、business/funds hash、资金守恒和 snapshot recovery。
- 负载模型：open-loop constant-arrival offered rate `100,000 business operations/s`，计划到达时间计入 entry latency并修正 coordinated omission；ACK interval 1,024，histogram `1 ns–30 s`，progress timeout 30秒，最大在途固定256。JMH进程内包含 owner、exchange-core matcher、Product Core账户/风险、runtime projection、Core Fact materializer和快照恢复；API、Aeron Cluster复制、Kafka、外部 exporter/history projection、数据库、WebSocket、Docker、wallet均不启动。
- 正确性/容量门禁：`acceptedBusinessOperations == terminalBusinessOperations`、accepted Core messages=terminal Core messages、两个 `unfinished*=0`、期末 backlog=0、最大 backlog `<=256`、producer starvation=0、拒绝/错误/超时=0；资金/余额/冻结/持仓/订单终态/hash/snapshot recovery全部通过。必须报告 terminal Core messages/s、fills/trades（若当前场景仍未独立输出则明确为证据缺口）、batches/items/平均及最大batch size、completion mailbox、最大/平均/期末backlog。
- 吞吐门禁：无 profiler 主轮 `terminal business ops/s >=10,000`，99.9%置信区间下界 `>=9,000`，3个fork均值分别 `>=9,000`；同时保存15个measurement值、误差和区间。带 profiler轮只作归因，不能替代主吞吐。
- 延迟门禁：PLACE_ORDER分别报告 entry→accepted、accepted→terminal、entry→terminal 的p50/p90/p95/p99/p99.9/max、样本数、单位、直方图区间和timeout。accepted→terminal p99/p99.9/max `<=35/50/150 ms`；entry→terminal p99/p99.9/max `<=1.70/1.75/2.10 s`。
- GC/分配门禁：独立 `-prof gc` 报告allocation rate、B/JMH invocation和B/terminal business op，要求 `<=51,200 B/business op`；ZGC allocation stall/OOM、allocation requiring GC、promotion/evacuation failure=0，JFR GC pause max `<=1 ms`。报告TLAB/非TLAB、最大对象、top allocation class/thread/site、heap committed/used、GC前后与post-GC趋势。
- JFR/NMT门禁：保存原始`.jfr`、`jfr summary`、JDK views、GC/safepoint log、NMT baseline/summary diff；按owner/driver、Lane worker、exchange-core/Disruptor、risk、projection、Core Fact、snapshot、Aeron/外围线程报告CPU/execution/墙钟热点。报告native reserved/committed、Direct/Mapped/pool、线程峰值与RUNNABLE/BLOCKED/WAITING/PARKED、锁/park、safepoint/VM operation、JIT/deopt/code cache/class/metaspace、I/O和异常；要求`DataLoss=0`、container throttling=0、owner measurement同步业务file/socket/database I/O=0。
- 长稳/泄漏：只有主吞吐、正确性、尾延迟、分配和环境有效性全部通过，才运行同一 `256 in-flight` 场景30秒预热+10分钟measurement，每10秒采样NMT/RSS/线程/FD/swap；至少3个真实post-GC点，live-set/old-generation斜率各 `<=1 MiB/s`，Direct/Mapped各 `<=256 KiB/s`，线程/FD/buffer count各 `<=0.01/s`，无Core Fact/materializer、mark freshness或业务异常。任一前置门禁失败则不运行并明确记录，短JFR不能证明无泄漏。
- 固定环境：Intel Core i9-9880H 8C/16T、16 GiB、macOS 26.7 / Darwin 25.6.0 x86_64，非容器、未绑核。锁定时swap used `341.00 MiB`、Pages throttled=0、Pageins/Pageouts=`8,274,313/163,738`、Swapins/Swapouts=`581,320/732,044`；要求采样窗口swap增量不超过64 MiB且无持续page-in/page-out或明显内存压力。锁定时Terminal约10.4% CPU、Codex约5.7%、WindowServer约4.8%，不擅自终止用户进程；若持续同机干扰、额外benchmark JVM、thermal/throttling或JFR DataLoss则环境结论无效。
- JVM/JMH：`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`，开放`jdk.internal.misc/ref`；Account Lane=4、matcher=1、matcher `BUSY_SPIN`、settlement `BLOCKING`、completion spins=16,384、projection `PARKING`/batch64/4MiB、journal65,536/1GiB、export pending256MiB。主轮 `5x5s warmup + 5x5s measurement + 3 forks + 1 thread`，冷却30秒；归因轮 `5x5s warmup + 3x5s measurement + 1 fork + 1 thread -prof gc`。
- JFR：配置 `surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc`，锁定SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。仅归因fork JVM启用`-XX:NativeMemoryTracking=summary`、JFR和GC/safepoint日志；从fork启动开始记录，业务分析窗口为最后3个measurement invocation，仍保留启动、预热与JIT证据。
- 执行命令口径：先 `mvn -pl :surprising-aeron-benchmarks -am -DskipTests package`；随后对同一个`product-core-benchmarks.jar`运行上述场景，显式传入全部参数和`-p maxInFlight=256`。主轮输出JSON；冷却后归因轮使用`-prof gc`、JFR、NMT和GC日志。完整命令、stdout/stderr、JDK/Maven/git/dependency/JAR/JFC SHA、系统/进程前后快照全部进入artifact。
- artifact根目录锁定为 `target/qualification/20260901T095814Z-unified-lane-256/`；所有成功、失败、中止、无效轮次、全部指标、原始artifact路径与校验信息、问题、未测范围和结论只按时间追加到本文件，不覆盖历史记录。

### 2026-09-01 18:08:53 +08:00 — `PV-20260901-256-10` — `主吞吐通过，按指令停止 GC/JFR 分析（部分验证）`

- 实际执行：被测生产 commit `db7b161cdb7e8a6905f4c3dfc68e14d660e6f488`，采集锁定 commit `ae6831fa`；package成功，shaded JAR SHA-256 `3f3abc139c77f42e49550dda27d89ed5c5af4e66def3cd268e230c765a9bef73`。无profiler主轮总时长 `4m11s`；随后按锁定参数启动的GC/JFR轮自然结束，总时长 `1m14s`。全部运行严格为 `256 in-flight`、4 Account Lane、1 matching engine、LINEAR_PERPETUAL；未启动Docker、wallet、外围exporter/history projection、数据库或其他产品线。
- 功能与多 Lane 正确性：受影响reactor `627/627`，0 failure/error/skipped；其中多maker/多fill测试验证同一成交结果涉及多个用户、跨多个Lane时，所有Lane通过同一mutation入口完成，owner只等待一次统一barrier后发布；失败路径验证整命令回滚。JMH主轮和归因轮均正常teardown，accepted business=terminal business、accepted Core=terminal Core、两个unfinished=0，资金、余额/冻结/持仓、活动订单、business/funds hash和snapshot recovery未抛出异常。
- 主吞吐：无profiler `11,372.933 ± 481.859 terminal business ops/s`，99.9% CI `[10,891.074, 11,854.791]`，通过 `>=10,000` 和CI下界 `>=9,000` 门禁；`terminal Core messages/s=11,384.039 ± 482.329`，CI `[10,901.710, 11,866.368]`。三个fork均值分别为 `10,967.190 / 11,558.022 / 11,593.586 terminal business ops/s`，均通过每fork `>=9,000`；15个正式measurement范围 `10,516.889–11,885.011 ops/s`。本轮最高单次为 `11,885.011 terminal business ops/s`，不以该峰值替代聚合结论。
- 计数闭环：主轮accepted business=`11,372.933 ops/s`=terminal business，accepted Core=`11,384.039 messages/s`=terminal Core，两个unfinished=`0`、producer starvation=`0`。场景teardown保证期末backlog清零并完成资金/状态/snapshot校验；但batches/items、平均/最大batch、fills/trades、拒绝/错误/超时独立计数、最大/平均backlog和completion mailbox需要读取JFR workload events，本轮依照用户“GC和JFR不要分析”的指令未解析，均记为证据缺口而不推算。
- GC/JFR处理：归因轮原始JMH仍保持accepted=terminal、unfinished=0，原始`.jfr`、GC log、NMT退出输出和`-prof gc` JSON已保存；按用户指令未运行JFR analyzer、`jfr summary`、`jfr view`或任何CPU、分配、GC、heap/native、线程/锁、safepoint、JIT、I/O、异常和三段延迟归因。因此不引用GC/分配数值，不判断对应门禁，也不声称短记录证明无泄漏。
- 系统有效性：swap used在主轮前后和归因轮前后均为 `341.00 MiB`，Pages throttled始终0；主轮Pageins/Pageouts增量 `9,362/833`页、Swapins/Swapouts增量 `128/0`，归因轮分别 `16,385/7,088`页、`381/0`。没有swap占用增长或swapout，但端点数据不能证明页面活动是否持续，且未做JFR/系统时间序列归因，环境有效性只标记为部分证据。
- 延迟与长稳：三段业务延迟直方图位于原始JFR workload events中，依指令未分析，延迟门禁未判定。由于GC/JFR、延迟和环境门禁未完成，且用户明确要求停止分析，本轮不执行10分钟长稳；live-set/old/native/Direct/Mapped/线程/FD/buffer增长斜率无证据，不能声明无泄漏。
- artifact：`target/qualification/20260901T095814Z-unified-lane-256/`，39个文件、约57 MiB。`main-256.json` 26,535 B、SHA-256 `5184028e6de718f06154794d883bce8e7ebbfadd517ecd2725993afbcda20c4b`；`attribution-256.json` 22,573 B、SHA-256 `8d3301be4b32b1f08192eb8b857fc1bee6aaefa0f3294560c541d6913545c6e6`；原始JFR 57,675,828 B、SHA-256 `4e8c8ed9092070652208a545a0f9e4945cacab2e6b9656541337055c11da2dbe`；GC log 117,204 B、SHA-256 `a3afb0a3d2469547986c521a483e5d3f8163feff74d296bf7f2eedac79d9565b`；SHA清单SHA-256 `f7a6394b6ee49c1ca95be5e7579d38f23d36c95bce8ccb600143e396fe399940`，size清单SHA-256 `af569068d65a6bf8d8e7ead3e42b5812fe85fe5b30394f6e370df7f82167fe4c`。
- 未测范围与结论：未分析GC/JFR、三段延迟、fills/trades和完整资源指标，未执行长稳；未测其余五产品线性能、撤单/改单/批量/触发/风险扫描/强平/资金费/ADL/保险基金/交割结算独立分布、API requests/s与连接、Aeron Cluster复制、Kafka、外部projection、数据库和WebSocket。最新代码在固定256场景的功能闭环与主吞吐绝对门禁通过，聚合为 `11,372.933 terminal business ops/s`；但按证据完整性只能标记“部分验证”，不能宣称完整性能验收或无泄漏通过。

### 2026-09-01 20:26:55 +08:00 — `PV-20260901-256-11` — `采集前锁定（Lane handoff 与 owner commit 简化）`

- 记录创建时间：`2026-09-01 20:26:55 +08:00`（`2026-09-01T12:26:55Z`）。被测生产 commit `948cbcde195e6ff2cbe5e19723fab602b717e0df`，分支 `codex/aeron-unified-core`，已推送；对照 commit 为直接父提交 `44727947b14c59b83b3f5c79865d6b7028e1a005`，仅用于界定本轮代码影响面。本轮不检索、不重跑、不引用旧性能数据作数值对照，只按以下绝对标准判断。
- 修改点：①多 Lane mutation 每个 task 只 await 一次，统一完成 barrier、错误聚合、owner 重绑定与发布；② Account Lane owner handoff 改为 O(1)，删除余额 Map 遍历和每个 `BalanceRuntime` 的线程 owner 状态；③ `RuntimeCommitPatch` 内部 core/projection 四个序列字段收敛为单一连续 sequence pair，兼容 getter 只作同值别名；④删除派生 `ownerGroups` 列表与 owner-order 接口，消费者直接读取 Lane groups 和 global group；⑤将 prepare/seal/hash/index/Core Fact/journal publish/rollback/finish 封装为单个 `OwnerCommitTransaction`。对应 owner-commit JMH 场景已迁移并校验 core/projection 序列同源。
- 功能门禁及已完成证据：Oracle GraalVM Java HotSpot `25.0.1+8.1`、Maven `3.9.16`；`mvn -pl :surprising-aeron-benchmarks -am test` 已通过 product API 12、protocol 80、instrument API 13、service 507、benchmarks 16，共 `628/628`，0 failure/error/skipped。覆盖多 Lane mutation/失败回滚、跨线程权威余额隔离、资金与持仓、连续 hash、commit journal/Core Fact、六产品线 snapshot 与恢复；`git diff --check` 通过。未启动 Docker、wallet 或外围服务。
- in-flight 与范围：所有性能采集严格且仅使用 `256 in-flight`；禁止运行、补跑、推算或横向比较 `64/512/1024` 等其他档位。只测 `LINEAR_PERPETUAL` Product Core；4 Account Lane、1 exchange-core matching engine、0 exchange-core risk engine、1 Product Core risk engine、1 JMH worker/进程内连接。其余五产品线只有功能/快照证据，不形成性能结论。
- 固定业务场景：`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`；10,000 活跃用户、512 listed/active symbol，每 invocation 16,384 个 `PLACE_ORDER` terminal business operations，50% maker GTC + 50% taker IOC，同 symbol/价格/数量配对成交；做市订单状态持续存在。成交双方按 userId 路由，跨 Lane 时由统一 mutation/barrier 完成。每用户最多5持仓、10未成交单；从固定 snapshot template 建立资金充足的用户/做市账户，teardown 检查余额、冻结、持仓、活动订单、business/funds hash、资金守恒和 snapshot recovery。
- 固定负载：open-loop constant-arrival offered rate `100,000 business operations/s`，计划到达时间计入 entry latency并修正 coordinated omission；export ACK interval 1,024，histogram `1 ns–30 s`、progress timeout 30秒，最大在途固定256。JMH进程内包含 owner、单 matcher、Product Core账户/风险、runtime projection、Core Fact materializer和快照恢复；API、Aeron Cluster复制、Kafka、外部 exporter/history projection、数据库、WebSocket、Docker、wallet均不启动。
- 正确性/计数门禁：`acceptedBusinessOperations == terminalBusinessOperations`、accepted Core messages=terminal Core messages、两个 `unfinished*=0`、期末 backlog=0、最大 backlog `<=256`、producer starvation=0、拒绝/错误/超时=0，且上述资金、余额/冻结、持仓、订单、hash和snapshot检查全部通过。必须报告主轮可直接读取的 terminal business ops/s、terminal Core messages/s、accepted/terminal、unfinished 和 producer starvation；fills/trades、batch、三段延迟、backlog细分若当前无 profiler 主轮不输出，必须标记证据缺口，禁止推算。
- 吞吐门禁：无 profiler 主轮 `terminal business ops/s >=10,000`，99.9%置信区间下界 `>=9,000`，3个fork均值分别 `>=9,000`；保存15个measurement值、主分数、误差和置信区间。单次最高 iteration 只报告，不作为结论；不运行对照 commit。
- 数据有效性：3个fork全部正常完成；每个fork warmup后进入稳定measurement且无功能断言、超时、额外benchmark JVM、thermal/container throttling、明显同机CPU争用或持续page-in/page-out；swap占用增量不超过64 MiB、Pages throttled为0。任一条件失败则如实标记诊断/部分验证，不通过容量验收。
- 固定机器与运行时：Intel Core i9-9880H 8C/16T、16 GiB、macOS 26.7 / Darwin 25.6.0 x86_64，非容器、未绑核。锁定时 swap used `240.25 MiB`、Pages throttled=0；主要同机进程 Terminal约14.5%、WindowServer约8.0%、Codex约7.6%，不终止用户进程。JVM参数：`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`，开放 `jdk.internal.misc/ref`；Lane=4、matcher=1、matcher `BUSY_SPIN`、settlement `BLOCKING`、completion spins=16,384、projection `PARKING`/batch64/4MiB、journal65,536/1GiB、export pending256MiB。
- JMH 参数：`5x5s warmup + 5x5s measurement + 3 forks + 1 thread`，每 iteration timeout 10分钟，主轮结束后冷却30秒；JMH 1.37，输出 JSON 和完整日志。先执行 `mvn -pl :surprising-aeron-benchmarks -am -DskipTests package`，再对同一个 `product-core-benchmarks.jar` 执行上述 benchmark，显式传入全部场景参数和 `-p maxInFlight=256`。
- GC/JFR、延迟与长稳：按用户明确指令，本轮不启动 `-prof gc`、JFR、NMT归因或长稳，不分析CPU/分配/heap/native/锁/safepoint/JIT/I/O及三段延迟。因此无论主吞吐是否通过，本轮最高只能判为“主吞吐与功能通过/部分验证”，不能宣称完整性能验收或无泄漏；这是预先锁定的证据边界，不在采集中途修改。
- artifact 根目录锁定为 `target/qualification/20260901T122655Z-lane-commit-simplification-256/`；package/JAR SHA、命令、Java/Maven/git/系统前后快照、完整 JMH JSON/log、SHA-256与size清单全部保存。所有成功、失败、中止、无效结果只追加到本文件，不覆盖历史记录。

### 2026-09-01 20:34:21 +08:00 — `PV-20260901-256-11` — `主吞吐与功能通过，按指令为部分验证`

- 实际执行：被测生产 commit `948cbcde195e6ff2cbe5e19723fab602b717e0df`，采集锁定 commit `5cca5526`，均已推送。Oracle GraalVM Java HotSpot `25.0.1+8.1`、JMH 1.37；package 成功，shaded JAR `63,947,129 B`、SHA-256 `272a6c2324ba1a9803ee2daa365bd60e6adf73445f3acdfa89ad238897a2822c`。本轮只运行 `LINEAR_PERPETUAL`、4 Account Lane、1 matching engine、BUSY_SPIN、固定 `256 in-flight`；未启动 Docker、wallet 或外围服务，未运行任何其他 in-flight。
- 执行命令：构建为 `mvn -pl :surprising-aeron-benchmarks -am -DskipTests package`；主轮为 `java -jar product-core-benchmarks.jar com.surprising.aeron.service.LinearPerpetualCoreBenchmark.saturatedMatchingWorkload -bm thrpt -tu s -wi 5 -w 5s -i 5 -r 5s -f 3 -t 1 -to 10m -rf json -p accountLanes=4 -p activeUsers=10000 -p listedSymbols=512 -p activeSymbols=512 -p maxPositionsPerUser=5 -p maxOpenOrdersPerUser=10 -p maxInFlight=256 -p operationsPerInvocation=16384 -p targetOperationsPerSecond=100000 -p matcherWaitStrategy=BUSY_SPIN`，并使用锁定记录中的完整 JVM/system properties；结束后冷却30秒。
- 功能与资金正确性：本轮采集前受影响 Reactor `628/628`，0 failure/error/skipped。3个 JMH fork 均正常 setup/teardown且无业务断言或超时；teardown 完成余额/冻结、持仓、活动订单、business/funds hash、资金守恒和 snapshot recovery 检查。跨 Lane、失败回滚、commit/hash/Core Fact及六产品线共享快照的功能测试均已通过。
- 主吞吐：`11,655.557 ± 439.925 terminal business ops/s`，99.9% CI `[11,215.631, 12,095.482]`，通过 `>=10,000` 与 CI 下界 `>=9,000` 门禁。三个 fork 均值依次为 `11,728.281 / 11,644.186 / 11,594.203 terminal business ops/s`，全部通过每 fork `>=9,000`。15个正式 measurement 分别为 `[11894.986,11663.295,12023.824,11946.622,11112.678] / [11985.444,11239.996,11976.895,11795.790,11222.806] / [11737.108,11982.232,11800.546,11818.180,10632.947]`，范围 `10,632.947–12,023.824`；最高值只作单次观测，不替代聚合结论。
- Core与计数闭环：`acceptedBusinessOperations=terminalBusinessOperations=11,655.557 ops/s`；`acceptedCoreMessages=terminalCoreMessages=11,666.939 ±440.355 messages/s`，99.9% CI `[11,226.584,12,107.294]`；`unfinishedBusinessOperations=0`、`unfinishedCoreMessages=0`、`matchingProducerStarvationSamples=0`。JMH invocation 主分数为 `0.711 ±0.027 invocations/s`，CI `[0.685,0.738]`。
- 其余主轮指标：`matchingRefillOperations=11,632.792 ±439.066 ops/s`、`matchingWindowSamples=182.118 ±6.874 samples/s`、`matchingFullWindowSamples=147.971 ±5.585 samples/s`；lane command/settlement/risk/query/总 operations 的辅助计数均约0，因为该 workload 的 Lane 工作在 owner commit 内部计入 terminal business operations，而未作为独立业务类型计数。期末 backlog 由正常 teardown 清零；最大/平均 backlog、completion mailbox、fills/trades、batch size、拒绝/错误/超时独立计数没有在无 profiler 主轮输出，均保留为证据缺口，不作推算。
- 环境有效性：主轮前后 swap used 均为 `240.25 MiB`，Swapouts增量0、Pages throttled始终0；Pageins/Pageouts增量 `12,422/310` 页，Swapins增量 `2,107` 页。未发现额外 benchmark JVM，端点差值没有显示swap占用增长或throttling；但没有时间序列或JFR，不能严格证明页面活动是否持续。工作站仍有Terminal、WindowServer、Codex、WeChat等同机进程，因此数字只代表本机当前固定场景，不是生产隔离容量认证。
- artifact：`target/qualification/20260901T122655Z-lane-commit-simplification-256/`，24个文件、约368 KiB。`main-256.json` 26,508 B、SHA-256 `76c6858f14a3eca99eb07ba88852ab474e7a4eee8d1f25ebea4ed98ee563e07a`；`main-256.log` 33,211 B、SHA-256 `d5d07d422ece9c12a4df04c560c20712c5a256cfd3326050f9416a108e63b69f`；`maven-package.log` 26,542 B、SHA-256 `4d1788e9956f729f65accd3423bf002174abdb11c83c6f2b7004386106a0c1ed`；artifact SHA清单 SHA-256 `8c233c0219ed96520e08eaf9fd559bef9358a48cb8920a9d4c101abf4eead5aa`，size清单 SHA-256 `76f1c4c60989d90b881df3bfadca6a63b6fa391bb3646dd08055fb6d55c8bb21`。
- 未测范围与结论：依用户指令未运行或分析GC/JFR/NMT、三段延迟和长稳，因而没有分配、GC、heap/native、CPU热点、锁/safepoint/JIT/I/O、尾延迟与泄漏证据；未测其余五产品线性能、撤单/改单/批量/触发/风险扫描/强平/资金费/ADL/保险基金/交割结算独立分布、API requests/s、Aeron Cluster复制、Kafka、外部projection、数据库和WebSocket。本轮五项简化的功能回归通过，固定256场景的主吞吐、CI下界、三个fork和计数闭环门禁全部通过；正式聚合结果为 `11,655.557 terminal business ops/s`，但按预锁定证据边界只能标记“主吞吐与功能通过/部分验证”，不能宣称完整性能验收或无泄漏通过。

### 2026-09-01 22:15:36 +08:00 — `PV-20260901-256-12` — `采集前锁定（同步 Owner 单链路）`

- 记录创建时间：`2026-09-01 22:15:36 +08:00`（`2026-09-01T14:15:36Z`）。被测 git commit `5f901bca54ddcfc51e78e861a870a7d11eec52fc`，分支 `codex/aeron-unified-core`，已推送；exchange-core fork 为已推送 commit `4636c44b19de90be0bd6c85afdd0e4fa190da9f0`、JAR SHA-256 `4a6e41ae66822eddf8539fa8bb80fe77ffc3cc4adc7376d6666b45cf24ee874e`。对照 commit `fa5edb846d3a66047c8fb6b3cc161d3b32ebe64b` 只用于代码影响审计；按用户要求不检索、重跑或数值比较旧性能数据。
- 修改点：① Aeron 依赖升级并固定为 `1.53.0`；② fork 新增单线程 `SynchronousMatchingEngine`，Product Core 直接调用，matching engine 代码级固定为1、exchange-core risk engine为0；③普通单、批量单、前置撤单与改单热路径删除已完成 Future/thenCompose/回调包装；④删除 Disruptor/ExchangeApi 提交链、completion queue/ring和等待策略；⑤ Account Lane 变为 owner 内逻辑隔离，删除 Lane worker、跨线程ACK/barrier和两套结算路径；⑥删除 Cluster timer continuation和全局 deferred head-of-line；⑦ commit journal只保留准入、连续sequence、rolling hash及诊断元数据，删除热projection replica/projector等待；⑧ query/snapshot fence直接从权威runtime物化，删除snapshot encoder/audit executor；⑨按命令checkpoint回滚全局及Lane mutable domain，Treasury按changed value回滚；⑩ Aeron publication明确处理backpressure/fatal状态，并调整term buffer、sparse与threading mode。
- 功能门禁：Oracle GraalVM Java HotSpot `25.0.1+8.1`、Maven `3.9.16` 下执行 `mvn -q -pl surprising-aeron-core/surprising-aeron-benchmarks -am test`，受影响 reactor 全部通过；另有 exchange-core `SynchronousMatchingEngineTest` 通过。覆盖普通/批量下单、撤单、改单、多fill跨Lane资金与持仓、失败回滚、幂等、business/funds hash、Core Fact、六产品线共享snapshot及恢复。`git diff --check`通过；未启动Docker、wallet或外围服务。
- in-flight：本轮性能采集严格且仅使用 `256`；禁止运行、补跑、推算或横向比较 `64/512/1024` 等任何其他档位。matching engine严格为1，不运行等待策略A/B。
- 固定场景：仅 `LINEAR_PERPETUAL` 的 `LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`；10,000活跃用户、512 listed/active symbol、4个逻辑Account Lane、1同步matching engine、0 exchange-core risk engine、1 Product Core owner/JMH线程，每用户最多5持仓和10未成交单，每invocation 16,384个PLACE_ORDER business operations，50% maker GTC与50% taker IOC，同symbol/价格/数量配对；做市状态持续存在。API、Aeron Cluster复制、Kafka、外部exporter/history projection、PostgreSQL、WebSocket、Docker和wallet均不启动。
- 负载与计数：open-loop constant-arrival offered rate `100,000 business operations/s`，计划到达时间计入entry latency并修正coordinated omission；driver上限固定256。同步owner在一次回调中完成accepted→matcher→Lane settlement→terminal；单transaction瞬时matcher backlog上限1、期末0，不存在completion mailbox/batch。export ACK interval 1,024；histogram `1 ns–30 s`，progress timeout 30秒。
- 正确性标准：`acceptedBusinessOperations == terminalBusinessOperations`、accepted Core messages=terminal Core messages、两个`unfinished*=0`、期末backlog=0、最大matcher backlog `<=1`、producer starvation=0、拒绝/错误/超时=0；teardown必须核对用户/做市账户余额、冻结、持仓、活动订单、资金守恒、business/funds hash及snapshot restore。fills/trades或逐账户流水若场景未独立输出，必须列为证据缺口，禁止推算。
- 吞吐门禁：无profiler结果 `terminal business ops/s >=10,000`，三个正式measurement各 `>=9,000`；同时报告主分数、误差/区间、terminal Core messages/s、accepted/terminal、unfinished、refill/window/starvation和全部JMH secondary metrics。单次峰值不替代聚合结论。
- 延迟门禁：PLACE_ORDER分别报告entry→accepted、accepted→terminal、entry→terminal的p50/p90/p95/p99/p99.9/max、样本数、单位、直方图区间与timeout；accepted→terminal p99/p99.9/max `<=15/25/100 ms`，entry→terminal p99/p99.9/max `<=1.70/1.75/2.10 s`。
- GC/JFR门禁：主轮无profiler；独立归因轮使用同一场景、同一256档和`-prof gc`，保存原始JFR、GC/safepoint log、NMT baseline/diff、`jfr summary`和可用views。报告allocation rate、B/invocation与B/business op（门禁 `<=51,200 B/op`）、GC次数/时间/暂停、heap/native/Direct/Mapped、CPU热点与线程角色、锁/park、safepoint/VM operation、JIT/deopt/code cache、I/O/异常；要求JFR DataLoss=0、ZGC stall/OOM=0、GC pause max `<=1 ms`、owner measurement同步file/socket/database I/O=0。短JFR只作归因，不能证明无泄漏。
- 预热/测量/冷却：主轮1次3秒warmup、3次各5秒measurement、1 fork、1 thread；主轮结束后30秒冷却。归因轮1次3秒warmup、1次10秒measurement、fork=0、1 thread并启用`-prof gc`、JFR/NMT/GC日志。JFR配置 `surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc`，采集前SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。
- JVM/机器：Intel Core i9-9880H 8C/16T、16 GiB、macOS 26.7 / Darwin 25.6.0 x86_64，非容器、未绑核；Oracle GraalVM HotSpot 25.0.1，ZGC，`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`及`jdk.internal.misc/ref`开放。锁定时swap used `208.25 MiB`、Pages throttled=0；Terminal约15%、Codex约11.5%、WindowServer约4%。不终止用户进程；swap增长、持续page-in/page-out、额外benchmark JVM、thermal/throttling或明显同机争用会使容量结论降级为诊断。
- 数据有效性：源码、参数、JDK/JVM、JFC和场景从本条锁定后不得改变；JMH正常teardown、artifact完整、无DataLoss、无积压/unfinished/业务断言、系统无明显干扰才可通过。任一失败或脚本/analyzer错误均如实追加。长稳本轮不执行，因而无论短轮结果如何均不得声明无heap/native/FD/线程/buffer泄漏。
- 执行命令口径：先执行 `QUALIFICATION_RUN_ID=20260901T141536Z-sync-owner-256 SURPRISING_JAVA_HOME=/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh saturation`，脚本只运行 `-p maxInFlight=256`并生成无profiler主轮及JFR/NMT归因轮；随后对同一个shaded JAR、同一场景和全部相同参数单独运行一次 `-prof gc`（1×3秒warmup、1×10秒measurement、1 fork），写入同一artifact目录。artifact根目录锁定为 `target/qualification/20260901T141536Z-sync-owner-256-scale/`；完整命令、版本、git/JAR/JFC SHA、系统快照、JMH JSON/log、JFR分析及最终size/SHA清单均追加记录。

### 2026-09-01 22:32:00 +08:00 — `PV-20260901-256-12` — `吞吐/尾延迟失败，部分归因完成`

- 实际范围：被测生产 commit `5f901bca54ddcfc51e78e861a870a7d11eec52fc`，采集锁定 commit `86389bfb`，exchange-core fork commit `4636c44b19de90be0bd6c85afdd0e4fa190da9f0`；Oracle GraalVM Java HotSpot 25.0.1、JMH 1.37、Aeron 1.53.0。只运行 `LINEAR_PERPETUAL`、4个逻辑Account Lane、1个同步matching engine、固定且仅固定`256 in-flight`；未启动Docker、wallet、API、Aeron Cluster、Kafka、数据库或外围服务，也未运行其他in-flight档位。
- 执行与功能证据：`mvn -q -pl surprising-aeron-core/surprising-aeron-benchmarks -am test`在采集前完成且退出码0；exchange-core `SynchronousMatchingEngineTest`已通过。性能脚本成功构建同一shaded JAR，主轮和JFR轮均正常完成JMH teardown；accepted business=terminal business、accepted Core=terminal Core、两个unfinished为0、matching producer starvation为0，最大matcher backlog为1、期末为0，未出现资金、余额/冻结、持仓、订单终态、business/funds hash或snapshot restore断言。fills/trades、拒绝/错误/超时与逐账户资金流水没有独立计数，保留为证据缺口。
- 主吞吐：3个正式measurement依次为`7,387.018 / 8,802.723 / 9,839.367 terminal business ops/s`，聚合`8,676.369 ±22,458.893 ops/s`（99.9%区间因仅3个样本而极宽且含负下界）；`terminal Core messages/s=8,684.842`。accepted与terminal完全相等，但聚合低于`10,000`，前两个measurement低于`9,000`，因此吞吐门禁失败；最高单点`9,839.367`只作观测，不能替代聚合结论。measurement逐次上升且JFR窗口仍发生8,805次编译，最长四级编译823 ms，说明锁定的3秒预热不足以使新同步链路达到稳定编译状态；本轮容量数值同时降级为诊断，不能解释为稳定上限。
- 延迟：JFR归因窗口记录8个PLACE_ORDER invocation、共`131,072`样本，open-loop constant-arrival、coordinated-omission corrected、直方图`1 ns–30 s`、timeout 30秒。合并log2直方图的entry→accepted p50/p90/p95/p99/p99.9/max为`1,073.742 / 2,147.484 / 2,147.484 / 2,147.484 / 4,294.967（桶上界）/ 2,244.243 ms`；accepted→terminal为`0.131 / 0.131 / 0.131 / 0.262 / 2.097 / 13.626 ms`；entry→terminal为`1,073.742 / 2,147.484 / 2,147.484 / 2,147.484 / 4,294.967（桶上界）/ 2,253.304 ms`。accepted→terminal门禁通过，entry→terminal p99/p99.9/max均失败；p99.9的桶上界大于精确max是log2直方图量化结果，不代表存在4.295秒样本。
- GC/分配：独立`-prof gc`归因轮为`7,486.779 terminal business ops/s`、`346.197 MB/s`、`824,699,732.8 B/JMH invocation`；每invocation固定16,384条，折算`50,335.677 B/terminal business op`，通过`51,200 B/op`门禁但仅余`864.323 B/op`余量。该轮4次GC、JMH累计GC time 692 ms。独立JFR轮为5次ZGC、23个pause phase，单次GC最长pause依次`0.0109/0.0276/0.0111/0.0152/0.0290 ms`，max 0.0290 ms，通过1 ms门禁；`AllocationRequiringGC=0`、`ZAllocationStall=0`、DataLoss=0。短JFR不能证明无泄漏。
- CPU与分配归因：JFR共1,391个execution samples。hot methods为`LongObjectHashMap.forEachKeyValue 4.89%`、`TreeMap.getEntry 4.24%`、`CoreStateHash.mix 4.03%`、`Arrays.fill(long[]) 3.67%`、`Arrays.fill(Object[]) 3.09%`、`LongObjectHashMap.getIfAbsent 2.73%`、`TreeMap.put 2.52%`；说明同步撮合/结算已无mailbox或Lane barrier背压，剩余主要成本仍是owner状态扫描、树结构、hash、数组清理与patch物化。allocation top sites为`LongObjectHashMap.addKeyValueAtIndex 4.33%`、`RollingBusinessStateHash.UserHash.<init> 3.48%`、`TreeMap.put 3.19%`、`ByteBuffer.allocate 2.36%`、immutable-list iterator 2.34%、primitive-map table分配1.87%、`HashMap.putVal 1.83%`、prepared symbol 1.79%、`RuntimeCommitPatch.Changes.seal 1.33%`。事件计数为ObjectAllocationSample 50,451、new-TLAB 49,324、outside-TLAB 3,745、ThreadAllocationStatistics 576。
- 线程/锁/JIT：JFR线程峰值13，业务JMH owner平均user/system CPU约`5.92%/0.10%`，Core Fact materializer约`0.39%/0.11%`；机器总CPU平均16.99%、最高26.91%。JavaMonitorEnter仅8次，按线程最大观测等待owner 0.368 ms、materializer 0.304 ms；ThreadPark 228,531次主要来自空闲/外围park，未观察matching busy-spin线程。JFR记录510次deoptimization、8,805次compilation、code committed最大37.5 MiB；最长编译集中于snapshot codec 823 ms、runtime prune 354 ms和Core Fact materializer 241/226 ms，印证短预热未越过主要JIT阶段。
- heap/native/I/O/异常：JFR heap committed固定8 GiB；5次GC前后从`88→60 MiB`、`820→188 MiB`、`1.6 GiB→312 MiB`、`2.4 GiB→568 MiB`、`5.5 GiB→684 MiB`。NMT末值total reserved/committed=`144,108,027/8,547,539 KiB`，相对baseline `+68,788/+107,500 KiB`；高reserved来自ZGC地址空间，heap committed 8 GiB，GC committed约42.6 MiB，code 37.5 MiB、metaspace 32.4 MiB。DirectBuffer 35次观测均为count/capacity/used=0，未观察Mapped pool。Socket read/write=0；file read主要为benchmark JAR 3.0 MiB，file write主要为JNR临时dylib和JMH JSON/标准输出，没有owner业务同步网络/数据库I/O。Java exception/error事件为876/127，top为JMH反射探测的NoSuchField/NoSuchMethod及JNR符号探测，未出现业务异常或teardown失败。
- safepoint与环境：28个完整safepoint，最长3.43 ms、其中到达safepoint 3.33 ms，远低于业务尾延迟；JFR记录期间swap稳定为约208.2 MiB、Pages throttled=0、DataLoss=0。采样结束后的项目离线分析器将`allocations.json`与`locks-parks.json`同时slurp进单个`jq`，RSS升至约8.4 GiB、swap升至约2.1 GiB；为保护机器已中止该聚合器，原始18.5 MiB JFR、summary及各分项文件完整，`aggregate.json`为0字节且不得作为证据。该swap发生在JMH/JFR测量窗口之后，不污染已记录的吞吐或JFR窗口，但属于脚本/analyzer缺陷并导致自动报告未完成。
- artifact：根目录`target/qualification/20260901T141536Z-sync-owner-256-scale/`。shaded JAR 63,924,974 B、SHA-256 `af909397fc4d77b3af65393592d87549f2f8cdc4048c40e4f71a297ae2c61502`；主JSON 17,415 B、SHA-256 `e563bf9be16c3c661fcf4e794b46f1a4245bf78d915a928ea55371c25ea81e9d`；JFR轮JSON SHA-256 `c858d477d2457af18b697f427dd4ee0bf719be2ade229f69df2ba6e6101617c5`；GC-prof JSON SHA-256 `36523adb94b43ef110e15b6666673ebcf3e942fc8df57df6689b559ee832fe4a`；原始JFR 19,348,170 B、SHA-256 `c2d8935e10ced7968b43dbf66efd3605959d5b75a404056c387be4039735a4bc`；JFR summary SHA-256 `8bf80d7947615bc8eb41805a4c56065af41c936c917b96324c9c57a96c229235`；NMT diff SHA-256 `a8a5d5f72b5286fdf4dcbd079029ff24fa4c9188177e4cbe808f2773414b4f75`；JFC SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。
- 未测范围与结论：未执行10分钟长稳，不能声明heap/native/FD/线程/buffer无泄漏；未测其余五产品线性能，以及撤单、改单、批量、触发、风险扫描、强平、资金费、ADL、保险基金、交割/行权、API requests/s、Aeron Cluster复制、Kafka、外部projection、数据库和WebSocket独立指标。本轮代码与受影响功能测试通过，accepted/terminal闭环、同步matcher backlog、GC pause和分配门禁通过；但主吞吐、entry→terminal尾延迟和稳定预热门禁失败，自动JFR聚合器也失败，因此`PV-12`最终判定为`性能验收失败/部分归因完成`，不能宣称十项改动已达到性能验收目标。

### 2026-09-01 23:40:51 +08:00 — `PV-20260901-256-13` — `采集前锁定（单 Matcher Worker / Owner 串行提交）`

- 记录创建时间：`2026-09-01 23:40:51 +08:00`（`2026-09-01T15:40:51Z`）。被测 git commit `fd1107f3db88097db498095962d4bda1b6af7169`，其中生产实现 commit 为 `cafa4671`、固定窗口测试修正 commit 为 `fd1107f3`，分支 `codex/aeron-unified-core`，均已推送。对照 commit `79fe128b5f16a7140ee1e2d10591310cef4782db` 仅用于界定同步 Owner 到两阶段管线的代码差异，不检索、重跑或横向比较旧性能数据；结论使用本条绝对门禁。
- 修改点：将 exchange-core 撮合从 Aeron/Product Core owner 线程移到唯一的 `core-matcher-0` worker；owner 通过预分配有界 SPSC ring 提交不可变命令并按 core sequence 有序收割完成结果，随后仍在 owner 上串行执行 Account Lane 资金/持仓结算、commit/hash/Core Fact。查询、状态哈希、snapshot matcher fence 与关闭操作也在同一 matcher worker 上串行执行；Cluster background work 每轮最多提交 64 个已完成结果，后续非撮合消息按日志游标延迟以保持全局顺序。benchmark feeder 恢复真实固定窗口饱和，禁止同步完成后伪造 in-flight。
- 功能门禁：Oracle GraalVM Java HotSpot `25.0.1+8.1`、Maven `3.9.16`。`mvn -q -pl surprising-aeron-core/surprising-aeron-service -am test` 退出码0；`MatcherCommandPipelineTest`、`CoreStateSnapshotCodecTest`、`SurprisingClusteredServiceTest`、`RuntimeCommitRecoveryTest` 与 `LinearPerpetualBenchmarkSupportTest` 的组合精确回归退出码0。覆盖SPSC容量/顺序/失败传播、后台有序提交、普通/批量撮合、恢复、资金/持仓/hash与snapshot。未启动Docker、wallet或外围服务。
- in-flight 与拓扑：本轮严格且仅使用 `256 in-flight`；不得运行、补跑、推算或比较任何其他档位。仅 `LINEAR_PERPETUAL`，10,000活跃用户、512 listed/active symbols、4个逻辑Account Lane、1个exchange-core matcher worker、0个exchange-core risk engine、1个Product Core owner/JMH线程，每用户最多5持仓和10未成交单。进程内runtime projection与Core Fact materializer开启；API、Aeron Cluster复制、Kafka、外部exporter/history projection、PostgreSQL、WebSocket、Docker和wallet均不开启。
- 业务场景：`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`；每invocation固定16,384个PLACE_ORDER business operations，50% maker GTC与50% taker IOC，同symbol/价格/数量配对成交，做市状态持续存在。初态由固定snapshot template恢复且用户/做市账户资金充足。open-loop constant-arrival offered rate `100,000 business ops/s`，计划到达等待计入entry latency并修正coordinated omission；histogram `1 ns–30 s`、progress timeout 30秒、export ACK interval 1,024。
- 正确性与流水线门禁：`acceptedBusinessOperations == terminalBusinessOperations`、accepted Core messages=terminal Core messages、两个`unfinished*=0`、期末backlog=0、最大matching backlog `<=256`、`matchingFullWindowSamples>0`、producer starvation=0；JMH teardown必须通过用户/做市余额、冻结、持仓、活动订单、资金守恒、business/funds hash和snapshot restore。拒绝、错误、超时、fills/trades或逐账户资金流水若没有独立计数，必须记录为证据缺口，禁止推算。
- 吞吐门禁：无profiler主轮 `terminal business ops/s >=10,000`、99.9% CI下界 `>=9,000`、3个fork均值各 `>=9,000`；同时报告所有正式iteration、terminal Core messages/s、accepted/terminal、unfinished、refill/window/full-window/starvation、backlog和completion batch/mailbox等所有可用secondary metrics。单次峰值不代替聚合结论。
- 延迟门禁：PLACE_ORDER分别报告entry→accepted、accepted→terminal、entry→terminal的p50/p90/p95/p99/p99.9/max、样本数、单位、直方图区间及timeout；accepted→terminal p99/p99.9/max `<=35/50/150 ms`，entry→terminal p99/p99.9/max `<=1.70/1.75/2.10 s`。
- GC/JFR门禁：主轮无profiler；独立归因轮使用同一256场景与`-prof gc`，JFR使用 `surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc`，SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。分配门禁 `<=51,200 B/business op`；要求DataLoss=0、ZGC stall/OOM/allocation requiring GC=0、GC pause max `<=1 ms`、owner measurement同步file/socket/database I/O=0，并报告CPU/线程角色、分配、heap/native/Direct/Mapped、锁/park、safepoint、JIT与异常。禁用会整体载入JFR JSON的旧聚合器，只用JDK `jfr summary/view` 做有界聚合。
- 预热/测量/冷却：主轮 `5x5s warmup + 5x5s measurement + 3 forks + 1 thread`；结束后冷却30秒。仅当主吞吐、正确性和环境有效性通过时执行归因轮 `5x5s warmup + 3x5s measurement + 1 fork + 1 thread -prof gc`；本轮不执行10分钟长稳，因此无论结果如何不得声明heap/native/FD/线程/buffer无泄漏。
- JVM/机器：Intel Core i9-9880H 8C/16T、16 GiB、MacBookPro16,1、macOS 26.7 / Darwin 25.6 x86_64，非容器、未绑核；Oracle GraalVM HotSpot 25.0.1。JVM固定 `-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`及现有benchmark模块开放参数；Account Lane=4、matcher=1、matcher wait=BUSY_SPIN、settlement=BLOCKING、completion spins=16384、projection=PARKING batch64/4MiB、journal65536/1GiB、export pending256MiB。
- 环境有效性：锁定时swap used `1,116 MiB`、Pages throttled=0，且XProtect瞬时约65% CPU；采集必须等待其降到10%以下并在前后记录系统快照。swap占用增长、Swapin/Swapout或Pageout持续增长、throttling、thermal、额外benchmark JVM或任一同机进程持续超过一个物理核，均使容量结果降级为诊断；不终止用户进程。源码、参数、JDK/JVM、JFC和场景锁定后不得修改，任一失败或无效轮次必须追加。
- 执行命令口径：构建 `mvn -pl :surprising-aeron-benchmarks -am -DskipTests package`；主轮直接运行shaded JAR的上述benchmark，参数 `-bm thrpt -tu s -wi 5 -w 5s -i 5 -r 5s -f 3 -t 1 -to 10m`及锁定业务参数和`maxInFlight=256`。artifact根目录锁定为 `target/qualification/20260901T154051Z-spsc-matcher-owner-256/`；保存环境、完整命令、git/JAR/JFC SHA、JMH JSON/log、系统前后快照和最终size/SHA清单。归因若执行，则在同一目录保存原始JFR、GC/NMT和有界views。

### 2026-09-01 23:47:51 +08:00 — `PV-20260901-256-13` — `主吞吐未达门禁/环境无效，停止后续归因`

- 实际范围：采集时HEAD为锁定记录 commit `123e8fed961f2ce062ace381aad3189aa9833810`；被测生产实现仍为 `cafa4671`，benchmark窗口修正为 `fd1107f3`，二者与锁定后构建之间没有运行时代码变化。Oracle GraalVM Java HotSpot 25.0.1、JMH 1.37；只运行LINEAR_PERPETUAL、4个逻辑Account Lane、1个matcher worker、0个exchange-core risk engine、1个owner/JMH线程和固定且仅固定`256 in-flight`。没有启动Docker、wallet、API、Aeron Cluster、Kafka、数据库或外围服务，也没有运行其他in-flight档位。
- 执行与功能：`mvn -pl :surprising-aeron-benchmarks -am -DskipTests package`成功；主轮按锁定的`5x5s warmup + 5x5s measurement + 3 forks`完整运行4分21秒并正常teardown。accepted business=terminal business、accepted Core=terminal Core、两个unfinished均为0、producer starvation=0、full-window samples为正；没有出现资金、余额/冻结、持仓、订单终态、business/funds hash或snapshot restore断言。
- 主吞吐：`9,843.795 ±485.595 terminal business ops/s`，99.9% CI `[9,358.201, 10,329.390]`。CI下界通过`>=9,000`，但聚合比`10,000`门禁低`156.205 ops/s`（1.56%），因此吞吐门禁失败。3个fork均值为`9,800.215 / 10,112.707 / 9,618.464 ops/s`，均通过每fork`>=9,000`。15个正式iteration为`[8999.743,9426.254,10367.692,10118.394,10088.991] / [9569.262,10478.932,10101.451,10435.382,9978.510] / [9747.049,9853.386,9181.094,9937.213,9373.578]`；最高单点`10,478.932`只作观测，不替代聚合结论。
- Core与窗口计数：`acceptedBusinessOperations=terminalBusinessOperations=9,843.795 ops/s`；`acceptedCoreMessages=terminalCoreMessages=9,853.408 ±486.069 messages/s`，99.9% CI `[9,367.340,10,339.477]`；`unfinishedBusinessOperations=unfinishedCoreMessages=0`。`matchingRefillOperations=9,824.569 ±484.646 ops/s`、`matchingWindowSamples=153.809 ±7.587 samples/s`、`matchingFullWindowSamples=124.970 ±6.165 samples/s`，满窗口采样占比约81.25%，producer starvation=0。这证明调整后的测试实际维持了有界饱和窗口，而不是同步完成后伪造256并发。
- 环境有效性：主轮前后swap used从`1,116 MiB`降至`1,084 MiB`、Swapouts增量0、Pages throttled始终0；但Pageins/Pageouts/Swapins分别增加`9,105/1,838/2,280`页（约35.6/7.2/8.9 MiB）。端点不能证明这些活动均发生在measurement窗口，但已违反采集前锁定的无swap/pageout活动条件，因此容量数字降级为诊断数据；Wallpaper、Terminal、WindowServer、Codex等同机进程亦持续存在。
- 未执行阶段与证据缺口：按采集前条件，主吞吐失败且环境无效后不启动`-prof gc`、JFR/NMT和长稳，也没有本轮三段延迟、分配、GC、CPU热点、锁、heap/native或泄漏数据。主轮未独立输出max/期末backlog、completion mailbox/batch、fills/trades、拒绝/错误/超时和逐账户资金流水，保留为证据缺口，不能推算。
- 结论：SPSC matcher/owner两阶段路径的功能闭环和真实256窗口成立，三个fork均超过9,000且聚合接近10,000；但绝对吞吐门禁未通过，同时系统发生swap-in/pageout，本轮最终判定为`性能验收失败/诊断数据`。不能据此声明该架构已通过容量、尾延迟、内存或泄漏验收。
- artifact：`target/qualification/20260901T154051Z-spsc-matcher-owner-256/`。shaded JAR 由输入清单记录，SHA-256 `086ab2e226e8c7b0ece823247e85fd3e92399b7e0052bb831332159b14ebf6a7`；`main-256.json` 25,979 B、SHA-256 `2f58719ad6412766089fd926da3e3601f8f37194ee4ad0e4910671e40d778ccc`；`main-256.log` 30,521 B、SHA-256 `2bc02d4146dc12cbc80478b8c2dc5b6db6b957c36cb1be40c77d7f98833a988d`；`maven-package.log` 26,542 B、SHA-256 `2fa5623e0cb853c0df4bd38d73aa8a26e4ae405a4cc5c9e8809ba350941831f8`；artifact SHA清单 SHA-256 `13eb4ca754063911ff675074f8f236b4d6c76664c34b9ca400ebbe56aaba6a75`，size清单 SHA-256 `51ad7a64d8b1af7bfbd86ceee8f4c81c6fca33800783579ccda4061046ae0e0f`。

### 2026-09-02 09:34:01 +08:00 — `PV-20260902-256-14` — `采集前锁定（真实跨 Lane 并行结算）`

- 记录创建时间：`2026-09-02 09:34:01 +08:00`（`2026-09-02T01:34:01Z`）。被测生产 commit `96e6aae659f0563b099a057c90c7d357fcbeb618`，对照 commit 为直接父提交 `20991a7a38fae5b450b427194658f2665646d6aa`，分支 `codex/aeron-unified-core`，均已推送。历史 `PV-13` 的 maker/taker 账户因512 symbol与4 Lane的取模关系实际落在同一 Lane，本轮已强制每对成交双方进入不同 Lane，因此历史约9.8k只能作背景，不能视为同场景数值基线。
- 修改点：新增每个 Account Lane 一个预分配、有界SPSC结算worker；多Lane成交由owner一次发布、统一barrier等待、聚合失败、重绑owner后统一发布，单Lane仍在owner内联。`MatcherSettlementPlan`改为primitive array和lane bitmask，现货/永续结算及treasury delta复用scratch，matcher提交只在空队列交接时unpark；新增真实trade、拒绝、错误、超时计数，并修复JMH拓扑使每笔maker/taker成交必定跨Lane。预期消除owner串行处理双方资金/持仓及列表/数组临时对象瓶颈，同时不改变资金权威、提交顺序、回滚或快照语义。
- 功能门禁已完成：Oracle GraalVM Java HotSpot `25.0.1+8.1`、Maven `3.9.16`；全部修改完成后统一执行 `git diff --check`、脚本`bash -n`和 `mvn -pl :surprising-aeron-benchmarks -am test`，reactor全部成功，product API 12、protocol 80、instrument API 13、service及benchmark受影响测试全部0 failure/error。覆盖跨Lane成功/失败与owner恢复、matcher idle/burst、资金/持仓/冻结、订单终态、hash、Core Fact和snapshot recovery；未启动wallet、Docker或外围服务。
- 固定范围与并发：严格且仅使用 `256 in-flight`，禁止运行、补跑或推算其他档位。仅 `LINEAR_PERPETUAL`，10,000活跃用户、512 listed/active symbol、4个Account Lane及4个settlement worker、1个exchange-core matcher、0个exchange-core risk engine、1个Product Core risk engine、1个Product Core owner/JMH thread/进程内连接。matching engine固定为1，maker持续运行；API、Aeron Cluster复制、Kafka、外部exporter/history projection、PostgreSQL、WebSocket、Docker和wallet不启动。
- 固定业务与初态：`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`；每invocation固定16,384个PLACE_ORDER business operations，50% maker GTC + 50% taker IOC，同symbol/价格/数量配对并成交，预期8,192 trades/invocation；每一maker/taker对固定路由到不同Account Lane。每用户最多5持仓、10未成交单；从固定snapshot template恢复资金充足的用户/做市账户。teardown必须检查用户与做市余额、冻结、持仓、订单终态、资金守恒、business/funds hash、所有Lane队列清空及snapshot recovery。
- 负载与口径：open-loop constant-arrival offered rate `100,000 business operations/s`，计划到达等待计入entry latency并修正coordinated omission；export ACK interval 1,024，histogram `1 ns–30 s`、progress timeout 30秒，最大在途固定256。主指标为terminal business ops/s；同时报告terminal Core messages/s和实际terminal trades/s，trade不能混入business operation。
- 吞吐门禁：无profiler主轮 `terminal business ops/s >=50,000`、99.9% CI下界 `>=45,000`、3个fork均值各 `>=45,000`；`terminal trades/s`应为terminal business ops/s的50%，accepted business=terminal business、accepted Core=terminal Core、两个unfinished=0、拒绝/错误/超时=0、期末backlog=0、最大matching backlog `<=256`、full-window samples>0且producer starvation=0。单个峰值不替代聚合结论。
- 延迟门禁：PLACE_ORDER分别报告entry→accepted、accepted→terminal、entry→terminal三段p50/p90/p95/p99/p99.9/max、样本数、单位、直方图区间及超时；accepted→terminal p99/p99.9/max `<=35/50/150 ms`，entry→terminal p99/p99.9/max `<=1.70/1.75/2.10 s`。
- GC/分配门禁：主轮无profiler；同一场景独立运行 `-prof gc`，要求分配 `<=10,240 B/terminal business op`，报告allocation rate、B/invocation、B/op、GC次数/原因/总时间。JFR要求ZGC allocation stall/OOM/allocation requiring GC/promotion/evacuation failure=0、pause max `<=1 ms`，并报告TLAB/非TLAB、top allocation class/thread/site和heap committed/used/GC前后变化。
- JFR/NMT与资源门禁：使用 `surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc`，采集前SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。保存原始JFR、summary/views、GC/safepoint和NMT；报告机器/进程/owner、matcher、各settlement Lane、risk、projection/Core Fact及外围线程CPU和热点，heap/native/Direct/Mapped，锁/park，busy-spin，safepoint/VM operation，JIT/deopt/code cache，I/O/异常。DataLoss、container throttling、owner或Lane同步file/socket/database I/O均须为0；短JFR仅作归因，不能证明无泄漏。
- 稳定性与长稳：锁定时机器为Intel Core i9-9880H 8C/16T、16GiB、macOS 26.7/Darwin 25.6 x86_64，非容器、未绑核；swap已用764MiB、Pages throttled=0。正式窗口若swap占用增长、发生持续swapout/pageout、throttling、明显thermal或额外benchmark JVM/同机进程持续占用一个物理核，则容量结果降级为诊断。只有吞吐、正确性、尾延迟、分配和环境门禁全部通过，才运行同一256场景30秒预热+10分钟measurement长稳；否则明确不运行，且不得声明heap/native/FD/线程/buffer无泄漏。
- 固定JVM：Oracle GraalVM Java HotSpot 25.0.1；`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`及脚本内JDK模块开放参数；Account Lane=4、matcher=1、settlement wait=`BLOCKING`、commit journal=65536/1GiB、export pending=256MiB。归因fork额外启用NMT summary、GC/safepoint log和上述JFC。
- 阶段与命令：主轮 `5x5s warmup + 5x5s measurement + 3 forks + 1 thread`；归因JFR为`1x3s warmup + 1x10s measurement + fork=0`，独立GC轮为`5x5s warmup + 3x5s measurement + 1 fork + 1 thread -prof gc`；各阶段间至少30秒冷却。执行 `QUALIFICATION_RUN_ID=20260902T013401Z-parallel-settlement-256 SURPRISING_JAVA_HOME=/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home SCALE_JMH_WARMUP_ITERATIONS=5 SCALE_JMH_WARMUP_SECONDS=5 SCALE_JMH_MEASUREMENT_ITERATIONS=5 SCALE_JMH_MEASUREMENT_SECONDS=5 SCALE_JMH_FORKS=3 SATURATION_OPERATIONS_PER_INVOCATION=16384 QUALIFICATION_HEAP=8g surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh saturation`，再以同一shaded JAR/JVM/业务参数执行上述`-prof gc`。artifact根目录锁定为 `target/qualification/20260902T013401Z-parallel-settlement-256-scale/`，保存完整命令、环境前后快照、git/JAR/JFC SHA、JMH JSON/log、JFR/GC/NMT、分析输出及size/SHA-256清单；失败或无效轮次也必须追加，不覆盖本记录。

### 2026-09-02 11:31:00 +08:00 — `PV-20260902-256-14` — `失败/诊断结果`

- 被测生产 commit `96e6aae659f0563b099a057c90c7d357fcbeb618`，严格为 `256 in-flight`、4 Account Lane、1 matcher、10,000活跃用户、512活跃symbol和50% maker/50% taker跨Lane成交。accepted business=terminal business、accepted Core=terminal Core、两个unfinished、拒绝、错误、超时和producer starvation均为0；terminal trades恰为terminal business operations的50%，短轮资金、余额/冻结、持仓、订单终态、hash和snapshot teardown未报错。
- 无profiler主轮为 `7,850.445 ±2,318.242 terminal business ops/s`，99.9% CI `[5,532.203,10,168.686]`；三个fork均值分别为 `4,958.818 / 8,960.818 / 9,631.698 ops/s`，全部低于预锁定的45,000/fork门禁，聚合值低于50,000门禁。terminal Core messages为 `7,858.111/s`，trades为 `3,925.222/s`。第一fork期间存在额外外部测试进程和明显同机干扰，数值只能作为诊断；即使排除该fork，后两个fork仍远未达到50k。
- 独立 `-prof gc` 轮为 `9,533.061 terminal business ops/s`、`9,542.370 terminal Core messages/s`、`4,766.530 trades/s`；分配 `438.065 MB/s`、`810,164,554.667 B/JMH invocation`，按每invocation 16,384 business operations折算约 `49,448.520 B/business op`，超过10,240 B/op门禁约4.83倍；6次GC、累计GC time `1,582 ms`。归因轮只用于定位，不能替代主吞吐。
- JFR轮为 `8,149.831 terminal business ops/s`，原始recording 37秒、40.27 MiB；包含5次GC、23个GC pause，DataLoss、AllocationRequiringGC、ZAllocationStall、promotion/evacuation failure和container throttling均为0。最后一个正式PLACE_ORDER窗口的accepted→terminal p50/p90/p95/p99/p99.9/max为 `30.399/34.803/35.885/40.043/49.349/50.732 ms`，entry→terminal为 `1214.733/2121.379/2238.111/2317.472/2329.946/2331.269 ms`；accepted→terminal p99超过35ms，entry→terminal p99/p99.9/max也超过门禁。
- 环境有效性失败：主轮期间系统Pageins增加210,221页、Pageouts增加8,086页、Swapins增加18,056页；虽然Swapouts不变且swap used下降，仍违反无持续swap/pageout活动条件。同机Terminal、WindowServer、IDE/Codex等进程持续存在。因此本轮结论为`性能验收失败/诊断数据`，不运行长稳，不能声明heap/native/FD/线程/buffer无泄漏，也不能声明跨Lane双任务+统一barrier设计有容量收益。
- artifact：`target/qualification/20260902T013401Z-parallel-settlement-256-scale/`；主JSON 31,560 B、SHA-256 `0b2d5f69603f6f6fb26ce8d6ea4a39bda30c0861604ca1a69216b8164e079667`；GC JSON 25,183 B、SHA-256 `4f1df4b0026d3c8f64bdb8b4661cd5d5885a63a9b1a02501105a326152a92b4c`；原始JFR 42,227,319 B、SHA-256 `1d4d592ea3bd60ad45692ddcc123a479526d4d5f8bf770cc8b05b82aa8dc4af0`。未测其余五产品线、API/Aeron Cluster、Kafka、数据库、WebSocket及外部projection。

### 2026-09-02 11:31:00 +08:00 — `PV-20260902-256-15` — `采集前锁定（不可变成交事件连续 Lane 消费）`

- 记录创建时间：`2026-09-02 11:31:00 +08:00`（`2026-09-02T03:31:00Z`）。被测生产 commit `99eb0467ced214fc69691d01fb293a36a2e3e350`，对照 commit `96e6aae659f0563b099a057c90c7d357fcbeb618`；分支 `codex/aeron-unified-core`，均已推送。tracked工作区clean，已知untracked `openai`和三个`.factorypath`不进入构建、classpath或artifact。
- 修改点：删除每笔成交拆成两个`LaneMutationTask`再barrier的结算路径；matcher结果成为同一个确定性不可变`MatcherSettlementEvent`，按touched-lane bitmask直接进入各Account Lane的永久SPSC队列，每个lane串行只改自己拥有的账户/余额/订单/持仓。owner生产路径不再逐笔await/park，完成使用primitive VarHandle bitmap；批量只在整批完成边界合并发布视图。删除`AccountLaneState`单笔checkpoint、pending apply和rollback模型，改为连续applied sequence与committed watermark；观察到matcher fact后的失败统一fail-stop，依赖snapshot/log恢复，不热回滚。同步删除TreeMap热查询、全量LongObjectHashMap清理、数组fill、reversed patch及无用rollback/临时aggregate结构。
- 采集前功能门禁：Oracle GraalVM Java HotSpot `25.0.1+8.1`、Maven `3.9.16`；`mvn -pl :surprising-aeron-benchmarks -am test`全部成功，product API 12、protocol 80、instrument API 13、service 491、benchmarks 16，共 `612/612`，0 failure/error/skipped。覆盖真实Spot/永续成交、跨Lane批量交错、资金与持仓、冻结/解冻、订单终态、fail-stop、hash/Core Fact和snapshot recovery；统一测试曾捕获发布buffer并发drain缺陷，修复后精确14/14及最终全套均通过。
- 固定范围、并发、业务和初态完全沿用PV-14：只测`LINEAR_PERPETUAL`的`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`；10,000活跃用户、512 listed/active symbols、4 Account Lane/4永久worker、1 matcher、0 exchange-core risk engine、1 Product Core risk engine、1 owner/JMH thread/进程内连接，matching engine固定为1，做市持续运行。每invocation 16,384个PLACE_ORDER business operations，50% maker GTC+50% taker IOC且双方固定跨Lane，预期8,192 trades；固定snapshot初态含每用户最多5持仓/10未成交单及足额用户/做市资金。
- 负载与口径：严格且仅使用`256 in-flight`，不运行、补跑、推算或比较其他档位。open-loop constant-arrival offered `100,000 business operations/s`，修正coordinated omission；ACK interval 1,024，histogram `1 ns–30 s`、timeout 30秒。主指标terminal business ops/s，同时报告terminal Core messages/s、terminal trades/s、lane settlement events/s和各lane队列高水位；API、Aeron Cluster复制、Kafka、外部exporter/history projection、PostgreSQL、WebSocket、Docker和wallet均不启动。
- 正确性门禁：accepted business=terminal business、accepted Core=terminal Core、两个unfinished=0、拒绝/错误/超时=0、期末backlog=0、max backlog<=256、full-window samples>0、producer starvation=0；terminal trades须为terminal business operations的50%。teardown必须通过用户/做市余额、冻结、持仓、活动/终态订单、资金守恒、business/funds hash、Lane队列清空和snapshot recovery。
- 吞吐门禁保持最终目标不降级：无profiler主轮`terminal business ops/s >=50,000`、99.9% CI下界`>=45,000`、3个fork均值各`>=45,000`；单点峰值或GC/JFR轮不得替代。延迟门禁：PLACE_ORDER三段均报告p50/p90/p95/p99/p99.9/max与样本数；accepted→terminal p99/p99.9/max `<=35/50/150 ms`，entry→terminal p99/p99.9/max `<=1.70/1.75/2.10 s`。
- GC/分配门禁：独立`-prof gc`要求`<=10,240 B/terminal business op`；报告allocation rate、B/invocation、B/business op、GC次数/原因/总时间。JFR要求ZGC allocation stall/OOM/allocation requiring GC/promotion/evacuation failure=0、pause max<=1ms，并报告TLAB/非TLAB、top allocation class/thread/site、heap committed/used、GC前后和live-set趋势。
- JFR/NMT门禁：固定JFC `surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc`，采集前SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`；保存原始JFR、summary/bounded views、GC/safepoint、NMT baseline/diff。按owner、matcher、4个Account Lane、risk、projection、Core Fact及外围线程分组报告CPU/热点、allocation、heap/native/Direct/Mapped、锁/park、busy-spin、safepoint/VM、JIT/deopt、I/O和异常。DataLoss、throttling、owner/Lane同步file/socket/database I/O须为0。
- 固定环境/JVM：Intel Core i9-9880H 8C/16T、16GiB、macOS 26.7/Darwin 25.6 x86_64、非容器、未绑核；Oracle GraalVM Java HotSpot 25.0.1，`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`及脚本模块开放参数，settlement wait=`BLOCKING`、journal=65536/1GiB、export pending=256MiB。正式采集前后记录vm_stat/swap和top进程；若额外进程持续占用一个物理核、发生swapout/pageout增长、throttling或明显thermal，数据降级为诊断。锁定时`corespotlightd`瞬时约118% CPU，故在其回落且连续环境快照满足门禁前不得开始正式窗口。
- 阶段与命令：主轮`5x5s warmup + 5x5s measurement + 3 forks + 1 thread`；JFR归因`1x3s warmup + 1x10s measurement + fork=0`；独立GC轮`5x5s warmup + 3x5s measurement + 1 fork + 1 thread -prof gc`；阶段间至少30秒冷却。主/JFR执行`QUALIFICATION_RUN_ID=20260902T033100Z-immutable-lane-events-256 SURPRISING_JAVA_HOME=/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home SCALE_JMH_WARMUP_ITERATIONS=5 SCALE_JMH_WARMUP_SECONDS=5 SCALE_JMH_MEASUREMENT_ITERATIONS=5 SCALE_JMH_MEASUREMENT_SECONDS=5 SCALE_JMH_FORKS=3 SATURATION_OPERATIONS_PER_INVOCATION=16384 QUALIFICATION_HEAP=8g surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh saturation`，再对同一shaded JAR/JVM/业务参数执行独立`-prof gc`。artifact锁定为`target/qualification/20260902T033100Z-immutable-lane-events-256-scale/`，所有失败/无效轮次也保留并追加。
- 长稳门禁：仅当主吞吐、正确性、尾延迟、分配和环境全部通过后，才执行同一256场景30秒warmup+10分钟measurement，按10秒采样NMT/RSS/线程/FD/swap并至少比较3个post-GC点；live-set/old slope各<=1MiB/s、Direct/Mapped各<=256KiB/s、线程/FD/pool balance各<=0.01/s。否则不运行且不得声明无泄漏。

### 2026-09-02 11:57:51 +08:00 — `PV-20260902-256-15` — `吞吐/延迟/分配与环境门禁失败，定位到隐藏 Lane 同步回查`

- 实际范围与正确性：被测生产 commit `99eb0467ced214fc69691d01fb293a36a2e3e350`，严格且仅运行 `256 in-flight`、4 Account Lane、1 matcher、10,000活跃用户、512活跃symbol、50% maker/50% taker跨Lane成交。主轮、JFR轮和独立GC轮均完成业务teardown；accepted business=terminal business、accepted Core=terminal Core、两个unfinished、拒绝、错误、超时及producer starvation均为0，terminal trades严格为terminal business operations的50%，资金、余额/冻结、持仓、订单终态、hash及snapshot恢复未报错。
- 主吞吐失败：无profiler主轮为 `1,215.963 ±56.319 terminal business ops/s`，99.9% CI `[1,159.644,1,272.282]`；三个fork均值为 `1,225.568 / 1,251.653 / 1,170.669 ops/s`，全部远低于预锁定的45,000/fork及50,000聚合门禁。terminal Core messages为 `1,217.151/s`，trades为 `607.982/s`；matching refill为 `1,213.588/s`，full-window samples为正。该结果不是12k短期回退目标，而是证明当前实现仍有新的热路径串行化。
- 延迟失败：JFR轮吞吐为 `868.796 terminal business ops/s`，两个正式invocation、32,768个PLACE_ORDER样本。合并log2直方图的entry→accepted p50/p90/p95/p99/p99.9为 `17.180/34.360/34.360/34.360/34.360 s`桶上界，精确max `20.538 s`；accepted→terminal p50/p90/p95/p99/p99.9为 `268.435/536.871/536.871/536.871/536.871 ms`桶上界，精确max `366.459 ms`；entry→terminal精确max `20.785 s`。三段尾延迟均未通过锁定门禁；桶上界大于精确max属于log2量化，不代表存在34秒样本。
- 分配失败：独立 `-prof gc` 轮为 `1,195.230 terminal business ops/s`，`64.723 MB/s`、`1,017,141,234.667 B/JMH invocation`；每invocation固定16,384条，折算约 `62,081.374 B/business op`，超过10,240 B/op门禁约6.06倍；6次GC、JMH累计GC time `1,118 ms`。JFR采样估算为4.057 GB/s、约123,819 sampled B/business op，仅作归因，不替代精确GC profiler口径。
- JFR归因：原始JFR `261,058,218 B`。owner/JMH worker占分配样本78.17%，Core Fact约6.41%，四个Lane各约2.45%–2.68%；CPU主热点为 `LongObjectHashMap.forEachKeyValue → TradingRuntimeState.prepareCommitPatch → OwnerCommitTransaction.prepareAndPublish`。主要分配点包括 `TreeMap.put`、`SettlementLaneWorker.run`、`LongObjectHashMap`扩容、`MatcherSettlementEvent.<init>`和`LaneMutationTask.prepare`；主要类型包括 `byte[]`、`long[]`、`Object[]`、`TreeMap.Entry`、`Long`及`ThreadLocalMap.Entry`。源码复核确认，虽然成交事件已经直接进入Lane，但dispatch前仍逐订单同步读取余额/预留，prepareCommitPatch又逐changed key通过`LaneMutationTask.await`回查余额和reservation；这是隐藏的每成交多次owner↔Lane barrier，也是本轮退化的直接修复目标。Lane辅助指标仍为0，说明其当前共享计数存在数据竞争/可见性缺陷，不能用于容量结论。
- NMT与环境无效：脚本保存的NMT baseline只有`Baseline taken`，diff为空，无法形成native增量证据；分析阶段因此退出非0，NMT门禁失败。主/JFR阶段Pageins/Pageouts/Swapins/Swapouts分别增加`265,962/7,266/71,407/10,911`页；独立GC阶段分别增加`12,710/2,911/10,274/187,267`页，swap used从1,762.50 MiB增至2,496.00 MiB；Pages throttled为0，但明显swap/pageout使容量数字同时降级为诊断。没有运行长稳，不能声明heap/native/FD/线程/buffer无泄漏。
- artifact与校验：`target/qualification/20260902T033100Z-immutable-lane-events-256-scale/`。原始JFR SHA-256 `6a5d780a15008c3db2278a86c8d35bf2fd583cc0f5e8fbb624683f46056d19d7`；主JSON SHA-256 `432f9305ff15ee9324bb9ff42a03a4444a174df99ca22f2ea0230a7286a86c0d`；JFR profile JSON SHA-256 `3b99727b0c0c795f611021560144692fa735e3ea5cb95111a7d725d88a19143a`；GC JSON SHA-256 `4f3c151c21092d2df2a3ac099f25a7d244dfd0813ba884751fc9ebbc2b978d1b`。本轮结论为`性能验收失败/诊断数据`；下一轮必须先删除上述逐key同步回查和ThreadLocal热分配，再重新预锁定并采集。
