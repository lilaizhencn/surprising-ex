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

### 2026-09-02 12:26:57 +08:00 — `PV-20260902-256-16` — `采集前锁定（删除 owner↔Lane 隐藏同步回查）`

- 记录创建时间：`2026-09-02 12:26:57 +08:00`（`2026-09-02T04:26:57Z`）。被测生产 commit `53e5baea497854fee18bd06955dc0038b370212c`，代码对照 commit `99eb0467ced214fc69691d01fb293a36a2e3e350`；分支 `codex/aeron-unified-core`，均已推送。tracked工作区在锁定前clean，已知untracked `openai`及三个`.factorypath`不进入构建、classpath或artifact。
- 修改点：保留一个不可变`MatcherSettlementEvent`直接fanout至touched Account Lane的连续消费模型；删除dispatch前逐订单余额/预留同步读取，pending reservation的完成、余额/订单/reservation/client-order before/after采集与Lane hash发布均在同一次Lane事件内完成。owner只删除primitive pending索引并合并Lane发布结果，不再为prepareCommitPatch逐余额回查Lane，也不再为每次lane revision hash同步查询各Lane；owner侧增加primitive pending-user计数和已发布hash，Lane内余额直接引用且显式发布after-state。matcher指标改为Lane本地计数；`ThreadLocal.remove()`改为永久Lane线程复用entry。`MatcherSettlementPlan`删除trade list wrapper并增加无成交零数组路径，`RuntimeTreasuryDelta`改为首资产标量、第二资产才懒分配数组且clear不全量fill。
- 失败语义锁定：`AccountLaneState`仍只有连续`appliedSequence`与`committedSequence` watermark，不恢复单笔pending-apply/checkpoint/rollback；matcher fact一旦被Lane观察，任何Lane/patch/hash/资金不变量异常均poison matcher并fail-stop，依赖snapshot/Core Fact log恢复。现存`rollbackActiveCommand`只服务matcher fact被观察前的 admission/direct-command 失败，不作为已观察成交事件的热回滚。
- 采集前功能门禁：Oracle GraalVM Java HotSpot `25.0.1+8.1`、Maven `3.9.16`。先精确执行`CoreProbeStateTest` 81项及撮合/共享快照/恢复/批量顺序/永续端到端测试组，随后在全部修改完成后统一执行`git diff --check`与`mvn -pl :surprising-aeron-benchmarks -am test`；product API 12、protocol 80、instrument API 13、service 491、benchmarks 16，共`612/612`，0 failure/error/skipped。覆盖Spot/永续真实成交、资金/持仓/冻结、订单与client-order可见性、rolling hash/Core Fact、snapshot及log恢复；未启动wallet、Docker或外围服务。
- 固定范围与并发：严格且仅使用`256 in-flight`，禁止运行、补跑、推算或比较其他档位。只测`LINEAR_PERPETUAL`的`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`；10,000活跃用户、512 listed/active symbols、4个Account Lane/4个永久worker、1个exchange-core matcher、0个exchange-core risk engine、1个Product Core risk engine、1个owner/JMH thread/进程内连接。matching engine固定为1，做市持续运行；API、Aeron Cluster复制、Kafka、外部exporter/history projection、PostgreSQL、WebSocket、Docker和wallet均不启动。
- 固定业务、初态与口径：每invocation固定16,384个PLACE_ORDER terminal business operations，50% maker GTC+50% taker IOC且每对固定跨Lane，预期8,192 terminal trades；固定snapshot初态含每用户最多5持仓、10未成交单及足额用户/做市资金。open-loop constant-arrival offered `100,000 business operations/s`，计划到达等待计入entry latency并修正coordinated omission；ACK interval 1,024，histogram `1 ns–30 s`、timeout 30秒。主指标terminal business ops/s，同时报告terminal Core messages/s、terminal trades/s、lane settlement events/s和各Lane队列高水位。
- 正确性门禁：accepted business=terminal business、accepted Core=terminal Core、两个unfinished=0、拒绝/错误/超时=0、期末backlog=0、max backlog<=256、full-window samples>0且producer starvation=0；terminal trades须为terminal business operations的50%。teardown必须通过用户/做市余额、冻结、持仓、活动/终态订单、资金守恒、business/funds hash、Lane队列清空及snapshot recovery。
- 吞吐门禁保持最终目标：无profiler主轮`terminal business ops/s >=50,000`、99.9% CI下界`>=45,000`、3个fork均值各`>=45,000`；单点峰值、JFR轮或GC轮不得替代。延迟门禁：PLACE_ORDER的entry→accepted、accepted→terminal、entry→terminal分别报告p50/p90/p95/p99/p99.9/max与样本数；accepted→terminal p99/p99.9/max `<=35/50/150 ms`，entry→terminal p99/p99.9/max `<=1.70/1.75/2.10 s`。
- GC/分配门禁：主轮无profiler；同场景独立`-prof gc`要求`<=10,240 B/terminal business op`，报告allocation rate、B/invocation、B/business op、GC次数/原因/总时间。JFR要求ZGC allocation stall/OOM/allocation requiring GC/promotion/evacuation failure=0、pause max<=1ms，并报告TLAB/非TLAB、top allocation class/thread/site、heap committed/used、GC前后和live-set趋势。
- JFR/NMT与资源门禁：固定JFC `surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc`，SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。保存原始JFR、summary/bounded views、GC/safepoint、NMT baseline/diff；按owner、matcher、4个Account Lane、risk、projection、Core Fact及外围线程分组报告CPU/热点、allocation、heap/native/Direct/Mapped、锁/park、busy-spin、safepoint/VM、JIT/deopt、I/O和异常。DataLoss、container throttling、owner/Lane同步file/socket/database I/O均须为0；短JFR只作归因，不能证明无泄漏。
- 固定环境/JVM：Intel Core i9-9880H 8C/16T、16GiB、macOS 26.7/Darwin 25.6 x86_64、非容器、未绑核；Oracle GraalVM Java HotSpot 25.0.1，`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`及脚本模块开放参数，settlement wait=`BLOCKING`、journal=65536/1GiB、export pending=256MiB。锁定时swap used=1,696MiB、Pages throttled=0，Terminal瞬时约199% CPU、mysqld约68% CPU；正式采集必须等待这些瞬时负载回落，并在前后记录vm_stat/swap/top。若额外进程持续占用一个物理核、发生swapout/pageout增长、throttling或明显thermal，数据降级为诊断，不擅自终止用户进程。
- 阶段与命令：主轮`5x5s warmup + 5x5s measurement + 3 forks + 1 thread`；JFR归因`1x3s warmup + 1x10s measurement + fork=0`；独立GC轮`5x5s warmup + 3x5s measurement + 1 fork + 1 thread -prof gc`；阶段间至少30秒冷却。主/JFR执行`QUALIFICATION_RUN_ID=20260902T042657Z-owner-lane-barriers-256 SURPRISING_JAVA_HOME=/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home SCALE_JMH_WARMUP_ITERATIONS=5 SCALE_JMH_WARMUP_SECONDS=5 SCALE_JMH_MEASUREMENT_ITERATIONS=5 SCALE_JMH_MEASUREMENT_SECONDS=5 SCALE_JMH_FORKS=3 SATURATION_OPERATIONS_PER_INVOCATION=16384 QUALIFICATION_HEAP=8g surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh saturation`，再对同一shaded JAR/JVM/业务参数执行独立`-prof gc`。artifact锁定为`target/qualification/20260902T042657Z-owner-lane-barriers-256-scale/`；完整命令、环境、git/JAR/JFC SHA、JMH JSON/log、JFR/GC/NMT、分析输出及SHA/size清单全部保留，失败和无效轮次也追加。
- 长稳门禁：只有主吞吐、正确性、尾延迟、分配和环境全部通过，才执行同一256场景30秒warmup+10分钟measurement，按10秒采样NMT/RSS/线程/FD/swap并至少比较3个post-GC点；live-set/old slope各<=1MiB/s、Direct/Mapped各<=256KiB/s、线程/FD/pool balance各<=0.01/s。任一前置门禁失败则不运行，且不得声明heap/native/FD/线程/buffer无泄漏。

### 2026-09-02 12:41:28 +08:00 — `PV-20260902-256-16` — `吞吐/延迟/分配与环境门禁失败，owner commit patch成为主瓶颈`

- 实际范围与正确性：被测生产 commit `53e5baea497854fee18bd06955dc0038b370212c`，采集锁定 commit `e635d7a1`；严格且仅运行`256 in-flight`、4 Account Lane、1 matcher、10,000活跃用户、512活跃symbol及50% maker/50% taker跨Lane成交。主轮、JFR轮和GC轮均完成业务teardown；accepted business=terminal business、accepted Core=terminal Core、两个unfinished、拒绝、错误、超时及producer starvation均为0，terminal trades严格为terminal business operations的50%，资金、余额/冻结、持仓、订单终态、business/funds hash与snapshot恢复未报错。
- 主吞吐失败：无profiler主轮为`2,879.189 ±198.219 terminal business ops/s`，99.9% CI `[2,680.970,3,077.408]`；三个fork均值为`2,710.150 / 2,927.670 / 2,999.746 ops/s`，全部远低于45,000/fork及50,000聚合门禁。terminal Core messages=`2,882.000/s`、terminal trades=`1,439.594/s`。相同业务场景下该值高于PV-15约1.2k的诊断结果，但本轮环境不满足正式横向比较条件，不能把比值声明为正式性能提升。
- 延迟失败：JFR轮`2,419.149 terminal business ops/s`，3个invocation共49,152个PLACE_ORDER样本。合并log2直方图的entry→accepted p50/p90/p95/p99/p99.9为`4.295/8.590/8.590/8.590/17.180s`桶上界，精确max`8.609s`；accepted→terminal为`134.218/134.218/134.218/268.435/268.435ms`桶上界，精确max`158.890ms`；entry→terminal p50/p90/p95/p99/p99.9为`4.295/8.590/8.590/8.590/17.180s`桶上界，精确max`8.718s`。accepted→terminal p99/max与entry→terminal全部未通过；桶上界大于精确max来自log2量化。
- 分配失败：独立`-prof gc`轮为`2,763.944 terminal business ops/s`、`132.201 MB/s`、`855,317,968 B/JMH invocation`；每invocation固定16,384条，折算`52,204.466 B/business op`，超过10,240 B/op门禁约5.10倍；4次profiler GC、累计concurrent GC time`678ms`。JFR分配样本中owner/JMH线程25,064/34,340=`72.99%`，Core Fact 4,299=`12.52%`，四个Lane合计3,592=`10.46%`，matcher 820=`2.39%`。
- CPU与当前瓶颈：JFR 1,593个按线程归类的wall execution samples中owner/JMH线程891=`55.93%`，matcher92=`5.78%`，Core Fact71=`4.46%`，四个Account Lane合计222=`13.94%`。`TradingRuntimeState.prepareCommitPatch → LongObjectHashMap.forEachKeyValue`单一栈占322个样本，是明确主热点；后续为rolling business hash、TreeMap/HashMap、Core Fact materialization和matcher等待。分配top为primitive map扩容、`RollingBusinessStateHash.UserHash/UserGroupUpdate`、`TreeMap.Entry`、`ByteBuffer.allocate`、`HashMap`、`ArrayList`、immutable list iterator、`RuntimeCommitPatch.Changes.seal/insert`、`Long`及fact identity slice。说明隐藏Lane同步回查已删除，下一瓶颈是owner端通用patch扫描/boxed change、rolling hash及Core Fact临时对象。
- GC/heap/native与运行时：原始JFR 56秒、162,475,707 B；4次ZGC、20个pause phase，总pause`0.278ms`，p50/p90/p95/p99/max=`0.0103/0.0212/0.0219/0.0717/0.0717ms`，ZGC allocation stall、allocation requiring GC、promotion/evacuation failure、OOM和DataLoss均为0。heap committed最大8GiB、used最大2.400GiB，4个post-GC点由60MiB增至554MiB，记录太短且覆盖setup/JIT/状态增长，不能作泄漏结论。NMT summary.diff有效，结束总reserved/committed约`140,765.4/8,383.6MiB`，相对baseline`+102.1/+141.4MiB`，主要为GC、code、tracing和class/JIT启动增长；DirectBuffer采样余额为0，未见Mapped余额。
- 线程、锁、停顿、JIT与I/O：线程峰值18；22个monitor/wait事件合计88.8ms，主要为启动/JFR/Zip等待，没有持续交易锁热点。110个safepoint事件总pause3.176ms、最大0.766ms，到达safepoint最大0.187ms；136个VM operation总5.253ms、最大0.801ms。JIT compilation 8,607次、累计34.608s、最长0.743s，deoptimization 573次、class load/unload=`3,436/0`，短warmup仍包含显著编译。完整recording含7,034个启动/JMH输出file I/O事件，但owner measurement同步file/socket/database I/O为`0 events / 0 B`；1,036个exception/error主要为反射/native探测的NoSuchField/NoSuchMethod/UnsatisfiedLink，不是交易业务throw site。
- 环境与有效性：启动前连续采样约86.6% CPU idle，但正式阶段系统发生swap活动；从启动采样到结束Swapins/Swapouts至少增加`47,213/8,160`页，虽然swap used从1,696MiB降至1,176MiB且Pages throttled=0，仍违反无swapout/pageout门禁，因此容量与native结论降级为诊断。吞吐离50k超过一个数量级且owner热点集中，不能只归因于同机噪声。
- 长稳与范围：吞吐、尾延迟、分配和环境前置门禁均失败，未运行10分钟长稳，不能声明heap/native/FD/线程/buffer无泄漏。未测其余五产品线、API/Aeron Cluster、Kafka、数据库、WebSocket、外部projection及其他业务类型性能分布；没有运行任何其他in-flight档位。
- artifact：`target/qualification/20260902T042657Z-owner-lane-barriers-256-scale/`，约1.1GiB。主JSON SHA-256 `8f01a25c39246c776a2be8c82c1f2afef4c0ae9f562637209d68f973af8b72ab`；JFR profile JSON `edc38c7ecb8ad76b2ed10724fdeea16b2fc46cd36e393c81134384c197a9d245`；独立GC JSON `b260c0b0aaccd55a5d77ca50628dfd4904a65402e980462a2f684f6f1c97df20`；原始JFR `e8afe889289f2cdab53087e4ad29087f2f115375d0d475da2e050a9a2c396749`。脚本在JFR analyzer严格异常门禁后退出1，但上述原始与聚合artifact均完整保留；本轮结论为`性能验收失败/诊断数据`。

### 2026-09-02 13:45:53 +08:00 — `PV-20260902-256-17` — `采集前锁定（前向 Account Lane 与无 staging owner commit）`

- 记录创建时间：`2026-09-02 13:45:53 +08:00`（`2026-09-02T05:45:53Z`）。被测生产 commit `59e33ef86d653e8040155386363a76628b006d6e`，代码对照 commit `53e5baea497854fee18bd06955dc0038b370212c`；分支`codex/aeron-unified-core`，均已推送。tracked工作区在锁定前clean；已知untracked `openai`及三个`.factorypath`不进入构建、classpath或artifact。
- 修改点：一个不可变matcher fact只按touched lane各投递一次；每个Lane在同一个owner task中串行完成余额、冻结、订单、reservation、持仓、applied/committed watermark与局部hash发布，彻底删除第二个`LaneCommitCommand`。Lane/hash进入后不再维护单命令checkpoint、reverse transition、staging operation数组或中途失败注入，后续异常统一fail-stop并从snapshot/Core Fact log恢复。用户/订单/reservation capture与patch builder改为可复用primitive first-touch数组，只遍历当前命令size；余额使用`long userId + int assetId`，全局int/long变化不再装箱；删除owner端全表遍历、key排序、`BeforeAfter`、`UserGroupUpdate`、`HashTransition`、TreeSet/TreeMap canonicalization及posting/terminal/tombstone排序。Core Fact协议保留确定性first-touch顺序并线性拒绝重复键。
- 采集前功能门禁：Oracle GraalVM Java HotSpot `25.0.1+8.1`、Maven `3.9.16`；统一执行`git diff --check`与`mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-benchmarks -am test`。product API 12、protocol 80、instrument API 13、service 488、benchmarks 16，共`609/609`，0 failure/error/skipped。覆盖Spot/永续真实成交、Lane前向watermark、资金/持仓/冻结、订单终态、rolling hash/Core Fact first-touch协议、snapshot及log恢复；未启动wallet、Docker或外围服务。
- 固定范围与并发：严格且仅使用`256 in-flight`，不运行、补跑、推算或比较其他档位。只测`LINEAR_PERPETUAL`的`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`；10,000活跃用户、512 listed/active symbols、4个Account Lane/4个永久worker、1个exchange-core matcher、0个exchange-core risk engine、1个Product Core risk engine、1个owner/JMH thread/进程内连接。matching engine固定为1，做市持续运行；API、Aeron Cluster复制、Kafka、外部exporter/history projection、PostgreSQL、WebSocket、Docker和wallet均不启动。
- 固定业务、初态与负载：每invocation固定16,384个`PLACE_ORDER` terminal business operations，50% maker GTC+50% taker IOC，双方固定跨Lane，预期8,192 terminal trades；固定snapshot初态含每用户最多5持仓、10未成交单及足额用户/做市资金。open-loop constant-arrival offered `100,000 business operations/s`，计划到达等待计入entry latency并修正coordinated omission；ACK interval 1,024，histogram `1ns–30s`、timeout 30秒。报告terminal business ops/s、terminal Core messages/s、trades/s、batches/s、items/s、平均/最大batch size、Lane event与队列高水位。
- 正确性门禁：accepted business=terminal business、accepted Core=terminal Core、两个unfinished=0、拒绝/错误/超时=0、期末backlog=0、max backlog<=256、full-window samples>0且producer starvation=0；terminal trades须为terminal business operations的50%。teardown必须通过用户/做市余额、冻结、持仓、活动/终态订单、资金守恒、business/funds hash、Lane队列清空及snapshot recovery。
- 吞吐门禁保持最终目标：无profiler主轮`terminal business ops/s >=50,000`、99.9% CI下界`>=45,000`、3个fork均值各`>=45,000`。延迟分别报告entry→accepted、accepted→terminal、entry→terminal的p50/p90/p95/p99/p99.9/max、样本数和单位；accepted→terminal p99/p99.9/max `<=35/50/150ms`，entry→terminal p99/p99.9/max `<=1.70/1.75/2.10s`。
- GC/分配门禁：主轮无profiler；同场景独立`-prof gc`要求`<=10,240 B/terminal business op`，报告allocation rate、B/invocation、B/business op、GC次数/原因/总时间。JFR要求ZGC allocation stall/OOM/allocation requiring GC/promotion/evacuation failure=0、pause max<=1ms，并报告TLAB/非TLAB、最大对象、top allocation class/thread/site、heap committed/used、GC前后和live-set趋势。
- JFR/NMT与资源门禁：固定JFC `surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc`，SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。保存原始JFR、summary/bounded views、GC/safepoint、NMT baseline/diff；按owner、matcher、4个Account Lane、risk、projection、Core Fact及外围线程分组报告CPU/热点、allocation、heap/native/Direct/Mapped、锁/park、busy-spin、safepoint/VM、JIT/deopt、I/O和异常。DataLoss、container throttling、owner/Lane同步file/socket/database I/O均须为0；短JFR只作归因，不能证明无泄漏。
- 固定环境/JVM：Intel Core i9-9880H 8C/16T、16GiB、macOS 26.7/Darwin 25.6 x86_64、非容器、未绑核；Oracle GraalVM Java HotSpot 25.0.1。JVM固定`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`及脚本模块开放参数，settlement wait=`BLOCKING`、journal=65536/1GiB、export pending=256MiB。锁定时swap used=`693.25MiB`、Pages throttled=0，最高外部进程WindowServer/Terminal约25.3%/17.7% CPU；正式采集前后记录vm_stat/swap/top。若额外进程持续占用一个物理核、swapout/pageout增长、throttling或明显thermal，数据降级为诊断，不擅自终止用户进程。
- 阶段与命令：主轮`5x5s warmup + 5x5s measurement + 3 forks + 1 thread`；JFR归因`1x3s warmup + 1x10s measurement + fork=0`；独立GC轮`5x5s warmup + 3x5s measurement + 1 fork + 1 thread -prof gc`；阶段间至少30秒冷却。主/JFR执行`QUALIFICATION_RUN_ID=20260902T054553Z-forward-only-lanes-256 SURPRISING_JAVA_HOME=/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home SCALE_JMH_WARMUP_ITERATIONS=5 SCALE_JMH_WARMUP_SECONDS=5 SCALE_JMH_MEASUREMENT_ITERATIONS=5 SCALE_JMH_MEASUREMENT_SECONDS=5 SCALE_JMH_FORKS=3 SATURATION_OPERATIONS_PER_INVOCATION=16384 QUALIFICATION_HEAP=8g surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh saturation`，再对同一shaded JAR/JVM/业务参数执行独立`-prof gc`。artifact锁定为`target/qualification/20260902T054553Z-forward-only-lanes-256-scale/`；完整命令、环境、git/JAR/JFC SHA、JMH JSON/log、JFR/GC/NMT、分析输出及SHA/size清单全部保留，失败和无效轮次也追加。
- 长稳门禁：只有主吞吐、正确性、尾延迟、分配和环境全部通过，才执行同一256场景30秒warmup+10分钟measurement，按10秒采样NMT/RSS/线程/FD/swap并至少比较3个post-GC点；live-set/old slope各<=1MiB/s、Direct/Mapped各<=256KiB/s、线程/FD/pool balance各<=0.01/s。任一前置门禁失败则不运行，且不得声明heap/native/FD/线程/buffer无泄漏。

### 2026-09-02 14:12:00 +08:00 — `PV-20260902-256-17` — `吞吐/延迟/分配与环境门禁失败，旧 owner hash 扫描已消失`

- 实际范围与正确性：被测生产 commit `59e33ef86d653e8040155386363a76628b006d6e`，采集锁定 commit `d71b1d67`；严格且仅运行`256 in-flight`、4 Account Lane、1 matcher、10,000活跃用户、512活跃symbol及50% maker/50% taker跨Lane成交。主轮、JFR轮和独立GC轮均正常完成业务teardown；accepted business=terminal business、accepted Core=terminal Core、两个unfinished、拒绝、错误、超时及producer starvation均为0，terminal trades严格为terminal business operations的50%，资金、余额/冻结、持仓、订单终态、business/funds hash与snapshot恢复未报异常。
- 主吞吐失败：无profiler主轮`6,639.223 ±759.085 terminal business ops/s`，99.9% CI `[5,880.137,7,398.308]`；三个fork的15个measurement分别为`5,773–6,833`、`5,806–7,185`、`7,121–7,321 ops/s`，三个fork均远低于45,000门禁。terminal Core messages=`6,645.706/s`、terminal trades=`3,319.611/s`。相对PV-16约2,879/s诊断值已提高约2.3倍，但本轮环境仍不满足正式横向比较条件，不能把该比值作为容量结论。
- 延迟失败：JFR归因轮`4,982.729 terminal business ops/s`，5个正式invocation共81,920个PLACE_ORDER样本，open-loop/coordinated-omission corrected。合并log2直方图的entry→accepted p50/p90/p95/p99/p99.9=`2.147/4.295/4.295/4.295/4.295s`桶上界、精确max`3.734s`；accepted→terminal=`33.554/67.109/67.109/67.109/67.109ms`、精确max`67.930ms`；entry→terminal=`2.147/4.295/4.295/4.295/4.295s`、精确max`3.756s`。accepted→terminal p99/p99.9和entry→terminal均失败；桶上界大于精确max来自log2量化。
- 分配失败：独立`-prof gc`轮`2,798.722 terminal business ops/s`、`103.498 MB/s`、`713,131,130.667 B/JMH invocation`；每invocation固定16,384条，折算`43,526.070 B/business op`，为10,240门禁的4.25倍。profiler measurement内GC count为0，故该轮无GC时间可报告；JFR完整记录为4次ZGC、20个pause phase，总pause`0.295ms`，p50/p90/p95/p99/max=`0.0107/0.0345/0.0377/0.0394/0.0394ms`，allocation stall、allocation requiring GC、promotion/evacuation failure和OOM为0。
- CPU与结构归因：PV-16的`prepareCommitPatch → LongObjectHashMap.forEachKeyValue`主热点已消失，rolling business hash只剩7个execution samples。新的明确热点是共享in-flight capture：`LaneLongCaptures.indexOf`共157个samples，其中至少114个直接来自运行时`captureOrderBefore/captureReservationBefore`，说明256个未完成命令之间仍在做O(in-flight)线性key查找；owner/JMH线程727个samples，matcher86，Core Fact66，四个Lane合计195。后续代码 commit `da56a4dd`已据此改为generation-stamped primitive O(1)索引，并删除hot path `changedUsers()`复制，但该后续commit不属于本轮采集结果。
- 分配归因：JFR sampled allocation约9.319GiB，owner/JMH线程占70.65%，两个Core Fact线程合计13.80%，四个Lane合计约6.23%，matcher约3.06%。top site为primitive map扩容、`TreeMap.put`、`HashMap.putVal/resize`、immutable list iterator、`ByteBuffer.allocate`、command decode、`TradingRuntimeState.changedUsers`、ArrayList/stream/List.copyOf、tombstone、`LaneMutationTask.prepare`、`UserHash`及`Long.valueOf`。旧`UserGroupUpdate`、reversible transition和全量commit scan已不在热点中，但通用容器、编码及同步Lane admission仍需继续删除或专用化。
- heap/native/线程/停顿/JIT/I/O：heap committed固定8GiB，used最高2.402GiB，4个post-GC点从64MiB增至546MiB；短记录覆盖初态/JIT/状态建立，不能作泄漏结论。结束NMT总reserved/committed=`147,573,278,736/8,769,472,528 B`，主要为ZGC虚拟地址空间与8GiB heap；DirectBuffer采样余额为0，未形成长期native证据。锁/park共22个事件/68.6ms，无持续业务monitor热点。Safepoint完成38次总pause3.352ms、pause max0.516ms，但一次time-to-safepoint为88.163ms，需要后续解释；137个VM operation总4.006ms、max0.553ms。JIT compilation 8,504次/37.931s、最长0.804s，短JFR仍覆盖大量业务编译。owner measurement同步file/socket/database I/O=`0 events/0 B`，`DataLoss=0`。
- 环境、长稳与范围：锁定时swap used`693.25MiB`，采集和后续分析完成后为`1,052.75MiB`；缺少同一采集窗口完整page-in/page-out前后计数，且swap增量超过64MiB，因此环境门禁失败、容量/native结论降级为诊断。吞吐、延迟、分配和环境前置门禁均失败，未运行10分钟长稳，不能声明heap/native/FD/线程/buffer无泄漏。未测其余五产品线、API/Aeron Cluster、Kafka、数据库、WebSocket、外围projection及其他业务类型性能分布；没有运行其他in-flight档位。
- artifact：`target/qualification/20260902T054553Z-forward-only-lanes-256-scale/`，55个文件、约1.1GiB。主JSON SHA-256 `4a3540410cafe608132ca701178cd1dd904f1d03d2d09d0e53a8b9d5197fd009`；JFR profile JSON `451ab57096710053e8b8651320476b8445df8d51df0d5799669823bc7ccfb97b`；独立GC JSON `13a073cf6b506f906119a786ef586016af3ab3ab025cf3951ba666fa666bcf86`；原始JFR `106d34a5181cd0633f2ed318bbc0981489335412123e8b111ce5229bcd20cb97`；聚合分析`f2f8c560e7f97ab114192fc21743091884918124429c5d6b48c01923d7dae341`。脚本在JFR analyzer严格异常门禁后退出1，但原始、JMH和聚合artifact完整保留；本轮结论为`性能验收失败/诊断数据`。

### 2026-09-02 14:50:54 +08:00 — `PV-20260902-256-18` — `采集前锁定（一次 matcher fact 完成全部 Lane 结算）`

- 记录创建时间：`2026-09-02 14:50:54 +08:00`（`2026-09-02T06:50:54Z`）。被测生产 commit `ebfe96fe787923a79ff8ae64c50e69ffc48590da`，代码对照 commit `fafb7965547d2a4b87161c77cd5bb01dfd0efb1d`；分支`codex/aeron-unified-core`，均已推送。tracked工作区在锁定前clean；已知untracked `openai`及三个`.factorypath`不进入构建、classpath或artifact。
- 修改点：异步成交的同一条不可变`MatcherSettlementEvent`现在由每个目标Lane一次完成资产、冻结、订单、reservation、持仓、订单`updatedAt/clusterPosition`、applied/committed watermark和局部hash；删除成交完成后的第二次订单stamping Lane fan-out及重复pending-reservation完成调用。每个Lane写入独立的cache-line-separated release/acquire completion slot，不共享atomic RMW。命令changed-ID使用first-touch primitive数组和generation索引，clear不扫描历史容量；不可变primitive列表移交新数组所有权避免二次复制；空tombstone复用单例，删除列表及liquidation/trigger/treasury fact列表按需分配；single-patch terminal order线性去重后一次排序，不创建TreeSet节点。Saturation JMH新增真实Lane operation计数、拒绝数和期末队列门禁。
- 采集前功能门禁：Oracle GraalVM Java HotSpot `25.0.1+8.1`、Maven `3.9.16`；统一执行`mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-benchmarks -am test`，product API 12、protocol 80、instrument API 13、service 490、benchmarks 16，共`611/611`，0 failure/error/skipped。覆盖六产品线成交资金矩阵、Spot/永续真实成交、订单批次、Lane forward-only契约、资金/余额/冻结/持仓、订单终态、Core Fact tombstone/terminal ID、snapshot及log replay恢复；未启动wallet、Docker或外围服务。
- 固定范围与并发：严格且仅使用`256 in-flight`，不运行、补跑、推算或比较其他档位。只测`LINEAR_PERPETUAL`的`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`；10,000活跃用户、512 listed/active symbols、4个Account Lane/4个永久worker、1个exchange-core matcher、0个exchange-core risk engine、1个Product Core risk engine、1个owner/JMH thread/进程内连接。matching engine固定为1，做市持续运行；API、Aeron Cluster复制、Kafka、外部exporter/history projection、PostgreSQL、WebSocket、Docker和wallet均不启动。
- 固定业务、初态与负载：每invocation固定16,384个`PLACE_ORDER` terminal business operations，50% maker GTC+50% taker IOC，双方固定跨Lane，预期8,192 terminal trades；固定snapshot初态含每用户最多5持仓、10未成交单及足额用户/做市资金。open-loop constant-arrival offered `100,000 business operations/s`，计划到达等待计入entry latency并修正coordinated omission；ACK interval 1,024，histogram `1ns–30s`、timeout 30秒。报告terminal business ops/s、terminal Core messages/s、trades/s、batches/s、items/s、平均/最大batch size、Lane operations、队列高水位、拒绝与期末深度。
- 正确性/容量门禁：accepted business=terminal business、accepted Core=terminal Core、两个unfinished=0、拒绝/错误/超时=0、期末matching及Lane backlog=0、max matching backlog<=256、full-window samples>0且producer starvation=0；terminal trades须为terminal business operations的50%；Lane operations只允许settlement类别且每business op总数`>2`、`<=3`。teardown必须通过用户/做市余额、冻结、持仓、活动/终态订单、资金守恒、business/funds hash、Lane队列清空及snapshot recovery。
- 吞吐门禁保持最终目标：无profiler主轮`terminal business ops/s >=50,000`、99.9% CI下界`>=45,000`、3个fork均值各`>=45,000`。延迟分别报告entry→accepted、accepted→terminal、entry→terminal的p50/p90/p95/p99/p99.9/max、样本数和单位；accepted→terminal p99/p99.9/max `<=35/50/150ms`，entry→terminal p99/p99.9/max `<=1.70/1.75/2.10s`。
- GC/分配门禁：主轮无profiler；同场景独立`-prof gc`要求`<=10,240 B/terminal business op`，报告allocation rate、B/invocation、B/business op、GC次数/原因/总时间。JFR要求ZGC allocation stall/OOM/allocation requiring GC/promotion/evacuation failure=0、pause max<=1ms，并报告TLAB/非TLAB、最大对象、top allocation class/thread/site、heap committed/used、GC前后和live-set趋势。
- JFR/NMT与资源门禁：固定JFC `surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc`，SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。保存原始JFR、summary/bounded views、GC/safepoint、NMT baseline/diff；按owner、matcher、4个Account Lane、risk、projection、Core Fact及外围线程分组报告CPU/热点、allocation、heap/native/Direct/Mapped、锁/park、busy-spin、safepoint/VM、JIT/deopt、I/O和异常。DataLoss、container throttling、owner/Lane同步file/socket/database I/O均须为0；短JFR只作归因，不能证明无泄漏。
- 固定环境/JVM：Intel Core i9-9880H 8C/16T、16GiB、macOS 26.7/Darwin 25.6 x86_64、非容器、未绑核；Oracle GraalVM Java HotSpot 25.0.1。JVM固定`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`及脚本模块开放参数，settlement wait=`BLOCKING`、journal=65536/1GiB、export pending=256MiB。锁定时swap used=`3293.50MiB`、Pages throttled=0，IntelliJ/WindowServer约94.6%/52.6% CPU，环境在采集前已不满足正式容量有效性；仍保留本轮作为诊断且不擅自终止用户进程。若采集前干扰回落则如实记录，但既有swap和采集窗口任何page-in/page-out、swapout、throttling或明显thermal仍使容量/native结论无效。
- 阶段与命令：主轮`5x5s warmup + 5x5s measurement + 3 forks + 1 thread`；JFR归因固定`1x3s warmup + 1x10s measurement + fork=0`；独立GC轮`5x5s warmup + 3x5s measurement + 1 fork + 1 thread -prof gc`；阶段间至少30秒冷却。主/JFR执行`QUALIFICATION_RUN_ID=20260902T065054Z-lane-event-settlement-256 SURPRISING_JAVA_HOME=/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home SCALE_JMH_WARMUP_ITERATIONS=5 SCALE_JMH_WARMUP_SECONDS=5 SCALE_JMH_MEASUREMENT_ITERATIONS=5 SCALE_JMH_MEASUREMENT_SECONDS=5 SCALE_JMH_FORKS=3 SATURATION_OPERATIONS_PER_INVOCATION=16384 QUALIFICATION_HEAP=8g surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh saturation`，再对同一shaded JAR/JVM/业务参数执行独立`-prof gc`。artifact锁定为`target/qualification/20260902T065054Z-lane-event-settlement-256-scale/`；完整命令、环境、git/JAR/JFC SHA、JMH JSON/log、JFR/GC/NMT、分析输出及SHA/size清单全部保留，失败和无效轮次也追加。
- 长稳门禁：只有主吞吐、正确性、尾延迟、分配和环境全部通过，才执行同一256场景30秒warmup+10分钟measurement，按10秒采样NMT/RSS/线程/FD/swap并至少比较3个post-GC点；live-set/old slope各<=1MiB/s、Direct/Mapped各<=256KiB/s、线程/FD/pool balance各<=0.01/s。任一前置门禁失败则不运行，且不得声明heap/native/FD/线程/buffer无泄漏。

### 2026-09-02 16:15:00 +08:00 — `PV-20260902-256-18` — `Lane 语义通过；吞吐、延迟、分配及环境门禁失败`

- 实际范围与正确性：被测生产 commit `ebfe96fe787923a79ff8ae64c50e69ffc48590da`，采集锁定 commit `90d0f7d6`。严格且仅运行固定`256 in-flight`、4 Account Lane、1 matcher、10,000活跃用户、512活跃symbol、50% maker GTC/50% taker IOC。主轮、JFR轮和独立GC轮均正常teardown；accepted business=terminal business、accepted Core=terminal Core，两个unfinished、拒绝、错误、超时和producer starvation均为0，terminal trades为terminal business operations的50%，资金、余额/冻结、持仓、订单终态、business/funds hash、Lane队列清空和snapshot恢复未报异常。
- Lane结构门禁通过：主轮Lane operations=`12,166.888/s`，全部为`SETTLEMENT`，command/query/risk均为0；Lane/business比值`2.508`，位于预锁定`>2且<=3`范围。JFR轮同样为`13,148.061/5,242.298=2.508`。这证明异步撮合完成后没有第二次订单stamping fan-out，也没有重复pending-reservation owner任务；一条不可变matcher fact在目标Lane内一次完成本Lane结算及订单commit metadata。
- 主吞吐失败：无profiler主轮`4,851.393 ±610.616 terminal business ops/s`，99.9% CI `[4,240.778,5,462.009]`；terminal Core=`4,856.131 messages/s`，trades=`2,425.697/s`，15个正式measurement范围`4,180.755–6,192.367 ops/s`，远低于50,000及每fork45,000门禁。由于本轮采集前机器已经严重swap且存在IntelliJ/WindowServer高CPU，本值只能作为诊断，不与PV-17作代码回归结论。
- 延迟失败：JFR轮5个正式invocation共81,920个PLACE_ORDER样本，open-loop/coordinated-omission corrected。entry→accepted p50/p90/p95/p99/p99.9为`2.147/4.295/4.295/4.295/4.295s`桶上界、精确max`3.630s`；accepted→terminal为`33.554/67.109/67.109/67.109/268.435ms`、精确max`194.654ms`；entry→terminal为`2.147/4.295/4.295/4.295/4.295s`、精确max`3.666s`。accepted→terminal p99/p99.9/max及entry→terminal门禁均失败。
- 分配失败但方向改善：独立`-prof gc`归因轮`6,196.519 terminal business ops/s`、`218.648 MB/s`、`635,440,462.222 B/JMH invocation`，按固定16,384条折算`38,784.208 B/business op`，仍为10,240门禁的3.79倍；measurement内约0次GC。该结果只作归因。JFR sampled allocation显示owner/JMH线程约67%、Core Fact约13%、四个Lane合计约12%、matcher约3%；top class为`byte[]`、`long[]`、`Object[]`、boxed `Long`、immutable list iterator、`TreeMap.Entry`、订单/runtime快照及`ArrayList`，top site包括`ByteBuffer.allocate`、primitive map扩容、`TreeMap.put`、`HashMap.putVal`、list iterator、command decode、stream、`prepareCommitPatch`和订单view物化。
- CPU与下一瓶颈：owner/JMH线程783 samples，matcher102、Core Fact83、四个Lane合计227。业务墙钟热点仍包含`CoreProbeState.completeMatching`、`TreeMap.put/successor/getEntry`、`CoreStateHash.mix`、`HashMap`扩容、pending ring lookup及owner健康检查；采样栈明确出现`RollingBusinessStateHash.stable/applyPatch`和snapshot materializer。Lane结算fan-out已经收敛，剩余主要成本转为同步Lane admission、逐命令canonical rolling hash、Core Fact/response物化及通用容器分配。
- GC/heap/native：JFR 4次ZGC，总GC concurrent时长约1.043s；20个pause phase总`0.303ms`，p50/p90/p95/p99/max=`0.0122/0.0180/0.0230/0.0558/0.0558ms`，allocation stall、allocation requiring GC及退化信号为0。heap committed固定8GiB，used最高2.402GiB；4个post-GC点从60MiB增至654MiB，短记录覆盖状态建立，不能证明泄漏。退出NMT总reserved/committed=`147,595,997,482/8,791,699,754 B`，主要是ZGC虚拟地址空间和8GiB heap；没有长稳native/Direct斜率证据。
- 线程/锁/停顿/JIT/I/O：记录末33线程；有3,292,337个ThreadPark事件，但物化后的monitor/park contention仅21个、总69.2ms，未见持续交易锁热点。完成safepoint总pause`2.595ms`、max`0.389ms`，time-to-safepoint max`0.572ms`。Compilation 8,337次、deoptimization 556次，JFR仍覆盖大量预热/JIT。owner正式measurement同步业务file/socket/database I/O为0；`DataLoss=0`、container throttling=0。
- 环境、长稳与范围：锁定时swap used`3,293.50MiB`，JFR结束时JFR记录显示物理内存约15.9/16GiB已用、swap约6.1/7GiB已用；后续系统读取仍为`4,146.25MiB`，并伴随大量page/swap活动，环境门禁明确失败。吞吐、延迟、分配和环境门禁失败，未运行10分钟长稳，不能声明heap/native/FD/线程/buffer无泄漏。未测其余五产品线、其他业务类型性能分布、API/Aeron Cluster、Kafka、数据库、WebSocket及外围projection；没有运行其他in-flight档位。
- artifact：`target/qualification/20260902T065054Z-lane-event-settlement-256-scale/`，55个文件、约1.0GiB。主JSON SHA-256 `d1c51d69caa761cb69bbb6f99b0f347b7a6e7ef2960da71d320fb1e200579a5f`；JFR profile JSON `8e112c081bd450092aedf4f15aaa269fb53c32796374eb23281e86d4c0ac8564`；独立GC JSON `4b3fae90917b28a36f5a188f0495f908b7ea346808d9976c932a2a7207e0d606`；原始JFR `098e1f22f08e83c0f87ebdc38bb6f5b633c20230ed057a3086699fe313d0d887`；聚合分析 `ef521c0273ba478157fca6c00d66174340853396879381584a26daa73b22e67b`。本轮结论为`Lane设计验收通过；整体性能验收失败/容量数据无效`。

### 2026-09-02 17:25:13 +08:00 — `PV-20260902-256-19` — `采集前锁定（单向 Lane admission 与最小结算状态）`

- 记录创建时间：`2026-09-02 17:25:13 +08:00`（`2026-09-02T09:25:13Z`）。被测生产 commit `40bb06c7a762c279bea6fe311e9ff705b51fc0c5`，代码对照 commit `ebfe96fe787923a79ff8ae64c50e69ffc48590da`；分支`codex/aeron-unified-core`，均已推送。tracked工作区在锁定前clean；已知untracked `openai`及三个`.factorypath`不进入构建、classpath或artifact。
- 修改点：PLACE admission改为单向不可变`PlaceAdmissionEvent`，owner只按sequence推进已完成结果，不做资产预变更、逐笔barrier、checkpoint或rollback；每个Account Lane永久线程只串行改变自己用户的余额、冻结、订单和reservation，运行时异常直接fail-stop。matcher仍只产生一个不可变fact，每个touched Lane各消费一次；完成状态改为紧凑atomic lane mask，settlement plan只保留必要order id并直接引用matcher events。删除逐命令projection副本，commit journal仅推进watermark；删除aggregate active-order复合索引、全表扫描/排序、热路径`TreeMap`、boxed long-key change map、临时`UserGroupUpdate`、重复client-order captures及多余空列表/数组。PLACE codec直接little-endian byte数组编解码；position/order/client key、rejection和capture索引使用primitive集合；rolling hash保留确定性校验但改为持久`UserHash`和复用updater，不再逐命令临时构建用户hash组。
- 采集前功能门禁：Oracle GraalVM Java HotSpot `25.0.1+8.1`、Maven `3.9.16`；统一执行`mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-benchmarks -am clean test`。product API 12、protocol 80、instrument API 13、service 490、benchmarks 16，共`611/611`，0 failure/error/skipped。覆盖异步admission拒绝、六产品线成交/财务与snapshot合同、Spot/永续真实成交、批次fail-stop、Lane线程权威、资金/余额/冻结/持仓、订单终态、rolling hash/Core Fact及snapshot/log恢复；未启动wallet、Docker或外围服务。
- 固定范围与并发：严格且仅使用`256 in-flight`，不运行、补跑、推算或比较其他档位。只测`LINEAR_PERPETUAL`的`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`；10,000活跃用户、512 listed/active symbols、4个Account Lane/4个永久worker、1个exchange-core matcher、0个exchange-core risk engine、1个Product Core risk engine、1个owner/JMH thread/进程内连接。matching engine固定为1，做市持续运行；API、Aeron Cluster复制、Kafka、外部exporter/history projection、PostgreSQL、WebSocket、Docker和wallet均不启动。
- 固定业务、初态与负载：每invocation固定16,384个`PLACE_ORDER` terminal business operations，50% maker GTC+50% taker IOC，双方固定跨Lane，预期8,192 terminal trades；固定snapshot初态含每用户最多5持仓、10未成交单及足额用户/做市资金。open-loop constant-arrival offered `100,000 business operations/s`，计划到达等待计入entry latency并修正coordinated omission；ACK interval 1,024，histogram `1ns–30s`、timeout 30秒。报告terminal business ops/s、terminal Core messages/s、trades/s、batches/s、items/s、平均/最大batch size、Lane admission/settlement operations、队列高水位、拒绝与期末深度。
- 正确性/容量门禁：accepted business=terminal business、accepted Core=terminal Core、两个unfinished=0、拒绝/错误/超时=0、期末matching及Lane backlog=0、max matching backlog<=256、full-window samples>0且producer starvation=0；terminal trades须为terminal business operations的50%。Lane admission必须严格等于terminal business operations；Lane settlement须`>1且<=2`倍business operations；Lane总operations须`>2且<=3`倍business operations，且只有admission/settlement两类。teardown必须通过用户/做市余额、冻结、持仓、活动/终态订单、资金守恒、business/funds hash、Lane队列清空及snapshot recovery。
- 吞吐门禁保持最终目标：无profiler主轮`terminal business ops/s >=50,000`、99.9% CI下界`>=45,000`、3个fork均值各`>=45,000`。延迟分别报告entry→accepted、accepted→terminal、entry→terminal的p50/p90/p95/p99/p99.9/max、样本数和单位；accepted→terminal p99/p99.9/max `<=35/50/150ms`，entry→terminal p99/p99.9/max `<=1.70/1.75/2.10s`。
- GC/分配门禁：主轮无profiler；同场景独立`-prof gc`要求`<=10,240 B/terminal business op`，报告allocation rate、B/invocation、B/business op、GC次数/原因/总时间。JFR要求ZGC allocation stall/OOM/allocation requiring GC/promotion/evacuation failure=0、pause max<=1ms，并报告TLAB/非TLAB、最大对象、top allocation class/thread/site、heap committed/used、GC前后和live-set趋势。
- JFR/NMT与资源门禁：固定JFC `surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc`，SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。保存原始JFR、summary/bounded views、GC/safepoint、NMT baseline/diff；按owner、matcher、4个Account Lane、risk、projection、Core Fact及外围线程分组报告CPU/热点、allocation、heap/native/Direct/Mapped、锁/park、busy-spin、safepoint/VM、JIT/deopt、I/O和异常。DataLoss、container throttling、owner/Lane同步file/socket/database I/O均须为0；短JFR只作归因，不能证明无泄漏。
- 固定环境/JVM：Intel Core i9-9880H 8C/16T、16GiB、macOS 26.7/Darwin 25.6 x86_64、非容器、未绑核；Oracle GraalVM Java HotSpot 25.0.1。JVM固定`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`及脚本模块开放参数，settlement wait=`BLOCKING`、journal=65536/1GiB、export pending=256MiB。锁定时swap used=`2831.25MiB`、Pages throttled=0，Xprotect/Terminal/WindowServer瞬时约55.8%/13.8%/11.8% CPU；环境在采集前已不满足正式容量有效性，本轮结果将如实标为诊断，不擅自终止用户或系统进程。若采集前干扰回落则记录，但既有swap和采集窗口任何明显page-in/page-out、swapout、throttling或thermal仍使容量/native结论无效。
- 阶段与命令：主轮`5x5s warmup + 5x5s measurement + 3 forks + 1 thread`；JFR归因固定`1x3s warmup + 1x10s measurement + fork=0`；独立GC轮`5x5s warmup + 3x5s measurement + 1 fork + 1 thread -prof gc`；阶段间至少30秒冷却。主/JFR执行`QUALIFICATION_RUN_ID=20260902T092513Z-one-way-lane-256 SURPRISING_JAVA_HOME=/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home SCALE_JMH_WARMUP_ITERATIONS=5 SCALE_JMH_WARMUP_SECONDS=5 SCALE_JMH_MEASUREMENT_ITERATIONS=5 SCALE_JMH_MEASUREMENT_SECONDS=5 SCALE_JMH_FORKS=3 SATURATION_OPERATIONS_PER_INVOCATION=16384 QUALIFICATION_HEAP=8g surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh saturation`，再对同一shaded JAR/JVM/业务参数执行独立`-prof gc`。artifact锁定为`target/qualification/20260902T092513Z-one-way-lane-256-scale/`；完整命令、环境、git/JAR/JFC SHA、JMH JSON/log、JFR/GC/NMT、分析输出及SHA/size清单全部保留，失败和无效轮次也追加。
- 长稳门禁：只有主吞吐、正确性、尾延迟、分配和环境全部通过，才执行同一256场景30秒warmup+10分钟measurement，按10秒采样NMT/RSS/线程/FD/swap并至少比较3个post-GC点；live-set/old slope各<=1MiB/s、Direct/Mapped各<=256KiB/s、线程/FD/pool balance各<=0.01/s。任一前置门禁失败则不运行，且不得声明heap/native/FD/线程/buffer无泄漏。

### 2026-09-02 17:40:00 +08:00 — `PV-20260902-256-19` — `Lane 正确性通过；吞吐/延迟/分配/环境失败，发现残留 TreeSet/ADL TreeMap 热点`

- 实际范围与正确性：被测生产代码 commit `40bb06c7a762c279bea6fe311e9ff705b51fc0c5`，采集锁定 commit `3a7ca248`；严格且仅运行`256 in-flight`、4 Account Lane、1 matcher、10,000活跃用户、512活跃symbol、50% maker GTC/50% taker IOC。主轮、JFR轮和独立GC轮均正常teardown；accepted business=terminal business、accepted Core=terminal Core，两个unfinished、拒绝、错误、超时和producer starvation均为0，terminal trades严格为terminal business operations的50%，资金、余额/冻结、持仓、订单终态、business/funds hash、Lane队列清空和snapshot恢复未报异常。
- Lane结构门禁通过：主轮admission=`8,256.308/s`，严格等于terminal business operations；settlement=`12,448.965/s`，为business的`1.508`倍；Lane总operations=`20,705.274/s`，为business的`2.508`倍，query/risk均为0。说明每笔PLACE只单向进入一个账户Lane，matcher fact每个touched Lane只消费一次，没有逐笔barrier或第二次stamping fan-out。
- 主吞吐失败：无profiler主轮`8,256.308 ±1,600.731 terminal business ops/s`，99.9% CI `[6,655.577,9,857.040]`；terminal Core=`8,264.371 messages/s`，trades=`4,128.154/s`。三个fork均值为`7,086.907 / 9,452.867 / 8,229.151 ops/s`，15个measurement范围`5,437.624–10,076.690/s`，均远低于50,000/45,000门禁。
- 延迟：JFR归因轮`7,579.267 terminal business ops/s`；取最后5个正式invocation共81,920个PLACE_ORDER样本，open-loop/coordinated-omission corrected。合并log2直方图entry→accepted p50/p90/p95/p99/p99.9=`1.074/2.147/2.147/2.147/2.147s`桶上界、精确max=`1.955s`；accepted→terminal五个百分位均为`33.554ms`桶上界、精确max=`34.697ms`；entry→terminal=`1.074/2.147/2.147/2.147/2.147s`、精确max=`1.977s`。accepted→terminal通过；entry→terminal p99/p99.9失败，max通过。
- 分配失败：独立`-prof gc`轮`9,764.770 terminal business ops/s`、`309.876 MB/s`、`565,456,297.111 B/JMH invocation`，按固定16,384条折算`34,512.713 B/business op`，为10,240门禁的3.37倍；3个measurement合计4次ZGC、854ms profiler GC time。相对PV-18的38,784 B/op下降约11.0%，但仍不可接受。JFR sampled allocation由owner/JMH线程约69.95%、Core Fact约14.84%、四Lane约5.7%、matcher约3.4%构成；top class为`byte[]/long[]/Object[]/ListItr/int[]/Long/TreeMap.Entry/CoreOrderState/OrderRuntime`，top site为`TreeMap.put`、primitive/HashMap扩容、`ByteBuffer.allocate`、list iterator/stream、PLACE decode与`prepareCommitPatch`。
- 残留结构定位：JFR完整栈证明`ActiveOrderIndex.ids(userId,symbol)`仍在每笔`preMatchingSelfTradeCancellations`中创建`TreeSet`并stream遍历；`AdlPositionIndex.add`仍在成交持仓更新时写`TreeMap/TreeSet`。这两处违反本轮“热路径无TreeMap/临时集合”目标，因此继续修复，不把PV-19作为最终实现。
- GC/heap/native：JFR 4次ZGC、20个pause phase，总pause`0.322ms`，p50/p90/p95/p99/max=`0.0126/0.0262/0.0264/0.0326/0.0326ms`，allocation stall、allocation requiring GC、promotion/evacuation failure和OOM均为0。heap committed固定8GiB、used最高2.579GiB，4个post-GC点从60MiB增至754MiB；短记录覆盖初态/JIT/状态建立，不能证明泄漏。结束NMT总reserved/committed=`147,601,275,523/8,797,198,979 B`，DirectBuffer余额为0；无长稳native斜率证据。
- CPU/线程/停顿/I/O：execution samples中owner/JMH worker 777、Core Fact 90、matcher 87、四个Lane合计188；业务热点包括`awaitMatchingResult`、`CoreStateHash.mix`、`commitReadyMatching/completeMatching`、Lane健康检查、`TreeMap`、pending admission ring及client-order capture。monitor/park物化后26个事件、79.3ms，主要是启动/JAR/JFR等待，无持续交易锁热点。Safepoint总pause`2.756ms`、max`0.783ms`，time-to-safepoint max`0.877ms`；owner正式measurement同步file/socket/database I/O为0，`DataLoss=0`、container throttling=0。JFR有1,015个反射/本地库探测异常，主要为`NoSuchFieldException/NoSuchMethodError/UnsatisfiedLinkError`，严格异常门禁失败但未形成业务拒绝或终态错误。
- 环境、长稳与范围：锁定时swap used`2,831.25MiB`，采集后为`3,713.25MiB`，增量约882MiB且物理内存接近满载，环境门禁明确失败，容量/native结果只能作为诊断。吞吐、entry尾延迟、分配和环境前置门禁失败，未运行10分钟长稳，不能声明heap/native/FD/线程/buffer无泄漏。未测其余五产品线及其他业务类型的性能分布，也未启动API/Aeron Cluster、Kafka、数据库、WebSocket或外围projection；没有运行其他in-flight档位。
- artifact：`target/qualification/20260902T092513Z-one-way-lane-256-scale/`，约1.0GiB。主JSON SHA-256 `6c0f32f62ff22ef918562c775fd6f49e93848d77eb5a77a40a00453a03b3b5ec`；JFR profile JSON `fdecfd5026a1de569990cebe0525a88d3d83b00f0672997caf28c576174c9212`；独立GC JSON `292a985466058ed47e67b4e826ff459697ef26e0af5dfd45ea9b1ea7edf33bcd`；原始JFR `3094074b87685f00a7772cb2718a333706faec1cad2a3f1d5a708c9294edd1d6`；聚合分析 `6073a7537fda77589a9e28c063c92abb65f899da9bbc79d23461d5370737684c`。结论为`Lane设计正确性通过；整体性能验收失败/环境无效，继续删除残留热路径结构`。

### 2026-09-02 23:50:18 +08:00 — `PV-20260902-256-20` — `采集前锁定（无逐命令物化、symbol 分区 matcher 与分区局部完成）`

- 被测生产 commit 固定为 `ac6148bd9779ef7e7c32e58ecfe81aaf109b5c11`，分支 `codex/aeron-unified-core`，已推送；对照 commit 为 PV-19 的 `40bb06c7a762c279bea6fe311e9ff705b51fc0c5`，只用于代码影响与同口径诊断，不在采集中途改变参数。修改点：删除逐命令 `RuntimeCommitPatch`、快照、boxed changes、列表/排序与 rolling-hash 对象图；Core Fact 直接消费不可变 frame；pending matcher 改为预分配 O(1) 任意释放环；matcher 按 symbol 稳定路由到独立同步 worker，跨 shard 独立完成、同 shard 保持 evidence 顺序；Account Lane 按 projection-local sequence 串行消费；全局 sequence 仅保留复制身份与连续完成 watermark；snapshot/control 使用显式全 shard fence，snapshot v18 保留精确 outbox reservation。
- 采集前功能证据：Oracle GraalVM Java HotSpot `25.0.1+8.1`、Maven `3.9.16`；默认单 matcher 受影响 reactor 共 `600/600`（product API 12、protocol 80、instrument API 13、service 492、benchmarks 16）通过，4 matcher 功能回归 `93/93` 通过，0 failure/error/skipped；覆盖真实撮合、成交结算、资金/余额/冻结/持仓、订单终态、Core Fact、snapshot corruption/recovery、跨 shard 独立完成与同 shard 顺序。`git diff --check` 通过。未启动 wallet、Docker 或外围服务。
- 固定范围：所有采集严格且仅为 `256 in-flight`；验收固定 `matchingEngines=1`，不采集其他 matcher 数或其他 in-flight。只测 `LINEAR_PERPETUAL` Product Core；10,000 活跃用户、512 listed/active symbol、4 Account Lane、1同步matcher、0 exchange-core risk engine、1 Product Core/JMH owner线程；每用户最多5持仓、10活动订单。其余五产品线只有共享功能/快照测试，不形成性能结论。
- 固定业务和负载：`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`，每 invocation 16,384个 `PLACE_ORDER` terminal business operations，50% maker GTC + 50% taker IOC，同symbol、价格与数量配对；做市状态持续存在。open-loop constant-arrival offered rate `100,000 business operations/s`，计划到达计入entry延迟并修正 coordinated omission；export ACK interval 1,024，直方图1ns–30s，progress timeout 30s。API、Aeron Cluster复制、Kafka、数据库、外部projection、WebSocket和wallet不启动。
- 正确性与计数门禁：必须满足 accepted business=terminal business、accepted Core messages=terminal Core messages、两个unfinished=0、期末backlog=0、最大backlog<=256、producer starvation=0、拒绝/错误/超时=0；同时报告terminal business ops/s、terminal Core messages/s、trades/fills、Lane admission/settlement/总operations。每个fork teardown必须验证用户及做市账户余额/冻结/持仓、活动订单、资金守恒、business/funds hash和snapshot restore；任一失败即本轮失败。
- 吞吐门禁：无 profiler 主轮 `terminal business ops/s >=50,000`，99.9% CI下界 `>=45,000`，3个fork均值分别 `>=45,000`；保存15个measurement、主分数、误差与CI。单 iteration峰值不作为结论。accepted→terminal p99<=50ms、p99.9<=100ms、max<=250ms；entry→terminal p99<=2s、p99.9<=3s、max<=5s，PLACE与TAKER_FILL按JFR业务事件分别报告。
- 分配/GC门禁：独立同参数 `-prof gc` 轮报告allocation rate、B/op、GC次数/时间，`<=10,240 B/business op`；JFR报告TLAB/非TLAB、top class/thread/site。ZGC不得有full GC、allocation stall/OOM，GC总时间<=2%、pause p99<=5ms/max<=10ms。profile数值只归因，不替代无profiler主轮。
- JFR/NMT门禁：保存明确 `owner-commit-profile.jfc` 的原始 `.jfr`、GC/safepoint log、NMT baseline/diff及分析输出；检查并按owner、matcher、4 Lane、Core Fact、外围线程分组报告CPU/墙钟热点、分配、heap/GC、native/Direct/Mapped、线程/锁/park、safepoint/VM op、JIT/deopt/code cache、I/O、异常、系统/container与`DataLoss`。owner正式measurement出现同步文件/网络/数据库I/O，明显CPU干扰/throttling/swapout或JFR DataLoss即环境无效。
- 固定机器/JVM：Intel Core i9-9880H 8C/16T、16GiB、macOS 26.7/Darwin 25.6.0 x86_64、非容器、未绑核；锁定时swap used `2,533.50 MiB`。JVM固定 `-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`，NMT summary；Lane=4、matcher=1、settlement BLOCKING、journal65,536/1GiB、export pending256MiB。swap增量>64MiB、持续page-in/out、Pages throttled非0或明显同机CPU争用则容量/native结论无效。
- JMH/JFR参数：主轮 `5x5s warmup + 5x5s measurement + 3 forks + 1 thread`，每iteration timeout 10分钟；profile轮 `1x3s warmup + 1x10s measurement + fork=0`；阶段间冷却至少30秒。执行 `QUALIFICATION_RUN_ID=20260902T155018Z-partitioned-matcher-256 SURPRISING_JAVA_HOME=/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home SCALE_JMH_WARMUP_ITERATIONS=5 SCALE_JMH_WARMUP_SECONDS=5 SCALE_JMH_MEASUREMENT_ITERATIONS=5 SCALE_JMH_MEASUREMENT_SECONDS=5 SCALE_JMH_FORKS=3 SATURATION_OPERATIONS_PER_INVOCATION=16384 QUALIFICATION_HEAP=8g MATCHING_ENGINES=1 surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh saturation`，再对同一JAR执行独立 `-prof gc`。
- artifact固定为 `target/qualification/20260902T155018Z-partitioned-matcher-256-scale/`。完整命令、Java/Maven/git/机器与swap前后快照、JAR/JFC SHA、JMH JSON/log、原始JFR、GC/NMT、分析结果及SHA/size清单全部保留。只有主吞吐、正确性、尾延迟、分配和环境均通过才执行相同256场景10分钟长稳；否则不运行且不得声称无泄漏。所有成功、失败、中止或环境无效结果只追加到本文件，不覆盖本记录。

### 2026-09-03 00:02:23 +08:00 — `PV-20260902-256-20` — `功能通过；吞吐、分配与环境失败，热路径仍有 Fact Frame/Map/TreeMap 物化`

- 实际范围与正确性：被测生产 commit `ac6148bd9779ef7e7c32e58ecfe81aaf109b5c11`，采集锁定 commit `a6c07ab5`；严格且仅运行 LINEAR_PERPETUAL、`256 in-flight`、4 Account Lane、1 matcher、10,000用户、512 symbol、50% maker GTC/50% taker IOC。采集前默认单matcher `600/600`、4 matcher功能回归`93/93`通过；主轮、JFR轮和独立GC轮均正常teardown，accepted business=terminal business、accepted Core=terminal Core，两个unfinished、拒绝、错误、超时和producer starvation均为0；trades严格为business operations的50%，资金、余额/冻结/持仓、订单终态、business/funds hash和snapshot恢复无异常。
- 主吞吐失败：无profiler `10,350.863 ±2,390.490 terminal business ops/s`，99.9% CI `[7,960.373,12,741.354]`，仅为50,000目标的20.7%；三个fork均值 `7,442.011 / 11,816.146 / 11,794.434`，15个measurement范围`7,062.382–12,549.747/s`，50,000、CI下界45,000和每fork45,000门禁全部失败。terminal Core=`10,360.972/s`、trades=`5,175.432/s`；Lane admission=`10,350.863/s`、settlement=`15,607.161/s`、总operations=`25,958.025/s`，说明单向Lane计数仍成立但系统总体受owner/materialization限制。
- 延迟：JFR归因轮 `8,268.266 terminal business ops/s`，8个正式invocation共131,072个PLACE样本，open-loop且修正coordinated omission。entry→accepted p50/p90/p95/p99/p99.9=`1.074/2.147/2.147/2.147/2.147s`桶上界、精确max=`1.964s`；accepted→terminal=`16.777/33.554/33.554/33.554/67.109ms`、精确max=`35.774ms`；entry→terminal=`1.074/2.147/2.147/2.147/2.147s`、精确max=`1.978s`。accepted→terminal p99通过、p99.9门禁通过但直方图桶上界高于精确max；entry→terminal p99未超过2.147s桶上界而高于2s门限，判失败。
- 分配失败：独立同参数`-prof gc`轮吞吐`10,347.584/s`、allocation rate=`387.833 MB/s`、`667,732,441.778 B/JMH invocation`，按16,384条折算 `40,755.154 B/business op`，为10,240门禁的3.98倍；3个measurement合计4次ZGC、796ms profiler GC time。JFR sampled/weighted估算为`85,485 B/op`，只用于归因；top site依次包括`RuntimeFactFrame.Builder.<init>/recordUser/capturePositionBefore`、`LongObjectHashMap`插入/扩容、`ByteBuffer.allocate`、`HashMap.put/resize`、`TreeMap.put`、`RuntimeFundsDelta`与`ArrayList.grow`。top types为`Object[]/byte[]/long[]/int[]/Long/TreeMap.Entry/ListItr/CoreOrderState/OrderRuntime`，证明此前删除RuntimeCommitPatch后，Fact Frame builder、Core Fact materialize及ADL TreeMap等仍是主要热路径物化。
- CPU/并行度：JFR execution samples按线程为owner/JMH 556、Core Fact 99、matcher 73、四Lane合计158；首要可识别业务热点为`PendingMatchingRing.firstCompletedUnsubmittedPlaceAdmission`（36 samples），其后为`commitReadyMatching`、`completeMatching`、`CoreStateHash.mix`、`awaitMatchingResult/progressPlaceAdmissions`，以及HashMap/TreeMap与client-order capture。说明单matcher不是当前唯一或最大可见瓶颈；owner对pending的反复线性扫描、每笔Fact Frame构造和Core Fact异步物化仍限制吞吐。锁/park共35事件、121ms，多为JAR/JFR/启动等待；owner正式measurement同步I/O为0。
- GC/heap/native/停顿：JFR 4次ZGC、20个pause phase，总pause`0.223ms`，p99/max约`0.0272ms`，无allocation stall、退化或OOM；最长并发phase约204.6ms。heap committed 8GiB，used最高约6.62GiB，4个post-GC点从60MiB增至594MiB；短采样含状态建立，不能证明泄漏。Safepoint总pause`2.771ms`、max`0.758ms`，time-to-safepoint max`0.281ms`；VM operation 121次、总`6.104ms`、最长`2.373ms`。NMT diff、DirectBuffer余额和原始GC log已保存，因未跑长稳不形成泄漏结论。
- JFR契约缺口：原始JFR与绝大多数聚合文件生成成功，但分析脚本最终严格契约校验返回1；aggregate仍完整包含吞吐、三段延迟、分配、heap/GC、线程角色、NMT、锁、safepoint、JIT、I/O和异常。全JVM I/O 7,430事件/约3.58MiB，主要System.out和JAR/native-library加载，owner measurement I/O为0。异常1,033次，主要反射/本地库探测`NoSuchFieldException/NoSuchMethodError/UnsatisfiedLinkError`，严格零异常门禁失败但无业务终态错误。该脚本失败作为证据缺口保留，不重跑或掩盖。
- 环境无效与长稳：swap从锁定时`2,533.50MiB`升至结束`3,724.50MiB`，增量约1,191MiB，远超64MiB门禁；JFR物理内存一度使用约15.95GiB/16GiB，同机有Chrome、WeChat、Codex等进程，Pages throttled为0。容量和native绝对值因此标记环境无效；即便忽略环境，吞吐与分配也明确失败。未运行10分钟长稳，不能声明heap/native/FD/线程/buffer无泄漏。
- artifact：`target/qualification/20260902T155018Z-partitioned-matcher-256-scale/`，约1.2GiB。主JSON SHA-256 `1b72f0f287a4e296172a318b7666ca681c594f878a31aab75bf2444e0acd2b93`；profile JSON `fd766a0cf66d7e87ad0528eaf0d28b3a4eef77d64ea4ea52083d202555e688a8`；GC JSON `d2230eccbc785dd0b76c674441e0dd028ee5938275891ff91d26c7af83bfb806`；原始JFR 150,931,017B、SHA-256 `f8e3713b6f0a4901c498e47bcb2ae107ac2ad3c75ee2c23507fbe926b1e8c6f0`；aggregate SHA-256 `68ed1895e5f0a7f2a5c16a164fe78f5b7eb98ed3f507ef1fc074d066ebde3b0d`；shaded JAR SHA-256 `e228921b1984b9f976a3a2f09a44441a966ae1068acb3eaca213211be567caba`。
- 结论与未测范围：架构正确性与分区功能测试通过，但50k目标没有达到，PV-20整体性能验收失败。下一轮必须优先把pending admission改为事件驱动/O(1) ready queue，复用或预分配Fact Frame/changes，删除Core Fact逐笔状态对象与ADL TreeMap/TreeSet，并减少owner上的hash/身份捕获；在这些热点清零前，增加matcher数量不会解决单matcher验收瓶颈。未测其余五产品线性能、API/Aeron Cluster、Kafka、数据库、WebSocket和外围projection。

### 2026-09-03 09:20:26 +08:00 — `PV-20260903-256-21` — `采集前锁定（事件 ready queue、复用 Fact Frame、无 ADL 有序树）`

- 被测生产 commit 固定为 `7c66b9d561842ce32e150db2dd317cc7147ea928`，分支 `codex/aeron-unified-core`，已推送；对照 commit 为 PV-20 的 `ac6148bd9779ef7e7c32e58ecfe81aaf109b5c11`。本轮修改点固定为：Account Lane 完成后发布预分配 SPSC admission-ready sequence，owner 按 matcher shard 的预分配 intrusive FIFO 做 O(1) 连续推进；预分配并复用 4,096 个 Fact Frame Builder；Core Fact 订单直接从不可变 runtime view 导出、删除中间 `CoreOrderState`；Fact 合并移除 `TreeMap/TreeSet`；ADL 热索引改为 primitive long map/set，仅查询时排序；缓存 topology hash 和空删除身份 slice；持仓 before-image 改为 Lane 独占预分配 capture，禁止 Lane 线程写共享 Builder。
- 采集前功能证据：Oracle GraalVM Java HotSpot `25.0.1+8.1`、Maven `3.9.16`；执行 `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am test`，product API 12、protocol 80、instrument API 13、service 495、benchmarks 16，共 `616/616` 通过，0 failure/error/skipped；包含真实成交、余额/冻结/持仓、资金费、订单终态、Core Fact、索引权威重建、snapshot corruption/recovery 与六产品线共享 snapshot 契约。`git diff --check` 通过，未启动 wallet、Docker 或外围服务。
- 固定范围与场景：严格且仅使用 `256 in-flight`，`matchingEngines=1`、exchange-core risk engine=0、4 Account Lane、Fact Frame pool=4,096；仅 `LINEAR_PERPETUAL` 的 `LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`，10,000 活跃用户、512 listed/active symbol、每用户最多5持仓/10活动订单、1 Product Core/JMH owner线程。每 invocation 16,384 个 PLACE_ORDER business operations，50% maker GTC + 50% taker IOC，同 symbol/价格/数量配对，做市状态持续存在；open-loop constant-arrival offered rate 100,000 business ops/s，计划到达计入 entry latency并修正 coordinated omission；export ACK interval 1,024、histogram 1ns–30s、timeout 30s。
- 正确性与计数门禁：accepted business=terminal business、accepted Core messages=terminal Core messages、两个 unfinished=0、期末 backlog=0、最大 backlog<=256、producer starvation=0、拒绝/错误/超时=0；报告 terminal business ops/s、Core messages/s、trades、Lane admission/settlement/总 operations。每 fork teardown 必须核对用户及做市账户余额、冻结、持仓、活动订单、资金守恒、business/funds hash 和 snapshot restore；任一失败即本轮失败。
- 吞吐与延迟门禁：无 profiler 主轮 terminal business `>=50,000 ops/s`、99.9% CI下界 `>=45,000`、三个 fork 均值各 `>=45,000`。accepted→terminal p99/p99.9/max `<=50/100/250ms`；entry→terminal p99/p99.9/max `<=2/3/5s`；PLACE 与 TAKER_FILL 分别报告三段 p50/p90/p95/p99/p99.9/max、样本数、单位、范围和 timeout。单次峰值不作为结论。
- 分配/GC/JFR门禁：独立同参数 `-prof gc` 轮分配 `<=10,240 B/business op`，报告 allocation rate、B/invocation、GC次数/时间；ZGC不得有 full GC、allocation stall/OOM，GC时间<=2%、pause p99<=5ms/max<=10ms。JFR保存原始文件、GC/safepoint log、NMT baseline/diff、summary/views，按 owner、matcher、4 Lane、Core Fact及外围线程报告 CPU/墙钟、TLAB/非TLAB与 top allocation、heap/live set、native/Direct/Mapped、锁/park/busy-spin、safepoint/VM、JIT/deopt/code cache、I/O/异常及系统/container；DataLoss、owner正式窗口同步文件/网络/DB I/O、明显 swapout/throttling/同机干扰均使对应结论失败或无效。短 JFR 不证明无泄漏。
- 固定机器/JVM：Intel Core i9-9880H 8C/16T、16GiB、macOS 26.7/Darwin 25.6 x86_64、非容器、未绑核；锁定时 swap used `2,681.50MiB`。Oracle GraalVM Java HotSpot 25.0.1，固定 `-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`、NMT summary及脚本模块开放参数；Lane settlement BLOCKING、journal 65,536/1GiB、export pending 256MiB。JFC SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。
- 阶段、命令与有效性：主轮 `5x5s warmup + 5x5s measurement + 3 forks + 1 thread`；JFR归因 `1x3s warmup + 1x10s measurement + fork=0`；独立GC轮 `5x5s warmup + 3x5s measurement + 1 fork -prof gc`；阶段间至少30秒冷却。执行 `QUALIFICATION_RUN_ID=20260903T012026Z-ready-frame-256 SURPRISING_JAVA_HOME=/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home SCALE_JMH_WARMUP_ITERATIONS=5 SCALE_JMH_WARMUP_SECONDS=5 SCALE_JMH_MEASUREMENT_ITERATIONS=5 SCALE_JMH_MEASUREMENT_SECONDS=5 SCALE_JMH_FORKS=3 SATURATION_OPERATIONS_PER_INVOCATION=16384 QUALIFICATION_HEAP=8g MATCHING_ENGINES=1 surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh saturation`，再对同一 JAR/场景执行独立 `-prof gc`。artifact 固定为 `target/qualification/20260903T012026Z-ready-frame-256-scale/`。源码、参数、JDK、JFC和场景锁定后不修改；失败/中止/无效数据也必须追加。只有吞吐、正确性、延迟、分配和环境均通过才执行同场景10分钟长稳，否则不运行且不得声明无泄漏。

### 2026-09-03 09:35:11 +08:00 — `PV-20260903-256-21` — `功能闭环；吞吐失败且环境无效，定位 Frame monitor 回退`

- 被测生产 commit `7c66b9d561842ce32e150db2dd317cc7147ea928`，采集锁定 commit `d868a96d`；严格且仅为256 in-flight、1 matcher、4 Lane、10,000用户、512 symbol。主轮 accepted business=terminal business、accepted Core=terminal Core、两个unfinished及拒绝/错误/超时/starvation均为0；资金、冻结、持仓、订单终态、hash与snapshot teardown通过。
- 主吞吐 `7,303.333 ±1,064.999 terminal business ops/s`，99.9% CI `[6,238.334,8,368.332]`，15个measurement范围`6,158.924–9,044.578/s`；Core messages `7,310.465/s`、trades `3,651.667/s`、Lane admission/settlement/总计 `7,303.333/11,012.057/18,315.390 ops/s`。50k门禁失败，且低于PV-20同口径10,350.863/s。
- JFR归因轮 `5,870.363 terminal business ops/s`，`DataLoss=0`、owner同步I/O=0、ZGC allocation stall=0，GC pause p99/max `0.029831ms`。JFR记录131个 monitor-enter；热点栈明确为 `PreparedFactFrame.materialize/visitTerminalValues`，证明新加的对象级 `synchronized` 让 owner 与 Core Fact materializer 串行竞争，是本轮代码回退。原始JFR 130MiB，SHA-256 `1c554fbb2f31b972833162d3aaa971c38726fa33bf07e7664e825e3387c81a4c`；主JSON SHA `69429c8a4b3e8bcd957792d0c104e5c0366311372aa8acfad478626da4f50951`。
- 环境从锁定2,681.50MiB swap升至4,461.75MiB，增量1,780.25MiB，容量/native结论无效。因吞吐与环境前置门禁失败且用户中断后要求删除反射方案，本轮未补独立GC轮、未跑长稳，不能声明分配或泄漏通过。artifact为 `target/qualification/20260903T012026Z-ready-frame-256-scale/`；本轮如实判定失败，随后已删除 monitor，且未采用任何反射 API。

### 2026-09-03 09:35:11 +08:00 — `PV-20260903-256-22` — `采集前锁定（无 monitor/无反射的预分配 Frame 生命周期）`

- 被测生产 commit 固定为 `e8155067312402de5543a1c0b1bff200583e42f8`，已推送；对照为PV-21生产commit `7c66b9d561842ce32e150db2dd317cc7147ea928`。唯一新增修改是删除 `PreparedFactFrame` 全部 `synchronized`，没有 `MethodHandles`、`VarHandle`、reflection或FieldUpdater；每个预分配 Builder 内含一个同样预分配的 `AtomicInteger` 状态字，owner/materializer各置完成位，最后完成者回池，不增加逐命令原子对象。HotSpot25完整受影响reactor `616/616`通过。
- 本轮继承PV-21全部固定场景、256 in-flight、单matcher、4 Lane、4,096 Frame pool、业务比例、正确性、50k吞吐、尾延迟、10,240 B/op、GC/JFR/NMT门禁和机器/JVM参数，不作任何变更。锁定时swap used `4,461.75MiB`，已预判环境容量结论无效，但仍采集同口径诊断以验证 monitor 回退是否消除；不得把环境无效数据宣称为生产容量。
- 主/JFR命令与PV-21相同，仅 `QUALIFICATION_RUN_ID=20260903T013511Z-lockfree-frame-256`，artifact固定为 `target/qualification/20260903T013511Z-lockfree-frame-256-scale/`；主轮5x5s+5x5s、3 forks，JFR轮1x3s+1x10s，独立GC轮5x5s+3x5s、1 fork，均仅256。失败数据照常追加；吞吐或环境不通过则不跑10分钟长稳。

### 2026-09-03 09:46:38 +08:00 — `PV-20260903-256-22` — `Frame monitor已消除；吞吐、正确性与环境门禁失败`

- 主轮汇总14个有效measurement为 `10,622.171 ±1,750.107 terminal business ops/s`，99.9% CI `[8,872.064,12,372.278]`，范围`7,559.910–12,529.969/s`；terminal Core messages `10,632.544/s`、trades `5,311.086/s`、Lane admission/settlement/总计 `10,622.171/16,016.242/26,638.413 ops/s`。相对PV-21的7,303.333/s提高45.44%，但50k及CI门禁失败。
- 15个measurement中的1个teardown失败，因此JMH只汇总14个：16,384个business operations、16,400个Core messages、8,192笔trade、Lane队列与in-flight均完全清零、资金守恒，但活动订单由49,995变为49,996。根因是saturation成交价100与恢复fixture的价格带重合，某个IOC可吃到fixture遗留单并留下本轮配对GTC；这是基准隔离缺陷，不能将本轮认定为业务正确性通过。后续将压力成交价移到fixture从不使用的独立价位并重新锁定采集。
- JFR归因轮为`8,245.926 terminal business ops/s`，accepted=terminal、unfinished/reject/error/timeout均为0。`PreparedFactFrame`/`RuntimeFactFrame` monitor事件为0；21个JavaMonitorEnter全部来自JAR/JFR初始化及一次symbol注册，证明PV-21的Frame串行锁已消除。实现不包含`MethodHandles`、`VarHandle`、reflection、FieldUpdater或业务`Unsafe`调用；JMH/Chronicle自身启动日志中的Unsafe警告不来自本次代码。
- JFR `DataLoss=0`、owner正式窗口同步I/O=0、4次ZGC、allocation stall/failure=0，pause p99/max=`0.029939ms`；sample-weight估算约`70,466 B/business op`，仍远高于10,240门禁，top allocation仍为primitive map扩容、`TreeMap.put`、`HashMap.putVal`、`RuntimeFundsDelta`、`ArrayList`、`ByteBuffer.allocate`和PLACE decode。独立`-prof gc`因正确性/吞吐前置门禁失败未执行，sample-weight不能替代精确分配结论。
- 锁定时swap used 4,461.75MiB，结束后5,181.00MiB，继续增加719.25MiB；环境门禁失败。未跑10分钟长稳，不能声明无泄漏。原始JFR 145MiB，SHA-256 `ac74512c287381134942d86674e32dd1b003c216501a255429654e45d5bcb7ae`；主JSON SHA-256 `a8b3d8286dd54a2e403122ffb485a8f8837289ae8dab563f8ce71a05777c1709`；artifact为`target/qualification/20260903T013511Z-lockfree-frame-256-scale/`。

### 2026-09-03 09:46:38 +08:00 — `PV-20260903-256-23` — `采集前锁定（独立成交价的无锁无反射Frame）`

- 被测commit固定为`b0147d969764623141131f355c6e0746c5407279`，对照为PV-22的`e8155067312402de5543a1c0b1bff200583e42f8`；生产实现不变，唯一变化是把saturation maker/taker成交价从fixture使用的100移到fixture从不使用的1,000，避免IOC消耗恢复快照中的订单。本轮继续验证无monitor、无反射、预分配Builder生命周期。
- 继承PV-22的全部标准、机器/JVM、正确性、吞吐、延迟、分配、GC/JFR/NMT和有效性门禁；严格且仅为256 in-flight、1 matcher、4 Account Lane、4,096 Frame pool、10,000用户、512 listed/active symbol、每invocation 16,384个PLACE（50% maker GTC、50% taker IOC）、100,000 offered ops/s。主轮5x5s+5x5s、3 forks；JFR轮1x3s+1x10s；独立GC轮5x5s+3x5s、1 fork；阶段间至少30秒。
- 锁定时swap used `5,181.00MiB`，环境容量结论预判无效，但仍执行同口径诊断确认订单隔离与Frame生命周期；不得宣称生产容量。命令沿用PV-22，仅`QUALIFICATION_RUN_ID=20260903T014638Z-isolated-price-256`，artifact固定为`target/qualification/20260903T014638Z-isolated-price-256-scale/`。任何失败照实追加；前置门禁失败则不跑10分钟长稳。

### 2026-09-03 09:59:00 +08:00 — `PV-20260903-256-23` — `功能与Frame无锁通过；吞吐、分配、异常和环境门禁失败`

- 采集前在Oracle GraalVM Java HotSpot 25.0.1上重新执行完整受影响reactor：Product API 12、protocol 80、instrument API 13、service 495、benchmarks 16，共`616/616`通过，0 failure/error/skipped。主轮15/15个measurement全部完成且teardown通过：accepted business=terminal business、accepted Core=terminal Core、两个unfinished、拒绝、错误、超时和producer starvation均为0；期末in-flight、Lane队列、matching pending与ready queue均为0，资金、冻结、持仓、活动订单、hash和snapshot恢复闭合。独立成交价修复了PV-22的fixture订单污染。
- 无profiler主轮为`11,925.472 ±662.274 terminal business ops/s`，99.9% CI `[11,263.198,12,587.746]`，15个measurement范围`10,858.160–12,887.736/s`；terminal Core messages `11,937.118/s`、trades `5,962.736/s`、Lane admission/settlement/总计`11,925.472/17,981.376/29,906.848 ops/s`。较PV-21的7,303.333/s提高63.29%，但只达到50k目标的23.85%，吞吐门禁明确失败。
- JFR归因轮为`8,780.306 terminal business ops/s`，131,072个延迟样本；accepted→terminal p50/p90/p95/p99/p99.9均落在16.78/33.55/33.55/33.55/33.55ms的log2桶，max34.441ms；entry→terminal p50/p90/p95/p99/p99.9约1.074/2.147/2.147/2.147/2.147s桶，max1.910s，入口尾延迟门禁失败。`PreparedFactFrame`和`RuntimeFactFrame` monitor事件仍为0；36个lock/park事件来自JAR/JFR初始化、symbol注册及外围线程，不存在PV-21的Frame互斥热点。
- 独立`-prof gc`轮为`10,862.486 terminal business ops/s`，allocation rate`310.080 MB/s`、`508,126,795.556 B/JMH invocation`，按固定16,384条折算`31,013.598 B/business op`，为10,240门禁的3.03倍；4次并发ZGC、profiler累计GC time594ms。JFR sample-weight估算69,593 B/op只用于归因；top allocation为`ByteBuffer.allocate`、primitive `LongObjectHashMap`插入/扩容、`TreeMap.put`、`HashMap.putVal`、列表增长、PLACE decode、`prepareFactFrame`和`RuntimeFundsDelta`，owner/JMH线程与Core Fact materializer仍是主要分配方。
- JFR `DataLoss=0`、owner正式窗口同步I/O=0、4次ZGC且allocation stall/failure=0，pause p99/max=`0.013150ms`；heap used max约2.402GiB，短记录post-GC used从64MiB增至642MiB，不能据此声明泄漏。JFR记录1,033个由JDK/依赖探测产生的异常（主要NoSuchFieldException/NoSuchMethodError）；虽本次业务修改文件确认不含reflection、MethodHandles、VarHandle、FieldUpdater、Unsafe或`synchronized`，但零异常门禁仍按标准判失败。
- swap从锁定5,181.00MiB增至5,981.75MiB，增加800.75MiB，环境无效；吞吐/分配/环境前置门禁失败，未跑10分钟长稳，不能声明生产容量或无泄漏。原始JFR 145MiB，SHA-256 `f4ed296aff7e27ea9f1f5513151496496370ea4527105cf9cd14e807e2e48361`；主JSON SHA `f0babd4f175fda8746834d72961a7570ee6c1706726680030e72a342a6ea2124`；GC JSON SHA `390ed81910010c4bf0e06776a5d500f1f192bc4e15e8cb32a0e74be11471e789`。artifact为`target/qualification/20260903T014638Z-isolated-price-256-scale/`。

### 2026-09-03 10:36:25 +08:00 — `PV-20260903-256-24` — `采集前锁定（重复解码与临时容器收敛）`

- 被测生产 commit 固定为 `ad39d486260c9802c5c2009a7199d826cb3e09c0`，分支 `codex/aeron-unified-core`，已推送；代码对照为 `b0147d969764623141131f355c6e0746c5407279`。本轮修改点固定为：matching admission、batch decode 和 pending 创建在同一命令生命周期只解码一次并复用 `DecodedMatchingCommand`；`OpenInterestIndex`、identity allocation reverse index 和 liquidation asset index 改为 primitive map；`RuntimeFundsDelta` 只构造一次并在 owner/materializer 间复用，可信 distinct postings 不再复制；删除重复 funds-posting derivation、无调用的 changed-lane `HashSet`，Fact fragment/owner group 使用不可变视图和精确容量，保留必要的业务快照顺序及失败语义。
- 采集前验证已完成：Oracle GraalVM Java HotSpot `25.0.1+8.1`、Maven `3.9.16`；`mvn -pl surprising-aeron-core/surprising-aeron-service -am test` 为 `495/495` 通过，`mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am -DskipTests clean package` 成功，`git diff --check` 通过。未启动 wallet、Docker 或外围服务；工作区仅保留既有 untracked `openai` 与三个 `.factorypath`，不进入构建。
- 固定范围与业务场景：严格且仅 `256 in-flight`、`matchingEngines=1`、4 Account Lane、LINEAR_PERPETUAL；10,000 活跃用户、512 listed/active symbols、每用户最多5持仓/10活动订单、1 Product Core risk engine、0 exchange-core risk engine、做市持续运行。每 invocation 16,384 个 PLACE_ORDER，50% maker GTC + 50% taker IOC，成交价使用与恢复 fixture 隔离的 1,000；open-loop offered `100,000 terminal business ops/s`，计划到达计入 entry latency 并修正 coordinated omission；ACK interval 1,024；histogram `1ns–30s`，timeout 30s。
- 正确性门禁：accepted business=terminal business、accepted Core messages=terminal Core messages、unfinished/rejected/error/timeout/starvation=0、期末 in-flight/matching/Lane backlog=0、terminal trades 为 business operations 的50%；teardown 通过用户/做市资金、余额/冻结、持仓、活动与终态订单、business/funds hash、snapshot restore。必须报告 admission/settlement/total Lane operations、backlog、fills/trades、批次计数和三段业务延迟。
- 吞吐/延迟门禁：无 profiler 主轮 `5x5s warmup + 5x5s measurement + 3 forks + 1 thread`，terminal business `>=50,000 ops/s`，99.9% CI 下界 `>=45,000`，每 fork 均值 `>=45,000`；JFR 归因 `1x3s + 1x10s + fork=0`；独立 GC `5x5s + 3x5s + 1 fork -prof gc`。accepted→terminal p99/p99.9/max `<=50/100/250ms`，entry→terminal p99/p99.9/max `<=2/3/5s`。
- 分配/资源/JFR 门禁：独立 `-prof gc` `<=10,240 B/terminal business op`；ZGC allocation stall/OOM/allocation requiring GC/promotion failure=0，pause p99<=5ms/max<=10ms；保存原始 JFR、`jfr summary/view`、GC/safepoint、NMT baseline/diff，按 owner、matcher、4 Lane、Core Fact、projection 和外围线程报告 CPU、分配、heap/native/Direct/Mapped、锁/park、safepoint、JIT、I/O、异常。DataLoss、owner 正式窗口同步业务 I/O、container throttling、明显 swap/page-out 均使对应结论失败/无效；短 JFR 不证明无泄漏。
- 固定环境/JVM：Intel Core i9-9880H 8C/16T、16GiB、macOS 26.7/Darwin 25.6 x86_64、非容器、未绑核；Oracle GraalVM Java HotSpot 25.0.1；JVM `-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`，settlement wait `BLOCKING`，journal 65,536/1GiB，export pending 256MiB；JFC SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。锁定时 swap used `4097.00MiB`，已预判环境容量/native 结论无效，但仍按同口径采集诊断；不擅自终止用户进程。
- 采集命令与 artifact 在开始前锁定：`QUALIFICATION_RUN_ID=20260903T103625Z-duplicate-container-256 SURPRISING_JAVA_HOME=/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home SCALE_JMH_WARMUP_ITERATIONS=5 SCALE_JMH_WARMUP_SECONDS=5 SCALE_JMH_MEASUREMENT_ITERATIONS=5 SCALE_JMH_MEASUREMENT_SECONDS=5 SCALE_JMH_FORKS=3 SATURATION_OPERATIONS_PER_INVOCATION=16384 QUALIFICATION_HEAP=8g MATCHING_ENGINES=1 surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh saturation`；同一 shaded JAR/JVM/参数再执行 `-prof gc`，artifact 固定 `target/qualification/20260903T103625Z-duplicate-container-256-scale/`。所有结果、失败、中止、原始 artifact 路径、大小和 SHA-256 只追加本文件；仅当主吞吐、正确性、延迟、分配和环境全部通过才执行10分钟长稳，否则不运行且不声明无泄漏。

### 2026-09-03 10:45:00 +08:00 — `PV-20260903-256-24` — `重复解码/临时容器减少；吞吐与基准正确性门禁失败`

- 实际采集：被测 commit `ad39d486260c9802c5c2009a7199d826cb3e09c0`，严格固定 `256 in-flight`、4 Account Lane、1 matcher、10,000 用户、512 symbol、16,384 PLACE/invocation、50% maker GTC + 50% taker IOC、100,000 offered ops/s。主轮按锁定的 `5x5s + 5x5s + 3 forks` 执行；因第5个 measurement invocation 的 teardown active-order 不变量失败，JMH保留13个有效 invocation，不能把主轮当作正确性通过。
- 主轮业务数据：terminal/accepted business `9,760.756 ops/s`，terminal/accepted Core `9,770.288 messages/s`，trades `4,880.378/s`；15个 measurement 业务吞吐范围 `7,745.768–11,737.258/s`，99.9% CI `[8,059.543,11,461.968]`，远低于 50,000 目标与 45,000 CI 门禁。Lane command/admission `9,760.756/s`，settlement `14,717.390/s`，Lane total `24,478.145/s`；query/risk/reject/error/timeout/unfinished/starvation 均为0，matching window/full-window/refill均有样本。
- 正确性异常：三个主轮 fork 和独立 GC 轮均出现 `saturation workload invariant failed`，业务/资金/成交计数闭合（16,384/16,384 business、16,400/16,400 Core、8,192/8,192 trades、资金 `11,536,000,000,125` 守恒），但恢复 fixture 的 active order 数分别出现 `49996/50000`、`49999/50001` 和 `49997/49999`。这说明在高负载多 invocation 下仍存在订单簿终态漂移/测试夹具时序问题；不能以“资金守恒”掩盖订单终态失败，下一轮需先隔离并修复该基准正确性问题。
- JFR/延迟：原始 JFR 45 秒、`DataLoss=0`；8个 workload events 共131,072 samples，entry→accepted p50/p90/p95/p99/p99.9=`1.074/2.147/2.147/2.147/2.147s`桶上界、max `2.079558172s`；accepted→terminal=`16.777/33.554/33.554/33.554/33.554ms`、max `37.414991ms`；entry→terminal=`1.074/2.147/2.147/2.147/2.147s`、max `2.094157263s`。accepted→terminal门禁通过，entry→terminal p99/p99.9超过锁定阈值。JFR top stack 仍出现 `CoreProbeState.progressPlaceAdmissions/commitReadyMatching`、`TreeMap.put/getEntry/successor`、`HashMap.getNode` 和 primitive map probing。
- GC/分配：独立 `-prof gc` 仅2个 measurement因第三次 teardown失败，terminal `8,958.970/s`，allocation rate `238.144 MB/s`，`gc.alloc.rate.norm=457,297,683 B/JMH invocation`，按16,384 business ops折算约 `27,911 B/business op`，超过 `10,240 B/op` 门禁约2.73倍；ZGC profiler计数约0（该轮数据仅作归因，不能替代完整正确性轮）。JFR summary含 `ObjectAllocationSample=32,330`、`ObjectAllocationInNewTLAB=31,173`、`ObjectAllocationOutsideTLAB=2,988`、`ThreadAllocationStatistics=868`；短JFR不作无泄漏结论。
- JFR/资源：JFR aggregate GC 4次，pause p50/p90/p95/p99/max=`0.010453/0.011732/0.013436/0.018850/0.018850ms`，owner正式窗口同步I/O `0 events/0 B`，但异常 `1,033`（主要依赖/JDK探测）违反零异常门禁；全JVM包含初始化文件与进程采样。heap约8GiB committed、短记录只覆盖状态建立；NMT/Direct/线程长稳无完整证据。锁定 swap `4,097MiB`，采集结束约 `4,801.5MiB`，增加约704.5MiB，环境容量/native结论无效。
- Artifact：`target/qualification/20260903T103625Z-duplicate-container-256-scale/`；主 JSON `31,459 B` SHA-256 `3555d5535a93cc7953e078cfd69ce6e9eebbd7923e98cb747f46e9e0b3d2b19e`；JFR profile JSON `21,072 B` SHA-256 `5196d5ad0aefde5027a876e6d72200a633ba346c77de39859cfcd5a508534c91`；GC JSON `23,901 B` SHA-256 `aebec44081a43d1519917f474d79321695e90dc5f522b71f0310b43c59cf0660`；原始 JFR `152,675,201 B` SHA-256 `65df5572b672e66a1cbffdc73efbe974e701e37f5f420ed2d7efca4ac9224dcc`；JFR aggregate `9,490,475 B` SHA-256 `cb9a9853ca0c5e33b5ae950709e587840ad5febad63d6a8f2d420f541e855a70`；`jfr summary` 保存于分析目录，包含完整事件计数。未执行10分钟长稳；未测其余五产品线、API/Aeron Cluster、Kafka、数据库、WebSocket和外围 projection。结论：代码结构优化已验证编译/单元测试通过，但本轮性能与基准终态门禁失败，不能宣称达到50k或完成生产验收。

### 2026-09-03 11:21:43 +08:00 — `PV-20260903-256-25` — `采集前锁定（去除运行时有序容器与 boxed ID）`

- 被测生产 commit 固定为 `5ca5756de522643e5a688c723eed34df933c2074`，对照 commit 为 `ad39d486260c9802c5c2009a7199d826cb3e09c0`；分支 `codex/aeron-unified-core`。改动固定为：Account Lane 的 algo/trigger numeric indexes 改用 primitive `LongObjectHashMap`；RuntimeProjectionState 的可变投影改用 `HashMap`，只在冻结/导出边界排序；FundsDelta 删除正常路径的 TreeMap/TreeSet 及重复资产 union；ActiveOrderIndex 为 settlement 提供 primitive descending cursor，并复用空查询集合；CoreExportState 去除 terminal ID stream 临时对象。未改变协议、业务语义、失败恢复或产品线边界。
- 采集前功能门禁已完成：Oracle GraalVM Java HotSpot `25.0.1+8.1`、Maven `3.9.16`；`mvn -pl surprising-aeron-core/surprising-aeron-service -am test` 为 `495/495`，新增 ActiveOrderIndex primitive cursor 测试通过；`mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am -DskipTests package` 成功；`git diff --check` 通过。工作区既有 untracked `openai` 与三个 `.factorypath` 不进入构建/提交。
- 固定范围与场景严格继承 PV-24：仅 `LINEAR_PERPETUAL`、`256 in-flight`、1 matcher、4 Account Lane、10,000 活跃用户、512 listed/active symbols、每用户5持仓/10活动订单、1 Product Core risk engine、16,384 PLACE/invocation、50% maker GTC + 50% taker IOC、成交价1,000、100,000 offered terminal business ops/s、ACK 1,024、做市持续运行；不运行其他 in-flight 档位及其他五产品线/外围服务。
- 正确性与吞吐门禁保持不变：accepted=terminal、Core messages 相等、unfinished/reject/error/timeout/starvation=0、期末 matching/Lane/in-flight backlog=0、trades=50%、资金/冻结/持仓/订单终态/hash/snapshot recovery 闭合；主轮 `5x5s warmup + 5x5s measurement + 3 forks + 1 thread`，JFR `1x3s + 1x10s + fork=0`，独立 GC `5x5s + 3x5s + 1 fork -prof gc`；主吞吐目标 `>=50,000 terminal business ops/s`，99.9% CI 下界 `>=45,000`，独立 GC `<=10,240 B/business op`。
- 固定 JVM/资源与 artifact：HotSpot JDK25、ZGC、`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`，固定 JFC `owner-commit-profile.jfc`（SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`）；锁定机器 MacBookPro16,1 / 16 CPU / 16 GiB，swap used `4,150.25MiB`，已预判环境容量结论可能无效。artifact 固定为 `target/qualification/20260903T112143Z-container-prune-256-scale/`，命令为 `QUALIFICATION_RUN_ID=20260903T112143Z-container-prune-256 SURPRISING_JAVA_HOME=/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home SCALE_JMH_WARMUP_ITERATIONS=5 SCALE_JMH_WARMUP_SECONDS=5 SCALE_JMH_MEASUREMENT_ITERATIONS=5 SCALE_JMH_MEASUREMENT_SECONDS=5 SCALE_JMH_FORKS=3 SATURATION_OPERATIONS_PER_INVOCATION=16384 QUALIFICATION_HEAP=8g MATCHING_ENGINES=1 surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh saturation`；同一 shaded JAR/场景另执行独立 `-prof gc`。锁定后标准、场景和命令不再修改；失败/无效数据照实追加，吞吐或环境未通过不运行长稳。

### 2026-09-03 11:28:00 +08:00 — `PV-20260903-256-25` — `容器收敛采集结果：吞吐/分配/异常/环境门禁失败`

- 主轮实际结果：15/15 个 measurement 完成，accepted=terminal business `11,730.723/s`，accepted=terminal Core `11,742.178/s`，trades `5,865.361/s`，Lane command `11,730.723/s`、settlement `17,687.730/s`、total `29,418.453/s`；unfinished/reject/error/timeout/starvation 均为0，16,384 business、16,400 Core、8,192 trades 及资金/冻结/持仓/订单终态/hash/snapshot teardown闭合。主 JMH `terminal business ops/s` `11,730.723 ± 838.319`，99.9% CI `[10,892.404,12,569.041]`，范围 `10,507.744–12,948.940/s`，远低于 50k 与45k CI门禁。
- 独立 `-prof gc` 已按锁定参数补跑：`12,630.188 ± 9,597.068 terminal business ops/s`（3 measurement，因单 fork误差不具横向稳定性），allocation `338.215 MB/s`、`477,958,046.667 B/JMH invocation`，固定16,384 business折算 `29,171.753 B/business op`，约为10,240门禁的2.85倍；GC `10` 次、总 `1,200ms`，因此分配门禁失败，不能用该轮替代无 profiler 主结果。
- JFR 归因：原始 `saturation.jfr` 151,827,269 B，记录约43s，`DataLoss=0`；aggregate sampled allocation `8,750,198,424 B`、`66,758.716 B/terminal business op`（仅采样权重）。top sites 仍包括 `LongObjectHashMap.addKeyValueAtIndex` 1,433 samples、`TreeMap.put` 1,195、`ArrayList.add` 861、`ByteBuffer.allocate` 858、`HashMap.putVal` 632、`LongObjectHashMap.allocateTable` 539、`TradingRuntimeState.prepareFactFrame` 523、`RuntimeFundsDelta.<init>` 324；top types 为 byte[]、long[]、Object[]、int[]、TreeMap.Entry、ListItr、Long、CoreOrderState/OrderRuntime。说明本轮减少的容器已生效，但剩余 Core Fact/编码/扩容/有序边界仍是主要分配源。
- JFR 延迟与资源：131,072 PLACE samples，accepted→terminal p50/p90/p95/p99/p99.9 为 `16.777/33.554/33.554/33.554/33.554ms` 桶上界，max `32.881ms`；entry→terminal p50/p90/p95/p99/p99.9 为 `1.074/2.147/2.147/2.147/2.147s` 桶上界，max `1.945s`，入口尾延迟门禁失败。owner同步业务I/O `0 events/0 B`；异常 `1,036`（主要 JDK/依赖探测）违反零异常门禁。ZGC 4 collections，pause p99/max `0.027661ms`，allocation stall/failure=0；heap committed 8GiB、used max约2.577GiB，after-GC live set从60MiB增至648MiB，短JFR不能证明无泄漏。Safepoint最长约162.848ms time-to-safepoint，需后续单独解释。
- 环境与 artifact：锁定 swap `4,150.25MiB`，采集后 `4,484.50MiB`，增加 `334.25MiB`，环境容量结论无效；未执行10分钟长稳，未测其他五产品线、API/Aeron Cluster、Kafka、数据库、WebSocket和外围 projection。主 JSON `33,002 B` SHA-256 `d916eb0e8f63c9fc3b191df06e98de283b212ecfcffac4b8c3e4dab9367a64cc`；JFR profile JSON `21,064 B` SHA-256 `fe1300b494b458a83934750e2ce18c9c423e19bb3d15ed4dbe51b7d05fa4795f`；GC JSON `26,127 B` SHA-256 `db484f064d13b0853592683760c4c930db111a5c0b592fb912cfcd702a1a0852`；原始 JFR SHA-256 `71513f11f120b555ebcf304f22babdb6abb8466a682406362da82a5f5745b948`；aggregate SHA-256 `3612672ff45a42edf83188083e54d101eef35b9777e24c6d9ada987fa4e07efa`。结论：本轮功能与终态正确性通过，结构优化未达到50k且分配/环境门禁失败，仍为诊断数据。
### 2026-09-03 11:38:18 +08:00 — `PV-20260903-256-26` — `采集前锁定（单 matcher 速率阶梯诊断）`

- 诊断问题：在当前 `matchingEngines=1`、4 Account Lane、512 listed/active symbol、固定 `256 in-flight` 下，逐步提高 `targetOperationsPerSecond`，找出 terminal business ops/s 的持续平台区间；本轮只作速率阶梯诊断，不把结果宣称为生产容量或正式验收。
- 固定场景：仅 `LINEAR_PERPETUAL`；10,000 活跃用户、512 symbol、每用户最多5持仓/10活动订单、1 Product Core/JMH owner线程、0 exchange-core risk engine；每 invocation 16,384 个 PLACE_ORDER，50% maker GTC + 50% taker IOC，同 symbol/价格/数量配对，成交价1,000，做市状态持续存在；open-loop constant-arrival，计划到达计入 entry latency 并修正 coordinated omission。
- 固定约束：严格且仅 `256 in-flight`、`matchingEngines=1`、4 Account Lane、HotSpot JDK 25、ZGC、与 PV-25 相同的 JVM/settlement/journal/export 参数；阶梯 offered rate 固定为 `5,000/8,000/10,000/12,000/15,000/20,000/30,000/40,000 business ops/s`，按顺序串行执行，不并行运行。
- 每档执行口径：同一 shaded benchmark JAR，`3x3s warmup + 3x5s measurement + 1 fork + 1 thread`；记录 primary terminal business ops/s、terminal Core messages/s、trades/s、accepted/terminal、unfinished、backlog、producer starvation、延迟及错误/超时。最高可持续值定义为 terminal 吞吐不再随 offered rate 增长且所有正确性门禁通过的最高档；短阶梯不证明无泄漏。
- 有效性：采集前 swap 已为 `4,452.50MiB`，本轮环境容量结论预先标记无效；如出现继续 swap/page-out、CPU throttling、JFR DataLoss 或基准终态错误，仍保留结果用于诊断但不得作容量结论。暂不跑长稳、GC/JFR归因或其他 matcher 数；若阶梯确定候选平台，再新建记录锁定长稳/JFR标准。
- 命令与 artifact：每档使用 `java -jar surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`，显式传入上述固定参数及对应 `-p targetOperationsPerSecond=<rate>`；artifact 固定为 `target/qualification/20260903T113818Z-rate-ladder-256-diagnostic/`。本条锁定后不修改；结果、失败和环境异常只追加本记录。

#### 采集结果

- 重新打包成功；被测 HEAD 为 `81c23fd037678327c0efe4246572fe026d1bcec1`，HotSpot JDK `25.0.1`、Maven `3.9.16`；shaded JAR SHA-256：`0b417f8e22b792cec40b1bdbfb8c8d839bfccfeed03ff25baaa765dbdddc4bfb`。
- `5,000` offered business ops/s：terminal business `4,928.044/s`，terminal Core `4,932.857/s`，trades `2,464.022/s`；accepted=terminal、unfinished/reject/error/timeout/starvation 均为0。
- `8,000` offered business ops/s：terminal business `7,810.917/s`，terminal Core `7,818.545/s`，trades `3,905.459/s`；accepted=terminal、unfinished/reject/error/timeout/starvation 均为0。
- `10,000` offered business ops/s：terminal business `9,729.512/s`，terminal Core `9,739.013/s`，trades `4,864.756/s`；accepted=terminal、unfinished/reject/error/timeout/starvation 均为0。
- `12,000` offered business ops/s：terminal business `10,127.474 ± 10,423.697/s`，单档3个 measurement 范围 `9,631.013–10,751.995/s`；terminal Core `10,137.364/s`，trades `5,063.737/s`；accepted=terminal、unfinished/reject/error/timeout/starvation 均为0。该档波动很大，不能作为稳定容量值。
- 各成功档 `matchingFullWindowSamples / matchingWindowSamples` 均约 `81.25%`，producer starvation 为0；该指标是单 matcher 的全局窗口，不是多个 shard 的逐 shard 利用率。
- `15,000` offered business ops/s：基准 teardown 失败，`activeOrders=49,998/49,999`，虽然 `16,384/16,384` business、`16,400/16,400` Core、`8,192/8,192` trades 和 funds invariant 闭合，但订单终态不一致，JMH 返回失败；因此不采纳该档吞吐。`20,000` 档在发现该错误后中止，仅留下空 JSON，不作为结果。
- 环境异常：swap 从采集前 `4,452.50MiB` 增至采集后 `5,379.75MiB`，增加约 `927.25MiB`；本轮机器容量结论无效。未执行 GC/JFR 归因和长稳，不得据此声明无泄漏或生产容量。
- artifact：`target/qualification/20260903T113818Z-rate-ladder-256-diagnostic/`；JSON SHA-256 依次为 `5000=00cb74b3adee9b2f09638bab071d07de194726117e6c8ea1d99a2d9fe3d6fa43`、`8000=3d93dd45c8bb87469d94d548e084186120e697ed039ad1f80343a4041309c5f2`、`10000=998e2b1535c2401e26b61cfd353bac7524c41ff58c273fe0fe02ea280cdd2619`、`12000=5641aa10fdebc6970373d3407a7891c43cf764022b6813074e0ec8c8e96f41f3`。结论：在当前单 matcher、256 in-flight 场景下，短阶梯显示约 `9.7k–10.1k terminal business ops/s` 已接近平台；但由于 15k 终态失败和 swap/page-out，本轮不能给出机器最大容量结论，下一步应先修复订单终态基准问题并在 swap=0 环境复测 `10k–15k` 区间。

### 2026-09-03 11:54:28 +08:00 — `PV-20260903-256-27` — `采集前锁定（activeOrders 基准修复后的 10k–15k 复测）`

- 修复点：`LinearPerpetualSaturationWorkload` 原先使用 `tradingState().orders().size()` 作为 active order 数，混入订单历史记录；本轮固定改为只统计 `CoreOrderStatus.OPEN`，不放宽终态校验、不修改撮合/结算逻辑。已在 HotSpot JDK 25 上运行 `LinearPerpetualBenchmarkSupportTest`，14/14 通过。
- 固定范围与场景：仅 `LINEAR_PERPETUAL`；10,000 活跃用户、512 listed/active symbols、每用户最多5持仓/10活动订单、4 Account Lane、1 matcher、0 exchange-core risk engine、做市状态持续运行；每 invocation 16,384 个 PLACE_ORDER，50% maker GTC + 50% taker IOC，同 symbol/价格/数量配对，成交价1,000；严格固定 `256 in-flight`。
- 预先锁定基准标准：本轮为诊断性速率复测，不宣称生产容量验收；各档 accepted business=terminal business、accepted Core=terminal Core、unfinished/rejected/error/timeout/starvation=0、期末 matching/Lane/in-flight backlog=0、terminal trades=business operations的50%、资金/余额/冻结/持仓/OPEN订单/hash/snapshot restore闭合；记录 terminal business ops/s、terminal Core messages/s、trades/s、三段延迟和 backlog。任一档终态校验失败，该档吞吐无效。
- 预先锁定负载与阶段：open-loop constant-arrival，offered `10,000/11,000/12,000/13,000/14,000/15,000 business ops/s`，按升序串行执行；每档 `3x3s warmup + 3x5s measurement + 1 fork + 1 thread`，阶段间至少30秒冷却；计划到达计入 entry latency 并修正 coordinated omission。仅运行该阶梯，不运行其他 in-flight、matcher 数或长稳/JFR/GC轮。
- 固定环境与有效性：Oracle GraalVM Java HotSpot `25.0.1`、Maven `3.9.16`、Intel Core i9-9880H 8C/16T、16GiB、macOS 26.7/Darwin 25.6 x86_64；JVM固定 `-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED` 及现有脚本开放参数，settlement=`BLOCKING`、journal=65536/1GiB、export pending=256MiB、ACK interval=1024。采集前必须确认 `vm.swapusage used=0`、Pages throttled=0且无明显page-out/CPU throttling；当前锁定前 swap used=`4,739.75MiB`，未清理前不得开始采集，若不满足条件整轮仅记录为无效。
- 命令与 artifact：使用修复后 shaded JAR `target/product-core-benchmarks.jar`（SHA-256 `a12703e4fb054dd2ebb12a75feceb7c4a4ff318ef76162cf8e5de0eb50bf7e46`），`QUALIFICATION_RUN_ID=20260903T115428Z-active-orders-256-recheck SURPRISING_JAVA_HOME=/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home SCALE_JMH_WARMUP_ITERATIONS=3 SCALE_JMH_WARMUP_SECONDS=3 SCALE_JMH_MEASUREMENT_ITERATIONS=3 SCALE_JMH_MEASUREMENT_SECONDS=5 SCALE_JMH_FORKS=1 SATURATION_OPERATIONS_PER_INVOCATION=16384 QUALIFICATION_HEAP=8g MATCHING_ENGINES=1 surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh saturation`，每档通过 `-p targetOperationsPerSecond=<rate>` 执行；artifact 固定为 `target/qualification/20260903T115428Z-active-orders-256-recheck/`。标准、场景、速率和命令锁定后不修改，所有成功、失败、中止、环境异常及 artifact SHA-256 只追加本记录。

#### 采集结果

- 采集时间：`2026-09-03 12:03–12:09 +08:00`；被测 commit `d5805e12db662caf88766a493704762228b191d1`；HotSpot JDK `25.0.1`；六档均返回 `rc=0`，每档 teardown 的 active order 校验通过，未再出现 `activeOrders` 偏差。采集期间及结束后 `vm.swapusage used=0.00MiB`、Pages throttled=`0`。
- 结果（均为 terminal business ops/s；括号内为 terminal Core messages/s、trades/s）：`10,000 offered → 9,135.583（9,144.505、4,567.792）`；`11,000 → 9,269.261（9,278.313、4,634.631）`；`12,000 → 10,495.362（10,505.612、5,247.681）`；`13,000 → 10,054.953（10,064.773、5,027.477）`；`14,000 → 10,646.876（10,657.273、5,323.438）`；`15,000 → 10,900.312（10,910.957、5,450.156）`。每档3个 measurement，因单 fork/3样本误差很大，不能用 JMH 平均误差当作稳定容量区间。
- 正确性：六档均 accepted business=terminal business、accepted Core=terminal Core；unfinished business/Core、rejected、error、timeout 均为 `0`；trades 严格为 terminal business 的50%；Lane admission 等于 business，Lane settlement 分别为 `13,774.746/13,976.308/15,825.039/15,160.984/16,053.492/16,435.627 ops/s`，Lane total 为 `22,910.329/23,245.569/26,320.401/25,215.937/26,700.368/27,335.939 ops/s`。matching window samples 为 `142.743/144.832/163.990/157.109/166.357/170.317`，full-window samples 为 `115.979/117.676/133.242/127.651/135.165/138.383`，均为约 `81.25%`，producer starvation 均为 `0`；资金、终态订单和 snapshot 相关校验未报错。
- 趋势判断：从10k升到15k，terminal business 约从 `9.14k` 增至 `10.90k ops/s`，15k 仍有增长但明显低于 offered rate，说明单 matcher/4 Lane/256 in-flight 的稳定处理平台大约在 `10k–11k terminal business ops/s`，而不是15k offered。该结论是短时阶梯诊断，不是生产容量验收。
- 数据缺口与限制：本轮只采集无 profiler JMH 阶梯，没有执行 JFR、`-prof gc`、NMT、10分钟长稳、逐段延迟直方图导出；JSON 未提供 `maxBacklog` 和三段延迟字段，因此不能据此完成交易主链路性能验收或泄漏结论。未测其他五产品线、API/Aeron Cluster、Kafka、数据库、WebSocket和外围 projection；没有运行其他 in-flight 或 matcher 数。
- artifact：目录 `target/qualification/20260903T115428Z-active-orders-256-recheck/`；`ladder-status.csv` SHA-256 `8f8fca37ecd99d062335ca6e2735c00d7f8f62cb98b7f76546c43ff1d1439532`；JSON SHA-256：`10000=3109fb776e496733beece9f0988c7ec66bd659a2bb13c8c104ea069a591119bd`、`11000=c56be3da99967cf4f180d8f9f60e653d2426ea7761d3281d436ae957e61b7ee0`、`12000=766aba5c6567c3b59f75c61f18ab87e1e189b23879a9ec7de1bcba762784bcae`、`13000=bcf940f0cca4cc26491468b0c67873250d4cc03b5af3f0e18e55d786e46e63f5`、`14000=7885217212aa5653e9ea4ecb069954e9a3c69eb979fea8a90d354bcaa655285d`、`15000=51dd64dbc3286fd94521a05134deced13e39e3301918129bc297859c1922b0e7`；对应日志 SHA-256：`10000=f1678165e449dfda7c5936e9e43ae4743b75de96cb4de32d5dd140c7ca2a92df`、`11000=63dd04be74cd1cd72b62bf4da520788828489d66ac4ccf3a1ffa870e5be6b2e0`、`12000=e7d01ddf1175c76849c656926f329f7bc109f7a1915c6697e6702b58d060af09`、`13000=da5d9b476417b0454b54e64e31377a4090681ba44690c0ef833e21e7b6dcd3f5`、`14000=94dbd3a19c5eb45ca95cb1f6804937b0afbe823a1cf2f4df2ab11962b7d8d936`、`15000=18137c11c980a47d04747d0f201007a10c73474dfaa91aac1d8579672227ac54`；JSON与CSV合计 `134,140 B`。结论：activeOrders 基准问题已修复，10k–15k 六档功能/资金/终态校验通过；吞吐仍受单 matcher/当前实现平台限制，完整性能验收为部分验证。

### 2026-09-03 12:16:45 +08:00 — `PV-20260903-256-28` — `采集前锁定（2 matcher shard 诊断对照）`

- 诊断目的：在上一条 `matchingEngines=1` 阶梯基础上，只将 matcher shard 数改为 `2`，观察 512 个均匀活跃 symbol 是否能分散到两个 matcher worker；本轮是独立诊断实验，不与单 matcher 正式基线混合，也不作为当前固定单 matcher 验收结论。
- 修改点：`LinearPerpetualCoreBenchmark.SaturationState` 允许合法的 matcher 数量（1–64且为2的幂），仍强制 `maxInFlight=256`；生产撮合、账户、结算和协议逻辑不变。被测 commit `8c74308e`，对照 commit `cd267a6b`；HotSpot JDK `25.0.1`、Maven `3.9.16`。
- 固定范围与场景：仅 `LINEAR_PERPETUAL`；10,000 活跃用户、512 listed/active symbols、每用户最多5持仓/10活动订单、4 Account Lane、`matchingEngines=2`、0 exchange-core risk engine、做市状态持续运行；每 invocation 16,384 个 PLACE_ORDER，50% maker GTC + 50% taker IOC，同 symbol/价格/数量配对，成交价1,000；严格固定 `256 in-flight`。
- 预先锁定标准：每档 accepted business=terminal business、accepted Core=terminal Core、unfinished/rejected/error/timeout/starvation=0、期末 matching/Lane/in-flight backlog=0、terminal trades=business operations的50%、资金/余额/冻结/持仓/OPEN订单/hash/snapshot restore闭合；记录 terminal business ops/s、terminal Core messages/s、trades/s、Lane工作量和窗口指标。任一终态校验失败，该档吞吐无效。
- 预先锁定负载与阶段：open-loop constant-arrival，offered `10,000/11,000/12,000/13,000/14,000/15,000 business ops/s`，按升序串行执行；每档 `3x3s warmup + 3x5s measurement + 1 fork + 1 thread`，阶段间至少30秒冷却；计划到达计入 entry latency 并修正 coordinated omission。只运行 matcher=2 阶梯，不运行其他 matcher 数、其他 in-flight、JFR/GC或长稳。
- 固定环境与有效性：Intel Core i9-9880H 8C/16T、16GiB、macOS 26.7/Darwin 25.6 x86_64；JVM固定 `-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED` 及现有开放参数，settlement=`BLOCKING`、journal=65536/1GiB、export pending=256MiB、ACK interval=1024。采集开始前必须确认 `vm.swapusage used=0`、Pages throttled=0且无明显page-out/CPU throttling；标准、场景、速率和命令锁定后不修改。
- 命令与 artifact：使用 shaded JAR `target/product-core-benchmarks.jar`（SHA-256 `92e2f3ec4a1c4a3eca2d816473a1f292fdcf4c47dfcc3f7d8ad2db3b949c4f9e`），显式设置 `-Dsurprising.aeron.matching-engines=2`，并传入 `-p matchingEngines=2`、上述固定参数和对应 `-p targetOperationsPerSecond=<rate>`；artifact 固定为 `target/qualification/20260903T121645Z-matcher2-256-diagnostic/`。所有成功、失败、中止、环境异常及 artifact SHA-256 只追加本记录。

#### 采集结果

- 采集时间：`2026-09-03 12:17–12:23 +08:00`；六档均返回 `rc=0`，active order、资金、成交、Lane 队列和 snapshot 相关终态校验通过；采集期间 swap=`0.00MiB`、Pages throttled=`0`。
- 2 matcher 的 terminal business ops/s：`10,000 → 9,693.917`；`11,000 → 9,352.442`；`12,000 → 10,563.447`；`13,000 → 9,986.398`；`14,000 → 9,606.054`；`15,000 → 11,026.846`。对应 terminal Core messages/s：`9,703.384/9,361.575/10,573.763/9,996.151/9,615.435/11,037.615`；trades/s：`4,846.959/4,676.221/5,281.723/4,993.199/4,803.027/5,513.423`。
- 与同机、同场景 matcher=1 结果对照，六档 terminal business 变化分别为 `+6.11%/+0.90%/+0.65%/-0.68%/-9.78%/+1.16%`；15k 从 `10,900.312` 提升到 `11,026.846 ops/s`，但每档只有3个 measurement且误差很大，不能认定为稳定收益。2 matcher 没有带来可重复的吞吐提升。
- 正确性与窗口：六档 accepted=terminal business/Core，unfinished、reject、error、timeout、producer starvation 均为 `0`；trades 均为 business 的50%。Lane admission 等于 business；settlement 为 `14,616.610/14,101.729/15,927.697/15,057.616/14,484.128/16,626.417 ops/s`，Lane total 为 `24,310.527/23,454.170/26,491.144/25,044.015/24,090.183/27,653.263 ops/s`。matching window/full-window 样本均约 `81.25%`，说明瓶颈仍表现为共享256窗口和 Core/owner 完成链路，而非压测端缺少请求；但本轮未采 JFR，不能把具体热点归因到某个方法。
- 结论：在当前 512 均匀 symbol、4 Account Lane、256 in-flight、单 Product Core owner 的场景中，matcher 从1增至2暂未实质提高 terminal business ops/s；下一步更有价值的是对 matcher=2 进行 JFR/GC/CPU 归因，确认共享 owner、Core Fact/commit 或 Lane settlement 是否已经成为主瓶颈，再决定是否测试4 matcher。该轮仍是诊断数据，不是正式容量验收。
- artifact：目录 `target/qualification/20260903T121645Z-matcher2-256-diagnostic/`；JSON SHA-256：`10000=32e9536bf8e609131dd781bbebe1519aac88face4621a716f713a05f5e6fb67c`、`11000=c9ca6f6980d0d18513ef0bc73f3f07a6f6906bcc5b48695a1bd49e4e0f8a3169`、`12000=1ea30663d373b4f92f353b97c0ea8125c2bdb27225b185291d7171573fbca91b`、`13000=5d11263b34bd62402cd54edab842e4d584db05fa1f3cc5714c75bdea9c9fa8fb`、`14000=c7e79b3448f8eee151edc577d4faf84ad4f064716a3f67fb5dcbe351d61b4e57`、`15000=8fad696c4897fbd44547e432bdd73d2b1ed83f6eaf8a8380416143bd1e6cc990`；状态文件 SHA-256 `8f8fca37ecd99d062335ca6e2735c00d7f8f62cb98b7f76546c43ff1d1439532`；对应日志 SHA-256 依次为 `51340edfdfe4f1d024740fb368695562da7c28c7d115bddd7f80ae1761dbf49a`、`9647759103b2121cc361e065a3238a913223e582b96fc491a70b0346247f858b`、`b592df86fea90034164cbb3dfbe46c7956c03a4ca54d66ba1d71319a47fa7f4b`、`5cc5b4a87f1b533eceb92403bbab2826dc59b81f91116554b4e013ecde149669`、`338d194dee1df821cf69594d48df32e0ecfdb7f9b3ebecc49191a22c52dfcd7f`、`8a50ab28105047386bd558410e58a9ca586ae1c5739340e76a6260efd79a5372`。

### 2026-09-03 12:39:05 +08:00 — `PV-20260903-256-29` — `采集前锁定（matcher=2 JFR 归因）`

- 诊断目的：针对 `PV-20260903-256-28` 中 matcher 从1增至2未产生可重复吞吐收益的问题，采集 matcher=2 在共享 Product Core owner、Core Fact/commit、Account Lane 和 settlement 路径上的 CPU、分配、GC、NMT、锁/park、safepoint、JIT、I/O、异常及 workload latency 证据。本轮仅作归因诊断，不替代固定 `matchingEngines=1` 的正式交易链路性能验收，也不据此宣称容量或无泄漏。
- 被测代码与对照：被测 commit `509b265ef5da3687ea754ca34a9d2892bd7c2d7a`；对照为 matcher=1 阶梯的 `d5805e12db662caf88766a493704762228b191d1`。被测 shaded JAR `target/product-core-benchmarks.jar` SHA-256 `92e2f3ec4a1c4a3eca2d816473a1f292fdcf4c47dfcc3f7d8ad2db3b949c4f9e`；JFC `owner-commit-profile.jfc` SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。
- 固定场景：仅 `LINEAR_PERPETUAL` 的 `LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`；`matchingEngines=2`、4 Account Lane、单 Product Core/JMH owner、0 exchange-core risk engine、10,000 活跃用户、512 listed/active symbols、每用户最多5持仓/10活动订单、做市持续运行；每 invocation 16,384 PLACE_ORDER，50% maker GTC + 50% taker IOC，同 symbol/价格/数量配对，成交价1,000；open-loop constant-arrival，offered `15,000 terminal business ops/s`，严格固定 `256 in-flight`。
- 固定标准：accepted/terminal business 与 Core messages 相等，unfinished/rejected/error/timeout/starvation=0，期末 matching/Lane/in-flight backlog=0，trades=business operations的50%，资金/余额/冻结/持仓/OPEN订单/hash/snapshot restore闭合；JFR `DataLoss=0`、owner正式窗口同步文件/网络/数据库I/O=0、无明显CPU throttling/swapout；完整报告按 owner、2 matcher worker、4 Lane、Core Fact/exporter、Aeron/外围线程分组。所有失败/异常均保留并标记。
- 固定阶段与资源：HotSpot JDK `25.0.1`、Maven `3.9.16`、MacBookPro16,1 / 16 CPU / 16 GiB、macOS 26.7/Darwin 25.6 x86_64；ZGC，`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`；`1x3s warmup + 1x10s measurement + 1 fork (JMH -f 0) + 1 thread`，JFR 从 fork JVM 启动开始；启用 NMT summary、GC/safepoint log；不执行独立 `-prof gc`、10分钟长稳或其他 matcher/in-flight 档位。
- 命令与 artifact：artifact 固定为 `target/qualification/20260903T123905Z-matcher2-256-jfr/`；运行 `java -jar product-core-benchmarks.jar LinearPerpetualCoreBenchmark.saturatedMatchingWorkload -p accountLanes=4 -p activeUsers=10000 -p listedSymbols=512 -p activeSymbols=512 -p maxPositionsPerUser=5 -p maxOpenOrdersPerUser=10 -p maxInFlight=256 -p operationsPerInvocation=16384 -p targetOperationsPerSecond=15000 -p matchingEngines=2 -wi 1 -w 3s -i 1 -r 10s -f 0 -t 1 -rf json -rff saturation-profile.json`；fork JVM追加 `-Dsurprising.aeron.matching-engines=2`、settlement BLOCKING、journal 65,536/1 GiB、export pending 256 MiB、ACK interval 1,024、显式 JFC `owner-commit-profile.jfc`、NMT summary和`-Xlog:gc*,safepoint`。保存原始 `.jfr`、JFR summary/metadata/views、GC log、NMT baseline/diff、JMH JSON/log及全部校验哈希；采集前确认 `vm.swapusage`、Pages throttled、Java/Maven版本和代码/JAR/JFC SHA。

#### 采集结果

- 采集时间：`2026-09-03 12:42–12:43 +08:00`；benchmark 返回 `rc=0`。按锁定命令使用 JMH `-f 0`，即不额外创建 fork JVM，属于 JFR 归因诊断，不是正式吞吐验收。JDK 为 Oracle GraalVM Java HotSpot `25.0.1`，Maven `3.9.16`；采集前后 swap 均 `0.00MiB`，Pages throttled 均为 `0`。
- 业务结果：terminal business `8,909.691 ops/s`，terminal Core messages `8,918.392/s`，trades `4,454.845/s`；accepted 与 terminal business/Core 相等，unfinished/rejected/error/timeout/producer-starvation 均为 `0`；Lane admission/settlement/total 分别为 `8,909.691/13,434.143/22,343.834 ops/s`，matching window/full-window 为 `139.214/113.111` samples/s。JFR/非 fork 开销使该吞吐低于前一轮无 profiler 的 `11,026.846/s`，两者不作绝对吞吐比较。
- JFR 完整性与延迟：原始记录约 `156MiB`，`DataLoss=0`；workload latency 共 `131,072` samples，offered `15,000 ops/s`。PLACE_ORDER entry→accepted p50/p99/max=`536.871ms/1.074s/1.040s`，accepted→terminal p50/p99/max=`16.777ms/33.554ms/29.726ms`，entry→terminal p50/p99/max=`536.871ms/1.074s/1.052s`；该轮只用于归因，不能替代固定生产验收门禁。
- CPU 归因：`jdk.ExecutionSample` 中 benchmark worker（同时承载本基准 owner/驱动）`605` samples，core-fact-materializer `52`，两个 matcher 合计 `13`（matcher-0=`9`、matcher-1=`4`），4 个 Account Lane 合计 `16`。worker 热点为 `CoreProbeState.progressPlaceAdmissions` `45`、`commitReadyMatching` `31`、`awaitMatchingResult` `23`、`completeMatching` `19`，以及 `TreeMap.put` `16`、`CoreStateHash.mix` `12`、`TreeMap.successor` `11`、`HashMap.getNode` `10`。这确认在当前基准中，matcher=2 的撮合线程不是主要 CPU 采样热点，共享 owner 的 admission/commit/完成与状态哈希路径更重。
- 分配归因：JFR sampled allocation `33,765` samples、约 `8.753GB` 加权样本，约 `66,777 B/terminal business op`（采样权重，不是精确 `-prof gc` 结果）。主要站点为 `LongObjectHashMap.addKeyValueAtIndex` `1,396`、`TreeMap.put` `1,237`、`ByteBuffer.allocate` `1,019`、`ArrayList.add` `888`、`HashMap.putVal` `806`、`TradingRuntimeState.prepareFactFrame` `589`、`LongObjectHashMap.allocateTable` `566`；主要对象包含 byte[]、long[]、Object[]、int[]、TreeMap.Entry、ListItr、Long、OrderRuntime/CoreOrderState。说明瓶颈不只是 matcher 本体，Core Fact/状态容器/编码边界仍有明显成本。
- GC、锁、I/O 与系统：4 次 ZGC，allocation stall/failure 均为 `0`；GC pause p50/p95/p99/max=`0.009773/0.012187/0.025486/0.025486ms`。锁/park 共29个事件，业务期间未见高竞争热点，主要为启动、symbol 注册和资源处理；owner同步文件/网络/数据库 I/O 为 `0 events/0 B`。NMT baseline/diff已保存，DirectBufferStatistics为0；JIT compilation `8,477`、deoptimization `533`、compilation failure `0`。短JFR不能证明无泄漏。
- 异常与分析器：JFR记录 `1,018` 个 Java 异常，主要为 `NoSuchFieldException=594`、`NoSuchMethodError=306`、`UnsatisfiedLinkError=33`、`IncompatibleClassChangeError=30`，主要线程为 main、JMH worker 和 matcher-0；因此严格零异常门禁失败，不能作为正式验收证据。分析器保留了严格运行和放宽 target/exception 后的诊断聚合；严格 analyzer 返回 `rc=1`（本轮 target=15,000 不符合其固定100,000 workload contract，且存在未分类生命周期线程），不影响原始 JFR、summary、metadata、views 和 aggregate 的留存。
- 确认结论：在 matcher=2、512 symbols、4 Lane、256 in-flight 的这轮实测中，两个 matcher worker 已被创建且执行，但只增加 matcher 并未把主要 CPU/分配压力移出单 Product Core owner；`progressPlaceAdmissions → commitReadyMatching → completeMatching`、Core Fact materialization、状态哈希/容器操作是当前更可信的共享瓶颈证据。因此“全局 per-command barrier 已删除”与“仍有单一逻辑 Core owner 形成共享提交边界”可以同时成立；当前不建议直接上 matcher=4，下一步应优先针对这些 owner/Core Fact 热点做改动或更细的 measurement-window JFR。
- artifact：目录 `target/qualification/20260903T123905Z-matcher2-256-jfr/`；原始 `saturation.jfr` `163,260,003 B`，SHA-256 `3b46f64ba87eb99f5b2aa0da77daf8b8b1025ea36208b00fd6069f6c1367ffea`；JMH JSON `21,057 B`，SHA-256 `9bc093dfe6029e4c420e9ee51bfb2d0dc20f4c4a7cab4f98931b5847055fa6c3`；GC log `76,058 B`，SHA-256 `73975fbb61b3effc0efe46050dde3e11d727c8b51c453c4c2469741c05c17556`；NMT baseline SHA-256 `cfb22a1eb3b35239ca9f8f5eebe2b313e0861589af5e23edf8ba73507c61e2c2`，NMT diff SHA-256 `257bf82bb9b0b80cbd3f4e1ece0f7ef7a18f8fc69f033e25f5033c2d13f9b888`；JFR summary SHA-256 `ef932773519353d14d905816be5bf7bb288108d45bd679697b59bf845d5d29ba`；诊断 aggregate `saturation-jfr-analysis-diagnostic/aggregate.json` `6,784,902 B`，SHA-256 `9957b2ea612ffa9cac0f3dfff530001a156c824a56066dda095c13cd5238cfb5`。严格分析输出位于 `saturation-jfr-analysis/`，诊断聚合及 bounded views 位于 `saturation-jfr-analysis-diagnostic/`。

### 2026-09-03 13:23:00 +08:00 — `PV-20260903-256-30` — `采集前锁定（matcher completion drain 优化正式对照）`

#### 采集前锁定

- 记录创建时间：`2026-09-03 13:23:00 +08:00`
- 被测 git commit：`de09b039`（待采集时解析完整 SHA）
- 对照 git commit：`d5805e12db662caf88766a493704762228b191d1`
- 修改点：`MatcherCommandPipeline` 暴露已完成 shard head，`MatcherPipelineGroup` 从各 shard 直接排空完成项，`CoreProbeState.drainMatchingCompletions` 不再遍历全部 pending matching；新增跨 shard drain 单元测试。未改变 Core sequence、Account Lane、资金、Fact、snapshot 或 matcher 数量。
- 验证目标：确认局部 completion drain 优化不会改变 matcher 控制 token、shard 内提交顺序、资金守恒、订单终态、Core Fact 和 snapshot recovery，并观察单 matcher 正式场景的 terminal business ops/s、Core messages/s、trades/s、尾延迟、owner CPU 与分配变化。
- in-flight：`256（固定，不得修改）`
- 通过标准：
  - 吞吐：`terminal business ops/s、terminal Core messages/s、trades/s 均大于0；与同机同场景对照相比不接受超过10%的 terminal business 回归`
  - 正确性：`accepted == terminal；accepted/terminal Core messages 相等；unfinished、rejected、error、timeout、producer starvation 均为0；期末 matching/Lane/in-flight backlog为0；资金、余额、冻结、持仓、OPEN订单和快照恢复正确`
  - 延迟：`记录 workload entry→accepted、accepted→terminal、entry→terminal 的 p50/p90/p95/p99/p99.9/max；本轮作为诊断对照，不设未采集的业务类型阈值`
  - 稳定性：`JFR DataLoss=0、无 swap/page-out、Pages throttled=0、无明显 CPU throttling；owner正式窗口同步文件/网络/数据库I/O为0`
  - 资源：`保存 JFR CPU/热点、allocation、GC、heap/native/NMT、线程/锁/park、safepoint/VM/JIT、I/O/异常证据；带 profiler 数值只用于归因`
  - 长稳/泄漏：`本轮不执行长稳，不据此下无泄漏结论`
- 测试场景：
  - 产品线与 symbol：`仅 LINEAR_PERPETUAL；10,000 活跃用户；512 listed/active symbols`
  - 业务动作与比例：`每 invocation 16,384 PLACE_ORDER；50% maker GTC + 50% taker IOC；同 symbol/价格/数量配对；预期 trades 为 terminal business 的50%`
  - 负载模型：`saturation benchmark 的 open-loop constant-arrival，target offered rate=100,000 business ops/s；记录计划到达延迟并修正 coordinated omission`
  - 并发：`活跃用户10,000；JMH线程1；in-flight=256；Account Lane=4；matching engine=1；risk engine=0`
  - 批量参数：`N/A（单 PLACE_ORDER；operationsPerInvocation=16,384）`
  - 阶段时长：`JMH 3x3s warmup + 3x5s measurement + 1 fork；随后1x3s warmup + 1x10s JFR measurement；不执行长稳`
  - 做市状态：`基准内做市状态持续运行`
  - 初始状态：`按现有 saturatedMatchingWorkload 初始化；用户资金、持仓、活动订单和盘口由 benchmark 固定生成`
  - 终态检查：`资金守恒、余额/冻结/持仓、订单生命周期、盘口、Core Fact、snapshot restore`
- 固定环境与参数：
  - 机器/CPU/内存/容器：`MacBookPro16,1；Intel Core i9-9880H；16 logical CPU；16GiB；无容器绑核；确认无明显同机干扰`
  - OS：`macOS 26.7 / Darwin 25.6 x86_64`
  - JDK/JVM：`Oracle GraalVM Java HotSpot 25.0.1；Maven 3.9.16；执行前记录 java -version 与 mvn -version`
  - JVM/GC/NMT 参数：`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED；account-lanes=4；matcher=1；settlement=BLOCKING；journal=65536/1GiB；export pending=256MiB；export ACK interval=1024；JFR profile 与 NMT summary`
  - JMH：`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload；fork=1；warmup=3x3s；measurement=3x5s；JMH线程=1；operationsPerInvocation=16,384；target=100,000；maxInFlight=256`
  - JFR：`owner-commit-profile.jfc；JFR saturation profile 约10s业务测量；保存原始 saturation.jfr、summary、metadata、views、GC/safepoint、NMT；profile 开销不与无 profiler 吞吐绝对比较`
  - 代码与配置：`分支 codex/aeron-unified-core；被测 commit de09b039；artifact 使用本轮构建 shaded JAR；JFC SHA 在结果中记录`
- 执行命令：
  - `java -version && mvn -version`
  - `QUALIFICATION_RUN_ID=20260903T132300Z-completion-drain-256 SURPRISING_JAVA_HOME=/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home SCALE_JMH_WARMUP_ITERATIONS=3 SCALE_JMH_WARMUP_SECONDS=3 SCALE_JMH_MEASUREMENT_ITERATIONS=3 SCALE_JMH_MEASUREMENT_SECONDS=5 SCALE_JMH_FORKS=1 SATURATION_OPERATIONS_PER_INVOCATION=16384 MATCHING_ENGINES=1 surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh saturation`

#### 采集结果

- 采集时间：`2026-09-03 13:23–13:26 +08:00`；被测 commit `de09b039079fe5d1436356811d42776295cad2e9`；对照 commit `d5805e12db662caf88766a493704762228b191d1`；HotSpot JDK `25.0.1`、Maven `3.9.16`。JMH 主结果使用 `1 fork`、`3` 个 measurement 样本；JFR profile 按脚本使用 `-f 0`，因此只作诊断，不能与无 profiler 的主结果直接比较。
- JMH 主结果：terminal business `9,078.890 ops/s`（样本 `8,777.623–9,324.044`，JMH 99.9% error `±5,062.604`）；terminal Core messages `9,087.756/s`；trades `4,539.445/s`。accepted business/Core 与 terminal business/Core 相等；unfinished business/Core、rejected、error、timeout、producer starvation 均为 `0`。Lane command=`9,078.890/s`、Lane total=`22,768.153/s`、Lane settlement=`13,689.263/s`；matching refill=`9,061.158/s`、window=`141.858/s`、full-window=`115.259/s`。
- JFR profile 结果：terminal business `8,823.412 ops/s`；terminal Core messages `8,832.029/s`；trades `4,411.706/s`；accepted/terminal 计数相等，unfinished/rejected/error/timeout/starvation 均为 `0`。JFR workload 共 `131,072` 个 PLACE_ORDER 样本，scheduled 与 terminal business 均为 `131,072`；matching 最大 backlog=`256`、平均 backlog=`232`、full window=`81.25%`。延迟聚合记录为 entry→accepted `p50/p90/p95/p99/p99.9/max=1.0737/2.1475/2.1475/2.1475/2.1475/1.9803s`，accepted→terminal `16.777/33.554/33.554/33.554/33.554/32.310ms`，entry→terminal `1.0737/2.1475/2.1475/2.1475/2.1475/1.9955s`；其中 quantile 是 Log2 histogram 桶值，不能当作精确分位数。
- JFR CPU/热点：execution samples 中 benchmark worker=`633`、core-fact-materializer=`79`、core-matcher-0=`78`、4 个 Account Lane 合计=`169`；主要业务热点为 `CoreProbeState.progressPlaceAdmissions`=`63`、`awaitMatchingResult`=`22`、`completeMatching`=`14`、`TreeMap.put`=`12`、`CoreStateHash.mix`=`11`。本轮仍显示共享 owner admission/完成路径比 matcher worker 更重。
- JFR 分配/GC/内存：采样分配约 `8.760GB`，约 `66,834.9 B/terminal business op`（采样权重，不是精确 `-prof gc`）；主要分配站点为 `LongObjectHashMap.addKeyValueAtIndex`、`TreeMap.put`、`ByteBuffer.allocate`、`ImmutableCollections.listCopy`、`ArrayList.add`、`TradingRuntimeState.prepareFactFrame`。ZGC `4` 次，allocation stall/failure=`0`；总 GC 时间占记录时间约 `49.85%`，暂停 p50/p95/p99/max=`0.010/0.013/0.144/0.144ms`，最长阶段为 Concurrent Mark `132.260ms`。heap committed=`8GiB`，最大 used=`2.398GiB`，末次 GC 后 live set=`614MiB`；NMT summary/diff、DirectBufferStatistics=`0` 已保存。短记录不能证明无泄漏。
- JFR 完整性与门禁：`DataLoss=0`、swap=`0`、Pages throttled=`0`、owner 同步文件/网络/数据库 I/O=`0 events/0 B`；JIT compilation=`8,613`、deoptimization=`524`。但记录了 `1,036` 个 Java exception/error throw（主要为 `NoSuchFieldException=594`、`NoSuchMethodError=324`、`UnsatisfiedLinkError=33`、`IncompatibleClassChangeError=30`），超过分析器配置的 `maxExceptions=0`，故分析脚本返回 `rc=1`，本轮不能作为正式性能验收。
- 结论：局部 completion drain 改动通过受影响单测和核心回归测试，业务计数、订单终态及压测 teardown 校验通过；本轮没有证据表明吞吐有稳定提升，也没有引入可接受的完整 JFR 验收证据。按“不要增加复杂度”的约束，未加入 ready 链表、跨层视图复用或额外状态副本；当前主要瓶颈仍在共享 owner 的 admission/commit/完成和状态容器路径，后续若继续优化应先针对单一热点做小改动。
- 未测范围：其他五条产品线、API/Kafka/数据库/WebSocket、长稳泄漏、独立 `-prof gc`、其他 in-flight、matcher=2/4；本轮不据此推导生产容量。
- artifact：目录 `target/qualification/20260903T132300Z-completion-drain-256-scale/`；主 JMH JSON SHA-256 `4d28c593916235a37122cebea37f7f709188b97920025be2c9d0aacdb19116d`（`22,164 B`）；JFR profile JSON SHA-256 `e1ce5fb4188418ffcfdd8eb7bcbb80584287568bc0d8504d025ea7cb3335892e`（`21,078 B`）；原始 `saturation.jfr` SHA-256 `5602b8474c38a934645917147a51bff28fa8dadbade0546317ae4304f47713ed`（`150,994,739 B`）；`jfr-summary.txt` SHA-256 `bd454b5aae3a156f3a6bd8e679e423fc054c6078b71eb8f886dca7b00f86bcf2`；`aggregate.json` SHA-256 `1adcdabf708b2a62caa3448bcc8aeb8d82bb5274e9cfae9f2646bf6948155779`；JFC SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。

### 2026-09-03 14:21:20 +08:00 — `PV-20260903-256-31` — `采集前锁定（删除 Core Fact 审计 hash 与 matcher evidence 导出字段）`

- 被测代码：当前工作树，HEAD `a85545622c48b60f5f22ee9695044be28074e159`；本轮未提交。修改点固定为：CoreExportEvent/Codec 删除 business/before/funds/topology/lane revision hash 与 matcher transition/evidence 导出字段；CoreExportState/CoreProbeState 删除对应 Draft、metadata、lane revision hash 扫描和外部连续性校验；Core Fact 协议 marker 升至 V11；不删除交易 runtime 的资金守恒、撮合、Account Lane、snapshot recovery 所需状态校验。
- 测试与构建标准：只验证交易链路，不执行 exporter 或 PostgreSQL/Docker 测试；HotSpot JDK 25.0.1、Maven 3.9.16；protocol 80、service 497 必须通过；benchmark shaded JAR 必须可编译；`git diff --check` 必须通过。
- 性能场景：仅 `LINEAR_PERPETUAL` 的 `LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`；10,000 活跃用户、512 listed/active symbols、4 Account Lane、1 matching engine、1 JMH worker、每用户最多5持仓/10活动订单；每 invocation 16,384 PLACE_ORDER，50% maker GTC + 50% taker IOC，做市持续运行，open-loop constant-arrival 100,000 offered terminal business ops/s，coordinated omission corrected；严格固定 `256 in-flight`，不运行其他档位。
- 正确性门禁：accepted business/Core messages 分别等于 terminal；unfinished/rejected/error/timeout/producer-starvation 为0；期末 matcher/Lane/in-flight backlog为0；trades、资金守恒、余额/冻结/持仓、订单终态和 snapshot recovery 校验通过。分别记录 terminal business ops/s、terminal Core messages/s、trades/s、backlog 及 entry→accepted、accepted→terminal、entry→terminal 的 p50/p90/p95/p99/p99.9/max。
- 性能门禁：无 profiler 主轮 `3x3s warmup + 3x5s measurement + 1 fork + 1 thread`，诊断 JFR 轮 `1x3s warmup + 1x10s measurement + fork=0`，JFR 不与无 profiler 吞吐横向比较；独立 `-prof gc` 不在本轮执行。主轮只要求相对 PV-30 同场景无超过10%的 terminal business 回归，本轮数据不宣称达到生产容量或100k/s。
- JVM/JFR/资源：`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`，settlement `BLOCKING`，journal `65536/1GiB`，export pending `256MiB`；JFC 使用 `surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc`，保存原始 JFR、summary、metadata/views、GC/safepoint、NMT 和 SHA-256。记录 swap、Pages throttled、CPU throttling、owner 同步 I/O、JFR DataLoss、JIT/GC/heap/native/线程/锁/异常证据；本轮不执行长稳，不能据此声明无泄漏。
- 执行命令固定为：`QUALIFICATION_RUN_ID=20260903T142120Z-audit-hash-removal-256 SURPRISING_JAVA_HOME=/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home SCALE_JMH_WARMUP_ITERATIONS=3 SCALE_JMH_WARMUP_SECONDS=3 SCALE_JMH_MEASUREMENT_ITERATIONS=3 SCALE_JMH_MEASUREMENT_SECONDS=5 SCALE_JMH_FORKS=1 SATURATION_OPERATIONS_PER_INVOCATION=16384 MATCHING_ENGINES=1 surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh saturation`；artifact 固定为 `target/qualification/20260903T142120Z-audit-hash-removal-256-scale/`。锁定后标准、场景和命令不再修改；失败或无效结果照实追加。

### 2026-09-03 14:33:32 +08:00 — `PV-20260903-256-32` — `采集前锁定（matcher=2 交易链路诊断）`

#### 采集前锁定

- 被测代码：当前工作树，HEAD `a85545622c48b60f5f22ee9695044be28074e159`，工作树包含本轮审计 hash 删除改动及 matcher=2 诊断入口放开改动；本轮不修改交易运行时逻辑。
- 修改点：正式验收仍默认 matcher=1；允许 matcher=2 作为独立扩展性/瓶颈诊断；`qualify-linear-perpetual-scale.sh` 接受 `[1,64]` 内的 2 次幂 matcher 数。生产撮合、账户、结算、协议、资金和 snapshot 逻辑不变。
- 测试与构建标准：只验证交易链路，不执行 exporter、PostgreSQL/Docker、API、Kafka、WebSocket 或其他产品线测试；HotSpot JDK 25.0.1、Maven 3.9.16；benchmark shaded JAR 必须成功构建；JMH 业务计数与终态校验必须通过；JFR 原始文件必须生成并记录完整性，即使严格异常门禁失败也保留诊断结果。
- 性能场景：仅 `LINEAR_PERPETUAL` 的 `LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`；10,000 活跃用户、512 listed/active symbols、4 Account Lane、2 matching engines、1 JMH worker、每用户最多5持仓/10活动订单；每 invocation 16,384 PLACE_ORDER，50% maker GTC + 50% taker IOC，做市持续运行，open-loop constant-arrival，offered target `100,000 terminal business ops/s`，coordinated omission corrected；严格固定 `256 in-flight`，不运行其他 matcher/in-flight 档位。
- 正确性门禁：accepted business/Core messages 分别等于 terminal；unfinished/rejected/error/timeout/producer-starvation 为0；期末 matcher/Lane/in-flight backlog为0；trades、资金守恒、余额/冻结/持仓、订单终态和 snapshot recovery 校验通过。记录 terminal business ops/s、terminal Core messages/s、trades/s、backlog 及 entry→accepted、accepted→terminal、entry→terminal 延迟。
- 性能门禁：无 profiler 主轮 `3x3s warmup + 3x5s measurement + 1 fork + 1 thread`；JFR 轮由脚本执行 `1x3s warmup + 1x10s measurement + fork=0`；固定 `256 in-flight`；JFR 吞吐不与无 profiler 主轮横向比较。记录 JFR CPU/热点、allocation、GC、heap/native/NMT、线程/锁/park、safepoint/VM/JIT、I/O/异常；本轮不执行长稳，不能据此声明无泄漏。
- JVM/JFR/资源：`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`，4 Account Lane，matcher=2，settlement `BLOCKING`，journal `65536/1GiB`，export pending `256MiB`；JFC 使用 `surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc`；执行前检查 `java -version`、`mvn -version`、swap 和 CPU throttling，保存原始 JFR、summary、metadata/views、GC/safepoint、NMT 和 SHA-256。
- 执行命令固定为：`java -version && mvn -version`；`QUALIFICATION_RUN_ID=20260903T143332Z-matcher2-256-scale SURPRISING_JAVA_HOME=/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home SCALE_JMH_WARMUP_ITERATIONS=3 SCALE_JMH_WARMUP_SECONDS=3 SCALE_JMH_MEASUREMENT_ITERATIONS=3 SCALE_JMH_MEASUREMENT_SECONDS=5 SCALE_JMH_FORKS=1 SATURATION_OPERATIONS_PER_INVOCATION=16384 MATCHING_ENGINES=2 surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh saturation`；artifact 固定为 `target/qualification/20260903T143332Z-matcher2-256-scale/`。锁定后标准、场景和命令不再修改；失败或无效结果照实追加。

#### 无效轮次说明

- 采集时间：`2026-09-03 14:34:50 +08:00`；脚本成功构建 shaded JAR，但 JMH 输出确认 benchmark 参数仍为 `matchingEngines=1`。原因是脚本只传入了 JVM 系统属性，未传入 JMH `-p matchingEngines=2`，而 `@Param` 默认值覆盖了系统属性。
- 该轮在第一个 warmup 开始前中止，退出码 `130`；无业务吞吐结果，不纳入任何比较。修复脚本后新建 `PV-20260903-256-33`，重新锁定全部采集标准和命令。

### 2026-09-03 14:35:24 +08:00 — `PV-20260903-256-33` — `采集前锁定（matcher=2 参数修复后的交易链路诊断）`

#### 采集前锁定

- 被测代码：当前工作树，HEAD `a85545622c48b60f5f22ee9695044be28074e159`，工作树包含本轮审计 hash 删除改动、matcher=2 诊断入口放开改动及 benchmark 脚本参数修复；本轮不修改交易运行时逻辑。
- 修改点：正式验收仍默认 matcher=1；允许 matcher=2 作为独立扩展性/瓶颈诊断；scale saturation 主轮和 JFR 轮均显式传入 `-p matchingEngines=2`，同时保留 JVM 系统属性。生产撮合、账户、结算、协议、资金和 snapshot 逻辑不变。
- 测试与构建标准：只验证交易链路，不执行 exporter、PostgreSQL/Docker、API、Kafka、WebSocket 或其他产品线测试；HotSpot JDK 25.0.1、Maven 3.9.16；benchmark shaded JAR 必须成功构建；JMH 业务计数与终态校验必须通过；JFR 原始文件必须生成并记录完整性，即使严格异常门禁失败也保留诊断结果。
- 性能场景：仅 `LINEAR_PERPETUAL` 的 `LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`；10,000 活跃用户、512 listed/active symbols、4 Account Lane、2 matching engines、1 JMH worker、每用户最多5持仓/10活动订单；每 invocation 16,384 PLACE_ORDER，50% maker GTC + 50% taker IOC，做市持续运行，open-loop constant-arrival，offered target `100,000 terminal business ops/s`，coordinated omission corrected；严格固定 `256 in-flight`，不运行其他 matcher/in-flight 档位。
- 正确性门禁：accepted business/Core messages 分别等于 terminal；unfinished/rejected/error/timeout/producer-starvation 为0；期末 matcher/Lane/in-flight backlog为0；trades、资金守恒、余额/冻结/持仓、订单终态和 snapshot recovery 校验通过。记录 terminal business ops/s、terminal Core messages/s、trades/s、backlog 及 entry→accepted、accepted→terminal、entry→terminal 延迟。
- 性能门禁：无 profiler 主轮 `3x3s warmup + 3x5s measurement + 1 fork + 1 thread`；JFR 轮由脚本执行 `1x3s warmup + 1x10s measurement + fork=0`；固定 `256 in-flight`；JFR 吞吐不与无 profiler 主轮横向比较。记录 JFR CPU/热点、allocation、GC、heap/native/NMT、线程/锁/park、safepoint/VM/JIT、I/O/异常；本轮不执行长稳，不能据此声明无泄漏。
- JVM/JFR/资源：`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`，4 Account Lane，matcher=2，settlement `BLOCKING`，journal `65536/1GiB`，export pending `256MiB`；JFC 使用 `surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc`；执行前检查 `java -version`、`mvn -version`、swap 和 CPU throttling，保存原始 JFR、summary、metadata/views、GC/safepoint、NMT 和 SHA-256。
- 执行命令固定为：`java -version && mvn -version`；`QUALIFICATION_RUN_ID=20260903T143524Z-matcher2-256-scale SURPRISING_JAVA_HOME=/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home SCALE_JMH_WARMUP_ITERATIONS=3 SCALE_JMH_WARMUP_SECONDS=3 SCALE_JMH_MEASUREMENT_ITERATIONS=3 SCALE_JMH_MEASUREMENT_SECONDS=5 SCALE_JMH_FORKS=1 SATURATION_OPERATIONS_PER_INVOCATION=16384 MATCHING_ENGINES=2 surprising-aeron-core/surprising-aeron-benchmarks/bin/qualify-linear-perpetual-scale.sh saturation`；artifact 固定为 `target/qualification/20260903T143524Z-matcher2-256-scale/`。锁定后标准、场景和命令不再修改；失败或无效结果照实追加。

#### 采集结果

- 采集时间：`2026-09-03 14:35–14:40 +08:00`；被测 JAR 构建成功；JDK Oracle GraalVM HotSpot `25.0.1`，Maven `3.9.16`；JMH 输出确认 `matchingEngines=2`、`maxInFlight=256`。
- 无 profiler 主轮：terminal business `11,911.766 ops/s`，terminal Core messages `11,923.399/s`，trades `5,955.883/s`；3 个 measurement 样本 terminal business 为 `11,314.156/11,832.332/12,588.811 ops/s`，JMH 99.9% error `±11,694.762 ops/s`。accepted 与 terminal business/Core 相等，unfinished/rejected/error/timeout/producer-starvation 均为 `0`。
- JFR 轮：terminal business `7,393.903 ops/s`，terminal Core messages `7,401.123/s`，trades `3,696.951/s`；accepted 与 terminal 相等，unfinished/rejected/error/timeout 均为 `0`。JFR 轮带 profiler，不与主轮吞吐作绝对值比较。
- JFR：`DataLoss=0`；workload `PLACE_ORDER` 样本 `114,688`，scheduled/terminal business 均 `114,688`；entry→accepted p50/p90/p95/p99/p99.9/max=`1.0737/2.1475/2.1475/2.1475/4.2949/2.1680s`，accepted→terminal=`16.777/33.554/33.554/33.554/67.109/43.557ms`，entry→terminal=`1.0737/2.1475/2.1475/2.1475/4.2949/2.1858s`；quantile 为 merged Log2 histogram 桶值。
- JFR 归因：benchmark worker execution samples `637`，core-fact-materializer `97`，matcher-0 `69`，matcher-1 `47`，4 个 Account Lane 合计 `161`；主要业务热点为 `progressPlaceAdmissions=37`、`completeMatching=26`、`commitReadyMatching=24`、`awaitMatchingResult=19`、`CoreStateHash.mix=10`。采样分配约 `8.143GB`，约 `71,002 B/terminal business op`（JFR 采样权重）；主要分配为 `ByteBuffer.allocate`、`LongObjectHashMap.addKeyValueAtIndex`、`TreeMap.put`、`RuntimeIdentityRegistry.asset`、`TradingRuntimeState.prepareFactFrame`。
- GC/资源：ZGC `4` 次，allocation stall/failure=`0`；GC pause p50/p95/p99/max=`0.009/0.028/0.029/0.029ms`，最长 Concurrent Mark=`155.191ms`；owner 同步 file/socket/database I/O=`0 events/0 B`；swap=`0`，Pages throttled=`0`。短 JFR 不作无泄漏结论。
- JFR 异常门禁：记录 `1,018` 个 Java exception/error throw；严格 analyzer 返回 `rc=1`，原始 JFR 与分析文件已保留。本轮按 matcher=2 诊断数据记录，不作为正式容量验收。
- 与最近同场景 matcher=1 主轮 `PV-20260903-256-30` 的 `9,078.890 terminal business ops/s` 相比，本轮 matcher=2 平均值高 `31.203%`；但当前只有 3 个 measurement 样本且误差较大，暂不认定为稳定收益。
- artifact：`target/qualification/20260903T143524Z-matcher2-256-scale-scale/`；`saturation.jfr` SHA-256 `1912c01b22eb790d8da30b04a404423065433874c6dad063d9ffc97d94a793b5`；主 JMH JSON SHA-256 `d13de52ff518a96fff56f11843e9d1c3494ce45a89fa218d17758a1d9b8ee13a`；JFR profile JSON SHA-256 `c756713a488b792e02acbf80c139cd67f124b279fdbb38c303077dcfd99509d4`；JFR aggregate 位于 `saturation-jfr-analysis/aggregate.json`。

### 2026-09-03 16:17:47 +08:00 — `PV-20260903-256-34` — `采集前锁定（删除 exporter/market-data/WebSocket 附加链路后的单 matcher 交易链路）`

#### 采集前锁定

- 被测代码：当前 dirty 工作树，HEAD `a85545622c48b60f5f22ee9695044be28074e159`，工作树 diff SHA-256 `8e201968b42bee61a195addb1308dd9d85790bd28fb7cc0425103c8618a58c26`；对照为同机同场景 matcher=1 的 `PV-20260903-256-30`（`9,078.890 terminal business ops/s`）。修改点为删除 exporter/history projection、Core Fact 审计/导出物化、Core market-data projection、公开行情 WebSocket fanout 与连续性审计；保留撮合、账户、资金、持仓、结算、风险、不可变交易状态及 snapshot recovery。
- 范围：仅测试进程内 `LINEAR_PERPETUAL` 交易链路；不启动或测试 PostgreSQL、Docker、exporter、wallet、Kafka、API、WebSocket、market-data 服务及其他五产品线。正式口径固定 1 matching engine、0 exchange-core risk engine、1 Product Core risk engine、4 Account Lane、1 JMH worker/进程内连接。
- 场景：10,000 活跃用户、512 listed/active symbols、每用户最多5持仓/10活动订单；每 invocation 16,384 个 PLACE_ORDER，50% maker GTC + 50% taker IOC，同 symbol/价格/数量配对成交，做市状态持续运行；open-loop constant-arrival offered rate `100,000 business ops/s`，记录计划到达延迟并修正 coordinated omission；严格且仅使用 `256 in-flight`。
- 正确性门禁：`acceptedBusinessOperations == terminalBusinessOperations`、accepted/terminal Core messages 相等，unfinished/rejected/error/timeout/producer-starvation 均为0，期末 matcher/Lane/in-flight backlog为0，trades为 terminal business 的50%；benchmark teardown 必须完成资金守恒、余额/冻结/持仓、订单生命周期终态、盘口及 snapshot recovery 校验。
- 性能门禁：无 profiler 主轮 `fork=1、warmup=3x3s、measurement=3x5s、thread=1`，相对 PV-30 不接受超过10%的 terminal business ops/s 回归；分别记录 terminal business ops/s、terminal Core messages/s、trades/s、backlog，以及 PLACE_ORDER entry→accepted、accepted→terminal、entry→terminal 的 p50/p90/p95/p99/p99.9/max。JFR 轮 `fork=0、warmup=1x3s、measurement=1x10s` 只作归因，不与主轮绝对吞吐比较。
- 环境与有效性：Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16；MacBookPro16,1 / Intel Core i9-9880H / 16 logical CPU / 16GiB / macOS 26.7 x86_64，无容器绑核。采集前 swap 已占用 `661MiB`，因此本轮仅在 page-out 不增长、Pages throttled=0、JFR DataLoss=0、无明显同机干扰/CPU throttling时作为部分验证，不能作为完整生产容量验收。
- JVM/JFR：`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --enable-native-access=ALL-UNNAMED`，settlement `BLOCKING`，journal `65536/1GiB`；JFR 使用 `owner-commit-profile.jfc`，启用 NMT summary 和 GC/safepoint log，保存 CPU/热点、allocation、GC/heap/native、线程/锁/park、safepoint/VM/JIT、I/O/异常证据。本轮不执行长稳，不能据此声明无泄漏。
- 构建：生产源码与 benchmark shaded JAR 使用 `mvn -pl :surprising-aeron-benchmarks -am -Dmaven.test.skip=true package` 构建成功；JAR SHA-256 `e9f0c476ba35225dd6067402a0bf7077ecc1441a31d53f38d2bc0c925f1e2d54`，JFC SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。普通 `-DskipTests package` 因删除功能后遗留测试源码仍引用 Core Fact/export API 而在 testCompile 阶段失败；本轮不运行这些 exporter 测试，交易正确性以 benchmark 真实路径 teardown 校验为门禁。
- 执行命令：直接运行 `product-core-benchmarks.jar` 的 `LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`，固定参数 `accountLanes=4, activeUsers=10000, listedSymbols=512, activeSymbols=512, matchingEngines=1, maxPositionsPerUser=5, maxOpenOrdersPerUser=10, maxInFlight=256, operationsPerInvocation=16384, targetOperationsPerSecond=100000`；主轮输出 `saturation-main.json`，JFR轮输出 `saturation-profile.json` 与 `saturation.jfr`。artifact 固定为 `target/qualification/20260903T161747Z-trading-only-matcher1-256/`。本条锁定后不修改标准、场景或参数，失败和异常仅追加结果。

#### 采集结果

- 无 profiler 主轮：`19,119.130 terminal business ops/s`、`19,268.435 terminal Core messages/s`、`9,559.565 trades/s`；3个样本为 `19,165.412/19,600.380/18,591.599 business ops/s`。accepted 与 terminal business/Core 全部相等，unfinished/rejected/error/timeout/producer-starvation 均为0；Lane settlement `28,678.695 ops/s`，期末 teardown、资金/订单终态与 snapshot recovery 校验通过。相对 PV-30 的 `9,078.890 ops/s` 提升 `110.59%`。
- JFR 轮：`17,071.777 terminal business ops/s`、`17,205.150 terminal Core messages/s`、`8,535.889 trades/s`，accepted/terminal相等且业务错误为0；profiler结果不与主轮绝对比较。原始 JFR `115MiB`，SHA-256 `2a448972f1ec7e4655b55947aa930dad36728d0ec08f456fb23e26f5858595f2`；主 JMH JSON SHA-256 `0ad87a6f85cffdd65a2f00d521daf0daa614c93fc3e036719705bc70694c320d`，JFR JSON SHA-256 `35afb4b8c2bdde9bc564e4aa4caa36ba60d6d412a9738f2aebe0abaef15312d0`。
- 环境有效性：Pages throttled保持0，但系统 swap 从 `661MiB` 增至 `857.25MiB`，违反锁定的数据有效性条件；JFR离线聚合被后续 matcher=2 请求中断。因此本轮是部分验证，不作为正式生产容量验收，也不作无泄漏结论。

### 2026-09-03 16:23:25 +08:00 — `PV-20260903-256-35` — `采集前锁定（删除附加链路后的 matcher=2 吞吐诊断）`

#### 采集前锁定

- 目的与对照：在 PV-34 相同代码/JAR、机器、JVM、LINEAR_PERPETUAL 场景和 offered rate 下，仅把 `matchingEngines` 从1改为2；对照为 PV-34 的 `19,119.130 terminal business ops/s`。当前 dirty 工作树 HEAD `a85545622c48b60f5f22ee9695044be28074e159`；JAR SHA-256 `e9f0c476ba35225dd6067402a0bf7077ecc1441a31d53f38d2bc0c925f1e2d54`。
- 固定场景：10,000 活跃用户、512 listed/active symbols、每用户最多5持仓/10活动订单、4 Account Lane、2 matching engines、0 exchange-core risk engine、1 Product Core risk engine、1 JMH worker/进程内连接；每 invocation 16,384 PLACE_ORDER，50% maker GTC + 50% taker IOC，同 symbol/价格/数量配对成交，做市持续运行；open-loop offered `100,000 business ops/s`；严格且仅使用 `256 in-flight`。
- 正确性门禁：accepted/terminal business 与 Core messages分别相等；unfinished/rejected/error/timeout/starvation均为0；期末 matcher/Lane/in-flight backlog为0；trades为business的50%；资金守恒、余额/冻结/持仓、订单终态、盘口和snapshot recovery通过。
- 采集参数：无 profiler 主轮 `fork=1、warmup=3x3s、measurement=3x5s、thread=1`；JFR轮 `fork=0、warmup=1x3s、measurement=1x10s`，JFR吞吐不与主轮绝对比较。JVM为HotSpot JDK25、8GiB ZGC、AlwaysPreTouch、BLOCKING settlement、journal 65536/1GiB；JFR使用同一 `owner-commit-profile.jfc` 并启用NMT和GC/safepoint日志。
- 有效性与范围：采集前 swap `857.25MiB`，只在page-out不增长、Pages throttled=0、JFR DataLoss=0且无明显CPU干扰时作为诊断对照；不执行长稳，不宣称无泄漏或正式容量。只测交易链路，不启动/测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data或其他产品线。
- artifact与命令：直接运行同一 `product-core-benchmarks.jar` 的 `LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`，固定 PV-34 全部参数，仅改 `-p matchingEngines=2` 和 `-Dsurprising.aeron.matching-engines=2`；artifact固定为 `target/qualification/20260903T162325Z-trading-only-matcher2-256/`。锁定后不修改标准、场景或参数。

#### 采集结果

- 无 profiler 主轮：`19,422.974 terminal business ops/s`、`19,574.653 terminal Core messages/s`、`9,711.487 trades/s`；3个 business 样本为 `20,103.918/19,563.410/18,601.594 ops/s`。accepted 与 terminal business/Core 相等，unfinished/rejected/error/timeout/producer-starvation均为0；Lane settlement `29,134.461 ops/s`，benchmark teardown 的资金、订单终态及snapshot recovery校验通过。
- matcher扩展性：同一JAR、同场景的 matcher=1 为 `19,119.130 terminal business ops/s`，matcher=2 仅提升 `1.59%`；两个三样本区间高度重叠，不能认定为稳定收益。当前约 `19k–20k terminal business ops/s` 的共享 owner/状态提交链路已成为主要平台，而不是单 matcher 撮合算力。
- JFR归因轮：`16,180.879 terminal business ops/s`、`16,307.292 terminal Core messages/s`、`8,090.439 trades/s`，accepted/terminal相等且业务错误为0。196,608个PLACE_ORDER延迟样本：entry→accepted p50/p90/p95/p99/p99.9/max=`536.871ms/1.074s/1.074s/2.147s/2.147s/1.377s`；accepted→terminal=`16.777ms/33.554ms/33.554ms/33.554ms/67.109ms/61.423ms`；entry→terminal=`536.871ms/1.074s/1.074s/2.147s/2.147s/1.391s`。分位数来自Log2 histogram桶，offered 100k远超实际处理能力，因此入口排队延迟很高。
- CPU/分配/GC：execution samples由JMH owner worker `734`主导，两个matcher分别`69/43`，4个Account Lane合计`156`；热方法为`TreeMap.put`、`completeMatching`、`LaneClientOrderCaptures.contains`、`commitReadyMatching`、`progressPlaceAdmissions`。JFR sampled allocation约`8.542GB`、`43,445.7 sampled B/business op`，主要来自TreeMap、LongObjectHashMap、OrderRuntime与`prepareFactFrame`；4次ZGC、allocation stall为0、最大暂停`0.0589ms`。owner同步文件/网络/数据库I/O为0，JFR `DataLoss=0`。
- 有效性：JFR记录约1,015个JVM启动/反射探测异常事件（主要NoSuchFieldException、NoSuchMethodError、UnsatisfiedLinkError），不属于business error，但不满足严格零异常门禁；采集期间swap由`857.25MiB`降至`761.25MiB`、Pages throttled保持0。未执行长稳及独立`-prof gc`，故本轮是matcher扩展性诊断，不是正式容量或无泄漏验收。
- artifact：`target/qualification/20260903T162325Z-trading-only-matcher2-256/`；主JMH JSON SHA-256 `f618987e6f3a16e0e9738baaf966481fc7cab1c2b44cb91990f420229905f5e4`；JFR JSON `6d91834e65fb89c2b8ce0c823d7e0ecaac657a7f168154fa59f5d07f791f55b6`；原始JFR `120MiB`，SHA-256 `7c310534fa0f368db09073d9bdfeb15dab61ac3a4a812ba79c58c38fbe7779f3`；aggregate SHA-256 `20bafb573fd34087768d37188317c8c0b976bd26a0a4c835d70341ca44347ce1`。`git diff --check`通过。

### 2026-09-03 17:19:12 +08:00 — `PV-20260903-256-36` — `采集前锁定（彻底移除 Core Fact/export 热路径后的单 matcher 交易链路）`

#### 采集前锁定

- 被测代码：当前 dirty 工作树，HEAD `88e23f7517fd805fe759091befb2d42be26ad0d0`，工作树 diff SHA-256 `7562d431b39a5592c2180ce9f98c0732728625b0ac74f49bd23e24e4ae81a355`；对照为 PV-34 同机 matcher=1 的 `19,119.130 terminal business ops/s`。本轮删除逐命令 before/after Fact frame、`CoreCommandDelta`、Fact admission/预算、export draft/队列/materializer/编码、逐命令审计 hash、执行 DTO 列表和无调用方的变更集合；状态索引与资金 delta 改为 owner 直接提交，保留资金守恒、撮合证据、账户/持仓/订单、风险、Aeron snapshot 与恢复。
- 测试范围：仅 `LINEAR_PERPETUAL` 进程内交易链路；不启动或测试 PostgreSQL、Docker、exporter、wallet、Kafka、API、WebSocket、market-data 及其他五产品线。固定 1 matching engine、0 exchange-core risk engine、1 Product Core risk engine、4 Account Lane、1 JMH worker。
- 主场景：10,000 活跃用户、512 listed/active symbols、每用户最多5持仓/10活动订单；每 invocation 16,384 PLACE_ORDER，50% maker GTC + 50% taker IOC，同 symbol/价格/数量配对成交，做市持续运行；open-loop offered `100,000 business ops/s`，coordinated omission corrected；严格且仅 `256 in-flight`。
- 正确性门禁：accepted business/Core messages 分别等于 terminal，unfinished/rejected/error/timeout/producer-starvation 均为0，期末 matcher/Lane/in-flight backlog为0，trades为 terminal business 的50%；teardown 必须验证资金守恒、余额/冻结/持仓、订单生命周期终态、盘口和 snapshot recovery。已完成的定向测试必须保持 `service 115/115`、benchmark-support `10/10` 通过。
- 性能门禁：无 profiler 主轮 `fork=1、warmup=3x3s、measurement=3x5s、thread=1`，相对 PV-34 不接受超过10%的 terminal business ops/s 回归；记录 terminal business/Core messages/s、trades/s、Lane工作量、backlog及三段延迟。新增真实 full-fill owner commit JMH 单独执行 `fork=1、warmup=3x3s、measurement=3x5s、thread=1` 并记录 `-prof gc` 分配。JFR轮 `fork=0、warmup=1x3s、measurement=1x10s` 只作热点归因，不与主轮绝对吞吐比较。
- 环境：Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16；MacBookPro16,1 / Intel Core i9-9880H / 16 logical CPU / 16GiB / macOS 26.7 x86_64。采集前 swap=`393.75MiB`、Pages throttled=0，因此只在 page-out 不增长、JFR DataLoss=0且无明显干扰时作为部分验证；不执行长稳，不能据此声明无泄漏或正式生产容量。
- JVM/JFR：`-Xms8g -Xmx8g -XX:SoftMaxHeapSize=8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:NativeMemoryTracking=summary --enable-native-access=ALL-UNNAMED`，settlement `BLOCKING`，journal `65536/1GiB`；JFR 使用 `owner-commit-profile.jfc`，记录 CPU/热点、allocation、GC/heap/native、线程/锁/park、safepoint/VM/JIT、I/O/异常。shaded JAR SHA-256 `d92929eb89053fe444a359125bdce17434a266de69e79f45fe499a4f10c284d9`，JFC SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。
- artifact 与命令：固定目录 `target/qualification/20260903T091912Z-direct-commit-matcher1-256/`。使用 `product-core-benchmarks.jar` 运行 `LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`，显式参数 `accountLanes=4,activeUsers=10000,listedSymbols=512,activeSymbols=512,matchingEngines=1,maxPositionsPerUser=5,maxOpenOrdersPerUser=10,maxInFlight=256,operationsPerInvocation=16384,targetOperationsPerSecond=100000`；另运行 `LinearPerpetualCoreBenchmark.tradingCommitFullFill` 和 `-prof gc`；JFR轮使用相同 saturation 参数。锁定后不修改标准、场景或参数，失败和异常只追加结果。

#### 中间轮结果（后续发现 benchmark 残留，不能作为最终代码结果）

- 第一次主轮因缺少 Agrona 所需的 `jdk.internal.misc` opens/exports 在 warmup 初始化失败，未产生吞吐样本；补齐与既有脚本一致的 JVM opens 后主轮成功，terminal business/Core messages 均为 `21,237.601/s`，trades `10,618.801/s`，三个 business 样本 `22,326.911/19,236.689/22,149.204/s`，accepted=terminal，unfinished/rejected/error/timeout/starvation=0，资金、订单终态和 snapshot recovery teardown 通过。
- `tradingCommitFullFill` 主轮 `62.179 us/op`；`-prof gc` 为 `65.139 us/op`、`11,837,518.809 B/op`、`5,473.366 MiB/s`。该 micro benchmark 包含每 invocation 的完整交易场景构造和关闭，分配数字不能解释为纯 commit 单操作分配。
- JFR轮 terminal business/Core messages `26,169.171/s`、trades `13,084.585/s`，业务门禁通过；`DataLoss=0`，4次ZGC，最大GC pause `0.044 ms`，allocation stall=0；sampled allocation约 `9.775GB`、`29,832 sampled B/business op`。`prepareFactFrame`、`CoreCommandDelta`、`core-fact-materializer`、`RuntimeFactFrameBuilderPool` 和 export event 均未出现在聚合热点/线程中。主要CPU热点转为 `progressPlaceAdmissions`、`assertAccountLanesHealthy`、`TreeMap.put`、primitive map lookup 和 `LaneClientOrderCaptures.contains`；owner同步I/O为0。
- JFR 记录327,680个PLACE_ORDER；entry→accepted p50/p90/p95/p99/p99.9/max=`268.435/536.871/1,073.742/1,073.742/1,073.742/1,040.468 ms`，accepted→terminal=`8.389/16.777/16.777/33.554/134.218/379.784 ms`，entry→terminal=`268.435/536.871/1,073.742/1,073.742/1,073.742/1,047.187 ms`，分位数为Log2桶。100k offered远超处理能力，入口排队延迟不代表服务容量点延迟。
- 环境/严格门禁：swap保持`393.75MiB`，但系统Pageouts从44,406升至50,832；JFR含1,003个JVM启动/反射探测异常，严格 analyzer `rc=1`。本轮仅为部分归因。之后又发现 benchmark 中仍有永远不执行的 export ACK 代码和无效 Fact-frame-capacity 参数并删除，因此本条所有数值只记录为中间代码诊断，不作为最终交付版本性能结果。artifact SHA-256：main `48ea544964edbde87e96e99eea7266ae0408eff0003842d4221ca83ddf9f85dd`，micro `cdbcf52c9e70a53ef931f0f5d71948b19fafec5ef20a2f0e6ab95f96449ea44e`，micro GC `32af045d69d57ed7ca8ddfcf6189583fc4c349030e5ece2a7e651f80eb5a985a`，JFR JSON `d2afaf98a4bccbe3cb0f5c52fa91455c3f195bc299f24ca71252692cc02b6e23`，JFR `1c35bff1d87b179a6affe32170c64943b89ad0538b8a322e70ebca67e29d2fed`，aggregate `80e2daa11298ec6b718f52d496962a28ef9d388e9cbf0c898c6c2c015395778c`。

### 2026-09-03 17:29:15 +08:00 — `PV-20260903-256-37` — `采集前锁定（最终无 export/Fact benchmark 残留的单 matcher 交易链路）`

#### 采集前锁定

- 被测代码：当前 dirty 工作树，HEAD `88e23f7517fd805fe759091befb2d42be26ad0d0`，最终工作树 diff SHA-256 `c92985ce6120bb3421e023c2d2707692d9790c0e5cf592a798acd6ad04f24e4c`；对照仍为 PV-34 `19,119.130 terminal business ops/s`。相较PV-36额外删除 benchmark 内无调用方的 export ACK、export 状态诊断和 Fact-frame-capacity 参数，不改变交易场景或业务逻辑。
- 测试与构建：HotSpot JDK25；最终 benchmark-support `10/10` 通过，shaded JAR构建成功，SHA-256 `165d01806847426c0dad7c4a01fcfd0c33c3ab88335a13b3685f946b62bfbfd7`。service 定向交易/资金/snapshot测试沿用本轮已通过的 `115/115`；不测试 PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data 或其他产品线。
- 场景、门禁和JVM：与PV-36完全相同，严格固定 matcher=1、4 Account Lane、10,000用户、512 symbols、50% maker+50% IOC、16,384 ops/invocation、100,000 offered、`256 in-flight`；主轮 `3x3s + 3x5s, fork=1`，JFR轮 `1x3s + 1x10s, fork=0`；8GiB ZGC、NMT summary、同一JFC。accepted/terminal、unfinished、backlog、错误、成交、资金、订单终态和snapshot门禁不变；主吞吐相对PV-34不得回归超过10%。
- 环境有效性：采集前 swap=`393.75MiB`、Pages throttled=0、Pageouts=50,832；只在Pageouts不增长、JFR DataLoss=0且无明显干扰时作部分验证，不执行长稳，不宣称无泄漏或正式生产容量。
- artifact与命令：最终目录固定 `target/qualification/20260903T092915Z-final-direct-commit-matcher1-256/`；运行最终JAR的 `saturatedMatchingWorkload` 主轮和JFR轮，参数与PV-36一致但不再存在 `factFramePoolCapacity`；失败和异常只追加结果。

#### 采集结果

- 最终无 profiler 主轮：`20,714.105 terminal business ops/s`、`20,714.105 terminal Core messages/s`、`10,357.053 trades/s`；三个 business 样本为 `22,744.706/20,464.225/18,933.385 ops/s`。accepted 与 terminal business/Core 相等，unfinished/rejected/error/timeout/producer-starvation均为0；Lane settlement `31,071.158 ops/s`，teardown 的资金、余额/冻结/持仓、订单终态、盘口及snapshot recovery校验通过。相对PV-34的`19,119.130/s`提升`8.34%`，通过预设“不回归超过10%”门禁；样本波动大，不能把峰值当稳定容量。
- 最终JFR轮：`27,176.418 terminal business ops/s`、`27,176.418 terminal Core messages/s`、`13,588.209 trades/s`；accepted=terminal且业务错误为0。带profiler且fork=0，不与主轮绝对吞吐比较。
- JFR热点：owner/JMH worker `942` 个execution samples，matcher `71`，4个Account Lane合计`226`；主要方法为`progressPlaceAdmissions=114`、`TreeMap.put=57`、`assertAccountLanesHealthy=55`、primitive map lookup、`TreeMap.getEntry/successor`、`commitReadyMatching=13`和`LaneClientOrderCaptures.contains=13`。`prepareFactFrame`、`CoreCommandDelta`、Fact builder pool、`core-fact-materializer`和export event在聚合热点与线程中均为0/不存在，说明本轮删除目标已离开交易热路径。
- 分配/GC/heap：JFR sampled allocation约`9.999GB`、`29,060.7 sampled B/business op`，主要分配点为`TreeMap.put`、primitive map扩容/插入、`OrderReservation.validSymbol`、`CoreOrderDecisionResolver.boundedMark`、identity symbol和`OrderRuntime`；4次ZGC、allocation stall/failure=0，pause p50/p95/p99/max=`0.0096/0.0278/0.0527/0.0527 ms`，最长Concurrent Mark=`151.825 ms`。heap committed峰值8GiB、used峰值约2.40GiB，四次GC后live set为`50/274/484/624 MiB`；短轮不能据此判定泄漏。
- 延迟：344,064个PLACE_ORDER样本；entry→accepted p50/p90/p95/p99/p99.9/max=`268.435/536.871/536.871/1,073.742/1,073.742/709.815 ms`，accepted→terminal=`8.389/16.777/16.777/16.777/67.109/90.091 ms`，entry→terminal=`268.435/536.871/536.871/1,073.742/1,073.742/717.446 ms`；分位数为合并Log2桶，100k offered导致入口排队。
- 线程/I/O/VM：owner正式路径同步file/socket/database I/O为0；JFR `DataLoss=0`。最大GC pause远低于业务尾延迟；最大safepoint结束暂停`0.834 ms`，最大到达safepoint`2.094 ms`。NMT退出汇总为native committed约`8.15 GiB`（主要是8GiB Java heap），32线程；没有长期NMT差分，不能给出native泄漏结论。
- 严格有效性：JFR仍记录1,003个JVM启动/反射探测异常（594 NoSuchFieldException、312 NoSuchMethodError等），analyzer严格零异常门禁`rc=1`；swap保持`393.75MiB`，Pages throttled=0，但Pageouts从50,832增至51,799。因此本轮结论为“交易正确性和短时性能通过、完整性能验收部分通过”，不宣称正式生产容量或无泄漏。
- artifact SHA-256：main JSON `6d26b27543025bb5f181d95418ac628731b4be1b3de51c93ef765ac83740d602`，JFR JSON `57285969ea101623bf436c6e6166329d8fb8cea86ee9d06b83ddfe96c36db542`，原始JFR `b1bf47468fe59048550ad8b461219798eb46bf4b86158e301b95a8f14aa4a9cc`，aggregate `5338eac15228897d191df1127042fe9e0c7305d0f756c8f26a64814d12922169`；目录 `target/qualification/20260903T092915Z-final-direct-commit-matcher1-256/`。

### 2026-09-03 17:38:35 +08:00 — `PV-20260903-256-38` — `采集前锁定（最终代码 matcher=2 扩展性诊断）`

#### 采集前锁定

- 目的与对照：在最终提交 `1613a7a806557f1657b06fdd9e3ee09360ad4ca6` 上，仅将 matching engines 从1改为2，诊断删除 Core Fact/export 热路径后的 matcher 扩展性；对照为PV-37最终代码 matcher=1的`20,714.105 terminal business ops/s`。本轮不作为默认单matcher正式验收结论。
- 固定场景：仅`LINEAR_PERPETUAL`进程内交易链路；10,000活跃用户、512 listed/active symbols、每用户最多5持仓/10活动订单、4 Account Lane、2 matching engines、0 exchange-core risk engine、1 Product Core risk engine、1 JMH worker；每invocation 16,384 PLACE_ORDER，50% maker GTC + 50% taker IOC，同symbol/价格/数量配对，做市持续运行；open-loop offered `100,000 business ops/s`并修正coordinated omission；严格且仅`256 in-flight`。
- 正确性门禁：accepted business/Core分别等于terminal；unfinished/rejected/error/timeout/producer-starvation均为0，期末matcher/Lane/in-flight backlog为0，trades为business的50%；资金守恒、余额/冻结/持仓、订单终态、盘口和snapshot recovery必须通过。
- 采集参数：Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16、8GiB ZGC、AlwaysPreTouch、BLOCKING settlement、journal 65536/1GiB；无profiler主轮`fork=1、warmup=3x3s、measurement=3x5s、thread=1`。本轮只采matcher=2主吞吐，不重复JFR、GC或长稳；matcher=1最终JFR已记录于PV-37。
- 环境与范围：采集前swap=`393.75MiB`、Pages throttled=0、Pageouts=51,799；如Pageouts增长则仅作为诊断数据。不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data或其他产品线。
- artifact与命令：使用SHA-256为`165d01806847426c0dad7c4a01fcfd0c33c3ab88335a13b3685f946b62bfbfd7`的最终shaded JAR，运行`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`，固定PV-37全部参数，仅改`-p matchingEngines=2`和`-Dsurprising.aeron.matching-engines=2`；artifact固定为`target/qualification/20260903T093835Z-final-direct-commit-matcher2-256/`。锁定后不修改场景、参数或门禁。

#### 采集结果

- 无profiler主轮：`21,478.824 terminal business ops/s`、`21,478.824 terminal Core messages/s`、`10,739.412 trades/s`；三个business样本为`22,511.651/21,053.899/20,870.920 ops/s`。accepted与terminal business/Core分别相等，unfinished/rejected/error/timeout/producer-starvation均为0；benchmark正常完成teardown，资金、余额/冻结/持仓、订单终态、盘口及snapshot recovery门禁通过。
- matcher扩展性：相对PV-37同一最终JAR、同场景matcher=1的`20,714.105 terminal business ops/s`，matcher=2增加`764.719 ops/s`，仅提升`3.69%`。两个三样本区间重叠，不能认定为稳定扩展收益；当前约`21k terminal business ops/s`的平台主要受共享owner、账户Lane和状态提交路径限制，而不是单matcher撮合算力。
- 有效性与范围：swap保持`393.75MiB`、Pages throttled保持0，但Pageouts从`51,799`增至`52,997`，因此本轮仅作为matcher扩展性诊断，不作为正式生产容量验收。按锁定范围未重复JFR、GC或长稳；matcher=1最终JFR归因见PV-37。
- artifact：`target/qualification/20260903T093835Z-final-direct-commit-matcher2-256/saturation-main.json`，SHA-256 `415cc1fcbe0bc5ea8ddb5f56d11e14ef03dd7cdb1cea1dd4e5e7358b51ab205c`。

### 2026-09-03 19:37:59 +08:00 — `PV-20260903-256-39` — `采集前锁定（owner completion event-loop）`

#### 采集前锁定

- 被测代码：dirty工作树，HEAD `ff2aadde6ef980444cc90ade95632a1d5fddc305`，除本文件外diff SHA-256 `0572d9411ebb001c6327d8a31826353083400f77b41389db826baae7e917f362`；对照为PV-37最终matcher=1的`20,714.105 terminal business ops/s`。修改点为Lane通过固定容量SPSC sequence queue发布admission/settlement completion，owner使用O(1) ready ring推进matching continuation，同一批内Lane派发后继续处理其他ready sequence，并把Lane健康检查收敛到commit批次边界。
- 范围：仅`LINEAR_PERPETUAL`进程内交易链路；不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线。正式主轮固定1 matching engine、0 exchange-core risk engine、1 Product Core risk engine、4 Account Lane、1 JMH worker。
- 场景：10,000活跃用户、512 listed/active symbols、每用户最多5持仓/10活动订单；每invocation 16,384 PLACE_ORDER，50% maker GTC + 50% taker IOC，同symbol/价格/数量配对成交，做市持续运行；open-loop offered `100,000 business ops/s`并修正coordinated omission；严格且仅`256 in-flight`。
- 正确性门禁：accepted business/Core分别等于terminal，unfinished/rejected/error/timeout/producer-starvation均为0，期末matcher/Lane/in-flight backlog为0，trades为business的50%；teardown必须通过资金守恒、余额/冻结/持仓、订单终态、盘口和snapshot recovery。已通过service定向测试`56/56`与benchmark-support`10/10`。
- 性能门禁：无profiler主轮`fork=1、warmup=3x3s、measurement=3x5s、thread=1`，相对PV-37不接受超过10%回归；记录terminal business/Core messages、trades、Lane工作量、backlog和三段延迟。JFR轮`fork=0、warmup=1x3s、measurement=1x10s`仅作归因，不与主轮绝对吞吐比较；检查owner/Lane/matcher热点、分配、GC、heap/native、线程/锁、safepoint、JIT、I/O、异常和DataLoss。短轮不作无泄漏结论。
- 环境：Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16；MacBookPro16,1 / Intel Core i9-9880H / 16 logical CPU / 16GiB / macOS 26.7 x86_64。采集前swap=`361.75MiB`、Pages throttled=0、Pageouts=52,997；Pageouts增长或JFR DataLoss非0则仅作部分验证。
- JVM/JFR：8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、BLOCKING settlement、journal 65536/1GiB及既有Agrona opens/exports；JFR使用`owner-commit-profile.jfc`。最终shaded JAR SHA-256 `c3410c2c524d6b6b515c5dbf980810cf33b976b7f73b3d7b45e89edccbdfbfc2`。
- artifact与命令：目录固定`target/qualification/20260903T113759Z-owner-event-loop-matcher1-256/`；运行`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`，参数固定`accountLanes=4,activeUsers=10000,listedSymbols=512,activeSymbols=512,matchingEngines=1,maxPositionsPerUser=5,maxOpenOrdersPerUser=10,maxInFlight=256,operationsPerInvocation=16384,targetOperationsPerSecond=100000`。锁定后不修改场景、参数或门禁，异常和失败只追加结果。

#### 采集结果

- 主轮在trial模板初始化期间失败，未进入有效warmup/measurement，`saturation-main.json`无吞吐样本。原因是同步测试API直接消费matcher completion并删除pending后，第一版独立ready FIFO仍保留陈旧sequence，历史命令累计后触发容量保护。该实现已废弃，本轮无可用性能结论。

### 2026-09-03 19:40:33 +08:00 — `PV-20260903-256-40` — `采集前锁定（intrusive owner-ready event-loop）`

#### 采集前锁定

- 被测代码：dirty工作树，HEAD仍为`ff2aadde6ef980444cc90ade95632a1d5fddc305`，除本文件外diff SHA-256 `cccf1b109aab3508d44c3527d0ef099dbc1c444860f7b0953f3067c5d6b8db24`；shaded JAR SHA-256 `dccf46c6b54af615942200fb42a391f9fe6a14edbdf8f195e9a3d8a54ae4f357`。相对PV-39仅把独立ready FIFO替换为嵌入`PendingMatchingRing` slot的intrusive ready list，pending删除时O(1)撤销通知，避免陈旧sequence与历史容量增长。
- 对照、场景、正确性门禁、JMH/JFR参数、JVM参数和测试范围与PV-39完全一致，不作其他修改：matcher=1、4 Account Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、100,000 offered、严格`256 in-flight`。主轮相对PV-37 `20,714.105 terminal business ops/s`不得回归超过10%；JFR仅作归因。
- 测试：service定向`58/58`通过，benchmark-support`10/10`通过；包含intrusive ready去重、同步删除撤销和slot复用测试。
- 环境：采集前swap=`361.75MiB`、Pages throttled=0、Pageouts=53,008；有效性条件和未测范围沿用PV-39。
- artifact固定为`target/qualification/20260903T114033Z-owner-intrusive-ready-matcher1-256/`；命令沿用PV-39，仅修改输出目录。锁定后不修改标准、场景或参数。

#### 采集结果（后续双matcher发现提交序缺陷，不能作为最终代码结果）

- 无profiler主轮：`22,441.888 terminal business ops/s`、`22,441.888 terminal Core messages/s`、`11,220.944 trades/s`；三个business样本为`25,048.656/18,581.166/23,695.843 ops/s`。accepted与terminal business/Core相等，unfinished/rejected/error/timeout/starvation均为0；Lane settlement `33,662.833/s`，资金、余额/冻结/持仓、订单终态、盘口及snapshot recovery teardown通过。相对PV-37提升`8.34%`。
- JFR归因轮：`28,080.935 terminal business/Core messages/s`、`14,040.467 trades/s`，业务门禁闭合；profiler/fork=0数值不与主轮绝对比较。owner/JMH worker有851个execution samples，matcher 74，4个Account Lane合计216；主要业务热点为`progressPlaceAdmissions=86`、`completeMatching=83`、`TreeMap.put=59`、primitive map访问及`commitReadyMatching=14`。说明逐槽ready扫描已经移除，但owner仍执行admission与settlement/state commit业务。
- 分配与GC：sampled allocation约`10.254GB`、`28,448 sampled B/business op`，主要来自primitive数组、`TreeMap.Entry`、`CoreOrderState`、`OrderRuntime`及BigInteger；4次ZGC，allocation stall/failure=0，pause p50/p95/p99/max=`0.0106/0.0389/0.0473/0.0473 ms`。heap committed峰值8GiB、used峰值约5.98GiB，GC后live set为`50/274/496/600 MiB`；短轮不能证明无泄漏。
- 延迟与系统：360,448个PLACE_ORDER样本；entry→accepted p50/p90/p95/p99/p99.9/max=`268.435/536.871/536.871/1,073.742/1,073.742/849.779 ms`，accepted→terminal=`8.389/16.777/16.777/16.777/67.109/220.752 ms`，entry→terminal=`268.435/536.871/536.871/1,073.742/1,073.742/857.650 ms`。offered 100k高于处理能力，因此入口排队延迟很高。owner同步I/O=0、socket I/O=0、DataLoss=0；最大GC pause`0.0473 ms`，最大safepoint结束暂停`0.732 ms`，但一次到达safepoint耗时`210.950 ms`。
- 严格门禁与artifact：JFR含1,003个JVM启动/反射探测异常，严格零异常analyzer返回非零；Pageouts由53,008增至53,567，故仅作部分归因。之后PV-41在matcher=2发现ready完成序可能越过全局提交序，代码已修改为只提交deterministic pending head，所以本轮不是最终代码性能证据。main/profile/JFR/aggregate SHA-256分别为`8e0f4f6ecd1a99f2ba1714757c68cef2c5373a33f266aac25098ae899ae8a033`、`2d94f07ecf1294801165eb20460cf267bdc8cf56e4ef246642483c38628e9069`、`828d971afcff6ee6c7bf78cdf361247df4026c73fcdb74e8005640aaaae802a2`、`0153472def8ba48fa6e01e82e94231744074de8cf61f07563c867add4c579568`。

### 2026-09-03 19:47:54 +08:00 — `PV-20260903-256-41` — `采集前锁定（event-loop owner matcher=2扩展性诊断）`

#### 采集前锁定

- 目的与对照：在PV-40完全相同代码、JAR、机器和交易场景下，仅将matching engines从1改为2，诊断event-driven Lane completion对matcher扩展性的影响；对照为PV-40 matcher=1主轮`22,441.888 terminal business ops/s`。本轮不作为默认单matcher正式验收结论。
- 被测代码：dirty工作树，HEAD `ff2aadde6ef980444cc90ade95632a1d5fddc305`，除本文件外diff SHA-256 `cccf1b109aab3508d44c3527d0ef099dbc1c444860f7b0953f3067c5d6b8db24`；shaded JAR SHA-256 `dccf46c6b54af615942200fb42a391f9fe6a14edbdf8f195e9a3d8a54ae4f357`。
- 固定场景：仅`LINEAR_PERPETUAL`进程内交易链路；10,000活跃用户、512 listed/active symbols、每用户最多5持仓/10活动订单、4 Account Lane、2 matching engines、0 exchange-core risk engine、1 Product Core risk engine、1 JMH worker；每invocation 16,384 PLACE_ORDER，50% maker GTC + 50% taker IOC，同symbol/价格/数量配对成交，做市持续运行；open-loop offered `100,000 business ops/s`并修正coordinated omission；严格且仅`256 in-flight`。
- 正确性门禁：accepted business/Core分别等于terminal，unfinished/rejected/error/timeout/producer-starvation均为0，期末matcher/Lane/in-flight backlog为0，trades为business的50%；teardown必须通过资金守恒、余额/冻结/持仓、订单终态、盘口和snapshot recovery。
- 采集参数：Oracle GraalVM Java HotSpot 25.0.1，8GiB ZGC、AlwaysPreTouch、BLOCKING settlement、journal 65536/1GiB；无profiler主轮`fork=1、warmup=3x3s、measurement=3x5s、thread=1`。只采matcher=2主吞吐，不重复JFR、GC或长稳；PV-40已提供最终matcher=1 JFR归因。
- 环境与范围：采集前swap=`361.75MiB`、Pages throttled=0、Pageouts=53,567；Pageouts增长则仅作为诊断数据。不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data或其他产品线。
- artifact固定为`target/qualification/20260903T114754Z-owner-intrusive-ready-matcher2-256/`；运行同一JAR的`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`，固定PV-40全部参数，仅改`-p matchingEngines=2`与matcher系统属性。锁定后不修改场景、参数或门禁。

#### 采集结果

- trial模板初始化期间失败，未进入有效warmup/measurement，无吞吐样本。双matcher乱序完成使第一版intrusive ready list按完成顺序选择sequence，越过benchmark与Aeron要求的全局提交顺序，触发`matching batch completion crossed submission order`。该问题在单matcher下被自然完成序掩盖。
- 修正为Lane仍异步发布ready flag，但owner只对当前deterministic pending head执行O(1)检查和提交；删除按完成顺序维护的额外ready链表。失败JSON SHA-256 `f8c412e258a5b323f4af772515c881e6038fea23f35ab10111123160b7e70536`，本轮无性能结论。

### 2026-09-03 19:51:31 +08:00 — `PV-20260903-256-42` — `采集前锁定（deterministic-head event-loop最终单matcher）`

#### 采集前锁定

- 被测代码：dirty工作树，HEAD `ff2aadde6ef980444cc90ade95632a1d5fddc305`，除本文件外diff SHA-256 `d6773c214e2d36ea1af5f7a62fde0eeffb8c21da901b86fcdb98186c9325f601`；shaded JAR SHA-256 `d68c76b6aa4a3de79a3c6325d2603e537913962e12139c01ac775c1af9aba356`。最终实现由Lane通过固定容量SPSC queue和ready lane bit异步通知owner；owner不扫描pending ring，仅对全局deterministic head作O(1) readiness检查，完成Lane派发后继续处理其他owner事件，同时不允许跨sequence乱序提交。
- 对照、场景、正确性门禁、JMH/JFR参数、JVM参数和测试范围与PV-40一致：仅`LINEAR_PERPETUAL`，matcher=1、4 Account Lane、10,000用户、512 symbols、每invocation 16,384 PLACE_ORDER、100,000 offered、严格`256 in-flight`。主轮相对PV-37 `20,714.105 terminal business ops/s`不得回归超过10%；JFR仅作归因。
- 测试：HotSpot JDK25定向service `58/58`、benchmark-support `10/10`通过；包含双matcher可见的deterministic-head ready约束测试。构建成功。
- 环境：采集前swap=`361.75MiB`、Pages throttled=0、Pageouts=53,718；Pageouts增长或JFR DataLoss非0则仅作部分验证。不执行长稳，不能声明无泄漏。
- artifact固定为`target/qualification/20260903T115131Z-owner-deterministic-head-matcher1-256/`；执行PV-40完全相同的单matcher主JMH和JFR命令。不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他产品线。锁定后不修改标准、场景或参数。

#### 采集结果

- 无profiler主轮：`25,339.931 terminal business ops/s`、`25,339.931 terminal Core messages/s`、`12,669.965 trades/s`；三个business样本为`28,467.114/22,229.881/25,322.796 ops/s`。accepted与terminal business/Core相等，unfinished/rejected/error/timeout/producer-starvation均为0；Lane settlement `38,009.896/s`，teardown资金、余额/冻结/持仓、订单终态、盘口及snapshot recovery通过。相对PV-37基线`20,714.105/s`提升`22.33%`，相对有乱序缺陷的PV-40也提升`12.91%`；样本波动较大。
- JFR轮：`26,811.623 terminal business/Core messages/s`、`13,405.811 trades/s`，业务门禁闭合；fork=0数值不与主轮绝对比较。owner/JMH worker 868个execution samples，matcher 82，4个Account Lane合计204；主要热点为primitive `LongIntHashMap.slowGetIfAbsent=61`、`commitReadyMatching=55`、`progressPlaceAdmissions=50`、`TreeMap.put=41`、primitive map访问及`completeMatching=15`。ready ring全扫描已消失，但owner仍有admission、settlement plan和状态索引提交工作。
- 分配/GC/heap：sampled allocation约`9.961GB`、`28,952 sampled B/business op`，主要是long/byte/object/int数组、`TreeMap.Entry`、`CoreOrderState`和`OrderRuntime`；4次ZGC、allocation stall/failure=0，pause p50/p95/p99/max=`0.0092/0.0907/0.1576/0.1576 ms`，最长Concurrent Mark=`204.444 ms`。heap committed峰值8GiB、used峰值约5.94GiB，GC后live set=`50/276/484/648 MiB`；短轮不能作无泄漏结论。退出NMT committed约`8.28GiB`，32线程。
- 延迟与系统：344,064个PLACE_ORDER样本；entry→accepted p50/p90/p95/p99/p99.9/max=`268.435/536.871/536.871/1,073.742/1,073.742/683.004 ms`，accepted→terminal=`8.389/16.777/16.777/16.777/67.109/87.532 ms`，entry→terminal=`268.435/536.871/536.871/1,073.742/1,073.742/689.983 ms`。100k offered高于处理能力，入口排队延迟不代表容量点延迟。owner同步I/O=0、socket I/O=0、DataLoss=0；最大safepoint结束暂停`0.784 ms`、最大到达safepoint`2.290 ms`。
- 有效性与artifact：严格analyzer仍因1,003个JVM启动/反射探测异常返回非零；Pageouts由53,718增至54,974，故结论为正确性与短时性能通过、完整验收部分通过。main/profile/JFR/aggregate SHA-256分别为`3a4bc9a1a7ee2736a5c4349fdb93761a25c47b3bbcdad2e6802c99444db8acac`、`d796881e7d66ab78617a2a658f0f42b1e28961ad4b5edcdd72edeee2640e5bd6`、`dd451bcfb98b42f70c5cb2a5bf218858639bcc0b7c9486de48b0526d26a596ba`、`0ad64a45f512d48fd39a133a0e3223162dbdbe16183f8d3416e1f91b73274fcc`。

### 2026-09-03 19:56:58 +08:00 — `PV-20260903-256-43` — `采集前锁定（deterministic-head event-loop matcher=2诊断）`

#### 采集前锁定

- 目的与对照：使用PV-42最终代码与同一JAR，仅把matching engines从1改为2，确认跨matcher完成不会越过全局sequence并测量扩展性；对照为PV-42 matcher=1的`25,339.931 terminal business ops/s`。本轮是扩展性诊断，不替代单matcher正式口径。
- 固定场景、正确性门禁和JVM参数与PV-42完全一致：仅`LINEAR_PERPETUAL`、4 Account Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、100,000 offered、严格`256 in-flight`；无profiler主轮`fork=1、warmup=3x3s、measurement=3x5s、thread=1`，不重复JFR/GC/长稳。
- 被测HEAD `ff2aadde6ef980444cc90ade95632a1d5fddc305`，源码diff SHA-256 `d6773c214e2d36ea1af5f7a62fde0eeffb8c21da901b86fcdb98186c9325f601`，JAR SHA-256 `d68c76b6aa4a3de79a3c6325d2603e537913962e12139c01ac775c1af9aba356`。采集前swap=`361.75MiB`、Pages throttled=0、Pageouts=54,974；Pageouts增长则只作诊断。
- artifact固定为`target/qualification/20260903T115658Z-owner-deterministic-head-matcher2-256/`。不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他产品线；锁定后不修改场景、参数或门禁。

#### 采集结果

- 无profiler主轮：`11,625.129 terminal business ops/s`、`11,625.129 terminal Core messages/s`、`5,812.565 trades/s`；三个business样本为`14,922.767/11,658.165/8,294.455 ops/s`。accepted与terminal business/Core相等，unfinished/rejected/error/timeout/starvation均为0；teardown资金、账户/持仓、订单终态、盘口及snapshot recovery通过，未再发生跨submission sequence提交。
- 相对PV-42 matcher=1的`25,339.931/s`下降`54.12%`。原因是双matcher完成顺序不同，而当前共享owner的全局command/snapshot context要求按sequence只允许一个settlement commit在途；deterministic head造成head-of-line blocking。曾诊断性删除Lane同步等待并尝试将PLACE context延后到回调，但真实混合交易测试出现`snapshot projection batch is already active`，证明全局context尚不能安全重入；该实验已完全回退，最终service `58/58`与benchmark-support `10/10`重新通过。
- 结论：本次完成了Lane completion事件通知、ready lane位图、O(1) deterministic-head选择以及批内继续推进，单matcher提升明显；但“owner只负责sequence/Aeron、所有业务状态由Lane独占”的最终形态尚未完成。要安全解除双matcher head-of-line，必须先把`currentAdmission`、change accumulators、snapshot projection batch与最终response构造迁为每sequence独立commit context，再按sequence发布；不能只删除等待循环。
- 有效性与artifact：swap由`361.75MiB`降至`329.75MiB`、Pages throttled=0，但Pageouts由54,974增至55,772，所以只作matcher扩展性诊断。JSON SHA-256 `a13c14cd4d64882c39a46dbd6a9e9cf48c284ffa623d878102937881b9bc70bd`。

### 2026-09-03 20:23:39 +08:00 — `PV-20260903-256-44` — `采集前锁定（owner异步PLACE settlement context，matcher=1）`

#### 采集前锁定

- 被测代码：dirty工作树，HEAD `92a4c28c59898707652401d4a9d0413427d3447f`，除本文件外diff SHA-256 `4ab76cc8e4637172bbf0c35f98ba785e9fc3ec94ae4024c5201e41bff6a63f95`；shaded JAR SHA-256 `fb950bfbfdadf1502ba8c2894fbd70a6d116ba280d57f0b92ab3b713f3f77e8c`。相对PV-42，普通、accepted且无预撤单的PLACE在派发Account Lane settlement前不再打开全局command/snapshot context，owner不阻塞等待Lane；Lane完成后仅在全局deterministic head处恢复该sequence的提交上下文并完成状态提交。TRIGGER/REPLACE/AMEND及预撤单PLACE继续使用同步兼容路径。
- 对照与性能门禁：对照为PV-42 matcher=1的`25,339.931 terminal business ops/s`；主轮相对对照不得回归超过10%。记录terminal business/Core messages/s、trades/s、Lane工作量、三段延迟、accepted/terminal差值、unfinished和期末backlog。JFR只作热点归因，不与无profiler主轮绝对比较。
- 范围与场景：仅`LINEAR_PERPETUAL`进程内交易链路；1 matching engine、0 exchange-core risk engine、1 Product Core risk engine、4 Account Lane、1 JMH worker；10,000活跃用户、512 listed/active symbols、每用户最多5持仓/10活动订单；每invocation 16,384 PLACE_ORDER，50% maker GTC + 50% taker IOC，同symbol/价格/数量配对成交，做市持续运行；open-loop offered `100,000 business ops/s`并修正coordinated omission；严格且仅`256 in-flight`。
- 正确性门禁：accepted business/Core分别等于terminal，unfinished/rejected/error/timeout/producer-starvation均为0，期末matcher/Lane/in-flight backlog为0，trades为business的50%；teardown通过资金守恒、余额/冻结/持仓、订单生命周期终态、盘口和snapshot recovery。HotSpot JDK25定向service `58/58`、benchmark-support `10/10`已通过。
- 采集参数：无profiler主轮`fork=1、warmup=3x3s、measurement=3x5s、thread=1`；JFR轮`fork=0、warmup=1x3s、measurement=1x10s`。Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16、8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、BLOCKING settlement、journal 65536/1GiB及既有Agrona opens/exports；JFC SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。
- 环境与有效性：MacBookPro16,1 / Intel Core i9-9880H / 16 logical CPU / 16GiB / macOS 26.7 x86_64；采集前swap=`221.25MiB`、Pages throttled=0、Pageouts=55,772。Pageouts增长、JFR DataLoss非0或明显同机干扰时仅作部分验证；短轮不作无泄漏结论。
- artifact与命令：目录固定`target/qualification/20260903T122339Z-owner-async-place-matcher1-256/`；运行`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`，参数固定`accountLanes=4,activeUsers=10000,listedSymbols=512,activeSymbols=512,matchingEngines=1,maxPositionsPerUser=5,maxOpenOrdersPerUser=10,maxInFlight=256,operationsPerInvocation=16384,targetOperationsPerSecond=100000`。不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线。锁定后不修改场景、参数或门禁，失败和异常只追加结果。

#### 采集结果

- 第一次命令在JMH参数解析阶段因`-jvmArgsAppend`被拆为多个参数而失败，没有启动fork或产生样本；修正命令行编码后按锁定参数重跑。
- 重跑完成3次warmup，在第1次measurement触发`FatalMatchingDivergenceException`，无有效吞吐样本。根因为异步PLACE settlement期间owner继续收集后续PLACE admission，后续admission把变更写入全局changed-key容器，当前sequence提交清理时发现跨sequence污染：`changed map contains an untracked key`。该版本不满足资金/状态正确性门禁，已停止JFR和matcher=2采集。
- 修正方向：Lane provisional admission只发布待匹配状态，不再写入全局snapshot/index changed-key容器；订单terminal settlement按全局sequence统一登记最终变更。修改后必须新建锁定记录，PV-44不得作为性能对照。

### 2026-09-03 20:29:18 +08:00 — `PV-20260903-256-45` — `采集前锁定（sequence终态登记，owner异步PLACE matcher=1）`

#### 采集前锁定

- 被测代码：dirty工作树，HEAD `92a4c28c59898707652401d4a9d0413427d3447f`，除本文件外diff SHA-256 `65c83a282104ec2410a6794e5de7def700fde578fce50646546c430914f44bec`；shaded JAR SHA-256 `e5cfbc3862e09546c5d159b6f8bceab00c9f0602f8fe60267f77d312c51366e3`。相对PV-44，Lane provisional PLACE admission不再写全局changed user/balance/order/reservation容器；这些变化只在该订单匹配终态按全局sequence登记，避免后续admission污染当前提交批次。
- 对照、场景、门禁、JVM和范围均沿用PV-44：对照PV-42 `25,339.931 terminal business ops/s`，matcher=1、4 Account Lane、10,000用户、512 symbols、50% maker GTC+50% taker IOC、16,384 ops/invocation、100,000 offered、严格`256 in-flight`；主轮`3x3s warmup + 3x5s measurement, fork=1`，JFR轮`1x3s + 1x10s, fork=0`；accepted/terminal、unfinished/backlog、资金、余额/冻结/持仓、订单终态、盘口和snapshot recovery门禁不变。定向service `58/58`、benchmark-support `10/10`通过。
- 环境：HotSpot JDK25、Maven 3.9.16、8GiB ZGC、NMT summary、BLOCKING settlement、journal 65536/1GiB；采集前swap=`221.25MiB`、Pages throttled=0、Pageouts=55,892。Pageouts增长或JFR DataLoss非0则仅作部分验证；短轮不作无泄漏结论。
- artifact固定为`target/qualification/20260903T122918Z-owner-async-place-finalize-matcher1-256/`，执行PV-44相同JMH/JFR参数。不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线。锁定后不修改场景、参数或门禁，失败和异常只追加结果。

#### 采集结果

- 无profiler主轮：`19,719.567 terminal business ops/s`、`19,719.567 terminal Core messages/s`、`9,859.783 trades/s`；三个business样本为`22,269.974/19,211.405/17,677.321 ops/s`。accepted与terminal business/Core相等，unfinished/rejected/error/timeout/producer-starvation均为0；Lane settlement `29,579.350/s`，teardown资金、余额/冻结/持仓、订单终态、盘口及snapshot recovery通过。相对PV-42的`25,339.931/s`回退`22.18%`，未通过预设10%回归门禁。
- JFR轮：`28,580.603 terminal business/Core messages/s`、`14,290.301 trades/s`，业务门禁闭合；fork=0且带profiler，不与主轮绝对比较。owner/JMH worker 964个execution samples，matcher 69，4个Account Lane合计175；热点包括`Thread.isInterrupted=95`、`TreeMap.put=48`、`progressPlaceAdmissions=40`、`ThreadLocal.get=32`、`TreeMap.getEntry/successor=57`和`drainMatcherSettlementCompletions=17`。matching full-window sample约为terminal吞吐的75%，说明释放同步等待后256窗口长期饱和，owner轮询与全局head约束并未形成有效并行收益。
- 分配/GC/延迟：sampled allocation约`10.157GB`、`28,179.8 sampled B/business op`；4次ZGC，allocation stall/failure=0，pause p50/p95/p99/max=`0.011/0.042/0.051/0.051 ms`，GC后live set=`54/276/524/628 MiB`。360,448个PLACE_ORDER样本，entry→accepted p50/p90/p95/p99/p99.9/max=`268.435/536.871/536.871/1,073.742/1,073.742/788.506 ms`，accepted→terminal=`8.389/16.777/16.777/16.777/134.218/192.595 ms`，entry→terminal max=`794.090 ms`。owner同步I/O=0、socket I/O=0、DataLoss=0；最大GC pause`0.051 ms`，最大safepoint结束暂停`0.781 ms`，但一次到达safepoint耗时`159.383 ms`。
- 严格门禁与artifact：JFR含1,003个JVM启动/反射探测异常，strict analyzer返回非零；Pageouts由55,892增至56,353，故本轮仅为正确性通过、性能回归的部分验证。main/profile/JFR/aggregate SHA-256分别为`29bcc5e5379c667cd45564745ba681b50ec7d8f34f5d5d4f5c22d182edeebee5`、`0120fad4fd4d5d918d5309d3a61ad95626e8401bbda5a2f49dcda997d4c1da41`、`adaa1b11c55e6380134f914bd94a39f37897c420f271d5182efa51e4caeddd40`、`f04ce44485bea19a165f991988dcdac663f2077b18b33c25e3a5c74b5ab588d2`；原始JFR约105MiB。

### 2026-09-03 20:34:52 +08:00 — `PV-20260903-256-46` — `采集前锁定（owner异步PLACE matcher=2扩展性诊断）`

#### 采集前锁定

- 目的与对照：使用PV-45完全相同代码、JAR、机器和业务场景，仅将matching engines从1改为2，验证异步PLACE settlement是否改善PV-43的双matcher head-of-line；对照为PV-45 matcher=1 `19,719.567 terminal business ops/s`，同时参考PV-43旧架构matcher=2 `11,625.129/s`。本轮仅为扩展性诊断，不替代正式matcher=1口径。
- 固定场景与门禁：`LINEAR_PERPETUAL`、4 Account Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker+50% taker、100,000 offered、严格`256 in-flight`；无profiler`fork=1、warmup=3x3s、measurement=3x5s、thread=1`。accepted/terminal、unfinished/backlog、错误、资金、账户/持仓、订单终态、盘口及snapshot recovery门禁不变。
- 被测HEAD `92a4c28c59898707652401d4a9d0413427d3447f`，源码diff SHA-256 `65c83a282104ec2410a6794e5de7def700fde578fce50646546c430914f44bec`，JAR SHA-256 `e5cfbc3862e09546c5d159b6f8bceab00c9f0602f8fe60267f77d312c51366e3`；HotSpot JDK25、8GiB ZGC及其他JVM参数沿用PV-45。采集前swap=`221.25MiB`、Pages throttled=0、Pageouts=56,353；Pageouts增长则只作诊断。
- artifact固定为`target/qualification/20260903T123452Z-owner-async-place-finalize-matcher2-256/`。不重复JFR/GC/长稳，不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线；锁定后不修改场景、参数或门禁。

#### 采集结果

- 无profiler主轮：`20,075.881 terminal business ops/s`、`20,075.881 terminal Core messages/s`、`10,037.940 trades/s`；三个business样本为`24,250.849/18,464.848/17,511.944 ops/s`。accepted与terminal business/Core相等，unfinished/rejected/error/timeout/starvation均为0；资金、账户/持仓、订单终态、盘口及snapshot recovery teardown通过。
- 相对PV-45 matcher=1只提升`1.81%`，但相对旧PV-43 matcher=2提升`72.69%`。异步context消除了旧实现中owner对Lane的同步等待放大，但两个matcher仍不能有效扩展；两个场景都出现256 matching window长期满载，且样本随迭代下降。
- 归因：Account Lane只有一个FIFO；head matcher完成后产生的terminal settlement排在已经提交的后续provisional admission之后，形成优先级反转。下一轮将terminal settlement与普通admission拆为两个固定容量SPSC队列，Lane在命令边界优先消费terminal settlement，同类命令内部保持FIFO且不抢占正在执行的命令。
- artifact `target/qualification/20260903T123452Z-owner-async-place-finalize-matcher2-256/saturation-main.json` SHA-256 `df8d27d73a1b4ab71859964f8a15e4f8d2688180f819e8195840f074ad6a6951`；本轮不采JFR。结果仅作matcher扩展性诊断。Pageouts由56,353增至56,630，环境门禁未通过。

### 2026-09-03 20:39:03 +08:00 — `PV-20260903-256-47` — `采集前锁定（Account Lane terminal优先队列，matcher=1）`

#### 采集前锁定

- 被测代码：dirty工作树，HEAD `92a4c28c59898707652401d4a9d0413427d3447f`，除本文件外diff SHA-256 `145ca65b243a366b173fa2e52910dd4b05f8903df57daf5f1042c304aea4525a`；shaded JAR SHA-256 `7387022e4a03b9206f26ab1b880b90a9aa4af114f488b0535a58c159268c51c5`。相对PV-45，Account Lane worker增加固定容量terminal SPSC队列；matcher settlement走terminal队列，Lane在命令边界优先消费，provisional admission继续走普通FIFO。新增确定性测试证明terminal可越过已排队但未执行的admission，正在执行的命令不抢占；定向service `59/59`、benchmark-support `10/10`通过。
- 对照、场景、门禁、JVM与范围沿用PV-45：对照PV-42 matcher=1 `25,339.931 terminal business ops/s`，同时比较PV-45无优先级的`19,719.567/s`；1 matcher、4 Account Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker+50% taker、100,000 offered、严格`256 in-flight`；主轮`3x3s + 3x5s, fork=1`，JFR轮`1x3s + 1x10s, fork=0`。accepted/terminal、unfinished/backlog、错误、资金、账户/持仓、订单终态、盘口与snapshot recovery门禁不变。
- 环境：HotSpot JDK25、Maven3.9.16、8GiB ZGC、NMT summary、BLOCKING settlement、journal 65536/1GiB；采集前swap=`221.25MiB`、Pages throttled=0、Pageouts=56,630。Pageouts增长或JFR DataLoss非0则仅作部分验证；短轮不作无泄漏结论。
- artifact固定为`target/qualification/20260903T123903Z-lane-terminal-priority-matcher1-256/`。不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线。锁定后不修改场景、参数或门禁，失败和异常只追加结果。

#### 采集结果

- 无profiler主轮：`16,835.194 terminal business ops/s`、`16,835.194 terminal Core messages/s`、`8,417.597 trades/s`；三个business样本为`21,082.030/15,215.147/14,208.405 ops/s`。accepted与terminal business/Core相等，unfinished/rejected/error/timeout/starvation均为0；资金、账户/持仓、订单终态、盘口及snapshot recovery通过。
- 相对PV-45无优先级版本回退`14.63%`，相对PV-42回退`33.56%`，未通过性能门禁。额外队列和每次Lane循环的优先级检查增加成本，但没有消除已经执行的provisional admission，也没有产生有效并行；该实现已完整回退，不进入最终代码，不再执行本轮JFR或matcher=2。
- 本轮JSON位于`target/qualification/20260903T123903Z-lane-terminal-priority-matcher1-256/saturation-main.json`，SHA-256 `4d2a77105f15c34ef9dd06ecd32e1ffb0190f6ca6ed3863b3b44b52a62b7ae4c`。最终代码退回PV-42 owner/Lane completion架构，只保留provisional admission不登记全局changed-key的边界收敛，并新建下一条锁定记录。Pageouts由56,630增至56,934。

### 2026-09-03 20:42:16 +08:00 — `PV-20260903-256-48` — `采集前锁定（终态changed-key登记，最终matcher=1）`

#### 采集前锁定

- 被测代码：dirty工作树，HEAD `92a4c28c59898707652401d4a9d0413427d3447f`，除本文件外diff SHA-256 `0c317da4f26e8b2ddd1c0547c4cf35926bb683be72137fa18721d26d31d7fbc7`；shaded JAR SHA-256 `243d4067f52293be1aa3103527e6a9452fa76ece9234ccb8c23dcad1f730dcc4`。最终代码沿用PV-42 deterministic-head owner/Lane completion，只删除provisional PLACE admission对全局changed user/balance/order/reservation的提前登记；订单在匹配终态统一登记。PV-44至PV-47的owner异步等待和Lane优先队列实验均已回退。
- 对照、场景、门禁与JVM沿用PV-42：对照`25,339.931 terminal business ops/s`；matcher=1、4 Account Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker GTC+50% taker IOC、100,000 offered、严格`256 in-flight`；主轮`3x3s + 3x5s, fork=1`，JFR轮`1x3s + 1x10s, fork=0`。accepted/terminal、unfinished/backlog、错误、资金、账户/持仓、订单终态、盘口和snapshot recovery门禁不变；相对PV-42不得回归超过10%。定向service `58/58`、benchmark-support `10/10`通过。
- 环境与范围：HotSpot JDK25、Maven3.9.16、8GiB ZGC、NMT summary、BLOCKING settlement、journal65536/1GiB；采集前swap=`221.25MiB`、Pages throttled=0、Pageouts=56,934。Pageouts增长或JFR DataLoss非0则仅作部分验证，短轮不作无泄漏结论。不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线。
- artifact固定为`target/qualification/20260903T124216Z-terminal-change-registration-matcher1-256/`。锁定后不修改场景、参数或门禁，失败和异常只追加结果。

#### 采集结果

- 无profiler主轮：`22,615.329 terminal business ops/s`、`22,615.329 terminal Core messages/s`、`11,307.664 trades/s`；三个business样本为`26,776.226/17,327.484/23,742.276 ops/s`。accepted与terminal business/Core相等，unfinished/rejected/error/timeout/starvation均为0；Lane settlement `33,922.993/s`，资金、账户/持仓、订单终态、盘口及snapshot recovery通过。相对PV-42回退`10.75%`，略超预设10%门禁；样本跨度大，不能认定4行删除造成稳定回归。
- JFR轮：`29,345.213 terminal business/Core messages/s`、`14,672.607 trades/s`，业务门禁闭合；fork=0数值不与主轮绝对比较。owner/JMH worker 938个execution samples，matcher84，4 Lane合计225；热点仍是`progressPlaceAdmissions=89`、`completeMatching=73`、primitive map、`TreeMap.put=53`和`awaitMatchingResult=17`，没有新增处理阶段。sampled allocation约`10.029GB`、`27,824.9 sampled B/business op`，低于PV-42的约28,952 B/op。
- GC/系统：4次ZGC，allocation stall/failure=0；owner同步I/O=0、socket I/O=0、DataLoss=0。严格analyzer因既有1,003个JVM启动/反射探测异常返回非零。Pageouts由56,934增至57,395，因此本轮仅为正确性通过、短时性能部分验证，不作生产容量或无泄漏结论。
- artifacts：main/profile/JFR/aggregate SHA-256分别为`0825166487c2e373ab0913561553eb9549e71a4bb0ffb312db368c2f331013f0`、`29df788bd0d98d750407935231a8b9f4c57f2b629a428d7e3879d78095e9ce5e`、`fc7f956b0891d96c8842a071fb25740fde107fbffb1ecdedeea76eb0d3e2aa29`、`36f660392c7f0d6ab9540dba12b94731d05733a370582078482acd5767d6f88b`；原始JFR约103MiB。

### 2026-09-03 20:47:07 +08:00 — `PV-20260903-256-49` — `采集前锁定（终态changed-key登记，最终matcher=2诊断）`

#### 采集前锁定

- 目的与对照：使用PV-48同一最终代码/JAR与场景，仅将matching engines从1改为2；对照PV-48 matcher=1 `22,615.329/s`及PV-43 matcher=2 `11,625.129/s`。本轮只作扩展性诊断，不替代matcher=1正式口径。
- 固定场景：`LINEAR_PERPETUAL`、4 Account Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker+50% taker、100,000 offered、严格`256 in-flight`；无profiler`fork=1、warmup=3x3s、measurement=3x5s、thread=1`。accepted/terminal、unfinished/backlog、错误、资金、账户/持仓、订单终态、盘口及snapshot recovery门禁不变。
- 被测HEAD `92a4c28c59898707652401d4a9d0413427d3447f`，源码diff SHA-256 `0c317da4f26e8b2ddd1c0547c4cf35926bb683be72137fa18721d26d31d7fbc7`，JAR SHA-256 `243d4067f52293be1aa3103527e6a9452fa76ece9234ccb8c23dcad1f730dcc4`；HotSpot JDK25、8GiB ZGC及其他JVM参数沿用PV-48。采集前swap=`221.25MiB`、Pages throttled=0、Pageouts=57,395；Pageouts增长则只作诊断。
- artifact固定为`target/qualification/20260903T124707Z-terminal-change-registration-matcher2-256/`。不重复JFR/GC/长稳，不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线；锁定后不修改场景、参数或门禁。

#### 采集结果

- 无profiler主轮：`21,828.720 terminal business ops/s`、`21,828.720 terminal Core messages/s`、`10,914.360 trades/s`；三个business样本为`25,217.185/18,170.269/22,098.707 ops/s`。accepted与terminal business/Core相等，unfinished/rejected/error/timeout/starvation均为0；资金、账户/持仓、订单终态、盘口及snapshot recovery通过。
- 相对PV-48 matcher=1下降`3.48%`，样本区间重叠，两个matcher无稳定扩展收益；相对PV-43 matcher=2提升`87.77%`，但该跨版本差异主要来自PV-42已经提交的Lane completion/O(1) deterministic-head改造，不能归因于本轮4行删除。共享owner的admission、terminal状态索引提交及全局sequence仍是平台瓶颈。
- 环境与artifact：Pageouts由57,395增至57,811，故只作matcher扩展性诊断。JSON `target/qualification/20260903T124707Z-terminal-change-registration-matcher2-256/saturation-main.json` SHA-256 `d2275e00b82966f24b90c773af7f4df21a906ce708cb8eaa5517068352b75edf`。
- 架构结论：回调化实验验证了若不先建立每sequence独立commit context，异步等待会让全局changed-key跨sequence污染并把256窗口长期打满；双队列优先级也不能修复已执行的provisional状态。后续正确拆分边界应是Lane在其串行上下文内产出不可变terminal delta，owner只按global sequence发布response/Aeron边界；`currentAdmission`、snapshot batch、changed-key/index提交和资金delta必须先从全局字段迁为sequence-owned context，之后才能安全允许多个settlement在途。

### 2026-09-03 21:09:33 +08:00 — `PV-20260903-256-50` — `采集前锁定（sequence-owned settlement context，matcher=1）`

#### 采集前锁定

- 被测代码：dirty工作树，HEAD `a9dddaae1ede287b0fefe265f5ed4af4d52a7e40`，除本文件外diff SHA-256 `a57740486eacb76d0a4a14b829b9ff1a09e369dfae6969b529dd01e2470befc4`；shaded JAR SHA-256 `aa1f211adbc2a0a55840dc17bbe3adef74ae670716862eb0489c827156ebe4b2`。修改把普通accepted PLACE的admission引用、snapshot batch标志、changed user/order accumulator、index发布视图和funds delta保存到每sequence context；每个MatcherSettlementEvent独占Lane发布缓冲和余额before/after补丁；owner按全局sequence合并并发布。独立dispatch cursor保持全局sequence单调，但可在前序settlement未提交时继续派发后续ready PLACE。
- 对照与门禁：对照PV-48 matcher=1 `22,615.329 terminal business ops/s`，主轮不接受超过10%回归。新增JMH业务门禁要求`dispatchedSettlementHighWaterMark >= 2`，同时accepted business/Core分别等于terminal，unfinished/rejected/error/timeout/producer-starvation为0，期末matcher/Lane/context backlog为0，trades为business的50%；teardown必须通过资金守恒、余额/冻结/持仓、订单终态、盘口和snapshot recovery。
- 固定范围与场景：仅`LINEAR_PERPETUAL`进程内交易链路；1 matching engine、0 exchange-core risk engine、1 Product Core risk engine、4 Account Lane、1 JMH owner线程；10,000活跃用户、512 listed/active symbols、每用户最多5持仓/10活动订单；每invocation 16,384 PLACE_ORDER，50% maker GTC + 50% taker IOC，同symbol/价格/数量配对成交，做市持续运行；open-loop offered `100,000 terminal business ops/s`并修正coordinated omission；严格且仅`256 in-flight`。
- 测试与环境：Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16；定向service/ring/end-to-end测试`9/9`、benchmark-support`10/10`通过，CoreMatchingState资金与交易测试通过（其中2个已删除exporter能力的遗留断言不在本轮范围）。不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线。
- 采集参数：无profiler主轮`fork=1、warmup=3x3s、measurement=3x5s、thread=1`；JFR归因轮`fork=0、warmup=1x3s、measurement=1x10s`，不与主轮绝对吞吐比较。JVM固定8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、BLOCKING settlement、journal 65536/1GiB及既有Agrona opens/exports。JFR使用`owner-commit-profile.jfc`，SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`；保存原始JFR、summary/views、GC/safepoint、NMT与校验哈希。
- 机器与有效性：MacBookPro16,1 / Intel Core i9-9880H / 16 logical CPU / 16GiB / macOS 26.7 x86_64；采集前swap=`221.25MiB`、Pages throttled=0、Pageouts=57,811。Pageouts增长、JFR DataLoss非0、业务门禁不闭合或明显同机干扰时只能作部分验证；短JFR不证明无泄漏，未执行长稳。
- artifact与命令：目录固定`target/qualification/20260903T130933Z-sequence-context-matcher1-256/`；运行`product-core-benchmarks.jar LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`，参数固定`accountLanes=4,activeUsers=10000,listedSymbols=512,activeSymbols=512,matchingEngines=1,maxPositionsPerUser=5,maxOpenOrdersPerUser=10,maxInFlight=256,operationsPerInvocation=16384,targetOperationsPerSecond=100000`。锁定后不修改场景、参数或门禁；失败和异常只追加结果。

### 2026-09-03 21:15:00 +08:00 — `PV-20260903-256-51` — `采集前锁定（sequence context GC分配诊断）`

#### 采集前锁定

- 使用PV-50完全相同代码、JAR、单matcher业务场景及严格`256 in-flight`，仅增加JMH `-prof gc`测量分配；本轮是归因数据，不替代PV-50无profiler主吞吐。
- 参数固定为`fork=1、warmup=1x3s、measurement=1x5s、thread=1`，`accountLanes=4,activeUsers=10000,listedSymbols=512,activeSymbols=512,matchingEngines=1,maxPositionsPerUser=5,maxOpenOrdersPerUser=10,maxInFlight=256,operationsPerInvocation=16384,targetOperationsPerSecond=100000`；JVM仍为HotSpot 25.0.1、8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary和BLOCKING settlement。
- 被测HEAD `a9dddaae1ede287b0fefe265f5ed4af4d52a7e40`，除本文件外diff SHA-256 `a57740486eacb76d0a4a14b829b9ff1a09e369dfae6969b529dd01e2470befc4`，JAR SHA-256 `aa1f211adbc2a0a55840dc17bbe3adef74ae670716862eb0489c827156ebe4b2`。业务正确性门禁与PV-50一致；artifact固定为`target/qualification/20260903T131500Z-sequence-context-gc-matcher1-256/`。
- 采集前swap=`221.25MiB`、Pages throttled=0、Pageouts=58,402；本轮不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他产品线。

#### 采集结果

- `-prof gc`轮为`21,362.879 terminal business/Core messages/s`、`10,681.439 trades/s`，accepted与terminal闭合，unfinished/rejected/error/timeout/starvation为0；分配率`492.997 MiB/s`，JMH归一化为每次16,384业务操作的invocation `571,763,531.429 B/op`，即约`34,897 B/terminal business op`；4次GC、GC累计时间`1,460 ms`。
- Pageouts由58,402增至59,958，因此该轮只用于分配归因。JSON SHA-256 `c0188b01cae435b32713dd2edfd06f6766d43f1e76edc9747c6822b08dd8994c`。

### 2026-09-03 21:16:17 +08:00 — `PV-20260903-256-52` — `采集前锁定（sequence-owned settlement context，matcher=2诊断）`

#### 采集前锁定

- 使用PV-50同一代码、JAR和业务场景，仅将matching engines从1改为2；对照PV-50 matcher=1 `38,031.789 terminal business ops/s`及PV-49旧架构matcher=2 `21,828.720/s`。本轮仅作matcher扩展性诊断。
- 固定严格`256 in-flight`、4 Account Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker+50% taker、100,000 offered；无profiler`fork=1、warmup=3x3s、measurement=3x5s、thread=1`。accepted/terminal、unfinished/backlog、错误、资金、账户/持仓、订单终态、盘口、snapshot recovery及`dispatchedSettlementHighWaterMark >= 2`门禁与PV-50一致。
- 被测HEAD `a9dddaae1ede287b0fefe265f5ed4af4d52a7e40`，源码diff SHA-256 `a57740486eacb76d0a4a14b829b9ff1a09e369dfae6969b529dd01e2470befc4`，JAR SHA-256 `aa1f211adbc2a0a55840dc17bbe3adef74ae670716862eb0489c827156ebe4b2`；HotSpot 25.0.1、8GiB ZGC及其他JVM参数沿用PV-50。
- artifact固定为`target/qualification/20260903T131617Z-sequence-context-matcher2-256/`；采集前swap=`221.25MiB`、Pages throttled=0、Pageouts=59,958。不重复JFR/GC/长稳，不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他产品线。

#### 采集结果

- 无profiler主轮：`38,380.220 terminal business ops/s`、`38,380.220 terminal Core messages/s`、`19,190.110 trades/s`；三个business样本为`41,999.161/39,003.190/34,138.310 ops/s`。accepted与terminal business/Core相等，unfinished/rejected/error/timeout/starvation为0；资金、账户/持仓、订单终态、盘口、snapshot recovery及多settlement在途门禁通过。
- 相对PV-50 matcher=1提升`0.92%`，样本区间重叠，说明解除单settlement同步边界后两个matcher已不再导致吞吐下降，但当前负载仍受共享owner的有序终态物化和分配成本限制；相对PV-49旧架构matcher=2提升`75.82%`。
- Pageouts由59,958增至60,293，因此本轮仅作扩展性诊断。JSON SHA-256 `cb9aa5da78742c38d94e8bc08f9baac388825c59be07bb62011205029c335ac4`。

#### PV-50采集结果

- 无profiler主轮：`38,031.789 terminal business ops/s`、`38,031.789 terminal Core messages/s`、`19,015.894 trades/s`；三个business样本为`41,915.973/37,318.733/34,860.660 ops/s`，Lane settlement `57,047.683/s`。accepted与terminal business/Core相等，unfinished/rejected/error/timeout/starvation为0，资金、余额/冻结/持仓、订单终态、盘口、snapshot recovery及`dispatchedSettlementHighWaterMark >= 2`全部通过。相对PV-48 `22,615.329/s`提升`68.17%`。
- JFR轮：`38,062.981 terminal business/Core messages/s`、`19,031.491 trades/s`，业务门禁闭合。热点前列为primitive `LongObjectHashMap.get=10.63%`、`getIfAbsent=5.00%`、`TreeMap.put=4.45%`、每sequence `MatcherSettlementChanges`构造`3.72%`和`progressPlaceAdmissions=2.54%`；owner已不再同步等待单个Lane settlement，剩余主要成本是有序索引/视图物化及sequence context分配。
- JFR内存与运行时：总线程分配约`19.0GiB`，其中owner/JMH worker占`84.23%`；TLAB内`17.2GiB`、TLAB外`1.8GiB`。主要分配为`long[] 28.70%`、`int[] 12.11%`、`byte[] 7.51%`、`Object[] 6.01%`和per-sequence change buffer `4.71%`。8次ZGC，34次pause合计`0.484ms`，pause p50/p90/p95/p99/max=`0.0102/0.0308/0.0504/0.0606/0.0606ms`；heap committed 8GiB，JVM native committed除heap外峰值主要为GC `185MiB`、Tracing `38MiB`、Metaspace `31MiB`、Code `30.3MiB`，Direct Buffer count/used始终为0。
- JFR线程/锁/I/O/JIT：owner/JMH worker CPU load最高，matcher及4 Lane次之；monitor contention仅6次且最大`0.114ms`，VM operation最长`0.583ms`，最大观测safepoint约`2.43ms`。交易owner无同步socket I/O，文件写仅JFR/JMH artifact；DataLoss=0。最长编译为snapshot codec `736ms`，主要编译发生在预热/采样窗口内，因此本轮只作部分性能验证；876个异常均来自启动期反射能力探测，业务错误为0。
- 有效性与artifact：Pageouts由57,811增至60,293，且未执行长稳，不能声明生产容量或无泄漏。main/profile/JFR SHA-256分别为`fc37badbbad8f60266c7637eb848d0f8679a16d4363333029ef09e130919fd42`、`96dc54e8fccd0fe720ada54201f33af3d905f25e9808fb59b749ddfe87a6f60d`、`6ce56182a9415787dca250ef08b1271612d44b65b72a064a006d4d560099caa5`；原始JFR约94MiB，summary/views位于同目录`jfr-analysis/`。

### 2026-09-03 21:36:43 +08:00 — `PV-20260903-256-53` — `采集前锁定（sequence change-buffer复用，matcher=1）`

#### 采集前锁定

- 被测修改：每sequence语义保持不变，但`MatcherSettlementChanges`及其Lane change/balance primitive buffers在settlement完全收集并清空后回收到owner独占池；最多保留实际并行高水位数量，后续sequence复用已扩容容量。同步batch不再创建未使用的sequence changes。
- 对照与门禁：对照PV-50 matcher=1 `38,031.789 terminal business ops/s`及PV-51约`34,897 B/terminal business op`；主轮不得回归超过10%，GC归因轮要求分配下降。accepted/terminal、unfinished/backlog、错误、资金、余额/冻结/持仓、订单终态、盘口、snapshot recovery及多settlement在途门禁不变。
- 场景固定为`LINEAR_PERPETUAL`、matcher=1、4 Account Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker+50% taker、100,000 offered、严格且仅`256 in-flight`。无profiler主轮`fork=1,warmup=3x3s,measurement=3x5s`；`-prof gc`轮`fork=1,warmup=1x3s,measurement=1x5s`；JFR轮`fork=0,warmup=1x3s,measurement=1x10s`。
- 环境：HotSpot 25.0.1、Maven 3.9.16、8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、BLOCKING settlement。HEAD `21374a60b76d146a1468731a41dc71b6e97ae505`，除本文件外diff SHA-256 `16051e8272e9b09e239bcedb1dcac7b2c0759d4837b4369a7df81eaf64fd45c4`，JAR SHA-256 `50f6422205752945d8f7e637c8dbd3c1d5367cc900f6da1cbaca84600570861d`。
- artifact固定为`target/qualification/20260903T133643Z-sequence-buffer-reuse-matcher1-256/`；采集前swap=`221.25MiB`、Pages throttled=0、Pageouts=60,293。短轮不证明无泄漏；不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他产品线。

#### 采集结果

- 无profiler主轮：`37,112.106 terminal business/Core messages/s`、`18,556.053 trades/s`，三个business样本为`33,977.758/38,330.546/39,028.013 ops/s`，Lane settlement `55,668.158/s`。accepted与terminal闭合，unfinished/rejected/error/timeout/starvation为0；资金、账户/持仓、订单终态、盘口、snapshot recovery和多settlement在途门禁通过。相对PV-50波动`-2.42%`，通过10%回归门禁。
- `-prof gc`轮为`38,723.942 terminal business ops/s`、`410.690 MiB/s`、`309,987,236.667 B/invocation`，折合约`18,920 B/terminal business op`，较PV-51约`34,897 B/op`下降`45.78%`；5秒measurement内GC次数为0。
- JFR轮为`40,547.327 terminal business/Core messages/s`、`20,273.664 trades/s`。owner/JMH worker总分配由PV-50的`15.9GiB`降至`9.1GiB`，占比由84.23%降至74.47%；`MatcherSettlementChanges`构造和`ChangeBuffer`不再进入主要分配类型，`ChangeBuffer.drain`仅占CPU sample `0.67%`。剩余主要分配为`long[] 16.14%`、`byte[] 11.87%`、`Object[] 5.41%`、`TreeMap.Entry 4.73%`和订单终态对象。
- JFR GC pause 23次、合计`0.433ms`，p50/p90/p95/p99/max=`0.0100/0.0328/0.143/0.170/0.170ms`；DataLoss=0。Pageouts由60,293增至60,900，因此只作短时部分验证，不作无泄漏结论。
- main/gc/profile/JFR SHA-256分别为`158b253c02d6636992916eba03554e097ac14207ba3ea8514120a4a96196750f`、`d50e274cef3685a26430be5761b0f4a285499a32bd22f5a0929eb16bac5049c0`、`9ad35602e951e3c0d085c7b3c6e8ba0c4b44bf1c7a6646c867b36b2ed120da14`、`c1a1a2e561bf8ba0847a64c76b093fe4920290ce628497ab0b4bd7b39daa92b5`；JFR约85MiB，summary和热点views位于同一artifact目录。

### 2026-09-03 21:41:01 +08:00 — `PV-20260903-256-54` — `采集前锁定（sequence change-buffer复用，matcher=2诊断）`

#### 采集前锁定

- 使用PV-53同一代码、JAR和业务场景，仅将matcher从1改为2，验证owner池不会在并行matcher settlement间提前复用；对照PV-53 matcher=1 `37,112.106/s`和PV-52 matcher=2 `38,380.220/s`。本轮仅作扩展性诊断。
- 固定严格`256 in-flight`、4 Account Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker+50% taker、100,000 offered；无profiler`fork=1,warmup=3x3s,measurement=3x5s,thread=1`。业务、资金、snapshot及多settlement门禁与PV-53一致。
- HEAD、源码diff、JAR和JVM参数沿用PV-53；artifact固定为`target/qualification/20260903T134101Z-sequence-buffer-reuse-matcher2-256/`。采集前swap=`221.25MiB`、Pages throttled=0、Pageouts=60,900；不执行JFR/GC/长稳及外围服务测试。

#### 采集结果

- 无profiler主轮：`37,241.486 terminal business/Core messages/s`、`18,620.743 trades/s`；三个business样本为`34,468.527/36,708.534/40,547.397 ops/s`。accepted与terminal闭合，unfinished/rejected/error/timeout/starvation为0，资金、账户/持仓、订单终态、盘口、snapshot recovery和多settlement在途门禁通过。
- 相对PV-53 matcher=1为`+0.35%`，仍无明显双matcher扩展收益，但确认并行matcher之间没有提前复用或sequence污染。相对PV-52 matcher=2波动`-2.97%`，在相同样本波动范围内。
- Pageouts由60,900增至61,046；本轮仅作扩展性诊断。JSON SHA-256 `57d2cf7971746ddafc14d9bfec340164f3c33548a418dbb296e63980ebd7dec3`。

### 2026-09-03 21:48:15 +08:00 — `PV-20260903-256-55` — `采集前锁定（订单决策零BigInteger/stream，matcher=1）`

#### 采集前锁定

- 被测修改：订单保护价和保证金边界价改用商/余数拆分的exact long PPM缩放，保持floor/ceil与溢出拒绝语义，移除每单BigInteger；fee policy选择改为直接遍历，移除stream pipeline。
- 对照PV-53 matcher=1 `37,112.106 terminal business ops/s`和`18,920 B/op`；主轮不得回归超过10%，GC轮要求BigInteger和fee stream分配退出热点。业务、资金、snapshot recovery及多settlement门禁不变。
- 固定`LINEAR_PERPETUAL`、matcher=1、4 Account Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker+50% taker、100,000 offered、严格`256 in-flight`。主轮`fork=1,warmup=3x3s,measurement=3x5s`，GC轮`fork=1,warmup=1x3s,measurement=1x5s`；不重复matcher=2/JFR/长稳。
- HotSpot 25.0.1、8GiB ZGC及JVM参数沿用PV-53。HEAD `6be1447e277a46f59e69f1f8bff909c1a9045063`，除本文件外diff SHA-256 `245b23861d55874812cee2b844d22ad5a8004656ffe9efd9e540fa81cd46024b`，JAR SHA-256 `0026b050a4868f48abc8a086eaea9cbbbb421a2abb713644b8f6aae7ae96fc1e`。
- artifact固定为`target/qualification/20260903T134815Z-order-decision-long-matcher1-256/`；采集前swap=`189.25MiB`、Pages throttled=0、Pageouts=61,046。不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他产品线。

#### 采集结果

- 无profiler主轮：`36,407.386 terminal business/Core messages/s`、`18,203.693 trades/s`；三个business样本`35,209.437/39,355.149/34,657.571 ops/s`。accepted与terminal闭合，unfinished/error为0，资金、账户/持仓、订单终态、盘口、snapshot recovery和多settlement门禁通过；相对PV-53波动`-1.90%`，通过回归门禁。
- `-prof gc`轮为`41,656.054 terminal business ops/s`、`398.591 MiB/s`、`278,535,662.154 B/invocation`，折合约`17,000 B/terminal business op`，较PV-53 `18,920 B/op`再下降`10.15%`；measurement内GC次数为0。
- Pageouts由61,046增至61,423，结论为短时部分验证。main/gc SHA-256分别为`6e7809663a669e20752217140342a1d80cd7fac6f8d816dc04217b4c8d21ccbe`、`e7ba70e345bc99553cf196d2f31c2e7964efe969dc4b22ccbffa07b159b49eed`。

### 2026-09-03 21:53:10 +08:00 — `PV-20260903-256-56` — `采集前锁定（command response单缓冲编码，matcher=1）`

#### 采集前锁定

- 在PV-55代码上将CoreCommandResult中的order列表直接写入最终ByteBuffer，删除中间open-orders byte[]及包装view；普通PLACE/CANCEL/TRIGGER单订单响应删除varargs long[]、ArrayList及二次List复制。
- 对照PV-55 `36,407.386 terminal business ops/s`和约`17,000 B/op`；主轮不得回归超过10%，GC轮预期继续降低byte[]/Object[]分配。协议round-trip `13/13`、service真实路径`13/13`、benchmark-support `10/10`通过。
- 场景、业务门禁和JVM与PV-55相同：matcher=1、4 Lane、10,000用户、512 symbols、100,000 offered、严格`256 in-flight`；主轮`3x3s+3x5s`，GC轮`1x3s+1x5s`，均fork=1。
- HEAD `6be1447e277a46f59e69f1f8bff909c1a9045063`，除本文件外diff SHA-256 `056771264513045a55838ce75663967b9577f19bd4eccea82da24539ef2e11da`，JAR SHA-256 `81d8c6b6f912cde235eb0aeaa6a45bc97d73fe44a47a4e0a34708489033aed05`。artifact固定为`target/qualification/20260903T135310Z-command-result-single-buffer-matcher1-256/`；采集前swap=`189.25MiB`、Pages throttled=0、Pageouts=61,423。不执行外围服务和其他产品线测试。

#### 采集结果

- 无profiler主轮：`36,492.565 terminal business/Core messages/s`、`18,246.282 trades/s`；三个business样本`32,670.387/40,254.153/36,553.153 ops/s`。accepted与terminal闭合，unfinished/error为0，全部业务、资金、snapshot和多settlement门禁通过；相对PV-55为`+0.23%`。
- `-prof gc`轮为`44,363.186 terminal business ops/s`、`403.710 MiB/s`、`265,777,682.286 B/invocation`，折合约`16,221 B/terminal business op`，较PV-55约17,000 B/op下降`4.58%`，较PV-51最初34,897 B/op累计下降`53.52%`；measurement内GC次数为0。
- Pageouts由61,423增至61,658，短时验证通过。main/gc SHA-256分别为`2ad7322a7c0269deac1638ad2e53c1712dd44c7f3b0a679c4fb44bd2f3059f90`、`9dbaee6f58cf0fe808fec3198bf60e094e83853e19a3716e8014f8b01186c0ad`。

### 2026-09-03 21:56:20 +08:00 — `PV-20260903-256-57` — `采集前锁定（最终订单提交路径JFR）`

#### 采集前锁定

- 使用PV-56最终提交`d02d0f18e5b367e1bffd2430224ad8a5d8055fe2`和同一JAR（SHA-256 `81d8c6b6f912cde235eb0aeaa6a45bc97d73fe44a47a4e0a34708489033aed05`）补齐JFR证据；场景仍为matcher=1、4 Lane、100k offered、严格`256 in-flight`。
- 固定`fork=0,warmup=1x3s,measurement=1x10s`，8GiB ZGC、NMT summary及自定义owner profile JFC与PV-53一致。业务、资金、snapshot、多settlement、DataLoss和owner I/O门禁不变；JFR吞吐不与无profiler主轮直接比较。
- artifact固定为`target/qualification/20260903T135620Z-final-order-commit-jfr-matcher1-256/`；采集前swap=`189.25MiB`、Pages throttled=0、Pageouts=61,658。短轮不证明无泄漏，不测试外围服务和其他产品线。

#### 采集结果

- JFR轮为`42,824.734 terminal business/Core messages/s`、`21,412.367 trades/s`，accepted与terminal闭合，unfinished/error为0，业务、资金、snapshot和多settlement门禁通过。
- BigInteger、fee-policy stream、`encodeOpenOrders`中间编码和`List.copyOf`不再进入主要热点。当前CPU首项为pending reservation相关primitive `LongObjectHashMap.getIfAbsent=21.09%`，之后为`TreeMap.put=4.43%`、`TreeMap.getEntry=3.16%`和`progressPlaceAdmissions=2.82%`；下一优化边界明确为单订单sequence的pending-reservation索引。
- allocation热点为`TreeMap.put=10.69%`、primitive map插入/扩容约19.66%、matcher evidence绑定1.98%、订单终态对象1.77%和最终command result byte[] 1.58%；已删除的open-orders中间byte[]不再出现。GC pause 28次合计`0.450ms`，p50/p95/p99/max=`0.0106/0.0496/0.0500/0.0500ms`，DataLoss=0。
- Pageouts由61,658增至64,796，因此为短时部分验证。profile/JFR SHA-256分别为`aef4e8133589597d98c8f3820b10612cb435b82ad17e29b27e2a8a34e11649dd`、`d4e654e794f745b6a831c438b94aed3795982cf3b871dc4a0fa3f8fa7dba885c`；JFR约84MiB，summary/views位于同一artifact目录。

### 2026-09-03 22:04:18 +08:00 — `PV-20260903-256-58` — `采集前锁定（pending reservation单值索引，matcher=1）`

#### 采集前锁定

- 被测修改：`pendingReservationsBySequence`从每sequence必建`LongHashSet`改为primitive `sequence -> firstOrderId`单值路径；仅当同一sequence出现第二个订单时懒加载additional set。普通单不再执行`getIfAbsentPut(..., LongHashSet::new)`或分配集合，批量单保留完整索引、提升和回滚语义。
- 对照PV-56 matcher=1 `36,492.565 terminal business ops/s`和约`16,221 B/terminal business op`，同时检查PV-57中占CPU `21.09%`的`LongObjectHashMap.getIfAbsent(long, Function0)`退出热点。主轮不得回归超过10%，GC轮要求每业务操作分配不回归；业务、资金、订单终态、snapshot recovery和多settlement在途门禁不变。
- 场景固定为`LINEAR_PERPETUAL`、matcher=1、4 Account Lane、10,000用户、512 listed/active symbols、每用户最多5持仓/10活动订单、每invocation 16,384 PLACE_ORDER、50% maker GTC + 50% taker IOC、100,000 offered terminal business ops/s、做市持续运行、严格且仅`256 in-flight`。无profiler主轮`fork=1,warmup=3x3s,measurement=3x5s,thread=1`；GC轮`fork=1,warmup=1x3s,measurement=1x5s`；JFR轮`fork=0,warmup=1x3s,measurement=1x10s`。
- 正确性要求：accepted business/Core分别等于terminal，unfinished/rejected/error/timeout/starvation为0，期末matcher/Lane/in-flight backlog为0，trades为business的50%；teardown检查资金守恒、余额/冻结/持仓、订单生命周期、盘口和snapshot recovery。HotSpot JDK25定向service测试`54/54`、benchmark-support测试`10/10`已通过。
- 环境：Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16、MacBookPro16,1 / Intel Core i9-9880H / 16 logical CPU / 16GiB / macOS 26.7 x86_64；8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、BLOCKING settlement、journal 65536/1GiB及既有Agrona opens/exports。JFC SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。
- 被测HEAD `aec792c85efca5be2f515acdd13abc9922c910d6`，除本文件外diff SHA-256 `97e564d67c56af6f4206d0c7bc7808e3eb09a042ab82391878ee053db3c16006`，shaded JAR SHA-256 `0321fea3308f6fc6182deb6a261dce698f891b81d8617944791666db46a50279`。artifact固定为`target/qualification/20260903T140418Z-pending-reservation-single-index-matcher1-256/`；采集前swap=`189.25MiB`、Pages throttled=0、Pageouts=64,796。Pageouts增长、JFR DataLoss非0或业务门禁不闭合时仅作部分验证；短轮不证明无泄漏。
- 不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线。锁定后不修改场景、参数或门禁，失败和异常只追加结果。

#### 采集结果

- 无profiler主轮：`36,259.695 terminal business/Core messages/s`、`18,129.848 trades/s`，三个business样本为`32,798.174/40,003.307/35,977.605 ops/s`；accepted与terminal闭合，unfinished/rejected/error/timeout/starvation为0，资金、账户/持仓、订单终态、盘口、snapshot recovery和多settlement在途门禁通过。相对PV-56为`-0.64%`，通过10%回归门禁。
- `-prof gc`轮为`45,044.709 terminal business ops/s`、`400.831 MB/s`、`262,062,951.429 B/invocation`，折合约`15,995 B/terminal business op`，较PV-56约`16,221 B/op`下降`1.39%`；measurement内GC次数为0。
- JFR轮为`43,760.238 terminal business/Core messages/s`、`21,880.119 trades/s`，业务门禁闭合。原PV-57 pending reservation的`getIfAbsentPut(..., LongHashSet::new)`调用栈已消失；剩余`LongObjectHashMap.getIfAbsent`样本来自Account Lane admission/risk、ActiveOrderIndex和RuntimeFactIndexes。新的`PendingReservationSequenceIndex`普通路径未进入主要CPU或allocation site，batch fallback不在本场景中触发。
- JFR记录39秒、1,459个execution samples、43,305个allocation samples、8次ZGC、DataLoss=0；34次GC pause合计`0.555ms`，p50/p90/p95/p99/max=`0.0110/0.0350/0.0505/0.0582/0.0582ms`。主要allocation site仍为`TreeMap.put=10.61%`、primitive map插入/扩容和业务终态对象；heap固定8GiB，native committed峰值主要为GC `144.2MiB`、Tracing `43.6MiB`、Metaspace `31.0MiB`、Code `28.2MiB`，socket I/O和DataLoss均为0。
- Pageouts由64,796增至66,092，且未执行长稳，故本轮为正确性通过、性能门禁通过的短时部分验证，不声明生产容量或无泄漏。main/gc/JFR-json/JFR SHA-256分别为`482b0b5978275463282cf4d199273202fccb7550fb10f96394df388c9803e527`、`a8431342865112d51a36ac9f591f70f6d473e22a80f5aea6e9be5ce9770ff925`、`02666ed8dd44cd051044c2bb9223a2a7206f2c3b7424855fc746f146672428ab`、`83b485ba5a2753eb20c5ac481cc9cfe3a0f0f48c44ff39db82ab6ec73afb4357`；原始JFR约85MiB，summary/views在同一artifact目录。

### 2026-09-03 22:08:30 +08:00 — `PV-20260903-256-59` — `采集前锁定（pending reservation单值索引，matcher=2诊断）`

#### 采集前锁定

- 使用PV-58完全相同代码、JAR、机器和业务场景，仅将matching engines从1改为2，验证多个settlement在途时sequence单值索引不会提前移除、错误升级或跨sequence污染；对照PV-58 matcher=1 `36,259.695/s`和PV-54 matcher=2 `37,241.486/s`。本轮仅作扩展性诊断，不替代正式matcher=1口径。
- 固定严格`256 in-flight`、4 Account Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker GTC + 50% taker IOC、100,000 offered；无profiler`fork=1,warmup=3x3s,measurement=3x5s,thread=1`。accepted/terminal、unfinished/backlog、错误、资金、账户/持仓、订单终态、盘口、snapshot recovery和多settlement门禁与PV-58一致。
- HEAD `aec792c85efca5be2f515acdd13abc9922c910d6`，源码diff和JAR SHA-256沿用PV-58；HotSpot 25.0.1、8GiB ZGC及其他JVM参数不变。artifact固定为`target/qualification/20260903T140830Z-pending-reservation-single-index-matcher2-256/`；采集前swap=`189.25MiB`、Pages throttled=0、Pageouts=66,092。不执行JFR/GC/长稳及外围服务测试；锁定后不修改参数或门禁。

#### 采集结果

- 无profiler主轮：`36,263.922 terminal business/Core messages/s`、`18,131.961 trades/s`，三个business样本为`33,253.837/39,816.419/35,721.510 ops/s`。accepted与terminal闭合，unfinished/rejected/error/timeout/starvation为0，资金、账户/持仓、订单终态、盘口、snapshot recovery和多settlement在途门禁通过。
- 相对PV-58 matcher=1为`+0.01%`，相对PV-54 matcher=2为`-2.63%`，均在本机样本波动范围内；两个matcher没有吞吐扩展收益，但也没有sequence索引回归、提前复用或状态污染。
- Pageouts由66,092增至66,345，因此本轮仅作matcher扩展性诊断。JSON SHA-256 `b604411e4738b2d699eb39a0e8732fa33a908f21da89f6e569d116596a93e70f`。

### 2026-09-03 22:28:22 +08:00 — `PV-20260903-256-60` — `采集前锁定（热索引合并更新，matcher=1）`

#### 采集前锁定

- 被测修改：Account Lane、活动订单及batch admission将pending/reduce-only/margin-mode三次订单扫描合并为一次；活动订单索引只对变化的user/symbol集合做差量更新；持仓终态只物化一个`RuntimePositionIndexValue`并由PositionUser/OpenInterest/ADL共享，删除前两者重复position map；OpenInterest热路径使用可变HashMap聚合并按同symbol净差量更新，排序及不可变Totals只在查询边界生成；changed order/position缓冲直接携带最终runtime值供索引提交，已知终态路径不再回查Lane。
- 对照与门禁：对照PV-58 matcher=1 `36,259.695 terminal business ops/s`及约`15,995 B/terminal business op`；无profiler主轮不得回归超过10%，GC轮每操作分配不得回归。accepted business/Core分别等于terminal，unfinished/rejected/error/timeout/starvation为0，期末matcher/Lane/in-flight backlog为0，trades为business的50%；teardown必须通过资金守恒、余额/冻结/持仓、订单生命周期、盘口、snapshot recovery和多settlement在途检查。
- 固定场景：仅`LINEAR_PERPETUAL`进程内交易链路；1 matching engine、4 Account Lane、10,000活跃用户、512 listed/active symbols、每用户最多5持仓/10活动订单、每invocation 16,384 PLACE_ORDER、50% maker GTC + 50% taker IOC、100,000 offered terminal business ops/s、做市持续运行、严格且仅`256 in-flight`；open-loop并修正coordinated omission。主轮`fork=1,warmup=3x3s,measurement=3x5s,thread=1`，GC轮`fork=1,warmup=1x3s,measurement=1x5s`，JFR轮`fork=0,warmup=1x3s,measurement=1x10s`。
- 环境：Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16、MacBookPro16,1 / Intel Core i9-9880H / 16 logical CPU / 16GiB / macOS 26.7 x86_64；8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、BLOCKING settlement、journal 65536/1GiB及既有Agrona opens/exports。定向service测试`62/62`和benchmark-support测试`10/10`通过。
- 被测HEAD `f2baecbedab100ff40d238b5fbf5ec3f32de6b3c`，除本文件外diff SHA-256 `a3c8b04cfc2917a02a95c9ec4cd3968274d3a80ea5144c72e00a0cf186c842c3`，shaded JAR SHA-256 `5b1cab53bf7edb38dbef09d7b12e3731c7d883688f6ac3c4b69effa338f6e764`。artifact固定为`target/qualification/20260903T142822Z-hot-index-consolidation-matcher1-256/`；采集前swap=`157.25MiB`、Pages throttled=0、Pageouts=66,345。Pageouts增长、JFR DataLoss非0或业务门禁不闭合时仅作部分验证；短轮不证明无泄漏。
- 不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线。锁定后不修改场景、参数或门禁，失败和异常只追加结果。

#### 采集结果

- 无profiler主轮：`37,237.544 terminal business/Core messages/s`、`18,618.772 trades/s`，三个business样本为`33,186.134/37,793.593/40,732.904 ops/s`；accepted与terminal闭合，unfinished/rejected/error/timeout/producer-starvation为0，资金、余额/冻结/持仓、订单终态、盘口、snapshot recovery和多settlement在途门禁通过。相对PV-58提升`2.70%`，通过10%回归门禁。
- `-prof gc`轮为`43,744.744 terminal business ops/s`、`384.120 MB/s`、`257,413,218.286 B/invocation`，折合约`15,711 B/terminal business op`，较PV-58约`15,995 B/op`下降`1.78%`；measurement内GC次数为0。
- JFR轮为`42,758.530 terminal business/Core messages/s`、`21,379.265 trades/s`，557,056个PLACE_ORDER样本；entry→accepted p50/p90/p95/p99/p99.9/max=`134.218/268.435/268.435/536.871/536.871/422.394 ms`，accepted→terminal=`8.389/8.389/8.389/16.777/67.109/110.816 ms`，entry→terminal max=`425.764 ms`。业务门禁闭合。
- JFR热点确认：OpenInterest热更新不再通过TreeMap或逐次构造Totals，PositionUser/OpenInterest不再维护重复position-value map；三个索引共享一次`RuntimePositionIndexValue`终态物化。Account Lane admission调用栈只剩一次`reservationIdsByUser.get`和一次`inspect`遍历。`LongObjectHashMap.getIfAbsent`仍占336/1,389 execution samples，来源主要为ActiveOrder旧值读取、Account Lane用户订单集合定位、changed-balance及少数仅携带key的结算定位，不再是三遍admission扫描。
- JFR运行时：sampled allocation约`12.000GB`、聚合器估算`21,542 sampled B/business op`（采样口径，仅作归因，分配验收采用GC轮）；热点仍为其他有序状态/快照边界的`TreeMap.put=10.56%`、primitive map插入扩容及订单终态对象。6次ZGC、28次pause合计`0.366ms`，pause p50/p90/p95/p99/max=`0.0096/0.0216/0.0483/0.0579/0.0579 ms`，allocation stall/failure=0。heap committed 8GiB；NMT committed峰值主要为GC `181.1MiB`、Tracing `32.9MiB`、Metaspace `31.1MiB`、Code `28.2MiB`，Direct Buffer为0。owner同步I/O和socket I/O为0，DataLoss=0；最大到达safepoint约`2.062ms`，最大VM operation为`13.124ms` HandshakeAllThreads。最长JIT编译为snapshot codec `790ms`，采样窗口仍包含较多编译。
- 严格分析器因既有启动期反射/native能力探测的1,003个异常及线程角色完整性门禁返回非零；Pageouts由66,345增至69,485，且未执行长稳，所以本轮是正确性及短时性能门禁通过的部分验证，不声明生产容量或无泄漏。main/gc/JFR-json/JFR/aggregate SHA-256分别为`d9e79cea8b161b2ace04863d5a7dc9c53891b6d4444daadbd3a841481239b010`、`7ae4d33c6a686cccf40e11357519aa3b58717273dc5ba40f11b70d25890c6b70`、`0628ec8b430fdb6107edf1c6c0963cf7f99b57470f2d504c2d45c46a4c4f6caf`、`fcb8fd81baf53f62e5df1ed75ffd793fe3350840d058467f2409c94a487af2a3`、`aaed772fb203d511fb32c519e158dc776aba442d412597d59b44761168671f11`；原始JFR约84MiB，summary/views/聚合位于同一artifact的`jfr-analysis/`。

### 2026-09-03 22:36:02 +08:00 — `PV-20260903-256-61` — `采集前锁定（热索引合并更新，matcher=2诊断）`

#### 采集前锁定

- 使用PV-60完全相同代码、JAR、机器和业务场景，仅将matching engines从1改为2，检查共享终态值、差量索引和复用admission summary在多个settlement并行在途时不会串sequence或提前覆盖；对照PV-60 matcher=1 `37,237.544/s`及PV-59 matcher=2 `36,263.922/s`。本轮仅作matcher扩展性诊断，不替代正式matcher=1口径。
- 固定严格`256 in-flight`、4 Account Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker GTC + 50% taker IOC、100,000 offered；无profiler`fork=1,warmup=3x3s,measurement=3x5s,thread=1`。accepted/terminal、unfinished/backlog、错误、资金、账户/持仓、订单终态、盘口、snapshot recovery和多settlement门禁与PV-60一致。
- HEAD `f2baecbedab100ff40d238b5fbf5ec3f32de6b3c`，源码diff与JAR SHA-256沿用PV-60；HotSpot 25.0.1、8GiB ZGC及其他JVM参数不变。artifact固定为`target/qualification/20260903T143602Z-hot-index-consolidation-matcher2-256/`；采集前swap=`157.25MiB`、Pages throttled=0、Pageouts=69,485。不执行JFR/GC/长稳及外围服务测试；锁定后不修改参数或门禁。

#### 采集结果

- 无profiler主轮：`36,851.660 terminal business/Core messages/s`、`18,425.830 trades/s`，三个business样本为`32,910.047/40,226.170/37,418.762 ops/s`。accepted与terminal闭合，unfinished/rejected/error/timeout/producer-starvation为0，资金、账户/持仓、订单终态、盘口、snapshot recovery和多settlement在途门禁通过。
- 相对PV-60 matcher=1为`-1.04%`，相对PV-59 matcher=2提升`1.62%`，均处于本机样本波动范围；两个matcher仍没有可确认的吞吐扩展收益，但共享终态值和复用summary未出现跨sequence覆盖、提前复用或资金/索引污染。
- Pageouts由69,485增至69,573，本轮只作matcher扩展性诊断。JSON SHA-256 `559169abb657218b146eba4ee6d4b901d295d471af30877b6a57070a905b77e8`。

### 2026-09-03 23:34:00 +08:00 — `PV-20260903-256-62` — `采集前锁定（Lane终态增量与owner边界瘦身，matcher=1）`

#### 采集前锁定

- 被测修改：Account Lane在每sequence settlement context内生成最终order/position索引值和余额资金增量；owner按sequence安装终态，不再扫描matcher plan重建changed-key，不再重复物化ActiveOrder、PositionUser、OpenInterest和ADL索引值。命令响应在changed-key清理前直接读取该sequence终态；snapshot batch挂起上下文迁入`LaneCommandContextRing.Context`；commit journal只保留entry容量和sequence，不再计算字节容量或在owner提交时传递审计hash；result/source digest改为删除或仅在snapshot边界计算。
- 对照与门禁：对照PV-60 matcher=1 `37,237.544 terminal business ops/s`和约`15,711 B/terminal business op`；无profiler主轮不得回归超过10%，GC轮每操作分配不得回归。accepted business/Core分别等于terminal，unfinished/rejected/error/timeout/producer-starvation为0，期末matcher/Lane/in-flight/context backlog为0，trades为business的50%；teardown必须通过资金守恒、余额/冻结/持仓、订单生命周期、盘口、snapshot recovery和多settlement在途检查。
- 固定场景：仅`LINEAR_PERPETUAL`进程内交易链路；1 matching engine、4 Account Lane、10,000活跃用户、512 listed/active symbols、每用户最多5持仓/10活动订单、每invocation 16,384 PLACE_ORDER、50% maker GTC + 50% taker IOC、100,000 offered terminal business ops/s、做市持续运行、严格且仅`256 in-flight`；open-loop并修正coordinated omission。主轮`fork=1,warmup=3x3s,measurement=3x5s,thread=1`，GC轮`fork=1,warmup=1x3s,measurement=1x5s`，JFR轮`fork=0,warmup=1x3s,measurement=1x10s`。
- 环境：Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16、MacBookPro16,1 / Intel Core i9-9880H / 16 logical CPU / 16GiB / macOS 26.7 x86_64；8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、BLOCKING settlement、journal 65536。生产代码和benchmark shaded JAR构建通过；service测试受已删除exporter API的遗留测试源码统一testCompile阻塞，本轮不恢复或测试exporter。
- 被测HEAD `d866c7cc28155a38e55c9ae5aa89ee728d201d1e`，除本文件外dirty diff SHA-256 `d32d2e917969019447593655c229fe6974ec47864191c9ee1eda319441beddde`，shaded JAR SHA-256 `e2a1d86f37bacce7134f234d634179f27052c63a856d1c7d02d8c7bac23d5d43`。artifact固定为`target/qualification/20260903T153400Z-lane-terminal-delta-matcher1-256/`；采集前swap=`157.25MiB`、Pages throttled=0、Pageouts=69,573。Pageouts增长、JFR DataLoss非0或业务门禁不闭合时仅作部分验证；短轮不证明无泄漏。
- 不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线。锁定后不修改场景、参数或门禁，失败和异常只追加结果。

#### PV-62采集结果

- 无profiler主轮：`36,190.068 terminal business/Core messages/s`、`18,095.034 trades/s`，三个business样本为`34,622.779/35,990.242/37,957.184 ops/s`。accepted与terminal闭合，unfinished/rejected/error/timeout/producer-starvation为0，资金、余额/冻结/持仓、订单终态、盘口、snapshot recovery和多settlement在途门禁通过；相对PV-60为`-2.81%`，通过10%回归门禁。
- `-prof gc`轮为`52,410.344 terminal business ops/s`、`446.461 MB/s`、`256,220,023.529 B/invocation`，折合约`15,638 B/terminal business op`，较PV-60约`15,711 B/op`下降`0.46%`；measurement内1次GC、累计显示约0ms。
- JFR轮为`44,111.712 terminal business/Core messages/s`、`22,055.856 trades/s`，业务门禁闭合。原owner matcher-plan changed-key重扫和owner侧order/position索引物化已退出热点；`ChangeBuffer.forEach`仅占CPU `0.59%`。主要CPU仍为Account Lane及索引使用的primitive `LongObjectHashMap.getIfAbsent=24.26%`、`TreeMap.put=4.53%`和`progressPlaceAdmissions=2.60%`；主要allocation为`TreeMap.put=10.62%`、primitive map插入/扩容、`List.copyOf=2.21%`、OrderRuntime和最终命令编码。Lane侧新增`RuntimeFundsDelta`占sampled allocation `1.43%`。
- JFR约82MiB，线程分配约11.6GiB，其中owner/JMH worker 7.8GiB/67.25%，4个Lane各约632–633MiB；5次ZGC、23次pause合计`0.288ms`，pause p50/p90/p95/p99/max=`0.0102/0.0244/0.0368/0.0393/0.0393ms`。DataLoss=0、异常统计=0、socket I/O=0；文件写为JFR/JMH artifact。Pageouts由69,573增至70,316，因此本轮是正确性和短时性能门禁通过的部分验证，不作无泄漏结论。
- main/gc/JFR-json/JFR SHA-256分别为`4099f005728dfb2acde08930b61170a9ebcb5922c85caf891692882ce601b9fe`、`843cc7d89c8f9f5f26873fb94e1281af561678f4c2f01a5a33000cfd6484c1c2`、`5e1b81f2c94fdb79c7a8e14a46fefd05dd47a21bea29eea2b02d66896b2f928d`、`70ebfd58860da2132aa429ca3f11f0d0be518780d689eb105775c2bddf6c1f4c`；summary/views位于同一artifact的`jfr-analysis/`。

### 2026-09-03 23:38:00 +08:00 — `PV-20260903-256-63` — `采集前锁定（Lane终态增量，matcher=2诊断）`

#### 采集前锁定

- 使用PV-62完全相同代码、JAR、机器和业务场景，仅将matching engines从1改为2，验证Lane预物化索引值、Lane资金增量及sequence context不会跨settlement提前可见或被复用；对照PV-62 matcher=1 `36,190.068/s`和PV-61 matcher=2 `36,851.660/s`。本轮仅作扩展性诊断，不替代正式matcher=1口径。
- 固定严格`256 in-flight`、4 Account Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker GTC + 50% taker IOC、100,000 offered；无profiler`fork=1,warmup=3x3s,measurement=3x5s,thread=1`。accepted/terminal、unfinished/backlog、错误、资金、账户/持仓、订单终态、盘口、snapshot recovery和多settlement门禁与PV-62一致。
- HEAD `d866c7cc28155a38e55c9ae5aa89ee728d201d1e`，dirty源码diff与JAR SHA-256沿用PV-62；HotSpot 25.0.1、8GiB ZGC及其他JVM参数不变。artifact固定为`target/qualification/20260903T153800Z-lane-terminal-delta-matcher2-256/`；采集前swap=`157.25MiB`、Pages throttled=0、Pageouts=70,316。不执行JFR/GC/长稳及外围服务测试；锁定后不修改参数或门禁。

#### 采集结果

- 无profiler主轮：`38,830.469 terminal business/Core messages/s`、`19,415.234 trades/s`，三个business样本为`38,483.346/43,255.164/34,752.896 ops/s`。accepted与terminal闭合，unfinished/rejected/error/timeout/producer-starvation为0，资金、余额/冻结/持仓、订单终态、盘口、snapshot recovery和多settlement在途门禁通过。
- 相对PV-62 matcher=1为`+7.30%`，相对PV-61 matcher=2为`+5.37%`；但三个样本波动较大、区间与单matcher重叠，只能说明本轮两个matcher没有回退，不能认定已有稳定线性扩展。
- Pageouts由70,316增至70,361，本轮仅作matcher扩展性诊断。JSON SHA-256 `1a165da8ade6d604ddf31166bbe8f379f81b3dcb268961e56674dd4a7539b1f9`。

### 2026-09-03 23:46:00 +08:00 — `PV-20260903-256-64` — `采集前锁定（最终sequence-owned admission，matcher=1）`

#### 采集前锁定

- 在PV-62代码上完成最后的所有权收口：`CoreAdmissionReservation`从`PendingMatching`转移到对应`LaneCommandContextRing.Context`，后续提交、拒绝、恢复和关闭均按sequence取用，不再由pending对象与sequence context重复持有。新增sequence admission/挂起commit context生命周期测试。
- 对照PV-62 matcher=1 `36,190.068 terminal business ops/s`和约`15,638 B/terminal business op`；固定场景、业务/资金/snapshot门禁、HotSpot 25、8GiB ZGC、严格`256 in-flight`与PV-62完全一致。主轮`fork=1,warmup=3x3s,measurement=3x5s`；GC轮`fork=1,warmup=1x3s,measurement=1x5s`；JFR轮`fork=0,warmup=1x3s,measurement=1x10s`。主轮不得回归超过10%，GC分配不得回归。
- 最终定向测试共`59/59`通过（TradingRuntimeState、RuntimeCommitJournal、RuntimeChangedIndexCommit、LaneCommandContextRing、CorePerpetualFinancialMatrix、CorePerpetualEndToEndBenchmark及result digest边界）；常规全testCompile仍被已删除exporter API的遗留测试源码阻塞，不恢复或测试exporter。
- HEAD `d866c7cc28155a38e55c9ae5aa89ee728d201d1e`，除本文件外dirty diff SHA-256 `534feb6cf6ff4e04f9ae1b5bbd37c482c27a4f21fc93a5c4afa12672560fad66`，shaded JAR SHA-256 `feeaf4f349cacdf95d1c15fa15970092f651cea19e70247556ad86d452c04093`。artifact固定为`target/qualification/20260903T154600Z-final-sequence-admission-matcher1-256/`；采集前swap=`157.25MiB`、Pages throttled=0、Pageouts=70,361。不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他产品线；锁定后不修改场景或门禁。

#### 采集结果

- 无profiler主轮：`38,813.074 terminal business/Core messages/s`、`19,406.537 trades/s`，三个business样本为`39,866.163/43,443.802/33,129.257 ops/s`。accepted与terminal闭合，unfinished/rejected/error/timeout/producer-starvation为0，资金、余额/冻结/持仓、订单终态、盘口、snapshot recovery和多settlement在途门禁通过；相对PV-62提升`7.25%`，相对PV-60提升`4.23%`。
- `-prof gc`轮为`51,966.139 terminal business ops/s`、`458.032 MB/s`、`259,468,743 B/invocation`，折合约`15,837 B/terminal business op`。比PV-62高约`1.27%`、比PV-60高约`0.80%`，处于单次GC采样噪声但未达到预设的严格“不回归”分配门禁，因此分配项标记为未通过；吞吐和业务正确性通过。
- JFR轮为`44,133.434 terminal business/Core messages/s`、`22,066.717 trades/s`，业务门禁闭合。主要CPU仍为primitive `LongObjectHashMap.getIfAbsent=26.21%`、`TreeMap.put=3.52%`、HashMap查询和`progressPlaceAdmissions=1.91%`；sequence admission转移未形成新热点，Lane终态缓冲`ChangeBuffer.forEach=0.73%`。6次ZGC、28次pause合计`0.493ms`，pause p50/p90/p95/p99/max=`0.0110/0.0555/0.0586/0.0593/0.0593ms`；DataLoss=0、异常统计=0、socket I/O=0。
- Pageouts由70,361增至70,725，且未执行长稳，因此本轮为吞吐/正确性通过、分配门禁未通过的短时部分验证，不声明生产容量或无泄漏。main/gc/JFR-json/JFR SHA-256分别为`27e2466dc6d194722ee593c9f65869953f019c222caab5c0be56f0b37ff93371`、`0414f71c29fd0aa34bc7b4585430f4298b322605579adb476eec5820a6192d4d`、`faf3ad83a03db40b1f395770dd48ca73ad5d23d27cfac45875b2baa074682231`、`776e2853681fa877d5cfdd7387549e3d753964023f2322f049507d0a70b5caed`；JFR约82MiB，summary/views在同一artifact的`jfr-analysis/`。

### 2026-09-03 23:50:00 +08:00 — `PV-20260903-256-65` — `采集前锁定（最终sequence-owned admission，matcher=2诊断）`

#### 采集前锁定

- 使用PV-64完全相同最终代码、JAR、机器与业务场景，仅将matching engines从1改为2；对照PV-64 matcher=1 `38,813.074/s`与PV-63 matcher=2 `38,830.469/s`。本轮仅作扩展性和跨sequence所有权诊断，不替代matcher=1正式口径。
- 固定严格`256 in-flight`、4 Account Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker GTC + 50% taker IOC、100,000 offered；无profiler`fork=1,warmup=3x3s,measurement=3x5s,thread=1`。accepted/terminal、unfinished/backlog、错误、资金、订单终态、盘口、snapshot recovery和多settlement门禁与PV-64一致。
- HEAD、dirty diff和JAR SHA-256沿用PV-64；artifact固定为`target/qualification/20260903T155000Z-final-sequence-admission-matcher2-256/`；采集前swap=`157.25MiB`、Pages throttled=0、Pageouts=70,725。不执行JFR/GC/长稳及外围服务测试；锁定后不修改参数或门禁。

#### 采集结果

- 无profiler主轮：`40,544.036 terminal business/Core messages/s`、`20,272.018 trades/s`，三个business样本为`40,696.830/44,289.975/36,645.304 ops/s`。accepted与terminal闭合，unfinished/rejected/error/timeout/producer-starvation为0，资金、余额/冻结/持仓、订单终态、盘口、snapshot recovery和多settlement在途门禁通过。
- 相对最终PV-64 matcher=1为`+4.46%`，相对PV-63 matcher=2为`+4.41%`；样本区间仍重叠，结论是两个matcher无回退且略有正向趋势，不能宣称线性扩展。Pageouts由70,725增至70,763，本轮仅作诊断。
- JSON SHA-256 `e6d9e99f9f68b0feae664bfbf23b87d2dc504327e0514fc1facf613ebef27ad9`。

### 2026-09-03 23:56:00 +08:00 — `PV-20260903-256-66` — `采集前锁定（sequence admission唯一所有权，matcher=1）`

#### 采集前锁定

- 在PV-64代码上消除延迟命令重新激活时对同一`CoreAdmissionReservation`的重复引用：reservation只由对应`LaneCommandContextRing.Context`持有，`PendingMatching`仅在首次claim前作一次性运输；正常、拒绝、批量、异步settlement和close释放路径已逐项审计。新增一次性转移与容量归零测试。
- 对照PV-64 matcher=1 `38,813.074 terminal business ops/s`和约`15,837 B/terminal business op`；固定场景、业务/资金/snapshot门禁、HotSpot 25、8GiB ZGC、严格`256 in-flight`与PV-64完全一致。主轮`fork=1,warmup=3x3s,measurement=3x5s,thread=1`；本次所有权修正不改变结算计算，因此只重跑无profiler正式主轮，吞吐不得回归超过10%。
- 定向service测试`61/61`通过（TradingRuntimeState、RuntimeCommitJournal、RuntimeChangedIndexCommit、LaneCommandContextRing、PendingMatching、CorePerpetualFinancialMatrix、CorePerpetualEndToEndBenchmark及result digest边界）；生产代码与benchmark shaded JAR构建通过。常规全testCompile仍被已删除exporter API的遗留测试源码阻塞，本轮不恢复或测试exporter。
- 被测HEAD `d866c7cc28155a38e55c9ae5aa89ee728d201d1e`，除本文件外dirty diff SHA-256 `fcfee32c577891914468e67f144e79070c3ec08c169c6eab1c1be800e3cf9245`，shaded JAR SHA-256 `ae0c8217f3a7b66487cf81f023fa4ff9d93a3d9da457a20d7ab5b289e65a664b`。artifact固定为`target/qualification/20260903T155600Z-sequence-admission-single-owner-matcher1-256/`；采集前swap=`157.25MiB`、Pages throttled=0、Pageouts=70,763。不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他产品线；锁定后不修改场景或门禁。

#### 采集结果

- 无profiler主轮：`40,539.446 terminal business/Core messages/s`、`20,269.723 trades/s`，三个business样本为`40,167.689/44,714.739/36,735.910 ops/s`。accepted与terminal闭合，unfinished/rejected/error/timeout/producer-starvation为0，资金、余额/冻结/持仓、订单终态、盘口、snapshot recovery和多settlement在途门禁通过。
- 相对PV-64提升`4.45%`，相对PV-60提升`8.87%`，通过10%回归门禁。Pageouts由70,763增至70,833，故仍是短时部分验证；本轮不新增分配、JFR或长稳结论。JSON SHA-256 `4f7fe4fdb1562ab2cf8ec76546a97e59462db96d1de4ad631b38fd85e1937fda`。

### 2026-09-04 00:00:00 +08:00 — `PV-20260903-256-67` — `采集前锁定（sequence admission唯一所有权，matcher=2诊断）`

#### 采集前锁定

- 使用PV-66完全相同最终代码、JAR、机器与业务场景，仅将matching engines从1改为2，验证唯一sequence reservation所有权在多settlement并行在途时不会串sequence、重复释放或丢失容量；对照PV-66 matcher=1 `40,539.446/s`与PV-65 matcher=2 `40,544.036/s`。本轮仅作扩展性诊断，不替代matcher=1正式口径。
- 固定严格`256 in-flight`、4 Account Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker GTC + 50% taker IOC、100,000 offered；无profiler`fork=1,warmup=3x3s,measurement=3x5s,thread=1`。accepted/terminal、unfinished/backlog、错误、资金、订单终态、盘口、snapshot recovery和多settlement门禁与PV-66一致。
- HEAD、dirty diff和JAR SHA-256沿用PV-66；artifact固定为`target/qualification/20260903T160000Z-sequence-admission-single-owner-matcher2-256/`；采集前swap=`157.25MiB`、Pages throttled=0、Pageouts=70,833。不执行JFR/GC/长稳及外围服务测试；锁定后不修改参数或门禁。

#### 采集结果

- 无profiler主轮：`40,171.257 terminal business/Core messages/s`、`20,085.628 trades/s`，三个business样本为`40,125.658/44,037.567/36,350.545 ops/s`。accepted与terminal闭合，unfinished/rejected/error/timeout/producer-starvation为0，资金、余额/冻结/持仓、订单终态、盘口、snapshot recovery和多settlement在途门禁通过。
- 相对PV-66 matcher=1为`-0.91%`，相对PV-65 matcher=2为`-0.92%`，均在样本波动范围；两个matcher没有可确认的扩展收益，也没有sequence reservation串扰、重复释放或容量泄漏。Pageouts由70,833增至71,331，环境门禁未通过，本轮只作matcher扩展性诊断。JSON SHA-256 `9404f10fc7e6c633c9f56ae9638511ab64dd4eceb008945d6bba24d84b24d9dd`。

### 2026-09-04 00:06:00 +08:00 — `PV-20260904-256-68` — `采集前锁定（删除Lane审计参数链，matcher=1）`

#### 采集前锁定

- 删除`auditBusinessStateHash/auditFundsStateHash`从owner经`stageLaneMutation`和`MatcherSettlementEvent`传到`AccountLaneState.applied`的热路径参数链；两个参数在Lane中从未参与任何计算，Lane只需校验并推进本地sequence/revision。snapshot/query边界所需的canonical hash保持不变。
- 对照PV-66 matcher=1 `40,539.446 terminal business ops/s`；固定场景、HotSpot 25、8GiB ZGC、严格`256 in-flight`和全部业务/资金/snapshot门禁不变。无profiler主轮`fork=1,warmup=3x3s,measurement=3x5s,thread=1`，吞吐不得回归超过10%。定向service测试`61/61`和生产/benchmark构建通过。
- 被测HEAD `d866c7cc28155a38e55c9ae5aa89ee728d201d1e`，除本文件外dirty diff SHA-256 `fdbc6960d0abb9d74cb373d7361f55f3f0fc4511be90adf483c688b36ab232ed`，shaded JAR SHA-256 `36fb205102c543adcee12e628d84cbad1aca1227d18a63cfe3ca363ce52d64e2`。artifact固定为`target/qualification/20260904T000600Z-no-lane-audit-contributions-matcher1-256/`；采集前swap=`157.25MiB`、Pages throttled=0、Pageouts=71,331。不测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他产品线；锁定后不修改参数或门禁。

#### 采集结果

- 无profiler主轮：`40,314.823 terminal business/Core messages/s`、`20,157.412 trades/s`，三个business样本为`38,911.868/44,774.750/37,257.851 ops/s`。accepted与terminal闭合，unfinished/rejected/error/timeout/producer-starvation为0，资金、余额/冻结/持仓、订单终态、盘口、snapshot recovery和多settlement在途门禁通过。
- 相对PV-66波动`-0.55%`，相对PV-60提升`8.26%`，通过回归门禁。Pageouts由71,331增至71,447，仍为短时部分验证；JSON SHA-256 `0291f8bacb37894ac4184eb199da1c7ecd16fe0924ddd52fa9c23ed234d04329`。

### 2026-09-04 00:08:00 +08:00 — `PV-20260904-256-69` — `采集前锁定（删除Lane审计参数链，matcher=2诊断）`

#### 采集前锁定

- 使用PV-68完全相同最终代码、JAR、机器和场景，仅将matching engines从1改为2；对照PV-68 matcher=1 `40,314.823/s`和PV-67 matcher=2 `40,171.257/s`。固定严格`256 in-flight`及PV-68全部业务、资金、snapshot和多settlement门禁，主轮`fork=1,warmup=3x3s,measurement=3x5s,thread=1`。
- HEAD、dirty diff和JAR SHA-256沿用PV-68；artifact固定为`target/qualification/20260904T000800Z-no-lane-audit-contributions-matcher2-256/`；采集前swap=`157.25MiB`、Pages throttled=0、Pageouts=71,447。本轮只作matcher扩展性诊断，不执行JFR/GC/长稳或外围服务测试。

#### 采集结果

- 无profiler主轮：`37,537.409 terminal business/Core messages/s`、`18,768.705 trades/s`，三个business样本为`32,652.679/42,139.910/37,819.639 ops/s`。accepted与terminal闭合，unfinished/rejected/error/timeout/producer-starvation为0，资金、余额/冻结/持仓、订单终态、盘口、snapshot recovery和多settlement在途门禁通过。
- 相对PV-68 matcher=1为`-6.89%`，相对PV-67 matcher=2为`-6.56%`；样本区间高度重叠，且Pageouts由71,447增至71,727，因此只能判定两个matcher未出现正确性或所有权问题，不能形成吞吐扩展结论。JSON SHA-256 `a629e08af32028bf8a03d66df378b0268e59f42b686409a7b185a2a1fad38bd6`。

### 2026-09-04 09:03:28 +08:00 — `PV-20260904-256-70` — `采集前锁定（统一sequence context ring，matcher=1）`

#### 采集前锁定

- 被测修改：pending matching、admission、matcher completion/rejection、ready标志、挂起commit context和Lane completion bitmap统一由按`coreSequence & mask`直接定位的固定容量context ring持有；删除pending sequence到slot的`LongIntHashMap`、free-list、重复`PendingMatching[]`和ready数组。commandId与user冲突索引因仍有业务调用方而保留。
- 对照PV-68 matcher=1 `40,314.823 terminal business ops/s`；主轮不得回归超过10%，GC轮每操作分配不得回归。accepted business/Core必须分别等于terminal，unfinished/rejected/error/timeout/producer-starvation为0，期末matcher/Lane/in-flight backlog为0，资金、余额/冻结/持仓、订单终态、盘口、snapshot recovery及多settlement在途门禁必须通过。
- 场景固定为`LINEAR_PERPETUAL`、matcher=1、4 Account Lane、10,000活跃用户、512 listed/active symbols、每invocation 16,384 PLACE_ORDER、50% maker GTC + 50% taker IOC、100,000 offered terminal business ops/s、做市持续运行、严格且仅`256 in-flight`，open-loop并修正coordinated omission。无profiler主轮`fork=1,warmup=3x3s,measurement=3x5s,thread=1`；GC轮`fork=1,warmup=1x3s,measurement=1x5s`；JFR轮`fork=0,warmup=1x3s,measurement=1x10s`。
- 环境：Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16、MacBookPro16,1 / Intel Core i9-9880H / 16 logical CPU / 16GiB / macOS 26.7 x86_64；8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、BLOCKING settlement、journal 65536。定向测试中ring、matcher pipeline、ClusteredService、资金幂等及生命周期等`36/36`通过；`CoreMatchingStateTest`其余`37/37`非exporter用例通过，2项已删除exporter断言按范围排除。
- 被测HEAD `aa8b16ee69b8df66030df9f471e057ae00b9c548`，除本文件外dirty diff SHA-256 `dfb040390fefd31b4d4cdd50d3729ea307f6b3aa41c5431c9d67629084eca7e7`，shaded JAR SHA-256 `5cbc2abd7d4670265ebd67208e14a7a09ae18eefb615b0ca6e07afcbc78db02b`。artifact固定为`target/qualification/20260904T010328Z-unified-sequence-context-matcher1-256/`；采集前swap=`125.25MiB`、Pages throttled=0、Pageouts=73,372。Pageouts增长、JFR DataLoss非0或业务门禁不闭合时仅作部分验证；短轮不证明无泄漏。
- 不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线。锁定后不修改场景、参数或门禁，失败和异常只追加结果。

#### PV-70采集结果

- 无profiler主轮：`38,088.840 terminal business/Core messages/s`、`19,044.420 trades/s`，三个business样本为`32,471.015/42,340.349/39,455.156 ops/s`。accepted与terminal闭合，unfinished/rejected/error/timeout/producer-starvation为0，资金、余额/冻结/持仓、订单终态、盘口、snapshot recovery和多settlement在途门禁通过；相对PV-68为`-5.52%`，通过10%回归门禁。
- `-prof gc`轮为`44,237.203 terminal business ops/s`、`396.702 MB/s`、`265,412,636 B/invocation`，折合约`16,199 B/terminal business op`，较PV-64约`15,837 B/op`高`2.29%`，未通过严格分配不回归门禁；measurement内GC次数为0。统一ring本身没有进入主要allocation site，差异按单轮噪声处理但不宣称分配改进。
- JFR轮为`41,847.722 terminal business/Core messages/s`、`20,923.861 trades/s`，业务门禁闭合。pending sequence到slot的hash lookup已退出热点；当前`LongObjectHashMap.getIfAbsent=16.21%`的221个样本中203个来自Account Lane `LaneAdmissionOrderIndex.inspect`，`progressPlaceAdmissions=2.71%`，`collectMatcherSettlement=0.81%`，说明下一瓶颈是Lane准入索引而不是owner completion协调。
- JFR记录41秒、1,363个execution samples、43,411个allocation samples、6次ZGC、DataLoss=0；28次GC pause合计`0.416ms`，p50/p90/p95/p99/max=`0.0110/0.0291/0.0461/0.0519/0.0519ms`。存在一次`123ms` safepoint state synchronization，发生在非GC safepoint，需视作本轮尾延迟异常；owner/JMH worker分配7.5GiB/66.97%，4个Lane各约618MiB。native committed峰值主要为GC `262.7MiB`、Tracing `38.0MiB`、Metaspace `31.0MiB`、Code `28.4MiB`；socket I/O与DataLoss均为0。
- Pageouts由73,372增至75,128，环境门禁未通过且未执行长稳，因此本轮为业务正确性与吞吐门禁通过、分配和环境门禁未通过的部分验证，不声明生产容量或无泄漏。main/gc/JFR-json/JFR SHA-256分别为`bb85f87e4cd538a4a07779f156d813a9566e9e31869fff7c92d0f934b179415d`、`d11df94a9c984d8337c15e7cbea9d937d289631a12cf11cad1e9094cc1b45d80`、`dca100c996351fcc1487f4cc33f7d8c7d482bb7fd5ec250ae6db97306776bcad`、`ca15db86fa0bc5f4877d888b6c5e746f0789de85845c634af8168cb076bbfbab`；summary及相关views位于同一artifact的`jfr-analysis/`。

### 2026-09-04 09:13:20 +08:00 — `PV-20260904-256-71` — `采集前锁定（Lane准入用户索引，matcher=1）`

#### 采集前锁定

- 被测修改：根据PV-70 JFR中203/221个热点样本，将Account Lane的`reservationIdsByUser`从Eclipse Collections `LongObjectHashMap`替换为Agrona primitive `Long2ObjectHashMap`；索引所有权、集合值、更新点和准入扫描语义不变，不新增状态副本或处理阶段。
- 对照PV-70 matcher=1 `38,088.840 terminal business ops/s`，并参考PV-68 `40,314.823/s`；主轮不得较PV-70回归超过10%，JFR要求原`LaneAdmissionOrderIndex.inspect -> LongObjectHashMap.getIfAbsent`调用栈退出热点。业务、资金、snapshot、多settlement及严格`256 in-flight`门禁与PV-70相同。
- 场景固定为`LINEAR_PERPETUAL`、matcher=1、4 Account Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker GTC + 50% taker IOC、100,000 offered；主轮`fork=1,warmup=3x3s,measurement=3x5s,thread=1`，JFR轮`fork=0,warmup=1x3s,measurement=1x10s`。HotSpot 25.0.1、8GiB ZGC、NMT summary、BLOCKING settlement及其他JVM参数不变。
- 定向service测试`77/77`通过。HEAD `aa8b16ee69b8df66030df9f471e057ae00b9c548`，除本文件外dirty diff SHA-256 `4952b1a3c7cc2a3ac838e475c6e8ae56cfee0869e96c6313488f17b8f9687503`，shaded JAR SHA-256 `1f09a78dad674e84411dfb60e2bcd35a6d83a2d94e182786ed21f321cd309c3e`。artifact固定为`target/qualification/20260904T011320Z-lane-admission-index-matcher1-256/`；采集前swap=`125.25MiB`、Pages throttled=0、Pageouts=75,128。
- 不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线；本轮不重复GC或长稳。锁定后不修改场景、参数或门禁。

#### PV-71采集结果

- 无profiler主轮为`37,206.212 terminal business/Core messages/s`、`18,603.106 trades/s`，三个business样本为`32,705.645/39,716.571/39,196.421 ops/s`；业务、资金、snapshot及在途门禁闭合。相对PV-70为`-2.32%`，在波动区间内但没有收益。
- JFR轮为`42,495.735 terminal business ops/s`。`LaneAdmissionOrderIndex.inspect -> LongObjectHashMap.getIfAbsent`仍有229个样本；进一步核对完整栈确认它来自循环内逐orderId执行的`orders.get`，并非user到reservation集合的首个查询。因此本轮替换没有命中热点，代码在下一轮撤销，不保留无证据的数据结构变更。
- Pageouts由75,128增至76,685，本轮仅为失败诊断。main/JFR-json/JFR SHA-256分别为`db23ac900c376173a9cc71dcbaa4990d6742d4209961af8cf904adb8dea9ac`、`1b362270e253b670679329ec9a5f3c7ab2dd43a9c5380a4067d3bb9b4eafef31`、`590e614e9d6a4ef21ea08e036ed331ced232199cf371853e3b706118c9a401e5`。

### 2026-09-04 09:19:38 +08:00 — `PV-20260904-256-72` — `采集前锁定（Lane准入增量汇总，matcher=1）`

#### 采集前锁定

- 被测修改：每个Account Lane按user/symbol增量维护活动订单准入汇总，直接保存pending quantity、reduce-only quantity及冲突margin-mode计数；订单新增、终态、更新、删除和回滚统一经Lane索引更新。`LaneAdmissionOrderIndex.inspect`不再遍历reservation IDs或逐订单查询，未增加跨Lane共享状态或owner阶段。
- 对照PV-70 matcher=1 `38,088.840 terminal business ops/s`及PV-71失败诊断`37,206.212/s`；主轮不得较PV-70回归超过10%，JFR要求`LaneAdmissionOrderIndex.inspect -> orders.get`调用栈退出热点。accepted/terminal、unfinished/backlog、资金、订单、盘口、snapshot recovery与多settlement门禁不变。
- 固定`LINEAR_PERPETUAL`、matcher=1、4 Account Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker GTC + 50% taker IOC、100,000 offered、严格`256 in-flight`。主轮`fork=1,warmup=3x3s,measurement=3x5s,thread=1`，GC轮`fork=1,warmup=1x3s,measurement=1x5s`，JFR轮`fork=0,warmup=1x3s,measurement=1x10s`；HotSpot 25.0.1、8GiB ZGC、NMT及BLOCKING settlement不变。
- 定向service测试`77/77`通过。HEAD `aa8b16ee69b8df66030df9f471e057ae00b9c548`，除本文件外dirty diff SHA-256 `c0995038c568980b6a7561dc5b64b71b479b8e4907347eff7172f67975ee0761`，shaded JAR SHA-256 `376636a9dd19b65eee9312c363736afa1c8fe09e5950b546a290810b8e472810`。artifact固定为`target/qualification/20260904T011938Z-lane-admission-summary-matcher1-256/`；采集前swap=`125.25MiB`、Pages throttled=0、Pageouts=76,685。
- 不测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他产品线；锁定后不修改场景、参数或门禁。

#### PV-72采集结果（无效）

- 第三个measurement因累计1,280,630条命令后基准逻辑时间超过固定mark的5秒 freshness bound，出现`STALE_MARK_PRICE`，本轮立即判无效；前两个样本`48,579.595/56,031.284 terminal business ops/s`不得用于验收或对照。原始JSON保留在锁定artifact目录。

### 2026-09-04 09:25:52 +08:00 — `PV-20260904-256-73` — `采集前锁定（Lane准入增量汇总复测，matcher=1）`

#### 采集前锁定

- 被测修改延续PV-72：每个Account Lane按user/symbol增量维护活动订单的pending quantity、reduce-only quantity和margin-mode计数，准入检查由逐活动订单扫描改为一次Lane本地查询；所有计数先完整计算并校验下溢/溢出，再原子安装。统一sequence context ring修改保持不变。
- 仅修正基准时间源：`COMMANDS_PER_LOGICAL_MILLISECOND`由256调整为1,024，使跨JMH warmup/measurement累计命令仍处于生产代码固定5秒mark freshness窗口；业务动作、比例、到达率、production freshness规则和终态门禁均不变。该修正不进入生产交易逻辑。
- 对照PV-70 matcher=1 `38,088.840 terminal business ops/s`和约`16,199 B/business op`；PV-72无效样本不作对照。主轮不得较PV-70回归超过10%，GC每操作分配不得回归；JFR要求`LaneAdmissionOrderIndex.inspect -> orders.get`逐订单扫描退出热点。accepted business/Core分别必须等于terminal，unfinished/rejected/error/timeout/producer-starvation及期末backlog为0，资金、余额/冻结/持仓、订单终态、盘口、snapshot recovery和多settlement在途门禁必须通过。
- 固定场景：仅`LINEAR_PERPETUAL`进程内交易链路；matcher=1、4 Account Lane、10,000活跃用户、512 listed/active symbols、每用户最多5持仓/10活动订单、每invocation 16,384 PLACE_ORDER、50% maker GTC + 50% taker IOC、100,000 offered terminal business ops/s、做市持续运行、open-loop并修正coordinated omission、严格且仅`256 in-flight`。主轮`fork=1,warmup=3x3s,measurement=3x5s,thread=1`；GC轮`fork=1,warmup=1x3s,measurement=1x5s`；JFR轮`fork=0,warmup=1x3s,measurement=1x10s`。
- 环境：Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16、MacBookPro16,1 / Intel Core i9-9880H / 16 logical CPU / 16GiB / macOS 26.7 x86_64；8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、BLOCKING settlement、journal 65,536、export ACK interval 1,024。受影响service测试`77/77`和benchmark支撑测试`10/10`通过。
- 被测HEAD `aa8b16ee69b8df66030df9f471e057ae00b9c548`，除本文件外dirty diff SHA-256 `715a05154f5ed4d9f5bae7dc90bbf368cf11bed567b70718b715a674d5530fe9`，shaded JAR SHA-256 `9026131e41d970bca24dcaadcffbb754823b07d09d6ed2f9bc8142df81ffab80`。artifact固定为`target/qualification/20260904T012552Z-lane-admission-summary-matcher1-256/`；采集前swap=`125.25MiB`、Pages throttled=0、Pageouts=76,851。Pageouts增长、JFR DataLoss非0或业务门禁不闭合时只作部分验证；短轮不证明无泄漏。
- 不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线。锁定后不修改场景、参数或门禁，失败和异常只追加结果。

#### PV-73采集结果

- 无profiler主轮：`49,470.422 terminal business/Core messages/s`、`24,735.211 trades/s`，三个business样本为`44,691.156/51,315.888/52,404.223 ops/s`。accepted与terminal闭合，unfinished/rejected/error/timeout/producer-starvation为0，资金、余额/冻结/持仓、订单终态、盘口、snapshot recovery和多settlement在途门禁通过；相对PV-70提升`29.88%`。
- `-prof gc`轮为`55,750.249 terminal business ops/s`、`455.484 MB/s`、`261,046,892 B/invocation`，折合约`15,933 B/terminal business op`；较PV-70约`16,200 B/op`下降`1.64%`，通过分配不回归门禁。
- JFR轮为`52,067.829 terminal business/Core messages/s`、`26,033.915 trades/s`，业务门禁闭合。共1,051个execution samples；原`LaneAdmissionOrderIndex.inspect -> orders.get`扫描栈为0，`LaneAdmissionOrderIndex.inspect`也未进入热点，证明本轮收益来自移除逐订单准入扫描。当前主要可识别热点转为`TreeMap.put` 63 samples、`progressPlaceAdmissions` 50 samples、`TreeMap.getEntry/successor`及HashMap查询；增量索引update分配占517/48,846 sampled allocation events，后续优化应优先处理终态物化的TreeMap/索引分配，而不是增加matcher或继续拆owner阶段。
- JFR约103MiB；sampled allocation约`13.70GB`，采样估算`21,441 B/business op`仅作归因。6次ZGC、28次pause合计`0.443ms`，pause p50/p95/p99/max=`0.0102/0.0490/0.0944/0.0944ms`，allocation stall/failure=0；heap committed 8GiB、used峰值约4.57GiB。NMT committed峰值主要为GC `189.9MiB`、Tracing `36.0MiB`、Metaspace `31.1MiB`、Code `28.3MiB`，Direct Buffer为0。DataLoss与socket I/O为0；文件I/O来自JFR/JMH输出及启动读取。最大到达safepoint约`6.50ms`，最长VM operation为`132.24ms HandshakeAllThreads`。
- 既有启动期反射/native能力探测产生1,000个异常，线程角色完整性门禁也不能在进程内JMH中识别owner；Pageouts由76,851增至79,618，且未执行长稳。因此本轮是吞吐、分配和业务正确性门禁通过的短时部分验证，不声明生产容量或无泄漏。main/gc/JFR-json/JFR/aggregate SHA-256分别为`1b1bc8c10f09feec1b2bb797df03c2c5b6d095943100bf699a56e7a5d0f26b55`、`1d8a5e4019f400204b42de9cd917597111b2eb4fa1af1b9278a49d989028048e`、`b4869e772cecef9ac835353183acc0d562913ad0b3a92fab72ceab09b161e1c7`、`08af046c745c184b4d35ce52b0c969dbe71c8ccc2173d5d10a0a45524f82f2d5`、`90456c5018c8f814d569bfeb8ff81ac2f6b33a9a408fe5be0540bb5bafe693f2`；summary/views位于同一artifact的`saturation-jfr-analysis/`。

### 2026-09-04 09:33:44 +08:00 — `PV-20260904-256-74` — `采集前锁定（Lane准入增量汇总，matcher=2诊断）`

#### 采集前锁定

- 使用PV-73完全相同代码、shaded JAR、机器和业务场景，仅将matching engines从1改为2；对照PV-73 matcher=1 `49,470.422/s`及PV-69 matcher=2 `37,537.409/s`。本轮只诊断matcher扩展性，不替代正式matcher=1结论。
- 固定严格`256 in-flight`、4 Account Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker GTC + 50% taker IOC、100,000 offered；无profiler`fork=1,warmup=3x3s,measurement=3x5s,thread=1`。accepted/terminal、unfinished/backlog、错误、资金、订单终态、盘口、snapshot recovery及多settlement门禁与PV-73一致。
- HEAD、dirty diff及JAR SHA-256沿用PV-73；artifact固定为`target/qualification/20260904T013344Z-lane-admission-summary-matcher2-256/`；采集前swap=`125.25MiB`、Pages throttled=0、Pageouts=79,618。不执行GC/JFR/长稳及外围服务测试；锁定后不修改场景、参数或门禁。

#### PV-74采集结果

- 无profiler主轮：`49,914.257 terminal business/Core messages/s`、`24,957.128 trades/s`，三个business样本为`45,460.827/50,587.529/53,694.414 ops/s`。accepted与terminal闭合，unfinished/rejected/error/timeout/producer-starvation为0，资金、余额/冻结/持仓、订单终态、盘口、snapshot recovery和多settlement在途门禁通过。
- 相对PV-73 matcher=1为`+0.90%`，相对PV-69旧matcher=2为`+32.97%`。两个matcher不再倒退，但样本区间与单matcher高度重叠，仍无可确认的matcher扩展收益；当前瓶颈在共享终态物化/索引和有序提交，而不是撮合算力。
- Pageouts由79,618增至79,854，本轮只作matcher扩展性诊断。JSON SHA-256 `355444ff9d9b9c437bee3dddd6aff533d976e5d077c0d886723345be13e89017`。
- 采集完成后仅同步更新根目录与Aeron README的所有权说明，未修改被测Java代码或JAR；包含该文档更新的最终非性能记录diff SHA-256为`94678ddaa2bef0cf2c36f8831cd56d2bc4314d224a6470ba095599a798f24bab`，JAR SHA-256仍为`9026131e41d970bca24dcaadcffbb754823b07d09d6ed2f9bc8142df81ffab80`。

### 2026-09-04 10:16:00 +08:00 — `PV-20260904-256-75` — `采集前锁定（交易链路P0/P1内存优化，matcher=1）`

#### 采集前锁定

- 被测修改：Lane资金变更由每阶段`RuntimeFundsDelta/List`合并改为sequence-local primitive accumulator并仅在owner提交边界物化一次；删除owner对user/order/reservation/position对象的重复镜像，仅保留primitive路由；`PendingMatching`、place admission和matcher settlement event改为固定sequence context/owner池复用；终态order/reservation/client索引在Lane提交时立即裁剪，并将重复ID保护收敛到已有有界snapshot tombstone；命令结果直接编码标量字段，删除中间`CoreCommandResultView`；Account Lane热Map预设容量。终态响应仍读取该sequence changed-order缓冲，资金、顺序和Aeron提交边界不变。
- 对照PV-73 matcher=1 `49,470.422 terminal business ops/s`和`15,933 B/terminal business op`。无profiler主轮不得回归超过10%（不低于`44,523.380/s`），GC轮每操作分配不得高于`15,933 B/op`；预期目标为明显降低终态对象、event/context及资金聚合分配。accepted business/Core分别必须等于terminal，unfinished/rejected/error/timeout/producer-starvation及期末matcher/Lane/in-flight/context backlog为0，trades为business的50%；资金守恒、余额/冻结/持仓、订单终态、盘口、snapshot recovery、多settlement在途和有界terminal tombstone门禁必须通过。
- 固定场景：仅`LINEAR_PERPETUAL`进程内交易链路；matcher=1、4 Account Lane、10,000活跃用户、512 listed/active symbols、每用户最多5持仓/10活动订单、每invocation 16,384 PLACE_ORDER、50% maker GTC + 50% taker IOC、100,000 offered terminal business ops/s、做市持续运行、open-loop并修正coordinated omission、严格且仅`256 in-flight`。主轮`fork=1,warmup=3x3s,measurement=3x5s,thread=1`；GC轮`fork=1,warmup=1x3s,measurement=1x5s`；JFR轮`fork=0,warmup=1x3s,measurement=1x10s`。
- 环境：Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16、MacBookPro16,1 / Intel Core i9-9880H / 16 logical CPU / 16GiB / macOS 26.7 x86_64；8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、BLOCKING settlement、journal 65,536。受影响service/protocol测试`109/109`和benchmark支撑测试`10/10`通过。
- 被测HEAD `29c16aef678e0c7865d68f4bcaff73c7603cc980`，包含新增文件且排除本记录的dirty diff SHA-256 `44f3fd6618b2897b0399194216ff4d90fcca5f96cd4d6b68980bd56596a42d17`，shaded JAR SHA-256 `c842df5b6e8d82d919ce22d5c064c87cb0408c4df978880aa420885f41bdd00b`，JFC SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。artifact固定为`target/qualification/20260904T021600Z-p0-p1-memory-matcher1-256/`；采集前swap=`93.25MiB`、Pages throttled=0、Pageouts=79,854。Pageouts增长、JFR DataLoss非0或业务门禁不闭合时只作部分验证；短轮不证明无泄漏。
- 不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线。锁定后不修改场景、参数或门禁，失败和异常只追加结果。

#### PV-75采集结果

- 未进入JMH warmup或measurement：锁定的`surprising-aeron-benchmarks-1.0.0-SNAPSHOT.jar`是普通模块JAR且没有Main-Class，命令立即以`no main manifest attribute`退出。本轮无性能数据，判定无效；原锁定目录保留。

### 2026-09-04 10:19:00 +08:00 — `PV-20260904-256-76` — `采集前锁定（交易链路P0/P1内存优化，matcher=1复测）`

#### 采集前锁定

- 修改内容、对照、通过阈值、业务正确性门禁、场景、负载、JMH/JFR参数、JDK/JVM、机器、GC和不测试范围全部沿用PV-75；唯一修正是使用实际带`org.openjdk.jmh.Main`的shaded JAR `surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar`。
- 被测HEAD `29c16aef678e0c7865d68f4bcaff73c7603cc980`，包含新增文件且排除性能记录的dirty diff SHA-256 `44f3fd6618b2897b0399194216ff4d90fcca5f96cd4d6b68980bd56596a42d17`，shaded JAR SHA-256 `e4e24adf445e9572856135077b301bf5309881489595f6153e9a2dde4b713055`，JFC SHA-256 `dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。artifact固定为`target/qualification/20260904T021900Z-p0-p1-memory-matcher1-256/`；采集前swap=`93.25MiB`、Pages throttled=0、Pageouts=79,854。锁定后不修改场景、参数或门禁，失败和异常只追加结果。

#### PV-76采集结果

- 主轮业务门禁闭合，但只有`10,176.235 terminal business/Core messages/s`和`5,088.117 trades/s`，三个样本为`10,325.007/10,536.401/9,667.296 ops/s`，较PV-73回退`79.43%`，未通过吞吐门禁。JFR诊断轮为`7,282.607/s`，记录约493万次`ThreadPark`，owner/JMH worker CPU约4.5%，证明删除Lane发布索引后`user/order/reservation/position`读取退化为逐次`LaneMutationTask.await()`，导致跨线程同步串行化。
- 本轮代码随后终止：恢复仅保存Lane已提交不可变record引用的owner publication index，终态实体仍从Lane和publication index同步裁剪；event pool增加Lane queue ticket消费完成门禁，修复对象在旧队列slot释放前跨Lane复用的竞态；ready queue允许丢弃已被level-trigger处理的陈旧通知。修改后benchmark支撑测试`10/10`和端到端测试通过，需新建轮次重新采集。
- main/JFR-json/JFR SHA-256分别为`45c7aa5770d8f177d5578708b8913147d5148142fc64286e3536ca986faf0637`、`5d2dcd838703196238b1f0189c5e7c67b00feab64d3d09f1bbaf9f5990dcd9b5`、`8547818f4ffe15f5654c24cdfb145fe2ad07ed537f352d5ce13d3754d75304b9`。本轮失败数据不作最终对照。

### 2026-09-04 10:29:00 +08:00 — `PV-20260904-256-77` — `采集前锁定（P0/P1有界Lane发布索引与安全事件复用，matcher=1）`

#### 采集前锁定

- 延续PV-75全部P0/P1修改，但基于PV-76诊断保留必要的Lane→owner publication index：它只保存Lane提交后的不可变record引用，不复制业务对象、不做业务计算；terminal order/reservation/client索引在Lane内立即删除，owner publication引用在同一sequence安装时删除，历史重复ID保护由最多65,536条snapshot tombstone承担。place admission和matcher settlement池化事件保存每个Lane queue ticket，只有队列slot consumed后才回池；coalesced ready通知可安全忽略已终结sequence。
- 对照、门禁、业务场景、严格`256 in-flight`、matcher=1、4 Account Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker GTC + 50% taker IOC、100,000 offered、JMH/JFR参数、HotSpot 25.0.1与8GiB ZGC配置全部沿用PV-75。吞吐门禁仍为不低于`44,523.380 terminal business ops/s`，分配门禁仍为不高于`15,933 B/op`；业务、资金、snapshot和多settlement门禁必须全部闭合。
- 被测HEAD `29c16aef678e0c7865d68f4bcaff73c7603cc980`，包含新增文件且排除性能记录的dirty diff SHA-256 `0d96e3662184928c258844ebace0087a49e941a9d08443f9281c892ffbb52477`，shaded JAR SHA-256 `d6517bd5dd7fb99e3370af36f6b48e60b062de9154e20c1b4718c62869671b82`，JFC沿用PV-75。artifact固定为`target/qualification/20260904T022900Z-p0-p1-bounded-publication-matcher1-256/`；采集前swap=`734.25MiB`、Pages throttled=0、Pageouts=80,231。swap/pageouts增长、DataLoss或门禁不闭合时只作部分验证；短轮不证明无泄漏。
- 不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线。锁定后不修改场景、参数或门禁，失败和异常只追加结果。

#### PV-77采集结果

- 主轮业务、资金、snapshot及in-flight门禁闭合，但吞吐为`31,968.869 terminal business/Core messages/s`和`15,984.435 trades/s`，三个样本为`36,580.705/30,045.467/29,280.435 ops/s`，较PV-73回退`35.38%`，未通过门禁。归因于为安全池化而在每条admission/settlement回收时等待Lane queue ticket consumed，事件池节省的分配不足以抵消新增同步点。
- 本轮代码随后终止：删除place admission和matcher settlement事件池及其ticket等待，继续保留sequence context内`PendingMatching`复用；ready queue陈旧通知处理及必要的有界publication index保留。修改后端到端和benchmark支撑测试`11/11`通过，需新建轮次重新采集。JSON SHA-256 `859d59d757dde466269ff2c9819b2e7300376294be26d3e899ba01d9b21f0870`。

### 2026-09-04 10:32:00 +08:00 — `PV-20260904-256-78` — `采集前锁定（P0/P1无同步事件回收，matcher=1）`

#### 采集前锁定

- 延续PV-77的primitive funds、sequence-resident pending context、Lane终态裁剪、有界snapshot tombstone、直接结果标量编码、Map容量规划和必要的Lane→owner不可变引用publication index；删除PV-77失败的admission/settlement事件池与queue-ticket同步回收。事件仍按业务命令创建，避免以同步点换取小对象分配下降。
- 对照、门禁、业务场景、严格`256 in-flight`、matcher=1、4 Account Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker GTC + 50% taker IOC、100,000 offered、JMH/JFR参数、HotSpot 25.0.1与8GiB ZGC配置全部沿用PV-75。吞吐门禁不低于`44,523.380/s`，分配门禁不高于`15,933 B/op`，全部业务、资金、snapshot与多settlement门禁必须闭合。
- 被测HEAD `29c16aef678e0c7865d68f4bcaff73c7603cc980`，包含新增文件且排除性能记录的dirty diff SHA-256 `958e91bc02b3edb879b0274424884b28869aa646286d0a53d8fcb2719c709be9`，shaded JAR SHA-256 `4bd96b6e2e65ef836dddde59793c1a76a9aa2dbd0d27e89bd4fa890d4dc1ae03`。artifact固定为`target/qualification/20260904T023200Z-p0-p1-no-event-pool-matcher1-256/`；采集前swap=`702.25MiB`、Pages throttled=0、Pageouts=81,308。JFC及不测试范围沿用PV-75；环境或数据门禁不通过时只作部分验证。

#### PV-78采集结果

- warmup期间失败，无measurement数据：未池化事件仍调用`clear()`，最后完成Lane在设置completion bit后、发布ready通知前与owner发生竞态，`MatcherSettlementEvent.plan`被提前清空。该轮无效，代码随后改为非池化事件不清空、不复用；端到端及benchmark支撑测试`11/11`通过后重新构建。

### 2026-09-04 10:34:00 +08:00 — `PV-20260904-256-79` — `采集前锁定（P0/P1非复用Lane事件，matcher=1）`

#### 采集前锁定

- 修改内容和所有门禁沿用PV-78；唯一修正为非池化的place admission/matcher settlement事件不再执行clear/reuse，彻底移除事件生命周期同步和竞态。`PendingMatching`仍由sequence context ring安全复用。
- 被测HEAD `29c16aef678e0c7865d68f4bcaff73c7603cc980`，包含新增文件且排除性能记录的dirty diff SHA-256 `6e5f99b59e45df9f15436039095ce79fe461b00d35dfc1538ec1289d824c56c9`，shaded JAR SHA-256 `a8c2cdf9adf1a5dd88222e1758c732081e72230f1ef698c044031a5e6908a3f9`。artifact固定为`target/qualification/20260904T023400Z-p0-p1-final-matcher1-256/`；采集前swap=`702.25MiB`、Pages throttled=0、Pageouts=81,408。JFC、场景、阈值、环境及不测试范围全部沿用PV-78，锁定后不修改。

#### PV-79采集结果

- 无profiler主轮：`47,686.390 terminal business/Core messages/s`、`23,843.195 trades/s`，三个business样本为`41,228.580/51,014.559/50,816.031 ops/s`。accepted与terminal闭合，unfinished/rejected/error/timeout/producer-starvation为0，资金、余额/冻结/持仓、订单终态、盘口、snapshot recovery、多settlement在途和有界terminal tombstone门禁通过；相对PV-73为`-3.61%`，通过不低于`44,523.380/s`的吞吐门禁。
- `-prof gc`轮为`45,753.708 terminal business ops/s`、`486.515 MB/s`、`208,976,252 B/invocation`，按16,384 operations/invocation折合`12,754.898 B/terminal business op`，相对PV-73的`15,933 B/op`降低`19.95%`，通过分配门禁；measurement内GC次数和GC时间均为0。
- JFR轮为`47,893.063 terminal business/Core messages/s`、`23,946.531 trades/s`，606,208个PLACE_ORDER样本业务门禁闭合。accepted→terminal延迟p50/p90/p95/p99/p99.9/max为`8.39/8.39/8.39/16.78/33.55/78.90ms`；入口→terminal为`134.22/268.44/268.44/536.87/536.87/404.79ms`，入口段主要体现100,000 offered open-loop下的排队压力。
- JFR记录35秒、约101MiB、842个execution samples、41,426个allocation samples、DataLoss=0。主要可识别CPU热点为`progressPlaceAdmissions` 72 samples、`HashMap.getNode` 45、`LongObjectHashMap.getIfAbsent` 19；主要分配点为`LongObjectHashMap.addKeyValueAtIndex`、`OrderRuntime`、`HashMap.putVal`、`TreeMap.put`和matcher evidence绑定。JFR sampled allocation估算`18,264 B/op`仅作归因，以同参数`-prof gc`的`12,754.898 B/op`作为门禁值。
- 5次ZGC，23个pause合计`0.398ms`，pause p50/p95/p99/max=`0.0106/0.0486/0.1016/0.1016ms`，allocation stall/failure/degeneration为0；heap committed 8GiB、used峰值约5.37GiB。最大到达safepoint为`0.273ms`，最长VM operation为`2.231ms HandshakeAllThreads`。owner同步文件/网络I/O为0，socket I/O与DataLoss为0，交易线程无显著monitor contention。
- NMT committed峰值：GC约`138.4MiB`、Tracing约`35.9MiB`、Metaspace约`31.2MiB`、Code约`28.7MiB`。既有启动期反射/native能力探测产生1,006个异常；未发现交易业务异常。采样结束swap为`638.25MiB`、Pageouts=85,150（采集前81,408），环境门禁未通过；after-GC live set随有界工作集预热增长且未执行长稳，故本轮是吞吐、分配、业务正确性通过的短时部分验证，不声明生产容量或无泄漏。
- 受影响protocol/service测试`111/111`、benchmark支撑测试`10/10`最终复测通过。main/gc/JFR-json/JFR/aggregate SHA-256分别为`a56bad008722ccb55db79b8ec8aa3384084b2ee6238e8021f6ad20107a2b9f9d`、`b6a73437d2cc300edbf0843a211b3261ff4d45764647a5bd93afce7bce9f644e`、`c554da497af48ec34ad6faac192ae88f2878726ad40ad773e29234d448b231c5`、`7c0ec2e86a5cac7bcabf19dde570c493276ee8748733439037f3bfbfad3b60ff`、`34c5fe31778d1473f53139eced8b7ae425d865a0ce4e0e6f82351e8d3488104b`；summary/views位于同一artifact的`saturation-jfr-analysis/`。

### 2026-09-04 10:43:00 +08:00 — `PV-20260904-256-80` — `采集前锁定（P0/P1最终代码，matcher=2诊断）`

#### 采集前锁定

- 使用PV-79完全相同最终Java代码、shaded JAR、机器和业务场景，仅将matching engines从1改为2；对照PV-79 matcher=1 `47,686.390/s`及PV-74 matcher=2 `49,914.257/s`。本轮仅诊断多matcher扩展性和多个settlement并行在途正确性，不替代matcher=1正式口径。
- 固定严格`256 in-flight`、4 Account Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker GTC + 50% taker IOC、100,000 offered；无profiler`fork=1,warmup=3x3s,measurement=3x5s,thread=1`。accepted/terminal、unfinished/backlog、错误、资金、订单终态、盘口、snapshot recovery、多settlement及有界terminal tombstone门禁与PV-79一致。
- HEAD `29c16aef678e0c7865d68f4bcaff73c7603cc980`，除性能记录外dirty diff SHA-256 `6e5f99b59e45df9f15436039095ce79fe461b00d35dfc1538ec1289d824c56c9`，shaded JAR SHA-256 `a8c2cdf9adf1a5dd88222e1758c732081e72230f1ef698c044031a5e6908a3f9`。artifact固定为`target/qualification/20260904T104300Z-p0-p1-final-matcher2-256/`；采集前swap=`638.25MiB`、Pages throttled=0、Pageouts=85,150。不执行GC/JFR/长稳、PostgreSQL、exporter、wallet或外围服务测试；锁定后不修改场景、参数或门禁。

#### PV-80采集结果

- 首轮无profiler诊断为`31,347.529 terminal business/Core messages/s`、`15,673.764 trades/s`，三个business样本为`35,280.678/29,961.420/28,800.489 ops/s`。accepted与terminal闭合，unfinished/rejected/error/timeout/producer-starvation为0，资金、余额/冻结/持仓、订单终态、盘口、snapshot recovery、多settlement和tombstone门禁通过。
- 相对PV-79 matcher=1为`-34.26%`，相对PV-74 matcher=2为`-37.20%`，属于明显扩展性回退，不能按样本波动解释。采集后swap仍为`638.25MiB`、Pageouts由85,150增至85,910、Pages throttled=0。首轮JSON SHA-256 `777254fb77998dccffda78a908dbdf54ee2df223a0a60370e936cb9d50e3c114`；在不改变任何参数和代码的前提下增加一次重复诊断，以区分瞬时环境波动与稳定回退。
- 完全相同参数重复轮为`31,903.890 terminal business/Core messages/s`、`15,951.945 trades/s`，三个样本`35,105.463/30,150.121/30,456.085 ops/s`，全部正确性门禁再次闭合；相对matcher=1为`-33.10%`，确认回退可复现。重复轮结束Pageouts=86,352、Pages throttled=0，JSON SHA-256 `57ce34a751c2420b927b0a930012670d46249b68cac916d2cc234c3aaaa17d8f`。本轮诊断结论为多matcher正确性通过但扩展性失败，继续以独立JFR轮归因。

### 2026-09-04 10:47:00 +08:00 — `PV-20260904-256-81` — `采集前锁定（P0/P1 matcher=2回退JFR归因）`

#### 采集前锁定

- 仅对PV-80已复现的matcher=2回退做JFR归因；代码、JAR、严格`256 in-flight`、4 Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker + 50% taker、100,000 offered及全部正确性门禁不变。JFR轮固定`fork=0,warmup=1x3s,measurement=1x10s`，使用PV-79相同custom JFC与8GiB ZGC/NMT配置。
- 对照PV-79 matcher=1 JFR `47,893.063/s`及PV-80 matcher=2重复结果约`31.3–31.9k/s`；检查owner、两个matcher、4 Lane的CPU/park/锁、allocation site、GC/safepoint、I/O和工作集，重点确认sequence context、settlement changes pool、terminal retention及matcher evidence是否产生跨shard共享竞争。
- 被测HEAD、dirty diff与JAR SHA-256沿用PV-80；artifact固定为`target/qualification/20260904T104700Z-p0-p1-matcher2-jfr-256/`；采集前swap=`638.25MiB`、Pages throttled=0、Pageouts=86,352。本轮为诊断数据，不替代正式验收，不执行外围服务或长稳。

#### PV-81采集结果

- matcher=2 JFR轮为`40,331.416 terminal business/Core messages/s`、`20,165.708 trades/s`，业务门禁闭合。对比matcher=1 JFR，owner/JMH worker execution samples由793增至844；matcher总样本由69增至103（matcher-0=65、matcher-1=38），4个Lane合计由194增至204。没有新的主导锁或单一业务热点，说明额外matcher线程增加CPU/cache竞争，但owner/Lane工作量未被并行消除。
- sampled allocation由matcher=1的`18,264 B/op`升至`19,236 B/op`（约`+5.32%`）；主要site排序基本一致，但`PublishedLaneChanges.ChangeBuffer.clear`、ConcurrentHashMap和LongHashSet在CPU热点中出现，符合更多settlement同时在途扩大sequence-local工作集的特征。没有allocation stall，5次ZGC pause合计`0.283ms`，owner同步I/O为0，锁事件25次/合计`21.14ms`且主要为启动/JMH等待，并非吞吐回退主因。
- 本轮发生一次约`282ms`到达safepoint异常，影响JFR绝对吞吐，但不能解释两次无profiler重复轮的稳定回退。结论：没有发现业务串行等待或锁竞态；现象更接近同机CPU/cache/热状态竞争叠加更大的并行settlement工作集。需紧邻执行同参数matcher=1配对轮，区分机器热降频与代码的matcher扩展性回退。
- 记录约108MiB，DataLoss/socket I/O/allocation stall为0。JFR-json/JFR/aggregate SHA-256分别为`d55f245284aa1d36abfce51a31b130bd519b70e7bc56f416676390aabcccd2c0`、`83263b86f2abb3aaf6ff2b38b2aa4961bf93f956ae51e1056b4855ce5e08108f`、`f5e884ee0d0de4414fd0ce17c4275a7038f9f1da2dcfd4d5aeaeb8f3d5e3b03a`；结束swap=`606.25MiB`、Pageouts=86,511。

### 2026-09-04 10:51:00 +08:00 — `PV-20260904-256-82` — `采集前锁定（P0/P1 matcher=1紧邻配对诊断）`

#### 采集前锁定

- 为排除连续8GiB压测后的机器热状态影响，紧邻PV-80/81执行同代码、同JAR、同机器、同严格`256 in-flight`和同业务场景的matcher=1无profiler配对轮；唯一变量为matching engines从2恢复为1。参数固定`fork=1,warmup=3x3s,measurement=3x5s,thread=1`。
- 对照PV-79早先matcher=1 `47,686.390/s`和PV-80紧邻matcher=2 `31,347.529/31,903.890/s`。若配对matcher=1也显著低于PV-79，则PV-80绝对回退受机器状态污染；matcher=1/2仍按紧邻结果比较。全部业务、资金、snapshot、多settlement及tombstone门禁保持不变。
- 被测HEAD、dirty diff和JAR SHA-256沿用PV-80；artifact固定为`target/qualification/20260904T105100Z-p0-p1-paired-matcher1-256/`；采集前swap=`606.25MiB`、Pages throttled=0、Pageouts=86,511。本轮只作配对诊断，不执行profiler或外围服务测试。

#### PV-82采集结果

- 紧邻matcher=1配对轮为`49,506.463 terminal business/Core messages/s`、`24,753.232 trades/s`，三个business样本为`41,632.770/52,729.501/54,157.119 ops/s`；accepted/terminal、unfinished/error/timeout、资金、订单、盘口、snapshot、多settlement和tombstone门禁全部闭合。
- 结果相对PV-79早先matcher=1为`+3.82%`，说明机器仍能复现约49.5k/s的单matcher水平；与PV-80两个matcher重复轮`31,903.890/s`相比，matcher=2低`35.56%`。因此PV-80回退不是机器热状态导致，而是当前共享owner/Lane终态物化饱和后，第二matcher的CPU/cache竞争大于撮合并行收益。
- 本轮结束swap=`606.25MiB`、Pageouts=86,959、Pages throttled=0；JSON SHA-256 `4330c8fa891b28e54c2c06e95358771425f8c6e6804a573336a01e2506c5aaa3`。P0/P1内存改动按正式matcher=1口径通过；matcher=2正确性通过但扩展性失败，不能宣称双matcher容量提升。

### 2026-09-04 11:04:00 +08:00 — `PV-20260904-256-83` — `采集前锁定（matcher evidence缓存行隔离，matcher=2）`

#### 采集前锁定

- 被测修改：`MatcherEvidenceLedger`由四个跨matcher共享的`AtomicLongArray`改为每matcher独立、缓存行隔离的`ShardState`，使用`VarHandle`维持原有atomic get-and-add、CAS绑定、acquire读取及snapshot restore语义；不改变sequence、matcher prefix或native sequence协议。
- 对照PV-80同代码基础matcher=2重复轮`31,903.890 terminal business ops/s`和PV-82紧邻matcher=1 `49,506.463/s`。本轮matcher=2主轮要求全部正确性门禁闭合；若吞吐无可重复改善则撤销该改动，不保留无证据padding。随后以同参数matcher=1紧邻配对，唯一变量为matching engines数量。
- 固定场景：仅`LINEAR_PERPETUAL`进程内交易链路；4 Account Lane、10,000活跃用户、512 listed/active symbols、每用户最多5持仓/10活动订单、每invocation 16,384 PLACE_ORDER、50% maker GTC + 50% taker IOC、100,000 offered、做市持续运行、open-loop并修正coordinated omission、严格且仅`256 in-flight`。主轮`fork=1,warmup=3x3s,measurement=3x5s,thread=1`。
- accepted business/Core必须分别等于terminal，unfinished/rejected/error/timeout/producer-starvation及期末matcher/Lane/in-flight/context backlog为0；资金守恒、余额/冻结/持仓、订单终态、盘口、snapshot recovery、多settlement在途及terminal tombstone门禁必须通过。
- 环境：Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16、MacBookPro16,1 / Intel Core i9-9880H / 16 logical CPU / 16GiB / macOS 26.7 x86_64；8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、BLOCKING settlement、journal 65,536。matcher定向测试`19/19`通过。
- 被测HEAD `3709fa8b9e3c3828821893ac2d098434f3ebcbc8`，代码diff SHA-256 `e59853230b99ddabbcc6cf2d8581daf3ec25de5dc69ebe86e98a9b50c460aa8b`，shaded JAR SHA-256 `aef7645b1486e9f947debc96c82e34a4349a5ed092b2c136482d257d50d032de`。artifact固定为`target/qualification/20260904T110400Z-matcher-evidence-padding-256/`。
- 不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线；本轮先执行matcher扩展性诊断，不执行GC、长稳或外围服务测试。锁定后不修改场景、参数或门禁。

#### PV-83采集结果

- matcher=2无profiler主轮为`33,314.780 terminal business/Core messages/s`、`16,657.390 trades/s`，三个business样本为`37,255.721/31,876.371/30,812.248 ops/s`。accepted与terminal闭合，unfinished/rejected/error/timeout/producer-starvation为0，资金、余额/冻结/持仓、订单终态、盘口、snapshot recovery、多settlement和tombstone门禁通过。
- 相对PV-80重复轮仅提升`4.42%`，样本离散且连续下降，仍比PV-82 matcher=1低`32.71%`，不能证明缓存行隔离产生可复用收益。按照采集前门禁撤销`MatcherEvidenceLedger`改动，不保留padding/VarHandle复杂度；本轮不继续matcher=1配对或JFR。
- 原始JSON位于锁定artifact目录。该轮为失败诊断，不作最终性能结论；后续归因转向owner终态扫描和changed-index提交链路。

### 2026-09-04 11:12:00 +08:00 — `PV-20260904-256-84` — `采集前锁定（sequence-local funds与终态单遍提交）`

#### 采集前锁定

- 被测修改：owner活动命令及每个`LaneCommandContextRing.Context`改用可复用primitive funds accumulator，matcher settlement直接把各Lane标量posting合并到当前sequence，删除逐settlement `RuntimeFundsDelta.plus`的中间accumulator/List/posting物化；终态订单在`PublishedLaneChanges`提交同一遍直接进入有界retention，删除随后扫描owner全局`changedOrders`的第二遍处理。订单响应引用、publication index、资金守恒、sequence及Aeron提交边界不变。
- 对照PV-82 matcher=1 `49,506.463 terminal business ops/s`、PV-80 matcher=2重复轮`31,903.890/s`及PV-79分配`12,754.898 B/op`。matcher=1不得回归超过10%（不低于`44,555.817/s`），GC分配不得高于PV-79；matcher=2仅作扩展性诊断。全部业务和资金正确性门禁必须闭合。
- 固定场景：仅`LINEAR_PERPETUAL`进程内交易链路；matcher=1主轮及matcher=2诊断轮、4 Account Lane、10,000用户、512 listed/active symbols、每用户最多5持仓/10活动订单、每invocation 16,384 PLACE_ORDER、50% maker GTC + 50% taker IOC、100,000 offered、open-loop并修正coordinated omission、严格且仅`256 in-flight`。主轮均为`fork=1,warmup=3x3s,measurement=3x5s,thread=1`；GC轮`fork=1,warmup=1x3s,measurement=1x5s`；JFR轮仅在代码保留且主轮通过后执行。
- accepted business/Core必须分别等于terminal，unfinished/rejected/error/timeout/producer-starvation及期末matcher/Lane/in-flight/context backlog为0；资金守恒、余额/冻结/持仓、订单终态、盘口、snapshot recovery、多settlement在途及terminal tombstone门禁必须通过。
- 环境沿用PV-83：Oracle GraalVM Java HotSpot 25.0.1、8GiB ZGC、NMT summary、MacBookPro16,1 / Intel Core i9-9880H / 16GiB / macOS 26.7 x86_64。定向测试`25/25`通过。HEAD `3709fa8b9e3c3828821893ac2d098434f3ebcbc8`，排除性能记录的代码diff SHA-256 `5bb262a554f0da9a4bc45a0f54cda75ef97136c7e678477cf9ba042dd38d9681`，shaded JAR SHA-256 `63054c07d3a8b3b92eab92e31a59745c96f3e06d3ff37565a739545f90dd86e3`。artifact固定为`target/qualification/20260904T111200Z-sequence-funds-terminal-256/`。
- 不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线。锁定后不修改场景、参数或门禁。

#### PV-84采集结果

- matcher=1无profiler主轮为`46,075.705 terminal business/Core messages/s`、`23,037.853 trades/s`，三个business样本为`37,712.669/50,512.416/50,002.031 ops/s`；通过`44,555.817/s`吞吐门禁。matcher=2紧邻诊断为`45,834.698 terminal business/Core messages/s`、`22,917.349 trades/s`，三个样本为`38,907.811/48,889.118/49,707.166 ops/s`；相对PV-80重复轮提升`43.66%`，与本轮matcher=1仅差`0.52%`，双matcher反向回退消失。两轮accepted/terminal、unfinished、错误、资金、订单、盘口、snapshot、多settlement及tombstone门禁均闭合。
- 两次matcher=1 `-prof gc`分别为`45,068.363/45,965.811 ops/s`，`211,077,169.714/210,685,833.067 B/invocation`，按16,384 operations折合`12,883.128/12,859.731 B/op`；较PV-79的`12,754.898 B/op`高`1.01%/0.82%`，未通过锁定的严格分配门禁。归因是最终守恒校验仍调用`toDelta()`物化列表；采集后改为primitive accumulator原地逐资产校验，PV-84代码不作为最终版本。
- matcher=1 JFR轮为`42,855.956 terminal business/Core messages/s`、`21,427.978 trades/s`，DataLoss/socket I/O为0。热点为`progressPlaceAdmissions` 62 samples、`HashMap.getNode` 34、`ThreadLocal.get` 16、`TreeMap.put` 13及`ChangeBuffer.forEach` 12；旧的全局`retainPrunedOrders/acceptChangedTerminalOrders`扫描栈为0。5次ZGC，最长pause `0.0409ms`；heap峰值约5.5GiB、GC后1.4GiB；NMT committed峰值GC约138.4MiB、Tracing 37.6MiB、Metaspace 31.2MiB、Code 28.7MiB。JFR约97MiB。
- 受影响定向service测试`102/102`及benchmark支撑测试`12/12`通过。全service旧套件另有42个失败，集中在已删除exporter/终态历史状态旧契约，未作为本轮交易链路验收；benchmark模块一次未带`-am`运行因本地旧service artifact产生`NoSuchMethodError`，带`-am`复跑12/12通过。main matcher1/matcher2、两次GC、JFR-json及JFR SHA-256分别为`c7140be71c793f3d7bd0d05e04f8a2d468e8bffd74d41cb2c6bd922ed6f1f932`、`cd1aac34106b784d8a42887119e6c4be0b32070a84e2ad543be9d39309b1a33d`、`96115184e48d35119e55e447920746ce25ef81b5f396944dd0751cddd2f4394b`、`07c1914863d10e4cd6c2b5b4084eecf12738d3e9c2a501bdbba02df2671fb437`、`68840e018af82984530d5b050a1c80c5edba352bbe3c77f4584d1022e8873576`、`549993fa7498387a680de51e4613cba85b113b90516c0f9fcd980d5c9b176d0d`。

### 2026-09-04 11:22:00 +08:00 — `PV-20260904-256-85` — `采集前锁定（primitive资金守恒最终复测）`

#### 采集前锁定

- 延续PV-84的sequence-local funds和终态单遍提交，唯一生产代码增量是资金守恒直接在primitive accumulator内按资产求和，不再为校验调用`RuntimeFundsDelta.toDelta()`；新增独立多资产守恒/失衡测试。协议、资金语义、snapshot和Aeron边界不变。
- 对照PV-84 matcher=1 `46,075.705/s`、matcher=2 `45,834.698/s`和GC最低`12,859.731 B/op`，并以PV-79 `12,754.898 B/op`为最终分配门禁。固定场景、严格`256 in-flight`、4 Lane、10,000用户、512 symbols、50% maker/50% taker、100,000 offered、JVM/JMH参数及所有正确性门禁沿用PV-84；执行matcher=1主轮、matcher=2诊断、matcher=1 GC和JFR。
- 最终代码受影响资金/service测试`57/57`，此前扩展交易测试`102/102`和benchmark支撑测试`12/12`通过。HEAD `3709fa8b9e3c3828821893ac2d098434f3ebcbc8`，排除性能记录的代码diff SHA-256 `e14317924463294cb92f22d8556e4a9f630c2c76fe1570e9b674d20252fc0ce4`，shaded JAR SHA-256 `e5f02dcc023ee646c74269808630b4adba401b04b4828c586606f178316a843b`。artifact固定为`target/qualification/20260904T112200Z-primitive-funds-final-256/`。
- 不测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data、其他五产品线或长稳。环境异常、DataLoss、业务门禁或分配门禁不通过时只作部分验证；锁定后不修改场景、参数或门禁。

#### PV-85采集结果

- matcher=1无profiler主轮为`50,292.270 terminal business/Core messages/s`、`25,146.135 trades/s`，三个business样本为`44,406.178/53,630.807/52,839.825 ops/s`；相对PV-82为`+1.59%`，通过吞吐门禁。matcher=2紧邻诊断为`47,959.146 terminal business/Core messages/s`、`23,979.573 trades/s`，三个样本为`42,224.670/51,353.330/50,299.437 ops/s`；相对PV-80重复轮提升`50.33%`，仅比本轮matcher=1低`4.64%`。两个matcher的反向扩展回退已消除，但共享owner/Lane提交仍限制其获得正向倍增。
- matcher=1 GC轮为`47,420.893 terminal business ops/s`、`498.661 MB/s`、`207,445,501.333 B/invocation`，按16,384 operations折合`12,661.469 B/op`；较PV-79 `12,754.898 B/op`降低`0.73%`、较PV-84最终诊断降低`1.54%`，通过分配门禁。measurement内GC次数和GC时间为0。
- matcher=1 JFR轮为`50,087.599 terminal business/Core messages/s`、`25,043.800 trades/s`，业务门禁闭合。主要热点为`progressPlaceAdmissions` 40 samples、`HashMap.getNode` 37、`awaitMatchingResult` 22、`ThreadLocal.get` 21、`ConcurrentHashMap.putVal` 16、`ChangeBuffer.clear` 15；旧全局终态二次扫描栈为0，资金`toDelta`不再出现在守恒校验路径。主要分配仍为primitive map扩容、`TreeMap.put`、HashMap节点、Order/Position runtime与结果编码。
- JFR约103MiB，5次ZGC、23个pause，最长pause `0.0451ms`；heap峰值约5.2GiB、GC后约1.5GiB。NMT committed峰值GC约132.6MiB、Tracing 37.4MiB、Metaspace 31.2MiB、Code 28.2MiB；DataLoss、socket I/O和allocation stall为0。878个异常来自既有启动期反射/native能力探测，未发现交易业务异常。短轮未做长稳，不声明无泄漏或生产容量。
- 最终受影响资金/service测试`57/57`，扩展交易测试`102/102`，benchmark真实负载测试`12/12`通过；资金守恒、余额/冻结/持仓、订单终态、盘口、snapshot recovery、多settlement及in-flight门禁全部闭合。main matcher1/matcher2、GC、JFR-json和JFR SHA-256分别为`7b4273a1fb6d2152c921ba74c4ac25a83d18c765225071063c74a426f6f59e80`、`4614f821c01907cbf7f2cb61eb89a65676b0944f86d8d20220dea5d4f52f5323`、`f68a5eff344fee5abd8957ac69e2bb1ebebeb8d20ecc4ddfd7c8f796bf667160`、`c48ed4c2ecbaee5a5c1eebc59efd629b2bb608209a28a5435851576e57746d70`、`d0f0c0333a7467eea77c2d7c05ba16134b7dc5bfc4aec6cc963cc17494ff3522`；summary/views位于同一artifact的`jfr-analysis/`。

### 2026-09-04 11:41:41 +08:00 — `PV-20260904-256-86` — `采集前锁定（sequence-local Lane commit fan-out）`

#### 采集前锁定

- 被测修改：普通命令/批量命令使用的Account Lane sequence提交由owner逐Lane `onLane().await()/awaitConsumed()`改为可复用`LaneCommitEvent`一次fan-out；每个Lane在自己的永久线程推进applied/committed sequence及hash，owner只观察完成mask。提交前使用owner已发布/已派发watermark整体校验，禁止旧sequence造成部分Lane推进；不改变余额、冻结、订单、持仓、资金守恒、snapshot或Aeron提交语义。
- 对照commit为`64297fdf8cb2b0a8b4a547bb4d51faf8fe5aac5d`，对照交易链路为PV-85 matcher=1 `50,292.270 terminal business ops/s`、`12,661.469 B/op`。正式交易主轮不得低于`45,263.043 ops/s`（-10%），accepted/terminal必须闭合，unfinished/rejected/error/timeout及期末backlog为0，资金、余额/冻结/持仓、订单终态、盘口和snapshot recovery必须通过。
- 受影响组件场景为`AccountLaneCommitBenchmark.sequenceLocalFanout`：4 Account Lane、1 owner、每批严格`256 in-flight` sequence，每个sequence覆盖4 Lane及4个已注册用户；JMH `fork=1,warmup=3x3s,measurement=3x5s,thread=1`，同时执行同参数`-prof gc`和JFR归因。该组件分数报告为Lane commit sequences/s，不冒充terminal business ops/s。
- 交易场景保持PV-85：`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`、matcher=1、4 Lane、10,000用户、512 symbols、每invocation 16,384 PLACE_ORDER、50% maker GTC+50% taker IOC、100,000 offered、open-loop/coordinated-omission corrected、严格`256 in-flight`；无profiler`fork=1,warmup=3x3s,measurement=3x5s,thread=1`，JFR `fork=0,warmup=1x3s,measurement=1x10s`。做市持续运行。
- 环境固定Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16、MacBookPro16,1 / Intel Core i9-9880H / 16 logical CPU / 16GiB / macOS 26.7 x86_64；8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、Account Lane BLOCKING wait strategy。采集前swap=`510.25MiB`、hypervisor=0。
- HEAD=`64297fdf8cb2b0a8b4a547bb4d51faf8fe5aac5d`，排除本记录的代码diff SHA-256=`196f7d9d72c4568df235d2f342f99b28c7226741a9e99d90d337e2bc59e5643d`，shaded JAR SHA-256=`d346f9495fa0e9ce39146de772efd29ad3da20f3b2a8c4549a2d3fee00eac387`。artifact固定为`target/qualification/20260904T114141Z-lane-commit-fanout-256/`。
- 不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线。长稳泄漏不在本轮范围；因此即使短轮通过也只形成交易吞吐/分配/热点/正确性的部分验证，不声明生产容量或无泄漏。锁定后不修改标准、场景或参数，失败和无效结果照实追加。

#### PV-86采集结果

- 组件主轮为`220,184.555 Lane commit sequences/s`，固定256 sequence同时在途且每个sequence fan-out到4 Lane；`-prof gc`为`222,300.863/s`、`0.010 B/commit`、测量期GC为0。组件JFR轮为`224,387.453/s`，热点集中在SPSC submit/run、dispatch和completion bitmap原子OR，没有逐Lane `awaitConsumed`栈。
- matcher=1完整交易主轮为`50,152.786 terminal business/Core messages/s`、`25,076.393 trades/s`，三个样本`44,070.591/53,528.153/52,859.615 ops/s`；相对PV-85 `-0.28%`，通过-10%门禁。accepted/terminal闭合，unfinished/rejected/error/timeout/producer-starvation为0，资金、余额/冻结/持仓、订单终态、盘口、snapshot recovery及期末backlog门禁通过。
- 完整交易JFR第一次因non-fork未继承`--add-exports`而在业务启动前失败，原始失败JFR/JSON保留；完全相同场景显式添加JDK模块参数后有效复跑为`49,354.751 terminal business ops/s`、`24,677.375 trades/s`。accepted/terminal和正确性门禁闭合；entry→accepted p50/p90/p95/p99/p99.9/max约`172/314/347/366/369/369ms`，accepted→terminal约`6.69/8.76/9.93/18.8/20.6/20.6ms`，entry→terminal约`175/322/355/372/372/372ms`（代表性稳定invocation，16,384样本，1ns–30s histogram，timeout 30s）。
- 有效JFR约104MiB、DataLoss=0、socket I/O=0；5次ZGC、23个pause合计`0.289ms`，pause p50/p95/p99/max=`0.0101/0.0451/0.0492/0.0492ms`。主要CPU热点为`progressPlaceAdmissions`55 samples、`HashMap.getNode`35、`LongObjectHashMap.getIfAbsent`19、`TreeMap.put`19和digest update 17；没有新增Lane commit等待热点。短轮未做长稳和NMT前后差分，不能声明无泄漏。
- 采集后只增加提交队列整体容量预检、删除已无调用方的`awaitConsumed`方法并同步README；因锁定后生产class bytecode变化，PV-86不作为最终代码artifact验收，按规则新建PV-87复测。PV-86 main组件/GC/组件JFR/交易main/有效交易JFR JSON SHA-256分别为`7f11f52b34c48467438ab69c29e5533c5888410f76e7ddf1acd06ed59949a54b`、`5762555b043bf9b376f2515ccf64adbce9c192f0e36c98ea39dff9c3d55d2031`、`8a67c91310beb19bc32b1713c309c2f3b7b6de30f61def4596205987cbd12c92`、`3754969dc4e9294cb8ab761e6c9193fc878113dbdf05665eebf66bfce5253a36`、`a3669f6d54c8398001327d6755fd2154287accf68ae4b67b777b623b70adcc9b`；组件/有效交易JFR SHA-256为`230472c5c894cc63576f2515ccf64adbce9c192f0e36c98ea39dff9c3d55d2031`、`17c42697ab4972289010be189ec29d794b283437500c49fdc3ae27cb554dcad8`。

### 2026-09-04 11:49:02 +08:00 — `PV-20260904-256-87` — `采集前锁定（最终Lane commit容量预检复测）`

#### 采集前锁定

- 最终增量仅为：fan-out前一次性确认全部目标Lane SPSC有容量，避免部分投递；删除零调用方`awaitConsumed`；同步README。Lane业务、sequence、completion bitmap、资金和snapshot语义与PV-86相同。对照PV-86组件`220,184.555 commits/s`和交易`50,152.786 terminal business ops/s`；组件不得低于`198,166.100/s`，交易不得低于`45,263.043/s`，全部正确性门禁不变。
- 场景、严格`256 in-flight`、4 Lane、matcher=1、用户/symbol、负载模型、JMH/JFR参数、HotSpot 25/ZGC/NMT和不测试范围完全沿用PV-86。执行最终组件main/GC/JFR与完整交易main/JFR；任何代码变化再次终止本轮。
- HEAD=`64297fdf8cb2b0a8b4a547bb4d51faf8fe5aac5d`，排除性能记录的最终代码diff SHA-256=`46e58353fa2dd6e17dc2ead66bba8b4ea2b8833c522f410ed3f583b293de62f4`，最终shaded JAR SHA-256=`86beb82ff392d6ed7f117b1664cd0d527d6dbc7133cfb693324dcc3e5c429420`。artifact固定为`target/qualification/20260904T114902Z-lane-commit-final-256/`；采集前swap=`510.25MiB`。不启动PostgreSQL、exporter或外围服务。

#### PV-87采集结果

- 最终组件主轮为`224,441.719 Lane commit sequences/s`，三个样本`223,466.373/226,258.395/223,600.390`，较PV-86 `+1.93%`并通过门禁。每个sequence覆盖4个Lane、严格256同时在途；`-prof gc`为`224,388.404/s`、`0.009 B/commit`、测量期GC为0。容量预检未产生可见回退，event、primitive用户缓冲及对象池保持稳定复用。
- 最终matcher=1完整交易主轮为`50,293.948 terminal business/Core messages/s`、`25,146.974 trades/s`，三个business样本`44,417.797/52,797.257/53,666.790 ops/s`；相对PV-85约`+0.003%`、相对PV-86 `+0.28%`。accepted/terminal闭合，unfinished/rejected/error/timeout/producer-starvation为0，资金守恒、用户和做市余额/冻结/持仓、订单终态、盘口、snapshot recovery及期末matcher/Lane/context backlog门禁全部通过。
- 完整交易JFR轮为`51,733.459 terminal business/Core messages/s`、`25,866.729 trades/s`。代表性稳定invocation的entry→accepted p50/p90/p95/p99/p99.9/max=`78.9/135/141/148/150/150ms`，accepted→terminal=`4.15/4.70/4.91/7.27/7.39/7.42ms`，entry→terminal=`81.6/139/146/152/152/152ms`；每组16,384样本、1ns–30s histogram、30s timeout、open-loop并修正coordinated omission。offered rate高于可持续吞吐，最大backlog=256、平均232、full-window=81.25%，因此entry段包含预期入口排队，终态未遗留积压。
- 有效JFR约102MiB，DataLoss/socket I/O/allocation stall为0；6次ZGC、28个pause合计`0.381ms`，pause p50/p95/p99/max=`0.0110/0.0401/0.0508/0.0508ms`。CPU热点仍为`progressPlaceAdmissions`44 samples、`HashMap.getNode`26、digest update18、`awaitMatchingResult`17和`TreeMap.put`15；组件JFR热点为SPSC run/submit、dispatch及completion bitmap原子OR，没有`LaneMutationTask.await`或`awaitConsumed`热点。主要分配仍是primitive map扩容、HashMap/TreeMap节点、OrderRuntime、结果编码和matcher event，不包含持续LaneCommitEvent分配。
- HotSpot 25最终受影响service测试`76/76`、benchmark真实交易测试`10/10`通过，构建和`git diff --check`通过。另行尝试的`RuntimeCommitRecoveryTest`仍有`5/5`既有失败，断言已删除的Fact/export patch必须非空；按用户范围不恢复、不测试exporter，该旧契约不计入本轮通过范围。未测试其他五产品线、PostgreSQL、exporter、Kafka、API、WebSocket、market-data、wallet或长稳泄漏；没有NMT前后差分，因此结论为交易链路和Lane commit的短时部分验证，不声明生产容量或无泄漏。
- 最终组件main/GC/JFR JSON、交易main/JFR JSON SHA-256分别为`cfd5f33f0bbbdf06d7c3dd5800056a99f4dc4ef0facfff0a8eba8290ede7c607`、`41fc7ebdcd57131cdfad4b638853e1c5ea6a4b826639a898cf9976d31b6e32c3`、`0e79e416c857b1696e67cfb23f2312ce4d7c905ac9b6bd0bf96adaa9aee78d75`、`e8de4f2b6b1e30a55fba1c55c86dd064717de143c3c817bec67af26980f1f04c`、`cbcd936836e09e4ff2dea787e739d5c01b8ccff3b9e5d5b054ef7f2a18e1034e`；组件/交易JFR SHA-256为`4156f937975be0201ca24d8086bb1a797bbe0a375eebf57dbc45ba16476bb0e1`、`137bf8d0734fc16f3ca34365e8f66c7fd1f7e69b38848d7417c9333d3398bdd0`，summary/views位于同一artifact的`jfr-analysis/`。

### 2026-09-04 12:30:18 +08:00 — `PV-20260904-256-88` — `采集前锁定（owner无等待订单continuation最终验证）`

#### 采集前锁定

- 被测修改：PLACE的预撤单、CANCEL及AMEND/REPLACE命令在owner完成admission后转交对应Account Lane，由Lane读取和修改订单、冻结及索引；matcher settlement在Lane执行预撤单后再提交撮合计划。每个sequence在`PendingMatching`中保存独立cancel/replace continuation，owner仅推进sequence、聚合完成位和提交Aeron边界，不同步等待单个Lane业务结果。
- 对照commit为`9d6192d02de7e3484f1145eab8712a8963f2c697`；完整交易吞吐对照为PV-87 matcher=1 `50,293.948 terminal business ops/s`，通过阈值为不低于`45,264.553/s`（-10%）。新增直接受影响场景为`cancelBurst256`及`amendBurst256`，每次invocation先连续提交且断言恰好`256 in-flight`，再统一drain；分数按`burst/s × 256`换算terminal business/Core messages/s，要求accepted=terminal、两个unfinished为0、期末backlog=0、最大窗口=256、资金/冻结/订单终态及snapshot恢复通过。因无旧版同口径burst基线，这两项只作为本轮受影响路径诊断，不作跨commit提升结论。
- 完整交易场景沿用PV-87：仅`LINEAR_PERPETUAL`、matcher=1、4 Account Lane、10,000活跃用户、512 listed/active symbols、每invocation 16,384 PLACE_ORDER、50% maker GTC+50% taker IOC、100,000 offered、做市持续运行、open-loop并修正coordinated omission、严格且仅`256 in-flight`。无profiler主轮`fork=1,warmup=3x3s,measurement=3x5s,thread=1`；直接场景使用相同参数，并增加`-prof gc`轮`fork=1,warmup=1x3s,measurement=1x5s`；完整交易JFR轮`fork=0,warmup=1x3s,measurement=1x10s`。
- 正确性门禁：accepted business/Core分别等于terminal，unfinished/rejected/error/timeout为0，期末matcher/Lane/context backlog为0；资金守恒、用户和做市余额/冻结/持仓、订单生命周期终态、盘口及snapshot恢复必须通过。每次cancel/amend burst teardown都会从完成态snapshot恢复并比较business state hash。
- 环境固定Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16、MacBookPro16,1 / Intel Core i9-9880H / 16 logical CPU / 16GiB / macOS 26.7 x86_64；8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、Account Lane BLOCKING wait strategy。JMH fork参数包含所需JDK模块开放；JFR使用PV-87同配置。预热后采集，测试间自然冷却；明显swap/pageout增长、CPU throttling或JFR DataLoss时结果无效。
- 采集前定向核心/快照测试`14/14`、新增256窗口测试`1/1`、benchmark真实场景测试`11/11`通过；一次不带`-am`的benchmark模块运行因本地旧service artifact产生`NoSuchMethodError`，已判为无效并由当前源码reactor重跑替代。HEAD=`9d6192d02de7e3484f1145eab8712a8963f2c697`，排除本记录且包含两个新增Lane事件文件的代码diff SHA-256=`1fd227b591b67bf33c3c7194a7dcc5d8ba7240463fada0a89f30500ce06b41b6`，shaded JAR SHA-256=`df1da5f2dfed6c862b653fcd5240e6b57c0d7cdb9c8e9e913615421d49994dce`。artifact固定为`target/qualification/20260904T043018Z-owner-nonblocking-continuations-256/`；采集前swap=`446.25MiB`。
- 不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线；不执行长稳泄漏测试，因此通过时也只形成交易链路短时部分验证，不声明生产容量或无泄漏。锁定后不修改代码、标准、场景或参数，失败和无效轮次照实追加。

#### PV-88采集结果

- cancel直接场景完成，三个样本折算为`47,881.696/64,261.476/64,270.959 terminal business ops/s`，平均`58,804.711/s`；accepted/terminal business及Core messages闭合，unfinished/rejected/error/timeout为0，严格256窗口和snapshot恢复门禁通过。
- amend在warmup中失败：第一次剩余223条未终态，独立重复诊断剩余245条未终态。线程转储显示owner/JMH worker持续位于`progressPlaceAdmissions`，matcher及4个Account Lane均空闲；说明不是Lane业务耗时或锁等待，而是非PLACE submission head未被继续提交。根因是shard ready推进循环遇到`placeAdmission == null`直接退出，AMEND在前序窗口释放后可永久留在submission队列。
- 本轮失败，不执行GC、完整交易主轮或JFR，不作性能验收。原始JSON保留于锁定artifact；代码随后增加非PLACE submission head推进并扩展为16轮cancel/amend各256窗口回归，按规则另建轮次复测。

### 2026-09-04 12:40:55 +08:00 — `PV-20260904-256-89` — `采集前锁定（非PLACE submission head推进最终复测）`

#### 采集前锁定

- 延续PV-88的owner无等待订单continuation实现，唯一生产代码增量是shard ready推进器遇到无Place admission的submission head时直接调用`submitMatching`并继续推进，消除matcher和Lane均空闲时AMEND永久滞留。新增回归把cancel/amend各256窗口连续执行16轮，每轮校验终态、最大窗口和完成态snapshot恢复。
- 对照和门禁沿用PV-88：完整交易对照PV-87 `50,293.948 terminal business ops/s`，阈值`45,264.553/s`；直接cancel/amend场景按`burst/s × 256`报告terminal business/Core messages/s，必须accepted=terminal、unfinished/rejected/error/timeout=0、最大窗口恰为256、期末backlog=0并通过资金/订单/snapshot门禁。cancel还对照PV-88有效结果`58,804.711/s`，不得回退超过10%；amend因PV-88超时无有效性能基线，只作绝对值和正确性诊断。
- 场景与参数保持不变：`LINEAR_PERPETUAL`、matcher=1、4 Account Lane；直接场景每invocation 256个独立用户及256条CANCEL或AMEND，先全部accepted再drain。完整交易为10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker GTC+50% taker IOC、100,000 offered、open-loop/coordinated-omission corrected、做市运行、严格256 in-flight。主轮`fork=1,warmup=3x3s,measurement=3x5s,thread=1`；直接场景GC轮`fork=1,warmup=1x3s,measurement=1x5s`；完整交易JFR轮`fork=0,warmup=1x3s,measurement=1x10s`。
- JDK/JVM、机器、8GiB ZGC、NMT、wait strategy、正确性和数据有效性条件、不测试范围全部沿用PV-88。修复后16轮直接回归`1/1`、定向核心/快照`14/14`、benchmark全部真实场景`11/11`通过；构建和`git diff --check`通过。
- HEAD=`9d6192d02de7e3484f1145eab8712a8963f2c697`，排除性能记录并包含新增Lane事件文件的最终代码diff SHA-256=`0ebf531d5f8a88f9fdc2d272661a866f84fc98857a6db46349d2c29195d13fe1`，shaded JAR SHA-256=`d4de040e9857c7d3be9ad52453ae51a2138e2e8b1c9c7c519ff54dbcb5f322bc`。artifact固定为`target/qualification/20260904T044055Z-owner-nonblocking-continuations-final-256/`；采集前swap=`446.25MiB`。锁定后不修改代码、场景、参数或门禁；PostgreSQL、exporter及外围服务仍不启动、不测试。

#### PV-89采集结果

- cancel再次完成，三个样本折算为`51,880.731/65,035.705/66,014.676 terminal business ops/s`，平均`60,977.037/s`；业务/Core accepted与terminal闭合，unfinished/rejected/error/timeout为0，较PV-88 cancel提高`3.69%`。
- amend仍在warmup失败，剩余143条未终态。新增分支只在`placeAdmissionShardReady=true`时生效；线程状态仍显示提示位被清零后存在非PLACE submission head，而matcher/Lane空闲。由此确认ready bit只能作为edge提示，不能控制是否检查实际队列状态。
- 本轮失败并终止后续GC、完整交易及JFR。代码随后把每个shard改为直接检查真实submission head的level-trigger推进，异步Place admission未完成时清提示并退出；按规则另建最终轮次。

### 2026-09-04 12:46:48 +08:00 — `PV-20260904-256-90` — `采集前锁定（level-trigger submission最终验证）`

#### 采集前锁定

- 最终修复为每次推进都直接读取各matcher shard的真实submission head，不再以`placeAdmissionShardReady`提示位作为循环条件；非PLACE head立即提交，PLACE head仅在admission acquire-complete后提交。提示位保留作通知但不参与正确性判断。诊断字段已删除，最终生产代码不含临时观测逻辑。
- 业务场景、严格256 in-flight、matcher=1、4 Lane、用户/symbol/做市状态、正确性门禁、完整交易阈值`45,264.553 terminal business ops/s`、JDK/JVM/机器、8GiB ZGC/NMT及不测试范围全部沿用PV-89。直接cancel/amend主轮仍为`fork=1,warmup=3x3s,measurement=3x5s`；本轮优先完成两者主轮及受影响AMEND JFR，完整交易主轮随后执行。
- level-trigger最终代码的cancel/amend各256窗口连续64轮测试通过；独立短JMH诊断也完成且accepted/terminal闭合。构建与`git diff --check`通过。HEAD=`9d6192d02de7e3484f1145eab8712a8963f2c697`，排除性能记录的最终代码diff SHA-256=`c233d8fd422e007997ecffca69a19b81bc52d614c087f8fb89a27bab698430cf`，shaded JAR SHA-256=`12a0a3b340d5ed22070dd64891297862b880d8ff50fc20fba0b90caf890e5e1d`。artifact固定为`target/qualification/20260904T044648Z-owner-level-trigger-final-256/`，采集前swap=`446.25MiB`；锁定后不修改代码或参数。

#### PV-90采集结果

- cancel主轮再次完成，平均`56,589.209 terminal business/Core messages/s`，accepted/terminal闭合，unfinished/rejected/error/timeout为0；严格256窗口和完成态snapshot恢复通过。
- amend第一测量样本为`13,304.406 terminal business/Core messages/s`且闭合，但第二样本再次超时，sequence 555后仍有216条未终态，因此整项无效。该结果证明前两轮对submission ready提示位的修补不是根因，相关`CoreProbeState`尝试已撤销，不保留无证据复杂度。
- 因直接受影响AMEND场景失败，本轮立即终止，不执行GC、完整交易或JFR，也不宣称owner无等待目标验收完成。功能测试虽通过，但不足以覆盖持续JIT负载下出现的推进停滞；原始JSON保留在锁定artifact。PostgreSQL和exporter全程未启动、未测试。
- PV-88/PV-89/PV-90原始JSON SHA-256分别为`147b62d206c8d79f171ebfb30ea0eadfbddf39ac7d331ffe548c1feeeda08360`、`c4a730049e2bd37ec2f006194ff663a2b189fe85d0b1bb11cb417ff6780fc719`、`f42fcaec9c9b67e8132517406e0f32e332532b7e7360ce1a08377f1705ac3294`；采集结束swap仍为`446.25MiB`，未发生swap增长。

### 2026-09-04 13:00:51 +08:00 — `PV-20260904-256-91` — `采集前锁定（Lane continuation回收竞态最终修复）`

#### 采集前锁定

- 根因修复：`LaneReplaceEvent`及具有同构风险的`LaneCancelEvent`在发布completion release位之前，把runtime、laneId和coreSequence复制到Lane线程局部变量；completion发布后只使用局部值发送ready通知，不再读取可能被owner立即`clear()`的池化事件字段。该改动不增加等待、锁、容器或业务阶段。
- 故障证据为AMEND卡住时matcher为空、目标settlement mask=`0/4`、Lane 2 worker因`LaneReplaceEvent.execute:93`对已清空runtime解引用而NPE，队列余2；因此此前表现为owner等待，实际是池化事件完成位与最后一次字段读取之间的回收竞态。临时诊断接口已全部删除。
- 性能标准、完整交易阈值`45,264.553 terminal business ops/s`、固定且仅256 in-flight、matcher=1、4 Lane、10,000用户、512 symbols、50% maker/50% taker、100,000 offered、JMH主轮`3x3s + 3x5s`、GC轮`1x3s + 1x5s`、JFR轮`1x3s + 1x10s`、HotSpot 25/8GiB ZGC/NMT和正确性门禁沿用PV-90。直接cancel/amend各256 burst必须全部终态、无错误、最大窗口256并通过snapshot恢复。
- 最终代码已通过cancel/amend各256窗口连续64轮测试；构建和`git diff --check`通过。HEAD=`9d6192d02de7e3484f1145eab8712a8963f2c697`，排除性能记录的代码diff SHA-256=`ea3c89ea179712bd1b7a36567d820659806100ed85dc9ffc0c631ab6f8e1d803`，shaded JAR SHA-256=`a8d1539b762395f74c8d2e9fa5928b3897fb7e6739668f579a570e5c909cfa7d`。artifact固定为`target/qualification/20260904T050051Z-lane-continuation-race-final-256/`，采集前swap=`446.25MiB`。不启动或测试PostgreSQL、exporter及外围服务；锁定后不修改代码、场景或参数。

#### PV-91采集结果

- 直接AMEND主轮三个样本为`18,388.223/24,527.717/25,375.965 terminal business/Core messages/s`，平均`22,763.635/s`；CANCEL三个样本为`66,064.757/66,908.160/67,756.259/s`，平均`66,909.725/s`。两者accepted/terminal均闭合，unfinished/rejected/error/timeout为0，每次最大窗口严格为256，期末backlog为0，订单终态、资金及完成态snapshot恢复通过。AMEND不再出现Lane worker failure或超时。
- 直接场景`-prof gc`轮：AMEND `10,967.312 terminal ops/s`、`783.930 MB/s`、`59,163,144 B/burst`，折合`231,106 B/business op`；CANCEL `27,649.892/s`、`984.141 MB/s`、`52,875,495.273 B/burst`，折合`206,545 B/op`。数值包含每个JMH invocation的snapshot restore及teardown snapshot验证分配，只用于本直接场景归因，不能替代PV-85完整交易`12,661.469 B/op`基线；测量期分别发生12次/201ms和6次/94ms GC。
- matcher=1完整交易主轮为`49,659.886 terminal business/Core messages/s`、`24,829.943 trades/s`，三个business样本`44,068.449/52,271.281/52,639.928 ops/s`；相对PV-87 `50,293.948/s`为`-1.26%`，通过`45,264.553/s`门禁。accepted/terminal闭合，unfinished/rejected/error/timeout/producer-starvation为0，资金、余额/冻结/持仓、订单终态、盘口、snapshot recovery及期末matcher/Lane/context backlog门禁通过。
- AMEND JFR轮为`12,800.431 terminal business/Core messages/s`，业务门禁闭合。记录15秒、约38MiB，227个execution samples、31,297个allocation samples、852,303个ThreadPark；主要CPU栈为`awaitMatchingResult`15 samples、`progressPlaceAdmissions`9、state hash mix 7、owner断言5和ThreadLocal lookup 5。5次ZGC、23个pause，最长pause`0.027346ms`；JavaMonitorEnter 14次，ExceptionStatistics、SocketRead/Write及DataLoss均为0。ThreadPark主要来自BLOCKING Lane及JMH生命周期，没有再次出现Lane worker NPE。
- 最终复测：定向核心/资金/snapshot测试`14/14`、benchmark真实场景测试`11/11`（其中continuation测试连续64轮）通过，构建和`git diff --check`通过。未执行其他五产品线、PostgreSQL、exporter、Kafka、API、WebSocket、market-data、wallet或长稳泄漏，因此结论为本次交易链路的短时部分验证，不声明生产容量或无泄漏。
- main/GC/trading-main/JFR-json/JFR SHA-256分别为`82a92f2eaa2dae141a9380573187ffdbbc81b4fc76231c53718250639ec4c75f`、`e6effdf09f6838c86ead0f6b23303b23afed57c2961524ba2054d8fd1f4a4b37`、`1d4f82fbb541e2ab01237c162d619c5034cae2663442bea69dd800fc4ec042e8`、`2d059fd974bcda7bf035455e33ba21223cdea0611855255b3560c05bc465a885`、`5945d16c7b1dec549175c80bbd71ff04c698b751feffc373d22508c0a51333c0`；采集结束swap仍为`446.25MiB`。

### 2026-09-04 13:27:41 +08:00 — `PV-20260904-256-92` — `采集前锁定（10分钟单matcher交易链路长压）`

#### 采集前锁定

- 本轮不修改生产代码，验证commit `69675744cfb723deecbb6e9af252d055dc561555`在持续饱和负载下的终态吞吐、尾延迟、资金正确性、积压收敛、GC/heap/native趋势及snapshot恢复。对照为PV-91 matcher=1完整交易`49,659.886 terminal business ops/s`；长压吞吐门禁沿用最终正式阈值`45,264.553/s`。任何业务门禁失败、JFR DataLoss、明显swap增长、page throttling或期末积压不为零均使本轮无效。
- 固定场景为`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`：仅`LINEAR_PERPETUAL`、1 matching engine、4 Account Lane、10,000活跃用户、512 listed/active symbols、每用户最多5持仓/10活动订单、每invocation 16,384个PLACE_ORDER、50% maker GTC + 50% taker IOC、做市持续运行、100,000 offered、open-loop且修正coordinated omission；最大且仅允许`256 in-flight`，不采集其他档位。
- JMH固定`thread=1,fork=0,warmup=3x3s,measurement=1x600s`；进程固定8GiB heap、ZGC、AlwaysPreTouch、DisableExplicitGC、Account Lane BLOCKING、commit journal 65,536、NMT summary。整段进程使用显式`owner-commit-profile.jfc`采集JFR，并记录GC/safepoint日志；每60秒采集NMT summary.diff和系统状态。JFR属于带profiler的10分钟长稳诊断，绝对吞吐不与无profiler短轮直接等同。
- 正确性门禁：`acceptedBusinessOperations == terminalBusinessOperations`、accepted/terminal Core messages相等、两个unfinished为0，rejected/error/timeout/producer-starvation为0，期末matcher/Lane/context backlog为0；用户和做市余额、冻结、持仓、订单终态、盘口、资金守恒、严格256窗口和完成态snapshot恢复全部通过。报告terminal business ops/s、Core messages/s、trades/s及入口→accepted、accepted→terminal、入口→terminal的p50/p90/p95/p99/p99.9/max。
- 环境：Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16、MacBookPro16,1 / Intel Core i9-9880H（8物理/16逻辑CPU）/ 16GiB、macOS 26.7 x86_64；采集前swap=`414.25MiB`、Pages throttled=0。shaded JAR SHA-256=`f75fd40561af8dfac9ca88e37ab4d4ddd32f054d8d531e2a0d9cf0b41104c56d`，JFR配置SHA-256=`dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`；artifact固定为`target/qualification/20260904T052741Z-trading-soak-600s-256/`。
- 不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线。本轮只有10分钟，若通过只能形成中等时长稳定性证据，不能替代40分钟正式长稳或据此单独声明无泄漏。锁定后不修改代码、门禁、场景或参数，失败与异常照实记录。

#### PV-92采集结果

- 本轮未完成：进程约运行184秒、测量阶段约61秒时，`saturatedMatchingWorkload`抛出`continuous feeder made no progress within 30 seconds`。故600秒长压未跑满，JMH JSON为空，不产生有效吞吐或长稳结论；失败时严格窗口仍为256，已终态8,376个business operations，最大matcher backlog=256、平均backlog=232.902，说明停在饱和窗口内。
- 失败前已完成样本的入口→accepted p50/p90/p95/p99/p99.9/max为`40.340/69.989/74.731/78.608/78.628/78.642ms`，accepted→terminal为`4.254/4.747/5.434/8.058/8.698/8.719ms`，入口→terminal为`44.071/74.612/78.718/85.500/86.728/86.742ms`；这些仅为失败前诊断样本，不能作为验收延迟。
- GC日志显示ZGC allocation stall为0，主要major concurrent cycle最长`5.114s`，对应STW phase最大约`0.050ms`；保留JFR窗口内safepoint pause最大`0.072ms`、到达safepoint最大`3.305ms`，因此没有证据表明30秒无进展由GC或safepoint造成。120秒NMT相对启动基线committed增加约`129.47MiB`，主要为Thread arena约64MiB、GC约53.75MiB、Tracing约12.44MiB；测试未跑满且没有多轮GC后趋势，不能用于泄漏判断。
- 本轮custom JFC对`ThreadPark`零阈值采集，保留的最后约48秒内产生约750万park事件；未显式指定JFR容量导致250MiB环形文件仅保留尾部，CPU/execution sample覆盖不足。该采样开销和覆盖缺陷使本轮无法区分采样扰动与真实活性故障；下一轮使用JDK `profile`配置和2GiB容量，业务参数不变，单独重跑。
- artifact为`target/qualification/20260904T052741Z-trading-soak-600s-256/`；JFR/DataLoss分别约249MiB/0。log、JFR、GC、NMT-120s、JMH JSON SHA-256分别为`55a5d2dee0ab95c4d2f88994cc0d76f410761e7dc6ee45d2bb8df1da292d2938`、`9ccb54514c18a72fd64ea02e630e971931eace93a92712641d432b398fd28f03`、`a35b94c36e6136db9de26f55c985f23777f00e4afb29a4933d2eb233f691b1c2`、`bb8c7145e06f81cdce6da62600d2c34066e23c645b351867773c26d29c24eb25`、`f8c412e258a5b323f4af772515c881e6038fea23f35ab10111123160b7e70536`。PostgreSQL、exporter及外围服务未启动、未测试。

### 2026-09-04 13:35:41 +08:00 — `PV-20260904-256-93` — `采集前锁定（10分钟单matcher低扰动JFR重跑）`

#### 采集前锁定

- 本轮只排除PV-92高频park采样的扰动，不修改生产代码、JAR或业务场景。继续验证commit `69675744cfb723deecbb6e9af252d055dc561555`，对照PV-91 matcher=1完整交易`49,659.886 terminal business ops/s`，通过阈值仍为`45,264.553/s`；失败、超时、业务门禁不闭合或600秒未跑满即判失败。
- 场景完全沿用PV-92：仅`LINEAR_PERPETUAL`、matcher=1、4 Account Lane、10,000用户、512 listed/active symbols、每用户最多5持仓/10活动订单、每invocation 16,384 PLACE_ORDER、50% maker GTC + 50% taker IOC、100,000 offered、open-loop/coordinated-omission corrected、做市持续运行，严格且仅`256 in-flight`。
- JMH固定`thread=1,fork=0,warmup=3x3s,measurement=1x600s,timeout=12m`；JVM仍为HotSpot 25.0.1、8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、Lane BLOCKING、journal 65,536。唯一采集变更是JFR由custom零阈值park配置改为JDK 25内置`profile`配置，并显式`maxsize=2g`保留整段；继续保存GC/safepoint、NMT和系统状态。带JFR绝对吞吐仅与本轮阈值比较，不与无profiler短轮直接等同。
- 正确性和有效性门禁完全沿用PV-92：accepted/terminal business及Core messages闭合、unfinished/rejected/error/timeout/producer-starvation为0、期末全部backlog清零，并验证资金、余额/冻结/持仓、订单终态、盘口、严格256窗口和完成态snapshot恢复。报告吞吐、三段完整分位延迟、GC/heap/native、线程/锁/park、safepoint/JIT/I/O/异常和系统状态。
- 环境与JAR沿用PV-92；重跑前swap=`414.25MiB`、Pages throttled=0、Pageouts=101,628。artifact固定为`target/qualification/20260904T053541Z-trading-soak-600s-profile-256/`。不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线；锁定后不修改代码、场景、参数或门禁，异常照实记录。

#### PV-93采集结果

- 低扰动JFR重跑仍未完成：进程运行约184秒，测量阶段再次在一个invocation等待约60秒后抛出`continuous feeder made no progress within 30 seconds`，JMH JSON为空。两种JFR配置均在近似位置复现，排除PV-92高频park采样是根因；600秒长压失败，不报告有效吞吐、尾延迟或无泄漏结论。
- 失败前共有306个完整workload事件，每个事件16,384个terminal business/Core messages且正确性闭合；最后一个失败事件只完成8,376/16,384 business operations，最大backlog=256、平均backlog=232.902、full-window=81.95%，无producer starvation。失败前最后一组诊断延迟：入口→accepted p50/p90/p95/p99/p99.9/max=`40.644/69.097/73.258/76.847/76.852/76.857ms`，accepted→terminal=`4.261/4.612/5.052/8.013/8.744/8.768ms`，入口→terminal=`44.013/73.365/77.033/83.835/84.737/84.762ms`；因该invocation未完成，这些数值不作验收。
- 181秒JFR完整保留且DataLoss=0。最后一份线程转储显示JMH/owner线程停在`CoreProbeState.awaitAnyMatchingCommitReady -> commitReadyMatching`，同时`core-matcher-0`与4个Account Lane全部处于park等待，说明已有256个在途窗口中存在没有任何worker继续负责推进的pending sequence；故障边界是matching completion ready/sequence推进活性，不是owner计算热点或外部I/O阻塞。
- 共22次ZGC，heap GC前峰值约5.9GiB、GC后最低约52MiB/后期约3.8GiB；allocation stall为0，最长concurrent major cycle约`5.094s`，STW phase最大`0.055ms`。180秒NMT相对启动基线committed增加约`213.53MiB`，主要为GC约151.58MiB、Code约20.59MiB、Tracing约13.81MiB和Metaspace约21.53MiB；运行时间不足且故障后停止分配，不能形成泄漏趋势证据。swap始终`414.25MiB`、Pages throttled=0，Pageouts由101,628增至103,223。
- CPU热点仍以`HashMap.getNode`、SHA digest、`ThreadLocal.get`、`ConcurrentHashMap.putVal`、`LongObjectHashMap.getIfAbsent`、`progressPlaceAdmissions`及changed-buffer处理为主；未出现同步数据库、Kafka、socket或文件I/O阻塞。JFR约3.8MiB，ExecutionSample=7,642、ThreadPark=299、ThreadDump=3，采样覆盖有效。
- artifact为`target/qualification/20260904T053541Z-trading-soak-600s-profile-256/`。log/JFR/GC/JMH JSON/thread dumps/workload events SHA-256分别为`d730551552108cf4d2242094929feff4ed9cdd928f7b41e9f7ef34f07ceeb123`、`7f344ee42eeb5ce9f1a74ad9003120bd585ebafb5b0138fcb8d7f4e1c4829ec5`、`45a3c21cea3aa1cd55c4597508fa3fdce35bb125476cefca016af11b0a58abeb`、`f8c412e258a5b323f4af772515c881e6038fea23f35ab10111123160b7e70536`、`44b65d02c9fe5d68723c2db10cc6d6d16c6e3fb68897b2aaac26397da399dccf`、`60752c9074c590470f22d6dca574901be4be0a5d8bb924c95dd2439057f91c45`。本轮未修改生产代码；PostgreSQL、exporter及外围服务未启动、未测试。
- 后续只读诊断复现到卡死head为sequence `5,121,024`：`PLACE/admission=none/matchingSubmitted=false/pendingReady=false/settlement=none`，pending size=256、submission head=0、matcher及4个Lane队列深度全为0。JFR显示该时刻集中抛出`STALE_MARK_PRICE`；基准逻辑时间为`BASE_EPOCH + correlationId/1024`，sequence 5,121,024对应约5,001ms，刚好超过Core的5,000ms mark freshness上限。该拒绝经`recordRejectedMatching`进入已有pending ring后调用`rejectMatching`和`completeSubmission`，但遗漏`signalPendingMatchingReady(sequence)`；因此拒绝结果已存在却永远不能被owner轮询提交。影响范围是所有“已有matching在途时产生的同步matching业务拒绝”，并非仅长压或mark price场景。临时诊断代码已撤销。

### 2026-09-04 14:00:45 +08:00 — `PV-20260904-256-94` — `采集前锁定（拒绝ready修复后10分钟长压）`

#### 采集前锁定

- 被测修复：`recordRejectedMatching`在已有matching在途时写入sequence-local rejection并移出submission队列后，同步调用`signalPendingMatchingReady(sequence)`，保证拒绝按sequence进入owner终态提交；saturation基准每4,000ms逻辑时间以当前价格和递增price sequence刷新全部活跃symbol的mark，保持生产Core的5,000ms freshness规则不变，刷新发生在每个JMH invocation业务计数基线之前。
- 对照PV-92/PV-93在约184秒稳定复现的`continuous feeder made no progress`；吞吐对照PV-91 matcher=1完整交易`49,659.886 terminal business ops/s`，通过阈值仍锁定为`45,264.553/s`。600秒未跑满、任何同步拒绝、业务门禁不闭合、JFR DataLoss、明显swap增长或Pages throttled非0均判失败。
- 场景固定：仅`LINEAR_PERPETUAL`、matcher=1、4 Account Lane、10,000用户、512 listed/active symbols、每用户最多5持仓/10活动订单、每invocation 16,384 PLACE_ORDER、50% maker GTC + 50% taker IOC、100,000 offered、open-loop/coordinated-omission corrected、做市及mark刷新持续运行，严格且仅`256 in-flight`。
- JMH固定`thread=1,fork=0,warmup=3x3s,measurement=1x600s,timeout=12m`；HotSpot 25.0.1、8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、Lane BLOCKING、journal 65,536。JFR使用JDK 25内置`profile`配置、`maxsize=2g`和dumponexit，保存整段JFR、GC/safepoint、每60秒NMT与系统状态。带JFR吞吐直接使用锁定阈值，但不与无profiler短轮作同开销比较。
- 正确性门禁：accepted/terminal business operations及Core messages相等，两个unfinished、rejected、error、timeout、producer-starvation均为0，期末matcher/Lane/context/backlog全部清零；资金守恒、余额/冻结/持仓、订单生命周期终态、盘口、严格256窗口和完成态snapshot恢复必须通过。报告terminal business ops/s、Core messages/s、trades/s及三段延迟p50/p90/p95/p99/p99.9/max。
- 修复回归：同步拒绝ready精确测试`1/1`、真实saturation支撑测试`11/11`通过；全`CoreMatchingStateTest`的其余22个既有失败属于已删除exporter/终态历史契约，不作为本次新增失败。HEAD=`69675744cfb723deecbb6e9af252d055dc561555`，生产/测试代码diff SHA-256=`89224b57e660e0b1e608b67a25690cec17175830143c6d133f7c15a3c7dc854d`，shaded JAR SHA-256=`0532c5dcc5d773ecaba42c834bc20695f304680d5d64ee19846b3630ac5d8ee7`。
- 环境沿用PV-93；采集前swap=`382.25MiB`、Pages throttled=0、Pageouts=103,558。artifact固定为`target/qualification/20260904T060045Z-rejection-ready-soak-600s-256/`。不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线；锁定后不修改代码、场景、参数或门禁，异常照实记录。

#### PV-94采集结果

- 600秒测量完整结束，总运行时间10分30秒；PV-92/PV-93约184秒必现的无进展不再复现，跨过5,000ms mark freshness边界后仍持续推进。同步拒绝ready回归`1/1`、真实saturation支撑测试`11/11`通过；最终资金守恒、用户/做市余额与冻结、持仓、订单生命周期终态、盘口、严格256窗口、期末matcher/Lane/context backlog清零及完成态snapshot恢复全部通过。未出现`STALE_MARK_PRICE`、`CoreStateRejectedException`或continuous-feeder异常，说明本轮活性修复有效。
- 带JFR长压吞吐为`29,664.809 terminal business ops/s`、`29,664.809 terminal Core messages/s`、`14,832.405 trades/s`；accepted business/Core分别与terminal相等，两个unfinished及rejected/error/timeout/producer-starvation均为0。结果低于锁定门槛`45,264.553/s`约`34.46%`，因此性能门禁失败。最大matching backlog为256，按measurement workload事件聚合平均backlog约231.90、full-window约81.19%。
- measurement内1,087个完整workload事件、17,809,408个延迟样本的对数直方图聚合结果：入口→accepted p50/p90/p95/p99/p99.9/max约`134.218/536.871/536.871/1,073.742/4,294.967/37,108.857ms`；accepted→terminal约`8.389/16.777/16.777/33.554/134.218/36,137.431ms`；入口→terminal约`134.218/536.871/536.871/1,073.742/4,294.967/37,120.432ms`。直方图桶为2倍幂，分位数精度受桶宽限制；最长尾延迟与ZGC allocation stall重合。
- 长稳内heap/live set持续增长：后半段多轮GC后占用由约5.0GiB升至约7.2–7.8GiB，进程退出时ZHeap used=`7,580MiB/8,192MiB`。共186次GC（ZYoung事件188、ZOld事件19），发生4次allocation stall，平均约`2,974ms`、最长`26,515.680ms`；major concurrent collection最长`35,430.848ms`。STW pause仍低，young pause最大约`1.417ms`，但并发回收无法跟上仍被引用的工作集增长。GC日志平均allocation rate约`358MB/s`，按业务吞吐粗折合约`12.1KB/business op`；该换算不是独立`-prof gc`结果。
- `OldObjectSample`和分配栈显示测试末段仍持续发生`LongObjectHashMap/LongLongHashMap.rehashAndGrow`，主要来源为`AccountLaneState.putOrder`、`TradingRuntimeState.putClientOrderIndex/collectPlaceAdmission/indexPendingReservation`和`ActiveOrderIndex.applySnapshot`；这说明终态订单及相关索引仍随总命令数增长，而不是仅有短生命周期临时分配。CPU热点同样以`HashMap.getNode`、`ConcurrentHashMap.putVal`、`RuntimeIdentityRegistry.trackAllocation`、SHA digest、`ThreadLocal.get`、primitive map probe/rehash、`ChangeBuffer`扫描及terminal digest为主。ExecutionSample按线程约为owner/JMH 40,074、4 Lane合计4,130、matcher 520，长期瓶颈仍主要位于owner及状态/索引提交链路。
- NMT committed相对启动基线在60/120/180/240/300/360/420/480/540秒分别增加约`178.6/209.9/284.0/336.1/337.3/418.2/396.5/422.9/460.0MiB`；DirectBuffer采样的count/capacity/memoryUsed均为0。系统swap由`382.25MiB`明显增至测试后约`1.6–1.9GiB`，Pageouts由103,558增至116,366，Pages throttled始终为0；swap增长使本轮按预锁标准无效，吞吐不能作为稳定容量结论。
- JFR时长约631秒、8.4MiB，DataLoss=0；ObjectAllocationSample=164,284、ExecutionSample=44,734、ThreadPark=3,960、SafepointBegin=632、Compilation=30、JavaMonitorEnter=1、FileRead=7，SocketRead/Write和FileWrite均为0。JavaExceptionThrow=345，但交易拒绝异常为0；未发现owner同步数据库、Kafka、socket或文件写入。短时停顿不是本轮主因，失败原因是持续状态保留导致的heap/swap压力、allocation stall和随时间下降的吞吐。
- artifact为`target/qualification/20260904T060045Z-rejection-ready-soak-600s-256/`。log/JFR/GC/JMH JSON/workload events/JFR summary/hot methods/allocation sites/java exceptions SHA-256分别为`77f67b55963ca9ea6c3bb3ab41865d17dd0c8d0a048c73a5c7b22198107c33f1`、`bc8f4f85305e819e43f63a9674fb1798f7fcc511ad4026bb9c1416d06a47c428`、`8364ea2adbed8bb3179ef9d9a423a52c526c28754f7704a364bbe6de61f0a8f9`、`d0ba4031a462f0401a6c2f8c4e342fbeacbfdc0a3320605d76b6a31a0076fe77`、`c8481ddd65c062e323784ae8f9dd00914aaa4a4c0c3c535373524e93f46a37b1`、`9cdf7d0a7480c950922a207b1f93a386c45e1772ca5adb7837414b2a217b9e98`、`5ccff08526d4aa330a60cc677f6fdc2ede0f1dc1f2a9d48626d733a7f6b7f299`、`86f03a366019b54ea40b3ed97b7d38eb4f513f0792d54e5d381bf39ad3ea78f5`、`869d8544e415edc46d5577f540bb87640753f8fe655f1c64176a13a848b6c656`。本轮结论为“拒绝sequence活性修复通过，10分钟业务正确性通过，但吞吐、系统内存和长稳泄漏门禁失败”；PostgreSQL、exporter及外围服务未启动、未测试。

### 2026-09-04 14:36:29 +08:00 — `PV-20260904-256-95` — `采集前锁定（终态client identity彻底回收）`

#### 采集前锁定

- 被测修改：Account Lane在终态订单已释放全部reservation后继续立即删除order、reservation、active/client索引和全局route，并把对应`userId+clientKey`写入该sequence的primitive retirement buffer；owner在终态tombstone写入后只按buffer释放`RuntimeIdentityRegistry`的forward/reverse/allocation四个索引，并在释放前确认该key未被后续活动订单复用。删除Core Fact移除后已无调用方的`IdentityReleaseConsumer`。不改变撮合、资金、订单响应、幂等tombstone、sequence、snapshot或Aeron边界。
- 正确性门禁新增：每个saturation invocation结束时client identity数量必须严格等于fixture初始值；终态order/reservation、Lane client index和active index不得残留；释放identity后，相同clientOrderId仍必须由有界terminal tombstone拒绝；snapshot恢复只包含活动订单和有界tombstone。受影响功能/恢复测试`60/60`、4,096个唯一clientOrderId真实saturation支撑测试`1/1`已通过。
- 场景固定为仅`LINEAR_PERPETUAL`、matcher=1、4 Account Lane、10,000用户、512 listed/active symbols、每用户最多5持仓/10活动订单、每invocation 16,384 PLACE_ORDER、50% maker GTC+50% taker IOC、100,000 offered、open-loop/coordinated-omission corrected、做市与4,000ms mark刷新持续运行，严格且仅`256 in-flight`。
- 无profiler主轮固定`fork=1,warmup=3x3s,measurement=3x5s,thread=1`，吞吐不得低于`45,264.553 terminal business ops/s`；GC轮固定`fork=1,warmup=1x3s,measurement=1x5s,-prof gc`，报告B/op、allocation rate、GC次数/时间。随后JFR长稳固定`fork=0,warmup=3x3s,measurement=1x600s,timeout=12m`，8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、JDK 25 `profile` JFR、`maxsize=2g`及GC/safepoint日志。
- 所有轮次要求accepted business/Core分别等于terminal，两个unfinished、rejected/error/timeout/producer-starvation及期末matcher/Lane/context backlog为0；资金守恒、余额/冻结/持仓、订单终态、盘口、严格256窗口、identity回收和完成态snapshot恢复通过。长稳还要求DataLoss=0、allocation stall=0、Pages throttled=0、相对采集前swap增长不超过128MiB，预热后多轮old GC live set不持续正增长且最后完整old GC live不高于2.5GiB；否则只作失败诊断。
- 环境：Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16、MacBookPro16,1 / Intel Core i9-9880H（8物理/16逻辑CPU）/16GiB/macOS 26.7 x86_64。HEAD=`69675744cfb723deecbb6e9af252d055dc561555`，排除性能记录的代码diff SHA-256=`7a5339a35b776bba3cda192aa404aec4c1f5fa41659b68aca10ba207f412cd1f`，shaded JAR SHA-256=`015ef10958478392671e046b136293449f522b98155bfc5aaef9b8b6b3615b2b`。artifact固定为`target/qualification/20260904T063629Z-terminal-identity-reclaim-256/`；采集前swap=`1,407.25MiB`、Pageouts=116,366、Pages throttled=0。
- 不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线。锁定后不修改代码、场景、参数或门禁；任何失败轮次如实追加。

#### PV-95采集结果

- 正确性与长稳完成：无profiler、GC及600秒JFR三轮均正常退出；accepted/terminal business operations和Core messages严格相等，unfinished/rejected/error/timeout/producer-starvation为0，固定256窗口、资金/余额/冻结/持仓、订单终态、盘口、client identity回收及完成态snapshot恢复门禁通过。该JMH场景未输出分段延迟直方图，因此本轮不形成尾延迟验收结论。
- 无profiler主轮为`29,208.496 terminal business/Core ops/s`、`14,604.248 trades/s`，三个样本`28,860.214/27,338.476/31,426.798 ops/s`，低于`45,264.553/s`门禁`35.47%`，性能验收失败。GC诊断轮为`33,025.044 terminal ops/s`、`373.926 MiB/s`，按16,384 business ops/invocation换算约`13,154 B/business op`，测量期5次GC、181ms。
- 600秒JFR轮跑满，总时长10m28s，`30,517.865 terminal business/Core ops/s`、`15,258.932 trades/s`。JFR `DataLoss=0`，GC日志allocation stall/OOM均为0；预热后major old live依次在约`0.78–1.45GiB`内波动，末次完整major old live约`1.25GiB`，进程退出前heap used `2,228MiB`，不再出现PV-94的`6.2GiB` old live与26.5秒allocation stall，证明唯一client identity无界保留已消除。
- JFR热点仍显示owner提交路径存在回收成本：`releaseRetiredClientIdentities`出现在436个execution sample stack、`releaseClientKey`119个、`HashMap.remove`701个；当前实现每个终态client identity先回查Account Lane，再删除`clientKeys/clients/clientAllocationKeys/clientKeyAllocations`四个索引。整体更大的遗留热点仍包括排序（`DualPivotQuicksort` 5,486个stack）、MessageDigest（3,155个stack）和changed-key buffer。下一轮将client identity注册表收敛为单一反向collision/resolution索引，删除仅为回滚/历史release服务的三个重复索引及owner的Lane二次查询。
- 系统采集无Pages throttled，swap从`1,407.25MiB`降至`1,168MiB`，但Pageouts从116,686增至121,531；机器只有16GiB且8GiB AlwaysPreTouch，故本轮系统环境存在内存压力。NMT末次相对启动baseline committed增加约`91.1MiB`，主要为GC、metaspace/code、tracing与线程；没有交易owner同步文件/网络/数据库I/O证据。
- artifact：`target/qualification/20260904T063629Z-terminal-identity-reclaim-256/`。`main.json/gc.json/soak.json/soak.jfr/gc-safepoint.log/jfr-summary.txt/jfr-hot-methods.txt/jfr-allocation-by-site.txt/system-samples.log/nmt-20.txt` SHA-256依次为`6ec3f3519f59f1793530146ea26a8bf9d57a4505c3d27b804328db76735f1ff4`、`2aa330fc34edb11d747b366a3403dcbfa934f554e8cabecec18d1af9a4a46eb1`、`01afbda1b6625ea362ee8bcc4c864819183aa9db6779a974babb0d6125392761`、`c867f87299d66ea916e5bc2d93de16bfb8a55280eba473a784509fdcee54488f`、`6cf708b002a7e58c18d4147a16522c16c87d825659f3f48a54dfc8543a0b6ac2`、`886d0ef0b8ab4d9d11cf98e866a58cb36544f70fc4a35ed7b974faa3eba3694a`、`a7cc7d3d735a4c38e869363ad404fe5b1357f1d32285fe6193e12375c2575d05`、`fcaae6055031baa3edfb06dd4df6be3ba100c984783fb0269cb17f64c139bf83`、`9b0f96b97a931c806e79c94ac8e88a49d0b2e59988d60a2f433c4831ae34bf70`、`a70d5d7e7c7ebb38aed6491d34dd60e809097b6738575c990cf9fa9e099cc2d8`。PostgreSQL、exporter及外围服务未启动、未测试。

### 2026-09-04 14:59:06 +08:00 — `PV-20260904-256-96` — `采集前锁定（owner零Lane等待的client identity回收）`

#### 采集前锁定

- 被测修改建立在PV-95已证明有界的终态清理上：删除owner对每个终态client key调用`TradingRuntimeState.orderIdByClient()`所产生的Account Lane任务投递和同步`await()`；`RuntimeIdentityRegistry`把`clientKeys/clients/clientAllocationKeys/clientKeyAllocations`四个索引收敛为单一`clientKey -> identity+referenceCount`反向collision/resolution索引。每次成功prepare取得一个引用，回滚或终态各释放一个引用；后续sequence在旧终态提交前复用相同key时引用计数保证旧release不会删除新活动订单的identity。snapshot仍只保存活动identity。
- 正确性门禁：终态order/reservation/Lane索引/global route及identity全部释放；同一key两个sequence并行在途时旧终态release后新引用仍可解析，最终release后identity数量归零；terminal tombstone继续拒绝重复clientOrderId；snapshot恢复只含活动订单及有界tombstone。HotSpot 25 reactor定向service/查询/恢复/撮合测试`64/64`、4,096唯一clientOrderId saturation支撑测试`1/1`已通过。
- 场景固定为仅`LINEAR_PERPETUAL`、matcher=1、4 Account Lane、10,000用户、512 listed/active symbols、每用户最多5持仓/10活动订单、每invocation 16,384 PLACE_ORDER、50% maker GTC+50% taker IOC、100,000 offered、open-loop/coordinated-omission corrected、做市与4,000ms mark刷新持续运行，严格且仅`256 in-flight`。
- 无profiler主轮固定`fork=1,warmup=3x3s,measurement=3x5s,thread=1`，吞吐不得低于`45,264.553 terminal business ops/s`；GC轮固定`fork=1,warmup=1x3s,measurement=1x5s,-prof gc`。JFR长稳固定`fork=0,warmup=3x3s,measurement=1x600s,timeout=12m`，8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、JDK 25 `profile`、`maxsize=2g`和GC/safepoint日志。
- 所有轮次要求accepted/terminal business及Core相等，unfinished/rejected/error/timeout/producer-starvation和期末matcher/Lane/context backlog为0；资金守恒、余额/冻结/持仓、订单终态、盘口、严格256窗口、identity回收和完成态snapshot恢复通过。长稳要求DataLoss=0、allocation stall=0、Pages throttled=0、swap增长不超过128MiB、预热后old live不持续增长且完整old GC live不高于2.5GiB。
- 环境：Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16、MacBookPro16,1 / Intel Core i9-9880H（8物理/16逻辑CPU）/16GiB/macOS 26.7 x86_64。HEAD=`69675744cfb723deecbb6e9af252d055dc561555`，排除性能记录的代码diff SHA-256=`4561a3c0d9162a4e114290369d6e20cec7d9cd5f68ffc0a44f48c857d0f0c86a`，shaded JAR SHA-256=`8e1b1a5de6a355699927567bd7b6e179da6b657724796e519e1ddf70f26876e0`。artifact固定为`target/qualification/20260904T065906Z-client-identity-refcount-256/`；采集前swap=`1,412.25MiB`、Pageouts=121,978、Pages throttled=0。
- 不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线。锁定后不修改代码、参数、场景或门禁；失败和无效结果如实追加。

#### PV-96采集结果

- 无profiler主轮为`48,793.720 terminal business/Core ops/s`、`24,396.860 trades/s`，三个样本`42,550.364/58,060.941/45,769.857 ops/s`；通过`45,264.553/s`门禁。相对PV-95同步owner→Lane查询版本提升`67.05%`，相对PV-91正式对照`49,659.886/s`为`-1.74%`。accepted/terminal闭合，unfinished/rejected/error/timeout/producer-starvation为0，资金、余额/冻结/持仓、订单终态、盘口、严格256窗口、identity回收及snapshot恢复通过。
- GC轮为`52,628.300 terminal ops/s`、`26,314.150 trades/s`、`562.555MiB/s`，换算`12,541.937 B/business op`，测量期GC为0；比PV-95四索引同步查询版本约`13,154 B/op`减少约`612 B/op`。定向HotSpot 25 reactor service/查询/恢复/撮合测试`64/64`及4,096唯一clientOrderId saturation支撑测试`1/1`通过。
- 600秒JFR轮跑满，总时长10m27s，`44,801.954 terminal business/Core ops/s`、`22,400.977 trades/s`；带profile的吞吐不替代无profiler主轮。业务门禁及teardown全部通过。JFR `DataLoss=0`，GC allocation stall/OOM为0；预热后major old live稳定在约`0.89–1.23GiB`，末次完整major old live`1.16GiB`，退出前heap used`1,516MiB`，client identity引用未积累。
- 终态identity release路径不再包含`orderIdByClient/onLane/LaneMutationTask.await`；JFR中仅有1个`orderIdByClient`样本来自snapshot恢复projector，唯一`LaneMutationTask.await`样本来自snapshot projection的risk index，不属于终态identity回收。`clientAllocationKeys/clientKeyAllocations`已完全不存在；单索引直接release仍可见，但没有Lane往返。剩余主要热点是HashMap、changed-key buffer、排序、MessageDigest及既有snapshot/index提交。
- 内存本身通过有界门禁，但系统有效性门禁失败：Pages throttled始终为0，然而相对采集前swap从`1,412.25MiB`增至`2,136MiB`（+`723.75MiB`，超过128MiB门禁），Pageouts从121,978增至131,172；因此本轮长稳标记为“代码内存趋势通过、宿主机系统稳定性部分验证”，不据此声明完整生产容量。NMT末次相对baseline committed约+`89.9MiB`，无交易owner同步文件/网络/数据库I/O。
- artifact：`target/qualification/20260904T065906Z-client-identity-refcount-256/`。`main.json/gc.json/soak.json/soak.jfr/gc-safepoint.log/jfr-summary.txt/jfr-hot-methods.txt/jfr-allocation-by-site.txt/system-samples.log/nmt-20.txt` SHA-256依次为`9fbabd23bbc21e3ff25340bd1f2b49e7d857f70584a395a935515b92ff274482`、`e74078001eb3cc389ea7fef88bc5c53278a5702199ef967187724ebad0f897eb`、`e405743419c135bae6f3d0b9251b93526da803460a18e876b9d426eedcdcf88e`、`eab60677870fd83be84c7daf487b68bdc0e7da73f776ed5767f3216341c0c6eb`、`f0389f24b3f60a1bb3f8c4ceee534b0af246bf3f4ef11ff808d24d7579319dd2`、`6e34a9e9032dd64f4f8dbbf51f17c93165e289238979467f5dea62428ba50636`、`70e6e86bb7e0ce57a5e056ab657ac8be31f700c3afd362ef1e5719ca52a3f9ce`、`6801fc350cc2939f85a29a86ae86adf2ea9dab06c3f0a73afd4a25bcc3ce539b`、`f61da206496b6c1913f7b9c6c21d23257822a2aafd291ea062a3e65c9ab30d54`、`d159c6208503a54571dd063c55b1c45f8b302cca5d78b838943009a1956ca495`。PostgreSQL、exporter及外围服务未启动、未测试。
### 2026-09-04 15:26:23 +08:00 — `PV-20260904-256-97` — `采集前锁定（risk completion 消除 owner Lane 回读）`

#### 采集前锁定

- 被测修改：Account Lane 在 completion 的 `PublishedLaneChanges` 中直接携带 `RiskSnapshotRuntime` 最终值，`null` 明确表示删除；owner 维护只读 `publishedRiskSnapshots`，`visitChangedIndexes` 和 `releaseRetiredPositionIdentities` 只消费 owner-local completion 数据，不再通过 `riskSnapshot -> onLane -> LaneMutationTask.await` 回读 Lane。position、risk index、identity、资金、sequence、matcher 和 Aeron snapshot 语义不变。
- 对照 commit=`560626b816ec98f2babee4ff4d9474e456778745`，对照为 PV-96 matcher=1 `48,793.720 terminal business ops/s`、`12,541.937 B/business op`。主交易吞吐不得低于 `43,914.348 terminal business ops/s`（-10%）；accepted/terminal business operations 与 Core messages 必须分别相等，unfinished/rejected/error/timeout/producer-starvation 和期末 matcher/Lane/in-flight/context backlog 必须为0；资金守恒、余额/冻结/持仓、订单终态、盘口、snapshot recovery、risk index 和 position identity 回收必须通过。
- 正式场景固定为 `LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`：仅 `LINEAR_PERPETUAL`、1 matching engine、4 Account Lane、10,000活跃用户、512 listed/active symbols、每用户最多5持仓/10活动订单、每 invocation 16,384 PLACE_ORDER、50% maker GTC + 50% taker IOC、做市持续运行、100,000 offered、open-loop且修正 coordinated omission；严格且仅 `256 in-flight`。无 profiler 为 `fork=1,warmup=3x3s,measurement=3x5s,thread=1`；GC为`fork=1,warmup=1x3s,measurement=1x5s`；JFR为相同业务参数、`fork=0,warmup=1x3s,measurement=1x10s`。
- 受影响组件场景为更新后的 `LinearPerpetualCoreBenchmark.riskScanLanePublishedCommit`：4 Account Lane、1,000风险用户，执行真实 mark-price/risk scan、Lane mutation、changed-risk index commit 及校验；该场景没有客户端 in-flight 参数，不形成并发容量结论，也不采集任何非256 in-flight档位。组件 JMH 使用`fork=1,warmup=3x3s,measurement=3x5s,thread=1`，JFR使用`fork=0,warmup=1x3s,measurement=1x10s`。JFR门禁为 owner commit/index/identity stack 中不出现 `riskSnapshot -> onLane -> LaneMutationTask.await`；风险命令自身 `executeUserRisk` 的一次 Lane completion 等待单独归类，不算回读。
- 环境固定：Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16、MacBookPro16,1 / Intel Core i9-9880H / 16 logical CPU / 16GiB / macOS 26.7 x86_64；8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、Account Lane BLOCKING。JFR使用 `surprising-aeron-benchmarks/config/owner-commit-profile.jfc`，保存原始 `.jfr`、JMH JSON/log、GC/safepoint、summary/views和校验哈希；profile数值不与无profiler吞吐直接比较。采集前swap=`1,880MiB`，若发生明显swap/pageout、throttling或JFR DataLoss则只标记部分验证。
- 采集前 HotSpot 25 定向 service 测试 `57/57`、benchmark真实负载测试 `11/11`通过。额外执行的旧 `RuntimeCommitRecoveryTest` 有5个已删除 exporter/snapshot 契约失败，按既定范围排除，不作为本轮交易路径失败。HEAD=`560626b816ec98f2babee4ff4d9474e456778745`，排除本记录的代码diff SHA-256=`c4307b675216275c7cd2fb3d6303292e9ba99d77b26dca4081e79d658d6ab4cc`，shaded JAR SHA-256=`6f411c30a3cc6b928f039d42d0eaa0a152b3e68dc1ef7435108019ecfe5410cc`，JFC SHA-256=`dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。artifact固定为`target/qualification/20260904T152623Z-risk-completion-256/`。
- 不启动或测试 PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线；不执行长稳，因此即使短轮通过也只形成受影响交易路径的吞吐、分配、热点和正确性部分验证，不声明生产容量或无泄漏。锁定后不修改场景、参数或门禁；失败和无效轮次如实追加。

#### PV-97采集结果

- HotSpot 25 定向测试通过：`RuntimeChangedIndexCommitTest`、`CoreRiskStateTest`、`TradingRuntimeStateTest`共`57/57`，更新后的真实交易负载支撑测试`LinearPerpetualBenchmarkSupportTest`为`11/11`；新增回归覆盖 Lane 发布 risk 最终值、`null` 删除标记、risk index 删除以及 position identity 最终回收。执行范围为 service reactor 定向测试和 benchmarks package；未启动 PostgreSQL、exporter、wallet 或外围服务。额外误执行的旧 `RuntimeCommitRecoveryTest` 有5个已删除 exporter/snapshot 契约失败，按锁定范围排除。
- 严格`256 in-flight`、matcher=1无 profiler 主轮为`49,608.956 terminal business/Core ops/s`、`24,804.478 trades/s`，三个吞吐样本为`45,111.462/55,453.496/48,261.909 ops/s`；较PV-96的`48,793.720/s`提升`1.67%`，通过`43,914.348/s`门禁。accepted/terminal business及Core分别相等，unfinished/rejected/error/timeout/producer-starvation及`laneQueryOperations`均为0，期末backlog清零；严格窗口最大backlog=256。资金守恒、余额/冻结/持仓、订单终态、盘口和snapshot recovery由真实负载 teardown 门禁通过。
- GC轮为`49,138.673 terminal ops/s`、`24,569.337 trades/s`，分配率`510.792 MiB/s`、`203,573,981 B/invocation`，按16,384业务操作换算`12,425.90 B/business op`，比PV-96的`12,541.937 B/op`下降`0.93%`；JMH profiler记录4次GC、222ms，该数包含fork启动/预热窗口，不能作为纯测量期停顿。受影响组件`riskScanLanePublishedCommit`（1,000 risk users、4 Lane）为`1,751,552.428 us/op`，三个样本`1,765,757.679/1,716,938.697/1,771,960.908 us/op`；该组件场景不形成并发容量结论。
- 主JFR轮为`49,880.447 terminal business/Core ops/s`、`24,940.223 trades/s`，业务门禁闭合。代表性16,384样本三段延迟（ns）为：入口→accepted `p50/p90/p95/p99/p99.9/max=239,314,544/383,144,950/400,108,262/415,637,967/418,486,468/418,583,387`；accepted→terminal为`7,344,797/8,886,549/9,498,650/18,730,569/23,498,124/23,649,291`；入口→terminal为`242,729,011/390,423,400/408,164,365/421,507,429/421,828,110/421,938,314`。这是100,000 offered open-loop并修正coordinated omission后的端到端分布，入口段包含供给排队。
- 对两个JFR的全部`ThreadPark`逐栈分类：主交易轮`riskSnapshotAwaits=0`、`identityReleaseAwaits=0`；risk组件轮同样均为0，满足本轮目标。risk轮仍有`executeUserRisk`自身等待14,448次、其他changed-index提交等待35,000次及snapshot materializer等待945,696次；主轮对应为36,131/2/81,065次，均不经过本次已删除的`riskSnapshot -> onLane`或position identity回收路径，不宣称owner已经完全无等待。
- 主JFR时长30秒、约79.6MB，`DataLoss=0`、socket read/write=0；5次GC，23个pause事件合计约`0.229ms`，p99/max约`0.0133ms`。最长VM operation为`HandshakeAllThreads 3.34ms`，最长已完成safepoint约`0.626ms`；最长JIT编译886ms，说明短轮仍受编译活动影响。热点为`progressPlaceAdmissions`、HashMap/ConcurrentHashMap、`CoreStateHash.mix`、TreeMap和changed buffer；主要分配点为`LongObjectHashMap.addKeyValueAtIndex`、`OrderRuntime`、TreeMap/HashMap、matcher evidence及编解码。878个Exception和128个Error来自启动期反射/native capability探测，未见交易owner同步文件、网络或数据库I/O。
- NMT主轮committed峰值除固定8GiB Java heap外，GC/Tracing/Metaspace/Code分别约`52.8/32.2/31.2/29.4MiB`；DirectBuffer count/capacity/used始终为0。采集后Pages throttled=0，swap由采集前`1,880MiB`降至`1,720MiB`，但宿主机仍有既存swap压力，且本轮未执行长稳，所以内存只作短轮分配与边界证据，不声明无泄漏或生产容量。
- 原始artifact为`target/qualification/20260904T152623Z-risk-completion-256/`。`main.json/gc.json/risk-main.json/main-jfr.json/risk-jfr.json/main.jfr/risk.jfr/main-jfr-summary.txt/main-hot-methods.txt/main-allocation-by-site.txt` SHA-256依次为`038c68ce3a3388953dad6f6650cc6becd62ef0a81244334692d0890fc3150097/4fa314a65b7fa2d892c78d6f29ffcca837c14bef5343594866a75e2ce50b1eef/0b5750956462a0dab34642d7f6e9e0f594ee989de8a6fa2d860a6836751adece/06d14be60fa3bd12ce799845105241327d0e20a44e6210d9679a07287115a6f8/8d060a923f8e4a8164f6bc903ec3ca7f27801cb3404c82e0a3489d3b741550fb/bcdf7752aa4efbb9033a1eaf8f38ba2ece6569e69cd63c52c6f7f03a13a8c6ae/698c4d45c054d0b109767462871dfb79674aaf50288d9a0424dd34da34f4d7ee/1c405ce3029aa22d456c28cc1b564a1adec5df32f408241ad2db4282de1f80c0/110c9323d8061d2b886be9ab661e543a78067bfccee15f21d68f894a22c06f2d/513bbe6f9cbfb44300ff7e2e21a8bff3799f6b4774376981f61ed7e2317cca95`。完整JMH/JFR参数保存在JSON和JFR中；主命令分别使用benchmark shaded JAR执行上述锁定的`-f/-wi/-w/-i/-r/-t/-p`参数，GC轮附加`-prof gc`，JFR轮附加锁定JFC、NMT及GC/safepoint日志参数。

### 2026-09-04 15:52:00 +08:00 — `PV-20260904-256-98` — `采集前锁定（risk scan Lane 批处理优化）`

#### 采集前锁定

- 目标是优化真实永续risk scan，不改变风险公式、扫描顺序、64 work-unit批次上限、强平判定、资金/持仓或Aeron恢复语义。先固定修正后的专项基准：进度检查直接读取owner-local `RiskScanRuntime`，不在测量区反复调用`tradingState()`全量物化；以生产代码未改的commit `b2830c19`采集诊断基线，随后候选实现必须使用同一benchmark源码、参数和环境比较。
- risk专项固定为`LinearPerpetualCoreBenchmark.riskScanLanePublishedCommit`、仅`LINEAR_PERPETUAL`、4 Account Lane、1,000风险用户、默认64 work-unit batch、1 matcher，执行真实mark price、全部continuation、risk snapshot/changed-index commit、liquidation work读取和teardown校验；`fork=1,warmup=3x3s,measurement=3x5s,thread=1`，GC轮`fork=1,warmup=1x3s,measurement=1x5s,-prof gc`，JFR轮`fork=0,warmup=1x3s,measurement=1x10s`。该组件场景没有客户端in-flight参数，不形成并发容量结论，也不采集其他in-flight档位。
- 候选risk主分数必须比本轮修正基准至少提升20%，三个测量样本均须完成且不得新增异常；JFR要求减少每用户`executeUserRisk -> LaneMutationTask.await`，不得重新引入risk snapshot/index/position identity Lane回读。risk snapshot、liquidation数量及状态、scan终态、资金守恒、余额/冻结/持仓和snapshot recovery必须一致。
- 交易回归固定为`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`：matcher=1、4 Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker GTC+50% taker IOC、100,000 offered、open-loop/coordinated-omission corrected、严格且仅256 in-flight；`fork=1,warmup=3x3s,measurement=3x5s,thread=1`。吞吐不得低于PV-97的90%即`44,648.060 terminal business ops/s`，accepted/terminal business及Core必须闭合，unfinished/rejected/error/timeout/starvation与期末backlog为0，资金、订单、盘口及snapshot恢复通过。
- 环境锁定为Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16、Intel i9-9880H / 16 logical CPU / 16GiB / macOS 26.7 x86_64；8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、Lane BLOCKING。JFR使用`owner-commit-profile.jfc`；DataLoss、明显swap/pageout或throttling使该轮只能作为部分验证。采集前swap=`1,624MiB`、Pages throttled=0。
- 采集前benchmark支撑测试`11/11`通过；基准修正diff SHA-256=`56b4455c93888fb831962a334a9e2f44acbef5f14733912b7f67ce462f037384`，修正后shaded JAR SHA-256=`52c4e6cfaf56734e01dd7d2a1952e2d92e53e6de8c0f8b522719e175a65d716f`，JFC SHA-256=`dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。artifact固定为`target/qualification/20260904T155200Z-risk-efficiency-256/`。不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线；锁定后场景、参数、门禁不再修改，失败轮照实记录。

#### PV-98采集结果

- 修正后、生产代码仍为`b2830c19`的risk基线为`115,778.548 us/op`（1,000 users/scan），三个样本`110,985.959/113,326.402/123,023.284 us/op`，折合`8,637 users/s`。候选实现为`47,151.929 us/op`，三个样本`51,471.912/49,852.740/40,131.134 us/op`，折合`21,208 users/s`；扫描延迟下降`59.27%`、用户处理能力提升`145.54%`，通过锁定的至少20%主分数门禁。
- 实现将liquidation最终值和`null`删除标记纳入`PublishedLaneChanges`，completion后同时更新owner-local mirror和changed-index buffer；owner的changed liquidation index提交和后续liquidation work查询不再遍历Lane。候选JFR的changed-index liquidation readback与liquidation work query readback均从基线`334,969/167,497`次降为`0/0`，risk snapshot和position identity readback仍为0。
- 锁定的“每用户`executeUserRisk -> LaneMutationTask.await`减少”未达成：候选JFR仍有`334,361`次user-risk await，占已分类Lane await约`97.5%`；绝对次数高于基线`138,270`是因为候选在相同采样窗口完成了更多扫描，不表示单次扫描增加。曾尝试按Lane整批执行，但会把权威的全局userId顺序改为Lane顺序，并破坏旧pass完成前保留新mark price的语义；`CoreRiskStateTest`暴露1 failure + 1 error后已完全回退该尝试。因此本轮risk效率优化有效，但按预锁定标准只能标记为部分验收。
- risk GC轮为`50,499.206 us/op`、`661.803 MB/s`、`51,357,038 B/scan invocation`，记录12次GC/265ms；该数包含Level.Invocation的snapshot restore/setup，不作为纯risk-user热路径分配结论。候选JFR时长16s、带profile分数`54,384.954 us/op`，`DataLoss=0`、4次ZGC，heap从`82MiB/820MiB/1.6GiB/2.4GiB`回收至`48MiB/184MiB/386MiB/556MiB`，最长GC pause`0.0228ms`，最长已完成safepoint`0.598ms`、最长VM operation`0.568ms`。NMT除固定8GiB heap外的GC/Metaspace/Tracing/Code峰值约`39.0/30.6/29.6/27.4MiB`；无socket I/O，file I/O仅为JAR/JDK加载、JFR/JMH产物和jffi临时native library，未见交易owner同步I/O。热点与分配主要位于TreeMap、ObjectsPool初始化、rolling state hash、Lane context ring及基准setup/restore；短JFR不证明无泄漏。
- 严格`256 in-flight`主交易第一轮为`44,577.829 terminal business/Core ops/s`、`22,288.914 trades/s`，低于`44,648.060/s`门禁`0.16%`，三个样本`38,497.008/40,959.129/54,277.349 ops/s`，标记为失败且高方差轮。相同参数复测为`48,675.089 terminal business/Core ops/s`、`24,337.545 trades/s`，三个样本`45,138.864/55,701.201/45,185.204 ops/s`，通过门禁；较PV-97的`49,608.956/s`为`-1.88%`。两轮accepted/terminal business及Core都闭合，unfinished/rejected/error/starvation/laneQuery均为0，期末backlog清零，资金、余额/冻结/持仓、订单终态、盘口和snapshot recovery由teardown门禁通过。
- HotSpot 25定向回归`RuntimeChangedIndexCommitTest/CoreRiskStateTest/TradingRuntimeStateTest`共`58/58`通过，benchmark真实负载支撑测试`11/11`通过；新增覆盖Lane发布liquidation最终值、删除标记、owner-local查询与liquidation index删除。最终代码diff SHA-256=`46c00f156fc3486a3e174f5dc0edee40943f38f66385f2ec4716fa81991f2ea1`，shaded JAR SHA-256=`a8344417833af64ab4a4080104483749eaba289cfbcaa182f32d1dfc901dc805`。第一次fork=0 JFR因module opens未传入host JVM产生`IllegalAccessError`，另一次命令因zsh未引用`-Xlog:gc*`而在JVM启动前失败，均作为无效诊断轮保留。
- artifact：`target/qualification/20260904T155200Z-risk-efficiency-256/`。`baseline-risk.json/candidate1-risk.json/candidate-risk-gc.json/baseline-risk.jfr/candidate-risk.jfr/trading-main.json/trading-main-repeat.json/candidate-jfr-summary.txt/candidate-hot-methods.txt/candidate-allocation-by-site.txt/candidate-gc.txt/candidate-safepoints.txt/candidate-vm-operations.txt/candidate-native-memory.txt`的SHA-256依次为`992b15b4f775c39c1e819c076be1ef14f4873cf0006d1e7c5b4c7372b607b9a3/78bd0d6784acf2c35ed1f7d9ab8e1a1c1171219f9808e0f240c3736f27b4465d/21ef9c25ed4819ede3e96a0ee22c51fa6c3d421ecf1e7f91333c2b624969d957/c8fa5f1b0a3a16f27915271651c84e8228187f1f909ca52197e4e7bb22fb35a3/5f0813271785189cf38fa91bd0efa70bcc5002e5ff715ab11e9014f4a549853e/ac0bbac96b1b14cb91a5f8f17ce57fb940ca40122b01ac9d660502850cb49733/a521bf29c05965941d3f905cd587dcf05cfd16aad7fbc3edfb55f0ddcd98d169/e6a1b4cb55c86d11e385dbb9e3675d331280f7126eab0fe75c1976b981cb88f5/14200195d6299800a1a0a2fb5d306cd03c75cfb5df51a2f8f0d4e63475189dcb/2b55b6eb277e61b1bef0d4a030e7f1e33022d28277b0a983647634fc6b671cf8/12daf5784dfac77869f532d7cc0ec4984de190ea301ad22fa2e3cdfaecd613b4/bcf569e3fdf07498914c6c001bcb790d309f2cd311d0514c5d2d444389ac6deb/b641141053b42c2c686fdfc20329a2c1484610c06debd7069229090678a4c32e/bb2031d23330c60ce275ab4adbe775d2f2cab4dc44bc730eed573e9ba2c16fb9`。采集后swap=`1,592MiB`、Pages throttled=0；未做长稳，不声明生产容量或无泄漏。PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线未启动、未测试。

### 2026-09-04 16:21:27 +08:00 — `PV-20260904-256-99` — `采集前锁定（risk user work 下沉 Account Lane）`

#### 采集前锁定

- 目标：沿用已存在并已进入snapshot/codec/hash的`RiskScan.accountLaneId`，把默认64 work-unit continuation改为确定性的Lane顺序；当前Lane在一次Lane任务内连续处理本Lane用户及单用户分页，Lane耗尽后推进下一Lane。owner不再对每个user执行一次`executeUserRisk/await`，只负责读取/写入scan cursor、分配全局liquidationId和Lane completion边界。不新增Map/List/Set，不改变风险公式、单次总work-unit上限、mark epoch、资金/持仓或强平状态机。
- 权威`TradingCoreReducer`和runtime必须使用同一`LaneTopology`路由及`accountLaneId + lastUserId + active-user cursor`语义；扫描顺序从全局userId调整为确定性的`laneId asc、lane内userId asc`。分页与一次性扫描最终risk snapshots/liquidations/nextLiquidationId必须一致；旧mark pass未完成时到达的新mark只更新latest price，必须先完成旧scanStart epoch，再自动开始latest epoch。中途snapshot恢复必须保留Lane和用户内cursor并得到相同终态。
- risk专项固定为`LinearPerpetualCoreBenchmark.riskScanLanePublishedCommit`：仅`LINEAR_PERPETUAL`、4 Account Lane、1 matcher、1,000 risk users、默认64 work-unit batch、真实mark/risk snapshot/liquidation changed-index及teardown；无profiler固定`fork=1,warmup=3x3s,measurement=3x5s,thread=1`，GC固定`fork=1,warmup=1x3s,measurement=1x5s,-prof gc`，JFR固定`fork=0,warmup=1x3s,measurement=1x10s`。该组件场景没有客户端in-flight参数，不形成容量结论。
- risk对照为PV-98 `47,151.929 us/op`、`21,208 users/s`；候选要求至少再提升20%，三个样本全部完成，JFR中`executeUserRisk -> LaneMutationTask.await`必须为0，risk continuation每命令Lane await不得超过实际访问Lane数，不得重新引入risk snapshot、liquidation index/query或position identity Lane回读。
- 主交易回归固定为`LinearPerpetualCoreBenchmark.saturatedMatchingWorkload`：matcher=1、4 Lane、10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker GTC+50% taker IOC、100,000 offered、open-loop并修正coordinated omission、严格且仅256 in-flight；`fork=1,warmup=3x3s,measurement=3x5s,thread=1`。不得低于PV-98复测的90%即`43,807.580 terminal business ops/s`，accepted/terminal business及Core闭合，unfinished/rejected/error/timeout/starvation/laneQuery和期末backlog为0，资金、余额/冻结/持仓、订单终态、盘口与snapshot recovery通过。
- 环境：Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16、Intel i9-9880H / 16 logical CPU / 16GiB / macOS 26.7 x86_64；8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、Lane BLOCKING。HEAD=`4748b9d8dd4ee9def6ee8e77fd5a067dbc51c79a`，基线JAR SHA-256=`a8344417833af64ab4a4080104483749eaba289cfbcaa182f32d1dfc901dc805`，JFC SHA-256=`dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`；采集前swap=`1,592MiB`、Pages throttled=0。artifact固定为`target/qualification/20260904T162127Z-risk-lane-batch-256/`。
- 不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线；不执行长稳，因此只形成受影响路径的吞吐、分配、热点、阻塞和正确性部分验证。锁定后不修改场景、参数与门禁，失败及无效轮如实追加。

#### PV-99采集结果

- 实现沿用既有`accountLaneId`字段和snapshot/codec/hash，不增加每Lane Map/List/Set：扫描顺序改为确定性的`laneId asc、Lane内userId asc`，一次`executeRiskLane`在Lane线程内连续处理本Lane用户及单用户持仓/预留分页；总预算仍是每条Core continuation最多64个实际work unit。Lane耗尽后cursor推进下一Lane。新liquidation使用Lane返回的局部next-id结果，由owner在completion边界一次推进全局`nextLiquidationId`，不存在Lane间ID竞争。
- risk主轮为`21,624.493 us/op`（1,000 users/scan），三个样本`23,734.601/22,021.302/19,117.577 us/op`，折合`46,244 users/s`。较PV-98的`47,151.929 us/op`延迟下降`54.14%`、处理能力提升`118.05%`，通过至少20%门禁；较PV-98修正前最初基线`115,778.548 us/op`累计提升`435.40%`。
- JFR全部ThreadPark逐栈检查后，生产risk路径`executeUserRisk -> LaneMutationTask.await=0`；`executeRiskLane` completion await为`18,557`次，较PV-98每用户await的`334,361`次下降`94.45%`。risk snapshot、liquidation changed-index/query和position identity Lane回读均为0。剩余risk等待对应有界continuation实际访问的Lane边界；当前仍是单cursor按Lane顺序推进，不宣称多个Lane已同时并行在途或owner完全非阻塞。
- risk GC轮为`28,118.430 us/op`、`853.456 MB/s`、`51,236,943.636 B/scan invocation`、12次GC/286ms；分配字节包含Level.Invocation snapshot restore/setup，较PV-98同口径`51,357,038 B/op`下降约`0.23%`。16秒JFR带profile分数`23,890.168 us/op`，`DataLoss=0`、6次ZGC，最长pause`0.0200ms`，最长已完成safepoint`0.198ms`，最长VM operation为`HandshakeAllThreads 0.808ms`。NMT除固定8GiB heap外GC/Tracing/Metaspace/Code峰值约`99.4/35.9/30.6/27.0MiB`；无socket I/O，未见交易owner同步文件、网络或数据库I/O。热点仍以TreeMap、primitive map、Lane健康检查和setup/hash为主，`processLane`只占execution samples约`1.13%`、allocation pressure约`1.08%`。
- 严格且仅`256 in-flight`、matcher=1主交易回归为`52,059.914 terminal business/Core ops/s`、`26,029.957 trades/s`，三个样本`44,725.048/58,391.246/53,063.447 ops/s`；较PV-98复测`48,675.089/s`提升`6.95%`，通过`43,807.580/s`门禁。accepted/terminal business和Core分别相等，unfinished/rejected/error/timeout/producer-starvation/laneQuery均为0，期末backlog清零；资金、余额/冻结/持仓、订单终态、盘口和snapshot recovery由benchmark teardown门禁通过。
- HotSpot 25最终定向service回归`73/73`、扩展永续资金/生命周期/sectioned snapshot测试`59/59`、benchmark真实负载支撑测试`11/11`通过。新增验证Lane扫描分页、跨Lane确定性liquidationId、每页runtime/reference parity、中途snapshot恢复到相同终态、risk Lane operation数量不再与用户数线性对应。最终代码diff SHA-256=`df7c2e86e45bbea85a816a80c4c5f406d9ad8074db48d0054b0707f55c6b3076`，candidate shaded JAR SHA-256=`b8a64b21a7535fc8977fa8379186ebc884572eb5828dbc5fa4863839e41988eb`。
- 第一次JFR命令因zsh未引用`-Xlog:gc*`而在JVM启动前失败，无业务样本，作为无效诊断轮记录。有效artifact位于`target/qualification/20260904T162127Z-risk-lane-batch-256/`；`risk-main.json/risk-gc.json/risk-jfr.json/risk.jfr/risk-lane-stacks.txt/trading-main.json/jfr-summary.txt/jfr-hot-methods.txt/jfr-allocation-by-site.txt/jfr-gc.txt/jfr-safepoints.txt/jfr-vm-operations.txt/jfr-native-memory.txt/risk-gc-safepoint.log`的SHA-256依次为`74655f9a40a91265026009d92efdf49b9c25c70391071257a2931529450b2d6f/2c9c4cbaa55a6ffb8c9ec474401f537722ede2f8129f96539164295d234d28dc/e0f8504ea7b69de2fb97e195aba74a47af31c2422d1d5966e88d3753fa2cfc51/52ca3b36ab7310f2ec1344d0e08fa4e62d91018300a1a8e65b9b89a0ac5db3f9/27e8535a6da5360c11f5ef58bd688ab8d9cd323234166302a1fdc96e5c9919d2/115d6e25015307de9911e27d6f3c15e6b3766f53227c5dcf168073faef909a22/1c74a20a8c9ef5cef27a721dd832fcacfe215305dd5482d783b99766e5ad4115/38624f7890911c337408057378ed9730f54c1d7f2c443e2721cc15cfa645dc2c/8cab45f4882ed82d128ce5e40c853a5a5d95bdac0851cf2e16c39f104c20bfcd/6aadd503c4594938fc83d78170a04e24b0527f88619b99c6c61f12070a069eef/3a03f3998c6fb4c066c90f409f3c9ef5d15106df7a99b73562c058c3059fe752/fb6cf3039d3014b061e9ee998c9e25f7921eedc9fcd06ae2dd35171bd3dd0bdd/51b6d0c5474470b00a03bdf0c9eb7530b8d99135d3200f46ac93b4dfa42e9138/2fd11b6e1864ef0abeccb8d054e8abb90d2aad68dc148d43fed3f35b90d3abee`。采集后swap=`1,560MiB`、Pages throttled=0；未做长稳，不声明生产容量或无泄漏。PostgreSQL、exporter及外围服务未启动、未测试。
- 采集后边界审查发现：Lane最后一个用户恰好耗尽第64个work unit时，PV-99 runtime候选要到下一命令才推进Lane，而权威reducer在当前命令推进。上述测试样本未命中该精确边界；已增加Lane-local exhaustion判断修复。因为修复发生在采集后，PV-99全部性能结果降级为诊断数据，不作为最终候选验收。

### 2026-09-04 16:38:53 +08:00 — `PV-20260904-256-100` — `采集前锁定（risk Lane batch 精确边界修复）`

#### 采集前锁定

- 被测代码为PV-99 Lane-owned risk batch加精确页边界修复：`processLane`在预算恰好归零且active user已完成时，只在当前Lane线程内检查是否仍有下一用户，并在同一completion中返回Lane完成标记；不得增加owner查询、额外Lane task、容器或改变64 work-unit预算。权威reducer、runtime、snapshot、liquidationId和新mark接管语义必须逐页一致。
- risk、GC、JFR和严格256 in-flight主交易场景及全部参数与PV-99相同。risk主分数仍须相对PV-98 `47,151.929 us/op`提升至少20%；`executeUserRisk` await必须为0，risk snapshot/liquidation/index/identity回读为0。主交易不得低于`43,807.580 terminal business ops/s`，所有业务、Core、unfinished、错误、超时、拒绝、starvation、laneQuery、backlog、资金、订单、盘口及snapshot门禁不变。
- 采集前HotSpot 25定向service测试`73/73`、benchmark支撑测试`11/11`通过。HEAD仍为`4748b9d8dd4ee9def6ee8e77fd5a067dbc51c79a`，排除性能记录的最终代码diff SHA-256=`dc8696ff569cb21e95bb84890a6a7bb993f431d80035cee223493fa39605deab`，candidate shaded JAR SHA-256=`7ca932d370acd40ee2bdb11bfefec57e327164532da1542c551579fdcc7b7ce2`，JFC SHA-256=`dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。
- 环境仍为Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16、Intel i9-9880H / 16 logical CPU / 16GiB / macOS 26.7 x86_64，8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、Lane BLOCKING；采集前swap=`1,560MiB`、Pages throttled=0。artifact固定为`target/qualification/20260904T163853Z-risk-lane-boundary-256/`。不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线；不做长稳，只作部分验证。锁定后不再修改代码、场景、参数和门禁。

#### PV-100采集结果

- 最终risk主轮为`20,390.582 us/op`（1,000 users/scan），三个样本`24,914.560/19,883.869/16,373.317 us/op`，折合`49,042 users/s`。相对PV-98的`47,151.929 us/op`，扫描延迟下降`56.76%`、处理能力提升`131.24%`，通过锁定的至少20%门禁；相对最初`115,778.548 us/op`处理能力累计提升约`467.80%`。
- `-prof gc`轮为`27,012.364 us/op`、`894.973 MB/s`、`51,248,927.739 B/scan invocation`、12次GC/248ms。该分配口径包含Level.Invocation的snapshot restore/setup，不能解释为纯risk user每次分配；与PV-98同口径`51,357,038 B/op`相比下降约`0.21%`。
- 15.9MiB JFR的带profile分数为`24,403.235 us/op`，`DataLoss=0`。逐栈检查确认生产risk路径`executeUserRisk -> LaneMutationTask.await=0`，`executeRiskLane` completion await为`17,366`次，相对PV-98每用户await `334,361`次下降`94.81%`；risk snapshot、liquidation changed-index/query及position identity Lane回读仍为0。当前模型仍是单cursor按Lane顺序推进，owner每个Lane continuation等待一次completion，不宣称多Lane并行在途或owner完全非阻塞。
- JFR记录5次ZGC，heap最高从`4.4GiB`回收到`1.6GiB`，最长GC pause `0.0364ms`；最长已完成safepoint为`3.49ms`。最长VM operation为一次由ZGC worker发起、非safepoint的`HandshakeAllThreads 294.86ms`，会污染该短profile轮的墙钟尾部，因此JFR数值只用于归因。NMT除固定8GiB heap外，GC/Tracing/Metaspace/Code峰值约`79.1/35.5/30.6/26.3MiB`；socket I/O与异常事件均为0。CPU热点主要为`TreeMap.put`、Lane健康检查、TreeMap遍历与rolling hash；分配热点主要为exchange-core对象池初始化、TreeMap、Lane context ring及hash/setup，生产`processUser`占采样分配压力约`0.91%`。短JFR不证明无泄漏。
- 严格且仅`256 in-flight`、matcher=1主交易回归为`60,655.149 terminal business/Core ops/s`、`30,327.574 trades/s`，三个terminal样本`61,458.769/60,296.347/60,210.330 ops/s`，超过`43,807.580/s`门禁`38.46%`。accepted/terminal business和Core均为`60,655.149/s`，unfinished/rejected/error/timeout/producer-starvation/laneQuery均为0，期末backlog清零；资金、余额/冻结/持仓、订单终态、盘口和snapshot recovery由benchmark teardown门禁通过。首次交易命令误指向不存在的service模块`target/benchmarks.jar`，JVM未启动、无样本；随后使用预锁定且SHA一致的benchmark shaded JAR完成有效轮。
- HotSpot 25最终定向service回归`73/73`、最终代码上的扩展永续资金/生命周期/sectioned snapshot测试`60/60`、benchmark真实负载支撑测试`11/11`通过。最终代码diff SHA-256仍为`dc8696ff569cb21e95bb84890a6a7bb993f431d80035cee223493fa39605deab`，candidate shaded JAR SHA-256仍为`7ca932d370acd40ee2bdb11bfefec57e327164532da1542c551579fdcc7b7ce2`；`git diff --check`通过。采集后swap仍为`1,560MiB`，未观察到环境口径变化。
- artifact：`target/qualification/20260904T163853Z-risk-lane-boundary-256/`。`risk-main.json/risk-gc.json/risk-jfr.json/risk.jfr/risk-lane-stacks.txt/trading-main.json/risk-jfr-summary.txt/risk-jfr-hot-methods.txt/risk-jfr-allocation-by-site.txt/risk-jfr-gc.txt/risk-jfr-safepoints.txt/risk-jfr-vm-operations.txt/risk-jfr-native-memory.txt`的SHA-256依次为`6b7111546737b182c93e26167bc34d0dbd68a57ee223dbb3d77a50432e82293c/c2064d6c24cac0c0039436e34072127c95dd80af1ad072437bc3b24c6276f0dc/d2c7025f7efad17246c7fd5285ca8a72542f44f817ac71c68df0973b2a9025b1/d8f1c7f9b35764ee3cd2e54827c8f4221b3402072525629de6b9c1df87b4f390/7bfddb1fefc12dd89e9572ad720a028c8811887527671f158b36747bb158b828/4bdc730aa8ac11f8a265f52e5b59bcf70bbf215dcd8a58da0161f2018e515030/4e52c57227c697437e4c6a70906f65b7dfaac68062b41fe83bea63f154750be0/f0281de81b9ec261b6c709ed651a097f2c03c1b3f37289ddf2c3f5813b6dc43d/ccb8124c9b2d8465f59a9b431fcc597837e10d3025fd550449104303bb2bd33c/967bf7ced62ccecae0518da3f7bdb00cb9884063912b99bd9928d6265214cd9a/593145555b622cf2ef639d0eca209ecebbb33397a8c3b20e2c139d11ff7c8a43/5c4c39930e5786a700a3be07600129c06dc729c8f81e9ae77bf645f1193d84f2/9121f24176281ac22d18c051b2e4a5625dcc26269205682ecd1808de1823f820`。本轮未做长稳、延迟分段、API/Kafka/WebSocket/market-data或其他产品线验证，结论为永续受影响路径的部分性能验证；PostgreSQL、exporter和wallet未启动、未测试。

### 2026-09-04 17:25:07 +08:00 — `PV-20260904-256-101` — `采集前锁定（强平公平顺序与六场景）`

#### 采集前锁定

- 被测修改：同一结算资产的多个保险索赔按未决deficit比例计算确定性建议份额，最小单位余数按`triggerPriceSequence、userId、symbol、positionSide、liquidationId`分配；Core强制按该优先级逐项结算并重算份额，拒绝越序或篡改coverage，保险余额为0时允许转ADL。`CoreLiquidationWork`协议升至v4并携带`recommendedCoveredUnits`。排序仅位于insurance查询/结算边界，不进入matcher、Lane成交或risk scan热路径。
- 场景固定为`LINEAR_PERPETUAL`、matcher=1、4 Account Lane、thread=1、HotSpot JDK 25；六个JMH为：(1)256用户同标记价爆仓规划；(2)单用户256个reduce-only挂单的强平撤销；(3)每invocation一个含256项的原生强平batch，持续保持256个强平business item；(4)256个deficit共享不足保险基金；(5)保险分摊后真实执行ADL并清零deficit；(6)1,000活跃用户、256活跃symbol、20 items/batch的普通挂撤/成交与完整强平/保险/ADL混合负载，严格256 matching Core messages在途。做市状态由fixture持续提供；不含外部I/O。
- 无profiler主轮固定`fork=1,warmup=3x1s,measurement=5x1s,thread=1`；组件场景固定256 work items但没有客户端请求并发含义，混合场景显式`maxInFlight=256`。通过阈值预先锁定为：强平batch至少`3,000 terminal business items/s`；256用户规划、256撤单分别不高于`100ms/op`；保险不足查询、保险到ADL分别不高于`50ms/op`；混合负载至少`8,000 terminal business ops/s`。这些新场景没有同口径历史commit对照，基准commit为当前HEAD `5e892c1950ab38053a65bd62c64a58e1065c28b2`，绝对阈值只用于本轮可执行性和明显性能退化门禁，不作生产容量承诺。
- GC轮固定`fork=1,warmup=1x1s,measurement=1x2s,-prof gc`并报告allocation rate、B/op、GC次数/时间；JFR轮固定同六场景、`fork=0,warmup=1x1s,measurement=1x2s`，宿主JVM显式module opens、4GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary和JDK25 `profile` recording。JFR检查CPU/线程角色、分配、heap/GC、native、锁/park、safepoint/VM operation、JIT、I/O、异常与DataLoss；短轮不形成泄漏结论。
- 所有场景必须无异常退出；强平batch和混合负载的accepted/terminal business及Core messages必须相等，unfinished/rejected/error/timeout为0且期末backlog清零。teardown必须验证资金、余额/冻结/持仓、活动订单/强平终态和完成态snapshot恢复；保险到ADL必须为`COMPLETED/deficit=0`。强平batch主JMH分数为batch/s，只能用AuxCounters的terminal business operations作为items/s。
- 环境：Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16、Intel Core i9-9880H（8物理/16逻辑CPU）/16GiB、macOS 26.7 x86_64；4GiB ZGC。采集前swap=`1,528MiB`、Pages throttled=0。最终代码diff（含新增policy文件）SHA-256=`b4f202c9c7496dabeb1935563809a6f813f48ffca5dee8618864569258c93369`，shaded JAR SHA-256=`1e67b0bb20d440a173a597c6ef1e88ab416a81af715cc6da6545178c5b6b5bd9`。artifact固定为`target/qualification/20260904T092507Z-liquidation-fairness-256/`。
- 采集前HotSpot 25回归：protocol `4/4`、service强平/资金/risk `43/43`、资金核对`16/16`、benchmark真实场景`12/12`通过。PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线不启动、不测试；不执行长稳，因此最终最多是线性永续受影响路径的部分性能验证。锁定后不修改代码、场景、参数和门禁，失败或无效轮照实追加。

#### PV-101采集结果

- 首次主轮无效：命令使用`-jvmArgsAppend`覆盖了benchmark注解中的module exports，snapshot恢复阶段触发`IllegalAccessError`，未产生有效sample。原始`main.json`为空（SHA-256=`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`），`main.log` SHA-256=`6a67b0bb20d440a173a597c6ef1e88ab416a81af715cc6da6545178c5b6b5bd9`；该轮只作失败诊断，不纳入比较。
- 修正module exports后的无profiler主轮全部通过预设门禁。`liquidationBurst256`为`68.407 ± 22.167 batches/s`，即`17,512.258 ± 5,674.814 terminal business items/s`；accepted/terminal business与Core messages均相等，unfinished/rejected/error/timeout均为0。`liquidationWithTrading`为`0.814 invocation/s`、`17,930.674 ± 4,727.599 terminal business ops/s`、`2,089.279 ± 550.859 terminal Core messages/s`、`4,168.788 ± 1,099.142 trades/s`，`maxInFlight=256`且所有差值与unfinished/error为0。组件耗时：多用户爆仓规划`5,134.843 ± 4,128.797 us/op`，256挂单撤销`2,180.734 ± 1,701.132 us/op`，保险不足分配`2,011.672 ± 738.216 us/op`，保险转真实ADL`1,598.989 ± 1,638.805 us/op`。全部teardown资金/订单/持仓/强平终态及snapshot恢复通过。
- GC归因轮：强平batch为`12,803.703 terminal business items/s`、`262.300 MB/s`、`288,503,192 B/invocation`，折合约`1,126,966 B/business item`；混合负载为`15,393.963 terminal business ops/s`、`425.124 MB/s`、`702,956,444 B/invocation`，按该轮约22,023 business ops/invocation折合约`31,920 B/business op`，12次GC共106ms。其余场景allocation rate分别为保险不足`423.666 MB/s`、保险到ADL`404.063 MB/s`、挂单撤销`762.930 MB/s`、爆仓规划`1,562.025 MB/s`。这些B/op包含JMH `Level.Invocation` fixture重建、容器初始化和snapshot恢复校验，不能解释为纯交易命令分配；主要用于定位，不替代主轮吞吐。
- JFR轮维持正确性：强平batch`13,475.932 terminal business items/s`；混合负载`16,880.425 terminal business ops/s`、`1,966.904 terminal Core messages/s`、`3,924.611 trades/s`，所有accepted/terminal差值、unfinished、error均为0。CPU top包括`TreeMap.put 8.92%`、`CoreProbeState.progressPlaceAdmissions 4.03%`、`LaneCommandContextRing`初始化`2.50%`、`ByteArrayOutputStream.write 2.18%`；保险codec writer为`0.87%`。分配top为fixture/容器初始化：`ObjectsPool` 18%、`TreeMap.put` 10.87%、`LongObjectHashMap` table 8.75%、`LaneCommandContextRing` 8.39%，没有显示新增保险排序进入matcher/Lane热路径。
- JFR共919个execution samples、6,580个allocation samples、602个park、17次ZGC，`DataLoss=0`。最长STW GC pause为`0.0534 ms`；4次ZGC allocation stall平均`97.17 ms`、最大`143.014 ms`，最长major collection约`3.001 s`且为并发阶段。4GiB profile宿主在六场景fixture反复重建下结束heap used约3.784GiB，因此该profile轮存在heap压力，只用于归因；不能据此证明泄漏或反向认定业务泄漏。VM operation最大约`0.526 ms`。JIT compilation 45次、deoptimization 386次；异常主要来自JMH/JVM反射初始化和worker teardown中断，没有`CoreStateRejected`、保险或ADL业务失败。
- NMT显示ZGC Java Heap地址空间reserved约68GiB、committed 4GiB；Direct/Mapped ByteBuffer四次采样均为count/capacity/used=0。`FileWrite=0`、socket read/write=0；7次`FileRead`来自进程pipe采集线程，不在owner。owner/matcher未出现同步数据库、网络或文件写I/O。采集后swap为`1,496MiB`、Pages throttled=0。
- 混合负载JFR业务延迟按类型记录三段直方图并启用coordinated-omission correction。代表值：PLACE_ORDER accepted→terminal p50=`5.831 ms`、p99=`7.750 ms`；CANCEL_ORDER p50=`5.294 ms`、p99=`7.821 ms`；ORDER_BATCH p50=`111.4 ms`、p99=`286.8 ms`；LIQUIDATION仅2个样本，accepted→terminal p50=`0.637 us`、p99=`0.244 ms`。入口→accepted的PLACE_ORDER p50约`320 ms`、CANCEL_ORDER p50约`580 ms`，是100k/s目标到达率超过该场景约17.9k terminal business ops/s容量后形成的客户端/入口排队，不代表可持续容量下的SLA；原始事件同时保留p90/p95/p99.9/max。该轮不是独立尾延迟验收。
- 原始artifact目录：`target/qualification/20260904T092507Z-liquidation-fairness-256/`。关键SHA-256：`main-valid.json`=`328abf41ada7a3fbf9c5c22a8655075fc89ae57a7c1e8210f6700d4ed168a48d`、`main-valid.log`=`7a4a5f3fe47911e4ad751f4c50d786dd67f121459d37fcdc254bbe1e67ac872e`、`gc.json`=`0165a18236cc5d3a2ed0a82d006cad760f55e06ae94c1643d19c814da848d9f8`、`gc.log`=`d2757035d95780a0c22f1f422e4298f9be21f0f092a9ac54e732812c51506f9e`、`jfr.json`=`5d5ebd3917264d21d85d1044da7678f2ef64c648b4a571e07f1fc02ea6533b7c`、`jfr.log`=`800a537375428553c2c277db89bcf0df7cad8d3b92c8d1ae0162cf7ee8e2d72b`、`liquidation.jfr`=`607014736698a42832d124f8a2872f61fe91cbb4b00dc3e41c09cb9c736ed3c1`、`jfr-business-latency.tsv`=`f02e61f456530f931ba2d9f7de6c0e94c1c50ccd5d4a0c911f627185bebb841f`。
- 结论：六个新增强平场景均通过预先锁定的吞吐/耗时与正确性门禁；保险不足时按结算资产内确定性优先级和比例份额执行，余额耗尽可真实进入ADL，越序或篡改coverage会被拒绝。当前仅完成线性永续本地短时部分性能验证；未覆盖长稳泄漏、API/Kafka/WebSocket/market-data、外部Aeron Cluster部署、PostgreSQL/exporter/wallet及其他五产品线，不能作为生产容量或完整性能验收结论。

### 2026-09-04 17:58:49 +08:00 — `PV-20260904-256-102` — `采集前锁定（强平混合负载口径修正）`

#### 采集前锁定

- 目的：修正PV-101把每轮256个symbol全量重业务与八阶段全量drain混为容量结论的问题。`liquidationWithTrading`固定1,000用户、256 active/listed symbols、20 items/batch、1 HFT round、32 lifecycle symbols/run、matcher=1、4 Account Lane；每个撮合依赖阶段内部最多且应达到256 matching Core messages在途，阶段间仅保留订单依赖和全局sequence要求的提交fence。每轮必须真实完成挂单、撤单、成交、trigger、funding/mark/risk以及一个`liquidation→insurance→ADL`闭环。
- Core当前只允许matching command越过未完成matching sequence；尝试取消依赖fence会分别触发`snapshot projection batch is already active`及matcher prefix divergence，均由测试在采集前发现并撤销，不进入候选。不得放宽sequence、matcher evidence或资金安全校验换取吞吐。本轮分别报告`terminalTradingOperations`和`terminalLifecycleOperations`，两者之和必须等于terminal business operations；accepted/terminal business和Core必须闭合，unfinished/rejected/error/timeout为0，期末backlog为0，最大matching窗口=256，资金、订单、持仓、强平终态和snapshot恢复通过。
- 无profiler主轮固定`fork=1,warmup=3x3s,measurement=3x5s,thread=1`、HotSpot JDK 25、8GiB ZGC、严格256 in-flight；混合负载门禁为至少`30,000 terminal business ops/s`且lifecycle operations必须为正。并用完全相同JVM/JMH参数复跑`saturatedMatchingWorkload`（10,000用户、512 symbols、16,384 PLACE_ORDER/invocation、50% maker/50% taker、100,000 offered），对照PV-100 `60,655.149/s`，不得低于其90%即`54,589.634/s`。
- GC轮固定`fork=1,warmup=1x3s,measurement=1x5s,-prof gc`；JFR轮固定混合场景`fork=0,warmup=1x3s,measurement=1x10s`，8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary和JDK25 profile，检查CPU、线程、分配、GC/heap/native、park/锁、safepoint/VM operation、JIT、I/O、异常与DataLoss。profiler结果只归因，不替代无profiler主轮；短轮不证明无泄漏。
- 环境：Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16、MacBookPro16,1 / Intel i9-9880H / 8物理16逻辑CPU / 16GiB / macOS 26.7 x86_64；采集前Pages throttled=0。候选diff SHA-256=`ec53b18b2a315bf3dc8aee7432c3629d440105a00542fc7721f6e610a32f7f31`，shaded JAR SHA-256=`9c923a4cd84124ddada07adb8eded34708c35fbf22dff1e0a0d529bc0227dadd`，artifact固定为`target/qualification/20260904T095849Z-liquidation-mixed-256/`。
- 采集前HotSpot 25 benchmark真实路径测试`12/12`通过；不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线。锁定后不修改代码、场景、参数或门禁；失败和无效轮如实追加。

#### PV-102采集结果（失败候选）

- 无profiler混合主轮为`14,655.415 terminal business ops/s`，其中trading=`14,566.003/s`、lifecycle=`89.412/s`、Core messages=`1,476.650/s`、trades=`3,468.096/s`；三个business样本为`15,355.176/13,898.628/14,712.442/s`。accepted/terminal闭合且unfinished/error为0，但低于30,000/s门禁。纯撮合同轮为`50,332.993/s`，三个样本`53,021.637/56,401.118/41,576.224/s`，仍是5万级但平均低于54,589.634/s门禁且第三样本异常偏低。
- GC轮混合负载为`20,209.052 business ops/s`、`543.577 MB/s`、`757,340,726 B/invocation`、12次GC/198ms；JFR轮为`17,436.078 business ops/s`，DataLoss=0。JFR CPU top为`TreeMap.put 19.27%`，allocation pressure为43.89%，但逐栈确认主要来自Trial/Invocation的template物化、snapshot restore和索引重建，不能归因于测量区保险逻辑；生产侧可见热点包括`awaitMatcherSettlementBatch 3.23%`、`progressPlaceAdmissions 2.34%`、`awaitMatchingResult 1.45%`。保险分配未进入top热点。
- 尝试用通用`HftSymbolFlow`取消八阶段fence没有性能收益，且此前两个候选分别被`snapshot projection batch is already active`和matcher prefix divergence安全校验拒绝；未放宽任何生产校验。该调度器在本轮后撤销，因此PV-102结果只作为失败诊断，不作为最终候选验收。artifact=`target/qualification/20260904T095849Z-liquidation-mixed-256/`；main/gc/JFR-json/JFR SHA-256分别为`f94ea51f780420934202e2c8627ca7da90b740b8d27730aa4cffcc2287458f13/5eb6de43f2c03c206ae22670afd47c2c8b14657b8c66fbfd2c8d94186fcb30cb/745fd54bb4ddd1bfea5a223b5308eb8ec465d3e643aedffcbf4311ebcca5656a/e41a39bd487a131cb3b2c206e0e6db8b5162d83b73615295511ab359147aac63`。

### 2026-09-04 18:05:08 +08:00 — `PV-20260904-256-103` — `采集前锁定（固定比例强平混合负载最终复测）`

#### 采集前锁定

- 场景、JDK/JVM、机器、正确性门禁和不测试范围沿用PV-102。最终候选撤销无收益的通用flow调度器，恢复既有低分配阶段局部循环；仅保留32/256 lifecycle symbols固定比例、严格256 matching in-flight和trading/lifecycle分项计数。生产Core代码相对`bca853d5`不变。
- 无profiler混合主轮仍固定`fork=1,warmup=3x3s,measurement=3x5s,thread=1`、8GiB ZGC；由于PV-102证明当前生产sequence协议要求阶段fence，最终门禁改为不得低于PV-101原始混合场景`17,930.674/s`的90%，即`16,137.607 terminal business ops/s`，且trading/lifecycle均为正、最大窗口256及全部正确性门禁闭合。纯撮合紧邻复测仍使用相同参数，以PV-100 `60,655.149/s`为参考并如实报告，不用PV-102高方差轮修改门禁。
- GC与JFR参数沿用PV-102，仅在最终无profiler候选通过后采集。最终候选diff SHA-256=`5640d58a40e1eeb9fcb976a6fc3052f569947914ec3d067463c3624af93f2361`，shaded JAR SHA-256=`12020a0e41503869cb4c1274980ad5a048b4969423b3de1d1557805ed61fe0e6`，artifact固定为`target/qualification/20260904T100508Z-liquidation-mixed-final-256/`。HotSpot 25 benchmark真实路径测试`12/12`通过；锁定后不再修改代码、参数、场景或门禁。

#### PV-103采集结果

- 无profiler最终混合主轮为`15,253.307 terminal business ops/s`，其中trading=`15,160.248/s`、lifecycle=`93.060/s`、Core messages=`1,536.893/s`、trades=`3,609.583/s`；三个business样本为`18,265.216/14,165.032/13,329.674/s`。accepted/terminal business及Core分别相等，unfinished/rejected/error/timeout为0，资金、订单、持仓、强平终态和snapshot恢复通过，但低于`16,137.607/s`门禁，因此性能门禁失败。
- 紧邻纯撮合为`51,678.973 terminal business/Core ops/s`、`25,839.487 trades/s`，三个样本`50,093.769/56,733.688/48,209.464/s`。它相对PV-100 `60,655.149/s`低`14.80%`，未通过严格-10%门禁，但稳定处于5万级；本轮没有证据显示保险公平代码把普通PLACE_ORDER路径降到1万级。
- lifecycle占混合terminal business operations约`0.61%`；从PV-101每轮256个重业务symbol降到固定32个后，混合吞吐没有提高。结合PV-102 JFR，当前约1.5万/s主要由batch挂单、batch撤单、成交及不同操作类型之间必须等待matcher prefix与全局sequence闭合的阶段fence决定，而不是risk、保险或ADL计算本身。取消fence的两个候选均被一致性校验拒绝，说明若要真正让不同matching类型和非matching控制流同时在途，需要修改生产sequence admission/每sequence matcher evidence与snapshot projection上下文；不能只改JMH驱动器。
- 因最终无profiler主轮失败，按预锁条件未执行PV-103 GC/JFR；PV-102失败候选的GC/JFR只作上述归因。artifact=`target/qualification/20260904T100508Z-liquidation-mixed-final-256/`，main JSON/log SHA-256分别为`99c1a0330ceb16e83f907d453f2d3b9fa230e2c0184510a903bd054b558d830c/8b0c022eac709afd277b8b9f7e00dcc1ef6d01df7f0ff795d67b32b1cf52dabf`。结论为功能与口径修正通过、混合性能门禁失败；不声明生产容量或完整性能验收。

### 2026-09-04 20:17:29 +08:00 — `PV-20260904-256-104` — `采集前锁定（batch OI O(1) 与 mixed 计时边界）`

#### 采集前锁定

- 被测修改仅包含两项：`batchOpenInterestSteps`不再为每个batch item调用`OpenInterestIndex.totals()`复制全部symbol到新`TreeMap`，改为按规范化symbol分别读取long/short quantity，保持既有batch内position增量修正和风险语义；`liquidationWithTrading` fixture从每invocation恢复、校验、关闭改为每iteration一次，使snapshot恢复、全状态物化、资金校验和线程关闭不进入吞吐计时。每个iteration的首次run仍真实执行一次`liquidation→insurance→ADL`闭环，后续run持续执行挂单、撤单、成交、trigger、funding、mark和risk scan；iteration teardown验证资金、订单、持仓、强平终态和snapshot可恢复性。
- 主混合场景固定`LINEAR_PERPETUAL`、matcher=1、4 Account Lane、1,000 active users、256 active/listed symbols、1 HFT round、20 items/batch、32 lifecycle symbols/run、严格且仅256 matching Core messages in-flight、thread=1、100,000 offered business ops/s；做市fixture持续运行，不含外部I/O。无profiler主轮固定`fork=1,warmup=3x3s,measurement=3x5s`、8GiB ZGC、AlwaysPreTouch、DisableExplicitGC。相对PV-103 `15,253.307 terminal business ops/s`，通过门禁锁定为至少`25,000 terminal business ops/s`；必须同时报告trading/lifecycle、Core messages、trades、三段分类延迟、最大/期末backlog，且accepted/terminal business及Core分别相等，unfinished/rejected/error/timeout为0，最大窗口不超过256、期末backlog为0。
- 紧邻运行单matcher纯撮合回归：10,000 users、512 active/listed symbols、16,384 PLACE_ORDER/invocation、50% maker/50% taker、100,000 offered、严格256 in-flight，其余JVM/JMH参数与主轮相同；不得低于PV-103 `51,678.973/s`的90%，即`46,511.076 terminal business/Core ops/s`。该场景仅用于确认O(1) OI改动没有回归普通单路径，不与混合batch场景合并为同一容量结论。
- GC轮固定主混合场景`fork=1,warmup=1x3s,measurement=1x5s,-prof gc`，报告allocation rate、B/op、GC次数和时间。JFR轮固定主混合场景`fork=0,warmup=1x3s,measurement=1x10s`，使用相同8GiB ZGC、NMT summary与`owner-commit-profile.jfc`；检查CPU/线程角色、`OpenInterestIndex.totals`/`TreeMap.put`是否退出batch热路径、分配、heap/GC/native、锁/park、safepoint/VM operation、JIT、I/O、异常与DataLoss。profiler轮只用于归因，不替代无profiler主轮；短轮不证明无泄漏。
- 环境锁定为Oracle GraalVM Java HotSpot 25.0.1、Maven 3.9.16、MacBookPro16,1 / Intel i9-9880H / 8物理16逻辑CPU / 16GiB / macOS 26.7 x86_64；采集前swap=`768.75MiB`、Pages throttled=0。被测HEAD=`c9f99f3a9586a4275489429f7120738165642a59`，排除本性能记录的代码与测试diff SHA-256=`184f97ae257246c4f2aea218672ada470192b4ec20e8c1db28aab6e1912fb8d5`，shaded JAR SHA-256=`c8d09e1bf967353135f0e88e2fd16f8eaee336ea4cb0dc491caf417ff07cce4a`，JFC SHA-256=`dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。artifact固定为`target/qualification/20260904T201729Z-oi-mixed-timing-256/`。
- 采集前HotSpot 25验证：`PositionUserIndexTest` 3/3、`LinearPerpetualBenchmarkSupportTest` 12/12通过，benchmark shaded JAR构建成功，`git diff --check`通过。PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线不启动、不测试；不执行长稳，因此最终最多形成线性永续受影响路径的部分性能验证。锁定后不再修改代码、场景、参数和门禁，失败或无效轮如实追加。

#### PV-104采集结果

- 首个合并命令错误地把两个benchmark同名的`activeUsers=1000,10000`参数展开为笛卡尔组合，额外执行了非锁定组合；该轮`main.json`只作无效诊断，不作为结论。随后拆成两个独立命令，以锁定的精确参数和全新fork完成有效主轮。
- 有效mixed主轮为`56,894.969 terminal business ops/s`，三个样本`52,824.912/59,008.248/58,851.746/s`；其中trading=`56,557.547/s`、lifecycle=`337.421/s`、Core messages=`5,723.854/s`、trades=`13,466.083/s`。相对PV-103 `15,253.307/s`提升`273.00%`，超过锁定的`25,000/s`门禁。accepted/terminal business均为`56,894.969/s`，accepted/terminal Core均为`5,723.854/s`，unfinished/rejected/error/timeout均为0；确定性驱动在每个阶段提交第256个matching Core message后立即drain，故最大backlog为256，每阶段及每run结束backlog为0。iteration teardown资金、订单、持仓、强平终态和snapshot恢复门禁通过。
- 精确纯撮合回归为`54,790.352 terminal business/Core ops/s`、`27,395.176 trades/s`，三个样本`54,015.285/59,430.358/50,925.414/s`；较PV-103 `51,678.973/s`提升`6.02%`，通过`46,511.076/s`门禁。accepted/terminal闭合，unfinished/error/rejected/timeout和producer starvation均为0，期末backlog清零，说明O(1) OI读取未回归普通PLACE_ORDER路径。
- GC归因轮为`44,784.050 terminal business ops/s`、`476.673 MB/s`、`278,762,547.636 B/invocation`、12次GC/351ms；按本轮约21,635 business operations/invocation折合约`12,885 B/business op`。相对PV-102包含Level.Invocation恢复的`757,340,726 B/invocation`下降`63.19%`；由于本轮已移出恢复/校验计时，该比例只用于解释被删除的fixture及OI分配，不作为纯生产命令逐项分配对照。
- JFR带profile吞吐为`40,807.735 terminal business ops/s`、Core messages=`4,105.277/s`、trades=`9,658.541/s`，所有终态和错误门禁闭合。532个execution samples中benchmark worker/matcher/四Lane分别为`393/6/130`；热点为latency recorder `7.14%`、`awaitMatcherSettlementBatch 6.58%`、ThreadLocal miss `4.89%`、interruption check `4.32%`、`progressPlaceAdmissions 3.76%`。`OpenInterestIndex.totals()`在execution及allocation stack中均为0；`TreeMap.put`从PV-102 CPU `19.27%`、allocation pressure `43.89%`降至`1.88%/2.70%`，确认batch OI全表复制已退出热路径。
- JFR业务延迟仍按业务类型记录三段并修正coordinated omission。稳定后代表值：ORDER_BATCH accepted→terminal p50约`38.6–52.7ms`、p99约`76.2–154.4ms`，较PV-102的p50约`118–198ms`明显下降；PLACE_ORDER p50约`3.89–4.53ms`，CANCEL_ORDER p50约`3.08–3.52ms`；RISK_SCAN和FUNDING accepted→terminal p50约`0.14–0.19us`。入口等待仍因100,000 offered business ops/s高于带profile处理能力及八阶段fence而达到数十至数百毫秒，不能作为可持续到达率下的生产SLA。
- 19秒JFR共4次ZGC，最大GC前heap `2.4GiB`、对应GC后`584MiB`，最长pause `0.0712ms`；最长已完成safepoint `0.577ms`。VM operation最长为`HandshakeAllThreads 84.4ms`；最长JIT compilation为`executeHftBurstsPipelined`约`695ms`，说明短profile窗口仍受编译影响，profiler绝对吞吐不与无profiler主轮比较。`DataLoss=0`、allocation requiring GC=0、direct buffer count/capacity/used全为0；NMT除固定8GiB heap外GC/Tracing/Metaspace/Code峰值约`47.9/34.0/32.5/32.5MiB`。无socket I/O；文件读为JAR/JDK配置，文件写为JFR/JMH结果及JFFI临时库，未见owner同步数据库、网络或业务文件I/O。异常均来自反射/JNR/JMH初始化，没有Core业务拒绝异常。短JFR不证明无泄漏。
- 有效artifact位于`target/qualification/20260904T201729Z-oi-mixed-timing-256/`。`main-mixed.json/main-saturation.json/gc.json/jfr.json/mixed.jfr/jfr-summary.txt/jfr-hot-methods.txt/jfr-allocation-by-site.txt/jfr-gc.txt/jfr-safepoints.txt/jfr-vm-operations.txt/jfr-native-memory.txt/jfr-contention.txt/jfr-direct-buffer.txt/jfr-exceptions.txt/jfr-file-reads.txt/jfr-file-writes.txt/jfr-thread-cpu.txt/jfr-jit.txt`的SHA-256依次为`a11f7137c60c1a38b650659492f5699a1f9331f847bcc48907297174481f903b/307145f73b1ee977ad955330fafec615ee558587920c11e0be1c2b178a1fff6d/ffe3347e995345430f1d94803914f8ae10be3bd675baf94b028de0b3e2f57cb9/2b706f022c7ecb6d0d6b30c72b4426079282ec1fef38c1963e7e8267333ec828/ce1d99965a7721303f253d5c5e3674ec3ae03bf2b129b531083be6e0b1e00198/e5b65bb1adc573a4691c109004aec4462ef07234dd61728a870062836417ce08/69a9e89eaeb2737181da761432ff1d33a4d1185fbfe441f95d2e630a98affe83/0763d3942719d28fa71a15cfe17dd6583ef31ae93c13cdac133cb2810eb52b7b/49f7f7c19c4123445a5cacdc78a293e2c84973059cf77895ca641f8ffec65122/8c15a74a9a46dbc4e1ffbbac51e1131b21b8766c585513272d483735edc56051/e5b65bb1adc573a4691c109004aec4462ef07234dd61728a870062836417ce08/7dd29665d7379afea36398237cf33f0f951829cef2441cacf503ba0ad4001863/628218f684db0a07dd2f3914fc50793d64f4c35763c975aa8d19b8165e8cf077/01f44e5a308532dc9079459120136b796efff07d67adfab9abc5b5bb77ccb9fa/d2f1dc662e9130c8011eabf0b1a14f17931e7cdbb838f7b3167c8fc14670af20/6c03b2bb5fdae88d495ca87e641145dc6ed7a4aafe4ce9dd5cb3f18d1983a1c7/9a49411bea12e7703b5992ada663a0b424dcab5cc532ed46d415453335a1bef4/ea0118593c36d4a1c329a5dfc4708c4b17864af36b12801089bd328372228f1f/ea2fa7d9aa582694761e6b183a3dbf5cb3f18d1983a1c7`。采集后swap=`736.75MiB`、Pages throttled=0。本轮通过锁定的mixed及纯撮合门禁，结论为线性永续受影响路径部分性能验证；未执行长稳、外部Aeron Cluster、PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线验证，不声明生产容量或无泄漏。
- SHA-256记录更正：上一行从`jfr-vm-operations.txt`起的正确值依次为`c5f7a71d7e2deda9764f1b3324430bddf967ee7748a3ab3f02d17957f71693b1/7dd29665d7379afea36398237cf33f0f951829cef2441cacf503ba0ad4001863/628218f684db0a07dd2f3914fc50793d64f4c35763c975aa8d19b8165e8cf077/01f44e5a308532dc9079459120136b796efff07d67adfab9abc5b5bb77ccb9fa/d2f1dc662e9130c8011eabf0b1a14f17931e7cdbb838f7b3167c8fc14670af20/6c03b2bb5fdae88d495ca87e641145dc6ed7a4aafe4ce9dd5cb3f18d1983a1c7/9a49411bea12e7703b5992ada663a0b424dcab5cc532ed46d415453335a1bef4/ea0118593c36d4a1c329a5dfc4708c4b17864af36b12801089bd328372228f1f/ea2fa7d9aa582694761e6b183a3dbf5cb5af26961f68dd35ec8e03d4a2bd5f52`；以前一行本更正为准。

### 2026-09-04 21:07:33 +08:00 — `PV-20260904-256-105` — `采集前锁定（owner/Lane 与 batch 七项优化）`

#### 采集前锁定

- 被测修改：owner线程`assertOwner`先走线程身份快路径，Lane线程才读取ThreadLocal；Account Lane维护用户活动订单ID primitive索引，trigger/reduce-only容量不再扫描Lane全订单；batch OI直接读取已规范化symbol；batch changed user/order和deferred order ID改为primitive容器并删除逐item stream/LinkedHashSet/List物化；place admission由ready-shard位图推进；永续order batch matcher settlement改为Lane完成后按sequence-ready恢复的continuation，owner不再调用`awaitMatcherSettlementBatch`原地等待；等待循环只在进入park阶段后检查中断。HFT阶段之间仍保留真实订单依赖与全局sequence所需fence；尝试删除后被`snapshot projection batch is already active`一致性门禁拒绝，不以放宽安全校验换吞吐。
- 主混合场景与PV-104完全同口径：仅`LINEAR_PERPETUAL`、matcher=1、4 Account Lane、1,000 active users、256 active/listed symbols、20 items/batch、1 HFT round、32 lifecycle symbols/run、严格且仅256 matching Core messages in-flight、1 JMH线程、100,000 offered business ops/s、open-loop/coordinated-omission corrected，做市持续运行。无profiler固定`fork=1,warmup=3x3s,measurement=3x5s`；门禁为不低于PV-104 `56,894.969/s`的90%，即`51,205.472 terminal business ops/s`，且trading/lifecycle均为正，accepted/terminal business和Core闭合，unfinished/rejected/error/timeout为0，最大backlog不超过256、期末为0，资金、订单、持仓、强平终态和snapshot恢复通过。
- 纯撮合回归与PV-104同口径：10,000 users、512 active/listed symbols、16,384 PLACE_ORDER/invocation、50% maker GTC+50% taker IOC、matcher=1、4 Lane、100,000 offered、严格256 in-flight；无profiler参数同主轮，门禁不低于PV-104 `54,790.352/s`的90%，即`49,311.317 terminal business/Core ops/s`，其余闭合和正确性门禁相同。
- GC轮固定mixed `fork=1,warmup=1x3s,measurement=1x5s,-prof gc`；JFR轮固定mixed `fork=0,warmup=2x5s,measurement=1x15s`，8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、`owner-commit-profile.jfc`。重点门禁为线性永续batch栈中`awaitMatcherSettlementBatch=0`、owner主路径`ThreadLocalMap.getEntryAfterMiss -> assertOwner=0`、`ordersForUser/LongObjectHashMap.forEachValue=0`，并报告CPU/线程、分配、GC/heap/native、锁/park、safepoint/VM operation、JIT、I/O、异常和DataLoss。
- 长稳固定同mixed场景、严格256 in-flight、`fork=1,warmup=1x10s,measurement=1x600s`；记录多轮GC后heap/live趋势、线程数、Direct/native、文件描述符、backlog、错误和资金/snapshot门禁。短JFR只用于热点，不以其声明无泄漏。
- 环境：Oracle GraalVM Java HotSpot 25.0.1、Maven3.9.16、MacBookPro16,1 / Intel i9-9880H / 8物理16逻辑CPU / 16GiB / macOS26.7 x86_64；8GiB ZGC。对照commit=`40938e2d`，被测HEAD仍为`40938e2d`加工作区候选，排除本性能记录的候选diff SHA-256=`070dedd7fe64dc347e3c31ab6a04b86f128c66816a90fa7c63e878dba23f86b7`；采集前swap=`736.75MiB`。artifact固定为`target/qualification/20260904T210733Z-owner-lane-batch-256/`。不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线。锁定后不修改候选代码、场景、参数或门禁；失败与无效轮如实追加。

#### PV-105采集结果（owner快路径门禁部分失败）

- 无profiler mixed主轮为`73,441.703 terminal business ops/s`，三个样本`72,503.269/73,697.642/74,124.197/s`；其中trading=`73,006.372/s`、lifecycle=`435.331/s`、Core messages=`7,388.319/s`、trades=`17,382.469/s`。相对PV-104 `56,894.969/s`提升`29.09%`。纯撮合为`56,322.725 terminal business/Core ops/s`、`28,161.362 trades/s`，三个样本`56,419.978/56,163.843/56,384.353/s`，相对PV-104提升`2.80%`。两场景accepted/terminal闭合，unfinished/rejected/error/timeout为0，最大窗口256、期末backlog为0，teardown资金、订单、持仓、强平终态与snapshot恢复通过。
- GC归因轮为`70,448.473 terminal business ops/s`、`499.343 MB/s`、`187,784,970.824 B/JMH invocation`、28次GC/235ms；按该轮每invocation约21,632个business operations折合约`8,680.79 B/business op`，相对PV-104约`12,885 B/business op`下降`32.63%`。该口径仍包含每invocation固定工作与校验，只用于同场景对照。
- JFR带profile吞吐为`45,264.058 terminal business ops/s`，`DataLoss=0`。生产热点中`progressPlaceAdmissions=6.21%`、`awaitMatchingResult=5.16%`、`TreeMap.put=3.16%`、matching completion约`2.21%`、symbol校验约`1.68%`；`awaitMatcherSettlementBatch=0`，旧`ordersForUser/LongObjectHashMap.forEachValue=0`，证明同步batch settlement等待和用户订单全Lane扫描已退出该真实路径。
- owner快路径的严格JFR门禁未完全通过：`ThreadLocalMap.getEntryAfterMiss -> TradingRuntimeState.assertOwner`仍有5个样本，另有owner入口普通`ThreadLocal.getEntry`样本。原因是首次owner绑定时`owner==null`仍先查询Lane ThreadLocal；该轮因此标记为部分失败，不执行锁定的10分钟长稳。代码随后只修正首次绑定分支，并以新候选PV-106重新完整采集，PV-105不作为最终验收结论。
- JFR共5次ZGC，heap最高由`5.8GiB`回收到`1.5GiB`，最长STW pause `0.0326ms`；短JFR不证明无泄漏。关键artifact SHA-256：`main-mixed.json`=`69f87222232c5b129007c002d2ab7473849ca22fb9419475288858a3b82e4714`、`main-saturation.json`=`5764b27dee5bb65bee9c947192d456f52e33bf5b91c3c1e327ea25d931a1c256`、`gc.json`=`6c5eb92ae0b2f330536a5680e763de162dcc83ae8e704c09e2f2d4a68e3a8059`、`jfr.json`=`e11b45c463d90130c8b98c5c66711ea868aaeb26926101aa55e9c5aee9740d1f`、`mixed.jfr`=`4cd6d2d5975ea794f52e3c3777e889a5ab11787841a4a2af4461d04979d97bba`。

### 2026-09-04 21:19:58 +08:00 — `PV-20260904-256-106` — `采集前锁定（owner首次绑定最终候选）`

#### 采集前锁定

- 候选在PV-105七项实现上只调整`TradingRuntimeState.assertOwner`：`owner==current`直接返回；`owner==null`由当前线程直接完成首次绑定；仅已绑定且非owner线程读取Lane scope ThreadLocal。语义等价于原首次`bindOwner`，目标是消除owner热路径的ThreadLocal读取。其余七项实现、必要sequence/snapshot fence及业务场景不变。
- mixed和纯撮合主轮、GC轮与PV-105完全同口径：mixed为线性永续、matcher=1、4 Lane、1,000 users、256 symbols、20 items/batch、32 lifecycle symbols/run、严格256 matching Core messages in-flight；纯撮合为10,000 users、512 symbols、16,384 PLACE_ORDER/invocation、严格256 in-flight。主轮`fork=1,warmup=3x3s,measurement=3x5s,thread=1`；门禁分别为`51,205.472 terminal business ops/s`和`49,311.317 terminal business/Core ops/s`。GC为mixed `fork=1,warmup=1x3s,measurement=1x5s,-prof gc`。
- JFR固定mixed `fork=0,warmup=2x5s,measurement=1x15s`，宿主8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary和相同`owner-commit-profile.jfc`。必须满足`awaitMatcherSettlementBatch=0`、owner线程`ThreadLocalMap.getEntryAfterMiss -> TradingRuntimeState.assertOwner=0`、旧`ordersForUser/LongObjectHashMap.forEachValue=0`；同时检查CPU、分配、heap/GC/native、线程/锁、safepoint/VM operation、JIT、I/O、异常与DataLoss。
- 若上述短轮全部通过，再执行同mixed场景10分钟长稳：严格256 in-flight，`fork=0,warmup=1x10s,measurement=1x600s`，宿主8GiB ZGC、NMT summary、JFR `default` recording、maxsize 1GiB。长稳必须accepted/terminal闭合、unfinished/rejected/error/timeout为0、期末backlog为0并通过资金/订单/持仓/强平终态和snapshot恢复；比较多轮GC后heap、线程、Direct/native、FD趋势。长稳JFR只用于状态增长与阻塞检查，不与无profiler吞吐横向比较。
- 环境：Oracle GraalVM Java HotSpot 25.0.1（HotSpot）、Maven3.9.16、MacBookPro16,1 / Intel i9-9880H / 8物理16逻辑CPU / 16GiB / macOS26.7 x86_64；采集前swap=`736.75MiB`。对照commit=`40938e2d`；排除本性能记录的候选diff SHA-256=`12ba9fe168293d13f3b377c32fb7b3e39c739de774996510081d37c4796b6174`，shaded JAR SHA-256=`fe25d8016715c610ed1971147c8bfcece612e7ac1f9fa36824e5fdd1d43bd199`，JFC SHA-256=`dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。artifact固定为`target/qualification/20260904T211958Z-owner-lane-batch-final-256/`。
- 采集前HotSpot 25定向测试65/65通过，shaded JAR构建成功，`git diff --check`通过。不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线。锁定后不修改代码、场景、参数或门禁，失败/无效轮照实追加。

#### PV-106采集结果（长稳失败）

- 无profiler mixed三个样本为`70,015.993/74,739.480/74,305.448 terminal business ops/s`，平均约`73,020.307/s`；纯撮合三个样本为`57,752.960/57,806.454/57,573.974 terminal business/Core ops/s`，平均约`57,711.129/s`，均通过短时吞吐门禁。GC轮为`69,178.307 terminal business ops/s`、`497.359 MB/s`、`189,060,967.529 B/invocation`、24次GC/227ms。
- 首次JFR命令遗漏宿主module export，在fixture开始前以`IllegalAccessError`退出，保留为`jfr-invalid-module.*`无效诊断，不含业务样本；补齐参数后的有效短JFR为`47,438.645 terminal business ops/s`、`DataLoss=0`。`awaitMatcherSettlementBatch`、旧`ordersForUser`和`OpenInterestIndex.totals`样本均为0；`ThreadLocalMap.getEntryAfterMiss -> TradingRuntimeState.assertOwner`的5个样本全部来自Account Lane，owner/JMH worker为0，短JFR热点门禁通过。
- 10分钟长稳在运行`7m34s`时因`OutOfMemoryError: Java heap space`失败，栈顶虽在`ActiveOrderIndex.add`扩容，但2GiB复现轮的强制GC histogram确认主要存活对象为约`1,843,294 OrderRuntime`、`1,843,294 ReservationRuntime`、同量client identity/entry和`1,853,043 LongHashSet`；CoreOrderState仅约5,213。JFR的GC后heap从`52MiB`持续增长到接近`8GiB`，线程约46、FD约34保持稳定，证明是Lane终态订单/预留/identity未回收，不是ActiveOrderIndex查询对象或短时分配波峰。
- 根因是order batch使用`commitSequence=0`的provisional matcher settlement，以及batch cancel直接调用同步`executeUserSettlement`：前者没有携带Lane terminal changes，后者没有进入`prepareLaneTerminal`，因此业务快照与资金校验仍正确，但Lane内部终态实体持续保留。PV-106长稳、无泄漏和最终验收门禁失败；artifact为`target/qualification/20260904T211958Z-owner-lane-batch-final-256/`，后续修复必须新建PV-107，不能复用本轮短时通过结论。

### 2026-09-04 21:56:33 +08:00 — `PV-20260904-256-107` — `采集前锁定（batch Lane终态回收最终候选）`

#### 采集前锁定

- 在PV-106七项优化基础上修复长稳暴露的Lane生命周期问题：provisional永续matcher settlement使用sequence-local changes捕获并在completion直接携带最终active-order value/删除标记、balance patch与terminal identity release；batch cancel合并为一个owner-user Lane事件异步执行和回调，不再由owner同步`executeUserSettlement`；同batch的终态`OrderRuntime`优先覆盖较早prepared active-order value。Lane只在最终stage commit推进sequence，provisional事件不重复推进Lane sequence。
- 主mixed、纯撮合、GC和短JFR场景/参数/门禁与PV-106相同，严格且仅256 in-flight、matcher=1、4 Lane。除原热点门禁外，短JFR必须确认batch cancel的owner同步`cancelOrderBatchRuntime/executeUserSettlement=0`；accepted/terminal、资金、余额/冻结、持仓、订单/强平终态和snapshot恢复必须全部闭合。
- 长稳仍固定同mixed场景、`fork=0,warmup=1x10s,measurement=1x600s,timeout=15m`，宿主8GiB ZGC、NMT summary、JFR default/maxsize 1GiB。必须完整运行10分钟无OOM，GC后live set不得持续线性增长，线程、FD、Direct/native稳定，全部业务闭合和teardown正确性门禁通过。另以已完成的2GiB/90秒严格256 in-flight诊断作为采集前反证：修复候选完整结束，`76,482.378 terminal business ops/s`（trading `76,029.775/s`、lifecycle `452.603/s`）、Core `7,693.533/s`、trades `18,102.327/s`，unfinished/error/timeout为0；该诊断不作为正式主吞吐结果。
- 环境：Oracle GraalVM Java HotSpot 25.0.1（HotSpot）、Maven3.9.16、MacBookPro16,1 / Intel i9-9880H / 8物理16逻辑CPU / 16GiB / macOS26.7 x86_64；采集前swap=`1,144.75MiB`。对照commit=`40938e2d`；排除性能记录的候选diff SHA-256=`4dfe20d225c44eb9a3f689b3037eb4fc578549e3ac81a48670cf5f4dd772833e`，shaded JAR SHA-256=`01ba31a67437b38be1bfed0bec6ca80517edd369bc7c916811aff2235f5f01a4`，JFC SHA-256=`dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`。artifact固定为`target/qualification/20260904T215633Z-owner-lane-terminal-final-256/`。
- 采集前HotSpot 25定向测试68/68通过，shaded JAR构建成功，`git diff --check`通过。不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data及其他五产品线。锁定后不修改代码、场景、参数或门禁；失败与无效轮照实追加。

#### PV-107采集结果

- 无profiler mixed主轮为`79,880.521 terminal business ops/s`，三个样本`78,430.023/81,502.524/79,709.014/s`；其中trading=`79,407.082/s`、lifecycle=`473.439/s`、Core messages=`8,036.018/s`、trades=`18,906.448/s`。相对PV-104提升`40.40%`，相对PV-105提升`8.77%`。纯撮合为`62,434.478 terminal business/Core ops/s`、`31,217.239 trades/s`，三个样本`63,252.391/61,937.729/62,113.314/s`，相对PV-104提升`13.95%`。两场景accepted/terminal闭合，unfinished/rejected/error/timeout为0，严格256 in-flight且期末backlog为0，资金、订单、持仓、强平终态和snapshot恢复通过。
- GC归因轮为`78,570.387 terminal business ops/s`、`538.562 MB/s`、`162,456,507.368 B/invocation`、15次GC/124ms；按该轮`3.632 invocation/s`折合约`21,632.816 business ops/invocation`和`7,509.725 B/business op`，相对PV-104下降`41.72%`、相对PV-105下降`13.49%`。短JFR带profile吞吐为`51,886.785 terminal business ops/s`，只用于归因。
- 短JFR的882个execution samples中，latency recorder约`10.88%`、`awaitMatchingResult 7.14%`、`progressPlaceAdmissions 6.01%`，其后单项均低于2%；`awaitMatcherSettlementBatch`、旧`ordersForUser/LongObjectHashMap.forEachValue`、`OpenInterestIndex.totals`、`cancelOrderBatchRuntime`和owner线程`assertOwner→ThreadLocal`均为0。4次ZGC把heap从最高`2.4GiB`回收到`350MiB`，最长GC pause `0.0402ms`；最长正常safepoint `0.617ms`，最长VM operation为`HandshakeAllThreads 45.6ms`。`DataLoss=0`；NMT除固定8GiB heap外Tracing/Code/Metaspace/GC峰值约`35.4/35.2/32.6/22.4MiB`，无owner同步数据库、网络或业务文件I/O。
- 短JFR最终稳定事件的accepted→terminal延迟：PLACE_ORDER p50/p99=`3.425/4.060ms`，CANCEL_ORDER=`2.359/6.132ms`，ORDER_BATCH=`55.453/114.660ms`，TRIGGER_ORDER=`0.254us/0.440ms`，RISK_SCAN=`0.115/0.193us`，FUNDING=`0.143/0.235us`；LIQUIDATION仅2样本、ADL仅1样本，不作尾延迟容量结论。入口→accepted仍受100,000 offered business ops/s高于profile轮处理能力影响而排队，原始p50/p90/p95/p99/p99.9/max及直方图保存在`jfr-business-latency.json/tsv`，不能作为可持续到达率SLA。
- 10分钟长稳完整结束，`55,905.995 terminal business ops/s`（trading=`55,575.183/s`、lifecycle=`330.811/s`）、Core=`5,623.686/s`、trades=`13,232.186/s`；accepted/terminal business与Core分别相等，unfinished/rejected/error/timeout为0，teardown资金与状态恢复门禁通过。65次ZGC的后半段GC后heap持续在约`440–670MiB`间波动，无线性live-set增长；与PV-106在7m34s达到8GiB并OOM形成直接反证。稳定段线程为46、FD为34，NMT committed约`8,321–8,340MiB`，Direct/Mapped buffer count/capacity/used均为0，`DataLoss=0`；未发现线程、native、FD或Lane终态实体持续增长。
- artifact=`target/qualification/20260904T215633Z-owner-lane-terminal-final-256/`。SHA-256：`main-mixed.json`=`6cc0663ac6e7bf5c09c96ef758e3527607c5289320f2d4f0963c0d3a05ed3f50`、`main-saturation.json`=`c984107cdb051e93de0a1c00680607a7ad7c62344b5175a5b16c8ad259889efe`、`gc.json`=`a101d1269f6f133cbbb4568cfc4b19a9be26e89a1f9c8a5f40fb30e1ca19cc07`、`jfr.json`=`0ef4e569cf2bb1fe7159f42555a99cc86b87651cf75bd10cea7b3185c5092dc2`、`mixed.jfr`=`d71b456623cfcf872d330b2244a61bc1473fabe9b953a69914bb3319bc322818`、`long-stable.json`=`83933ba93d4aa6fb3547ed7874dd1853833d350a7a5264f240a83ff865733582`、`long-stable.jfr`=`e38ba1aa248e18c8fb36eecb49fc41da1b9b5e524a7df0e4db71c932dbf2cd5c`、`long-telemetry.txt`=`a7ccbdea8d1519d7f9cbf7e6f26a45ed1a1bba91d406fe43845e64ca2a7cbfbb`。最终候选通过本机线性永续受影响路径的吞吐、分配、热点和10分钟长稳门禁；仍未覆盖外部Aeron Cluster、API/Kafka/WebSocket/market-data、PostgreSQL/exporter/wallet及其他五产品线，不宣称生产容量或全系统验收。
### 2026-09-04 22:44:40 +08:00 — `PV-20260904-256-108` — `采集前锁定（completion/batch allocation优化候选）`

#### 采集前锁定

- 对照为已推送commit `d4e0a4c8` / PV-107。本候选优化七类剩余热点：去除`awaitMatchingResult -> takeMatchingResult -> progressPlaceAdmissions`重复轮询和空闲ready-mask RMW；将order-batch admission的boxed `HashMap<Record, boxed value>`替换为按batch容量持有的primitive/typed数组；按item数量预分配batch change set/result容器；复用安全生命周期内的place-admission与matcher-settlement event并预扩容sequence-local Lane change buffer；Lane scope退出保留空ThreadLocal槽以消除逐任务Entry重建；`ResolvedPlaceOrder`携带已解析symbolId并供admission/replace直接使用；batch codec使用直接little-endian byte-array writer并新增20-item codec JMH。matcher settlement池只在owner完成collect、changes归还且event `complete()`后回收，sequence/Aeron提交顺序、Lane单写和snapshot fence不变。
- 正式主场景保持PV-107完全相同：线性永续、matcher=1、4 Account Lane、1,000 users、256 symbols、20 items/batch、32 lifecycle symbols/run、做市流量在场，严格且仅`256 in-flight`。无profiler主轮`fork=1,warmup=3x3s,measurement=3x5s,threads=1`；通过阈值为`>=75,000 terminal business ops/s`，且accepted/terminal business与Core相等、unfinished/rejected/error/timeout为0、期末backlog为0，并通过资金守恒、余额/冻结、持仓、订单/强平终态和snapshot恢复。PV-107同口径对照为`79,880.521 terminal business ops/s`。
- GC轮固定同主场景`fork=1,warmup=1x3s,measurement=1x5s,-prof gc`；目标`<=7,509.725 B/terminal business op`且无Full GC。codec补充轮只用于归因，`CoreResponseEncodingBenchmark.(encode|decode)PlaceBatch`固定20 items、`fork=1,warmup=3x1s,measurement=5x1s`，不与主业务吞吐混算。
- JFR固定同主场景`fork=0,warmup=2x5s,measurement=1x15s`，宿主`-Xms8g -Xmx8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:NativeMemoryTracking=summary`和`owner-commit-profile.jfc`。必须检查CPU/线程分组、分配/GC/heap/native、锁/park、safepoint/VM operation、JIT、I/O/异常及`DataLoss`；重点门禁为owner `awaitMatchingResult -> takeMatchingResult=0`、Lane `ThreadLocalMap.set`分配趋零、batch boxed key record分配为0、`MatcherSettlementEvent.prepare`分配下降，owner无同步数据库/网络/文件I/O。
- 短轮通过后执行同场景10分钟长稳：`fork=0,warmup=1x10s,measurement=1x600s`、严格256 in-flight、8GiB ZGC、NMT summary、JFR default/maxsize 1GiB。要求业务计数闭合、资金与snapshot恢复通过，多轮GC后live set无持续线性增长，线程、FD、Direct/Mapped及native committed稳定。event池和扩容后的buffer属于长期状态，因此缺少长稳结果不能声明完整验收。
- 环境：Oracle GraalVM Java HotSpot 25.0.1、Maven3.9.16、MacBookPro16,1 / Intel i9-9880H / 8物理16逻辑CPU / 16GiB / macOS26.7 x86_64。被测HEAD=`d4e0a4c8`加未提交候选，排除本记录前的代码diff SHA-256=`220a78d86ec2c0cd31298d4ad91f8055838e29f5965206bc7637a614929d8e25`；artifact锁定为`target/qualification/20260904T224440Z-completion-batch-allocation-256/`。采集开始后不修改代码、参数、场景或门禁；失败与无效轮照实追加。
- 采集前HotSpot 25回归：protocol codec `13/13`、Lane/risk/runtime/index `66/66`、真实benchmark support `12/12`通过，`git diff --check`通过。旧`CoreOrderedOrderBatchTest`仍包含已删除exporter与终态订单历史契约，诊断运行出现相应8项失败，不纳入本次锁定测试范围；未启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data和其他五产品线。

#### PV-108采集结果（JFR发现event回收发布竞态，候选失败）

- 无profiler mixed为`75,991.642 terminal business ops/s`，三个样本`70,874.952/78,911.421/78,188.553`；accepted/terminal business与Core闭合，unfinished/rejected/error/timeout为0。GC轮为`76,055.636 terminal business ops/s`、`506.104 MB/s`、`157,998,540.444 B/invocation`、15次GC/108ms，按`3.516 invocation/s`折算约`7,304 B/business op`。20-item codec编码/解码为`826,467.055/918,636.553 batch/s`。
- JFR在首个warmup触发`PlaceAdmissionEvent.execute`空指针：Lane先设置completed，owner通过其他进度路径观察到完成并调用release清空`runtime`字段，Lane随后用该字段发布ready通知。该轮证明event回池的发布依赖未满足happens-before生命周期边界，吞吐/GC虽达到门槛也不能验收。artifact保留在`target/qualification/20260904T224440Z-completion-batch-allocation-256/`；`mixed.jfr`与`jfr.json`为失败诊断，不是有效profile。

### 2026-09-04 22:48:48 +08:00 — `PV-20260904-256-109` — `采集前锁定（event发布竞态修复）`

#### 采集前锁定

- 仅修复PV-108暴露的回收竞态：`PlaceAdmissionEvent`与`MatcherSettlementEvent`在开放completed可见性之前，把ready发布所需runtime、lane与sequence捕获到Lane栈局部变量；owner回收清空字段后，Lane不再回读event字段。其余PV-108七项候选代码、场景、JVM/JMH/JFR参数和数据有效性门禁完全不变。
- 正式mixed阈值仍为`>=75,000 terminal business ops/s`，严格256 in-flight且业务/资金/snapshot门禁闭合；GC仍要求`<=7,509.725 B/business op`；JFR热点门禁和10分钟长稳要求不变。对照仍为commit `d4e0a4c8` / PV-107。
- 环境仍为Oracle GraalVM Java HotSpot 25.0.1、Maven3.9.16、Intel i9-9880H 8物理16逻辑CPU、16GiB、macOS26.7 x86_64。被测HEAD=`d4e0a4c8`加未提交候选，排除性能记录的代码diff SHA-256=`8eabb81dde281c9255302259af260960e431d5b81c7623789dc8ff9c26b25928`；artifact锁定为`target/qualification/20260904T224848Z-completion-batch-race-fixed-256/`。
- 修复后HotSpot 25重新执行protocol codec `13/13`、Lane/risk/runtime/index `66/66`、真实benchmark support `12/12`全部通过。采集开始后不再修改代码、参数、场景或门禁；失败与无效轮照实追加。不测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data和其他五产品线。

#### PV-109采集结果

- mixed主轮为`79,629.981 terminal business ops/s`，Core messages=`8,010.813/s`、trades=`18,847.149/s`；accepted/terminal闭合，unfinished/rejected/error/timeout为0。相对PV-107变化`-0.31%`，吞吐基本持平。
- GC轮为`76,068.295 terminal business ops/s`、`509.225 MB/s`、14次GC/112ms，折合`7,356.164 B/business op`，较PV-107下降`2.05%`。20-item codec为encode=`721,982.191 batch/s`、decode=`850,504.484 batch/s`。
- 短JFR确认重复matching result轮询、batch boxed key和逐Lane任务ThreadLocal重建已退出热路径；`DataLoss=0`，最长ZGC pause=`0.0118ms`。主要热点仍是`awaitMatchingResult`、Lane ready通知和matching completion。
- 10分钟长稳完整结束：`54,198.389 terminal business ops/s`、Core=`5,451.915/s`、trades=`12,828.019/s`；业务计数、资金和snapshot恢复闭合。65次ZGC后半段live set约`394–652MiB`，未发现线性增长，最长pause=`0.0689ms`。
- artifact：`target/qualification/20260904T224848Z-completion-batch-race-fixed-256/`。关键SHA-256：`main-mixed.json`=`d45894c037698974286f794566ae37e144ccf4db4ad66554aaa8b288742b5163`、`gc.json`=`62547652609c3e863666bc884233b5e8d393ca6ec87205c67d56360cebbc0ee3`、`mixed.jfr`=`2179981a1c8d11bccea338c887430e0f9bf5ddd2332f4c818eb8d27dc298a150`、`long-stable.json`=`b40ed99bbaee3f60ec2f5d2dc2d83ae9a76d4540e5752b8341b8c3b9cd676d51`、`long-stable.jfr`=`3ee479ab26820dce441090b955a887bdb3ccc22c270ad9bb10983d2316e14ac3`。未测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data、外部Aeron Cluster和其他五产品线。

### 2026-09-04 23:56:30 +08:00 — `PV-20260904-256-110` — `采集前锁定（异步batch admission与单次completion提交）`

#### 采集前锁定

- 本候选按顺序实现六项交易热路径优化：mixed JMH按生产后台方式批量泵取完成态；PLACE batch预校验后以单个Account Lane事件异步admission，owner不再同步等待Lane mutation；matching/admission/settlement由单一owner completion pump推进；Lane terminal completion在owner一次遍历中同时提交最终值、changed-key/index及identity删除；batch admission的symbol/position/side/margin与资产累计预占均改为O(1)增量索引，移除前序订单回扫；`OrderBatchPending`及其数组、列表、primitive map/set和admission index按容量池化复用。sequence、matcher prefix、资金、风险、强平、ADL、保险基金和Aeron snapshot fence语义不变。
- 对照commit=`697b1e75c9abd83fa0f2f4e0ff8991904b3656d7`，同口径PV-109为`79,629.981 terminal business ops/s`和`7,356.164 B/business op`。正式mixed通过阈值固定为`>=75,000 terminal business ops/s`，优化目标为`>=90,000/s`并观察是否达到`100,000/s`；分配门禁为`<=7,500 B/business op`且无Full GC。低于目标不修改本轮口径，按失败或未达目标记录。
- 场景固定为`LinearPerpetualCoreBenchmark.liquidationWithTrading`：仅LINEAR_PERPETUAL、matcher=1、4 Account Lane、1,000活跃用户、256 active symbols、20 items/batch、每轮1个HFT batch、32 lifecycle symbols/run、做市与mark/risk/强平/保险基金/ADL流程在场；严格且仅`256 in-flight`，100,000 offered、open-loop并修正coordinated omission。主轮固定`fork=1,warmup=3x3s,measurement=3x5s,threads=1`；GC轮固定`fork=1,warmup=1x3s,measurement=1x5s,-prof gc`。
- 所有轮次要求accepted/terminal business operations及Core messages分别相等，unfinished/rejected/error/timeout/producer-starvation和期末matcher/Lane/context backlog为0；分别报告terminal business/Core messages、trades、批量items和业务类型，资金守恒、余额/冻结/持仓、订单生命周期终态、强平/保险/ADL及完成态snapshot恢复必须通过。benchmark未提供的API层requests/s和外部连接指标不作推断。
- JFR固定同一mixed场景`fork=0,warmup=2x5s,measurement=1x15s`，宿主`-Xms8g -Xmx8g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:NativeMemoryTracking=summary`，使用`owner-commit-profile.jfc`。检查owner/matcher/Lane/risk/snapshot分组CPU，allocation/GC/heap/native，线程/锁/park，safepoint/VM operation，JIT，I/O/异常和DataLoss；重点确认owner不再同步batch admission、completion无重复轮询、batch admission无O(batch²)回扫、终态changed/index只遍历一次，且owner无同步数据库/网络/业务文件I/O。
- 短轮通过后执行相同mixed场景10分钟长稳：`fork=0,warmup=1x10s,measurement=1x600s,timeout=15m`、严格256 in-flight、8GiB ZGC、NMT summary、JFR default/maxsize 1GiB。要求业务与资金闭合，多轮GC后live set无持续线性增长，线程、FD、Direct/Mapped和native committed稳定；本候选新增事件/context池，缺少长稳不能声明完整验收。
- 环境：Oracle GraalVM Java HotSpot 25.0.1、Maven3.9.16、MacBookPro16,1 / Intel i9-9880H / 8物理16逻辑CPU / 16GiB / macOS26.7 x86_64；采集前swap=`952.75MiB`。排除本记录的代码diff SHA-256=`92537789e72db850a867da47089230fa97172ebeb6bb3deb16362da0ebdf3bea`，shaded JAR SHA-256=`1a3c80bff5a4feee522d4d66cf829a141597f06d8e7b31d36cc759a705a8998d`，JFC SHA-256=`dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`；artifact固定为`target/qualification/20260904T235630Z-async-batch-completion-256/`。
- 采集前HotSpot 25定向测试：核心状态/Lane/index/batch资金守恒`60/60`、真实mixed benchmark支撑`12/12`通过，shaded JAR构建和`git diff --check`通过。不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data、外部Aeron Cluster及其他五产品线。采集开始后不修改代码、参数、场景或门禁；失败和无效轮照实追加。

#### PV-110采集结果（主轮失败）

- 无profiler mixed主轮为`41,385.054 terminal business ops/s`，三个样本`40,376.486/41,744.889/42,033.788/s`；Core messages=`4,163.682/s`、trades=`9,795.098/s`。accepted/terminal business与Core闭合，unfinished/rejected/error/timeout为0，但较PV-109下降`48.03%`，低于75,000/s门禁，因此本轮失败并按锁定规则停止GC、JFR和10分钟长稳。
- 初步负对照显示业务Lane仍在持续处理且没有backlog或正确性错误；新增production-style benchmark drain使用`awaitFirst=false`，完成队列暂空后在宿主macOS调用`parkNanos(1,000)`，其实际调度代价被每轮completion空窗放大。该等待混入测量边界，不代表异步batch admission的纯业务成本。下一候选只修正completion pump的busy-spin/等待策略后重新锁定采集，PV-110不作为性能结论。
- artifact=`target/qualification/20260904T235630Z-async-batch-completion-256/`；`main-mixed.json`与`main-mixed.log`保留为失败证据。未执行PostgreSQL或exporter测试。

### 2026-09-05 00:50:46 +08:00 — `PV-20260905-256-111` — `采集前锁定（异步batch admission最终修正轮）`

#### 采集前锁定

- 对照commit=`697b1e75c9abd83fa0f2f4e0ff8991904b3656d7` / PV-109。本候选完成六项：mixed驱动等待首个终态后批量收割ready completion；PLACE batch由单个Lane事件异步admission，owner不等待Lane；统一owner completion pump；Lane completion携带最终changed/index/funds delta并由owner一次遍历提交；batch admission使用O(1) symbol/资金累计索引；池化完整`OrderBatchPending`、数组、集合与event。修复诊断发现的submission ready-mask生命周期、异步admission竞态及余额镜像重复遍历；sequence、matcher prefix、资金与snapshot fence不变。
- 场景固定`LinearPerpetualCoreBenchmark.liquidationWithTrading`：仅LINEAR_PERPETUAL，matcher=1、4 Account Lane、1,000 users、256 symbols、20 items/batch、1 HFT round、32 lifecycle symbols/run、做市/risk/强平/保险/ADL在场，严格且仅`256 in-flight`，100,000 offered、open-loop并修正coordinated omission。主轮固定`fork=1,warmup=3x3s,measurement=3x5s,threads=1`，8GiB ZGC；门禁仍为`>=75,000 terminal business ops/s`，目标100,000/s。
- 有效性要求：accepted/terminal business及Core分别相等，unfinished/rejected/error/timeout为0，期末backlog为0；资金守恒、余额/冻结、持仓、订单/强平终态与snapshot恢复通过。主轮通过后才运行同场景`-prof gc`、15秒JFR和10分钟长稳；主轮失败则停止后续采集，不能以profiler或诊断轮替代。
- 环境：Oracle GraalVM Java HotSpot 25.0.1、Maven3.9.16、MacBookPro16,1 / Intel i9-9880H / 8物理16逻辑CPU / 16GiB / macOS26.7 x86_64；Pages throttled=0。代码diff SHA-256=`a9ec5be48ce13e7173e4fe048c47b6b9b5068580c402949aaf5eee7d328a7871`，shaded JAR SHA-256=`af529964ebf589c72e77c62316bff043d5e35245f05128e4a559b6b274c0ff01`，JFC SHA-256=`dff0b88ea10e024e116295260c4906d1654f2fcd0c4371139daebf825a9813b4`；artifact固定`target/qualification/20260905T005046Z-async-batch-final-256/`。
- 采集前HotSpot25定向回归：核心状态/Lane/index `59/59`、真实mixed benchmark支撑`12/12`通过，shaded JAR构建及`git diff --check`通过。不启动或测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data、外部Aeron Cluster及其他五产品线。锁定后不修改代码、参数、场景或门禁。

#### PV-111采集结果（主轮失败）

- 无profiler mixed主轮为`37,071.913 terminal business ops/s`，三个样本为`34,479.183/38,339.439/38,397.117/s`；其中trading=`36,851.792/s`、lifecycle=`220.121/s`、terminal Core messages=`3,729.815/s`、trades=`8,774.236/s`。accepted/terminal business与Core分别相等，unfinished/rejected/error/timeout均为0，资金、余额/冻结、持仓、订单/强平终态与snapshot恢复检查通过，但低于锁定的`75,000/s`门禁，因此本候选性能验收失败。
- 同机短时诊断显示对照HEAD/PV-109代码约为`47,738.954 terminal business ops/s`，当前候选约低`22%`；历史PV-109的`79,629.981/s`受机器当时状态影响，不能直接当作本轮同机差值。两者每invocation的Lane业务操作量基本一致，未发现额外Lane任务膨胀。
- 归因结论：异步admission新增了一个Lane完成边界，但当前全局snapshot mutation仍只允许一个order batch活跃；matcher提交必须等该batch admission成功后才能开始，因此没有形成跨batch并行收益，反而增加owner/Lane协调成本。六项结构调整已完成且正确性闭合，但要获得吞吐收益，下一步必须把batch snapshot/admission状态改为真正的per-sequence context，允许多个batch sequence同时在途，同时保持matcher prefix和资金提交顺序。
- 按预锁规则，主轮失败后停止GC、正式JFR和10分钟长稳，不能声明分配、内存、尾延迟或长期稳定性验收通过。另以HotSpot 25补跑永续batch累计预占与资金守恒精确测试`1/1`通过；PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data、外部Aeron Cluster及其他五产品线未测试。
- artifact=`target/qualification/20260905T005046Z-async-batch-final-256/`。SHA-256：`main-mixed.json`=`59e9e59b30545c98b945fa2074b0bd688077348612dca313174ef8147917b48d`，`main-mixed.log`=`63e20a804de3f872ec9460f5e5bd370e17226eb7f638b7e5111ff3cf697d24bb`。

### 2026-09-05 08:44:36 +08:00 — `PV-20260905-256-112` — `采集前锁定（per-sequence batch流水线诊断）`

#### 采集前锁定

- 目的：诊断不同用户、不同symbol的PLACE batch是否能分别处于Lane admission、matcher执行和owner顺序提交阶段；同用户或同symbol仍保持依赖顺序。候选把batch admission completion保留在各自sequence context，完成后先提交matcher，只有pending head进入owner mutation/snapshot/funds/changed-key提交上下文。sequence、matcher prefix、资金校验和snapshot fence不变。
- 场景固定`LinearPerpetualCoreBenchmark.liquidationWithTrading`：LINEAR_PERPETUAL、matcher=1、4 Account Lane、1,000 users、256 symbols、20 items/batch、1 HFT round、32 lifecycle symbols/run、做市/risk/强平/保险/ADL在场，严格`256 in-flight`、100,000 offered、open-loop并修正coordinated omission。诊断轮固定`fork=1,warmup=1x3s,measurement=1x5s,threads=1`，8GiB ZGC、AlwaysPreTouch、DisableExplicitGC；只与同机PV-111 `37,071.913/s`及其紧邻旧代码诊断`47,738.954/s`比较，不作为正式验收。
- 数据有效性要求accepted/terminal business及Core分别相等，unfinished/rejected/error/timeout为0，期末backlog为0，并通过资金守恒、余额/冻结、持仓、订单/强平终态和snapshot恢复。诊断目标为确认吞吐恢复到至少同机旧代码约`47,000 terminal business ops/s`；未达到则继续修改并另建记录，不能将本轮改为正式验收。
- 环境：Oracle GraalVM Java HotSpot 25.0.1、Maven3.9.16、MacBookPro16,1 / Intel i9-9880H / 8物理16逻辑CPU / 16GiB / macOS26.7 x86_64，swap=`762MiB`。HEAD=`d261926b82e8b18dc8eac82311fec0111dc6b510`加未提交候选；生产代码diff SHA-256=`cb1e3b021ab568b58905b4b7092c835b975fa2b9ada893d796b27cbd85280746`，shaded JAR SHA-256=`ce2a4afe3b00442b5bac72a8f7f7c0f28e5fa93e37cf742b26908950b0de1ee5`；artifact固定为`target/qualification/20260905T084436Z-per-sequence-batch-diagnostic-256/`。
- 采集前HotSpot 25编译通过，真实mixed benchmark支撑`12/12`通过，永续batch累计预占与资金守恒`1/1`通过。旧`CoreOrderedOrderBatchTest`中的exporter及终态订单保留断言与当前已删除功能不一致，不纳入诊断门禁。不测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data、外部Aeron Cluster及其他五产品线。

#### PV-112采集结果（诊断目标失败）

- mixed诊断轮为`36,515.131 terminal business ops/s`，其中trading=`36,298.319/s`、lifecycle=`216.812/s`、Core messages=`3,673.794/s`、trades=`8,642.457/s`；accepted/terminal business及Core闭合，unfinished/rejected/error/timeout为0，资金与snapshot恢复检查通过。
- 结果没有恢复到同机旧代码约47k/s，且与PV-111的37k/s基本持平。说明仅让多个batch admission与matcher提前在途没有消除主瓶颈：batch settlement仍由owner在前一batch终态提交后才构造和派发，Account Lane没有形成多sequence settlement窗口。本轮不作为正式验收，候选继续把matcher result解释和settlement dispatch前移到sequence context。
- artifact=`target/qualification/20260905T084436Z-per-sequence-batch-diagnostic-256/main.json`，SHA-256=`d76561e22e156b887c70e73da99fd187847e97cfa60d09e32b4e764c70e8ca90`。本轮未执行GC、JFR或长稳。

### 2026-09-05 08:50:08 +08:00 — `PV-20260905-256-113` — `采集前锁定（多sequence settlement在途诊断）`

#### 采集前锁定

- 在PV-112基础上，将已完成batch matcher result的解释、matcher prefix推进、settlement plan构造和隔离Lane settlement事件派发前移到按core sequence排序的dispatch head；每个batch保留自己的admission、matching result、settlement events和最终delta。owner提交头部仅收割已完成Lane事件、恢复该sequence的snapshot/funds/changed-key context并发布，不等待其他sequence。相同用户/symbol依赖规则不变。
- 场景、JVM和有效性条件与PV-112相同：LINEAR_PERPETUAL、matcher=1、4 Lane、1,000 users、256 symbols、20 items/batch、32 lifecycle symbols、严格256 in-flight、100,000 offered；`fork=1,warmup=1x3s,measurement=1x5s,threads=1`、8GiB ZGC。诊断目标为`>=47,000 terminal business ops/s`且settlement in-flight高水位大于1；计数闭合、错误为0、资金与snapshot恢复通过。该短轮仍不作为正式验收。
- HEAD=`d261926b82e8b18dc8eac82311fec0111dc6b510`加未提交候选；生产代码diff SHA-256=`1a95d048a48d44f6218cabd5683fcd8bdfe1bc4a85abbdd42563113460b358dd`，shaded JAR SHA-256=`83161c323ed7c811ff3c87093bd20c97a24995246dd7d5e33f2cab1e84b3b231`；artifact固定`target/qualification/20260905T085008Z-multi-settlement-diagnostic-256/`。HotSpot25真实mixed benchmark支撑`12/12`通过；不测试范围沿用PV-112。

#### PV-113采集结果（无效诊断）

- mixed为`40,222.321 terminal business ops/s`，trading=`39,983.579/s`、lifecycle=`238.741/s`、Core messages=`4,046.701/s`、trades=`9,519.900/s`；计数闭合且该轮teardown正确性通过，较PV-112提高约`10.15%`，但未达到47k诊断目标。
- 采集后的扩大回归在5/12场景触发`runtime changed-index commit is out of order`，证明该候选把未来batch admission的共享changed buffer/revision暴露给当前sequence，故本轮数据无效，不能作为容量或正确性结论。后续候选将admission changed-key/balance patch隔离进事件，并把20-item settlement合并为每Lane一个batch事件。
- artifact JSON SHA-256=`3aadb7ad2bb4ed5806f3c62b5f7df08380f7bf5eb73fdc3f69279d54ba9e8d50`；未执行GC、JFR或长稳。

### 2026-09-05 09:00:52 +08:00 — `PV-20260905-256-114` — `采集前锁定（per-sequence隔离与Lane batch settlement诊断）`

#### 采集前锁定

- 候选完成per-sequence admission changed-key/balance patch隔离，并把同一20-item batch的per-item matcher settlement plans合并为一个不可变batch event；每个涉及的Account Lane只接收一个任务并按matcher顺序应用全部plan，event完成后owner按core sequence收割最终order/position/balance/index/funds delta。同用户、同symbol或matching结果实际触及相同用户时保持依赖顺序。
- 场景、机器和JVM沿用PV-112/PV-113：LINEAR_PERPETUAL、matcher=1、4 Lane、1,000 users、256 symbols、20 items/batch、32 lifecycle symbols、严格256 in-flight、100,000 offered；`fork=1,warmup=1x3s,measurement=1x5s,threads=1`、8GiB ZGC。诊断通过标准为`>=60,000 terminal business ops/s`，且accepted/terminal business与Core闭合、unfinished/rejected/error/timeout为0、资金/持仓/订单/强平终态和snapshot恢复通过。诊断通过后才建立正式主轮记录。
- HEAD=`d261926b82e8b18dc8eac82311fec0111dc6b510`加未提交候选；生产代码diff SHA-256=`00e09ae2ec9f0097bc8444c819aef8a1511daf24a796c102b13742fe29a4973b`，shaded JAR SHA-256=`1e5be19d93ec3fc68eb5f7efe88330fad4b0f1f8facdc8f791e98d4bc4ba3286`；artifact固定`target/qualification/20260905T090052Z-lane-batch-settlement-diagnostic-256/`。HotSpot25定向service测试`61/61`、真实mixed benchmark支撑`12/12`通过；不测试范围沿用PV-112。

#### PV-114采集结果（未达诊断目标）

- mixed为`44,479.035 terminal business ops/s`，trading=`44,215.102/s`、lifecycle=`263.933/s`、Core messages=`4,474.895/s`、trades=`10,527.405/s`；accepted/terminal闭合，unfinished/rejected/error/timeout为0，资金及snapshot恢复通过，但低于60k诊断目标。
- Lane operations降至`13,825.257/s`、其中settlement=`11,155.087/s`，相对PV-113约`48,659.890/46,254.628/s`下降约72%/76%，证明20-item settlement合并有效；吞吐相对PV-111提高约19.98%。剩余瓶颈已从Lane任务碎片转到owner/matcher侧，需JFR归因。
- artifact JSON SHA-256=`ec57f0a23860cc1e416b5ad3dba323be70687567b149a9d2c903817b81f778db`；本轮未执行GC或长稳。

### 2026-09-05 09:02:30 +08:00 — `PV-20260905-256-115` — `采集前锁定（per-sequence流水线JFR诊断）`

#### 采集前锁定

- 使用PV-114完全相同代码与业务场景执行短JFR归因，固定严格256 in-flight、matcher=1、4 Lane、1,000 users、256 symbols、20 items/batch和完整risk/强平/保险/ADL；`fork=0,warmup=1x3s,measurement=1x10s,threads=1`，8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary，使用既有`owner-commit-profile.jfc`。该轮只定位CPU、allocation、park/lock、GC、safepoint、I/O和异常，不替代主吞吐。
- 数据有效性仍要求业务/Core计数闭合、错误及unfinished为0、资金与snapshot恢复通过、JFR DataLoss=0。重点区分owner的plan/result/snapshot/index commit、matcher和Lane batch event；artifact固定`target/qualification/20260905T090230Z-per-sequence-jfr-diagnostic-256/`。代码与JAR哈希沿用PV-114；不测试范围沿用PV-112。

#### PV-115采集结果

- JFR归因轮为`36,853.738 terminal business ops/s`，trading=`36,635.292/s`、lifecycle=`218.446/s`、Core messages=`3,707.521/s`、trades=`8,722.689/s`；accepted/terminal business及Core闭合，unfinished/rejected/error/timeout为0，资金和snapshot恢复通过，`DataLoss=0`。
- 367个execution samples中，owner的`conflictsWithEarlierPipelinedBatch`与`awaitMatchingResult`各占`3.00%`，`drainLaneReadyNotifications`占`2.45%`；冲突判断按earlier batch × candidate item重复扫描，是新增流水线的首要可删除热点。Lane任务已经合并，但owner仍承担冲突扫描和completion轮询。
- 19秒内3次ZGC，最高GC前heap=`1.6GiB`、对应GC后=`378MiB`，最长pause=`0.0404ms`；owner/JMH worker分配约`2.3GiB`、占总分配`62.31%`，matcher约`12.75%`，4个Lane合计约`23%`。主要分配来自primitive map扩容、`OrderRuntime`、published change buffer、codec/result对象；短JFR不证明无泄漏。
- JVM平均user/system CPU约`13.97%/1.68%`，机器总CPU约`20.14%`；NMT除固定8GiB heap外无异常增长，Direct/Mapped buffer为0，无socket I/O及owner同步数据库/网络/业务文件I/O。artifact `mixed.jfr`/`jfr.json` SHA-256分别为`6568e6befec8f5e02fc9c8bc0aea78e8688cb4c526c059427cddffb83c313195`/`d20030a10cc8e392eb373d0b3023782ad73bf48eff4b77d959a1e40283150ddf`。

### 2026-09-05 09:08:44 +08:00 — `PV-20260905-256-116` — `采集前锁定（增量symbol依赖与Lane顺序诊断）`

#### 采集前锁定

- 候选将pipelined batch的同symbol依赖从`pending window × batch items`扫描改为owner维护的增量symbol→batch索引；batch完成/回收时移除。删除settlement前的跨batch changed-user扫描：同一用户固定路由到同一Account Lane，batch settlement事件由owner按core sequence提交，各Lane队列天然保持该用户的执行顺序；不同Lane用户无需全局等待。sequence提交、matcher prefix、admission symbol依赖、资金和snapshot fence保持不变。
- 诊断场景与PV-114相同：`LINEAR_PERPETUAL`、matcher=1、4 Lane、1,000 users、256 symbols、20 items/batch、1 HFT round、32 lifecycle symbols、严格且仅256 in-flight、100,000 offered、open-loop/coordinated-omission corrected；固定`fork=1,warmup=1x3s,measurement=1x5s,threads=1`和8GiB ZGC。目标为高于PV-114的`44,479.035 terminal business ops/s`且至少达到`47,000/s`；accepted/terminal business及Core必须闭合，unfinished/rejected/error/timeout为0，资金、余额/冻结、持仓、订单/强平终态及snapshot恢复通过。
- HEAD=`d261926b82e8b18dc8eac82311fec0111dc6b510`加未提交候选；生产源码diff SHA-256=`92e0607c323e8f0418c4e226bbbece8f7c1a1498d1c7fb38a756f287125ddb59`。采集前HotSpot25上游及service构建成功，定向service测试`61/61`、真实mixed benchmark支撑`12/12`通过。artifact固定`target/qualification/20260905T090844Z-incremental-pipeline-dependency-256/`。
- 不测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data、外部Aeron Cluster及其他五产品线。该短轮只作诊断，采集开始后不修改代码、场景、参数或门禁。

#### PV-116采集结果（未达诊断门禁）

- mixed为`41,888.697 terminal business ops/s`，trading=`41,640.065/s`、lifecycle=`248.632/s`、Core messages=`4,214.353/s`、trades=`9,914.301/s`；accepted/terminal闭合，unfinished/rejected/error/timeout为0，资金及snapshot恢复通过。
- 结果低于47k门禁，也低于PV-114的44,479/s。增量symbol索引删除了JFR发现的嵌套symbol扫描，但`conflictsWithEarlierPipelinedBatch`仍为每个新batch遍历全部earlier batch确认其状态；本场景每个20-item batch只包含单一且互不相同的symbol，因此剩余扫描是纯O(window²)协调成本，不是业务依赖。下一轮将其替换为active-pipelined计数门禁。
- artifact `main.json` SHA-256=`d98e79de7dc1400332180db2dc49e8d3ddaa84252a7ab785fd082b2758659880`；该轮未执行GC、JFR或长稳。

### 2026-09-05 09:13:12 +08:00 — `PV-20260905-256-117` — `采集前锁定（O(1) pipeline barrier诊断）`

#### 采集前锁定

- 候选在PV-116基础上用active-pipelined batch计数判断前序是否全部可流水化，删除每个新batch对全部earlier batch的状态扫描；symbol→batch索引仍只阻塞真实同symbol依赖。计数和symbol所有权随batch注册/完成精确增减，普通batch仍形成顺序barrier。
- 场景、参数、有效性条件和不测试范围与PV-116完全一致：LINEAR_PERPETUAL、matcher=1、4 Lane、1,000 users、256 symbols、20 items/batch、32 lifecycle symbols、严格256 in-flight；`fork=1,warmup=1x3s,measurement=1x5s,threads=1`、8GiB ZGC。诊断门禁为`>=47,000 terminal business ops/s`，且业务/Core计数、错误、资金、订单、持仓、强平与snapshot恢复全部闭合。
- HEAD=`d261926b82e8b18dc8eac82311fec0111dc6b510`加未提交候选；生产源码diff SHA-256=`148eefa1cd97c07e08ce40a646094281329219d8c5fd313d6531d2732a331c7a`，shaded JAR SHA-256=`ce92a9c21888f4487f310131f0cc1ba8d1b731663be8a9010e4267d021329ad6`。HotSpot25定向service测试`61/61`、真实mixed benchmark支撑`12/12`通过；artifact固定`target/qualification/20260905T091312Z-o1-pipeline-barrier-256/`。采集开始后不修改代码、场景、参数或门禁。

#### PV-117采集结果（未达诊断门禁）

- mixed为`41,701.590 terminal business ops/s`，trading=`41,454.068/s`、lifecycle=`247.522/s`、Core messages=`4,195.528/s`、trades=`9,870.016/s`；所有业务、资金和snapshot门禁闭合，但仍低于47k。
- O(1) barrier与PV-116吞吐基本相同，排除batch依赖扫描为主因。源码路径确认每个pipelined batch settlement完成后，owner仍同步执行`stampChangedOrdersByLane`，随后再同步`stageLaneMutation`；即Lane已完成业务后，每sequence仍有两次额外Lane往返，owner并未达到只负责sequence/发布边界的目标。
- artifact `main.json` SHA-256=`60b4b2b6e58004eaa1cec53a5cd6558c0ea20b95f2425a565a371848963f9e86`；未执行GC、JFR或长稳。

### 2026-09-05 09:19:50 +08:00 — `PV-20260905-256-118` — `采集前锁定（Lane settlement直接最终提交诊断）`

#### 采集前锁定

- 候选将batch的core sequence、commit timestamp和cluster position在settlement dispatch时直接写入单个Lane batch event；Lane按plan顺序完成成交、终态回收、资金/position/order/index change捕获、订单commit metadata和Lane applied/committed watermark。owner completion只收割最终值并按core sequence发布，不再为该batch同步调用`stampChangedOrdersByLane`或`stageLaneMutation`。普通非batch及无settlement的batch路径保持原逻辑。
- 场景继续固定LINEAR_PERPETUAL mixed：matcher=1、4 Lane、1,000 users、256 symbols、20 items/batch、32 lifecycle symbols、做市/risk/强平/保险/ADL在场，严格且仅256 in-flight；`fork=1,warmup=1x3s,measurement=1x5s,threads=1`、8GiB ZGC。诊断门禁提升为`>=55,000 terminal business ops/s`，并要求accepted/terminal business及Core闭合、unfinished/rejected/error/timeout为0、资金/余额/冻结/持仓/订单/强平终态和snapshot恢复通过。
- HEAD=`d261926b82e8b18dc8eac82311fec0111dc6b510`加未提交候选；生产源码diff SHA-256=`d0ecb6808fadf4701eeb9bc75aefc8577069db1a6a4b3db0d27087ed78891136`，shaded JAR SHA-256=`a15b8a016a0def4a7a2c011dd0655ce86cdb678633b5f5951ecba2ecef0698b9`。HotSpot25 compile通过，定向service测试`59/59`、永续batch资金测试`1/1`、真实mixed benchmark支撑`12/12`通过。artifact固定`target/qualification/20260905T091950Z-lane-final-batch-commit-256/`。
- 不测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data、外部Aeron Cluster及其他五产品线。短诊断通过后再建立正式主轮/GC/JFR/长稳记录；采集开始后不修改代码、场景、参数或门禁。

#### PV-118采集结果（未达吞吐门禁，结构目标通过）

- mixed为`42,957.969 terminal business ops/s`，trading=`42,702.990/s`、lifecycle=`254.979/s`、Core messages=`4,321.930/s`、trades=`10,167.379/s`；计数、错误、资金、状态与snapshot恢复全部闭合。
- Lane operations从PV-117的`12,943.408/s`降至`9,266.414/s`，settlement从`10,449.687/s`降至`6,697.562/s`，证明batch终态stamp和Lane watermark已折叠进原settlement事件，owner不再为pipelined PLACE batch同步等待这两次Lane往返。总吞吐仅比PV-117提高约`3.01%`且低于55k门禁，当前限制已经转到owner per-item/result/commit与matcher completion侧。
- artifact `main.json` SHA-256=`1821f40c21ce4b8c966d436039f23bbff601ac0f52f5ad569ec33eee92d86bc0`；未执行GC或长稳。

### 2026-09-05 09:20:54 +08:00 — `PV-20260905-256-119` — `采集前锁定（Lane最终提交后JFR诊断）`

#### 采集前锁定

- 使用PV-118完全相同代码与mixed业务场景，固定matcher=1、4 Lane、1,000 users、256 symbols、20 items/batch、32 lifecycle symbols和严格256 in-flight；JFR为`fork=0,warmup=1x3s,measurement=1x10s,threads=1`，8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary、`owner-commit-profile.jfc`。
- 要求业务/Core计数闭合、错误与unfinished为0、资金及snapshot恢复通过、DataLoss=0；重点确认owner路径不再出现batch `stampChangedOrdersByLane/stageLaneMutation`，并定位result materialization、snapshot/index commit、matching completion及allocation top site。artifact固定`target/qualification/20260905T092054Z-lane-final-jfr-256/`；不测试范围沿用PV-118。该轮只用于归因，不替代无profiler吞吐。

#### PV-119采集结果

- JFR轮为`41,649.122 terminal business ops/s`，Core messages=`4,189.903/s`、trades=`9,857.689/s`；计数、资金、snapshot和`DataLoss=0`门禁通过。Lane settlement=`6,551.320/s`，owner同步batch `stampChangedOrdersByLane/stageLaneMutation`已退出真实路径。
- 332个execution samples中`awaitMatchingResult`以`5.72%`成为第一热点，`drainLaneReadyNotifications`为`1.81%`；JFR栈和harness源码确认每次`drainSubmitted`固定先同步等待最老命令，再批量泵其余255个，导致每个HFT阶段人为建立串行边界。该等待不是生产completion API要求。
- 18秒4次ZGC，最高GC前heap=`2.4GiB`、GC后=`376MiB`，最长pause=`0.0525ms`。分配约58.19%在owner/JMH worker、matcher约13.63%、4 Lane约26%；主要站点仍为runtime primitive map、change buffer、codec/order/result。artifact `mixed.jfr`/`jfr.json` SHA-256=`004851842a9fe34e185265f157ee8e0ac140795b197cbd0623bce034a27de02d`/`c6b49a9bd868362bfe7afb3bac88866d221b8c7b3c1a29aadf39da753ba84333`。

### 2026-09-05 09:32:00 +08:00 — `PV-20260905-256-120` — `采集前锁定（纯batch completion pump诊断）`

#### 采集前锁定

- 候选在PV-119代码上删除benchmark harness每个HFT阶段固定一次的同步`drainOldestLatencyNanos/awaitMatchingResult`，统一使用生产式`commitReadyMatching`批量pump，单次最多提交256个terminal Core message。同步等待改为持续drain matcher/Lane completion，只有sequence明确ready才进入下一提交阶段；同一Core sequence的多item batch每次从sequence context取得最新matcher completion，禁止复用上一item结果。该修复不改变matcher prefix、owner sequence提交、Lane单写、资金或snapshot fence语义。
- 诊断场景固定`LinearPerpetualCoreBenchmark.liquidationWithTrading`：LINEAR_PERPETUAL、matcher=1、4 Account Lane、1,000 users、256 symbols、20 items/batch、1 HFT round、32 lifecycle symbols/run、做市/risk/强平/保险/ADL在场，严格且仅`256 in-flight`、100,000 offered、open-loop并修正coordinated omission。JVM固定8GiB ZGC、AlwaysPreTouch、DisableExplicitGC；JMH固定`fork=1,warmup=1x3s,measurement=1x5s,threads=1`。
- 数据有效性要求accepted/terminal business及Core分别相等、两个unfinished为0、rejected/error/timeout为0、期末backlog为0，并通过资金守恒、余额/冻结、持仓、订单/强平终态与snapshot恢复。诊断吞吐目标为`>=55,000 terminal business ops/s`，且不再出现同步`awaitMatchingResult`调用；不满足则如实标记失败并继续定位，不升级为正式验收。
- 环境：Oracle GraalVM Java HotSpot 25.0.1、Maven3.9.16、MacBookPro16,1 / Intel i9-9880H / 8物理16逻辑CPU / 16GiB / macOS26.7 x86_64，swap=`762MiB`。HEAD=`d261926b82e8b18dc8eac82311fec0111dc6b510`加未提交候选；排除本记录的候选源码diff SHA-256=`0977809f5198c02f2553a402d725f59e38ade3c43389652a0d6435cef3ed46d7`，shaded JAR SHA-256=`2effeef17dd95e130ef7e78f4e48b6b5e92242e718d13db039e2160584100689`；artifact固定`target/qualification/20260905T013200Z-batch-completion-pump-256/`。
- 采集前HotSpot25真实mixed benchmark支撑测试`12/12`通过，shaded JAR构建成功，`git diff --check`通过。不测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data、外部Aeron Cluster及其他五产品线。采集开始后不修改代码、场景、参数或门禁。

#### PV-120采集结果（吞吐改善但未达门禁）

- mixed诊断为`49,002.142 terminal business ops/s`，其中trading=`48,711.438/s`、lifecycle=`290.704/s`、Core messages=`4,929.889/s`、trades=`11,597.961/s`；accepted/terminal business及Core闭合，unfinished/rejected/error/timeout为0，资金与snapshot恢复检查通过。
- 相对PV-118的`42,957.969/s`提升约`14.07%`，证明每阶段强制同步`awaitMatchingResult`确实形成了额外串行边界；但结果仍低于55k诊断门禁，不能作为正式性能验收。Lane operations=`10,610.511/s`、settlement=`7,659.109/s`，剩余限制需用同代码JFR重新归因。
- artifact `main.json` SHA-256=`154041627c43fbddf13cf5c8fcce5f2671f3892bb5eead6288059e3358ce2785`；本轮未执行GC或长稳。

### 2026-09-05 09:33:06 +08:00 — `PV-20260905-256-121` — `采集前锁定（completion pump后JFR诊断）`

#### 采集前锁定

- 使用PV-120完全相同代码和业务场景执行短JFR，固定matcher=1、4 Lane、1,000 users、256 symbols、20 items/batch、32 lifecycle symbols和严格256 in-flight；JMH固定`fork=0,warmup=1x3s,measurement=1x10s,threads=1`，宿主8GiB ZGC、AlwaysPreTouch、DisableExplicitGC、NMT summary，JFR使用`surprising-aeron-core/surprising-aeron-benchmarks/config/owner-commit-profile.jfc`。
- 有效性要求业务/Core计数闭合、错误及unfinished为0、资金与snapshot恢复通过、`DataLoss=0`。重点检查`awaitMatchingResult`是否退出owner样本，以及新的owner、matcher、Lane、allocation、GC、锁/park、safepoint、I/O与异常热点。该轮只用于归因，不替代无profiler吞吐。
- 代码、JAR与环境哈希沿用PV-120；artifact固定`target/qualification/20260905T013306Z-completion-pump-jfr-256/`。不测试范围沿用PV-120，采集开始后不修改代码、场景或参数。

#### PV-121采集结果

- JFR轮为`44,066.483 terminal business ops/s`，trading=`43,805.349/s`、Core messages=`4,433.072/s`、trades=`10,429.845/s`；业务/Core计数、错误、资金和snapshot门禁闭合，`DataLoss=0`。`awaitMatchingResult`已完全退出owner execution samples。
- 342个execution samples中，`pumpMatchingCommitCompletions`为`3.80%`、primitive map get约`3.22%`、ThreadLocal get约`2.63%`、`drainLaneReadyNotifications`约`1.46%`；无单一业务方法占据主要CPU。记录包含`1,328,440`次ThreadPark，owner completion等待和后台worker均采用微秒级park；1µs owner park实际常为约5–7µs，成为流水线调度抖动来源。后续仅将专用owner的两个completion等待点改为持续spin，保留deadline及worker空闲策略。
- 4次ZGC，最高GC前heap=`2.4GiB`、对应GC后=`560MiB`，最长GC pause=`0.276ms`；owner/JMH worker分配约`2.4GiB/60.58%`，matcher约12.67%，4 Lane合计约24%。最长`HandshakeAllThreads`=`368ms`，其中一个safepoint同步约146ms，属于本轮profile/JIT干扰，JFR吞吐不与无profiler主轮比较。无owner同步数据库、网络或业务文件I/O。
- artifact SHA-256：`mixed.jfr`=`3be16aaee92ab804482aed4867b2c07f2518b9842e9b6d15a8b30867916158ee`、`jfr.json`=`62a565b9b2c02a8d982051c07f7438124ed4fa1f8078f1f9897f27edaaa411f5`；summary、hot methods、allocation与GC视图保存在同目录。

### 2026-09-05 09:38:21 +08:00 — `PV-20260905-256-122` — `采集前锁定（owner completion busy-spin诊断）`

#### 采集前锁定

- 候选仅把专用owner在`awaitAnyMatchingCommitReady`及已派发Lane continuation等待中的1µs park改为持续`Thread.onSpinWait`；deadline与中断检查保持，matcher/Lane worker空闲策略不变。目标是消除PV-121确认的微秒级调度让出，不改变业务、sequence、Lane或snapshot语义。
- 场景、JVM、JMH和有效性门禁与PV-120相同：LINEAR_PERPETUAL、matcher=1、4 Lane、1,000 users、256 symbols、20 items/batch、32 lifecycle symbols、严格256 in-flight；8GiB ZGC，`fork=1,warmup=1x3s,measurement=1x5s,threads=1`。诊断目标为高于PV-120的`49,002.142/s`且达到`>=55,000 terminal business ops/s`，所有业务/Core/资金/订单/持仓/强平/snapshot门禁必须闭合。
- HEAD=`d261926b82e8b18dc8eac82311fec0111dc6b510`加未提交候选；排除本记录的源码diff SHA-256=`27468f3de6f0caac7f9fd43310c172cf5fdd7b258273d2834ec80204fbfb10b3`，shaded JAR SHA-256=`50acc5063dcb858f43ca8bf1c51ba055a1a89a7609c3435121c82e55c7e35d29`；artifact固定`target/qualification/20260905T013821Z-owner-completion-spin-256/`。
- HotSpot25真实mixed benchmark支撑测试`12/12`通过，构建与`git diff --check`通过。不测试范围沿用PV-120；采集开始后不修改代码、场景、参数或门禁。

#### PV-122采集结果（失败，已撤销）

- mixed为`36,018.513 terminal business ops/s`，trading=`35,804.650/s`、Core messages=`3,623.830/s`、trades=`8,524.917/s`；正确性门禁闭合，但相对PV-120下降约`26.50%`，未达到吞吐门禁。
- 持续spin使owner与matcher/Lane争用可运行CPU或同核调度资源；PV-121的ThreadPark总数同时包含所有后台worker，不能直接归因成owner吞吐损失。本候选撤销，恢复PV-120的先spin后1µs park策略。artifact `main.json`保留用于反证。

- PV-122 `main.json` SHA-256=`ff1e1ee536814eb528b176fae6fd443e9558995c52764f038491ee5252330dac`。

### 2026-09-05 09:40:59 +08:00 — `PV-20260905-256-123` — `采集前锁定（completion架构正式主轮）`

#### 采集前锁定

- 被测候选为PV-120正确版本：生产式batch completion pump、sequence ready后提交、同sequence多item取得最新matcher result，以及per-sequence admission/snapshot/changed-key/funds context、multi-settlement in-flight、Lane batch最终提交；PV-122的纯busy-spin已完全撤销。owner不再调用同步`awaitMatchingResult`，pipelined PLACE batch不再为stamp/watermark增加Lane往返。
- 正式场景与PV-107/PV-109口径一致：LINEAR_PERPETUAL、matcher=1、4 Account Lane、1,000 users、256 symbols、20 items/batch、1 HFT round、32 lifecycle symbols/run、做市/risk/强平/保险/ADL在场，严格且仅256 in-flight、100,000 offered、open-loop/coordinated-omission corrected；8GiB ZGC、AlwaysPreTouch、DisableExplicitGC，`fork=1,warmup=3x3s,measurement=3x5s,threads=1`。
- 通过阈值为`>=75,000 terminal business ops/s`；必须同时满足accepted/terminal business及Core相等、两个unfinished为0、rejected/error/timeout为0、期末backlog为0，并通过资金守恒、余额/冻结、持仓、订单/强平终态及snapshot恢复。三个样本全部记录，不以最佳值替代平均值。若失败则不继续GC/长稳正式验收。
- 环境沿用PV-120；HEAD=`d261926b82e8b18dc8eac82311fec0111dc6b510`加未提交候选，源码diff SHA-256=`0977809f5198c02f2553a402d725f59e38ade3c43389652a0d6435cef3ed46d7`，shaded JAR SHA-256=`b0cfad0823b8b2bf0f8d35666e37d21ea97c4dd9f005c28353b4d190d7442df3`；artifact固定`target/qualification/20260905T014059Z-completion-final-main-256/`。
- HotSpot25真实mixed benchmark支撑测试`12/12`通过，构建与`git diff --check`通过。不测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data、外部Aeron Cluster及其他五产品线。采集开始后不修改代码、场景、参数或门禁。

#### PV-123采集结果（同机干扰，无效）

- 三个样本为`25,318.698/29,649.957/34,829.389 terminal business ops/s`，平均`29,932.682/s`；业务正确性闭合，但该轮采集期间发现两组此前启动的`jfr print --json | jq`仍残留运行，其中一个jq持续占用约97%单核，两个JFR展开进程占用大量内存，swap从采集前762MiB升至约28.4GiB。
- 该轮发生明确CPU竞争和严重swap，违反数据有效性条件，不能作为代码性能结论。已按PID确认并终止仅由本次诊断启动的六个残留进程；随后系统free memory恢复到75%、swap回落至约2.3GiB。artifact `main.json` SHA-256=`dac1af0a9ac0525272b87f78dddd02a1ddebe5a365a52cda6d2d6a87ddb0f3a0`。

### 2026-09-05 09:43:00 +08:00 — `PV-20260905-256-124` — `采集前锁定（清除同机干扰后的正式重采）`

#### 采集前锁定

- 代码、JAR、业务场景、JVM/JMH参数、75k吞吐门禁及全部正确性门禁与PV-123完全相同；唯一变化是清除PV-123确认的残留JFR JSON展开进程。采集前无其他JFR/JMH/java压测进程，system-wide free memory=`75%`，swap约`2.3GiB`且不再快速增长。
- 源码diff SHA-256=`0977809f5198c02f2553a402d725f59e38ade3c43389652a0d6435cef3ed46d7`，shaded JAR SHA-256=`b0cfad0823b8b2bf0f8d35666e37d21ea97c4dd9f005c28353b4d190d7442df3`；artifact固定`target/qualification/20260905T014300Z-completion-final-clean-main-256/`。不测试范围沿用PV-123，采集开始后不修改代码、场景、参数或门禁。

#### PV-124状态（采集前取消）

- 用户要求先重跑历史79.6k版本再与当前候选对比；PV-124尚未启动任何JMH采集，故取消并由PV-125成对对照记录替代。

### 2026-09-05 09:45:00 +08:00 — `PV-20260905-256-125` — `采集前锁定（PV-109历史版本与当前候选成对重跑）`

#### 采集前锁定

- 对照版本固定为PV-109提交`d4e0a4c8`，在独立detached worktree构建并先运行；候选为当前HEAD `d261926b82e8b18dc8eac82311fec0111dc6b510`加未提交代码，随后运行。每个版本均使用其提交内真实benchmark driver，比较的是完整版本行为；由于当前版同时修正了completion pump计时边界，本轮不是只替换生产类的微基准，结论需同时说明driver差异。
- 两边命令参数完全相同：`LinearPerpetualCoreBenchmark.liquidationWithTrading`，LINEAR_PERPETUAL、matcher=1、4 Lane、1,000 users、256 symbols、20 items/batch、1 HFT round、32 lifecycle symbols/run、严格256 in-flight；8GiB ZGC、AlwaysPreTouch、DisableExplicitGC，`fork=1,warmup=3x3s,measurement=3x5s,threads=1`。先旧版、后当前版，不并行运行。
- 两边均要求accepted/terminal business及Core闭合、unfinished/rejected/error/timeout为0、期末backlog为0，并通过各版本内置资金、订单、持仓、强平终态与snapshot恢复。主要比较terminal business ops/s、Core messages/s、trades/s、Lane operations/s及三个原始样本；任何同机CPU/内存干扰或swap快速增长均使对应轮无效。
- 环境为HotSpot25.0.1、Maven3.9.16、Intel i9-9880H、16GiB、macOS26.7；采集前free memory=`75%`、swap约`2.0GiB`，无JFR/JMH/java压测残留。旧版JAR SHA-256=`3ecc5bb84774eab826f24159ea92cf47a83dcc88133b707e61ece15a507ef2d8`，当前JAR SHA-256=`b0cfad0823b8b2bf0f8d35666e37d21ea97c4dd9f005c28353b4d190d7442df3`；artifact固定`target/qualification/20260905T014500Z-pv109-vs-current-256/`。
- 两边shaded JAR均构建成功；不测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data、外部Aeron Cluster及其他五产品线。采集开始后不修改代码、场景、参数或比较顺序。

#### PV-125采集结果

- PV-109提交`d4e0a4c8`本次重跑为`56,216.947 terminal business ops/s`，三个样本`64,128.175/50,067.026/54,455.640`；Core messages=`5,655.660/s`、trades=`13,305.602/s`、Lane operations=`68,081.179/s`、Lane settlement=`66,677.932/s`。未复现2026-09-04采集的`79,629.981/s`，说明历史绝对值受到机器时段、热状态或同机环境影响，不能直接归因成后续代码回退。
- 当前候选紧邻重跑为`56,879.114 terminal business ops/s`，三个样本`61,475.373/53,499.383/55,662.588`；Core messages=`5,722.260/s`、trades=`13,462.330/s`、Lane operations=`12,346.279/s`、Lane settlement=`8,904.538/s`。相对同机旧版平均提高约`1.18%`；Lane operations下降约`81.86%`、Lane settlement下降约`86.65%`。
- 两版accepted/terminal business与Core均闭合，unfinished/rejected/error/timeout为0，内置资金、订单、持仓、强平终态和snapshot恢复通过。当前版没有出现此前怀疑的性能大幅回退，但每版仅3个且波动较大的样本，`+1.18%`不宣称统计显著；可确认的是新架构以基本持平吞吐显著减少Lane任务与往返，并修复了旧driver每阶段强制同步等待的计时边界。
- artifact SHA-256：`pv109.json`=`8330e1b6c3160456728ca0ce1d1c45acce816401088ff2449f55d165c01d96a2`，`current.json`=`2e8fa6ce7baa68cfd602db876d6a0d50fbaff7bab3d80aeba58bccb58fb82366`。本轮为成对性能诊断，未执行GC/JFR或长稳，不升级为75k正式吞吐验收。

### 2026-09-05 10:11:30 +08:00 — `PV-20260905-256-126` — `采集前锁定（重启后PV-109与当前版成对重跑）`

#### 采集前锁定

- 用户完成整机重启后，重新成对运行PV-109提交`d4e0a4c8`与当前提交`872293cb`。顺序仍为旧版先、当前版后；两个版本分别使用自身提交内benchmark driver，比较完整版本行为，并明确保留driver计时边界差异。
- 两边固定同一命令口径：`LinearPerpetualCoreBenchmark.liquidationWithTrading`，LINEAR_PERPETUAL、matcher=1、4 Account Lane、1,000 users、256 symbols、20 items/batch、1 HFT round、32 lifecycle symbols/run、严格且仅256 in-flight；8GiB ZGC、AlwaysPreTouch、DisableExplicitGC，`fork=1,warmup=3x3s,measurement=3x5s,threads=1`。报告terminal business ops/s、Core messages/s、trades/s、Lane operations/s及三个原始样本。
- 有效性要求两边accepted/terminal business及Core闭合、unfinished/rejected/error/timeout为0、期末backlog为0，并通过各自内置资金、余额/冻结、订单、持仓、强平终态与snapshot恢复。采集前系统uptime约11分钟、free memory=`84%`、swap=`0`，XProtect启动扫描已结束，无JFR/JMH/java压测残留；若采集中出现明显同机干扰或swap增长则该轮无效。
- 环境：Oracle GraalVM Java HotSpot25.0.1、Maven3.9.16、Intel i9-9880H 8物理16逻辑CPU、16GiB、macOS26.7 x86_64。旧版JAR SHA-256=`725f7e9f9871fb78030dee4fe6f8e7fd9a9116b79bb509b3804a25f02d378a98`，当前JAR SHA-256=`b0cfad0823b8b2bf0f8d35666e37d21ea97c4dd9f005c28353b4d190d7442df3`；artifact固定`target/qualification/20260905T021130Z-reboot-pv109-vs-current-256/`。
- 两边shaded JAR均构建成功。不测试PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data、外部Aeron Cluster及其他五产品线。采集开始后不修改代码、场景、参数或比较顺序。

#### PV-126采集结果

- 重启后PV-109提交`d4e0a4c8`重跑为`54,966.218 terminal business ops/s`，三个样本`61,933.189/50,326.584/52,638.881`；Core messages=`5,529.832/s`、trades=`13,009.575/s`、Lane operations=`66,566.198/s`、Lane settlement=`65,194.325/s`。旧版仍未复现历史`79,629.981/s`。
- 当前提交`872293cb`紧邻重跑为`57,335.397 terminal business ops/s`，三个样本`60,732.249/54,430.956/56,842.987`；Core messages=`5,768.148/s`、trades=`13,570.329/s`、Lane operations=`12,451.213/s`、Lane settlement=`8,978.771/s`。相对同机旧版吞吐提高约`4.31%`，Lane operations下降约`81.29%`，Lane settlement下降约`86.23%`。
- 两版accepted/terminal business及Core闭合，unfinished/rejected/error/timeout为0，资金、订单、持仓、强平终态和snapshot恢复通过。测试后free memory=`74%`、swap仍为`0`，无残留JMH/JFR/java压测进程，数据有效。结论：重启清除了swap和残留任务干扰，但79.6k历史绝对值仍不可复现；同一重启窗口内当前版没有性能回退，并以略高吞吐显著减少Lane任务。
- artifact SHA-256：`pv109.json`=`0c33d1f17f0a30b74ddb2a517eb59dad69a9e5825908691f3d660a3ccbb56a5c`，`current.json`=`9458e5837cc5465256aeceba137b754a62c8098514f5e6324c8e0d9d233d6ccc`。本轮未执行GC/JFR或长稳，不能据此声明75k正式性能验收或生产容量。

### 2026-09-05 10:43:00 +08:00 — `PV-20260905-256-127` — 采集前锁定：五项交易路径优化

- 被测代码 `7d08fef3`；对照 `386ffeac`（生产代码与 `872293cb` 相同）。基线 detached worktree `/private/tmp/surprising-five-baseline-wTgsKx` 只加入同一份新 benchmark driver，SHA-256 `b58ec6f4ab1a4b562805a4694a322c707c81b829b009591512dc0fc2e9642d60`。已有 mixed driver 不变，测试期间不改代码或参数。
- 改动：同 matcher 连续撤单合并任务；batch 最终响应单次物化、取消 executions 汇总复制；admission 仅预分配目标 Lane 实际写入缓冲；无异步查询时跳过 pending-client 扫描；仅运行指标查询放开撮合 fence，业务查询和非撮合写入仍保序，fence 后允许连续交易重新填充窗口。此外修复现货 batch inline Lane 通知被误判为单笔 continuation 的路由错误。
- 环境：已检查 java/mvn，Oracle GraalVM 25.0.1 HotSpot、Maven 3.9.16、macOS 26.7 x86_64、Intel i9-9880H、8物理/16逻辑CPU、16GiB；采集前 swap=0。所有轮次 matcher=1、Account Lane=4、严格256 in-flight。JVM统一 `-Xms4g -Xmx4g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -Dsurprising.aeron.matching-engines=1 --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED`。4GiB与历史8GiB不同，仅比较本轮同参数结果。
- 主轮：`fork=1,threads=1,warmup=3x3s,measurement=3x5s`，每轮间冷却20s；顺序固定 baseline mixed → current mixed → baseline service-linear → current service-linear → current service-spot。主吞吐不得用带 profiler 的数值代替。
- mixed 场景：`LinearPerpetualCoreBenchmark.liquidationWithTrading -p accountLanes=4 -p activeUsers=1000 -p symbols=256 -p hftRounds=1 -p hftBatchSize=20 -p maxInFlight=256 -p lifecycleSymbolsPerRun=32`。U本位永续、模板每用户最多5持仓/10挂单，资金和初始持仓沿用固定 driver 的真实模板；maker/taker、双向批量挂单/成交/撤单及风险扫描、强平、保险、ADL按 `executeHftBurstsPipelined` 与 lifecycle 固定循环执行，不将数据依赖的强平次数强行混成固定订单比例。进程内做市流动性持续存在，外部连接0；闭环饱和负载、无固定到达率、未修正 coordinated omission。
- service 场景：`ClusteredBatchTradingBenchmark.batchPlaceCancelWithMetrics -p accountLanes=4 -p batchSize=20 -p maxInFlight=256 -p productLine=LINEAR_PERPETUAL|SPOT`。256交易用户+1 maker、1模拟ClientSession、1symbol，每轮256个place batch后256个cancel batch，20 items/batch、每batch一个LANE_METRICS_QUERY；业务操作下单/撤单各50%，无成交，查询另计。用户各10亿USDT，初始无持仓；maker在120挂1单位卖单，用户90买单不交叉，现货maker另有1 BTC。真实service编解码、pendingClients、非等待completion pump和egress均计时，模拟transport/无外部Aeron。每轮512 Core messages、10240 business ops、512 queries，最大窗口256；撤单后用户可用恢复、冻结/持仓/reservation为空，只保留maker订单。iteration teardown比较资金与snapshot恢复。
- 通过门槛：相关功能测试全通过（当前82项）；两套主轮相对各自基线终态business ops/s不低于95%，但三个样本仅用于诊断、不宣称统计显著。所有场景accepted/terminal business及Core闭合，unfinished/end backlog/error/timeout=0，主场景拒绝0；资金/余额/冻结/持仓/订单与snapshot正确。CPU明显干扰、swap增长或JFR DataLoss使该轮无效，失败照常记录。baseline spot已知可能存在batch通知错误，不采为可比较基线。
- 归因轮：current mixed、service-linear、service-spot各 `fork=1,warmup=1x3s,measurement=1x10s`，同时 `-prof gc`、NMT summary/退出统计、明确JFR `target/qualification/20260905-five-paths-256/profile.jfc`（项目owner配置，ThreadPark阈值10ms、monitor阈值1ms，减少idle事件开销），保存原始jfr及GC/safepoint日志。记录采样与profiler扰动，不与主轮绝对吞吐横比。
- 长稳：同一候选 `LinearPerpetualScaleSoakMain 1000 256 256 5 10 UNIFORM 1 20 32 600 30`，10分钟、30s间隔，真实mixed state持续复用、256窗口。相同JVM和JFR/NMT参数，检查多轮GC后live set、oldgen、direct/mapped、线程、FD和buffer余额；沿用内置门槛：live set 1MiB/s、native buffer 256KiB/s、threads/FD/buffer count 0.01/s，至少3个GC后样本。短轮不用于无泄漏结论。
- 原始artifact统一 `target/qualification/20260905-five-paths-256/`，执行入口为该目录 `run.sh`（参数按本记录固定），测试日志为 `/tmp/surprising-five-final-tests.log`。采集后追加完整命令/校验、结果与JFR分析。
- 范围：已测现货、U本位永续服务路径和直接受影响运行时/资金测试。未启动 PostgreSQL/exporter/wallet/Kafka/外部Aeron Cluster/API/WebSocket/market-data，其他四产品线未做真实服务级场景；共享资金矩阵不能替代其端到端验收。缺少分业务三段尾延迟、真实网络/外部做市或native库pool证据时，只能标记部分性能验证，不宣称100k容量或六产品线完整验收。

#### PV-127执行与结果（2026-09-05 10:43～11:05 +08:00）

- 代码/测试/README已提交推送 `7d08fef3`。HotSpot25 Maven精确测试共82项通过：service 68项、benchmark 14项，覆盖现货重复撤单/缺失订单/跨用户拒绝、同shard撤单队列提交次数、解冻与snapshot、运行指标/业务query fence、目标Lane预分配、RuntimeChangedIndexCommit、永续资金矩阵与mixed workload。新service场景现货/永续均连续运行两轮；现货测试曾暴露inline batch Lane通知误路由，修复后重新跑完上述测试。
- 构建/执行命令：`mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am '-Dtest=ClusteredBatchTradingBenchmarkTest,LinearPerpetualBenchmarkSupportTest,CoreOrderedOrderBatchTest#coalescesSameMatcherCancellationsAndPreservesRejectedItemOrder+rejectsMixedUserCancelBatchBeforeAnyMutation,SurprisingClusteredServiceTest#operationalQueryDoesNotFenceLaterTradingButBusinessQueryDoes+deferredIngressCommitsMatchingBeforeTheFollowingFact+backgroundWorkAppliesMatchingExactlyOnceWithoutAReplicatedTimer+restoresSuccessfulSnapshotRoundTripWithoutTimingPoll,TradingRuntimeStateTest,RuntimeChangedIndexCommitTest,RuntimePerpetualMatchProcessorTest,CorePerpetualFinancialMatrixTest' -Dsurefire.failIfNoSpecifiedTests=false package`，日志见 `maven-tests.log`；性能执行 `bash target/qualification/20260905-five-paths-256/run.sh`，该脚本保存了全部实际JMH/JVM/soak命令。主轮与归因轮全部正常结束，soak exit=0。
- 基线JAR SHA-256=`b935e181574a2b7fff711fe69b0b1cd2579c8338171593ce57c8b73c25fa9237`；候选JAR=`e047da2e31b753a7679b1ff56ac964d3cacdb8e5d8f3b806e969ec4147e2b6e0`。`profile.jfc`=`4dbdbd4994757dc2e6930dee513d8ee298d687d9f298bc27434455d499784dc7`，`run.sh`=`96aa8db571f7c7ab737973810ea4d14bf935e067406ea1c3b1bde6ef79e7e3e6`。全部原始/聚合artifact校验见同目录 `SHA256SUMS`。

| 无profiler场景 | baseline terminal business ops/s | current terminal business ops/s | current Core messages/s | current trades/s |
| --- | ---: | ---: | ---: | ---: |
| 永续 risk/liquidation mixed | 40,520.265 | 72,674.834 | 7,311.184 | 17,200.960 |
| 永续 service批量下单/撤单+metrics | 68,349.863 | 73,433.687 | 3,671.684 | 0 |
| 现货 service批量下单/撤单+metrics | 未采集 | 47,360.998 | 2,368.050 | 0 |

- mixed baseline三样本=`45043.723/39448.463/37068.609`，current=`72163.219/72272.078/73589.204`；JMH误差分别约`±74692.802/±14480.709 ops/s`（仅3样本的99.9% CI非常宽）。点估计提升79.35%，不得宣称统计显著或稳定提升79%。Lane operations从8741.112/s到15850.065/s、settlement从6317.736/s到11413.010/s，是吞吐升高后的每秒计数，不应解释为每operation任务变多。
- service benchmark主分数是完整cycle/s，必须乘`10240 business items/cycle`；其AuxCounters使用`EVENTS`，单位`#`是样本累计数量，**不能把1044480等计数当作ops/s**。baseline/current线性永续为`6.674791/7.171259 cycles/s`（换算误差`±6381.624/±5005.783 business ops/s`），点估计提升7.44%；现货`4.625097 cycles/s`（换算误差`±16926.853`）。两套可比较主轮均满足预定95%无明显回退门槛，但都是部分诊断而非容量验收。
- service的Core command messages=same batches，items/s=business ops/s，平均/最大batch size均20；每batch另有1个metrics query，query responses/s分别为同一场景的batches/s，不混入交易Core command分母。mixed按固定driver约3440 batches/s、68804 batch items/s，其余为单笔/风险业务；原始指标与业务动作不可跨场景混用。
- mixed accepted/terminal business和Core均相等，unfinished/rejected/error/timeout均0；service每cycle校验512 batch终态与512 query结果，每item均APPLIED、无成交，并检查用户冻结归零、maker资金/挂单、终态订单回收和snapshot。service当前最大pendingMatching=256、baseline=1（指标查询fence使旧版内部串行，外部仍按256窗口投递），两版期末backlog=0。当前未把现货旧版的通知错误转成可比较性能结果。

#### PV-127分配、JFR与边界核对

| current归因场景 | `-prof gc` 分配率（JMH MB/sec） | 约 bytes/business op | MXBean GC count/time |
| --- | ---: | ---: | ---: |
| mixed | 421.705 | 7,034.6 | 10 / 319ms |
| service永续 | 660.490 | 10,389.8 | 10 / 188ms |
| service现货 | 384.732 | 9,265.6 | 12 / 87ms |

- bytes/op由每cycle分配归一到business items，service包含模拟客户端编码/解码及校验，mixed与service不横比；没有本轮baseline GC对照，不宣称分配已降低某个百分比。MXBean计数与JFR逻辑GC周期口径不同。
- 录制配置还会启用既有 `OpenLoopBusinessLatencyRecorder`：mixed/soak在每次run按100000计划business ops/s记录入口时间，包含其busy-spin；主轮未启用该记录器。归因/长稳不仅有JFR开销，还存在此负载驱动差异，不能替代无profiler主吞吐。这一实际边界按源码和JFR补充记录，采集前参数未被修改。
- 原始JFR：`profile-mixed.jfr` 6,483,649 bytes/约17s，`profile-service-linear.jfr` 5,430,417 bytes/约15s，`profile-service-spot.jfr` 4,946,706 bytes/约15s，`soak.jfr` 91,108,501 bytes/约617s（包含fixture、600s循环、snapshot/recovery和退出）。四份 `jdk.DataLoss=0`。SHA-256依次为 `651899e7bf8079f72b4531d86676f7e605ae4fbe0b19e64f5cf36d47bcadba87`、`7338b95bd56a00ad198e8a0490aff9736795fcde1f29c412110cd41280e92fe9`、`48a85738c73d1f2ffd60385cb9687a19bfda39dcb4369493bcf01b97152439c7`、`abb65cff788248009797f3cb01fa7917b986e60a5bbcd1f5e2ff4688cb29e5f0`。
- 分析命令：`jfr summary <file>`；`java -Xmx256m -m jdk.jfr/jdk.jfr.internal.tool.Main view --width 160 <view> <file>`；`java -Xmx256m JfrRead.java <file>`。采用流式RecordingFile分析，不输出整份JSON再交给jq；保存各文件 `*-summary.txt`、`*-analysis.txt` 和hot-methods/thread-cpu-load/allocation/GC/safepoint/VM/NMT/JIT/I/O等17个 `*-view-*.txt`。
- mixed分配采样owner/harness约55.44%、Lane29.73%、matcher14.24%；top class为long[]、byte[]、OrderRuntime、Object[]、Long、CoreMatchingResult。service永续owner/harness约80.65%，现货约73.31%，其中包含模拟客户端response解码。snapshot/query采样归在实际执行owner/Lane线程，risk同样在Lane执行；没有独立Fact/export/Aeron/Kafka线程在本轮运行，不能借此评价这些外围组件。
- mixed JFR记录380 execution+57 native samples；owner/harness 311、Lane68、matcher51、其余7，不能把包含park与驱动逻辑的样本直接称为纯业务CPU占比。mixed机器/进程CPU平均约23.9%/21.2%（机器总CPU归一）。短归因录制仍覆盖大量JIT：mixed编译7971次、总33.78s、最长614ms，不能视为完全预热后的精确CPU归因。
- mixed JFR单次GC pause最大0.036ms；safepoint同步最大0.406ms、VM op最大0.591ms。GC后heap最大555.7MB，JVM heap committed固定4GiB。NMT退出total reserved/committed分别：mixed约74.531/4.445GB，service永续74.527/4.427GB，service现货74.522/4.422GB；reserved是虚拟地址预留，不是RSS。全部NMT category的first/last/max/delta见analysis与view，不能以Direct=0替代NMT。
- mixed/service永续/service现货的JavaExceptionThrow分别1328/1280/1282，JavaErrorThrow分别153/129/130；含JDK MethodHandle/类加载探测，事件数不是业务错误数。I/O路径为JAR/JNI加载、benchmark stdout、JMH fork控制socket；未见交易业务同步数据库或外部网络路径。保留原始异常类型/站点与file/socket视图，不把启动和harness I/O隐去；生产实网I/O边界未验收。
- 分业务三段延迟已按已有CSV histogram合并，完整p50/p90/p95/p99/p99.9/max、sample count在mixed/soak analysis末尾。直方图为2次幂桶，分位值是桶上界，max为精确值；范围1ns～30s，包含warmup/fixture且计划到达器按run重置。短profile实际LIQUIDATION仅4样本、ADL仅2样本，不足以评价其尾延迟；service新场景未埋三段延迟，吃单未独立分类、snapshot只测恢复总时长，均属于验收缺口。

#### PV-127十分钟长稳与最终结论

- `LinearPerpetualScaleSoakMain`运行601.802s，25,287,812 terminal business operations、2,543,748 terminal Core messages；平均42,020.139 business ops/s、4,226.884 Core messages/s，maxMatchingBacklog=256。资金不变量PASS、snapshot=16,268,323 bytes、恢复770.310ms。窗口吞吐从首30秒62,415.203下降到末段36,461.119；没有baseline长稳，因此不把下降直接归因本次改动，也不宣称持续达到72.7k。
- 循环末仍有199个可恢复的分片risk scan工作流、funding incomplete=0；这是已完成Core命令留下的后续workflow状态，不是199条未终态Core message。本场景不能声称所有强平/风险工作流都已全量排空。
- 内置448个GC通知样本的leak verdict=PASS，报告median slope=0。独立JFR `GCHeapSummary`在排除初60s后重新计算的GC后heap稳健斜率约75,448 bytes/s，**不是严格零增长**，低于锁定的1MiB/s门槛。GC后最大532,676,608 bytes，末次494,927,872 bytes；每30秒oldgen约166～233MB。线程/FD持续14/13，Direct/Mapped bytes/count均0，swap全程0；`pmset -g therm`未记录thermal/performance warning，scheduler/speed limit=100，可用CPU=16。不能仅凭本轮断言永久无泄漏或完全没有频率变化。
- 长稳NMT退出reserved=74,543,576,027、committed=4,456,719,323 bytes；GC category committed约4.7→42.2MB、峰值51.0MB，Code21.5→38.9MB、峰值39.8MB，Metaspace23.3→34.2MB；Other34,816→36,864 bytes、峰值45,056。框架native pool/外部Aeron/Netty未启动，缺少其独立分配/释放余额，不能外推生产native泄漏结论。
- 长稳逻辑GC94周期（ZGC major并非停顿式Full GC），累计pause7.867ms/约0.0013%，364个pause phase的p50/p95/p99/max=`0.015/0.048/0.056/0.064ms`；safepoint同步最大0.102ms、VM op最大3.197ms。编译10686次/总52.69s/最长1.755s；deoptimization按reason保存在view。未见allocation stall、晋升/疏散失败或DataLoss。JFR采样对象权重282.49GB，TLAB266.83GB、非TLAB16.50GB，最大已记录对象16,777,232 bytes；对象数/operation无法由采样权重精确还原。
- 后段热点证据：`OpenLoopBusinessLatencyRecorder.enter`（计划到达率busy-spin）1541个采样；`TradingRuntimeState.triggerOrdersForRuntime` 在Lane内及owner查询边界构建TreeMap，`TradingCoreState.validateTrigger` 与监控 `incompleteFundingSettlements→tradingState→RuntimeStateMaterializer.materialize`全量物化也反复出现；TreeMap.Entry占长稳分配权重约51.90GB。每分钟分配约27～28GB而吞吐下降，提示状态相关物化成本值得下一轮消融。另有 `requiredReservationPrepared→effectiveLeverage→TradingRuntimeState.leverage→onLane` 的同步回读样本，不能宣称owner已经完全不等待。上述不属于本次五项，未在采集过程中继续改动，也未据采样给出虚构收益。
- 长稳ORDER_BATCH三段p99桶上界分别134.218ms（entry→accepted）、67.109ms（accepted→terminal）、134.218ms（entry→terminal），p99.9为268.435/134.218/268.435ms，max为487.747/182.001/551.938ms，样本1,197,056；不能与单条GC pause混成同一延迟或作为无profiler API SLA。
- **结论：五项实现、82项相关测试、现货/永续JMH/JFR及十分钟资金/snapshot/内存趋势验证完成；性能验收仍为部分验证。** 两套主对照满足预定不回退门槛，但仅三个样本、无baseline allocation/长稳对照，profile存在计划到达器和观察器成本；尚无生产API/真实Aeron/WebSocket、完整各业务尾延迟及其他四产品线服务级证据，不宣称100k或72.7k持续生产容量。

### 2026-09-05 12:34:04 +08:00 — `PV-20260905-256-128` — 采集前锁定：当前 master 交易路径优化

- 被测 commit：`e2b693e8`（包含 protocol `395462e9`、client `7966d8b5`、gateway `85285b3f`）。对照 commit：N/A，仅当前 master；不检出、构建或运行旧版本，不作历史吞吐对照。候选 JAR SHA-256=`48b44787ae68d6653b480a400dfb030bca312b0eed8bf17eaab957ba751af4fd`。
- 实现范围：批量永续杠杆/风险/冻结下沉 Lane，保留预准入失败后有序逐项执行；删除无消费者终态审计 digest；深度 fill primitive 去重与 Lane 事件索引；只扩容触及 Lane；trigger 用户索引与 risk 无复制游标读取；目标保险赔付免全局结果 Map/排序；batch frame 直接编码/限界解码；client 有序 deadline、唤醒和局部 payload 免复制；egress 条数/字节/回收容量及 drain budget；普通 gateway 不计算管理审计 hash；去掉长稳计数全状态物化及 JFR 隐式改变负载。
- 未完成架构范围必须保留在结论：risk successor 仍需线性查找，风险 continuation 仍有同步 Lane 调用；同 symbol batch 互斥、非撮合写入/业务 query fence、snapshot fence 未删除；客户端响应仍扫描 pending，mailbox/生命周期锁和同步 future callback 未全部消除；网关同步代理/实时鉴权查询未改。不能用本轮局部实现宣称十几项全部完成或 owner 完全不等待。
- 功能证据：HotSpot25 下已通过 224 个相关测试（跨多次精确运行合并，CoreOrderedOrderBatchTest 13个非旧导出路径用例按唯一方法计数）。覆盖六产品线资金矩阵和 snapshot，真实256窗口深度成交/六产品线 workload、batch部分拒绝/重复client撤销预冻结、故障后停止推进、terminal订单回收、客户端重连/超时及 gateway。首次扩展回归暴露离线 risk scope 不兼容，已修复；旧测试同步完成/终态保留断言已按当前异步/回收契约修订并回归。四个旧导出相关 batch 测试首次被误纳入并失败，按用户“不测 exporter”要求未作为本轮验收；不是全量 Maven 通过声明。原始 surefire XML 位于各受影响模块 target/surefire-reports。
- 构建命令：`mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am -DskipTests package -q`。测试主命令为 `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks,surprising-gateway -am -Dtest=<精确类/方法> -Dsurefire.failIfNoSpecifiedTests=false test -q`；精确类/统计将随结果附于 artifact。
- 环境锁定：macOS26.7 x86_64、Intel i9-9880H、8物理/16逻辑CPU、16GiB；Oracle GraalVM25.0.1（Java HotSpot 64-Bit Server VM）、Maven3.9.16；java/mvn路径一致，采集前swap=0。并行压测 JVM=1；各轮前后保存进程CPU/RSS、swap和 thermal 状态。禁止 OpenJ9/其他 in-flight。
- JVM：`-Xms4g -Xmx4g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -Dsurprising.aeron.matching-engines=1 -Dsurprising.benchmark.openLoop=false --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED`；JMH驱动进程heap256MiB。全部 matcher=1、Account Lane=4、最大在途窗口严格256，risk batch预算不等于in-flight。所有负载为闭环饱和、无固定到达率、未修正 coordinated omission；不能称真实 open-loop API SLA。
- 主轮参数：fork=1、threads=1、warmup=3×3s、measurement=3×5s、轮间冷却20s。主吞吐取无 profiler 结果；三样本置信区间必须报告，不宣称统计显著。主目标为 U本位永续真实 mixed 持续终态业务吞吐≥100,000 terminal business ops/s；未达即目标未达。其余场景用于影响面/瓶颈诊断，不用无成交批量场景替代 mixed 容量。没有旧版本对照，不能量化改动收益百分比。
- 正确性/数据有效性：acceptedBusinessOperations=terminalBusinessOperations、accepted/terminal Core messages闭合、unfinished/end backlog=0；每轮无业务错误/超时，主场景拒绝0，资金/冻结/仓位/终态订单与snapshot恢复通过。明显同机干扰、swap增长、throttling或JFR DataLoss使该轮无效，照常记录。所有业务延迟需按类型报告三段分位及max；当前仅部分driver具备分类型埋点，缺项不得宣称完整性能验收。
- 场景A（主目标）：`LinearPerpetualCoreBenchmark.liquidationWithTrading`，activeUsers=1000、symbols=256、hftRounds=1、hftBatchSize=20、maxInFlight=256、lifecycleSymbolsPerRun=32。用户最多5持仓/10挂单；初始资金/持仓沿用当前模板；双向 maker/taker 批量挂单、成交、撤单与risk/强平/保险/ADL按真实固定driver执行，重操作次数受状态影响，报告实际业务/消息/fill计数，不伪造固定风险百分比。
- 场景B：`ClusteredBatchTradingBenchmark.batchPlaceCancelWithMetrics`，SPOT和LINEAR_PERPETUAL分别测试，256交易用户+1maker、1模拟ClientSession、1symbol、batchSize=20，每cycle256 place batch+256 cancel batch=10240 business items/512 Core messages，另512 metrics query；下单/撤单各50%，fills=0。用户各10亿USDT、初始无持仓，maker在120挂1单位卖单、用户90买单；现货maker另有1BTC。资金、剩余唯一maker订单和snapshot恢复在teardown验证。真实service回调/编解码，传输是模拟的，不代表真实Aeron吞吐。
- 场景C：`SpotCoreBenchmark.productionMixedWorkload`，以及 `DerivativeCoreBenchmark.productionMixedWorkload` 的INVERSE_PERPETUAL、LINEAR_DELIVERY、INVERSE_DELIVERY、OPTION，各activeUsers=1000、symbols=256、hftRounds=1、hftBatchSize=20、maxInFlight=256。新driver支持256symbol并在JMH入口强制256窗口，已以真实256命令单测验证。每轮执行双向挂单/撤单/部分成交，衍生品追加对应风险/资金费/交割/期权生命周期；各产品线独立instrument、结算资产和初始资金，按源码Profile创建，使用各自资金守恒/snapshot校验，不能与场景A当作同一混合负载比较。
- 场景D：新增 `LinearPerpetualCoreBenchmark.deepFillBurst256`，makerDepth=8、maxInFlight=256、4maker+256taker、1symbol，初始每用户10亿USDT、2048个1单位maker订单，256个IOC吃单每单8fill。每invocation256 business ops/Core messages、2048fills；校验总余额+手续费=期初、净持仓0、无终态reservation/live订单、snapshot恢复。fixture/恢复位于JMH invocation边界，不把其吞吐当作持续服务容量。
- 所有场景做市流动性由进程内maker持续提供，外部API连接0（B是1个模拟连接），没有独立生产做市进程。主轮顺序A→B永续→B现货→C现货→C四衍生品→D；随后相同顺序的归因轮，各fork1/warmup3×3s/measurement1×10s、冷却20s、`-prof gc`，同时NMT summary/退出统计、GC/safepoint日志、JFR明确配置。Profiler吞吐只归因，不替代主轮。
- JFR配置 `target/qualification/20260905-chain-256/profile.jfc` SHA-256=`4dbdbd4994757dc2e6930dee513d8ee298d687d9f298bc27434455d499784dc7`，profile派生配置、ThreadPark阈值10ms、monitor阈值1ms；启用TLAB/非TLAB/分配采样、线程、GC、safepoint、JIT、NMT及I/O事件。配置存在采样和低于阈值盲区；不能用“没有park样本”证明零等待。每份保存jfr summary、低heap流式JfrRead及view聚合，分类owner/matcher/Lane/risk/snapshot/外围，报告DataLoss和预热/JIT影响。
- 长稳：同候选执行 `LinearPerpetualScaleSoakMain 1000 256 256 5 10 UNIFORM 1 20 32 600 30`，600s有效循环、30s采样，闭环256，JFR/NMT/GC配置同归因轮。记录多轮GC后live set/oldgen、Direct/Mapped/native、线程/FD及buffer数量；沿用门槛live set<1MiB/s、native buffer<256KiB/s、线程/FD/buffer count斜率<0.01/s且至少3个GC后样本。短JFR不证明无泄漏。风险workflow余量另报，不与已终态Core消息混淆。
- 执行入口：`bash target/qualification/20260905-chain-256/run.sh`，SHA-256=`dc256891bbe96564fcac193e55d3852064e6434970587c262dffd84954d2d516`。原始artifact全部在该目录；结果按时间追加本文，不修改本条口径。未启动/未验收PostgreSQL、exporter、wallet、Kafka、真实Aeron Cluster网络、API/WebSocket/market-data、生产native pool；这些缺口及未完成架构不因局部基准通过而消失。

### PV-128 结果与终止（2026-09-05 12:53 +08:00）

- 被测 `e2b693e8`，无旧版对照。原始数据目录 `target/qualification/20260905-chain-256/`；输入 jar SHA-256=`48b44787ae68d6653b480a400dfb030bca312b0eed8bf17eaab957ba751af4fd`。
- 无 profiler 的 U本位永续真实 mixed：132522.0845 terminal business ops/s，三次样本109919.1743/138073.2944/149573.7849，JMH 99.9%误差±372205.022 ops/s；13331.3344 terminal Core messages/s，31365.9869 fills/s。置信区间过宽，仅短测诊断，不能宣称持续100k容量或优化增益。
- service 批量挂撤：LINEAR_PERPETUAL 109053.611 terminal business ops/s（误差±145066.266）、5452.68055 Core messages/s；SPOT 47844.5191 business ops/s（误差±50598.6013）、2392.22596 Core messages/s。均无成交，不替代 mixed。已完成场景accepted/terminal闭合、unfinished/期末backlog=0、错误/超时/拒绝=0；JMH AuxCounters EVENTS 是计数不是速率。
- SPOT 完整 mixed 最后一次 teardown snapshot 抛 `invalid snapshot section length`，停止整个轮次。前两次36799.861/38600.716 business ops/s仅保留为故障诊断，不能作为通过结果。其余四产品线、deep、GC profiler/JFR/NMT及600s长稳未执行，三段尾延迟和长期内存未验收。
- 追加回归测试确认 SPOT 逐项批量成交保留终态订单（`spot retained a terminal order: 2002944`），见 `spot-retention-regression-before.log`。第一次修复被资金守恒断言拦截，见 `spot-retention-regression-after.log`，未关闭/削弱该断言；后续修改必须新建采集轮次。swap=0；现有数据不支持无泄漏或完整性能验收结论。

- 修复后 `spot-retention-regression-after-2.log` 通过：Lane 在原 settlement 回收终态，余额端点合并整批原始资金边界，256在途连续三轮检查及snapshot通过。扩大测试另发现迟到 admission 通知路由越界、prepared position key 普通HashMap并发扩容、衍生品driver使用序号生成未来标记价时间；分别修正跳过已提交通知、并发索引和逻辑时钟，未放宽生产风险校验。
- `spot-fix-affected-tests.log` 和 `spot-fix-affected-tests-2.log` 保留失败证据；后者service资金/恢复/批量13方法均通过，benchmark深成交8次通过但重复衍生品因未来标记价失败。最终 `identity-and-repeated-lifecycle.log` 的 identity并发回归、现货多轮、五条衍生品各8轮、深成交8次及其余Linear支持测试全部通过。上述是功能测试，不作为性能结果；未跑exporter/PG。

## PV-20260905-256-129：现货终态及Lane并发修复后重新验证（采集前锁定，13:02 +08:00）

- 被测commit=`fdeb207e`，对照commit=无（不运行旧版本）。新增变更：SPOT逐项批量Lane终态回收与整批余额端点合并、迟到admission通知过滤、position正向索引并发发布、衍生品driver标记价时间修正。jar SHA-256=`579fd694200d9553a6e01d555a69b1cea2eab6638d0bd77aab062dfb5a873768`。
- 明确沿用PV-128锁定的机器/JDK、完整JVM/GC参数、场景A/B/C/D及资金持仓初态、并发用户/连接/symbol、业务计数口径和有效性条件：HotSpot25.0.1、Intel i9-9880H 8C16T、16GiB、macOS26.7；4GiB ZGC、AlwaysPreTouch、DisableExplicitGC、matcher=1、Lane=4、256in-flight；闭环饱和，CO未修正。一次只运行一个被测JVM；全部六产品线独立执行。主目标仍为场景A≥100000 terminal business ops/s，accepted=terminal、unfinished/期末backlog=0、资金/冻结/仓位/终态/snapshot正确。无profiler主轮f1/t1/wi3×3s/i3×5s，冷却20s，误差区间如实报告。
- 九个主轮及九个profile顺序与PV-128相同；profile f1/t1/wi3×3s/i1×10s、`-prof gc`，启用NMT summary与退出统计、gc/safepoint日志、自定义JFR。JFR配置SHA-256=`4dbdbd4994757dc2e6930dee513d8ee298d687d9f298bc27434455d499784dc7`，NativeMemoryUsage/DirectBufferStatistics/ThreadAllocationStatistics 1s周期、execution20ms、TLAB/非TLAB分配及其他原配置事件；512MiB recording上限，检查实际覆盖时长与DataLoss。profiler数值不代替主吞吐。
- 永续长稳沿用 `LinearPerpetualScaleSoakMain 1000 256 256 5 10 UNIFORM 1 20 32 600 30`：600s/30s采样、同JFR/NMT。额外SPOT长稳使用场景C的SPOT JMH，wi3×3s、i1×600s、f1/t1、gc profiler/JFR/NMT，以teardown资金和snapshot恢复检查验证终态增长修复。长稳沿用live set<1MiB/s、native buffer<256KiB/s、线程/FD/buffer count<0.01/s、至少3个GC后样本；缺失的spot FD/原生pool或三段业务分位明确列为部分验证，不从短测推出无泄漏。
- 执行 `bash target/qualification/20260905-chain-256-r129/run.sh`；脚本SHA-256=`b3152f7fee06c2576f7d93344f370ba92bc478ed7885fb5694deec17e4113f56`，所有原始artifact在该目录。命令失败即停止后续轮次，修改代码/场景需再新建记录。未测试边界与PV-128相同：PG/exporter/wallet/真实Kafka-Aeron网络/API-WebSocket/native生产pool；仍不宣称所有架构优化已完成。

### PV-129 终止结果与证据修正（2026-09-05，按采集顺序追加）

- 九个主轮和九个短profile完成，但整轮无效/部分诊断：①主轮期间WallpaperAerials、视频解码、Metal编译及Spotlight出现明显CPU干扰；②长稳约430s出现 `matching batch completion crossed submission order`，600s未完成、SPOT600s未启动；③复核发现mixed只完整校验最后一个batch，可能漏掉中间item拒绝。因此撤回PV-128/PV-129中“mixed拒绝率0”的推断，不把任何历史reported terminal ops/s当作全成功业务容量。短轮资金余额与snapshot检查通过不等于每个批次item均成功。
- 场景C覆盖范围修正：源码只执行订单生命周期、risk和适用产品资金费，未执行真实到期交割/行权/期权失效。此前锁定记录中的“交割/期权生命周期”表述过宽；本轮不能提供该范围验收证据，不修改已采数据或原定义。

| 主轮（无profiler，仅诊断） | reported terminal business ops/s | terminal Core messages/s | fills/s |
|---|---:|---:|---:|
| U本位永续real mixed | 43658.270（99.9%误差±159045.704） | 4392.363 | 10333.134 |
| service永续批量挂撤 | 40613.507（cycle×10240） | 2030.675 | 0 |
| service现货批量挂撤 | 28611.340（cycle×10240） | 1430.567 | 0 |
| 现货mixed | 21744.314 | 2070.887 | 未埋点，不记0 |
| 币本位永续mixed | 48820.502 | 4653.679 | 未埋点，不记0 |
| U本位交割mixed | 48073.134 | 4580.416 | 未埋点，不记0 |
| 币本位交割mixed | 41615.724 | 3965.153 | 未埋点，不记0 |
| 期权订单mixed | 43113.233 | 4107.836 | 未埋点，不记0 |
| 深成交8 fills/order | 8684.681 | 8684.681 | 69477.450 |

- 主轮完整误差、参数及raw samples见各`main-*.json/log`。service AuxCounters为EVENTS（#），表中由cycle主分数换算，不能把累计item计数当每秒吞吐。短轮已有driver的accepted/terminal、unfinished和期末backlog检查通过，窗口上限256；因漏检item状态不能据此认定错误率/拒绝率/超时率全0。非service各业务真实fill计数、三阶段按类型完整尾延迟等缺项保留。

| gc profiler场景 | 分配MiB/s | bytes/JMH cycle | GC次数/时间ms |
|---|---:|---:|---:|
| 永续real mixed | 253.781 | 154132999.579（约7125 B/business op） | 4/622 |
| service永续 | 260.693 | 94225118.400 | 2/133 |
| service现货 | 162.412 | 107680781.176 | 6/117 |
| 现货mixed | 158.927 | 279627328.000（约13004 B/business op） | 6/202 |
| 币本位永续 | 374.313 | 260738270.588 | 8/914 |
| U本位交割 | 302.126 | 177217876.400 | 4/433 |
| 币本位交割 | 541.213 | 241576506.462 | 14/1471 |
| 期权订单 | 360.012 | 168711834.667 | 4/448 |
| 深成交 | 628.218 | 100865243.273 | 11/1401 |

- profiler记录包含setup/warmup/teardown，GC总时间含并发工作，不等于停顿。deep每invocation新建/恢复fixture，分配含fixture，不可当成纯撮合每fill成本。以上每business op为driver计数换算，漏检拒绝问题同样影响该分母。
- 长稳最近一次正常采样421.640s；前150s约50k→78k，后台CPU下降后连续30s窗口约148k～150k（同一commit，没有换版本）。未完成窗口及可能存在的item拒绝使其不能认定持续100k达标。线程14、FD13、Direct/Mapped bytes/count均0、swap0；风险workflow未完成数在约183～225波动，不能与未终态Core消息混淆。
- 原始JFR：九个短文件约5～8MiB，`soak.jfr`约81MiB、summary434s、分析434.372s；所有10个summary的`jdk.DataLoss=0`。`*-summary.txt`、`*-analysis.txt`保存原始摘要/聚合，`jfr-sha256.txt`保存全部JFR SHA-256。采集命令见run.sh；分析命令为`jfr summary <file>`及`java -Xmx256m target/qualification/20260905-chain-256/JfrRead.java <file>`，均在性能进程停止后执行。
- 长稳JFR CPU样本按线程：owner/harness12853、Lane3440、matcher1207、外围8；owner包含基准驱动，不是纯service owner。热点为`CommandFingerprint.of`/SHA、completion pump、Lane通知和settlement dispatch；matcher含`MatcherPrefixDigest`。Java分配采样权重总313399972096B（约721.5MB/s，含全录制），owner约154.6GB、Lane109.2GB、matcher49.6GB；top class为long[]、byte[]、OrderRuntime、Object[]、Long、NativeCommand/CoreMatchingResult、LongHashSet、ReservationRuntime、ThreadLocal.Entry。TLAB约294.6GB、非TLAB约19.7GB、最大采样对象8388624B。采样权重不提供精确对象数/op。
- Heap/JIT/VM：JFR After-GC100点，最后530579456B、最大773849088B；排除首60s稳健斜率约172163B/s，但430s失败轮不能证明无泄漏。MBean日志若为0是无效GC池聚合样本，不当作真实heap归零。GC pause sum累计8.881ms，p50/p95/p99/max=0.086/0.172/0.251/0.251ms；safepoint351次，begin max0.180ms、同步max0.158ms；Compilation8932次、总56.609s、最长4.314s（含预热，不能说明短测已经充分预热）。详细事件及available codecache/deopt/类加载指标见summary/analysis，未对全部编译阶段单独分窗验收。
- Native：退出NMT reserved74548625669B/committed4445204741B，ZGC巨量reserved是虚拟地址，不是RSS。JFR Heap committed固定4GiB；GC committed首4.5MB/末37.0MB/峰58.4MB，Code15.5→34.1MB、峰37.8MB，Class1.9→3.5MB，Thread约184→191KB、峰254KB，Other34.8→36.9KB。完整NMT category首末/峰值见soak-analysis；未验收生产Aeron/Netty/native pool分配释放余额。
- 线程/IO：ThreadPark5323次，总82.386s（跨线程相加，主要Lane空闲，非锁竞争业务耗时）；owner/harness park累计17.338ms。owner/main存在类加载文件读和soak采样`printf`文件写，不能据此声称owner无同步IO或完整主链路验收；它们需与真实业务热段分窗区分。锁对象/墙钟等待全分布、OS上下文切换/pagefault、全业务三段p50/p90/p95/p99/p99.9/max、native池余额和完整长稳资金/恢复终检仍不齐备，结论为未通过完整验收。
- 定位后的功能回归：`terminal-order-regression-before.log`确定性复现应回调[3,4,5]但只有[3,5]；背景全拒绝批次丢terminal。首次修复后的snapshot检查又暴露Core/projection fence混用；`terminal-order-regression-after-2.log`及`core-sequence-and-all-items-tests.log`修复后通过。新的`rejectedCancelContinuations`夹具已覆盖SPOT/LINEAR_PERPETUAL真实service、256窗口、254拒绝batch与相邻有效batch的顺序响应、资金及snapshot；不是容量基线。改动后必须另开PV-130，不能复用本轮数字。

- 追加修复验证均通过：`rejected-continuation-jmh-fixture-tests.log`、`matcher-scope-tests.log`、`fence-v19-and-matcher-tests.log`、`fresh-mark-driver-tests.log`。包含11个非exporter快照编解码/损坏/被动恢复检查、拒绝批次后余额调整及查询fence、v18明确拒绝/v19恢复、每batch所有item状态与截断扫描、撮合作用域时间戳隔离、256窗口深成交8次、各产品线混合/资金/恢复，以及逻辑时间推进6s后的报价刷新。报价刷新与risk完成状态解耦，按1000ms逻辑时间按需发真实mark命令并计入业务量；未改变生产新鲜度校验。此前长稳未记录全部item失败原因，不能把它的所有拒绝归因为价格老化，只能说明修复了确定存在的驱动边界缺陷。

## PV-20260905-256-130：逐批全item校验与Core fence修复候选（采集前锁定，2026-09-05 13:58 +08:00）

- 被测commit=`afbc1ab9`，无旧版对照。jar SHA-256=`d33012287336eda09dfdd1e1e06d795731cbe846394ddb705ad345fd3bbe068b`。更改为：全拒绝批次从顺序completion返回；所有Lane fence统一Core sequence、snapshot v19；每个mixed batch扫描所有item状态；每1000ms逻辑时间按需刷新mark且不等待risk完成；matcher复用primitive ThreadLocal作用域。前两轮不可作为已验证的全成功容量基线。
- 环境及完整JVM/JMH/JFR设置沿用PV-129：HotSpot25.0.1 Oracle GraalVM、Maven3.9.16、macOS26.7 x86_64、Intel i9-9880H 8C/16T、16GiB；`-Xms4g -Xmx4g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -Dsurprising.aeron.matching-engines=1 -Dsurprising.benchmark.openLoop=false --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED`。固定256in-flight、4 Account Lanes、1matcher，六产品线单独运行，做市流动性保留。无profiler主轮f1/t1/wi3×3s/i3×5s；profile wi3×3s/i1×10s、`-prof gc`+NMT summary+JFR+GC/safepoint；冷却20s。闭环饱和、无固定到达率、CO未修正。
- 主场景A依旧1000用户/256symbol、hftRounds1/hftBatchSize20/lifecycleSymbolsPerRun32，真实HFT、trigger、risk、强平、保险/ADL业务组合；比例由固定driver和状态产生，以实际终态/消息/fill计数报告。报价更新加入实测operation分母，不能再无条件使用固定21632/cycle。初始资金/仓位/用户分布保持模板，无自动补钱或风险豁免。成功标准：主轮及600s持续终态业务吞吐≥100000 terminal business ops/s，全部batch item成功、accepted=terminal、两类unfinished及期末backlog=0、资金/仓位/冻结/终态与恢复通过；任一未满足不得称目标完成。
- 场景B/C/D沿用PV-129实际源码口径和初态：service SPOT/LINEAR_PERPETUAL256用户+maker/1模拟会话/1symbol/20batch，挂撤各50%、每cycle10240items/512messages/512queries、fills0；SPOT和其他4衍生品mixed1000用户/256symbol/1round/20batch，订单与risk/适用资金费，不含真实到期交割/行权；深成交256taker/4maker/1symbol/8fills-order，256items/messages/2048fills，每invocation恢复fixture。PG/exporter/wallet和真实Aeron/Kafka/API/WebSocket/native生产pool不启动、不验收。
- 新场景E `ClusteredBatchTradingBenchmark.rejectedCancelContinuations`：SPOT、LINEAR_PERPETUAL各自执行；沿用service初态，256消息窗口由前后两个有效place batch和254个不存在订单的cancel batch组成，再发2个cleanup cancel batch。每cycle258messages/batches、5160items、其中5080个ORDER_NOT_FOUND拒绝，成功80items，fills0；需验证所有terminal确实返回、拒绝原因/数量精确、资金冻结恢复和snapshot。该场景的拒绝吞吐只能用于顺序边界诊断，不能作为100k交易容量。
- 顺序锁定：A主轮→Aprofile→永续600s长稳→SPOT主轮/profile→SPOT600s长稳→其余4衍生品各主轮/profile→service两产品线各普通主轮/profile及E主轮/profile→deep主轮/profile。永续长稳命令 `LinearPerpetualScaleSoakMain 1000 256 256 5 10 UNIFORM 1 20 32 600 30`，SPOT长稳JMH wi3×3s/i1×600s，均同JFR/NMT配置。
- 有效性/内存门槛沿用：明显后台CPU干扰、swap、throttling或DataLoss判为无效，失败即停止后续；live set<1MiB/s、native buffer<256KiB/s、线程/FD/buffer count<0.01/s且≥3有效GC后样本。MBean零值不可当作真实GC后heap，使用原始JFR交叉验证。尾延迟必须三阶段分业务；若现有driver缺项或owner/harness IO、native pool/OS指标未齐，仍只能部分验证。profiler数值不代替主吞吐，无基线不能量化优化增益。
- 执行 `bash target/qualification/20260905-chain-256-r130/run.sh`，SHA-256=`8c0d8efa7147306089674c0ece64317efd7e227ae114b4820a9cb2ce77785690`；配置SHA-256=`4dbdbd4994757dc2e6930dee513d8ee298d687d9f298bc27434455d499784dc7`，recording512MiB上限。原始artifact全部保存在该目录；开始后不修改代码/口径，结果按时间追加本文。

### PV-130 终止与初步结果（2026-09-05，按时间追加）

- 永续主轮154328.164 terminal business ops/s，99.9%误差±12694.222，三个样本153658.028/154279.379/155047.086；terminal Core messages/s=15524.848，fills/s=36527.189。已启用每批每item状态检查，accepted/terminal闭合、unfinished与期末backlog为0。无旧版对照，不能量化改动增益。
- 永续600s长稳完成：实际605.036s、66043580终态business ops、6644412终态Core messages，平均109156.464 terminal business ops/s、10981.847 messages/s，最大backlog256；终检资金与snapshot恢复通过（snapshot33725984B、restore2787.041ms）。sweep整体p50/p95/p99/max=148031.327/315594.359/357206.529/630890.351μs，不是分业务三阶段延迟。后台动态壁纸/视频解码/WindowServer再次干扰，后半段窗口约73–80k，不能认定各稳定窗口均达到100k或完整性能验收。MBean零值不作为内存验收证据。
- 负载覆盖澄清：JMH mixed每measurement iteration新建scenario，专用强平→保险→真实ADL链每scenario执行一次；后续循环以交易/trigger/risk/资金费为主。长稳整个scenario只执行一次专用loss链。以上数字不能表述为持续强平/ADL风暴的容量。
- SPOT主轮21951.900±64367.064 terminal business ops/s，后台CPU干扰明显。SPOT600s在JMH默认600s iteration timeout触发中断：日志明确`benchmark timed out, interrupted 1 times`，随后teardown的snapshot fence检测到interrupt并失败。没有完整终检，不能声称SPOT长稳通过；空soak-spot.json保留。后续4衍生品、service、拒绝续跑及deep轮次未执行。生产snapshot中断检查不应删除；修正测试timeout必须新开记录。

## PV-20260905-256-131：SPOT长稳超时配置纠正与剩余覆盖（采集前锁定，2026-09-05）

- 被测仍为afbc1ab9，jar SHA-256=d33012287336eda09dfdd1e1e06d795731cbe846394ddb705ad345fd3bbe068b；不修改生产代码、不运行旧版本。全部环境、CPU/JDK25 HotSpot、4GiB ZGC/JVM参数、固定256in-flight/4Lane/1matcher、用户/初始资金/做市流动性/产品线边界、业务动作比例、闭环CO未修正、恢复资金校验、有效性与内存阈值沿用PV-130逐场景定义。
- 唯一测试修正为JMH显式`-to 900s`，不是放宽业务超时或snapshot deadline。顺序：SPOT600s（wi3×3s/i1×600s/f1/t1/gc+JFR+NMT）→其余4衍生品各main/profile→service两产品线各普通及拒绝main/profile→deep main/profile。main仍wi3×3s/i3×5s，profile wi3×3s/i1×10s，冷却20s。A永续主轮/长稳不重跑，PV-130结果不与本轮混成一次验收。
- 标准：mixed成功item全成功、accepted=terminal、unfinished/期末backlog0、资金冻结/终态/恢复通过；容量目标仍100000 terminal business ops/s。拒绝场景期望5080拒绝/80成功/258messages每cycle，不算成功交易容量。长稳live set<1MiB/s、native buffer<256KiB/s、线程/FD/buffer count<0.01/s且≥3有效GC后样本；背景CPU/swap/throttling/DataLoss或缺失指标导致无效/部分验证。未测PG/exporter/wallet、真实网络/API/WebSocket、连续强平ADL风暴及生产native pool。任何失败立即停。
- 执行`bash target/qualification/20260905-chain-256-r131/run.sh`，脚本SHA-256=b928eee73c30103cabe9ad66af86a487ddc4100eb2a9036f785ea5c942bbf71b；JFR配置SHA-256=4dbdbd4994757dc2e6930dee513d8ee298d687d9f298bc27434455d499784dc7、512MiB上限。原始artifact保存在同目录；完整命令/参数以预先固定run.sh为准，不覆盖失败轮。

### PV-130 原始采样核对补充（PV-131采集期间仅分析已关闭artifact）

- 四份JFR均DataLoss=0，`jfr-sha256.txt`保存校验；永续soak111831146B、SPOT失败soak74192348B，其余短profile约6MiB。`*-summary.txt`与`*-analysis.txt`由HotSpot25的`jfr summary`和PV-129同一流式JfrRead工具生成，分析在PV-131启动前完成，未与性能进程争抢CPU。
- 永续全录制609.890s：分配采样权重407395281192B/667981572B/s，TLAB382682752400B、非TLAB25903769152B、最大对象33726000B。owner/harness分配201454475520B、Lane146594542096B、matcher59325491216B；CPU samples分别18180/4721/1626。owner主要为CommandFingerprint/SHA、completion pump、Lane ready、批次处理；matcher仍有evidence prefix/result包装。采样含预热与终检，不把权重/总时间当纯业务精确分配或对象数/op。
- After-GC130点，末505413632B、峰807403520B，剔除前60s稳健斜率71664B/s，低于预设1MiB/s；但只有本夹具600s证据，不能推断长期生产无泄漏。heap committed固定4GiB；NMT GC committed末39.98MB/峰61.59MB，Code末36.46MB/峰39.42MB，Thread末188080B/峰237320B，Other末36864B/峰45056B。ZGC heap reserved73GB是虚拟映射，不是RSS；生产native pool仍未覆盖。
- GC pause438次，累计11.560ms，p50/p95/p99/max=0.020/0.056/0.066/0.073ms；safepoint451次、begin max2.200ms、同步max2.193ms，需要纳入尾延迟而非忽略。Compilation10766次/45.601s/最长1.677s，Deoptimization598次，完整窗口包括预热。ThreadPark11426次，owner/harness累计171.961ms、Lane176.612s；不能将Lane空闲park等同于锁竞争。IO/类加载/JIT探测异常仍须与业务热段区分，不宣称owner生产IO门禁已通过。
- 分业务三阶段原始直方图聚合保存在soak-analysis，单位ms、2次幂桶上界；例PLACE_ORDER入口到terminal p50≤4.194304、p99/p99.9≤16.777216、max17.082612，样本1563136。未覆盖的API入口、批量item独立延迟、全部风险重操作及CO修正仍是验收缺口。SPOT失败录制After-GC93点、末287309824B/峰375390208B，不能替代失败终检或判定长稳通过。

### PV-131 SPOT长稳结果与后续排程终止（2026-09-05）

- 600秒measurement及最终逐批item/资金守恒/余额冻结/终态回收/snapshot恢复检查完成，无JMH timeout。带gc/JFR/NMT的诊断平均28059.050 terminal business ops/s、2672.290 terminal Core messages/s，accepted=terminal、unfinished及期末backlog0；不能作为无profiler主吞吐，也未达到100k。
- gc profiler分配299.478MiB/s、241365136.347B/JMH cycle，gc.count400/gc.time16863ms（并发GC时间不等于pause）。JFR72546395B、summary615s、聚合616.002s、DataLoss0；全录制分配权重192865787928B、313092795B/s，TLAB189755551344B、非TLAB3694603432B、最大对象8388624B。After-GC84点、末257949696B/峰337641472B；pause340次、累计7.043ms，p50/p95/p99/max=0.016/0.049/0.055/0.057ms。NMT heap committed4GiB，GC committed末19.58MB/峰42.29MB、Other末69632B/峰77824B、Thread末196936B/峰232200B。原始summary/analysis保存在同目录。缺失生产native池余额、完整分业务三阶段延迟、FD长稳序列及环境干扰限制仍保留，结论为功能长稳通过、性能部分验证。
- 完成SPOT后主动终止排程以继续实现现货batch Lane合并结算，不是用测试中途修改生产代码。INVERSE_PERPETUAL主轮已启动，随后连同本轮调度明确终止，输出仅作为中止记录；余下4衍生品、service、拒绝continuation与deep待最终代码新轮重跑。PV-131不得被标为全部场景完成。
- 后续实现定位：SPOT原逐item调用applyOrderBatchMatcherSettlement会同步等待Lane；现改为可流水化PLACE batch整批准入与一次/Lane最终结算，累计maker剩余量校验移至MatcherSettlementPlan共享协议边界，现货/衍生品金融内核不混用。单项/部分成功路径保留必要的逐项资金依赖。新回归覆盖四个taker吃同一maker、先卖后买收入复用、真实成交后fatal与快照重放；更新Spot JMH要求batch>=2并设置15min timeout。
- 首轮广泛回归97项有3个旧逐item回调/临时准入容器假设失败，见spot-pipeline-full-regression.log；改为显式测试顺序分支，并增加独立SPOT pipeline fatal恢复覆盖，不放松资金/序列断言。spot-pipeline-boundary-tests.log已通过；最终跨产品线回归与性能采集另记，不提前宣称完成。

## PV-20260905-256-132：现货批量Lane流水线候选（采集前锁定，2026-09-05）

- 被测commit=`2cd0bfb3`，jar SHA-256=`56c7635cb29dd6fb7f3768f936b8d9f903eb1544bf2d4bd886e105c5e23c66a2`，无旧版对照、不开历史分支。源码变更：SPOT PLACE batch复用异步Lane准入/一次每Lane结算；整批累计maker剩余校验移至MatcherSettlementPlan；保留资产内核与部分成功资金依赖，Spot JMH要求batch>=2/15min timeout。最终目标回归`spot-pipeline-final-tests.log`及package构建通过，包括17个非exporter ordered-batch方法、runtime/lifecycle、六产品线snapshot契约、Spot8轮复用、derivative/linear mixed、service普通和拒绝256窗口。
- 环境完整沿用PV-130：HotSpot25.0.1 Oracle GraalVM、Maven3.9.16、macOS26.7/x86_64、Intel i9-9880H 8C/16T、16GiB；`-Xms4g -Xmx4g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -Dsurprising.aeron.matching-engines=1 -Dsurprising.benchmark.openLoop=false --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED`。所有采样256in-flight、4Lane、1matcher，闭环饱和、无固定到达率、CO未修正，真实API并发连接不模拟为生产连接。
- 场景A～E按PV-130实际源码定义固定：mixed1000用户/256symbol/1round/20items batch，永续lifecycle32symbol/run及初始最多5持仓/10挂单；SPOT双向批量挂撤和IOC共享maker部分成交/剩余撤单，maker/taker资金与初态不变，maker流动性持续存在；其他四衍生品订单/risk及适用资金费，不含到期交割行权。永续专用强平/保险/ADL每iteration一次，不表述成连续重操作。service256用户/1symbol/20batch/1模拟会话，普通10240items/512messages、挂撤各50%；拒绝5160items/258messages，其中5080预期拒绝/80成功；deep256taker/4maker/1symbol/8fills每order，每invocation恢复fixture。
- 主轮f1/t1/wi3×3s/i3×5s、无profiler；profile wi3×3s/i1×10s/gc+NMTsummary+JFR512MiB/GC-safepoint log；冷却20s；所有JMH显式`-to 900s`。顺序SPOT main/profile→永续main/profile→其余四衍生品main/profile→service两个产品线普通及拒绝main/profile→deep main/profile→SPOT wi3×3s/i1×600s长稳（同profiler配置）。不重跑未改变长期状态的永续600s，PV-130同类长稳只能作为此前证据，不冒充本commit已重测。
- 通过阈值保持mixed≥100000 terminal business ops/s、所有成功场景每item成功、accepted/terminal business与Core messages相等、unfinished/期末backlog0、资金/冻结/仓位/订单终态/snapshot恢复通过；风险workflow未完不等同unfinished Core message。拒绝场景必须严格匹配拒绝数，不作为成功交易容量。Spot长稳live set<1MiB/s、native buffer<256KiB/s、线程/FD/buffer count<0.01/s且≥3有效GC后样本，缺项为部分验证。后台明显CPU干扰、swap/throttling/JFR DataLoss判无效；profiler不替代主吞吐，无旧版对照不能承诺提升百分比。
- 仍不测试PG/exporter/wallet、真实Aeron/Kafka/API/WebSocket和生产native池；完整每业务三段尾延迟/CO/OS与native余额缺项必须报告。任何失败即停，开始后不改生产代码或负载口径，新增变更另开轮。
- 执行`bash target/qualification/20260905-chain-256-r132/run.sh`，SHA-256=`65346f0189960d2c703f27ed1c2fac4370874d5d966dfc1ee738f357e7537541`，JFR配置SHA-256=`4dbdbd4994757dc2e6930dee513d8ee298d687d9f298bc27434455d499784dc7`。全部原始artifact保存该目录，JFR分析仅在性能进程结束后执行，历史失败/中止记录不覆盖。

### PV-132 系统限速终止（2026-09-05 15:01 +08:00）

- 明确发现系统CPU限速，按预定义门槛将本轮性能验收判无效并停止：main-SPOT结束`CPU_Speed_Limit=50`，main永续结束37，main币本位永续开始43/结束37，profile永续结束39，profile币本位永续开始41。详见`cpu-limits.txt`和完整system-before/after；Scheduler_Limit100、Available_CPUs16不代表CPU没有限速。停止时空闲报告Speed_Limit100、AC供电/电池100%；这些读数不能区分具体散热或电源管理原因，不擅自改系统设置。
- 四个main和四个profile完成；正在运行的INVERSE_DELIVERY main明确终止，空/不完整JSON保留。OPTION、两类service/拒绝continuation、deep及新SPOT600s尚未执行。前一版PV-131现货600s不能算作2cd0bfb3已完成长稳。最新代码目标回归及构建成功，但整轮性能/长期状态验收未完成。

| 已完成主轮（无效诊断，非容量结论） | terminal business ops/s | 99.9%误差 | terminal Core messages/s |
|---|---:|---:|---:|
| SPOT mixed | 70625.044 | ±294369.235 | 6726.195 |
| LINEAR_PERPETUAL mixed | 41350.450 | ±34786.025 | 4160.200 |
| INVERSE_PERPETUAL mixed | 35655.067 | ±88773.841 | 3398.721 |
| LINEAR_DELIVERY mixed | 156325.417 | ±36972.251 | 14894.712 |

- 已完成场景每batch item检查及accepted=terminal/unfinished/期末backlog0、最终资金/冻结/订单/恢复检查通过。业务ops与消息分开；Spot和通用derivative driver未逐笔输出fill计数，不记为0，也不以理论batch size代替实际成交。不能从不同产品线/限速程度/单一short窗口比较改动收益或宣称100k完成。

| gc profiler | MiB/s | B/JMH cycle（不是B/business op） | GC count / time ms（含并发） |
|---|---:|---:|---:|
| SPOT | 297.325 | 172922537.905 | 4 / 626 |
| LINEAR_PERPETUAL | 253.468 | 148378258.105 | 4 / 698 |
| INVERSE_PERPETUAL | 1104.593 | 236101314.353 | 18 / 730 |
| LINEAR_DELIVERY | 1029.951 | 157305517.746 | 14 / 456 |

- 四个JFR全部DataLoss0，summary时长SPOT27s/永续31s/币永续26s/线性交割22s；原始文件、`*-summary.txt`、`*-analysis.txt`、`jfr-sha256.txt`齐备。使用相同明确profile.jfc（高分配事件有开销）和相同流式聚合器，所有分析在压测停止后运行；不能把profiler吞吐替代main。SPOT全录制27.196s、分配权重6296259368B/231514170B/s、TLAB5957623880B、非TLAB344821304B、最大8388624B；owner/harness分配3860930272B、Lane1638533192B、matcher764078488B，CPU samples756/361/134。热栈仍是completion/Lane通知；录制还包含matcher native库初始化，不能当纯业务栈。
- SPOT短JFR After-GC5点、末/峰505413632B（不能推导无泄漏），heap committed4GiB；NMT GC末/峰29.64MB，Other末67584B/峰77824B，Thread末196936B/峰229664B。pause23次累计0.642ms、p50/p95/p99/max=0.031/0.042/0.042/0.042ms；safepoint28次begin最大0.377ms、同步最大0.362ms。完整JIT/锁/异常/IO/native类别见原始聚合，缺分窗生产IO判定、完整三阶段分业务尾延迟、OS上下文切换/FD/native池余额和新版本长稳，故只能部分验证。
- 追溯环境记录补充：PV-130 Spot main/profile结束Speed_Limit93/91，PV-131 Spot长稳结束52；这些轮次的性能结论同样无效，资金/恢复功能通过事实不变。PV-130永续main及soak前后读数100，但只有边界采样不能证明全过程从未限速；此前已因后台干扰/尾延迟等缺项判部分验证，不升级结论。
- 尚未实现/未完成的范围明确保留：risk successor虽然去复制但仍重复扫描、risk命令仍有owner等待；同symbol batch排他和顺序部分成功路径仍有串行依赖；客户端剩余pending响应扫描/同步future回调及网关同步代理/鉴权查询未重构；保险expectedCoverage已缩小查询，但未建立持久有序索引；真实egress压力/API-WebSocket及全业务open-loop指标未验收。不宣称“十几个问题全部解决”或owner完全无等待。继续更改前需在持续不降频的环境完成当前候选的剩余JMH/JFR/长稳验证。

## PV-20260905-256-133：关闭航拍并断开外屏后的五分钟诊断（采集前锁定）

- 被测master HEAD=1cf60d03，运行时代码2cd0bfb3，jar SHA-256=56c7635cb29dd6fb7f3768f936b8d9f903eb1544bf2d4bd886e105c5e23c66a2；无旧版对照。本次仅运行用户要求的300s U本位永续mixed，不修改生产代码。已确认纯色壁纸、外屏断开、内屏由Intel核显驱动；AC电源、低电量模式关闭，开始前Speed_Limit100、16可用CPU、swap0。
- HotSpot JDK25.0.1 Oracle GraalVM/Maven3.9.16、macOS26.7 x86_64/i9-9880H 8C16T/16GiB。JVM固定4GiB ZGC、AlwaysPreTouch、DisableExplicitGC、matching-engines1、openLoop=false及jdk.internal.misc opens/exports，另启NMTsummary、PrintNMTStatistics、512MiB JFR（沿用profile.jfc SHA-256=4dbdbd4994757dc2e6930dee513d8ee298d687d9f298bc27434455d499784dc7）和GC/safepoint日志。profiler有开销，本轮不是无profiler JMH主吞吐，不能替代正式容量验收。
- 场景固定256in-flight、4 Account Lane、1matcher、1000用户、256活跃symbol、UNIFORM、初始每用户最多5持仓/10挂单、hftRounds1/hftBatchSize20/lifecycleSymbolsPerRun32；资金/仓位沿用既有模板、进程内maker流动性保持、外部连接0。执行双向批量下单/成交/撤单、trigger/risk/资金费及每scenario一次专用强平→保险→ADL链；不是持续强平ADL风暴。闭环饱和无固定到达率、CO未修正，业务比例依固定driver与实际状态，不将fill计入订单ops。
- 执行`LinearPerpetualScaleSoakMain 1000 256 256 5 10 UNIFORM 1 20 32 300 30`；1 JVM/1 owner驱动，无单独预热，报告包含预热的全程平均，并单列30s窗口观察稳定阶段，不丢弃低速窗口。300s后完成当前业务周期及资金/余额/冻结/持仓/订单终态/snapshot恢复检查，终检时间单列；无额外性能轮。结束后至少冷却30s再考虑新采集。
- 目标≥100000 terminal business ops/s，accepted=terminal（business/Core分别闭合）、unfinished和期末backlog0、每batch每item成功、资金恢复正确；同时报告Core messages/s、可用延迟与最大backlog。每5s采pmset/交换空间、每30s采进程CPU；出现Speed_Limit<100、swap或DataLoss则容量结论无效，但保留完整300s诊断与正确性终检，不修改口径重新解释。后台显著干扰须记录。live set门槛<1MiB/s、native buffer<256KiB/s、线程/FD/buffer count<0.01/s且至少3个有效GC后样本；MBean零值不能认定heap归零，需核对JFR。
- 原始artifact目录`target/qualification/20260905-chain-256-r133`，执行`bash .../run.sh`，脚本SHA-256=c4ea2bfea48aac96b6755a4cb5e1c35ea316707c42d38e612d589e6fb2374abf。结束后用相同JfrRead和jfr summary聚合，避免分析与压测并行争抢CPU。未测其他产品线、PG/exporter/wallet、真实API/Aeron/Kafka/WebSocket、生产native池、完整分业务三阶段分位与长期泄漏；仅五分钟部分验证。

### PV-133 结果（2026-09-05 15:18 +08:00）

- 完成300s负载与资金/快照恢复终检，summary PASS/fundsInvariant=true。总终态business operations=44778960、Core messages=4505040；driver elapsed301.984s包含最终verify/snapshot/restore，平均148282.335 terminal business ops/s、14918.119 terminal Core messages/s，初始模板setup3364.166ms单列。最大matcher backlog256；逐cycle accepted/terminal闭合、每batch每item检查成功、unfinished及期末消息backlog0。期末216个可继续risk workflow不等于未终态Core消息，funding未完成0。没有逐fill计数输出，不推测fills/s。
- 九个完整30s窗口依次140094.912、150185.928、150438.162、149992.711、153833.306、151858.952、149500.660、148778.458、149323.934 ops/s；最后不足30s窗口未单独打印但计入总量。全程61次5s采样Speed_Limit全部100，swap0，线程14/FD13、Direct/Mapped bytes/count0。未见前轮的限速和吞吐塌陷；5s离散采样不能证明采样间隙毫无限速，也不能从一次设置变更断言具体原因。
- 全业务周期sweep p50/p95/p99/max=142566.754/163467.863/176285.043/507676.465μs，不是单订单延迟。JFR中三阶段按类型直方图及样本数完整保存在soak-analysis，单位ms、2次幂桶上界；PLACE_ORDER entry-terminal p50/p99≤4.194304、p99.9≤8.388608、max14.513136（n1059840）；ORDER_BATCH p50≤16.777216、p99≤33.554432、p99.9≤67.108864、max86.191431（n2119680）。批次指标不是item独立分位，CO未修正，API真实入口不覆盖。专用LIQUIDATION仅2条、ADL仅1条，不以这些少量样本认定持续重操作性能。
- Snapshot24617448B，恢复999.697ms，business state hash一致；资金守恒、余额/冻结/仓位和终态检查通过。MBean386个GC样本的零斜率不能直接作为无泄漏结论；JFR After-GC88点，末585105408B/峰729808896B，剔除首60s稳健斜率219476B/s，低于预设1MiB/s，但五分钟仅支持当前窗口趋势。
- 原始soak.jfr70660057B（约67.4MiB），summary305s/聚合305.974s，DataLoss0；`soak-summary.txt`、`soak-analysis.txt`、`jfr-sha256.txt`齐备。全录制分配权重272980957744B/892170438B/s，TLAB256224420880B、非TLAB17552299632B、最大对象33554448B；含setup/终检的粗略归一化约6096B/terminal business op，非精确对象数/op或JMH gc-profiler值。owner/harness分配137251708688B、Lane98119442584B、matcher37594004136B，CPU samples8953/2526/726；主要仍为owner命令与completion处理，不把busy-spin判为业务计算。原始top类/栈/线程与CPU load见聚合。
- GC pause304次，累计6.963ms、p50/p95/p99/max=0.015/0.051/0.057/0.060ms；safepoint314次begin最大0.135ms、同步最大0.102ms。JIT10892次/39.635s/最长1.715s，记录含预热；ThreadPark302次累计15.467s，主要Lane空闲。同步IO/异常包括夹具采样printf、类加载和JIT/native符号探测，不能冒充owner生产IO完整门禁通过。
- Heap committed4GiB；NMT GC committed末37.42MB/峰58.85MB，Code末34.71MB/峰38.31MB，Class末3.526MB/峰3.557MB，Thread末205832B/峰230952B，Other末36864B/峰45056B。各类别首末/峰值均在analysis，Direct/Mapped0不等于生产Aeron/Netty池已验收。OS页故障/上下文切换、全部native池余额、精确对象数/op、完整API三段分位及更长稳定性仍未覆盖。
- 结论：当前代码在关闭航拍/断开外屏后的指定永续mixed、256in-flight/1matcher/4Lane下，本轮五分钟带JFR诊断平均148.3k/s、各完整窗口均超过100k，功能与恢复通过；没有采到系统限速。不是无profiler JMH主分数、不是所有产品线/持续强平ADL/真实API的100k容量验收，也不表示此前尚未实现的优化已完成。本轮不修改生产代码。

### PV-134 采集前锁定：Cluster 确定性回调边界诊断（2026-09-05 15:58 +08:00）

- 被测代码：master 的 420c695754d47de73f07d5884f1b1354a49c9ecf 加本轮工作树修改；采集前保存 git diff/其 SHA-256 和被测 JAR SHA。对照 commit：不设旧版本对照，遵从只测试当前代码要求，不宣称改造收益百分比。
- 修改范围：交易/子撮合/异步查询在原始 onSessionMessage 返回前终态；doBackgroundWork 为 no-op；删除 deferred ingress/pending clients/后台 egress，响应在回调内最多重试1秒；异常转 AgentTerminationException；snapshot 拒绝遗留业务。内部 Lane 和账务计算未改。更新 callback JMH 的 maker 初始化和逐回调终态断言。
- 环境：HotSpot Oracle GraalVM25.0.1+8.1、Maven3.9.16、macOS26.7、i9-9880H 8C16T、16GiB；采集时记录 java/mvn、pmset、swap。JVM -Xms2g -Xmx2g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC，matcher=1，jdk.internal.misc opens/exports；profile 独立增加 NMT summary、PrintNMTStatistics、显式自定义 profile.jfc、GC/safepoint 日志。
- 场景：ClusteredBatchTradingBenchmark 的 batchPlaceCancelWithMetrics 与 rejectedCancelContinuations；SPOT、LINEAR_PERPETUAL 各自运行；maxInFlight=256 固定、accountLanes=4、batchSize=20、1 symbol、256交易用户+1常驻maker。每用户初始USDT=1e9，maker在120挂卖1，SPOT maker另有BTC=1，无初始仓位。第一场景每次512批/10240 business ops（挂买90/撤单各50%）+512 metrics queries，不成交；第二场景258批/5160 items，其中254批撤不存在订单，预期业务拒绝5080 items，技术错误必须0。
- 负载边界：本机串行模拟 Cluster 日志回调，256请求提交波次/固定窗口配置，不是网络上256并发连接或真实256在途占用，不能形成并发容量结论；无外部API/Aeron传输/三节点/数据库/Kafka/WebSocket，maker仅为持续挂单夹具。闭环饱和、无固定到达率、CO未修正。
- JMH主轮：1 fork、1线程、warmup=2x2s、measurement=3x2s；主分数为invocations/s，乘每次实际业务数换算 terminal business ops/s，同时报告Core messages/s、batches/s、items/s、fills/s=0及预期拒绝。GC独立轮：1 fork、warmup=1x2s、measurement=2x2s、-prof gc，分配B/invocation另除以业务数，不能当B/business op原值。
- JFR独立轮只采集第一场景，两产品线分别1 fork、warmup=1x2s、measurement=2x2s；使用 target/qualification/20260905-chain-256-r133/profile.jfc 同一配置，输出原始JFR/summary/流式JfrRead聚合/NMT。各阶段冷却30秒。记录setup/teardown和短预热开销，不把带profiler分数替代主分数。
- 数据有效性：每回调无pending matcher/query；逐批逐item终态检查；accepted/terminal一致、unfinished/endBacklog=0；余额/冻结/仓位/订单终态、maker资产和snapshot恢复必须通过；技术错误/超时=0；JFR DataLoss=0、采样CPU_Speed_Limit=100、swap=0。任一不满足标记失败/无效。吞吐不设100k通过门槛：本轮是架构正确性变更后的诊断，不是100k或HA验收。
- artifact：target/qualification/20260905-cluster-callback-256-r134/，run.sh保存完整命令，生成输出SHA清单。未覆盖真实3节点故障、API分业务三阶段全分位、真实native池/IO、长稳泄漏和其余四线JMH；这些缺口使本轮最多部分验证，不宣称完整交易链路验收。

### PV-134 结果（2026-09-05 16:03 +08:00）

- 定向构建通过：`mvn -pl surprising-aeron-core/surprising-aeron-service,surprising-aeron-core/surprising-aeron-benchmarks -am '-Dtest=SurprisingClusteredServiceTest,ClusteredBatchTradingBenchmarkTest,SharedProductLineSnapshotContractTest,CoreNativeSnapshotProductLineTest,CoreStateSnapshotCodecTest,CorePerpetualFinancialMatrixTest,CoreDeliveryOptionFinancialMatrixTest,RuntimeCommitRecoveryTest#replaysInsuranceResolutionAndAdlAcrossPairedClusteredSnapshotCuts' -Dsurefire.failIfNoSpecifiedTests=false package`。Service65项+benchmark4项，均0失败。覆盖原始回调完成、无session/不同role、256次连续挂撤、异步book query、后台无状态/egress调用、异常AgentTermination、六线快照、资金矩阵及强平→不足额保险→ADL前后快照恢复与重复命令。未启动PG/exporter/wallet/API服务。
- 失败轮次如实保留：首轮漏删引用所需WireMessageKind import导致编译失败，已修复；新增边界检查先于runtime激活导致首命令/空快照失败，已修复。扩大测试发现CoreOrderedOrderBatchTest的四项旧Core内部并行/export检查失败（isolatesOverlappingBatchesUntilTheActiveBatchCompletes、defersSinglePlacePreparationAndExportUntilTheActiveBatchCompletes、processesMaximumBatchesInInputOrder、exportsConservedFundsWhenABatchMatchesAnotherUser），本次未修改其生产路径或修订这些测试，不能宣称该类全绿。RuntimeCommitRecoveryTest初始四项export/对象身份比较断言失败；仅修订本次保险/ADL回调恢复路径，去掉已移除exporter的证明依赖，使用直接经济权益/保险/deficit/持仓/幂等/快照检查。夹具原基金非零，直接加25导致expectedCoverage=134而不是25，现显式调整基金至25，并把外部调账差额纳入资金守恒。索引恢复比较忽略可复用查询scratch，对OI可变值按内容比较。不声称旧export测试已全部修复，日志见earlier-*-failures.log。
- 被测JAR SHA-256=2da65d523d99810d784054d927856b34b0b1f212557fe8890fe4a1af1925c459；source.diff SHA-256=3b055ed81fd8187633076e8a26430b6fb56fc2f958087d7194c9c2614ff21a35；运行命令`bash target/qualification/20260905-cluster-callback-256-r134/run.sh`。代码为420c6957+上述固定diff，未比较旧版本。main.json SHA=a953323f9a3189ac41d87f0a7117d23748fb618cf40f536946a9c13b05e0c42c；gc.json SHA=d56e765ab1741c4adb293c2a1cafc0631323180b41f90bca330d2ed6f3176027，其余SHA见input/output-sha256.txt。
- 无profiler主轮batchPlaceCancelWithMetrics：SPOT 10.39873±2.70631 invocations/s，即106482.994±27712.600 terminal business ops/s、5324.150 terminal业务Core messages/s/batches/s、106482.994 items/s，另5324.150 metrics queries/s；LINEAR_PERPETUAL 10.42760±4.58925 invocations/s，即106778.640±46993.891 terminal business ops/s、5338.932业务Core messages/s/batches/s、106778.640 items/s，另5338.932 queries/s。若把查询计入全部Core消息，分别10648.299/10677.864 messages/s。batch平均/最大20，fills/s=0。JMH默认99.9%误差，3个样本、短预热，区间很宽，不是持续100k证明。
- 拒绝场景：SPOT 420398.048±147967.633 business ops/s、21019.902批/s；LINEAR_PERPETUAL 421211.913±219848.257 business ops/s、21060.596批/s；98.4496% items为预期ORDER_NOT_FOUND，不能作为正常交易吞吐宣传。各主轮acceptedBusinessOperations==terminalBusinessOperations（挂撤每产品655360，拒绝场景每产品2533560），acceptedCore==terminalCore；逐回调/终检unfinished=0、endBacklog=0、回调后最大pending=0（不代表入口积压为0）；技术错误/超时0，挂撤业务拒绝0，余额/冻结/无遗留仓位/仅maker活动订单及snapshot恢复全部通过。
- 独立GC轮：挂撤SPOT/LINEAR每business op分配7930.667/8049.203B（包含夹具编码、响应解码、metrics查询及迭代成本），gc.alloc.rate=654.415/650.848MB/s；GC count14/14、gc.time383/368ms，后者包含并发GC时间而非STW。拒绝场景2634.239/2643.849B/op、945.088/925.577MB/s，GC8/8、133/134ms。完整原始每invocation归一化和分数在gc.json，不用profile吞吐替代主分数。
- JFR原始SPOT.jfr=4173357B、SHA9011965502f4db3c930aeb113c39f0240c8ab6efd8c52de7bd486ffa93f67d4b；LINEAR_PERPETUAL.jfr=4179688B、SHA539478e8a151f3933e763d3e8eb4fd2c0891ada3c25090b72955b7d49680f93a；事件跨度8.174/7.949s，均DataLoss0。summary和流式JfrRead聚合已保存。录制从fork启动开始，含初始化、JIT、迭代重建、终检，不能代表充分预热后的稳态热点。
- 分组CPU样本（含native）：SPOT owner/harness126、matcher30、Lane8、other4；LINEAR owner/harness162、matcher19、Lane7、other4。owner可见idleCommand/commitReadyMatching等待与批量完成路径，不能把spin当纯业务计算；matcher样本含首次jffi native加载。采样总分配3.757/3.800GB（459.574/478.027MB/s），owner/harness2.840/2.854GB、Lane455/470MB、matcher430/444MB；TLAB3.515/3.557GB、非TLAB246/248MB，最大对象8388624B。top为byte[]、long[]、Long、Object[]、OrderRuntime、stream、MatcherResult、CoreOrderStateView、CoreMatchingResult，详见各analysis的class/thread/site。
- GC/heap：SPOT/LINEAR分别6/5个GC事件，暂停phase26/23，总暂停0.325/0.241ms，phase p50/p95/p99/max=0.010/0.029/0.032/0.032ms及0.010/0.016/0.028/0.028ms；heap committed2GiB，After-GC末299892736/197132288B、峰408944640/247463936B。短窗口仍在初始化增长，不能证明无泄漏。NMT退出reserved/committed分别38015975042/2277846658B和38005861080/2265246424B（ZGC地址预留不等于物理占用），各category首末/峰值保存在analysis。Direct计数/字节0，因为没有真实Aeron传输，不能认证native池。
- 调度/JIT/IO：ThreadPark64/66次、聚合13.40/12.27s（跨线程相加，主要Lane和JMH等待）；safepoint31/28次、最大begin0.211/0.162ms。编译5126/5178次、总22.96/22.57s、最大529.5/507.9ms，说明尚未充分预热。异常/IO含JMH反射探测、lambda链接、jffi解包、快照/夹具打印及JMH进程间socket，不能当业务异常数量或生产owner IO验收。22个系统样本Speed_Limit均100、swap0；OS上下文切换、FD峰值、完整native池和分业务三阶段延迟未采全。
- 结论：本轮完成第一阶段回调边界实现，定向69项通过；JMH/JFR提供当前指定两线批量场景的部分诊断证据。既有扩展测试失败已单列，真实三节点选举/网络分区/高速追赶、其余四线JMH、API尾延迟与长稳泄漏未完成，不宣称完整交易链路或Cluster HA验收通过。下一阶段应先做真实Cluster的背压/慢消费者/重放与切主验证，而不是沿用内部Harness跑分推断生产容量。

## PV-20260905-256-135：交割 ADL 候选与非 PM 期权多头保护（采集前锁定）

- 被测代码：master ba908e38 + 本轮固定工作区 diff；对照 commit：不适用（仅验证当前 master）。修复交割 ADL 查询过滤，以及非 PM 期权多头强平计划/执行保护；不包含完整 OKX 期权保证金、接管、期权 ADL 改造。
- 环境：Oracle GraalVM 25.0.1 HotSpot、Maven3.9.16，macOS26.7 x86_64/i9-9880H 8C16T/16GiB。JVM -Xms2g -Xmx2g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC、matching-engines=1，jdk.internal.misc opens/exports。开始前 Speed_Limit100、swap0；每5s记录系统限制和swap。
- 场景：DerivativeRiskBoundaryBenchmark.riskAndAdl，分别 LINEAR_DELIVERY、INVERSE_DELIVERY、OPTION；4 Account Lane、1matcher、257用户（256交易用户+1maker）、2symbol、外部连接0，maxInFlight固定256。每用户经实际下单成交持有 RISK-LONG +1、RISK-SHORT -10，初始成交价100；随后扣除可用余额，保留已冻结保证金，外部调整计入期初资金。maker资金1e9，测量期间保留1笔maker挂单。币本位 multiplier=100/settleScale=100，其他为1；期权CALL/strike100。
- 每invocation执行256次标记价更新（两symbol各128次，long120/short300），然后按64 work units续扫至风险扫描完成；交割额外查询1次ADL候选并确认盈利用户存在。期权终检要求256空头计划、多头无非终态强平计划且持仓保留。窗口256是进程内driver参数；标记价命令顺序完成，不能表述为256个并发网络请求或生产容量。闭环饱和、CO未修正，测量阶段无成交/batch，fills/s=0，订单成交在setup；query单列、不计business ops。
- 无profiler主轮1fork/1thread、warmup2×2s、measurement3×2s；GC轮1fork、warmup1×2s、measurement2×2s，-prof gc；JFR每线单独1fork、warmup1×2s、measurement2×2s，使用PV134的明确profile.jfc，NMTsummary/PrintNMTStatistics和GC/safepoint日志。主轮与GC轮后各冷却30s，所有参数开始后不调整。
- 通过阈值：business/Core accepted=terminal、unfinished0、资金守恒、非负余额/冻结、原始持仓不变及快照hash/资金一致；业务拒绝/技术错误/超时0；交割候选非空，期权多头保护成立。无吞吐数值门槛，此轮为新业务边界诊断。原始JMH主分数invocations/s，同时由计数和样本时长报告terminal business ops/s、Core messages/s，查询额外单列。最大/期末matcher backlog在本轮标记价场景为0，不代表入口排队0。
- 无profiler结果用于场景吞吐，带profiler结果仅归因。JFR DataLoss>0、Speed_Limit<100或swap>0则性能轮无效；短预热/JIT、业务分段全分位、真实Aeron/native池、API/WebSocket、六线端到端、长期泄漏均未覆盖，最多部分验证。没有新增长期持仓容器；本轮不以短录制证明无泄漏。
- artifact：target/qualification/20260905-risk-boundaries-256-r135/；run.sh保存完整命令，输入/输出SHA清单包含jar、diff、JFC、JSON及JFR。构建夹具失败：首轮测试辅助函数不接受零保证金，已修正；新基准续扫4096超过现有scanBatchSize被拒绝，改为64并重跑通过。这些失败未采集性能分数。

### PV-135 结果（2026-09-05，原始 UTC 采集时间见 system-samples.txt）

- 46项service回归（含六线快照）与3项benchmark夹具验证通过。执行本轮run.sh完成主轮、GC轮和三条线JFR。资金、余额、持仓、快照、accepted/terminal与unfinished检查通过；JFR DataLoss0，系统采样Speed_Limit100/swap0。
- 发现基准错误地在续扫循环调用tradingState()，每次都物化全部账户/持仓并回读Lane。该轮标记为夹具干扰诊断，不能作为风险主链路吞吐验收。主分数按LINEAR_DELIVERY/INVERSE_DELIVERY/OPTION顺序4.043514±1.912002、4.048983±0.944450、4.195150±1.549850 invocations/s；每invocation固定324business/Core commands（256标记价+68续扫），即1310.099±619.489、1311.870±306.002、1359.228±502.151 terminal business ops/s，业务Core messages/s相同。测量总终态8424/8748/8748；额外ADL查询26/27/0，fills=0。所有unfinished=0，技术拒绝和超时0。
- GC轮分配率327.409/374.046/330.639MiB/s，每business op317444/351972/295279B（包含错误的全状态物化）；GC次数6/6/6、时间38/39/37ms。上述分配不是生产risk纯计算成本。
- JFR时长8.541/8.540/8.639s，大小3739766/3899605/3722665B；采样分配权重2.555/2.834/2.477GB（299.201/331.822/286.746MB/s），TLAB2.506/2.786/2.427GB，非TLAB43.669/43.246/43.609MB，最大对象8388624B。聚合risk角色实际为执行Core的owner/JMH worker，CPU样本52/73/68，Lane17/22/17，matcher25/19/19；主要干扰栈RuntimeStateMaterializer→pendingReservedUnits→LaneMutationTask.await，另有首次matcher native库加载与risk续扫。角色标签不代表独立风险线程。
- GC pause phase总0.266/0.350/0.294ms，p50=0.009/0.009/0.010ms，p95/p99/max分别0.048/0.048/0.048、0.032/0.048/0.048、0.045/0.045/0.045ms；AfterGC末119537664/92274688/117440512B，committed2GiB。Safepoint25/28/25次，max0.089/0.071/0.111ms；JIT4572/4907/4434次，最长254.389/210.843/247.311ms，窗口未充分越过初始化。Park44/40/48次含JMH迭代等待；Direct bytes/count0仅指此进程内场景，NMT各类别首末峰值和delta均保存在analysis。异常和I/O包含JMH反射探测、类加载、native解包、snapshot终检，不能作为生产owner I/O门禁证据。
- SHA、完整计数/栈/分配类/GC/JIT/NativeMemoryUsage/IO结果在input-sha256.txt、output-sha256.txt、各线summary/analysis。分业务三段延迟、对象数/op、真实native池、长稳泄漏、API/WebSocket与其余三线JMH未测；修复夹具后另开PV136，不改写本轮或比较旧版本。

## PV-20260905-256-136：修正风险基准计时路径（采集前锁定）

- 被测master ba908e38 + 本轮固定diff，对照commit：不适用（仅验证当前master）。生产改动与PV135相同，基准续扫循环改为CoreProbeState.runtimeRiskScan直接读取两symbol扫描状态；资金、订单和快照全量物化只发生在setup/终检边界。
- 环境、HotSpot25/JVM参数/ZGC、机器、257用户/2symbol/4Lane/1matcher、固定maxInFlight256、持仓/资金/maker、业务动作比例及计数口径、1fork/1thread、主轮2×2s预热+3×2s测量、GC/JFR各1×2s预热+2×2s测量、两段30s冷却与profile.jfc均按PV135采集前定义锁定。mark命令仍顺序完成，因此只用于进程内风险组件诊断；不声称256网络并发容量。通过条件仍为全部资金/持仓/快照/终态检查通过，技术拒绝/超时0，DataLoss0、Speed_Limit100、swap0，不设吞吐门槛。
- 执行target/qualification/20260905-risk-boundaries-256-r136/run.sh，输入/输出SHA和原始JFR/summary/analysis记录于同目录。完整API延迟、native池、长稳泄漏、期权卖方强平接管/期权ADL与完整OKX保证金规则仍未验证；本轮最多部分验证。开始后不修改参数。

### PV-136 结果（2026-09-05，采集 UTC 时刻见 system-samples.txt）

- HotSpot25构建与测试通过：`mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am '-Dtest=DerivativeRiskBoundaryBenchmarkTest,CoreDeliveryOptionFinancialMatrixTest,CoreRiskStateTest,CorePerpetualFinancialMatrixTest,CoreNativeSnapshotProductLineTest,SharedProductLineSnapshotContractTest' -Dsurefire.failIfNoSpecifiedTests=false package`。Service46项+benchmark3项=49项，0失败；tests.log保存完整结果。覆盖交割两方向合约/全仓逐仓候选和快照、期权混合多空账户保护与已排队强平取消、既有永续资金矩阵和六线快照。
- jar SHA256=1a7d543321f56b6c8c2bd6fccc7c5c1fa04f523cd305f304eb79d4f24a8688b5；diff SHA256=7f424b7980acd6659e428dd1e37b76cbab2e532666cc7d21ef1120f9e324d7ac；JFC SHA256=4dbdbd4994757dc2e6930dee513d8ee298d687d9f298bc27434455d499784dc7。新增基准源文件单独列于input-sha256.txt，输出SHA见output-sha256.txt。
- 无profiler主轮，按LINEAR_DELIVERY/INVERSE_DELIVERY/OPTION顺序：77.072009±58.147883、64.153760±61.774598、105.490666±69.276450 invocations/s。每invocation324终态业务命令，分别24971.331±18839.914、20785.818±20014.970、34178.976±22445.570 terminal business ops/s，业务Core messages/s相同；交割额外77.072/64.154 queries/s（所有Core消息含query时为25048.403/20849.972 messages/s），期权无query。短预热3个测量样本的99.9%区间很宽，只作为指定重风险场景诊断，不推断普通交易或网络并发容量。
- 主轮accepted/terminal business与业务Core计数分别150984/125388/206064，额外query466/387/0；两个unfinished=0、资金/余额/冻结/持仓及恢复检查均通过。测量阶段fills/trades=0、batches/items=0，初始成交及资金调整在setup中；拒绝、技术错误、超时0，期末风险续扫完成，matcher backlog为0。期权保留256空头PLANNED任务和256用户多头持仓，这些计划不是未终态Core消息，也不表示强平执行已完成。
- 独立GC轮：分配率700.800/1094.495/913.569MiB/s，每business op38653.019/73529.480/35002.252B，GC次数8/18/8、GC时间23/71/19ms（非纯STW），不使用profiler分数替代主轮。
- JFR时长8.556/8.357/8.206s，原始大小4089821/4574512/4168790B，DataLoss均0。全录制采样分配权重4.725/6.606/5.611GB，对应552.263/790.419/683.819MB/s；TLAB4.688/6.576/5.583GB，非TLAB35.111/33.122/28.372MB，最大对象8388624B。采样含setup与终检，不能从权重推断精确对象数/op。
- 分组CPU（聚合risk标签实为Core owner/JMH worker）：owner131/124/159、Lane54/82/62、matcher28/22/17、other6/4/6；主要业务栈PositionUserIndex.users的TreeSet物化、LiquidationIndex/RiskSnapshotIndex的TreeMap更新，首次native库加载仍占matcher样本。分配按owner3.461/2.851/4.160GB、Lane1.194/3.683/1.381GB；币本位包含反向合约数学的BigInteger分配。具体class/thread/site及CPU load见analysis，未将初始化/空闲park计为撮合业务热点。
- GC phase pause总0.421/0.416/0.438ms，p50=0.011/0.009/0.010ms，p95=0.030/0.035/0.048ms，p99/max=0.046/0.044/0.048ms；AfterGC末98566144/100663296/98566144B，峰146800640/134217728/140509184B，heap committed2GiB。短窗口live set受初始化影响，不证明无泄漏。
- NMT退出reserved/committed分别37991662010/2257732026、37991869689/2256473337、37990910184/2253093096B，地址预留不等于物理内存；各category首末/峰值/delta在analysis。Direct bytes/count全程采样0，场景无真实Aeron/Netty池，不能作生产堆外验收。
- Safepoint30/33/30次、最大0.099/0.082/0.784ms；期权0.784ms需在后续单业务尾延迟采集中对照，本轮没有相应延迟直方图。JIT4673/4955/4726次、总10.593/8.407/6.559s、最长245.170/172.982/184.742ms，明显包含编译预热。ThreadPark113/248/208次，累计13.625/14.795/13.517s是跨线程相加、含迭代等待。异常、file/socket I/O仍有JMH反射探测、类加载/native解包、snapshot验证；不能宣称生产owner I/O门禁通过。系统采样Speed_Limit均100、swap0。
- 脚本异常：首个JFR fork已正常完成，随后采样ps遇到目标进程退出，在set-e下使编排退出。原始LINEAR JFR/JSON完整保留；使用resume-profile.sh按已锁定参数仅完成其分析和剩余两条线JFR，修正退出后的ps非零处理。没有重跑已完成主轮、GC轮或覆盖原始数据。
- 结论：本轮两项运行时边界修复及49项回归完成，三线JMH/JFR部分验证完成；逐业务三段延迟、OS调度全量指标、长期泄漏、真实Aeron三节点/API/WebSocket/外围native池、完整期权资金和接管模型仍缺失。未启动PG/exporter/wallet，未比较旧版本。完整OKX期权风险改造尚未完成。

## PV-20260905-256-137：OKX non-PM 期权风险全生命周期（采集前锁定）

- 被测 git commit：`0627326bc3627561a1a7179942e34a3eb20f7305`（采集记录提交只增加本文档，不改变运行代码）；对照 commit：不适用（仅验证当前 master）。修改范围为 OPTION 明确 mark/index/same-expiry-forward 输入、风险档位 `optionMarginFactorPpm`、卖开/成交/买平保证金、long IM/MM=0、short IM/MM、cross/isolated 权益、空头强平、保险不足转 ADL、snapshot v25 与 immutable/runtime 一致性；不包含 PM 组合保证金。
- 环境：Oracle GraalVM 25.0.1 HotSpot、Maven 3.9.16，macOS 26.7 x86_64、Intel i9-9880H 8C/16T、16GiB。JVM 固定 `-Xms2g -Xmx2g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -Dsurprising.aeron.matching-engines=1` 及 jdk.internal.misc opens/exports；profile/长稳额外启用 NMT summary、PrintNMTStatistics、显式 JFR 配置和 GC/safepoint 日志。采样期间要求 `CPU_Speed_Limit=100`、swap=0。
- 场景：`DerivativeRiskBoundaryBenchmark.riskAndAdl` 仅运行 `OPTION`；固定 256 in-flight、4 Account Lane、1 matcher、257 用户、2 个 CALL symbol，外部连接 0。256 个交易用户均通过真实下单/成交形成每 symbol 的 long +1 与 short -10 组合，maker 提供流动性；mark/index/同到期 forward 初值均 100。每 invocation 执行 256 次 mark 更新（long=120、short=300），再以 64 work units 续扫到完成；测量期 fills/trades=0，setup 成交不计入测量业务 ops。闭环饱和，coordinated omission 未修正；该结果是进程内 OPTION 风险边界上限，不代表 API 或真实 Aeron Cluster 容量。
- 无 profiler 主轮：1 fork/1 thread、warmup 2×2s、measurement 3×2s；独立 GC 轮：1 fork/1 thread、warmup 1×2s、measurement 2×2s、`-prof gc`；独立短 JFR：1 fork/1 thread、warmup 1×2s、measurement 2×2s。短轮之间冷却 20s，长稳前冷却 30s；主轮与 profiler 数值分开，带 profiler 吞吐仅用于归因。
- 长稳：相同 OPTION 场景、固定 256 in-flight，1 fork/1 thread、warmup 1×5s、measurement 1×300s、timeout 360s，启用 `-prof gc`、NMT summary、512MiB JFR 与 GC/safepoint 日志。检查多轮 GC 后 live set/old generation、线程、FD、Direct/Mapped、NMT committed 与风险/强平长期容器；门槛为稳态 after-GC live set 斜率 <1MiB/s、native buffer <256KiB/s、线程/FD/buffer count 斜率 <0.01/s，且至少 3 个 after-GC 样本。
- 通过阈值：主轮 `terminal business ops/s >= 20,000`；accepted business==terminal business、accepted Core==terminal Core、两个 unfinished=0、期末 matcher backlog=0、技术拒绝/错误/超时=0；资金守恒、余额/冻结非负、256 个期权空头强平计划、期权多头不被计划、持仓不被风险扫描改写、snapshot hash/资金恢复一致。GC 轮分配 <=50,000 B/business op。JFR 要求 DataLoss=0，报告 CPU/线程角色、分配、heap/GC、NMT/native、锁/park、safepoint/VM/JIT、I/O/异常；任一环境或正确性门槛失败则该轮无效。
- artifact：`target/qualification/20260905-option-risk-256-r137/`；保存 run.sh、java/maven/commit、输入 SHA、主/GC JSON、短/长 JFR、summary、流式 analysis、系统采样、GC 日志和输出 SHA。未启动 PostgreSQL、exporter、wallet、API、WebSocket 或真实 Aeron Cluster；未覆盖逐业务入口→accepted→terminal 延迟，因此本轮最多是 OPTION Core 风险路径的部分性能验收。采集开始后不修改上述参数和阈值。

### PV-137 结果（2026-09-05 21:33 +08:00）

- HotSpot 25 构建与定向测试通过。artifact 内复跑 protocol 9 项、service 77 项、benchmark 3 项，共 89 项、0 失败；同一实现提交此前完成 instrument provider 12 项和 price provider/consumer 18 项，均 0 失败。覆盖 CALL/PUT IM/MM、卖开冻结、成交权利金转仓位保证金、买平/卖平释放、cross/isolated 权益、long 强平保护、short 强平、保险不足转 ADL、immutable/runtime parity、snapshot v25 恢复和资金守恒。未启动 PostgreSQL、exporter、wallet。
- 无 profiler 主轮为 `102.693016 ± 55.417018 invocations/s`；每 invocation 固定 324 个业务/Core 命令，因此为 `33,272.537 ± 17,955.514 terminal business ops/s`，同时为 `33,272.537 terminal Core messages/s`。3 个短测样本的 99.9% 区间很宽，仅表示锁定场景的诊断上限。accepted/terminal business 均为 200,232，accepted/terminal Core 均为 200,232，两个 unfinished=0、query/fills/trades=0；命令全部接受完成，技术拒绝/错误/超时=0，期末 matcher backlog=0。
- 独立 GC 轮为 `32,859.893 terminal business ops/s`。`gc.alloc.rate=925.275 MiB/s`，`11,253,696.317 B/invocation`，折合 `34,733.631 B/business op`，通过预设 50,000 B/op 门槛；`gc.count=13`、`gc.time=27ms`，后者含并发 GC 工作，不能作为 STW 停顿。
- 300 秒 measurement 长稳完整结束，带 GC/JFR/NMT 时为 `99.792666 invocations/s`，即 `32,332.824 terminal business/Core ops/s`；累计 accepted/terminal business 与 Core 均为 9,700,236，两个 unfinished=0。`gc.alloc.rate=1052.488 MiB/s`，折合 `34,257.336 B/business op`；JMH profiler 报告 1,102 次 collector 事件、5,928ms 并发 GC 时间。teardown 的 256 个 option short 计划、long 保护、仓位/余额、资金总额及 snapshot hash/恢复检查全部通过。
- 短/长 JFR 分别 8.445s/308.210s、4.1MiB/69MiB，SHA-256 为 `0ea8136038d9ca0e827e71e40f5a745b9ddb0d9f86219f67b9ba690ea1a3be19`、`3ae2e32a7cac553c6c01a9477107afa6f24ca2ef01b73b74873f045705b8c11b`，DataLoss 均为 0。长 JFR 采样分配权重 337,067,592,512B（1.094GB/s），TLAB 338,030,619,872B、非 TLAB 29,978,112B、最大对象 8,388,624B；该权重用于热点归因，不替代 JMH 的精确 B/op。
- 长 JFR CPU 样本按角色为 risk/owner worker 6,228、Account Lane 3,474、matcher 72、其他 3。首要热点是 `PositionUserIndex.users` 将 primitive user set 物化为 `TreeSet`，其次是 `RiskSnapshotIndex`/`LiquidationIndex` 的 `TreeMap` remove/put、Lane 的风险用户/持仓有序游标以及 changed-index 提交；新增期权定点公式不是 top hotspot。分配按线程为 risk/owner worker 74.17%，四条 Lane 合计 25.82%，matcher 0.01%；top class 为 `TreeMap.Entry` 37.43%、boxed `Long` 31.91%、`RiskScanRuntime` 5.58%、`PositionRisk` 2.44%。
- 长稳 JFR 有 229 个 after-GC heap 样本，首/末/峰值为 48,234,496/125,829,120/184,549,376B；排除首 60s 的 Theil-Sen robust slope 为 `0 B/s`，通过 <1MiB/s 门槛。JFR GC 总停顿 14.401ms，collection pause p50/p95/p99/max 为 0.059/0.121/0.152/0.156ms；GC phase p99/max 为 0.049/0.060ms，无 allocation stall。
- heap committed 固定 2GiB。退出 NMT reserved/committed 为 37,989,644,791/2,254,756,343B；JFR 中 GC committed 首/末/峰约 3.3/5.0/5.8MiB，Code 17.7/22.6/22.6MiB，Metaspace 24.1/29.0/29.0MiB，Tracing 20.1/19.2/22.0MiB，Thread 168.7/183.7/215.6KiB，Other 66.3/78.0/78.0KiB。Direct/Mapped count 和 bytes 全程为 0；本进程内夹具未启动生产 Aeron/Netty native pool，不能外推其堆外余额。
- 活跃线程在整个 measurement 保持 14，teardown 最后一个样本为 15，斜率约 0.0032/s；系统采样的长稳父进程 FD 除启动 21/36 外其余 59 个样本均为 37，通过预设线程/FD 门槛。无 `JavaMonitorEnter`/contention 事件；park 主要是 JMH owner/harness 等待 measurement 结束（307.993s），Lane 合计 6.557s，matcher 18.993ms，不能解释为业务 owner 同步 I/O。
- safepoint 915 次，state synchronization p99/max 为 0.083/6.666ms；最长 VM operation 为 `ZRelocateStartYoung 18.799ms`，但对应 ZGC 实际 collection pause max 0.156ms。JIT 4,645 次、总 8.393s、最长 162.927ms，主要发生在启动/预热；deoptimization 155 次、class unload 4 次，Code/Metaspace 后段达到平台。JFR 记录的异常来自 JMH/序列化/反射和 native symbol 探测；文件 I/O 是 classpath/JDK 配置与 jffi 解包，socket I/O 是 JMH fork 的 localhost 控制链路，未发现交易 owner 的同步数据库、网络或业务文件 I/O。
- 68 个系统样本全部 `CPU_Speed_Limit=100`、swap=0，无热/性能告警。本轮所有预设正确性、吞吐、分配、DataLoss 和长稳斜率门槛通过。结论仅为当前 master 的 OPTION Core non-PM 风险路径部分性能验收通过；API 三段尾延迟、真实三节点 Aeron Cluster、Kafka/WebSocket、生产 native pool、六产品线端到端和 PM 仍未覆盖，不能据此宣称完整交易链路容量。完整原始数据、JFR view、summary、analysis 与 SHA 在锁定 artifact 目录；JAR SHA-256=`b751bf8f0d267c7be5755ccd8c38fdc088712c58871a3d658deeba3f5a1a8e2c`。

## PV-20260905-256-138：owner/Lane/risk 热路径优化验收（采集前锁定）

- 被测运行代码 commit：`cd980758`；采集记录提交只增加本文档，不改变运行代码；对照 commit：不适用（仅验证当前 `master`）。修改范围为 Lane 分片 primitive 风险用户索引、删除无读取方的 `RiskSnapshotIndex`、primitive liquidation id 索引、sequence/完成通知缓存行隔离、去共享 ready-mask CAS、matcher evidence 单写者 acquire/release、fingerprint 批量 digest、primitive funds delta、admission/journal 复用以及 Lane 阻塞策略 lost-wakeup 修复。
- 环境锁定为 Oracle GraalVM 25.0.1 HotSpot、Maven 3.9.16、macOS 26.7 x86_64、Intel i9-9880H 8C/16T、16GiB；JVM 使用 ZGC、AlwaysPreTouch、DisableExplicitGC、matching-engines=1、4 Account Lane 及 jdk.internal.misc opens/exports。交易长稳使用 4GiB heap，风险 JMH 使用 2GiB heap；JFR 轮额外启用 NMT summary、明确 `profile.jfc`、GC/safepoint 日志。采样要求 `CPU_Speed_Limit=100`、swap=0、JFR DataLoss=0。
- 交易主链路：仅 `LINEAR_PERPETUAL`，执行 `LinearPerpetualScaleSoakMain 1000 256 256 5 10 UNIFORM 1 20 32 300 30`；严格 256 in-flight、4 Lane、1 matcher、1000 活跃用户、256 listed/active symbol、每用户最多5持仓/10挂单、闭环饱和且 CO 未修正，maker 持续，包含批量下单/成交/撤单、trigger/risk/资金费和每场景一次强平→保险→ADL。300秒 JFR/NMT 长稳，报告30秒窗口、terminal business ops/s、Core messages/s、可用分业务延迟、backlog、资金与 snapshot 恢复。吞吐门槛锁定为 `>=120,000 terminal business ops/s`，这是本机有效性下限而非产品目标或容量上限。
- 风险链路：`DerivativeRiskBoundaryBenchmark.riskAndAdl` 仅 `OPTION`，固定 256 in-flight、4 Lane、1 matcher、257用户、2 CALL symbol；256用户均经真实成交形成 long +1/short -10，测量每 invocation 为256次 mark 更新及64单位续扫至完成。主轮1 fork/1 thread、warmup 2x2s、measurement 3x2s；GC轮 warmup 1x2s、measurement 2x2s；短JFR同GC时长。吞吐门槛 `>=40,000 terminal business/Core ops/s`，分配门槛 `<=15,000 B/business op`；fills=0仅指测量期，setup成交不计数。
- 正确性与有效性门禁：business/Core accepted 必须分别等于 terminal，两个 unfinished、技术错误、超时及期末 backlog 为0；所有批/item成功；用户与maker余额、冻结、持仓、订单终态、资金守恒、风险计划、long保护以及完成态 snapshot hash/资金恢复一致。交易 after-GC 稳态斜率 <1MiB/s、native buffer <256KiB/s、线程/FD/buffer count斜率 <0.01/s且至少3个有效GC样本；明显系统限速、swap、DataLoss或门禁不闭合使对应性能轮无效。
- 已完成改动后定向测试：protocol 9项、service扩展交易/资金/风险/恢复测试176项、benchmark真实夹具29项及 ordered batch 8项均通过；一次 benchmark 运行暴露 Lane 条件 unpark 的 lost-wakeup，修复为生产者发布后无条件 unpark 并复跑通过。最终采集脚本会在 HotSpot 25 重新构建并运行核心受影响测试。artifact 固定为 `target/qualification/20260905-owner-lane-risk-256-r138/`，保存命令、环境、JAR/JFC/脚本 SHA、JSON、JFR、summary/views、系统样本和输出 SHA。
- 不启动或测试 PostgreSQL、exporter、wallet、Kafka、API、WebSocket、market-data或真实三节点 Aeron Cluster；不运行旧版本、不改变 matcher 数、不采集其他 in-flight。交易长稳不是 open-loop API 容量测试，风险短轮不能证明长期无泄漏；未测项不纳入通过结论。采集开始后不修改上述参数、阈值或口径，失败与无效轮次照实追加。

### PV-138 结果（采集前失败，2026-09-05 22:20 +08:00）

- 尚未开始任何 JMH/JFR 性能采样。第一次预构建的 service 81项通过，但整类 `CoreOrderedOrderBatchTest` 命中 PV-134 已记录的4项旧 exporter/异步契约失败；保留 `earlier-known-tests-failures.log`，改为已知有效的8个受影响方法。第二次预构建 service 受影响测试通过，benchmark 29项中 `allLinearPerpetualScenariosCompleteOnFourAccountLanes` 的 `partialFill` 偶发看到 Lane queue depth=1；该轮在采集前终止，不产生性能结论。
- 根因是 worker 执行 Lane 命令并发布 terminal completion 后才更新 ring consumer cursor。owner 合法观察到 terminal 后，测试/指标可能在 worker 执行尾声读取到瞬时 depth=1；这不是业务 mutation 未完成，但违反 terminal 时队列已排空的指标契约。生产修复把已读取到本地变量的 ring slot 和 consumer cursor 在执行命令前释放，使 terminal completion 只能发生在消费游标前移之后；同时保留本地命令引用，不允许覆盖影响执行。相同4-Lane全场景方法连续独立运行10次全部通过。修复提交为 `6cd2adf7`，因此 PV-138 的 `cd980758` 不再是最终代码，另开 PV-139。

## PV-20260905-256-139：最终 owner/Lane/risk 热路径优化验收（采集前锁定）

- 被测 git commit：`6cd2adf799953dcc99766f7e50d3c5572fe95efe`；对照 commit：不适用（仅验证当前 `master`）。完整修改范围沿用 PV-138，并增加 terminal completion 与 Lane ring consumer cursor 的确定性先后修复。PV-138 未采集任何性能数据，不作为对照。
- 环境、JVM、交易场景、风险场景、固定 256 in-flight、4 Lane、1 matcher、用户/symbol/负载配置、预热/测量/冷却、JFR/NMT/GC参数、业务计数口径、正确性门禁及未测范围全部与 PV-138 相同。吞吐门槛保持交易 mixed `>=120,000 terminal business ops/s`、OPTION risk `>=40,000 terminal business/Core ops/s`；风险分配门槛保持 `<=15,000 B/business op`，长稳斜率门槛不变。采集开始后不修改参数或阈值。
- 采集前额外门禁为4-Lane全业务 benchmark 方法连续10次通过，最终构建只选择8个仍有效的 ordered-batch方法，旧 exporter/历史异步断言不纳入当前路径。artifact 固定为 `target/qualification/20260905-owner-lane-risk-256-r139/`；保存失败/成功测试日志、环境、命令与 SHA。若系统限速、swap、DataLoss、资金/snapshot/终态门禁失败，则对应轮次无效并照实记录。

### PV-139 结果（2026-09-05 22:34 +08:00）

- HotSpot 25 最终构建通过：protocol 9项、service 72项、benchmark 29项，共110项、0失败；另有采集前4-Lane全场景方法连续10次独立运行通过。覆盖 fingerprint 固定向量、资金幂等、Lane/journal/index提交、永续/交割/期权资金矩阵、snapshot、matcher pipeline、8个有效 ordered batch 场景、Cluster callback 和真实 mixed/risk 夹具。JAR SHA-256=`936ce5d49436a5861644f300e174bc3e0304602e80f46836618da181a1759ab2`，run.sh SHA-256=`1d6977f695b1f1262197d00fae4d86cf62e0ad2100f175cdec85e7138b739c60`。
- 300秒 `LINEAR_PERPETUAL` mixed 完整结束并返回 `PASS`、`fundsInvariant=true`。driver 总时长302.495s，terminal business operations=46,812,368，平均 `154,754.039 terminal business ops/s`；terminal Core messages=4,709,584，平均 `15,569.115 messages/s`。accepted/terminal逐周期闭合、unfinished和期末消息backlog为0、所有batch/item成功；max matcher backlog=256。测量脚本没有独立输出 fills/trades，故不推测其数值。首个30秒窗口120,609.497/s，后续完整窗口157,544.740、161,112.896、162,238.420、162,065.795、162,822.578、159,225.308、159,000.630、155,174.412/s，超过锁定的120k下限；该闭环本机结果不是产品容量目标。
- mixed 资金、余额/冻结、持仓、订单终态及 snapshot 恢复通过；snapshot=25,494,637B，恢复1,172.211ms。业务 sweep p50/p95/p99/max=`133653.601/158590.019/281747.120/676622.488us`。JFR 分业务入口→终态：PLACE_ORDER n=1,107,968，p50/p95/p99/p99.9上界约4.194/4.194/8.389/16.777ms，max15.279ms；ORDER_BATCH n=2,215,936，约16.777/33.554/33.554/67.109ms，max115.322ms；RISK_SCAN n=69,964，约0.066/0.131/0.262/0.262ms，max2.218ms；FUNDING n=69,248，约0.131/0.131/0.262/0.524ms，max35.375ms；TRIGGER n=138,496，约0.262/0.524/1.049/1.049ms，max31.304ms。LIQUIDATION仅2条，不据此作容量结论。直方图为2倍幂桶，场景闭环且CO未修正。
- OPTION risk 无 profiler 为213.401 invocations/s；每invocation固定324个业务/Core命令，换算 `69,141.934 terminal business/Core ops/s`，通过40k门槛，但3个短样本的99.9%误差换算约±129.8k，离散度较高。accepted/terminal均415,692、unfinished=0。独立GC轮为191.363 invocations/s，即约62,001.567 terminal ops/s；`3,229,499.967 B/invocation`，折合 `9,967.592 B/business op`，通过15k门槛；GC count=4、并发GC time=11ms。短JFR轮约63,633.361 terminal ops/s，资金、long保护、256空头计划、持仓与snapshot终检通过。
- mixed JFR 307.525s、约72MiB，DataLoss=0；采样分配权重274,590,180,912B（892.9MB/s），按46,812,368个terminal business ops粗略归一约`5,866 B/op`，其中owner/harness 51.12%、Lane 36.58%、matcher 12.30%。top分配类为 `long[]`17.07%、`byte[]`10.52%、`OrderRuntime`9.17%、`Object[]`7.22%、boxed `Long`5.01%；主要站点仍为primitive map插入/扩容、batch decode ArrayList、OrderRuntime、response/result和identity map。该JFR权重包含setup/终检，不替代独立GC profiler。
- mixed CPU采样按角色约owner/harness 10,839、Lane 2,985、matcher 797；top方法为 `LaneMutationTask.await`5.90%、SHA digest3.71%、4-Lane ready queue扫描3.56%、ThreadLocal3.34%、ConcurrentHashMap.get3.17%、HashMap.getNode3.01%。风险短JFR中 `LaneMutationTask.await`占44.03%，表示单条 CONTINUE_RISK_SCAN 必须等待4条Lane工作完成后才能在同步Core命令边界返回；旧 `PositionUserIndex.users/TreeSet`、`RiskSnapshotIndex/TreeMap` 和 `PositionRisk` 已不再是热点。当前残余等待属于同步业务终态边界及少量无matcher settlement的Lane元数据提交，不能宣称owner“完全不等待”；若继续解除，必须改变Cluster回调内终态契约或让相应业务成为显式异步协议，不能只换成回调名称。
- mixed 共89次JFR GC，pause累计6.124ms，p50/p95/p99/max=`0.061/0.127/0.173/0.173ms`；GC phase max0.068ms，无allocation stall。after-GC 89点首/末/峰约92.3/564.1/715.1MiB，排除前60秒JFR稳健斜率195,841B/s；业务采样器386点斜率为0，均通过1MiB/s门槛。old-generation、Direct/Mapped、线程/FD/pool余额斜率均为0；线程稳定14、Direct/Mapped=0、swap=0。NMT末总reserved/committed约74.53GB/4.44GB，其中heap committed固定4GiB；GC/Code/Metaspace/Tracing committed末约33.9/33.2/33.9/22.0MiB。短风险JFR约3.7MiB、DataLoss=0，5次GC pause总0.247ms、max0.079ms。
- 全部68个系统采样均 `CPU_Speed_Limit=100`、16 CPU可用、swap=0；两个JFR均无 monitor contention，mixed无socket I/O。JIT和异常主要发生于启动/预热及JMH/JNR能力探测；未发现owner同步数据库、Kafka、网络或业务文件I/O。risk-main/risk-gc/risk-JFR/mixed-JFR SHA-256分别为`af1783e4f0a23a6f8c42cee6c919d5cebde14d84a6681ce9d14d5b5053b37c79`、`b69ebd3e80189945374febcf8669b7a04a87aaf8c75d05f9c9604a984beb6213`、`456684b835c4c93c7d8f97f84ed90765a452012d744db4e0c2401517c0ece636`、`9854fcc80ff2e00d3d67138c4baa046e159db4e0367a47d5db13898510ba6c70`。
- 结论：PV-139 的功能、吞吐、分配、GC、环境、资金和五分钟稳定性门禁全部通过；Lane terminal/cursor竞态未复现。结果只覆盖当前master的进程内 U本位永续 mixed 与 OPTION risk，未覆盖真实API/open-loop、三节点Aeron、Kafka/WebSocket/native pool、其余产品线性能或更长稳定性，不能外推为生产容量或“所有等待/分配已归零”。完整原始artifact、summary、views、analysis和SHA位于锁定目录。


### PV-140 产品线规则拆分：采集前锁定（2026-09-05，Asia/Shanghai）

- 被测代码：当前 master `b2404433c891f122bba7dc72419567a9226b12d8` 加本次产品规则拆分工作区；采集前保存完整源码 diff/新增文件和 SHA。对照 commit：不适用（仅验证当前 master）。干净同一 HEAD 副本仅用于核验既有测试失败，不进行历史版本性能对照。
- 修改范围：六个无状态产品规则入口、现货/合约/期权订单预留、期权保证金与成交专属计算、正反向开仓均价、衍生品账户命令和 Reducer 结算方法归属；共用状态与 Lane 所有权、协议、快照格式保持不变。
- JVM：Oracle GraalVM HotSpot 25.0.1+8.1，Maven 3.9.16；Intel i9-9880H、8物理/16逻辑CPU、16GiB RAM、macOS 26.7。JVM 为 `-Xms2g -Xmx2g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED -Dsurprising.aeron.matching-engines=1 -Dsurprising.aeron.account-lanes=4 -Dsurprising.aeron.settlement-wait-strategy=BLOCKING`。
- 场景：新增 `ProductRulesRefactorBenchmark.committedProductWorkload`，参数六条 ProductLine、256 activeUsers、256 symbols、4 Account Lane、1 matcher、exchange-core risk engine=0、固定256 in-flight、8 hftRounds、20 hftBatchSize、1线程/1 fork。进程内有界闭环，不设open-loop到达率，无HTTP/WS连接，不修正coordinated omission；不能推导生产API容量或三段尾延迟。
- 业务：沿用 `SpotMixedWorkload`/`DerivativeMixedWorkload` 的真实Core双向maker/taker批量成交与撤单，maker流动性全程存在。每方向每symbol为1次maker下单、20次taker item、1次maker撤单，双向合计44 business ops，另包含既有标记价/风险扫描及永续资金费工作，具体实际计数由harness输出。现货初态每retail用户1,000,000 USDT、10,000加小额偏移的base，HFT用户10,000,000,000 quote/100,000,000 base；衍生品fixture安全余额10,000,000,000 settle units、价格100、预建多空持仓，反向settle BTC，其余USDT。每trial检查maker与用户资金守恒、非负余额、终态订单冻结释放及配对snapshot恢复后的业务状态。
- 无profiler主吞吐：warmup 2×1s、measurement 3×1s；GC profiler：warmup 1×1s、measurement 2×1s；JFR：warmup 1×1s、measurement 2×1s；每组冷却5s。JMH invocation可能超过迭代时长，输出实际时间。timeout=120s。先main，再gc，再六条线各自JFR；profiler结果只作归因，不替代main。
- JFR：沿用已存在的 `target/qualification/20260905-cluster-callback-256-r134/profile.jfc`，复制至当次目录并保存SHA；开启NMT summary、退出打印NMT、GC/safepoint日志，每条线单独recording，maxsize=256m；输出summary、CPU/分配/GC/safepoint/锁/I/O等views及现有JfrRead聚合。JFR包含fixture/setup/恢复检查，不能按全程权重冒充测量窗口纯交易开销。
- 预设局部通过阈值：六条线各自terminalBusinessOperations >=1000 ops/s；acceptedBusinessOperations=terminalBusinessOperations、acceptedCoreMessages=terminalCoreMessages、两项unfinished为0；无业务异常/超时；资金/余额/冻结/终态/snapshot检查全部通过；每business op分配<1MiB（以gc.alloc.rate.norm除每invocation实际业务操作数计算）；JFR DataLoss=0，无owner同步外部业务I/O。CPU限速、swap或数据缺失标记该轮无效。此阈值仅防止严重回归，不证明吞吐/尾延迟与改动前相同。
- 验证边界：不新增长期缓存/容器，短JFR不证明无泄漏；未采集三段API延迟、真实三节点HA、网络推送、长稳泄漏和完整风险重操作性能时，整体性能结论只能为部分验证。交割/行权、强平/ADL等功能由对应测试及benchmark夹具补充，mixed主分数不能冒充这些动作独立吞吐。
- Artifact：`target/qualification/20260905-product-rules-256-r140/`。所有失败/无效轮次、命令、参数、测试对照、JSON、JFR、环境与校验信息按时间追加本文件。


### PV-140 结果与终止（2026-09-05 22:59 +08:00）

- 功能：当前HEAD干净副本与拆分后service全量均运行414项，出现同一组31个失败用例（拆分后7 failure/24 error，干净副本6 failure/25 error，批次测试同一用例错误表现波动），失败集合无新增。原有失败涉及停用exporter、终态保留及相关snapshot断言，记录 `test-failure-comparison.json`，没有删除或放宽失败断言。新增产品隔离/生命周期测试后，定向156项service+33项benchmark夹具全部通过；最终clean package再次通过189项。
- main六线终态business ops/s依次为SPOT 110,538、LINEAR_PERPETUAL 102,752、INVERSE_PERPETUAL 98,858、LINEAR_DELIVERY 103,670、INVERSE_DELIVERY 97,847、OPTION 103,711；accepted/terminal一致，unfinished均0，资金和snapshot检查通过。三个短iteration置信区间很宽，不能用于精确容量或性能不变结论。
- 系统swap从0增至9.50MiB，违反采集前环境条件。本轮整体性能门禁无效；停止剩余采集，已生成main/gc和部分JFR保留为诊断，不作为性能验收证据。CPU限速未见，不能用此抵消swap。
- 最终代码仅做Java token不变的格式整理；再次clean package消除了旧类残留，后续采集改用该clean JAR。读取器发现 `Refactor` 中的 `fact` 会误把JMH owner分类为fact/export，后续使用当次读取器副本优先匹配jmh-worker，不改变历史文件。

### PV-141 产品线规则拆分低内存复测：采集前锁定（2026-09-05 23:01 +08:00）

- 代码、业务场景、六产品线、256用户/256 symbols/256 in-flight、4 Lane/1 matcher、8轮×20 batch item、资金/snapshot不变量、吞吐与分配阈值、JMH warmup/measurement/fork/冷却/timeout均继承PV-140；对照commit仍为不适用（仅验证当前master工作区），不作历史性能比较。
- 使用clean package的最终JAR，保存源码、JAR/JFC/读取器/脚本SHA。仅内存配置变更：被测fork `-Xms768m -Xmx768m`，保留ZGC及其他选项；launcher、JFR reader和view工具通过JAVA_TOOL_OPTIONS限制为384MiB，被测fork显式768MiB覆盖此默认。避免采样工具触发同机内存压力。
- 环境口径：已存在的9.50MiB历史swap占用本身不能证明本轮换页，因此记录采集前及每5秒vm_stat的Swapins/Swapouts/Pageouts累计计数。本轮要求这些计数无增长、CPU_Speed_Limit持续100、JFR DataLoss=0；发生新的swap/pageout或throttling则无效，不把已有swap量降低视为通过。采集前保留绝对swap量及累计计数。该口径在启动采集前锁定。
- JFR线程分组使用独立读取器副本，优先识别jmh-worker为owner/harness，随后识别Lane、matcher、snapshot/projection、fact/export、risk。保留逐线程view与完整栈作为核验依据。
- Artifact：`target/qualification/20260905-product-rules-256-r141/`。整体仍属于进程内部分性能验证，未覆盖API三段尾延迟、真实三节点/网络、长稳泄漏；不得表述为完整交易主链路验收。


### PV-141 结果（2026-09-05 23:06 +08:00）

- 最终HotSpot 25 clean package通过189项：service156项、benchmark真实业务夹具33项，包含六产品隔离、现货/永续/交割/期权资金矩阵、风险/资金费、配对native snapshot与恢复。未改动现有失败测试的预期；当前HEAD干净副本与拆分后全量414项失败集合相同31项，见当次test-failure-comparison.json。
- 所有六线main/gc/JFR均运行完成；每线accepted/terminal business及Core messages相等，unfinished均0，每次trial终检资金、余额非负、订单终态/冻结释放与snapshot恢复通过。以下均为诊断数据，不能作为有效容量验收：

|产品线|terminal business ops/s|terminal Core messages/s|分配 B/business op|JFR秒数/字节|GC pause max ms|
|---|---:|---:|---:|---|---:|
|SPOT|155835|14841|7483.0|7.608 / 4702380|0.0549|
|LINEAR_PERPETUAL|134585|12829|7527.3|8.277 / 4901794|0.0691|
|INVERSE_PERPETUAL|127120|12117|10660.8|8.468 / 5225547|0.0613|
|LINEAR_DELIVERY|141099|13444|7378.2|8.187 / 4781953|0.0462|
|INVERSE_DELIVERY|136289|12986|10729.4|8.334 / 5212363|0.0294|
|OPTION|146710|13979|7467.2|7.860 / 4717878|0.0544|

- 分配口径按gc.alloc.rate.norm除每invocation业务操作数；现货172032、两永续172048、两交割及期权172040。场景除双向成交外还包含每symbol20笔maker挂单及20笔撤单，因此交易项总量为每symbol/round 84项；其余差额是实际风险/标记价/资金费工作。原始JSON保留全部JMH主分数、误差/置信区间、参数、aux counters和GC指标；短测置信区间较宽，不能精确推导容量。
- 系统门禁失败：Swapins从192增至320，Swapouts维持2431、Pageouts维持47031；CPU_Speed_Limit最低75（还出现79/89/93/97）。因此本轮性能验收无效，不以无新增swap-out替代预设条件，也不再放宽阈值重跑。只可确认功能与恢复断言通过，无法承诺性能无回归。
- 六份JFR DataLoss均0，保存summary、18类view和JfrRead分析；录制含启动/预热/终检。以OPTION为例，分配主要为long[]15.82%、byte[]10.53%、OrderRuntime7.58%、Object[]6.16%、Long5.02%；CPU可见LaneMutationTask.await、readyLaneMask、HashMap操作、ThreadLocal及SHA相关栈。owner/harness、Lane、matcher已分组；没有把Refactor名称误归为fact/export。等待仍是既有同步业务终态协调，未因方法拆分新增线程/任务。
- NMT、DirectBuffer、heap/GC、线程、safepoint/VM operation、JIT/类加载、异常及文件/socket I/O均保存原始统计；OPTION Direct bytes/count为0，heap committed固定768MiB，线程采样约15；after-GC数据包含fixture和订单保留窗口的建立，短测不用于泄漏结论。I/O栈含类加载/JAR、JMH控制通信和诊断输出，不能将其计为交易业务I/O；本次代码差异没有新增文件/数据库/网络调用。真实Aeron/native pool、系统上下文切换和API accepted/terminal三段延迟未覆盖。
- 源码最后仅调整新方法闭括号缩进，Java token及行数不变；clean JAR与这次格式修改执行语义相同。代码整理完成且定向功能/恢复验证通过；完整交易链路性能验收、长稳泄漏和真实三节点HA未完成。
- 复现命令/环境/JFC/工具及SHA见当次commands.jsonl、run-performance.py、input-sha256.txt；最终逐文件SHA见output-sha256.txt；原始JAR SHA-256=d9a9105fd61b9abae447b1c4ef96694a093abbe22fe29ff758c7e5231e776c81。历史PV-140无效数据同样保留，不覆盖。

### PV-142 生命周期分页修复：采集前锁定（2026-09-06 00:02 +08:00）

- 本轮为真实生命周期路径的短时诊断，不是持续交易容量/无泄漏验收；不对旧代码作性能对照。对照 commit：不适用（仅验证当前 master）。被测代码为本条记录提交后的 master，采集前写入 artifact 的 commit/JAR SHA，不在采集中变更代码或标准。
- 改动：交割续页互斥、全局订单/用户游标、实际本页 Lane mask；资金费固定价格/price sequence 及持仓 fence；snapshot v26；现货同资产校验与到期边界。全仓净额/保险不足及异步 owner 优化尚未完成。
- 环境：Intel i9-9880H 2.30GHz、16 logical CPU、16GiB RAM、macOS x86_64；Oracle GraalVM 25.0.1 HotSpot、Maven 3.9.16；G1，-Xms512m -Xmx512m；额外参数 --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED。
- 场景：LifecyclePaginationBenchmark 的 LINEAR_DELIVERY/INVERSE_DELIVERY/OPTION，及 FundingCutBenchmark 的 LINEAR_PERPETUAL/INVERSE_PERPETUAL。每次独立一个产品线，1 symbol、4 Account Lane、1 matcher/1 risk engine；固定 maxInFlight=256。生命周期同 symbol 后页依赖前页游标，实际页面串行，不冒充 256 页并发或 open-loop 容量；无外部 API 连接。fixture 内 maker 提供真实成交，并在订单撤销阶段保持待撤盘口；未启动独立做市服务。
- 初态：256 用户各 1,000,000 settle units、maker 1,000,000,000；每用户成交买入 1、maker 卖出 256；零手续费。交割各用户额外挂买单 1，price90；到期 price120，16 orders/page、16 users/page，共 32 terminal business operations/Core messages。资金费 100000ppm、16 users/page 共17页，第一页后 mark100→200 一次，总18 business operations/messages。成交填充只发生在 fixture 建立，不计入测量业务 ops/fills。不覆盖逐仓、资不抵债或保险不足。
- JMH：1 thread、1 fork、warmup 1×1s、measurement 2×1s；每轮冷却2s；无到达率控制、无 coordinated omission 修正。首轮无 profiler 主分数，第二轮 -prof gc + profile.jfc JFR、NMT summary/退出统计；JFR 开销不可忽略，不能替代主分数。Invocation setup/teardown 恢复和校验不在业务计时内，但 GC/JFR 包含这些工作，不能把 gc.alloc.rate.norm 当作纯交易热路径分配。
- 预定门禁：业务执行不得异常，页数严格32/18，accepted=terminal，unfinished=0，期末未完成命令/资金差额/不应保留的冻结与仓位为0，恢复 hash 一致。主分数仅要求有限且>0，不能作为容量目标；阈值不对应历史吞吐。JFR DataLoss>0、swap计数增长、CPU_Speed_Limit<100、OOM或资金不变量失败判数据无效，不放宽重跑。
- 原始产物：surprising-aeron-core/surprising-aeron-benchmarks/target/pv142/；保存 main/gc JSON、控制台、原始JFR、summary/view、NMT、环境前后值及SHA。未测 API 三段/类型尾延迟、真实集群网络、完整native pool峰值、长稳泄漏与故障切换，结论最多部分验证。功能回归先执行 mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am test，566项通过。

### PV-142 结果（2026-09-06 00:07–00:09 +08:00）

- 被测 commit：1a801402；对照 commit：不适用（仅验证当前 master）。执行命令为 `bash surprising-aeron-core/surprising-aeron-benchmarks/target/pv142/run.sh`，脚本内保存五产品线的完整 JMH 参数。原始数据在 `target/pv142/run1/`，commit.txt、jar.sha256、output.sha256 保存代码与逐 JSON/JFR 校验。首次启动因 macOS Bash 3 对空数组的 nounset 行为，在 fork 前退出，没有生成测量数据；保留 pv142 根目录启动记录，修正启动脚本后采集标准与业务代码均未改变。
- 功能命令：`mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am test`。Product API 12、协议84、instrument API13、service415、benchmark42，共566项通过；日志 `/tmp/product-fix-all-core-tests.log`。中间失败包括新 guard 的 Lane 回读 Treasury（改为 owner 准备 AdmissionIdentity 布尔值）、测试期权 mark 参数次序以及旧 mixed workload 跨 funding fence 交易（改为先续完该 symbol 的页），均在本轮构建前修复并全量重跑。未运行 PG/exporter/wallet。

|产品线|无 profiler terminal business ops/s|GC+JFR ops/s（归因用）|gc.alloc.rate.norm B/op（含恢复）|gc.alloc.rate MiB/s|GC 次数 / 时间 ms（GC profiler）|
|---|---:|---:|---:|---:|---:|
|LINEAR_DELIVERY|2668.55|2638.93|1484220.64|883.63|14 / 43|
|INVERSE_DELIVERY|2599.73|2531.45|1487383.31|859.96|13 / 39|
|OPTION|2530.88|2453.01|1481752.32|840.70|13 / 39|
|LINEAR_PERPETUAL|5875.00|5424.82|2280316.53|1187.87|23 / 51|
|INVERSE_PERPETUAL|5385.36|5470.28|2301677.65|1211.87|24 / 50|

- 业务计数：交割每 invocation 固定32个有界结算命令，共撤256单、结算257用户；资金费每 invocation 固定17个资金费命令和1次 mark update。各 invocation 校验 acceptedBusinessOperations=terminalBusinessOperations、acceptedCoreMessages=terminalCoreMessages，差值/unfinished 为0；本次不存在批量订单接口测量，测量 fills=0，fixture 成交另计。总累计计数、最大 backlog 和业务类型尾延迟未额外输出；不能作完整容量验收。JMH 仅2次 measurement，scoreError/CI 为 NaN，原始结果保留，不补造置信区间。
- 五份 JFR 均约5s、1.4–1.5MiB，DataLoss=0。JFR profile 包含 invocation 恢复/验证，因此表内1.48–2.30MB/op不是纯业务热路径分配，不能据此认定交易每单分配这些字节；主吞吐只覆盖生命周期执行，也不能与普通下单/混合流量比较。
- 以 INVERSE_DELIVERY 为例：分配样本 long[]26.35%、Object[]16.25%、byte[]15.62%、CoreOrderState8.63%；benchmark owner/恢复线程占88.41%，matcher线程分散占用，其余Lane与初始化线程见 allocation-by-thread/thread-cpu-load。CPU样本可见 awaitAnyMatchingCommitReady、TreeMap、matcher event、Lane worker构造和snapshot恢复；重建反复起线程使JIT/构造成本混入记录，不宣称已越过主要编译期。未创建独立风险、Aeron/Kafka或export线程，不能把这些分组缺失解释为零成本。监视器竞争样本0、ThreadPark241，未取得完整墙钟阻塞分解，不能宣称无等待。
- 同一 JFR GC 全记录窗口18次pause、总60.4ms、p50 3.15ms、p95/p99/max 8.86ms；它包含启动/恢复，不能与GC profiler测量窗口13次39ms相混。NMT采样 heap512MiB、GC native69.0→69.4MiB、code22.0→31.3MiB、metaspace16.2→21.9MiB，全部类别与退出统计在 native-memory-committed/gc.log。DirectBufferStatistics事件0，TLAB精确分配事件0，仅 ObjectAllocationSample1210/ThreadAllocationStatistics40可用；native池峰值、对象数/op及TLAB内外精确分解缺失。
- Safepoint、VM operation、JIT/deoptimization、file/socket I/O和异常聚合保存在各产品线view文件；异常以MethodHandle/反射和jnr初始化为主，没有基准业务失败。采样还包含类加载/JMH控制通信，未完成业务owner同步I/O逐栈排除，故不宣称主链路I/O门禁完成。
- 系统采集窗口 CPU_Speed_Limit 均100、Pages throttled=0；Swapins832→832、Swapouts2431→2431、Pageouts48287→48287。满足本轮短诊断系统门禁，但没有长稳、多轮GC后live set/native slope、真实API/WebSocket与三节点HA证据。
- 结论：这批分页、资金费基准及到期/配置保护修复通过已测功能/恢复场景，性能仅部分诊断；不宣称整体交易性能优化或资金全场景验收完成。全仓净额/保险不足处置仍需业务规则确认，随后才进行剩余性能优化。

### PV-143 偿付修复与索引/币本位计算：采集前锁定（2026-09-06 00:52 +08:00）

- 对照 commit：不适用（仅验证当前 master）。被测 commit 为本条预注册提交后的 master，在启动脚本记录 commit/JAR SHA；采集不修改代码/标准。仅诊断，不以此前版本或100k为目标，不声称完整容量验收。
- 改动：全仓同symbol净额、逐仓偿付隔离、本页保险不足不应用账户变更、补资恢复；协议schema5/snapshot27；账户consumer失败不误确认和降序游标；PositionUserIndex成员不变时跳过重建、用户后继分页；ActiveOrderIndex有界heap分页；币本位精确long快路径。Lane独立准备/应用可以并行，仍有owner阶段等待。
- 环境锁定：Intel i9-9880H 2.30GHz、16逻辑CPU/16GiB、macOS26.7 x86_64；Oracle GraalVM25.0.1 HotSpot、Maven3.9.16；G1，-Xms768m -Xmx768m；--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED。每次一产品线，不并跑Maven/其他压测。
- A场景：SettlementSolvencyBenchmark.pauseRefillResume，两交割及OPTION，各CROSS/ISOLATED。4 Account Lane、1 matcher/1 risk engine、1 symbol、maker999+256用户、固定maxInFlight256，无外部连接。同symbol分页有因果依赖，实际串行页面。maker初始10^9单位、其他用户成交后提走可用余额，仅留仓位保证金；entry100、结算1000。inverse multiplier/scale100，其他1，OPTION CALL strike100。每invocation一次不足暂停、一次补资、17页（16users/page）完成，19 terminal business ops/Core messages；测量fills0，fixture先成交256笔、无手续费。最终maker收益、亏方零现金/平仓、保险/clearing/进度清零、恢复hash一致；invocation恢复/校验不在计时内，GC/JFR包含这些成本，不冒充纯热路径分配。
- B场景：ProductRulesRefactorBenchmark.committedProductWorkload，六产品线分别运行；accountLanes4、activeUsers256、symbols256、maxInFlight256、hftRounds8、hftBatchSize20；1 matcher/1 risk engine。fixture与真实业务为现有SpotMixedWorkload/DerivativeMixedWorkload：持续maker/taker成交、每symbol/round20maker挂单和20撤单、双向成交/风险mark更新，永续加funding。每invocation完整业务计数按aux accepted/terminal展开，不把invocation/s当业务ops/s。maker资金10^10，衍生品mark100；现货maker各base10^8/quote10^10、retail base10^4/quote10^6；零初始仓位。fixture做市者参与每轮，无独立外部做市服务；无API连接。逐用户资金、冻结、终态订单/持仓以及snapshot恢复在teardown核对。
- 两场景均closed-loop，未修正coordinated omission，无固定到达率；JMH1thread/1fork，warmup2×2s、measurement3×2s、冷却2s。各跑无profiler主分数，另跑-prof gc + profile.jfc JFR、NMT summary/退出统计；带profiler仅归因，不能代替主分数。主分数/误差/CI/完整参数保存JSON。
- 预定门禁：所有业务断言通过、accepted=terminal、unfinished0、余额资金等式成立、恢复一致，诊断分数有限且>0；不设容量承诺。JFR DataLoss>0、swap增长、CPU_Speed_Limit<100或OOM/资金不变量失败判该轮性能无效，不放宽条件补跑。缺少类型三段尾延迟/native池/长稳/真实Aeron网络证据，最多部分验证。
- 原始路径：surprising-aeron-core/surprising-aeron-benchmarks/target/pv143/；脚本run.sh、main/gc JSON/log、原始JFR、summary及CPU/分配/GC/NMT/锁/VM/JIT/I/O视图、系统前后和逐秒thermal、输入输出SHA均保留。不运行PG/exporter/wallet。
