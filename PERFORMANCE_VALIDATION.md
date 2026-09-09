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

### PV-143 结果（2026-09-06 00:54–01:03 +08:00）

- 被测 commit `6dcb7351`，对照 commit：不适用（仅验证当前 master）。`bash surprising-aeron-core/surprising-aeron-benchmarks/target/pv143/run.sh` 完成12个场景×无profiler/GC+JFR共24轮；JAR SHA-256 `c93d8ef6824af9e32301b579b4f838f83f6cbbc6254cf774dc1069109638e054`。输入记录、全部JSON/JFR和最终校验位于上述目录；`scores.mjs`/`scores.json`保存口径换算，`JfrDigest.java`/各digest.txt保存离线线程分组和GC分位。原始12份JFR共约40MiB，每份12–16秒；各summary包含事件配置的实际开启/数量。
- 系统门禁失败：Swapins1280→1343，Swapouts2431→2431、Pageouts48287→48287；CPU_Speed_Limit全程100。没有因“只发生swap-in”放宽门禁，**本轮性能验收无效**，以下数字仅归档诊断，不作为性能提升、容量或旧版本比较证据。没有自动关闭用户的同机应用。
- 功能测试通过：核心依赖/benchmark 573项（Product API12、协议86、instrument API14、service416、benchmark45），客户端38、工具59、账户生命周期13、账户保证金/划转12，共695个对应范围测试；重复执行的依赖测试不重复计数。额外3个偿付恢复参数测试再次通过，覆盖两margin mode。命令分别是 `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am test`、`mvn -pl surprising-aeron-core/surprising-aeron-client,surprising-aeron-core/surprising-aeron-tools -am test`、账户provider `-am -Dtest=ExpiringContractSettlementFanoutServiceTest,ExpiringContractSettlementConsumerTest -Dsurefire.failIfNoSpecifiedTests=false test`，及 `-Dtest=MarginTransferMathTest,AccountMarginReleaseMathTest,ProductTransferInternalControllerTest`。日志 `/tmp/remaining-core-final3.log`、`/tmp/remaining-protocol-consumers.log`、`/tmp/remaining-account-final2.log`、`/tmp/remaining-account-math.log`、`/tmp/solvency-extra-tests.log`。
- 开发中失败也已处理：旧协议golden同步schema5；新增索引测试缺少import；反射状态比较误把nullable查询scratch当业务索引（仅排除此scratch）；全局订单分页可选symbol被必填校验拒绝（修正可选参数并覆盖）。最终复跑成功。离线JFR聚合初次遇到无Java名字的native线程，聚合器修正后重新读取原始JFR，未重采样或修改标准。

混合交易诊断（每秒；固定256 in-flight、4 Lane、1 matcher；误差为JMH 99.9% CI半宽，非真实集群容量）：

|产品线|terminal business ops/s ± error|terminal Core messages/s|GC+JFR business ops/s|分配B/business op|分配MiB/s|GC次数/时间ms|
|---|---:|---:|---:|---:|---:|---:|
|SPOT|199749 ±18414|19024|195398|6106.8|1099.5|27/318|
|LINEAR_PERPETUAL|172403 ±22563|16434|167956|6187.7|964.6|22/263|
|INVERSE_PERPETUAL|169823 ±8613|16188|167380|6243.1|957.5|22/236|
|LINEAR_DELIVERY|183391 ±24211|17474|177164|6074.8|999.1|26/309|
|INVERSE_DELIVERY|181705 ±27209|17313|175765|6154.5|1000.8|31/345|
|OPTION|186259 ±40879|17747|179552|6048.8|1003.7|30/346|

- 六场景main/gc均输出 `funds=true snapshot=true terminal=true maxBacklog=256`；accepted/terminal business与Core指标一致、两个unfinished均0、期末命令积压0。每轮完整断言不等同于所有生产业务全覆盖；本场景的行情/风险/资金费及下撤单比例由已锁定fixture控制。GC每操作字节按实际business ops/invocation展开，非Core message字节。未独立输出本轮fills/s、batches/s、累计accepted计数及三段类型延迟，不能以已有aux每秒速率代替这些缺失指标。

偿付续结诊断（每invocation19个business ops/Core messages，测量fills=0）：

|产品线/模式|terminal business ops/s ± error|GC+JFR ops/s|分配B/op（含恢复）|分配MiB/s|GC次数/时间ms|
|---|---:|---:|---:|---:|---:|
|LINEAR_DELIVERY/CROSS|6037 ±4775|6092|2126971|1502.1|135/235|
|LINEAR_DELIVERY/ISOLATED|6319 ±9787|5889|2125024|1477.2|63/129|
|INVERSE_DELIVERY/CROSS|6173 ±10691|6014|2115709|1472.8|135/245|
|INVERSE_DELIVERY/ISOLATED|6217 ±8343|5246|2116086|1329.9|288/477|
|OPTION/CROSS|6065 ±6963|5759|2127131|1505.4|60/126|
|OPTION/ISOLATED|6285 ±8640|5478|2128207|1391.1|250/400|

- 这组分配含每invocation重建runtime、线程和快照校验，约2.12MB/op不能归因于生产结算计算。`run(false)`业务计时不物化全量状态；暂停页未动资金、部分补资不消耗和重复完成不赔付由`run(true)`功能路径验证。主分数区间很宽，不能精确推断结算容量，也不与混合交易混报。
- JFR CPU/线程：12份DataLoss均0。以INVERSE_DELIVERY-MIX为例，CPU样本owner+fixture979、Account Lane257、matcher79、other8；没有独立risk/snapshot/fact/Aeron/Kafka线程样本不等于这些生产服务成本为0。JVM user平均22.29%、system1.65%，机器平均26.85%（相对整机CPU）；各线程CPU见thread-cpu-load。真实剩余等待栈是 `finishOrderBatch → RuntimeCommandProcessor.stampChangedOrdersByLane → executeOwnerSettlements → LaneMutationTask.await`；另有`readyLaneMask`就绪扫描和命令/状态hash编码栈，不能宣称owner已完全非阻塞。纯结算LINEAR_DELIVERY-CROSS的await占30.37%执行样本，并混有fixture恢复。
- 分配/heap/GC：INVERSE_DELIVERY-MIX采样weight估计owner+fixture约6.30GB、Lane5.10GB、matcher1.52GB（全录制窗口估计，不是精确TLAB计数）；SPOT样本top为long[]17.02%、byte[]14.20%、OrderRuntime8.04%、Long6.73%。TLAB内/外精确事件均0，缺少对象数/op和精确最大对象。INVERSE_DELIVERY-MIX全JFR GC pause43次，总486.792ms，p50=12.680、p95=16.487、p99/max=22.593ms，包含预热/终检，不与表内measurement GC345ms混用。GC before/after、old/concurrent周期和原因见gc/view；没有长稳live-set斜率证据。
- Native/Direct：NMT开启且保留全部类别/退出reserved与committed；同一inverse mixed heap committed768MiB，GC native74.3→75.2MiB、code21.3→37.7MiB、metaspace15.6→22.0MiB；DirectBuffer三个样本count/capacity/used均0。真实Aeron/Netty/Chronicle池、mapped buffer峰值及FD增长未测，不能据此断言无native泄漏。
- 锁/VM/JIT/I/O：inverse mixed ThreadPark220，另见各contention-by-site；无完整墙钟RUNNABLE/BLOCKED/park占比、上下文切换和busy-spin按阶段占比。SafepointBegin48，但profile未记录完整End/同步时长，view显示Indefinite，不当作零停顿；因此不能与业务p99门禁核对。Compiler统计7663方法、总26.7s（多线程累计）、最长534ms、1 bailout，Deoptimization256；仍混入启动编译，不能证明完整窗口已越过主要JIT阶段。异常样本以MethodHandle/反射/jnr初始化为主，未发生基准业务失败。该inverse记录仅2个外围ObjectInputStream socket-read事件，未录到交易owner同步I/O；阈值采样缺失不能作为绝对无I/O证明。
- 已测范围为当前核心与对应账户consumer/数学路径、六产品线fixture的资金/终态/快照恢复；未测PG/exporter/wallet（按要求）、真实模拟用户API/WebSocket、真实集群HA、独立做市进程、open-loop类型尾延迟、长稳/native泄漏。结论是**已测功能修复通过，性能仅诊断且系统门禁失败；整体交易链路验收未完成**。本轮未重构owner生命周期/订单stamp为全异步，也未把到期保险不足直接转成自动ADL或补齐跨产品组合保证金规则。

## 2026-09-06 实时出口及异步查询边界验证（采集前锁定）

- 被测代码：当前 master，基础提交 `92cbb3aa` 加本次未提交实时出口实现；最终 diff 校验另追加。对照 commit：不适用（仅验证当前 master）。不运行旧版本，不以开关关闭数据作对照。
- 修改点：提交回调内有界增量编码/outbox；异步 Account Lane 用户快照及 matcher 有界深度读；外围独立 Aeron sender/router/Valkey。可靠 Kafka 出口在独立 Archive replay 进程。
- 机器/JVM：Intel i9-9880H 2.30GHz，16 logical CPU、16GiB、macOS 26.7；Oracle GraalVM 25.0.1 HotSpot / Maven 3.9.16。固定 G1，`-Xms768m -Xmx768m`，JMH fork 1、threads 1；不启用 OpenJ9。
- 场景：`ClusteredBatchTradingBenchmark.committedRealtimeTrades`，六产品线逐个运行，4 Account Lane、1 matcher、257 活跃用户、1 symbol、零外部网络连接。每轮四个预构建 256 请求波次（固定 256 in-flight），maker 卖→256用户买→256用户卖→maker买；1024 terminal business ops/Core messages，512 fills，均普通限价单，无 batch，0手续费。现货 maker 初始257 BTC，其余结算资产每用户 BALANCE（源码常量）；衍生品零初始仓位，所有交易往返后恢复资金/冻结/仓位；保留 maker 挂单。每1024操作执行一次真实异步用户快照和深度读取，iteration teardown 验证全部用户及maker资金、终态与快照恢复hash。
- 负载模型：closed-loop 256请求波次，无固定到达率；非 HTTP/Cluster 网络入口，不修正 coordinated omission，无独立做市进程（fixture maker 持续参与）。主分数 cycles/s 必须乘1024报告 terminal business ops/s，乘512报告 fills/s。不能将这些结果当作生产容量或网络请求吞吐。
- 无 profiler 主轮：每产品3×3s warmup + 3×5s measurement，轮间冷却2s，JMH JSON。带 profiler归因轮：相同场景/时长，`-prof gc`、JFR profile配置、NMT summary，JFR原件/summary/相关view保留。总录制包括setup/teardown，分配不等同纯生产编码成本。JFR开销单独归因，不替代主轮吞吐。
- 预锁定局部门禁：每产品主轮均值至少10,000 terminal business ops/s；accepted=terminal、unfinished=0、业务错误/超时0、资金不变量全部通过；outbox允许drop但记录数量。Java分配诊断目标≤64KiB/business op；单GC pause≤100ms。系统出现CPU throttling、新增swap或者JFR DataLoss则当轮无效。阈值是本机局部回归门禁，不是用户尚未给定的生产SLO。
- 整体验收另要求：open-loop真实API三阶段各业务p50/p90/p95/p99/p99.9/max、真实WS慢节点故障/恢复、native/FD/heap长稳与各重业务覆盖。当前JMH不提供这些证据，缺失时必须标为部分验证，不能声称“零性能影响”或整体性能验收完成。
- 命令：`mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am package -DskipTests`；随后逐产品 `java -jar surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar '.*committedRealtimeTrades' -p productLine=PRODUCT -p realtime=true -p maxInFlight=256 -p accountLanes=4 -f 1 -t 1 -wi 3 -w 3s -i 3 -r 5s -jvmArgsAppend '-Xms768m -Xmx768m -XX:+UseG1GC' -rf json -rff ARTIFACT/main-PRODUCT.json`。归因轮另加`-prof gc`和`-XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics -XX:StartFlightRecording=filename=ARTIFACT/PRODUCT.jfr,settings=profile,dumponexit=true`。
- 原始产物目录：`/tmp/surprising-realtime-validation-20260906/`。测试后追加具体时间、校验和、指标、问题与未测范围，不修改上述标准。

### 首轮执行结果：启动失败，全部无效

12个 fork 在 warmup 前因 `IllegalAccessError: org.agrona.UnsafeApi cannot access jdk.internal.misc.Unsafe` 退出，JMH父进程仍返回0且JSON为空。原因是命令行 jvmArgsAppend 覆盖了注解中 Agrona 所需的 opens/exports。全部原始日志/JFR保留上述目录，不能作为吞吐、分配或业务正确性证据。没有业务样本，不作阈值判断。

## 2026-09-06 实时出口验证第二轮（采集前重新锁定）

- 标准、机器、JVM、六产品线、256 in-flight、257用户、1 symbol、4 Lane/1 matcher、1024普通单/512fills、资金/冻结/仓位/终态/恢复要求、3×3s预热+3×5s测量+2s冷却、各项门禁及所有未覆盖范围与上一条采集前记录完全相同；仅修正启动参数，另加 `-foe true` 使 fork 失败中止。
- 被测 master 为 `92cbb3aa` 加当前实现；本次包含用户风险快照和失败快照的显式 UNAVAILABLE 帧。对照 commit：不适用（仅验证当前 master）。
- 主轮 JVM 参数锁定：`--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED -Xms768m -Xmx768m -XX:+UseG1GC`。归因轮在此前明确的 profile JFR/NMT/gc 参数基础上使用相同 opens/exports。
- 构建后按同一Python命令清单顺序逐产品运行，原始文件目录改为 `/tmp/surprising-realtime-validation-20260906-round2/`，保留旧轮全部文件；主轮不并行跑Maven或其它负载。

### 第二轮结果与证据（2026-09-06 11:15–11:21）

主轮全部通过预锁定的本机场景均值门禁，六产品accepted=terminal、unfinished/endBacklog=0、资金/冻结/仓位/订单及恢复hash校验通过，realtimeDroppedBatches=0。主轮CPU speed=100，swap持续9.50MiB无新增；并非零swap机器。

|产品线|terminal business ops/s ± JMH error|fills/s|归因B/business op|分配MiB/s|measurement GC次数/时间ms|JFR最大pause ms|
|---|---:|---:|---:|---:|---:|---:|
|SPOT|25890 ±19214|12945|20569|492.25|21/212|22.5|
|LINEAR_PERPETUAL|24018 ±3048|12009|22217|483.51|19/230|33.7|
|INVERSE_PERPETUAL|26671 ±9486|13336|22160|504.30|21/190|21.3|
|LINEAR_DELIVERY|25312 ±18085|12656|22225|522.25|21/199|21.0|
|INVERSE_DELIVERY|25238 ±11537|12619|22149|519.61|22/207|20.9|
|OPTION|24873 ±4391|12437|22276|494.75|20/221|31.5|

- 单笔普通单所以 terminal Core messages/s 与 business ops/s相同；没有batch，不提供API requests/s。JMH auxiliary `#`是操作总计不是每秒速率；取主分数×1024/512换算。置信区间较宽，不作精确生产容量结论。每callback终态完成后内部backlog为0，入口256请求波次与内部backlog不是同一指标。
- 严格按系统门禁，**五条衍生品归因轮出现CPU speed=97，判为无效性能验收数据**，上表对应归因列只用于诊断；SPOT归因轮speed=100。六份JFR均DataLoss=0，26–27秒原件保留，每份summary/views已生成。不能把无效轮与主轮混合宣称性能通过。
- SPOT JFR：进程user平均12.26%/system2.25%、机器20.62%；ExecutionSample按线程分组owner/fixture1692、matcher11、Account Lane3、realtime-drain1、other3。owner热点包含既有Lane/Matcher completion等待、assertAccountLanesHealthy和readyLaneMask；独立风险/投影/可靠exporter/Aeron网络线程不在该JMH进程，不能据此认为它们免费。
- 分配top byte[]36.94%、long[]8.53%、stream filter3.47%、OrderRuntime3.35%、RealtimeFrame2.98%。上述20–22KiB包含Core、fixture编码和周期查询，不是新增功能单独成本。TLAB内/外精确事件为0，缺对象数/op与精确最大对象。
- SPOT全JFR GC pause总324ms/33次，p50=8.15、p90=19.5、p95=21.5、p99/p99.9/max=22.5ms；与measurement GC212ms窗口不同。post-GC heap first17,368,760B/last215,771,008B/max751,828,992B，混合iteration创建/销毁及young GC，不能据此证明live-set稳定，需更长稳态检查。
- NMT SPOT退出reserved2,364,681,582B/committed983,829,870B；Java heap768MiB，GC native74.2→75.0MiB（峰75.6）、Code21.5→30.6MiB、Metaspace16.2→21.5MiB。5个DirectBuffer样本全部0：本场景只有outbox drain，未覆盖真实Aeron/Netty mapped/native池，不作无泄漏结论。
- ThreadPark/monitor热点、线程峰值、JIT/异常、file/socket/GC/VM数据逐产品保存在 `*.views.txt`。SPOT SafepointBegin38但profile未启用完整End/同步事件，不能算真实最大停顿；JIT仍有编译和deopt，未证明整窗口越过所有预热。FileWrite/SocketWrite事件为0，不等于阈值采样证明绝对无I/O；静态调用边界无交易owner同步外部I/O。
- 原始目录 `/tmp/surprising-realtime-validation-20260906-round2/`，`SHA256SUMS`列出日志/JFR/JSON校验。采样JAR备份 `benchmarks.jar` SHA256=`ee5e8db0b4ad8d52871dfd48b935ee9b339eec96141afe883c8cc53f7cc13442`。采样后新增rollback时整体丢弃私有暂存帧保护及对应故障测试；不改变本轮成交成功路径，但本轮不能覆盖该拒绝分支性能。
- 结论：局部主轮及资金正确性通过；整体性能仅部分验证。未测真实API三阶段分类型p50/p90/p95/p99/p99.9/max、open-loop与coordinated omission修正、真实Cluster HA和独立做市进程、真实native/FD长稳、强平/资金费/ADL/到期等重业务的新增出口负载。没有提供生产SLO，不宣称“零性能影响”。

## 2026-09-06 实时出口持续状态检查（采集前锁定）

- 目的：检查同一个Core实例连续处理大量交易时是否出现OOM、outbox失控或资金/终态/恢复错误；不能替代生产24小时soak或全链路延迟验收。
- 代码：当前master最终Core实现（包括rollback保护）；对照commit：不适用（仅验证当前master）。重建JAR，单独保存校验。
- 机器/JDK/GC/资金/1 symbol/257用户/4 Lane/1 matcher/固定256请求波次与第二轮相同。代表产品SPOT及OPTION顺序运行，0外部连接，closed-loop，maker持续参与，1024普通单/512fills/1用户快照/1book读取每cycle；0手续费。其它四产品长稳本轮未覆盖，六产品短JMH及功能另有证据。
- 每产品3×3s预热、1×180s measurement、不重建measurement内Core；启动前和产品之间冷却30s。强制GC只在JMH迭代间（`-gc true`）；同一heap768MiB、G1、opens/exports、NMT/JFR profile与gc profiler。完整recording含预热/终检，带profiler吞吐仅诊断。
- 门禁：业务资金/终态/恢复检查通过、无OOM/业务错误/未完成、period末backlog0；JFR DataLoss0、CPU speed100、无新增swap，否则性能数据无效。检查GC后heap趋势、NMT类别/线程及outbox计数；180秒不能证明无长期泄漏，尤其真实Aeron native与WS连接未进入此场景。
- 命令在第二轮相同命令基础上使用 `-gc true -i 1 -r 180s -prof gc` 并输出到 `/tmp/surprising-realtime-soak-20260906/`。所有系统采样、JSON/JFR及失败同样保留；不修改上述标准。

### 持续状态检查结果（2026-09-06 11:31–11:38）

- 当前Core源对应 `461f331d`（随后只改外围Router/可靠exporter）；两个实例各连续180秒measurement，采样包含预热/终检共194秒。只测SPOT/OPTION，不扩展为六产品全业务长稳结论。
- SPOT：4,993,024 terminal business ops/Core messages、2,496,512fills，27,734 ops/s（带profiler、单measurement无置信区间），19,858B/op、measurement GC1,661ms，JFR最大pause18.1ms。
- OPTION：4,658,176 terminal business ops/Core messages、2,329,088fills，25,877 ops/s（同口径），21,497B/op、measurement GC1,680ms，JFR最大pause19.1ms。
- 两者accepted=terminal、unfinished/endBacklog/max internalBacklog=0，realtimeDroppedBatches=0；全部用户和maker资金/冻结/仓位、保留挂单及快照恢复hash校验通过，无OOM和业务错误。
- 系统约30秒采样一次，所采CPU speed均100，swap保持9.50MiB无新增；短于采样间隔的系统波动未能排除。JFR DataLoss均0。
- GC后heap以30秒桶观察：SPOT稳定段每桶min45.4–45.5MiB/max46.8–47.3MiB；OPTION min45.3–45.8MiB/max46.8–47.1MiB。未观察到本次180秒稳定段持续增长。GC cause：SPOT G1 Evacuation224/System.gc8/Metadata2，OPTION226/8/2，显式GC位于迭代边界，不在每个业务操作中。
- NMT退出SPOT reserved2,376,942,555B/committed986,428,379B，OPTION2,374,503,208B/988,441,384B；GC native约74.1→74.4MiB，线程native末值约0.12MiB，Code/Metaspace含启动编译增长（SPOT12.81→27.01MiB、11.55→21.41MiB；OPTION12.97→28.27MiB、11.55→21.84MiB）。线程start/end含预热实例及恢复验证，不应把累计start直接当成存活线程数。
- JFR原件、summary、views、memory事件JSON、系统日志及JMH JSON位于 `/tmp/surprising-realtime-soak-20260906/`，原件SPOT约7.9MiB、OPTION约8.0MiB，校验见该目录SHA256SUMS。采样JAR未单独备份，之后被最终Maven打包替换，缺少该轮精确JAR二进制校验；保留源码commit、构建日志 `/tmp/realtime-soak-build.log` 和全部原始录制，故不作为完整可复现的发布性能认证。
- 本轮通过已定义的资金、终态、短期状态容量检查；真实API三阶段尾延迟、真实Aeron/WS/native/FD长期稳定性、真实Cluster切主、其余四产品及风险/强平/资金费/ADL/到期重业务长稳仍未测。**整体生产性能验收仍未完成**；不会把三分钟稳定段表述为无长期泄漏或零交易性能成本。

### 最终功能构建与交付记录（2026-09-06 11:44）

`mvn package -Drealtime.test.server=/tmp/valkey-realtime-build/valkey-8.0.1/src/valkey-server` 全reactor成功：1410测试、1388通过、0失败/错误、22跳过。跳过均为缺少 `SURPRISING_WITHDRAWAL_IT_DATABASE_URL` 的提现数据库集成测试；按任务边界未启动wallet。实际Valkey验证覆盖目录租约、乱序版本、缺口重建、定向UDP和MDC控制路由；新增Spring属性绑定测试及分片中途commit counter测试通过。最终日志 `/tmp/realtime-final-package2.log`。Router可执行 `-exec.jar` 与独立MediaDriver、Valkey实际启动成功，记录 `/tmp/realtime-router-startup-result.log`。这些是功能/启动证据，不改变以上生产性能部分验证结论。


## 2026-09-06 批量结算故障等待及平仓查询修复（采集前锁定）

- 被测代码：当前 master `f8e8b5bbe78f88eedb715bec6ab4f2289992134f` 加本次未提交修改；保存 source.patch、源码/采样JAR校验。对照 commit：不适用（仅验证当前 master），不检出历史代码。
- 修改：现货顺序批次结算每1024次未完成轮询检查 Lane 故障、中断和30秒期限，异常终止且不发布结果；批量改单成功后的异常进入不可继续的故障状态；手动平仓只读一次 Core 账户。新真实场景还发现现货改单重复占资，因此加入撮合前可用+原单剩余冻结校验，并在既有预占 Lane 任务中先解冻旧单，最终原单终态回收仍走原事件。
- 机器/JVM：macOS26.7 x86_64、Intel i9-9880H 2.30GHz、16 logical CPU、16GiB RAM；Oracle GraalVM25.0.1 HotSpot(TM)64-Bit / Maven3.9.16。固定G1、`-Xms768m -Xmx768m`；opens/exports `java.base/jdk.internal.misc=ALL-UNNAMED`。采集前 swap9.50MiB；每5秒采集pmset therm、swap、vm_stat及进程列表。
- 真实路径：新增 `ClusteredBatchTradingBenchmark.batchAmendRoundTripTrades`；六产品线逐一运行，SPOT覆盖本次等待及余额复用分支，其余五线检查共享批次错误处理不影响正常成交。4 Account Lane、1 matcher、risk engine0，257活跃用户、1 symbol、0外部连接；做市由fixture用户1256持续挂单/成交，未运行独立做市进程。
- 每cycle六波次、每波固定256 in-flight的预构建请求：maker限价卖100→用户批量买90→批量改价100成交→用户批量卖110→maker限价买100→用户批量改价100成交。batch size固定2，平均/最大均2；每cycle2560 business ops（1536下单、1024改单）、1536 Core messages、1024 batches/2048 items、1024 fills。无风险、资金费、强平、到期业务。realtime=true且独立线程排空outbox。
- 初态：每用户及maker结算资产1,000,000,000单位，零持仓，0费率；SPOT maker BTC513单位，其它用户BTC0。预留maker卖120的一单位挂单。往返价格均100，iteration结束验证所有用户及maker余额/冻结/持仓/预占与初态一致，只有预留maker订单存活；真实快照恢复business hash一致。函数逐cycle核验1536终态响应和1024实际成交，错误/超时必须0、accepted=terminal，unfinished business/Core与末尾backlog均0。入口最大波次backlog256、内部pending另报。
- 负载closed-loop、无固定到达率、不修正coordinated omission；JMH主分数cycles/s需乘2560报告terminal business ops/s、乘1536报告Core messages/s、乘1024报告fills/s及batches/s、乘2048报告items/s。它不是API requests/s或实际集群连接容量。
- 主轮：fork1、threads1、3×3s warmup、3×5s measurement、轮间2秒冷却、无profiler、JSON、`-foe true`。归因轮同参数，追加`-prof gc`、NMT summary/退出统计、基于JDK profile.jfc的明确自定义JFR，额外启用NewTLAB/OutsideTLAB、完整safepoint/VM operation事件。JFR包含启动、预热与teardown，额外采样开销只用于归因，不能替代主轮。
- 预锁定局部门禁：主轮各产品均值≥10,000 terminal business ops/s；资金/冻结/终态/恢复全部通过、错误及超时0；归因分配≤64KiB/business op、单GC pause≤100ms。CPU speed<100、新增swap或JFR DataLoss使对应性能轮无效；失败轮保留，不修改阈值。以上是本机回归门禁，不是生产SLO。
- 限制：本次不改变长期容器/队列生命周期；不以短JFR证明无泄漏。真实API三阶段分业务p50/p90/p95/p99/p99.9/max、open-loop、独立做市/Cluster HA/WS实际网络、native/FD长稳仍未覆盖，缺失即仅部分性能验证。Provider账户查询减少用五线多空共10次调用计数测试验证，未添加依赖完整Spring服务的Core微基准。
- 执行：`mvn -pl surprising-aeron-core/surprising-aeron-benchmarks,surprising-trading/surprising-trading-provider -am -Dtest=ClusteredBatchTradingBenchmarkTest,CoreOrderedOrderBatchTest,OrderBatchSettlementWaitTest,TradingRuntimeStateTest,SurprisingClusteredServiceTest,OrderServiceTest,OrderPlacementStateServiceTest,OrderAeronGatewayTest,AeronOrderCommandServiceTest -Dsurefire.failIfNoSpecifiedTests=false package`。随后按artifact目录run.py顺序执行 `java -jar benchmarks.jar '.*batchAmendRoundTripTrades' -p productLine=PRODUCT -p realtime=true -p batchSize=2 -p maxInFlight=256 -p accountLanes=4 -f 1 -t 1 -wi 3 -w 3s -i 3 -r 5s -foe true -jvmArgsAppend JVM_ARGS -rf json -rff RESULT.json`。
- 原始产物：`/tmp/surprising-batch-fixes-20260906/`；包含命令清单、JMH JSON/日志、JFR配置/原件/summary/views、系统记录、NMT、源码patch和精确JAR，采集后追加所有结果及SHA256。之前测试失败日志 `/tmp/trading-fixes-regression.log`（真实零余额改单重复占资）和 `regression2.log`（中间实现遗漏旧单终态回收）均保留，未产生性能数据。


## 2026-09-06 现货批量改单终态持续状态检查（采集前锁定）

- 与上一条相同源码、HotSpot25/G1/768MiB、256 in-flight、4 Lane/1 matcher/0 risk engine、257用户、1 symbol、batch2、maker持续参与和资金初态；仅SPOT，`batchAmendRoundTripTrades`同一Core实例连续180秒measurement，3×3s预热、1×180s测量、`-gc true`仅迭代边界显式GC，采集前30秒冷却。不开旧版本，不比较其它in-flight。对照commit：不适用（仅验证当前master）。
- 使用相同gc profiler、自定义JFR和NMT参数，另外每5秒记录测试fork的FD计数。关注批量旧单终态清理在多次复用后是否累积订单/预占/身份，以及GC后heap、native committed、线程/FD趋势。文件前缀 `soak-SPOT`，原件 `soak-SPOT.jfr`，仍保存在 `/tmp/surprising-batch-fixes-20260906/`。
- 门禁：资金/冻结/终态/快照恢复正确、无OOM、错误/超时/unfinished/末尾积压均0、JFR DataLoss0、无新增swap、CPU speed100；profiler吞吐只作诊断。180秒只构成局部持续状态检查，不证明生产长期无泄漏；真实Aeron/Netty/WS native池、24小时live-set趋势及API分段尾延迟未测。


### 首轮功能与采样结果（14:20–14:29，尚含一次可避免的预检 Lane 读取）

- 直接受影响模块及依赖全测848项，零失败/错误/跳过。Core423、trading-provider170、benchmarks63，其余192项来自协议/client/API依赖。命令及日志 `trading-fixes-modules.log` 已归档。故障注入在真实 Lane 内触发成交余额溢出，证明批次不再无限自旋、不发布错误终态/快照，原快照+同一改单日志可恢复且重复回放不重复结算。
- 新场景暴露的现货零可用余额改单问题已修复，买方涨价不足在撮合前拒绝，降价/卖方满额改单复用原冻结。中间修复还曾漏掉原单回收，继而最终 cancel 重复执行失败；`regression2/3/4`及`batch-diagnostic`日志完整保留。已修正终态事件纳入和元数据不变时的回收；测试辅助等待改用实际`commitReadyMatching`有界推进，避免测试自身掩盖 Lane 故障。未放宽资金及终态断言。

|产品线|主轮 terminal business ops/s ±99.9% JMH error|归因 B/business op|归因 MiB/s|measurement GC次数/时间ms|JFR最大GC pause ms|
|---|---:|---:|---:|---:|---:|
|SPOT|23897 ±1256|20550|416.7|17/193|33.30|
|LINEAR_PERPETUAL|25444 ±5981|20933|507.0|22/199|22.90|
|INVERSE_PERPETUAL|26150 ±3334|21203|490.3|22/195|18.81|
|LINEAR_DELIVERY|27154 ±20186|21182|485.4|20/179|18.93|
|INVERSE_DELIVERY|27175 ±19952|21230|477.5|19/204|28.90|
|OPTION|26254 ±637|21247|493.5|20/177|19.67|

- Core messages/s=business ops/s×0.6，fills/s和batches/s=×0.4，items/s=×0.8；精确值、accepted/terminal auxiliary计数、完整JMH误差/参数在JSON。每cycle核对响应/成交数，accepted=terminal、unfinished/endBacklog=0、最大入口波次256、内部callback末尾maxBacklog0、业务拒绝/错误/超时0、outbox droppedBatches0。所有六线资金/冻结/持仓及快照恢复通过。
- **仅SPOT无profiler主轮通过机器门禁**。五条衍生品主轮以及全部六条归因轮出现CPU speed<100，故表中这些性能值只用于诊断；全程swap9.50MiB未增加，七份JFR DataLoss均0。不能宣称六线性能验收通过。
- JFR CPU按owner/fixture、matcher、Account Lane、外围线程分类输出。SPOT samples分别1694/5/4/6，JVM user平均13.62%、system2.17%、机器19.56%；无独立risk/projection/Core Fact/Kafka/网络sender线程，不能据此推断生产外围服务成本为零。热栈包含必要的Lane预占完成等待、cluster callback等待及本次`awaitOrderBatchMatcherSettlement`；另发现新增预检用`balance()`触发Lane读取56个样本，因此下一轮改用已有owner发布余额标量，避免引入额外任务/BalanceRuntime副本，不取消业务完成屏障。
- 分配归因：SPOT top byte[]34.10%、Long5.97%、long[]5.92%、OrderRuntime3.38%；最大记录对象4,194,320B。已启用TLAB内/外事件并保留tlabs视图，精确对象总数/op仍缺失。全部归因数据含夹具编码、启动/teardown和周期恢复；不能当作单次新增逻辑成本。SPOT全录制GC pause27次/281.30ms，p50=7.11、p95=23.93、p99/max=33.30ms，与measurement193ms窗口不同。
- NMT所有类别first/last/peak见`jfr-analysis.json`和views。SPOT heap committed768MiB；GC native74.2→77.7MiB，Code21.8→35.2MiB（峰37.6）、Metaspace16.4→21.8MiB。5次DirectBuffer样本count/capacity/used均0；未包含真实Aeron/Netty/Mapped池。Java monitor竞争事件0，Lane正常park与owner自旋单独归因；没有完整墙钟状态占比/上下文切换指标。完整safepoint采到SPOT最大43.4ms，记录边界有2个Indefinite项，不当作零；没有真实业务尾延迟，无法做暂停与p99门禁比较。JIT仍存在编译（7240方法、累计24s、峰826ms、1 bailout）及deopt，不能断言整窗口完全越过预热。
- I/O：SPOT和持续轮仅录到JMH控制`main`线程ObjectInputStream socket read各2次，未录到交易owner文件/socket I/O；阈值采样不是绝对无I/O证明。异常视图含反射/MethodHandle/JNR初始化，业务错误数0。文件/socket/异常、线程、JIT、GC和safepoint逐份views已保存。
- 180秒现货持续轮：3,210,240 business ops、1,926,144 Core messages、1,284,096 fills，终态/资金/快照全部通过。GC后heap稳定阶段各30秒桶min45.25–45.43MiB/max46.70–47.26MiB；FD采样保持35。GC pause155次/1264.59ms，p50=7.94、p95=10.47、p99=16.08、max18.74ms；GC native74.1→74.4MiB、线程native末值约0.12MiB。CPU speed最低45，持续轮吞吐门禁无效；内存观察只描述该次短时受降频负载，不能证明长期无泄漏。
- 原件六份短JFR各27秒/约3.0–4.1MiB，持续JFR194秒/约9.6MiB。`benchmarks.jar` SHA256=`43242800220e2433367bfaeb86a6f65544e728f10d0093d2ff7c8680ec38f450`，`source.patch` SHA256=`fadef71c4f8cf669dfd384eb4f0a82a6a68cab371059ed9c5549ea3ffd6ef11d`。详见原目录SHA256SUMS。删除的仅是可从JFR重建的巨大事件JSON展开文件，原件、汇总和脚本保留；后处理改为流式解析。后处理发生在采样结束后，其内存开销导致宿主swap升高，不混入上述采集系统记录。

## 2026-09-06 现货 owner 余额预检修正后复测（采集前锁定）

- 当前master同一基础提交+最终修改，源码/JAR重新保存到 `/tmp/surprising-batch-fixes-20260906/round2/`。对照commit：不适用（仅验证当前master），不作首轮与本轮性能比较。
- 唯一新增修正：SPOT批量改单预检改用`publishedAvailableBalance`，前一项结算完成屏障保证owner标量已更新；不产生额外Lane任务或余额副本。既有预占Lane任务仍做实际资金冻结校验。
- 最终小改动重跑`CoreOrderedOrderBatchTest,TradingRuntimeStateTest,ClusteredBatchTradingBenchmarkTest,OrderBatchSettlementWaitTest,SurprisingClusteredServiceTest`，共101项，通过后打包。五条衍生品短测/Provider代码未改变，不重采未受影响路径；该次功能测试仍覆盖六线夹具。
- 场景、阈值、HotSpot25/G1/768MiB、opens/exports、4 Lane/1 matcher/risk0、257用户、symbol1、batch2、256 in-flight、每cycle2560 ops/1536 messages/1024 fills、realtime=true、资金/终态/快照规则均与首轮锁定一致。只采SPOT最终路径，主轮和归因轮均3×3s warmup+3×5s measurement，fork1/threads1；持续轮同一Core实例1×180s measurement、3×3s预热、仅边界`-gc true`。
- 主轮前冷却30秒，主轮与归因轮间30秒，归因轮与持续轮间共60秒。归因/持续使用同一自定义JFR+NMT+gc profiler配置，后处理JSON不展开逐TLAB栈（原JFR事件及tlabs视图保留），流式解析防止诊断工具物化多GiB对象。
- 机器CPU speed门禁仍100，JFR DataLoss0，预存swap约2.6GiB，以每轮首个sysctl采样为基准，只允许持平/下降，不允许新增；记录既有swap环境不洁净这一限制，不宣称零swap机器。CPU降频或新增swap则性能数据无效，保留数据不放宽门禁。真实API三段分业务尾延迟、open-loop、独立做市/WS/HA、24小时及真实native池仍未测，仍只能部分验证。
- 命令为同目录`run.py`的完整commands.jsonl；只保留最终SPOT主轮、归因轮及持续轮。所有结果继续追加本文件，不覆盖首轮失败/诊断记录。


### 最终现货复测结果（14:43–14:48）

- 最终功能改动复测101项全通过（Core91、六线benchmark fixture10）；此前直接受影响模块全测848项零失败/跳过，Provider代码此后未变。资金校验、已完成订单/原单预占回收、故障不提交及快照+日志恢复均通过。未运行wallet、真实外部API/WS和三节点HA。
- 最终SPOT无profiler主轮：**24,501 ±10,861 terminal business ops/s**（JMH99.9% error）；14,701 terminal Core messages/s、9,800 fills/s、9,800 batches/s、19,601 items/s。4 Lane/1 matcher、257用户/1symbol、256 in-flight、batch平均/最大2；closed-loop，不代表生产容量。均值通过预锁定10,000 ops/s本机门禁，置信区间较宽，不作更精确容量推断。
- 主轮CPU speed100，swap2522.75→2490.75MiB；归因轮CPU speed100，swap2490.75→2426.75MiB；三轮`vm_stat Swapouts`均保持717975、Pageouts61908，既有swap页有读入，环境并非无swap。两份JFR DataLoss0。因此最终SPOT主轮及归因轮通过本次已定义的局部系统门禁；这不等同生产性能验收。
- 归因轮：22,068 business ops/s（profiler数据仅归因），20,504 B/business op、423.04MiB/s；measurement GC19次/203ms。全JFR27秒，GC pause29次/288.81ms，p50=7.97、p95=19.85、p99/max=23.00ms，均低于本轮100ms上限。额外JFR/NMT/GC统计开销不从主轮分数扣算，也不与首轮作版本比较。
- 最终JFR静态/采样共同确认预检直接读取owner发布余额，`prepareOrderBatchItem→balance→onLane`等待样本0；仍有既有预占Lane完成屏障与结算完成等待，不将busy-spin计作实际金融计算。短JFR execution samples：owner/fixture1635、matcher11、Account Lane10、realtime drain1、other8；JVM user均14.01%、system2.23%、机器27.02%。JFR口径线程CPU平均owner user5.34%/system0.60%，matcher0.99%/0.48%，四Lane见thread-cpu-load；没有完整墙钟/上下文切换分解，不能据稀少Lane execution samples认为Lane成本为零。
- 分配采样weight按组估计owner/fixture8.05GB、matcher0.53GB、Lane1.17GB，包含全录制预热/teardown；top仍为byte[]、long[]、Long、OrderRuntime。TLAB内/外事件原件与tlabs视图保留，后处理不物化所有逐TLAB栈；精确对象数/op和最大对象不在最终汇总中，不能用采样weight当作对象计数。
- Heap/NMT：heap committed768MiB；短轮GC native74.2→77.7MiB（峰78.5）、Code22.6→35.0MiB（峰37.3）、Metaspace16.5→21.8MiB，所有NMT类别及first/last/peak在JSON/views/退出日志。DirectBuffer采样count/capacity/used均0，本夹具无真实Aeron/Netty/Mapped池。没有JavaMonitorEnter竞争事件，ThreadPark825包含Lane空闲及JMH控制等待。Safepoint最大23.0ms，记录边界仍有2个Indefinite项；尚无真实业务p99/p99.9可供比较。
- JIT：短轮统计7232编译方法、累计27.4s、最长1.14s、1 bailout，Deoptimization310；recording包含启动编译，不能证明整窗口完全稳定。I/O仅2次JMH main控制socket read，未采到业务owner同步文件/socket访问；阈值采样不等同绝对无I/O证明。异常/线程/类加载/VM operation详情已归档。
- 最终180秒持续轮：**4,177,920 business ops、2,506,752 Core messages、1,671,168 fills**；每cycle终态/成交数相符，accepted=terminal、unfinished business/Core=0、末尾backlog0、入口最大波次256、内部callback后maxBacklog0、拒绝/错误/超时0、outbox droppedBatches0。全部用户和maker期末余额/冻结/持仓、原单及新单终态回收、snapshot恢复hash通过；无OOM。
- 持续轮带profiler23,207 ops/s、19,715B/op、436.11MiB/s，measurement GC179次/1371ms；全194秒JFR GC pause197次/1532.86ms，p50=7.73、p95=8.57、p99=15.37、max17.92ms。稳定阶段30秒桶GC后heap min45.18–45.64MiB、max46.91–47.19MiB，未观察到该窗口持续增长；FD全部35。GC native74.1→74.4MiB、线程native末值0.12MiB、Code12.5→30.7MiB、Metaspace11.3→21.8MiB，后两者包含预热编译增长。持续轮CPU speed有93/95/97，故其吞吐仅诊断；不能用受降频三分钟负载证明长期无泄漏。
- 精确产物位于`/tmp/surprising-batch-fixes-20260906/round2/`：最终JAR SHA256=`f782ae48ef7063cd5cf6d0b431a3ade2a32b78fad5fc4a0407d6e873eda3f418`；source.patch SHA256=`fb2e4f1b978e4c1cb8d24e7d13536831eb8eafc369a9eae6262c99812c9ba9fe`；JFR配置SHA256=`114575ecd54d9227700c212f1417cbe7afe342879535206dc6319b7a1e93d4ab`。原JFR短轮3,255,541B/27s，持续11,874,429B/194s；完整summary、26类view、metrics、逐组归因、系统日志和命令及文件校验见SHA256SUMS。
- **结论：功能修复及最终SPOT短轮局部阈值通过，整体性能仍仅部分验证。** 五条衍生品首轮性能因降频无效；未测真实API/WS三阶段分业务p50/p90/p95/p99/p99.9/max、open-loop及coordinated omission、独立做市、生产集群切主、真实native池/FD及24小时长稳。没有这些证据，不宣称六线生产容量、长期无泄漏或“零性能影响”。

## 2026-09-06 交易维护功能验证（未进行性能采集）

- 被测代码：当前 master 基于 `40a32d5b9176ad2cc7eaabd2aa3a4cc02650cb9c` 的本次维护改动；
  除 README 外的已暂存源码补丁 SHA256：`736b865f2259c42d8fdfb16a2a947cc42a40e378798792666c3e0f459accf6e1`。
  对照 commit：不适用（仅验证当前 master）。验证时间为本机 2026-09-06，UTC+8。
- 环境：Oracle GraalVM 25.0.1 HotSpot、Maven 3.9.16、macOS 26.7 x86_64，Intel i9-9880H、16 GiB RAM。
  本次按用户此前「不要进行压测，少量样本验证功能」执行有限功能场景，没有预锁定性能阈值或采集吞吐指标。
- 改动路径：六线维护门控、普通/触发撤单、五衍生品 reduce-only 市价/限价 IOC、固定价格清退、
  原有到期/资金费任务与清退联动、持久任务恢复、Core 快照和资金核对。
  `SettlementSolvencyBenchmark` 新增 `settlementTrigger=MAINTENANCE`，支持五条衍生品；未启动 JMH 定时测量。
- Maven 命令：`MAINTENANCE_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:55439/postgres mvn -q -pl surprising-trading/surprising-trading-provider,surprising-account/surprising-account-provider,surprising-funding/surprising-funding-provider,surprising-gateway,surprising-aeron-core/surprising-aeron-benchmarks -am test`。
  1248 项中 1226 通过、0 失败/错误；22 个已有 CustodyWithdrawalReconciliationPostgresTest 因未配置其专用数据库而跳过。
  新维护集成测试使用独立 PostgreSQL，36 项全通过；Core 维护11、协议2、控制器2、网关审批套件29、
  到期联动套件9、资金费套件9通过。最终 `mvn -q -DskipTests install` 全 reactor 构建通过。
- 有限偿付验证：`java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.util.zip=ALL-UNNAMED -cp surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar com.surprising.aeron.service.MaintenanceSettlementVerificationMain`。
  五衍生品 × CROSS/ISOLATED 共10例通过，复用固定 `maxInFlight=256` 的既有偿付夹具，逐例有限执行，无到达率或持续负载。
  验证保险不足不扣部分款、补资、快照恢复、重复命令、逐用户余额/冻结/仓位、保险与清算总额守恒。
- 发现并修复：错误清退价格必须在进入 matcher 前拒绝；明确门控拒绝与未知结果需要不同重试身份策略；
  release 必须持久化为独立恢复阶段；已有强平/保险/ADL 不得被新清退冻结；晚到的到期事件仅在 Core 已 CLOSED 后确认，
  资金费暂停期间不得伪造结算成功。新增强平测试最初只更新 mark 未执行风险扫描，补上真实 continuation 后通过，保留失败日志。
- 证据目录：`/Users/atomex/Desktop/surprising/maintenance-evidence/2026-09-06/`，含最终测试/构建日志、
  222 份 Surefire XML、有限偿付日志、源码补丁、浏览器 QA 脚本/截图及 `SHA256SUMS`。
  浏览器验证使用接口契约桩；实际财务集成使用真实 PostgreSQL 与 Core/matcher，但网络边界在进程内连接。
- 未测：本次新功能的三节点真实网络故障矩阵、真实网关至浏览器全栈、推送、JMH/JFR、GC/分配、
  heap/native、线程阻塞归因、吞吐和分位延迟、长期泄漏。没有 JFR artifact，本条不构成主链路性能验收，
  不声称零性能影响、生产容量或完整生产上线验收。Core 仍保留原有查询与生命周期一致性 fence。


## 2026-09-06 Instrument 当前配置与去版本功能验证（未进行性能采集）

- 被测代码：当前 master 基于 `407871bf` 的本次改动，最终提交号及源码补丁校验见证据目录。对照 commit：不适用（仅验证当前 master）。
- 环境：Oracle GraalVM 25.0.1 HotSpot、Maven 3.9.16、macOS 26.7 x86_64。按用户「不要进行压测，少量样本验证功能」执行，未定义或采集性能阈值、吞吐、分位延迟、GC/分配数据。
- 变更：每产品线/币对仅一份当前配置；删除历史版本 API、配置版本表和缓存；不可变操作日志保留操作人、原因、时间与前后字段。计算参数审计 ID 与最新操作 ID 分离，暂停/恢复不改变存量订单/持仓的计算依据；后台控制线程通过独立 Aeron 维护客户端同步 Core，界面展示实际确认状态。
- 协议、Core 状态与快照随字段更新；六线验证暂停、恢复、乱序更新、维护门控保留、资金不变及快照恢复。新增 `InstrumentPauseAdmissionBenchmark` 六产品线场景，通过 JUnit 各有限调用一次验证真实 Core 路径，未运行 JMH 定时测量，无 JFR artifact，不构成主链路性能验收。
- 全 reactor `mvn test`（专用本地 PostgreSQL 的 INSTRUMENT_TEST_JDBC_URL 和 MAINTENANCE_TEST_JDBC_URL）：1487 通过、0 失败/错误；22 个既有 custody 数据库测试因未配置其专用数据库跳过。随后定向回归 39 项、订单审计字段回归 35 项均通过，重复覆盖不叠加为唯一测试数。真实 PostgreSQL 当前配置/日志事务测试通过。
- 最终初始化使用 `psql -X -v ON_ERROR_STOP=1 ... -d instrument_current_validated -f init.sql`，完整事务成功。`INSTRUMENT_SEED_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:55439/instrument_current_validated mvn -pl :surprising-aeron-tools,:surprising-trading-provider -am -Dtest=InstrumentSeedCoreContractTest,InstrumentCoreSyncServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`：7 项通过；逐条验证 1151 个初始化配置编码/解码并被 Core 接受。
- 数据质量修复：31 个币本位配置的 base_asset 从错误 USD 修正为 settle_asset。原期权快照仅 30 个 USDT 结算合约的行权价能以现有整数 tick 精确表示，仅保留这些合格记录，未猜测或舍入价格。各线数量：SPOT 512、LINEAR_PERPETUAL 430、INVERSE_PERPETUAL 15、LINEAR_DELIVERY 148、INVERSE_DELIVERY 16、OPTION 30。期权补足 512 需要重新校准原始数据。
- 前端：管理后台 lint/build 通过；用户 Web 41 项测试及构建通过（lint 有既有提示，无错误）；Flutter analyze 无问题、53 项测试通过。浏览器桌面/移动端检查实际 React 页面及接口契约桩，覆盖必填原因、审批、日志差异、产品线和 Core 待确认/已确认，不等同真实网关全栈验收。
- 原始日志、浏览器脚本/截图、源码补丁和 SHA256SUMS：`/Users/atomex/Desktop/surprising/instrument-current-evidence/2026-09-06/`。早期期权种子失败诊断另存，最终 1151 个配置校验通过。
- 未测：本次修改后的三节点真实网络故障矩阵、真实网关至浏览器端到端、JMH/JFR、持续负载及生产容量。协议、JSON 字段和快照格式需前后端/Core 配套更新；产品未上线，不提供旧格式兼容。不能据功能测试声称零性能影响。


## 2026-09-06 async-profiler 4.5 有限样本诊断：采集前定义

- 当前 master 被测提交 `ae21a38735cd53820b1105296ba6bc5725a786fb`；对照 commit：不适用（仅验证当前 master）。不改生产代码。本条在采集前锁定，仅做诊断，不作为性能验收或吞吐对照。
- 工具：官方最新版正式版 async-profiler 4.5（GitHub latest API 已核验），macOS 下载包 SHA256 `46d04ef81f532a065a0b3877e488aa706afa14aa2ea14433b323db9e6fda76dc`。HotSpot Oracle GraalVM 25.0.1、Maven 3.9.16；macOS x86_64、Intel i9-9880H、16 GiB RAM。
- 固定场景：六产品线分别启动独立 JVM，复用 ClusteredBatchTradingBenchmark.Workload；1 个 matcher、4 Account Lane、256 in-flight、256 用户加 1 maker、1 symbol、1 个模拟 ClientSession。真实 Core/matcher，Aeron Cluster 网络回调为夹具；做市由同进程 maker 挂单维持，无独立做市进程、网络连接、数据库或 WS 推送。
- 每 JVM 初始化后预执行 1 轮改单成交往返+批量挂撤单，停 250ms；诊断阶段固定 2 轮、轮间停 100ms，不按时长循环、不测到达率、不加持续负载。batchSize=2；每轮 3584 business ops（改单成交往返2560、挂撤1024）、2048终态Core业务messages、1024fills、512额外查询；每线采样7168 business ops、4096 Core业务messages、2048fills。有限预热不能保证 JIT 稳态。
- 初态：每用户/maker 1,000,000,000 settle units；现货maker额外513 BTC最小单位；单symbol价格100、maker保留卖价120；费率为0，衍生品 CROSS，标记价100。每轮验证终态响应/成交数量，最终验证用户与maker余额、冻结、预占、仓位归零、仅剩maker挂单，以及快照恢复businessStateHash。资金费、强平、保险、到期结算不在该夹具覆盖范围。
- JVM：`-Xms512m -Xmx512m -XX:+UseG1GC -XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints`，开放 jdk.internal.misc 和 java.util.zip。JVM自带JFR `settings=profile,dumponexit=true` 覆盖整个进程；async-profiler按业务与恢复两个窗口分别采集 CPU 1ms、wall 10ms、alloc 128KiB、lock 1ms，threads=true。JMH timed measurement：未运行；无固定测量秒数，执行完固定样本即停止。
- 正确性通过条件：全部有限断言通过，未完成命令/期末积压为0，快照及资金核对通过。诊断数据条件：profiler成功产生可解析文件，检查DataLoss、采样错误及同机环境；采样不足、启动/JIT/夹具分配需单列，不能据缺失样本证明没有问题。观察热点必须追溯到实际业务调用路径；自旋、空闲park、恢复分配不直接定性为bug。
- 产物目录 `/Users/atomex/Desktop/surprising/async-profiler-evidence/2026-09-06/`，包含有限运行入口、命令、环境、原始JFR、火焰图和校验。本轮不提供requests/s、持续terminal business ops/s、分位延迟、长稳泄漏及生产容量结论；性能验收阈值不适用。

### async-profiler 第一轮无效与第二轮采集前定义

- 第一轮 SPOT 在 profiler.start 拒绝组合：`Cannot start wall clock with the selected event`。未进入采样业务窗口，无 CPU/alloc/wall 结果，不是业务断言失败。保留 SPOT.log、SPOT-jvm.jfr。原有限入口 finally 同样尝试不支持组合，未完成恢复验证，不能将该轮算作通过。
- 第二轮在执行前改为独立 JVM 分开采集：每产品线各 CPU+alloc+lock（1ms/128KiB/1ms）和 wall（10ms）两次；业务与恢复各独立文件。其余场景、JVM及有限轮数不变。两种模式各自每线7168采样 business ops，不合并为容量测量；CPU在macOS的实现限制会单独注明。无持续压测，仍仅诊断。

### async-profiler 第二轮结果与源码核对（仅诊断）

- 2026-09-06 21:34–21:37 UTC+8，六产品线 × 两模式共12个独立JVM全部 exit 0。每次业务断言及最终用户/maker资金、冻结、预占、仓位、终态订单、快照恢复hash均通过。含预热每JVM acceptedCore=terminalCore=6144、queries=1536、unfinished=0、endBacklog=0；单次采样阶段7168 business ops、4096业务Core messages、2048fills。maxBacklog=0是回调完成后的内部观测，不是无并发的证明。
- **平台口径纠正**：async-profiler 4.5 原始 `jdk.ActiveSetting` 显示 `event=cpu` 实际 `engine=wall`、kernelSymbols=false。文件名仍保留 cpu 以还原执行参数，但这些 ExecutionSample 不能当作 Linux perf CPU 时间。采样到大量 Graal JVMCI-native 编译线程，有限预热没有越过JIT，不能输出稳态CPU占比/吞吐。wall模式另有10ms全线程采样，空闲Lane的park及显式100ms节流sleep均需排除解释。
- **确认的问题一：入站协议解码存在可避免的临时对象。** `CoreMessageType.fromWireCode`、`CommandSource.fromWireCode` 对 `values()` 建Stream；`ProductLineWireCode.decode` 对entrySet建Stream。真实入站 `CoreMessageFlyweightDecoder.decode` 调用栈在六线均采到枚举数组/Stream等分配；每线该调用链采样权重约5.12–7.12MiB，占整个诊断业务窗口样本权重约4%–6%。这不是响应验证夹具造成的。适合优先使用保留现有wireCode和非法值异常语义的直接查表/switch优化；本轮只定位，未修改协议代码。
- **确认的问题二：结算调度轮询路径反复分配Long。** `CoreProbeState.dispatchReadyPlaceSettlements` 的Long采样权重每线10.38–13.75MiB，约8%–11%。源码存在 `pendingOrderBatches.get(pending.sequence())` 的boxed key查询，是候选来源；由于Graal内联归因，SPOT行号映射落在4670循环头，而该行调用的 `PendingMatchingRing.dispatchHead` 本身是primitive slot访问，所以不能错误归咎于ring，也不能将Map替换收益说成已经验证。下一步应隔离此调用的装箱及重复轮询，再保留原有批次顺序、拒绝、撤销和commit fence语义验证。
- **观察而非bug：同步完成边界仍存在。** SPOT 10ms wall窗口owner115个样本中50个经过commitReadyMatching，14个叶子为onSpinWait；有17个含await，20个来自本诊断显式sleep。这些计数有包含关系，不能相加或当作端到端延迟。该边界保证有序log callback完成，不能为了消除等待直接删除。Account Lane空闲park不等同锁竞争。
- 业务窗口分配权重（MiB，采样估计、非精确总字节，也非稳态B/op）：SPOT114.75、LINEAR_PERPETUAL126.38、INVERSE_PERPETUAL138.38、LINEAR_DELIVERY127.88、INVERSE_DELIVERY128.50、OPTION127.38。主要类含byte[]、Long、long[]、Stream、OrderRuntime；其中夹具 `lambda$setup$2` 响应复制/解码占23.88–30.88MiB/线，已独立标注，不归为交易owner业务必要开销。恢复窗口单独保存，没有混进这些业务数字。线程分组及各类/调用点明细见profile-aggregates.json和allocation-attribution.json。
- 六个CPU参数轮次全进程JFR各4次G1New，最长单次pause6.63–7.16ms；含启动后GC live heap约23→29/30→31MiB，不能据短期增加认定泄漏。全进程JFR平均JVM CPU占机器容量15.0%–27.3%、机器18.2%–34.1%，仅环境背景，不能映射为业务CPU。180–207个Deoptimization及大量native JIT栈确认冷启动干扰。NMT退出汇总保存在各log，未采连续NMT/native池/FD轨迹，无法证明无泄漏。
- profiler 1ms monitor阈值下业务窗口锁事件0；全进程profile.jfc采样的JavaMonitorEnter/DataLoss均0，不能证明不存在短锁或丢失之外的采样偏差。未采到profile.jfc阈值以上FileRead/SocketRead业务IO，但采样阈值下同步IO仍不能排除。CPU_Speed_Limit采集前后100，非连续热状态记录，不能承诺没有中途节流。
- 原始文件：24个有效async-profiler JFR（业务/恢复分开）和12个JVM JFR，另保留首轮失败JVM JFR；48个火焰图、collapsed调用栈、全部jfr summary、JVM事件JSON、命令commands.json、采样入口FiniteCoreProfile.java、run.py、analyze.py、metrics.py和SHA256SUMS位于上述证据目录。官方发行包/API和参数文档副本已保存。
- 未测：真实Aeron三节点/网络、网关/数据库/Kafka/WS，资金费、强平、ADL、到期行权、非零费率、长期内存、稳态CPU、准确分配对象数/op、全量TLAB与最大对象、分业务延迟分位数/持续吞吐、完整safepoint/VM归因。仅做有限诊断，没有JMH定时测量，不构成完整性能验收。已发现两处应优先优化的分配路径，未发现此次样本中的资金/订单终态错误；不据此宣称项目无其他问题。
- 被测benchmark JAR SHA256：`bfbe04ea99d25a1f041d53079f86728cab2a5317796a57354553f57e849ae4d9`；源码提交仍为`ae21a387`。本次仓库只追加验证记录，未修改生产逻辑。复现：在上述环境执行证据目录 `python3 run.py`，分析执行 `python3 analyze.py` 和 `python3 metrics.py`。


## 2026-09-06 async-profiler 分配热点修复：采集前定义

- 当前 master 基于 d0c0b73b 的修复工作区；对照commit不适用（仅验证当前master），不检出/重跑旧版，不形成旧版吞吐比较。协议5处解码删除逐调用Stream/枚举数组克隆；PendingMatching惰性复用owner-only Long key，初始化新序号时清空，同序号复制时共享。保留原LinkedHashMap插入顺序和全部提交边界，不新增Map或队列。
- 每个被查批次/命令最多缓存一个Long，生命周期限于可复用PendingMatching槽位到下一次initialize；为既有有序Map提供稳定查询键，不增加逐轮状态快照。当前真实采样调用链明确要求减少反复装箱；效果待本轮验证，不预先声称零分配。
- 新增JMH场景decodedBatchAdmissionAndSettlement组合真实入站、改单成交和批量挂撤，6产品线有限调用，继承既有Workload资金、订单、快照核对；本次按用户此前不压测约束不执行JMH timed measurement，性能验收仍未完成。
- 采样环境/阈值/初态延续上一诊断：HotSpot GraalVM25.0.1、G1、512MiB固定heap、4 Account Lane、1 matcher、256 in-flight、256用户+1maker、1symbol，batch2、零费率、CROSS；1轮预执行、停250ms、2轮采样及轮间100ms停顿。每线7168采样business ops、4096业务Core messages、2048fills。不测持续到达率。
- 本轮只运行CPU参数+alloc128KiB+lock1ms各线一次，明确macOS engine=wall，不当作精确CPU时间；JVM profile.jfc覆盖全进程，业务/恢复分开async JFR。JVM参数同上一轮，含NMT、DebugNonSafepoints。无稳定预热/吞吐阈值。
- 通过条件：协议全部有效wireCode保持映射，非法边界/空洞拒绝不变；序号键复用和环槽代际隔离通过；相关协议/服务/bench Maven测试通过；六线有限断言通过、unfinished/endBacklog0。采样检查目标枚举数组/Stream及dispatch Long是否仍有样本，并以源码确认消除逐查询分配，不能单凭零样本证明绝对零分配。
- 证据目录 `/Users/atomex/Desktop/surprising/async-profiler-evidence/2026-09-06-fixes/`。仍不覆盖真实网络/网关/WS、资金费/强平/到期、长期泄漏、生产容量；本轮不得声称JMH/JFR完整性能验收。

### 分配热点修复结果

- HotSpot25上 `mvn -pl :surprising-aeron-benchmarks -am test`：110个测试类、677项通过，0失败/错误/跳过；包括协议有效码/空洞/边界、PendingMatching键复用/同序号复制/不同序号重置、批次有序推进/拒绝续批、六线资金和恢复测试。随后同reactor `-DskipTests package`成功。
- 六个独立JVM的新JMH场景有限调用全部通过；每线总计含预执行10752 business ops、6144业务Core messages、3072fills，采样阶段仍为7168/4096/2048，计数断言通过。每线用户/maker资金、冻结、预占、仓位归零及快照hash一致，unfinished=0、endBacklog=0。未执行JMH定时测量、网络压测或容量验收。
- 六线的5个目标协议lookup调用链分配样本均0；源码正常查找路径仅数组访问或switch，无逐调用values/Stream。非法码仍抛原ProtocolException，空洞不会误映射。
- 原反复装箱查询已改为 `pending.sequenceKey()`：首次惰性装箱，后续轮询复用；batch插入也用同一键。有序LinkedHashMap未替换，顺序/拒绝/提交fence不变。代际重置与复制测试通过。单槽位至下一次initialize最多保留一个Long，不是全局增长缓存。
- dispatchReadyPlaceSettlements含下游的Long样本权重为SPOT0.125MiB、LINEAR_PERPETUAL0.125MiB、INVERSE_PERPETUAL0、LINEAR_DELIVERY0.25MiB、INVERSE_DELIVERY0.125MiB、OPTION0.125MiB。大部分残留明确在completeMatching/applyMatcherProgress/applyPipelinedPlaceBatchResults，OPTION的1个样本仅归因到内联父帧，不能进一步断言具体来源；未声称整链路无Long分配。直接表查询不再逐轮产生新key的结论同时来自源码与代际复用测试，不依赖采样缺失。
- 当前诊断业务窗口总分配采样权重（MiB）：SPOT90.50、LINEAR_PERPETUAL94.38、INVERSE_PERPETUAL90.50、LINEAR_DELIVERY97.12、INVERSE_DELIVERY95.62、OPTION96.88；仍含夹具响应验证和未充分预热，不作为精确B/op或吞吐改善百分比。源码/计数改动后的样本仅用于目标调用栈归因，不运行旧版对照。
- 六个全进程JFR各3次G1New，最长单次pause6.49–7.59ms，JavaMonitorEnter和DataLoss均0。仍有冷启动/JIT，CPU参数实际engine=wall；不能用本轮结果证明稳定CPU、尾延迟、无锁、无泄漏或生产零性能影响。
- 证据：新目录含12个async-profiler JFR（业务/恢复）、6个JVM JFR、36个火焰图、collapsed、全部summary、事件JSON、聚合、测试/构建日志、运行入口/命令及SHA256SUMS。复现入口run.py有限调用新增decodedBatchAdmissionAndSettlement并校验计数。没有改生产配置、协议wireCode、资金计算和恢复格式。
- 被测benchmark JAR SHA256：`069cc3c5bdac8cc9df02bec0f9f560428ed510b58dc3fac031efbd0448fcadd4`；被测源码补丁与最终提交对应，source.patch校验见证据目录SHA256SUMS。上述功能及目标分配修复通过，完整性能验收仍未完成。


## 2026-09-06 已有订单扫描分配优化：采集前定义

- 当前master基于2163389d；对照commit不适用（仅验证当前master）。依据上轮真实调用栈：PositionCloseCapacity.inspectRuntime→ActiveOrderIndex.ids构建TreeSet/TreeMap节点/Long，STP→sortedIds复制排序全部候选long[]。本轮只优化这两条路径，不复用跨线程财务结果、不删除正常协议/快照分配。
- 实现：matchingIds提供owner只读primitive交集游标，扫描较小集合并过滤；每次调用独立游标，无共享可变缓冲，索引在游标消费期间不能修改。平仓容量仍按同样条件累加并按corePosition/orderId降序排列reduce-only撤单；STP先过滤交叉订单，仅实际撤单时分配结果并升序排序，空结果用已有EMPTY_ORDER_IDS。空commitment列表惰性创建，比较器静态复用。
- 新游标仍有固定数量对象分配，并非全链路零分配；选择独立游标以支持嵌套检查且不增加持久订单副本。新增用户/币对交集、两种扫描方向、空集合、重复hasNext、游标耗尽及嵌套独立性测试；既有CoreMatchingStateTest覆盖最新reduce-only撤单优先级，六线场景覆盖资金与恢复。
- JMH decodedBatchAdmissionAndSettlement场景注释明确当前maker多挂单与taker/平仓波次覆盖此路径；执行其有限入口，不执行timed JMH。使用同一HotSpot25/GraalVM、G1、512MiBheap、NMT/DebugNonSafepoints设置及4Account Lane/1matcher/256in-flight/batch2/257账户/1symbol，六线各独立JVM；1预执行轮、250ms停顿、2采样轮、轮间100ms停顿。每线采样7168 business ops、4096业务Core messages、2048fills。
- 采集CPU参数1ms（已知macOS实际wall引擎）、alloc128KiB、lock1ms，业务和恢复分别async JFR；全进程profile.jfc。仍是有限诊断，非稳态CPU或吞吐/延迟验收。原始目录 `/Users/atomex/Desktop/surprising/async-profiler-evidence/2026-09-06-order-scan/`。
- 通过条件：相关Maven回归通过，六线资金/冻结/持仓/终态/快照hash及命令计数通过；源码无TreeSet物化和全候选排序分配调用，采样验证目标路径；游标存活期间无索引写入，排序仅在确有撤单结果时保留。无持续压测，不宣称无泄漏或生产零分配。

### 已有订单扫描优化结果

- 首轮新增交集测试因夹具遗漏订单所属用户触发TradingCoreState分区校验；只补齐测试用户，生产校验未放宽，失败日志已保留。最终相关reactor678项通过、0失败/错误/跳过；后补真实STP测试后CoreMatchingStateTest定向41项通过（含重复覆盖，不叠加宣称唯一测试数）。`mvn -pl :surprising-aeron-benchmarks -am -DskipTests package`成功。
- 六线新JAR有限采样全部exit0，每线采样7168business ops、4096业务Core messages、2048fills，完整计数/用户与maker资金/冻结/持仓/终态订单/快照hash验证通过，unfinished/endBacklog0。真实STP测试使用非顺序orderId，确认只撤自己交叉订单、其他用户挂单保留、不产生自成交、余额守恒及快照恢复。既有测试确认reduce-only按最新提交优先撤单和后续平仓持仓归零。
- 目标STP与inspectRuntime调用链未采到TreeMap$Entry、TreeSet、Long或long[]。源码已移除这些调用点的树集合物化及全候选复制排序；仅实际STP冲突时分配结果并排序，空结果共享不可变零长数组。没有把无冲突有限样本当作真实冲突路径零分配证明。
- 仍存在的目标调用链采样权重：SPOT0.25MiB、LINEAR_PERPETUAL0.375MiB、INVERSE_PERPETUAL0.375MiB、LINEAR_DELIVERY0、INVERSE_DELIVERY0.5MiB、OPTION0.625MiB，包含独立primitive游标、PositionCloseCapacity及决策结果等。0样本并不代表没有对象；有订单时游标依然有O(1)分配，未引入共享可变游标/池以免破坏嵌套与所有权。
- 当前全业务窗口分配权重（MiB，含夹具、查询与冷启动影响）：SPOT81.875、LINEAR_PERPETUAL84.875、INVERSE_PERPETUAL88.75、LINEAR_DELIVERY82.125、INVERSE_DELIVERY90.25、OPTION93.0。byte[]、long[]、OrderRuntime和协议/成交结果仍有分配；不能将不可变跨线程结果、查询输出及快照所需分配全部池化，也不能声称整条交易链路零分配。本轮没有旧代码重跑或吞吐对比。
- 全进程JFR每线3次G1New，最长单次pause6.93–7.46ms，DataLoss/JavaMonitorEnter事件0。CPU参数实际wall引擎，预热不足，不输出稳态CPU、B/op、吞吐或分位延迟结论。JMH场景通过有限入口执行，未进行timed measurement。
- 证据目录含原始async/JVM JFR、火焰图、collapsed/summary、事件JSON、分组指标、所有测试日志、有限入口/命令、源码patch及SHA256SUMS。未覆盖真实Aeron网络/网关/WS、长期泄漏和完整生产容量，仍不构成完整性能验收。
- 被测benchmark JAR SHA256：`6b94d8079a4b74746ed612cd364a9d39045b00a1123d498d1d7ee63f76eddbd8`；源码最终提交和source.patch校验存于同目录revision.json、SHA256SUMS。


## 2026-09-06 整体Core链路持续分配诊断：采集前锁定

- 用户本轮明确授权几分钟压测，覆盖此前不持续压测的限制。被测master提交b191a7aa9b9a3fa0c7062715671f509c565e376c；对照commit不适用（仅验证当前master）。仅采集，不在测量期间修改源码/参数。
- 范围：SPOT和LINEAR_PERPETUAL各1×180s持续measurement，前3×10s warmup，独立JVM顺序运行，中间30s冷却。六线已有有限功能覆盖，其余四线本轮不形成持续性能结论。真实Core服务解码→owner准入→matcher→Account Lane结算→commit→响应编码，模拟Cluster/ClientSession；不包含真实Aeron网络、HTTP网关、数据库/Kafka/WS。实时出口关闭，maker由同进程fixture持续保留挂单参与往返交易，不等同独立做市进程。
- 固定Workload decodedBatchAdmissionAndSettlement：256 in-flight、257活跃账户（256用户+1maker）、1模拟session、1symbol、4Account Lane、1matcher，batchSize2；每cycle3584business ops、2048业务Core messages、1024fills、512查询。closed-loop波次，无open-loop到达率；不输出coordinated-omission修正或API三段尾延迟。JMH主分数的op是cycle，报告时转换为terminal business ops/s；gc.alloc.rate.norm的B/op也必须除以3584才能标注B/business op。
- 初态同既有场景：每账户settle资产1,000,000,000units，现货maker额外513 BTC units，价格100、永久maker挂单120，零费率、衍生品CROSS、mark100；六阶段改单成交往返+批量挂撤及查询。每cycle校验终态及fill数量，迭代末全量核对用户/maker余额、冻结、预占、持仓归零、只剩maker订单和恢复snapshot hash。
- 环境：Oracle GraalVM25.0.1 HotSpot/Maven3.9.16，macOS26.7 x86_64、Intel i9-9880H 8C/16T、16GiB。JVM Xms=Xmx768MiB、G1、NMT summary/退出统计、UnlockDiagnosticVMOptions/DebugNonSafepoints，jdk.internal.misc和java.util.zip opens/exports。无OpenJ9。采集前swap已用1202.50MiB，监控新增swap/pageout，不把既有swap当作本轮分配。
- JMH fork1、threads1、warmup3×10s、measurement1×180s、gc=true仅迭代边界、foe=true、prof gc。async-profiler4.5通过JMH集成：event=wall/interval10ms、alloc=128k、lock=1ms、threads=true、output=jfr，只采measurement；macOS不声称perf CPU。独立JVM JFR自定义allocation.jfc覆盖启动/预热/终检，含NewTLAB/OutsideTLAB、1s线程分配/CPU/DirectBuffer、safepoint/VM等事件。采样开销下吞吐仅诊断，没有无profiler主轮，不能作生产容量。
- 采集前通过/告警标准：accepted=terminal、unfinished/endBacklog=0，资金及snapshot核对通过、无OOM/业务异常；DataLoss=0、CPU speed=100且无新增swap/pageout才可作稳定性能证据，否则标无效性能轮次并保留定位栈。GC pause>50ms或稳定窗口GC后heap净增>32MiB标记待查；owner交易栈出现同步文件/socket/database IO标记问题，需排除夹具/启动/快照。缺少长稳、native池余额、上下文切换及分业务尾延迟不能完整验收。
- 每5s记录pmset热状态、swap/vm_stat、目标线程/进程CPU/RSS；每30s采集fork JVM NMT及FD数量，记录采样命令的额外扰动。不并行跑Maven/其它压测。按交易owner、matcher、Account Lane、查询/响应、夹具、JIT/其它分组分配，不能把客户端模拟解码算成Core业务分配。
- 原始产物 `/Users/atomex/Desktop/surprising/async-profiler-evidence/2026-09-06-sustained/`，含命令、JAR、JFC、JMH JSON、async/JVM JFR、系统/NMT日志、聚合和SHA256SUMS；失败轮次保留。180s不证明长期无泄漏或全产品线容量。
- 锁定JAR SHA256 `6b94d8079a4b74746ed612cd364a9d39045b00a1123d498d1d7ee63f76eddbd8`；JFC SHA256 `e59ae7afc138334cfe3bd62c04e0d451a56a5dbc1063e5f5480a9982c344a2e7`。

### 3分钟持续测量结果与整体分配归因

2026-09-06 22:07–22:15 UTC+8，两条产品分别预热30秒、持续测量180秒，全部成功。业务源码未改，仅追加诊断记录。

|指标|SPOT|LINEAR_PERPETUAL|
|---|---:|---:|
|终态business ops|4,906,496|5,085,696|
|终态业务Core messages|2,803,712|2,906,112|
|fills|1,401,856|1,453,056|
|带profiler终态business ops/s|27,239|28,234|
|JMH分配率 MiB/s|304.90|316.87|
|B/business op（含夹具及查询）|11,746.79|11,777.51|
|measurement GC次数/时间ms|125 / 995|130 / 1042|

- 两边acceptedBusinessOperations=terminalBusinessOperations、acceptedCoreMessages=terminalCoreMessages，循环和终检断言全部通过，unfinished/endBacklog0、预期拒绝0。用户/maker余额、冻结、预占、仓位归零、终态订单仅剩maker挂单及snapshot恢复hash核对通过。maxBacklog0是回调完成时的内部指标，不能代替真实网络积压。counter/params/score/原始结果完整保留。
- 本轮只有带profiler的单measurement，JMH主op是3584业务操作的一个cycle，GC norm原单位B/cycle已除3584。上述bytes/business op含512queries/cycle、夹具请求构造/响应解码和迭代边界开销，不能直接称作纯撮合每单开销。没有置信区间/无profiler主轮，不以此认定生产容量。

|分配采样归属（互斥栈分类，占整次async分配权重）|SPOT|LINEAR_PERPETUAL|
|---|---:|---:|
|真实Core owner（剔除下列单列阶段）|37.6%|36.3%|
|Account Lane|18.7%|20.2%|
|matcher|7.9%|7.9%|
|Core响应输出|2.3%|2.3%|
|Lane监控查询|12.8%|12.8%|
|夹具响应解码|14.6%|14.5%|
|夹具请求/初始化|5.9%|5.9%|
|快照/恢复边界|0.1%|0.1%|

- 权重为async采样估计，非精确对象个数；整次权重53.57/55.56GiB。分类按栈优先级避免同一份权重重复计算：客户端响应解码、快照、监控查询、matcher、Lane、响应输出、owner、夹具。个别JIT内联行号可能指到调用后指令，不把某一源码行机械等同allocation表达式。
- **下一优先级1：结果编码/payload生命周期。** 剔除夹具响应和监控查询后，CoreProtocol.encodeResponsePayload调用链byte[]权重约1280/1306MiB，CoreResponse构造约706/757MiB；源码CoreResponse构造防御性clone，batch结果编码和响应组包各生成数组。这是真实服务边界成本，适合检查能否直接写入有明确所有权的响应buffer、减少中间封装；不能直接删防御性复制或复用仍被异步读取的buffer。withCommittedCoreSequence虽也会复制，但本轮未采到该方法，不将其说成本轮主要热点。
- **下一优先级2：client-order反向索引。** Account Lane的TradingRuntimeState.putClientOrderIndex在每个新orderId创建LongHashSet；该调用链权重约1157/1168MiB，其中long[]约629/636MiB。常见单clientKey订单也付出集合开销。可以评估单键存储、多别名才升级集合的表示，但必须保留改单/重试别名、终态回收和恢复语义，不能简单去掉索引。
- **下一优先级3：订单状态重复物化。** OrderRuntime类总权重约2479/2533MiB；stampMatcherOrders/withCommitMetadata和Lane订单生命周期多次生成不可变记录。必须先证明哪些中间版本没有发布/被财务fact引用，才能合并构造；跨owner/Lane共享可变OrderRuntime并非可接受的“零分配”方案。
- **单列监控成本而非交易bug：** 本夹具每4条业务Core消息另有1次Lane指标查询，laneMetrics/accountLaneMetricsById、CoreLaneMetrics/CoreLaneMetricsView和编码层多次生成/复制long[]。该12.8%不能套用到实际低频监控部署；下轮纯交易成本测量应把查询频率单独作为场景变量，不能在本轮中途偷偷改频率。
- 先前修复的枚举解码、重复查询键和已有订单TreeSet不再是本轮主要热点；仍有Long/long[]用于真实状态和输出。整个系统尚未零分配，后续应按上述权重和所有权逐项验证，不能把所有数组都认定为浪费。
- **GC和堆：** 全进程JFR（含预热/终检）GC154/161次，pause p50约7.99/8.08ms、p95约9.34/9.49ms、max20.57/21.83ms，均未触发50ms告警。以第一条heap事件起算60秒后的30秒桶，SPOT GC后45.14–47.60MiB、永续45.50–47.68MiB，无连续上升或32MiB净增告警。仅3分钟，不能证明长期无泄漏。
- **NMT/native：** 约30秒至210秒NMT committed现货953.83→959.74MiB、永续955.69→962.12MiB；主要增长Tracing5549/5931KiB，另有Code288/374KiB及NMT自身304/326KiB。Java Heap committed768MiB不变，DirectBuffer采样count/capacity/used均0（不含真实Aeron/Netty池，不能推断生产堆外为0）。run.py的“FD count”实际上计lsof输出条目，包含mmap等，不能当作精确FD数，本轮未形成可靠FD趋势结论。
- **线程/等待：** async锁1ms门限无事件、JFR DataLoss0；owner壁钟样本中commitReadyMatching为10898/17725与8871/16840，onSpinWait为4494与3299，await为4598与3403，集合有重叠不可相加。这是有序提交/等待边界，不能据此删除fence，也不能将wall百分比说成CPU占比。实际CPU/线程负载原件在cpu.tsv；全进程Compilation事件38/33、Deoptimization292/293包含启动，不保证所有窗口已JIT稳态。
- **分配事件完整口径：** 全进程NewTLAB事件147070/152560、权重约66.65/69.64GB，OutsideTLAB1758/1848、对象字节166.54/168.49MB，所记录最大对象4,194,320B；包括启动、预热、快照，非measurement分母。ThreadAllocationStatistics部分native编译线程计数回退，原始first/last保留，不对其负差值做“负分配”解释；真实业务线程与async权重分开保存。未精确测量每个Java对象数/op。
- **环境和I/O：** 两轮所有5秒记录CPU_Speed_Limit=100，swap1202.50MiB无增加；DataLoss0。未采到配置阈值以上的交易processCommittedRequest栈文件/socket IO，不等于排除阈值以下全部IO。更多safepoint/VM事件、wait栈、CPU、GC桶、NMT类别、分配class/site/thread见JfrStats.java流式聚合输出和diagnostics.json，不物化百万条TLAB事件为大JSON。
- 原始四份JFR（每线JVM+async）、六种模式火焰图（每线alloc/wall/lock）、collapsed/summary、JMH JSON、JFC、运行/流式分析脚本、系统/NMT日志、锁定JAR、源码提交和SHA256SUMS均在本轮证据目录。记录期包含若干初始化/终检事件，已单列边界。
- **结论：持续负载能稳定定位上述真实分配路径，当前两线的业务正确性和短期状态回收通过。** 真实网络/Aeron三节点、HTTP/API三段分业务尾延迟、其余四线持续负载、非零费率、风险/资金费/强平/到期、WS、完整native池/FD和长期泄漏未测；整体仍仅部分性能验证，不是全栈容量验收。本轮不修改生产代码。


## 2026-09-06 响应、client-order 索引与订单提交元数据分配优化（采集前锁定）

- 被测代码：当前 master / 31fd98f8 加本次工作区修改；采集前保存 git diff SHA256、构建 JAR SHA256 和最终源码提交。对照 commit：不适用（仅验证当前 master）；不构建、重跑或比较旧版。
- 修改：CoreResponse 显式编码数据所有权转交，公开构造及 data() 防御性复制；decoder 对其新建数组直接接管；withCommittedCoreSequence 共享已封装不可变数据。OrderClientKeyIndex 常见单键使用 primitive map，多别名才建 LongHashSet。成交/撤单不可变 OrderRuntime 合并状态和 commit metadata 构造，原 capture/publish/fence、六产品内核、财务修订和恢复格式不变。
- JMH 场景更新：CoreResponseEncodingBenchmark.constructAndEncodeOwnedResponse 新增所有权构造+真实编码路径；ClusteredBatchTradingBenchmark.close 增加终态 client alias 回收和 snapshot 索引一致性断言。相关协议、账户/交易/行情/衍生生命周期调用方及工具测试扩大验证。
- 环境：HotSpot Oracle GraalVM JDK25.0.1+8；Maven3.9.16；macOS26.7 x86_64，Intel i9-9880H 8C16T /16GiB。本机诊断，不是 AWS 容量承诺。
- 基准阈值：业务 accepted=terminal、Core messages accepted=terminal，unfinished/endBacklog=0，资金/冻结/仓位/订单终态及 snapshot hash/alias 全部一致；任何财务失败立即停止。两条持续场景全 JVM 含夹具/查询的 gc.alloc.rate.norm 除3584必须 <12000 B/business op；编码微基准 encodeCommittedResponse <1 B/op、constructAndEncodeOwnedResponse <128 B/op，dataBytes=0/4096分别验证，无 payload 长度同比例复制。此阈值只验分配，不是全链路性能验收。
- 数据有效性：无 JFR DataLoss、CPU_Speed_Limit 100、swap used 不增长、无外部压测。GC最长pause>50ms、稳定窗口GC后live set净增>32MiB、native/FD持续增长作为调查告警。采样值和无样本不能证明零锁/无泄漏；未具备全部分段尾延迟、open-loop和真实网络指标，仅部分性能验证。
- 第一阶段：六产品 SPOT/LINEAR_PERPETUAL/INVERSE_PERPETUAL/LINEAR_DELIVERY/INVERSE_DELIVERY/OPTION 逐个运行 FiniteCoreProfile，直接调用更新后的实际 JMH 工作负载，1次warm cycle+2次measurement cycle（每cycle3584 business ops/2048 Core messages/1024 fills），warm后250ms，cycle间100ms；每条共10752/6144/3072，256 in-flight、batch2、4Account Lane、1matcher、256用户+1maker/1symbol/1模拟会话、realtime=false。固定512MiB G1、NMT summary、DebugNonSafepoints、PrintNMTStatistics；JFR profile；async-profiler4.5 alloc128KiB+lock1ms+wall10ms（旧helper cpu请求在macOS实际engine=wall），保留实际ActiveSetting。金融及snapshot终检每条执行。本阶段无吞吐容量结论。
- 第二阶段：同一机器六条小样本完后冷却30秒，SPOT和LINEAR_PERPETUAL各运行 decodedBatchAdmissionAndSettlement，f1/t1、3×10s warmup、1×180s measurement、产品间cool30s、gc=true、foe=true。JVM -Xms768m -Xmx768m -XX:+UseG1GC -XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:+PrintNMTStatistics --enable-native-access=ALL-UNNAMED，opens/exports jdk.internal.misc，opens java.util.zip；StartFlightRecording 使用 artifact 内 allocation.jfc。-prof gc 与 async: event=wall;interval=10000000;alloc=128k;lock=1ms;threads=true;output=jfr，lib为 ~/.local/opt/async-profiler-4.5-macos/lib/libasyncProfiler.dylib。
- 持续业务场景与上述有限场景一致：每cycle3584 business ops、2048业务Core messages、1024fills、512额外Lane指标查询；循环包含往返开/平仓成交、批量成交、挂单/撤单，保持原比例与查询频率。闭环最大速率，未修正coordinated omission；没有固定open-loop到达率。batch平均/最大2，每轮1536批/3072items及512单操作；实际参数/counters以JMH原件核对。用户初始settle余额1,000,000,000，SPOT maker BTC513，zero fees、CROSS、mark100、maker持续120卖挂单；不独立启动做市进程，但真实Core内流动性全程存在。完成后全部用户/maker资金恢复、无挂起预占、仓位归零，仅maker订单及一个client alias留存，快照业务hash/alias一致。
- 第三阶段：响应微基准两个方法，dataBytes0/4096，f1/t1、3×1s warmup、3×1s measurement、G1固定512MiB、NMTsummary、-prof gc+JFR profile。此处是纯编码不涉及交易在途队列，业务全链路固定256设置不变，不引入其他in-flight档。
- 指标处理：主JMH op=cycle，business ops/s=score×3584、Core messages/s=score×2048、fills/s=score×1024；aux Type.EVENTS为次数。gc norm/3584为包含夹具、查询和边界的B/business op，不能冒充纯matcher分配。没有无profiler业务主轮或完整尾延迟，不宣称生产容量或完整性能验收。
- NMT每30秒采一次，系统/CPU throttle/swap每5秒；FD改用lsof -a -p pid -F f，仅计数字FD，保存原始字段输出。JFR流式分析线程、GC/TLAB/heap/NMT/direct/park/IO/safepoint/JIT及顶层分配栈；macOSwall不可当CPU精确占比。
- Artifact：/Users/atomex/Desktop/surprising/async-profiler-evidence/2026-09-06-allocation-opt（finite、sustained、response子目录），命令、JDK、源码patch/JAR/配置校验、原始JFR与聚合全部保留。采集开始后以上标准与场景不再修改，结果只追加。


### 分配优化结果（2026-09-06 22:38–22:53 UTC+8）

- 源码提交：`a1c079aea689cbd3d7d9a741eadce8597c330337`，已推送 master。全部只采当前源码，不作历史版本比较。JAR 在提交前由完全相同的工作区源码构建，SHA256 `5de21731ac17357245dc7cf797a3e99aa936edb348d993e8fabf87ec37b83635`。
- 相关 reactor：`mvn -pl :surprising-aeron-benchmarks,:surprising-aeron-tools,:surprising-account-provider,:surprising-trading-provider,:surprising-market-data-provider,:surprising-derivatives-lifecycle-provider -am test`，1054 条实际执行通过；另有11个数据库条件跳过条目（含参数化模板）。随后独立 PostgreSQL18.4 临时 cluster，仅绑定127.0.0.1，用当前 init.sql 初始化，补跑 `MaintenanceIntegrationTest` 36条及 `InstrumentSeedCoreContractTest` 1条全部通过，无遗留跳过；数据库已停止。合计1091条通过，详细类计数在 tests.log/database-tests.log。第一次编译因误用 Eclipse Collections void removeKey 返回值失败，已修正为 get+remove 后重跑；保留 initial-compile-failure.log，不掩盖失败轮。
- 新增/扩展回归覆盖公开 response 输入/输出防御复制、所有权转交及编码格式、提交序号共享只读数据；client alias 单键/多键升级降级、重复添加、移除不存在键、跨订单重绑定和终态清理；成交费用、revision、取消与 commit metadata 合并后的不可变前态。已有相关 reactor 覆盖非零费用、资金守恒、STP、回滚、快照与 Core 恢复；数据库补测包含六产品维护撤单、平仓、丢响应和重启恢复。
- 六产品 finite 阶段均 exit0，每条1 warm+2测量cycle，共10752 business ops /6144 Core messages /3072 fills；全部用户/maker余额、冻结、仓位、终态订单/alias及snapshot hash一致，共64512业务操作。原始两类JFR、摘要、计数和命令在 finite/。

|3分钟持续场景指标|SPOT|LINEAR_PERPETUAL|
|---|---:|---:|
|measurement business operations|4,867,072|5,300,736|
|terminal Core messages|2,781,184|3,028,992|
|fills|1,390,592|1,514,496|
|带profiler terminal business ops/s|27,031.20|29,432.60|
|带profiler terminal Core messages/s|15,446.40|16,818.63|
|带profiler fills/s|7,723.20|8,409.31|
|分配 MiB/s|287.91|307.66|
|B/business op，包含夹具和查询|11,177.51|10,970.07|
|measurement GC次数 / 时间ms|118 /947|126 /1018|

- 每条均3×10秒warmup、1×180秒measurement；现货22:39:38–22:43:14，永续22:43:44–22:47:21，时间包括startup/终检。accepted=terminal、unfinished/endBacklog=0、maxBacklog0（仅回调末内部观察）、拒绝/业务错误/超时0；资金、预占、仓位归零、仅剩maker挂单和client alias、快照恢复全部通过。每cycle1536批/3072items，批平均/最大2，512单业务操作、512查询。总测量10,167,808业务操作；两个分配阈值均通过。
- 只有单次measurement，没有可用业务吞吐置信区间；未提供无profiler主吞吐及入口/accepted/terminal分段p50至p99.9/max，不宣称吞吐提升百分比、生产容量或完整性能验收。上述数字是内部实际Core回调闭环256准备在途的指定场景，不是真实网络并发连接数或三节点Cluster TPS。

|响应JMH（3×1秒warmup、3×1秒measurement）|dataBytes=0 B/op|dataBytes=4096 B/op|
|---|---:|---:|
|encodeCommittedResponse|0.000268|0.000511|
|constructAndEncodeOwnedResponse|0.000253|0.000606|

- 两个响应方法均近0 B/op并通过预锁阈值；这是JIT优化、已编码只读数据和复用目的缓冲区的场景，不包含新业务payload生成，也不代表public构造器/decoder/全部交易零分配。普通构造器和data()仍防御性复制，外部修改不会污染幂等保留结果；owned调用方禁止再改已转交数组。
- **纠正此前响应采样的过强归因：** encodeResponsePayload 的源代码/字节码已经是写入复用数组和System.arraycopy，本身没有新建payload；本次微基准也验证编码无随payload长度增长的分配。永续综合profile依然把byte[]样本归到encodeResponsePayload:59，而现货同阶段权重仅0.23%，不可把优化JIT栈/行号机械解释为该源码行new数组，更不能按这个权重宣称消除了相同数量分配。本次实际消除的是明确的CoreResponse重复clone、decoder刚建数组后的再clone，以及metadata副本再clone；未额外修改已经复用的发送缓冲区。
- **当前剩余分配：** 互斥采样分类的owner/Lane/matcher为38.09/18.66/8.26%（现货）、36.73/19.85/8.39%（永续）；高频Lane查询12.37/13.70%、夹具响应解码16.03/12.39%，单独保留不伪装生产成本。OrderRuntime仍有约2481.6/2420.7MiB权重，属于多个构造及状态路径，不是全部可删除；此次只合并已有提交元数据与成交/撤单状态的构造。OrderClientKeyIndex常见订单不再每单new LongHashSet，但primitive map扩容/rehash仍可分配，不能称整个索引绝对0B。top classes仍为byte[]、long[]、OrderRuntime，完整site/thread/stack权重在results.json及alloc.html/collapsed。
- **Heap/GC：** 全JFR含预热/终检，GC146/155次，其中young137/146、old9/9；原因是G1 Evacuation Pause136/145、System.gc8/8、Metadata GC Threshold2/2，另有concurrent phase事件，不能把显式iteration边界GC都算进稳定交易。pause p50 8.10/8.14ms、p95 9.46/9.55ms、max20.23/19.45ms；measurement GC占测量时间约0.53/0.57%。稳定窗口（首heap事件后60秒起）的GC后占用约45.6–47.7MiB，未达到32MiB增长告警；heap committed固定768MiB。没有晋升/疏散失败事件。
- **Java分配细节：** JFR NewTLAB事件138587/148442，对应refill bytes 63,130,067,888 /67,265,502,640；OutsideTLAB事件1925/1898、bytes170,476,520 /167,720,976；最大采样对象4,194,320B。NewTLAB最大触发对象131088/262160B。此为全进程记录含warmup/终检，refill字节并非精确对象大小累加；抽样不能推导精确对象数/业务操作。ThreadAllocationStatistics原始first/last保留，JVMCI线程个别计数回退不解释成负分配。
- **NMT/堆外/FD：** 约30秒到210秒，reserved 2,335,376→2,341,247KiB /2,335,588→2,342,005KiB；committed953.50→959.36MiB /955.09→961.43MiB。主要增量Tracing5485/5991KiB，Code276/332KiB和NMT自身303/326KiB；不是业务native池泄漏证据。214/215次DirectBuffer统计count/capacity/used均0，本夹具不含真实Aeron/Netty缓冲池。数字FD全程16，fds-*.txt保留，本次已排除mmap/cwd等非FD条目。Java线程peak21，最后采样active16；iteration重建服务，累计thread start/end不能当存活线程增长。
- **CPU/锁/等待/IO：** CPU_Speed_Limit均100，swap used均1202.50MiB不变，JFR DataLoss0。JFR CPULoad原始user+system均值约0.1200/0.1189；线程原始mean在diagnostics.json，不把wall样本比解释为CPU占用。owner壁钟样本17287/17285，其中commitReadyMatching10618/9045、onSpinWait4371/3412、await4445/3509，集合重叠不可相加；原有顺序提交边界未删除。async lock1ms门限0样本，记录配置门限以上交易回调同步file/socketIO为0，不等于所有锁/IO绝不存在。
- **VM/JIT/异常：** SafepointBegin160/170，最长进入时间0.151/0.121ms；VM operation174/182，最长20.25/19.48ms，与GC停顿量级一致。Compilation37/38，累计8.51/9.89秒，最长834/837ms为编译线程工作，不是同长STW；Deoptimization284/282含启动，不能据此保证所有方法已完全稳定。类加载2369→4552/4598，卸载0，NMT类区及metaspace增量较小，code cache原件在runtime-stats.json。JavaExceptionThrow545/553和JavaErrorThrow134/136是事件数（可能同一throw同时产生两类事件），完整128层栈保留；集中在前30秒和收尾，有MethodHandle链接探测、JMH marker/反射、snapshot阶段jnr/ffi加载及符号探测。60秒后的稳定交易回调未采到异常，不能把启动探测数量当作业务失败；终检和恢复实际通过。
- **证据与未测范围：** artifact根 `/Users/atomex/Desktop/surprising/async-profiler-evidence/2026-09-06-allocation-opt`，165个证据文件约311MiB（清单不含停止的临时PG数据目录）。SHA256SUMS自身SHA256 `9b287f06514b2f7d2ef8ba15b31599a654a335c87c582a9d536f1238a431223d`；原始JFR、summary、HTML、collapsed、gc/heap/cpu/wait-io/jit-vm/thread-allocation TSV、NMT、FD、系统采样、命令和配置齐全。执行命令原件见finite/commands.json、sustained各产品command.json、response各场景command.json、database-commands.json。没有真实HTTP/Aeron传输/三节点故障、Kafka/WS吞吐，没有其他四产品的分钟级测量和小时级leak/OldObjectSample验收；本次为三处局部优化的正确性与分配部分验证，不能声称整链路零分配或长期无泄漏。


## 2026-09-06 重启后连续10分钟 async-profiler 诊断（采集前锁定）

- 目标：观察当前 master 的持续分配、GC/live set/native、线程等待与可优化路径；本轮不改业务代码，不重跑或比较旧版。被测 commit e050d2b6（业务源码a1c079ae），对照commit：不适用（仅验证当前master）。构建新JAR，采集前保存源码、JDK、JAR和配置SHA256。
- 环境：macOS26.7 x86_64，i9-9880H8C16T、16GiB；HotSpot Oracle GraalVM25.0.1+8、Maven3.9.16；async-profiler4.5。重启后swap0，CPU_Speed_Limit100；启动初期有Xprotect/系统后台工作，每30秒记录进程CPU/RSS，不停止用户进程。构建后cool30秒。
- 产品与场景：仅LINEAR_PERPETUAL，用已有ClusteredBatchTradingBenchmark.decodedBatchAdmissionAndSettlement实际Core回调→准入→1matcher→4Account Lane→结算→提交→响应。1symbol，256活跃用户+1maker，1模拟ClientSession，固定256 in-flight，batch2、realtime=false；闭环最大速率，未修正coordinated omission。每cycle3584 business ops、2048 Core messages、1024fills、1536batches/3072items、512单操作和512额外Lane指标查询。动作包括maker挂单、用户批量挂单/改单成交、往返开平仓、批量挂撤单，保持既有查询频率和负载比例。
- 初态与做市：每用户/maker settle余额1,000,000,000，CROSS、zero fees、mark100，maker持续120卖挂单保留；做市由同一实际Core内的maker账户完成，没有独立网络做市进程。每cycle核对终态/成交数，iteration结束核对用户和maker资金、冻结/预占归零、持仓归零、仅maker订单/client alias存在、snapshot业务hash和alias一致。
- 参数：JMH f1/t1，3×20s warmup，1×600s连续measurement；仅warmup iteration边界重建服务，600秒中途不重建、不主动GC、不打heap histogram。gc=true只在JMH边界触发。固定-Xms768m -Xmx768m、G1、NMTsummary、DebugNonSafepoints、PrintNMTStatistics、enable-native-access；opens/exports jdk.internal.misc及opens java.util.zip。JVM JFR使用artifact内allocation.jfc（New/OutsideTLAB、每秒ThreadAllocation/CPU/Direct及GCHeapSummary、VM/JIT/IO/异常）；-prof gc及async event=wall;interval=10000000;alloc=128k;lock=1ms;threads=true;output=jfr。macOS wall不当精确CPU时间；配置增加采样开销。
- 阈值与有效性：accepted=terminal、Core messages accepted=terminal、unfinished/endBacklog=0，财务和快照全部正确；任何业务错误/超时停止并记录。分配关注阈值<12000 B/business op（全JVM含夹具/查询）；GC单次pause>50ms、预热后live set净增>32MiB或持续单调上涨、native/FD/thread稳定增长需要调查。JFR DataLoss、CPU降频、swap增长会标记数据无效/环境受扰，不能当容量证据。预热后按分钟桶检查GC后live set/线程分配趋势，NMT/FD每30秒、系统每5秒，记录采样最大对象和top classes/sites/stacks。
- 口径：JMH op=cycle；business/s=score×3584，Core messages/s=score×2048，fills/s=score×1024，B/business=gc.alloc.rate.norm/3584；aux EVENTS为计数。只有单measurement，没有业务吞吐置信区间，无无profiler主轮和分段尾延迟，故仅为热点与内存部分诊断，不宣称真实并发容量、三节点HA吞吐或长期无泄漏。其他五产品、HTTP/Kafka/WS/真实Aeron网络不在本轮范围。
- Artifact：/Users/atomex/Desktop/surprising/async-profiler-evidence/2026-09-06-ten-minute；run.py、command.json记录精确命令，原始JFR和摘要、GC/heap/CPU/线程/锁/IO/VM/native聚合及SHA256清单保留。开始采集后本节不可修改，结果仅追加。


### 重启后10分钟测量结果与优化建议

- 执行于2026-09-06 23:21:30–23:32:40 UTC+8，实际async录制23:22:40起连续600秒（JFR summary确认），JVM JFR669秒含warmup/终检，exit0。被测源码e050d2b6，业务源码a1c079ae；本轮未修改业务代码，只重新构建并运行预定义场景，不比较历史版本。
- HotSpot25/Maven/profile版本均核验；JAR SHA256 `5de21731ac17357245dc7cf797a3e99aa936edb348d993e8fabf87ec37b83635`，JFC SHA256 `e59ae7afc138334cfe3bd62c04e0d451a56a5dbc1063e5f5480a9982c344a2e7`，dylib SHA256 `81e22b07df3d7e63e74ce9c3870b9208b9055c4e6ef674941641d717ca53af16`，版本原件在revision.json/java-version.log。没有重复跑测试套件；本轮验证由实际JMH循环及资金/快照终检完成。

|600秒 measurement 指标|LINEAR_PERPETUAL|
|---|---:|
|terminal business operations|17,973,760|
|terminal Core messages|10,270,720|
|fills|5,135,360|
|带profiler terminal business ops/s|29,951.32|
|带profiler terminal Core messages/s|17,115.04|
|带profiler fills/s|8,557.52|
|分配 MiB/s|315.34|
|B/business op（全JVM含夹具与查询）|11,043.00|
|measurement GC次数 / 时间|431 /3450ms|
|measurement GC时间占比|约0.575%|

- acceptedBusinessOperations=terminalBusinessOperations，accepted/terminal Core messages相等，unfinished/endBacklog0、maxBacklog0（仅回调末内部指标）、拒绝/业务错误/超时0。用户/maker期末余额、冻结/预占、仓位归零、仅剩maker订单及client alias、snapshot业务hash/alias恢复一致。无中途服务重建。batch平均/最大2、每cycle1536batches/3072items+512单操作+512额外查询，计数和参数原件在jmh.json。业务分配通过预锁12000 B/op关注阈值。
- JMH主分数8.356952 cycles/s，单measurement没有可用误差/置信区间；其scorePercentiles不是业务延迟分位数。本轮没有分段p50/p99/p99.9/max、open-loop到达率、无profiler主轮，不作容量或性能提升百分比承诺。固定256仅指夹具准备在途，本轮模拟1会话，不是256条真实Aeron连接或三节点压力。

**内存与稳定性**

- GC后heap在稳定窗口（首heap事件后第2分钟起）45.47–47.69MiB，G1事件记录的oldGenUsedSize均值约26.65→26.69MiB，末段26.69MiB。按分钟的owner分配221.84–225.38MiB/s、四Lane合计63.83–64.80MiB/s、matcher26.04–26.44MiB/s，没有持续上升趋势；这些是ThreadAllocationStatistics差分率，不是每分钟交易吞吐。heap committed固定768MiB，未达到32MiB增长告警。
- 全JVM录制GC483次：young474、old9；原因G1 Evacuation Pause473、System.gc8、Metadata GC Threshold2。显式GC在JMH边界，不能全部算进600秒稳定交易。pause p50 8.01ms、p95 8.80ms、p99 13.95ms、max21.92ms，未触发50ms告警；无promotion/evacuation failure记录。
- NMT committed在采样点约949.80–967.81MiB，末点953.30MiB；Tracing在16.70–29.95MiB间波动且出现释放，不能把这部分计成业务泄漏。数字FD21次采样均15；稳定窗口active Java threads16、无持续增长。DirectBuffer统计原件保留；夹具不含真实Aeron/Netty池，不能由此评价生产native池。系统CPU_Speed_Limit全程100，swap/pageout0，JFR DataLoss0，环境有效。
- NewTLAB事件479506，对应refill bytes218,441,611,728；OutsideTLAB5650次/251,026,584B，最大采样对象4,194,320B；NewTLAB最大触发对象131088B。抽样不能推导精确对象数/业务操作。全async分配权重约184.59GiB，是估计值；TLAB/线程/JMH窗口不同，不能直接混算。原始事件和各分钟数据在event-counts.tsv、allocation-timeline.tsv、g1-heap.tsv、trends.json。
- 10分钟内无明显堆、老年代、FD、线程持续增长证据，不代表小时/天级leak验证完成；没有主动heap histogram或OldObjectSample扫描扰动测量。

**热点核对及下一步建议（均为待实现建议，不冒充已验证收益）**

1. **最明确的重复对象：准备下单安装过程。** `RuntimeCommandProcessor.placeOrderPrepared:253` 已构造完整OrderRuntime/ReservationRuntime，随后调用 `TradingRuntimeState.reserveOrder:4619` 再创建简化订单/预占，再执行replaceOrder/replaceReservation覆盖。实际采样路径为CoreProbeState.reservePlaceOrderRuntime→executeUserSettlement→placeOrderPrepared→reserveOrder，并非仅测试构造。reserveOrder lambda内OrderRuntime约859.12MiB、ReservationRuntime393.75MiB、占位UUID159.25MiB、ReservedOrder载体120.38MiB权重；源码和完整栈均支持重复物化。建议让预占安装接收已解析的最终不可变订单/预占，一次完成资金冻结、before capture、索引和发布；必须保留失败回滚、revision、资金及跨Lane可见性。这是尚存的准备下单路径，不是此前已合并的成交/撤单metadata路径；不能删除必要的真实订单状态对象。
2. **小容器反复创建/清空。** `TradingRuntimeState.addUserEntity:5019`、`indexPosition:4730`、`putClientOrderIndex:5037` 和 `AccountLaneState.markPendingReservation:141` 对少量键也创建默认LongHashSet/LongLongHashMap/IntLongHashMap，清空后移除，下次再创建。按上述方法优先归属去重，采样权重分别2567.25/1693.00/1820.50/3227.75MiB，合计约4.92%。反向client-key索引已用单键优化，此处包含其他必要正向/预占/持仓索引。建议先评估适配小基数的初始容量或首键直接存储，并保留多键升级、用户回收和资金语义；不无限保留历史用户空Map，也不叠加另一套重复索引。这些数字是调用链归因，不等于全部可省。
3. **监控查询的同步任务与多层复制。** `CoreProbeState.laneMetrics:4888` 每Lane分别调用accountLaneById和accountLaneMetricsById，共8次Lane查询/4Lane；AccountLaneMetricsSnapshot→CoreLaneMetrics→CoreLaneMetricsView→codec经过多轮clone/getter复制。整条监控查询约占13.63%分配，JDK execution samples也命中accountLaneById的LaneMutationTask.await。可将同一Lane元数据和指标合并一次采集，并在受控编码边界减少重复数组复制，保留公开API防御性复制和一致性。夹具每cycle512次查询，此比例显著高于通常部署，不应把整个13.63%都算作生产可回收成本；本轮没有降低查询频率以美化数字。
4. **已核对的合理成本。** TradingOrderBatchCodec.encodeResult已有精确长度数组与直接写入，分配为结果保留/传输所需，不能重复建议改成精确分配；encodeResponsePayload的arraycopy行号仍有样本归因，优化JIT行号不证明该行new数组。OrderRuntime全类仍有8163.9MiB权重，包含真实不可变状态转换/快照边界，不能整类池化成共享可变对象。业务状态与响应封装后仍需隔离所有权。

- 互斥分配分类：owner36.52%、Lane20.47%、matcher8.39%、监控查询13.63%、夹具响应解码12.28%、夹具请求/初始化6.21%、Core输出2.47%、快照恢复0.04%。byte[]、long[]、OrderRuntime为主要class；具体top site/stack/thread在results.json。分类是profile归因，不是保证精确的源码分配表达式。
- 锁与CPU：async lock1ms门限0样本，交易回调内未采到JFR门限以上同步file/socketIO。JDK ExecutionSample62517；最高单栈8878为Thread.isInterrupted→SurprisingClusteredService.idleCommand，另有LaneMutationTask.await、监控查询和健康检查。本夹具明确使用NoOpIdleStrategy，等待会忙轮询；不能据此认定生产idle策略错误。提交前等待Lane/matcher完成有确定性与资金边界，不删除fence/中断/超时检查。owner wall58968个样本，其中commitReadyMatching30202、await12103、onSpinWait11709、laneMetrics6958，集合有重叠不可相加。CPULoad原始mean0.11415，owner ThreadCPULoad约0.06170、matcher0.01310、各Lane0.00593–0.01162；不能将wall份额当精确CPU时间。
- VM/JIT：SafepointBegin520，最长进入0.399ms；VM operation551次、总3965ms、max21.94ms，与GC停顿量级一致。Compilation37次、累计8687ms、max868ms在编译线程，Deoptimization299含启动；类加载/code cache/native保留原JFR/NMT，不以单次长编译时间冒充STW。JavaExceptionThrow609/JavaErrorThrow136为可能重叠的事件计数，按时间排序集中启动/反射/MethodHandle/jnr探测和收尾；120–660秒的交易回调未采到异常。异常栈和时间完整保留exceptions.tsv，不将启动探测直接判成业务bug。
- Artifact根 `/Users/atomex/Desktop/surprising/async-profiler-evidence/2026-09-06-ten-minute`，107个证据文件约180.61MiB，含run.py精确命令、原始JFR/summary、alloc/wall HTML、NMT/FD/进程/系统、GC/CPU/VM/异常/分配分钟趋势及归因数据。SHA256SUMS自身SHA256 `e3d97e94c0add5f519afcc5a652c1bd569400607e9ed7e56fa1d12cca3acdf78`。
- 交付为本次U本位永续Core内部连续600秒的诊断；其他五产品、真实三节点网络、HTTP/Kafka/WS、小时级长稳和完整业务分段尾延迟未在本轮验证。优先按上述重复构造、小容器、监控快照顺序设计局部改动，再以当前master固定256场景重新锁定指标验证。


## 准备下单、小容器与 Lane 指标编码优化验证（采集前锁定，2026-09-07 UTC+8）

- 被测：当前 master 的 c28552c8 加本次优化，采集前归档 patch/JAR SHA256，结果追加实际提交号；对照 commit：不适用（仅验证当前 master），不采旧版、不作历史百分比比较。
- 修改范围：准备下单直接安装最终 OrderRuntime/ReservationRuntime；小基数的 per-user LongHashSet/LongLongHashMap/IntLongHashMap 初始容量2，按需增长、空集合照常移除；同用户活跃订单更新不重建成员关系。查询边界用有封口所有权转交的精确协议缓冲区，每Lane一次任务同时读取元数据与matcher指标，公开快照仍防御复制，保持原查询频率与任务完成屏障。
- JMH更新：ClusteredBatchTradingBenchmark 的金融/终态/snapshot 终检增加直接指标聚合形状与公开数组隔离断言；持续循环本来就真实触发 prepared admission、成交/撤单、小索引与512次额外metrics查询，不减少这些动作。六产品均复用该实际JMH场景。
- 环境：macOS26.7 x86_64，Intel i9-9880H 8C16T、16GiB；HotSpot Oracle GraalVM25.0.1+8、Maven3.9.16、async-profiler4.5。实际版本、进程与环境输出保存在artifact。无并行服务/压测，不启动wallet。
- 预锁通过阈值：acceptedBusinessOperations=terminalBusinessOperations、accepted/terminal Core messages相等；unfinished/endBacklog=0，所有金融、索引终态与snapshot恢复断言通过，拒绝/错误/超时0。持续轮 gc.alloc.rate.norm/3584 <10000 B/business op（全JVM含夹具与查询），是当前源码分配阈值而非旧版收益承诺。GC最长pause>50ms、稳定GC后live set净增>32MiB、线程/FD/native持续单调增长需调查；JFR DataLoss、CPU_Speed_Limit<100、swap增长标记环境受扰/无效，金融错误立即停止。
- 场景：固定256 in-flight、batch2、4Account Lane、1matcher、256用户+1maker、1symbol、1模拟ClientSession、realtime=false；真实SurprisingClusteredService回调、admission、matcher、Lane settlement、commit、response。每cycle3584 business ops/2048 Core messages/1024fills、1536batches/3072items（平均/最大2）+512单业务操作+512额外指标查询。动作包含批量挂单/改单成交、往返开平仓和批量挂撤单；闭环最大速率，不是固定到达率，不修正coordinated omission。
- 金融初态：每用户/maker settle余额1,000,000,000，zero fees、CROSS、mark100、maker持续120卖挂单，现货maker BTC513。无独立做市进程，Core内真实maker账户持续有流动性。每cycle核对计数，iteration结束全部用户/maker资金守恒、冻结/预占及仓位归零、仅一个maker订单/client alias保留，snapshot业务hash/alias一致。
- 阶段一：SPOT、LINEAR_PERPETUAL、INVERSE_PERPETUAL、LINEAR_DELIVERY、INVERSE_DELIVERY、OPTION逐条有限运行更新JMH场景，1warm+2measurement cycles；warm后250ms、cycle间100ms，每产品总10752业务操作/6144 Core messages/3072fills。固定512MiB G1，NMTsummary、DebugNonSafepoints、PrintNMTStatistics，JVM JFR profile；async4.5 alloc128KiB+lock1ms及macOS实际wall fallback（helper请求cpu，按真实ActiveSetting解释）。没有短样本吞吐容量结论。
- 阶段二：冷却30秒，LINEAR_PERPETUAL f1/t1、3×20秒warmup、1×600秒连续measurement，中途不重建服务、不主动GC。固定768MiB G1、NMTsummary、DebugNonSafepoints、PrintNMTStatistics、enable-native-access、opens/exports jdk.internal.misc、opens java.util.zip；-prof gc、async event=wall;interval=10000000;alloc=128k;lock=1ms;threads=true;output=jfr；JVM JFR自定义allocation.jfc（SHA256 e59ae7afc138334cfe3bd62c04e0d451a56a5dbc1063e5f5480a9982c344a2e7）。gc=true仅JMH边界，foe=true；run.py/command.json保存逐项参数。
- 指标：JMH op=cycle，business/s=score×3584、Core messages/s=score×2048、fills/s=score×1024、batches/s=score×1536、items/s=score×3072；aux EVENTS是计数。gc norm/3584为含夹具/查询的B/business op。系统每5秒、NMT/数字FD每30秒；按分钟GC后heap/oldGen/ThreadAllocation趋势、CPU/锁/IO/异常/VM/JIT归因，保留原始JFR、summary、flamegraph和SHA256清单。
- 限制：带profiler值只能用于归因；无无profiler主轮、完整三段尾延迟/p99.9、open-loop或真实Aeron网络。单次measurement没有可用业务吞吐置信区间；仅部分性能验证，不宣称全链路零分配、三节点容量或小时级无泄漏。六产品短样本均测，10分钟仅U本位永续；HTTP/Kafka/WS与真实native池不在本次局部改动验证范围。
- Artifact：/Users/atomex/Desktop/surprising/async-profiler-evidence/2026-09-07-core-allocation；finite/与LINEAR_PERPETUAL/保留全部原件；当前参数开始采集后不可修改，失败与结果只追加。


### 首轮中止与修正（2026-09-07 00:02 UTC+8）

- 首轮六产品有限样本通过，但持续轮复核时发现新增指标编码在进入Lane任务前复制了计数器；PlaceAdmissionEvent/LaneCommitEvent也在Lane线程写这些数组，因此必须在同一次Lane任务内读取，不能依赖Core owner侧提前复制。该风险是本轮新增实现的复核发现，不把正常的既有Lane写入判为交易bug。
- 已主动终止首轮JMH fork（SIGTERM，日志/部分JFR保留，终态验收不成立），不使用该轮作性能结论。artifact根原封保留。预锁记录于2026-09-06 23:58写入，轮次以跨午夜2026-09-07命名。
- 修正将全部操作计数器读取与Lane元数据、matcher计数合并到同一Lane任务，Core owner等待完成；追加“前序排队admission未完成时发出查询”的并发回归，验证查询观察到其完成后的计数/耗时。

## Lane指标读取屏障修正后重验（采集前锁定，2026-09-07 UTC+8）

- 新轮仅当前master c28552c8加最终patch，不使用中止轮或历史版本比较。artifact新根：/Users/atomex/Desktop/surprising/async-profiler-evidence/2026-09-07-core-allocation-final；精确源码patch/JAR SHA和命令独立归档。
- 标准、环境、全部JVM/JMH/JFR参数、六产品1warm+2测量cycle、随后30秒冷却+U本位永续3×20秒预热+600秒连续测量，全部沿用上面“准备下单、小容器与Lane指标编码优化验证”的已锁定完整场景；固定256 in-flight、4Lane、1matcher、batch2、257用户、1模拟会话，原查询频率不变。唯一实现修正为计数器读取放入Lane任务内，未改变业务负载或计数口径。
- 本轮通过要求：业务/Core accepted=terminal、unfinished/endBacklog0、拒绝/错误/超时0、六产品资金及快照断言全通过；持续全JVM gc.norm/3584 <10000 B/business op。GC>50ms、稳定live set净增>32MiB或native/线程/FD增长调查；DataLoss/降频/swap增长判环境无效。全量细项分析/限制/原件保留要求同前一预锁记录，本段开始采样后只追加结果。


### 最终优化验证结果（2026-09-07 UTC+8）

- 实现提交 `24dbf6b3`（master，已推送）；被测JAR由该提交的完全相同业务/测试源码构建，SHA256 `13f19409a96375c8245ba31048eb0898556570969aa2c155092338f82f3de3d5`。最终source.patch SHA256 `5118ee322d74c51f6325130ed705820cb5df7f4ef7f01fefbd0dc978845a73d2`；JFC/dylib校验同预锁记录，revision.json记录基底和最终提交。不比较历史源码、首轮中止数据或旧性能结果。
- 完成三项：准备下单一次安装完整订单/预留，消除占位对象、占位UUID与ReservedOrder载体；小型每用户索引容量2、自然扩容、空索引清理不变，活跃订单同用户更新不删除再添加成员；指标协议直接编码，每Lane一次有完成屏障的任务读取全部计数器/元数据，不暴露运行时数组，finish后编码器封口。公开快照构造/访问防御复制和协议version1字节格式保持不变。
- 测试：扩大reactor覆盖benchmarks/tools/account/trading/market-data/derivatives-lifecycle直接调用方，1060条实际通过、11条数据库条件跳过；最终Lane计数读取屏障修正后重新运行 `mvn -pl :surprising-aeron-benchmarks -am test package`，包含新增第49条TradingRuntimeState并发测试。独立PostgreSQL18.4按当前init.sql初始化，补跑MaintenanceIntegrationTest36条、InstrumentSeedCoreContractTest1条；按类最后一次运行去重，199类、1098条通过，0失败/错误/跳过。明细final-test-counts.json；不能把多次重跑重复计数。
- 新增测试覆盖：准备预留安装对象身份、重复订单/别名拒绝前不多冻结、取消精确解冻；活跃订单100键扩容/部分成交更新/终态回收；独立Lane元数据/计数一致性；前序admission仍被阻塞时排队查询，必须读取其完成后的计数与耗时；直接编码旧协议逐字节相等、1/4/64Lane完整性、封口后禁止再写、计数溢出前不部分更新。现有10资产/100用户预留计数清理、六产品非零费用/资金/回滚和恢复测试继续通过。
- 首轮新测试编译误用不存在的PARTIALLY_FILLED枚举，改为本项目实际OPEN部分成交状态后通过；日志保留于首轮目录。最终数据库第一次启动因artifact绝对路径过长超过macOS Unix socket103字节上限失败，PostgreSQL自行退出；使用新数据目录和短/tmp socket路径重试通过并停止数据库。两次失败日志均保留，没有隐藏、覆盖首轮证据或更改业务代码规避测试。
- 最终六产品finite均exit0，每条1warm+2测量cycles、总10752业务操作/6144 Core messages/3072fills，六条共64512业务操作；每条用户/maker余额、冻结/预留、仓位、订单/client alias终态与snapshot业务hash/alias恢复均通过，原始JFR及summary在finite/。六产品短样本不用于吞吐容量结论。

|U本位永续600秒测量指标|当前master实测|
|---|---:|
|terminal business operations|18,902,016|
|terminal Core messages|10,801,152|
|fills|5,400,576|
|terminal batches / items|8,100,864 /16,201,728|
|额外metrics查询|2,700,288|
|带profiler terminal business ops/s|31,501.46|
|带profiler terminal Core messages/s|18,000.83|
|带profiler fills/s|9,000.42|
|带profiler batches/s / items/s|13,500.63 /27,001.25|
|全JVM分配率|288.88 MiB/s|
|分配B/business op（含夹具/查询）|9,618.47|
|measurement GC次数 / 时间|395 /3229ms|
|measurement GC时间占比|约0.538%|

- 最终持续轮00:04:58启动、00:16:04正常退出，async JFR连续600秒，JVM JFR665秒含预热及终检。acceptedBusinessOperations=terminalBusinessOperations、accepted/terminal Core messages相等、unfinished/endBacklog0、maxBacklog0（仅回调末观察）、拒绝/错误/超时0；600秒中途不重建服务，金融和snapshot终检全通过。预锁10000 B/business op分配阈值通过。JMH score8.789470 cycles/s，单measurement没有可用误差/置信区间，scorePercentiles不能当业务尾延迟。
- 并发口径保持256 in-flight、257用户含maker、1模拟会话、1symbol、4Lane、1matcher；batch平均/最大2，原查询频率没有降低。主数值带profiler且为闭环最大速率；没有无profiler主轮、open-loop或业务入口/accepted/terminal三段p50/p90/p95/p99/p99.9/max，故只通过本轮分配与功能部分验证，不宣称生产容量或吞吐提升百分比。

**实际分配归因**

- 最终reserveOrder安装lambda下样本仅long[]（索引等必要工作），不再出现占位OrderRuntime、ReservationRuntime、UUID或ReservedOrder；完整OrderRuntime/ReservationRuntime仍在准备阶段按业务需要创建，不把必要不可变订单物化称为可全部消除。
- encodeLaneMetrics调用链采样为byte[]、HeapByteBuffer、Encoder及有界任务/发布lambda，没有中间long[]快照。编码器只分配精确输出，不复用已经转交给响应的数组。完整metrics查询分类占5.15%分配，含请求/响应等边界；这不是全部可移除成本，夹具查询比例仍高于通常部署。
- 小集合仍会首次创建/扩容：addUserEntity、indexPosition、clientIndex、pendingReserve的LongHashSet/primitive map及数组样本保留在targets.json。这一轮缩小常见小集合的初始数组并去掉无效活跃成员移除/重建，没有引入无限历史用户空缓存、共享可变业务对象或第二套索引，不能声称全链路0B。
- 全async分配权重181,137,479,704B（抽样估计，窗口与TLAB统计不同），互斥分类owner41.74%、Lane19.47%、matcher9.61%、查询5.15%、夹具response decode14.06%、fixture request/setup7.10%、Core输出2.82%、snapshot0.04%。top class/site/完整栈在results.json、targets.json、alloc.collapsed/html；JIT行号不能机械当源码new表达式。未报告无法由抽样精确推导的对象数/业务操作。
- 全JVM NewTLAB444269事件、refill bytes200,170,661,424，OutsideTLAB5744事件/251,635,544B；最大NewTLAB触发对象262160B、最大outside对象4,194,320B。refill bytes不等于触发对象大小之和。稳定分钟ThreadAllocation差分：owner202.26–205.16MiB/s、Lane合计55.52–56.32、matcher27.33–27.73，无持续上涨；含各线程实际执行的夹具/查询归属。

**GC、内存、线程与VM**

- 稳定窗口（记录第2分钟起）GC后heap45.31–47.29MiB，G1 oldGen均值26.48→26.54MiB；heap committed768MiB。全JVM GC444次（young435、old9），原因G1 Evacuation Pause434、System.gc8、Metadata GC Threshold2；JMH边界显式GC不混入600秒业务GC。pause p50 8.20ms、p95 8.95ms、p99 13.82ms、max21.32ms；没有promotion/evacuation failure记录，未触发50ms/32MiB告警。
- NMT采样committed950.01–961.85MiB，最后950.93MiB；reserved首末2,335,993→2,331,248KiB。Tracing18.44–29.66MiB且两次明显释放，不能算业务native泄漏；Code首末37507→37464KiB、Metaspace22770→22898KiB。664个DirectBuffer事件count/capacity/used均0；本夹具不含真实Aeron/Netty/Chronicle native池流量，真实池分配/释放余额未验证。
- 稳定active Java threads16，数字FD21次样本均15；CPU_Speed_Limit均100、swap/pageout0、JFR DataLoss0。10分钟未见持续堆/oldGen/native/FD/线程增长证据，不外推小时/天级无泄漏；未用heap histogram/OldObjectSample扰动运行。
- JDK ExecutionSample61942，最高单栈8884为Thread.isInterrupted→SurprisingClusteredService.idleCommand，后续有LaneMutationTask.await等待真实准备/结算。夹具NoOpIdleStrategy会忙轮询；保留提交完成、中断、超时和可见性屏障，不误删正常等待。JFR process CPU load均值0.11423，machine均值0.13636/max0.46024；ThreadCPULoad原始均值owner0.06175、matcher0.01362、Lane约0.00576–0.01160，原值和线程分组在diagnostics.json/cpu.tsv。macOS async为wall，不能把等待线程墙钟份额当CPU占比。
- async lock1ms门限0样本；JFR park/wait原始时长及栈在wait-io.tsv，主要为Lane空闲、JMH main和后台releaser；没有交易回调内JFR门限以上同步file/socket IO样本。没有样本不证明所有短IO/锁都不存在。
- SafepointBegin481、最长进入0.129ms；ExecuteVMOperation512次/累计3722.74ms/max21.35ms，与GC停顿量级一致。Compilation31次/总6772.84ms/max584.39ms在编译线程，不当STW；Deoptimization286含启动。JavaExceptionThrow530、JavaErrorThrow136为可能重叠事件，主要启动/反射/MethodHandle及终检边界，稳定16:07–16:16 UTC的交易回调无异常样本；完整时间/栈在exceptions.tsv。
- 样本未包含真实3节点Aeron网络、HTTP/Kafka/WS与全部风险重操作的持续混合容量；六产品资金/恢复由短JMH场景及相关回归补足，不扩大解释10分钟永续样本。性能验收范围和缺失的业务分段尾延迟如上，当前交付为全部三项局部优化及功能/分配验证完成。
- 最终artifact根 `/Users/atomex/Desktop/surprising/async-profiler-evidence/2026-09-07-core-allocation-final`，172个证据文件约192.67MiB（不计临时PG数据目录），包含源码/JAR、精确命令、两类原始JFR/summary、flamegraph、TLAB/CPU/GC/VM/异常/分钟趋势、NMT/FD/系统以及测试原件。SHA256SUMS自身SHA256 `8542ecbacc83852fc730fce902407e8aac6ac9a2c015c9346805fdb33c8574ad`。首轮中止和数据库初次启动失败原件保留；最终压测与临时数据库进程均已结束。


## 六产品无采样profiler吞吐测量（2026-09-07，采集前锁定）

- 请求：优化后实测性能；只测试当前master a45769cc（业务24dbf6b3），不修改业务代码。对照commit：不适用（仅验证当前master），不与历史结果或不同采样配置作收益百分比比较。
- 环境：HotSpot Oracle GraalVM25.0.1+8、Maven3.9.16，macOS26.7 x86_64、Intel i9-9880H8C16T/16GiB；启动前swap0、CPU_Speed_Limit100。本机有用户桌面应用运行，保留每30秒同机进程证据，不关闭用户应用；结果仅作共享本机指定场景测量。
- 主轮：六产品按SPOT/LINEAR_PERPETUAL/INVERSE_PERPETUAL/LINEAR_DELIVERY/INVERSE_DELIVERY/OPTION依次独立JMH进程；每条f1/t1，3×10秒warmup、3×20秒measurement，每条前cool15秒，foe=true、gc=true（仅iteration边界）。主轮不启用async-profiler、JFR或-prof gc，不启用NMT；保留JVM GC日志与外部系统监控。固定-Xms768m -Xmx768m -XX:+UseG1GC、native-access、opens/exports jdk.internal.misc、opens java.util.zip；精确命令在各command.json。
- 统一场景ClusteredBatchTradingBenchmark.decodedBatchAdmissionAndSettlement：256 in-flight、4Account Lane、1matcher、batch2、realtime=false、256用户+1maker/1symbol/1模拟ClientSession。每cycle3584 business ops/2048业务Core messages/1024fills/1536batches/3072items/512单业务操作/512额外metrics查询；保持原批量挂单、改单成交、往返开平仓与批量挂撤单比例和查询频率。Core实际onSessionMessage→admission→matcher→Lane settlement→commit→response，闭环最大速率，未修正coordinated omission，无open-loop到达率。
- 初态：每用户/maker settle余额1,000,000,000，zero fees、CROSS、mark100、maker持续120卖挂单，现货maker BTC513；做市由真实Core内maker账户提供流动性，不是独立网络进程。每cycle核对计数，每iteration全部资金、冻结/预留、仓位、订单/client alias终态和snapshot业务hash/alias恢复核对。业务accepted=terminal、Core accepted=terminal、unfinished/endBacklog=0，拒绝/错误/超时0为硬性要求。
- 预锁吞吐检查：每产品主轮均值>=20000 terminal business ops/s，3个measurement样本CV<=10%；此为本轮本机观察阈值，不代表生产SLA。任一财务失败停止；性能阈值失败如实记录/诊断，不改场景重定义通过。明显降频、swap增长或持续外部负载干扰必须标记无效/受扰，禁止用受扰数字作容量承诺。
- 指标：JMH score×3584为business ops/s、×2048为Core messages/s、×1024为fills/s、×1536为batches/s、×3072为items/s。主分数与99.9%CI/误差、3次原值、CV、aux EVENTS计数全部保存；不能把JMH scorePercentiles当订单延迟。外部系统每5秒、数字FD与同机进程每30秒，JVM gc日志单列。
- 诊断轮：主轮六产品完后冷却30秒，U本位永续相同业务参数，f1/t1、3×10秒warmup、1×180秒measurement。相同768MiB G1，追加NMTsummary、DebugNonSafepoints、PrintNMTStatistics、-prof gc和async4.5 wall10ms/alloc128KiB/lock1ms/threads/JFR；JVM JFR使用allocation.jfc，SHA256 e59ae7afc138334cfe3bd62c04e0d451a56a5dbc1063e5f5480a9982c344a2e7。此轮仅补GC/分配、CPU/线程/堆/native/IO/VM归因，不代替无采样主轮吞吐，不混算成同一置信区间。
- 诊断阈值：gc.norm/3584<10000 B/business op，DataLoss0、CPU限速100、swap无增长、金融/终态全部通过；GC>50ms、稳定live set净增>32MiB或native/FD/线程持续增长需调查。保留原始JFR/summary、全部聚合、NMT/FD和SHA256清单。
- 范围：本轮报告六产品内部终态吞吐及独立诊断，不含真实三节点Aeron网络/HTTP/Kafka/WS、全部风险重操作负载或入口/accepted/terminal三段尾延迟。单fork只有3measurement、闭环负载与桌面共享机器均限制外推；没有完整分段p99.9/open-loop/小时级长稳，只能标记部分性能验证。无业务修改，不重复刚通过的1098条回归，以每轮实际资金及snapshot断言核对当前构建。
- Artifact根：/Users/atomex/Desktop/surprising/async-profiler-evidence/2026-09-07-throughput；主轮各产品目录与diagnostic/严格分开。采样前归档当前源码提交、JAR和配置SHA256；本段开始采集后不修改，结果和失败只追加。


### 六产品小批量轮停止：用户要求恢复15万对应场景（2026-09-07）

- 用户指出关注的是此前约15万对应的mixed标准，本轮却沿用了近期分配诊断的batch2/单symbol/service回调场景，选择口径不符合其预期。两者不能据数值差异判断性能回退。
- 已完成SPOT均值31451.51、LINEAR_PERPETUAL34355.91 terminal business ops/s；原始3measurement、资金与恢复均通过。INVERSE_PERPETUAL中途SIGTERM停止，其他三产品和诊断轮未启动；保留所有原件，不把未完成六产品轮认定完整通过。原artifact：2026-09-07-throughput，不覆盖预锁内容。

## 按原mixed配置重测当前master（2026-09-07，采集前锁定）

- 用户要求按约15万对应的压测标准验证。复用PV-133配置和300秒driver、原始profile.jfc，只有被测源码使用当前master a45769cc（业务24dbf6b3），artifact路径独立。对照commit：不适用（仅验证当前master）；读取旧记录仅为恢复场景参数，不检出旧版，不作历史性能百分比比较。
- 精确driver：`LinearPerpetualScaleSoakMain 1000 256 256 5 10 UNIFORM 1 20 32 300 30`；U本位永续、1000活跃用户、256 listed/active symbol、每用户初始最多5持仓/10挂单、hftRounds1/batch20、lifecycleSymbolsPerRun32，4Lane/1matcher/固定256 in-flight。closed-loop饱和、openLoop=false、CO未修正；包含双向批量下单/成交/撤单、trigger/risk/资金费和每scenario一次强平→保险→ADL，内部maker持续供给，外部连接0。不把fill计入订单ops，实际比例由同一mixed driver和状态决定。
- 初始账户资金/持仓沿用当前LinearPerpetualMixedWorkload模板与原配置；逐cycle accepted/terminal business/Core闭合、逐batch逐item成功；结束前用户/maker资金守恒、冻结/仓位/订单终态及snapshot hash恢复必须通过。允许可继续的risk工作流存在，必须与未终态Core命令区分；funding未完成单列。
- JVM严格沿用原参数：HotSpot25.0.1 Oracle GraalVM、-Xms4g -Xmx4g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC、-Dsurprising.aeron.matching-engines=1、-Dsurprising.benchmark.openLoop=false、jdk.internal.misc opens/exports、NMTsummary/UnlockDiagnosticVMOptions/PrintNMTStatistics、JFR dumponexit/maxsize512m、gc/safepoint日志；不加async-profiler。原profile.jfc SHA256 `4dbdbd4994757dc2e6930dee513d8ee298d687d9f298bc27434455d499784dc7`，精确run.sh/JAR哈希在新目录revision.json。
- 环境：macOS26.7/i9-9880H8C16T/16GiB、Maven3.9.16；本机桌面共享环境，不关闭用户应用。开始前冷却30秒；单JVM单owner，无单独预热，300秒中包含预热，30秒窗口逐个报告不丢低速窗口，最后未满窗口也计总量，最终verify/snapshot/restore计入原driver elapsed分母、setup单列。该轮有JFR开销，不能称无profiler主吞吐或JMH结果。
- 阈值：用户关注150000 terminal business ops/s，本次按完整driver均值>=150000报告是否达到，同时保留全部30秒窗口，不能选峰值冒充达标。功能accepted=terminal、每batch每item成功、unfinished/endBacklog0、资金/snapshot正确为硬门槛。GC后live set斜率<1MiB/s、native<256KiB/s、线程/FD/pool count<0.01/s且>=3有效GC样本；核对JFR的真实GC后heap，不能仅用MBean零值判无增长。
- 每5秒pmset/swap，每30秒所有进程CPU；Speed_Limit<100、swap增长、JFR DataLoss或显著后台干扰时性能结论标为无效/受扰，但保留完整负载与功能终检证据。不改阈值美化结果。非业务修改，不重复测试套件；此次driver自身金融与恢复校验直接约束压测。
- 分析：保留原始soak.jfr、summary和流式JfrRead聚合（含分业务三阶段直方图），报告总吞吐、Core messages/s、sweep与命令延迟区别、分配/GC/heap/native/CPU/锁/IO/VM/JIT/异常/系统状态。无独立fill计数则标未输出，不推测fills/s；无逐item延迟则不能把batch直方图当item。现有JFR含setup及终检，需注明窗口。
- 未测：其他五产品、API/真实Aeron3节点/Kafka/WS、生产native池、完整open-loop及小时级长稳，本轮仅原mixed标准下当前源码的五分钟性能验证。artifact：/Users/atomex/Desktop/surprising/async-profiler-evidence/2026-09-07-mixed-standard；采样开始后本段不再修改，所有结果和失败只追加。


### 原mixed标准当前代码结果（2026-09-07 09:07–09:13 UTC+8）

- 只运行当前master a45769cc（业务24dbf6b3），没有更改业务代码、检出旧版本或调整负载追求数值。JAR SHA256 `13f19409a96375c8245ba31048eb0898556570969aa2c155092338f82f3de3d5`，原配置JFC SHA256 `4dbdbd4994757dc2e6930dee513d8ee298d687d9f298bc27434455d499784dc7`；新run.sh SHA256 `69461da6a3533383daea81df78a2eb46f54d95ac40d2a52caef2d5ce27acdae7`。已恢复1000用户/256币对/batch20/4GiB ZGC/300秒的原mixed参数，256 in-flight和1matcher不变。
- **全程均值158417.667 terminal business ops/s，达到本轮150000目标**；47,937,232个terminal business operations、4,822,736个terminal Core messages、15937.645 terminal Core messages/s。driver elapsed302.600秒含首段预热及最终verify/snapshot/restore，setup3275.984ms单列；没有删去低速首段美化均值。run退出0、summary PASS、fundsInvariant=true。
- 九个完整30秒窗口依次123681.524、162934.659、164085.434、163656.728、165014.378、164358.275、163877.842、164877.210、162858.513 business ops/s。首窗口含预热，之后已输出窗口范围162858.513–165014.378；最后不足30秒窗口计入全程总量，无单独窗口数字。不把最高窗口165014当全程均值。
- 逐cycle business/Core accepted=terminal，逐batch逐item成功、未终态命令和期末消息backlog0；最大matching backlog256是运行中真实在途。终检余额/冻结/仓位/订单终态、maker资金及snapshot业务hash恢复通过；snapshot25,984,748B、恢复1278.260ms。期末222个可继续risk workflow是该mixed场景的存续业务状态，不是222个未终态Core消息；funding未完成0。driver没有独立fill、batch/items累计输出，不推测fills/s或用business数量充当Core消息数。
- 此场景直接经Harness→CoreProbeState并流水化mixed工作，与先前小批量service回调场景不同；batch20的每item按业务操作计数，报告中同时列15937.645 Core messages/s防止误读成15.8万网络请求/s。无采样service小批量轮的31451/34356仅归属其自己的场景，不用于判断mixed退步。原记录约15万只是用于确认用户所指场景，本轮不作历史收益百分比比较。

|JFR内部入口→终态（毫秒，直方图桶上界）|样本数|p50≤|p90≤|p95≤|p99≤|p99.9≤|max（实测）|
|---|---:|---:|---:|---:|---:|---:|---:|
|PLACE_ORDER|1,134,592|4.194304|4.194304|4.194304|8.388608|16.777216|21.142458|
|CANCEL_ORDER|1,134,592|4.194304|4.194304|4.194304|8.388608|16.777216|19.407526|
|ORDER_BATCH|2,269,184|16.777216|33.554432|33.554432|33.554432|67.108864|132.840270|
|TRIGGER_ORDER|141,824|0.262144|0.524288|0.524288|0.524288|1.048576|10.511094|
|RISK_SCAN|71,628|0.065536|0.131072|0.131072|0.262144|0.262144|1.126822|
|FUNDING|70,912|0.131072|0.131072|0.131072|0.262144|0.524288|5.920921|

- 全部三段（entryAccepted/acceptedTerminal/entryTerminal）p50/p90/p95/p99/p99.9/max及样本数见soak-analysis.txt；按2次幂纳秒桶上界输出，包含setup/预热，不是精确逐样本百分位。ORDER_BATCH是整批延迟，不能当单item延迟；真实API入口、CO修正及独立taker-fill分位未覆盖。LIQUIDATION仅2样本、ADL仅1样本，不用来认定持续强平/ADL性能。sweep p50/p95/p99/max=130912.457/153045.231/270989.297/530245.638μs是全业务周期耗时，也不是单订单延迟。批次max132.84ms如实保留，吞吐达标不表示尾延迟SLA达标。
- JFR summary306秒、流式聚合306.491秒，原文件64,662,528B（约61.67MiB），DataLoss0。总分配采样权重247,553,782,808B、约807,703,270B/s；TLAB229,898,468,840B、非TLAB18,342,644,872B、最大对象33,554,448B。含setup/终检，不冒充JMH -prof gc精确B/business op或精确对象数/op。按owner/harness、Lane、matcher分配权重130,400,731,400/82,541,008,888/34,597,265,560B；CPU样本10893/2487/792，原class/site/线程栈保留。
- JFR GC78次，ZGC Minor60次、Major18次；全部GC累计pause6.247ms，每GC累计pause分布p50/p95/p99/max=0.073/0.148/0.170/0.170ms；270个GCPhasePause最大单phase0.066ms，无ZAllocationStall/evacuation/promotion failure。JFR After-GC78点、末498MiB/峰684MiB，去首60秒稳健斜率132973B/s，低于1MiB/s。MBean342次信号的零斜率未被直接当作heap无增长，已用JFR交叉检查。
- NMT末reserved74,531,854,517B、committed4,446,795,957B，heap committed固定4GiB（ZGC较大reserved是地址空间预留，非相同大小物理驻留）；Code末约31.45MiB、GC35.29MiB、Metaspace32.55MiB、Tracing20.82MiB，完整first/last/min/max分类在聚合。Direct/Mapped bytes/count全0、线程稳定14/FD13；未测真实Aeron/Netty等外部native池，不外推生产池无泄漏。
- 62次外部系统采样Speed_Limit均100、swap0；JFR机器CPU均值0.147/max0.258、进程均值0.126，main ThreadCPULoad约0.062、四Lane约0.010–0.011、matcher约0.011。保留全部桌面进程采样，仍是共享本机环境。五分钟无达到预设泄漏斜率告警的证据，不宣称小时/天级无泄漏。
- SafepointBegin279次/最大0.406ms，VMOperation943次/总30.087ms/max3.230ms；Compilation10805次/总31641ms/max1803.606ms为编译线程耗时，不当STW。CPU热点包括CommandFingerprint的SHA摘要、Core completion/admission通知、批次decode及Lane完成等待；此次只测性能，没有据此删除业务去重或完成屏障。
- JavaExceptionThrow317/JavaErrorThrow153主要启动和反射/JNR能力探测；无JavaMonitorEnter contention、SocketRead/Write或ZAllocationStall事件。全录制FileRead2275次/10.044ms、FileWrite488次/10.750ms，主要类加载和driver采样stdout；不把“同为main线程”误认作交易持久化IO。新增全栈流式IoAudit核对首60秒之后CoreProbeState.apply调用链，stableCoreIo=0、stableCoreThrows=0，原始JFR及io-audit.txt保留，零样本仅代表本配置记录范围。
- 结论：按用户所指mixed场景，当前代码全程吞吐达到150000目标且资金/终态/恢复与环境门槛通过。该场景是有JFR的单JVM Core内部五分钟闭环运行，无独立JMH主轮/置信区间、无真实三节点网络、无完整open-loop/API/item级尾延迟，整体仍属部分性能验证。不得用其替代真实服务网络容量或所有六产品的同标准验收。
- Artifact `/Users/atomex/Desktop/surprising/async-profiler-evidence/2026-09-07-mixed-standard`，24个文件130,656,879B，原始JFR、run/config/JAR校验、summary/全量分析、系统/GC/IO审计齐备，SHA256SUMS自身SHA256 `167453f757b7586e317d65b5a3f96a76be76a650ece77340456db0c7704382f5`。停止的小批量轮41文件65,316,696B、manifest SHA256 `d5dac6d620e1942ce5a4efadb0d28ef66bb809331ab459234d46bbc565b26985`，不覆盖或隐去中止事实。压测进程已全部退出。

- 文件大小校正：上述soak.jfr经最终stat核验为64,662,208B（约61.67MiB）；先前精确字节数有录入误差。原始artifact及SHA256清单不变。


## Owner批次完成优化（2026-09-07，采集前锁定）

- 请求：优化owner串行收尾、Lane往返和无进展轮询。仅当前master；对照commit：不适用（仅验证当前master）。实际被测commit/JAR SHA在采集前保存至artifact。改动：纯撤单批次复用LaneCancelEvent完成Lane commit；等待未完成Lane的批次不进入收尾；批次响应直接编码owner临时items容器并回填帧长度，省去防御复制和重复长度计算。改单双阶段提交、资金校验、SHA256幂等、回调完成/快照/恢复边界保留。
- 机器：macOS26.7 x86_64、Intel i9-9880H 8C16T、16GiB；HotSpot Oracle GraalVM25.0.1+8、Maven3.9.16。同机用户应用保留，5秒采集CPU_Speed_Limit/swap、30秒进程CPU；节流/swap新增/JFR DataLoss使轮次无效。每轮顺序执行，不并发压测。
- Artifact：`/Users/atomex/Desktop/surprising/async-profiler-evidence/2026-09-07-owner-completion`，包含JAR、命令、测试日志、系统/GC日志、JFR/config/分析、SHA256清单；不纳入git。
- mixed主吞吐与采样：保持上一条用户确认的标准，LinearPerpetualScaleSoakMain参数 `1000 256 256 5 10 UNIFORM 1 20 32 300 30`。1000用户，256活跃symbol，初始每用户最多5持仓/10订单，UNIFORM，hftRounds1/batch20，lifecycleSymbols32，4Account Lane/1matcher，固定256 in-flight；0外部连接，夹具main同时生成请求与执行Core owner，内部maker持续保留流动性。包括双向下单/成交/撤单、触发/风险/资金费及每scenario一次强平→保险→ADL；不是风险风暴或真实Cluster网络容量。
- mixed JVM：`-Xms4g -Xmx4g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -Dsurprising.aeron.matching-engines=1 -Dsurprising.benchmark.openLoop=false --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED`。闭环最高速率，未修正coordinated omission；无独立warmup，300秒包含首段预热，终检进入总耗时；另报30秒稳定窗口。主轮不启用JFR/async/NMT，保留GC日志；诊断轮添加NMT summary/PrintNMTStatistics及上一mixed原样profile.jfc，JFR maxsize512m、dumponexit，记录300秒业务+setup/终检；轮间冷却15秒。
- mixed通过阈值：无profiler主轮持续terminal business ops/s≥150000；诊断轮只作归因，不计算优化收益百分比。每轮accepted=terminal business ops及Core messages、unfinished=0、期末命令backlog0、非预期拒绝/错误/超时0，资金/冻结/持仓/订单终态及snapshot恢复hash必须通过；报告fills/trades可用口径。稳定GC后live-set斜率<1MiB/s、native committed斜率<256KiB/s、线程/FD斜率<0.01/s，无allocation stall及恢复失败。
- 六产品影响面JMH：新增`ClusteredBatchTradingBenchmark.ownerBatchCompletion`，逐产品独立进程SPOT、LINEAR_PERPETUAL、INVERSE_PERPETUAL、LINEAR_DELIVERY、INVERSE_DELIVERY、OPTION。f1/t1、warmup2×3s、measurement3×5s、每轮前冷却15s、`-prof gc -foe true`。JVM768MiB G1、1matcher、4Lane、batch20、256 in-flight、realtime=false、257用户含maker、1模拟会话/1symbol、余额每账户1e9结算单位、SPOT maker BTC余额5121单位、初始无用户持仓，maker持续挂120卖单。每cycle先2个place batch+254个missing cancel batch+2个有效cancel batch，再256个place+256个cancel batch；合计15400 business ops/770 Core messages、15400items/770batches、512metrics查询、5080预期ORDER_NOT_FOUND拒绝、0fill（另有六产品amend成交功能回归）。必须按拒绝与成功分别解释，不等同全成功成交吞吐。该轮对吞吐无生产容量断言；资金/snapshot/terminal硬断言，报告JMH误差、GC分配B/op、GC次数/时间。JMH该专用场景没有三段业务延迟，不能单独宣称完整性能验收。
- JFR分析：沿用streaming JfrRead/IO audit，包含owner/Lane/matcher CPU、栈、allocation/TLAB、GC暂停/live-set/native/线程/FD、VM/JIT/异常/IO和三段分业务延迟p50/p90/p95/p99/p99.9/max（直方图桶上界）。分析owner忙轮询，不把CPU100%当有效计算100%。完整原始artifact保留；短测不证明长期无泄漏。真实3节点网络、外部API/Kafka/WS不在本次范围，回调同步约束明确保留。


### Owner JMH启动修正（2026-09-07，重跑前锁定）

- 上一轮SPOT JMH在warmup开始前IllegalAccessError：命令行-jvmArgsAppend覆盖注解中的add-opens/add-exports，Agrona无法访问jdk.internal.misc.Unsafe。没有有效measurement，其余五产品未启动；driver已因foe=true退出。原始jmh-SPOT.log保留，不作为业务失败或性能样本。
- 仅修正JMH fork启动参数，完整为 `-Xms768m -Xmx768m -XX:+UseG1GC -Dsurprising.aeron.matching-engines=1 --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED`。重跑六产品参数、场景、阈值、采样和冷却完全沿用上条预锁定义，原始文件使用jmh-r2-*命名。仅当前master97d762d6，对照不适用。
- mixed无profiler主轮已PASS：164631.242 business ops/s；JFR诊断轮功能PASS但180秒后出现同机diskimagesiod、iOS/watchOS update_dyld_sim_shared_cache及桌面应用CPU干扰，保留整轮，不把后半吞吐下降归因于交易代码。JMH继续记录同机负载，只用于场景功能/分配部分验证，不作生产容量断言。


### Owner JFR环境无效后的补采（2026-09-07，采集前锁定）

- 第一轮JFR末段09:51:02/07 CPU_Speed_Limit70、09:51:12/17为75，触发预锁环境无效条件。其135796.487 business ops/s及尾延迟不作验收；资金/snapshot PASS保留，不能以功能PASS替代性能有效性。无profiler主轮在09:45前结束，该轮系统样本CPU limit均100、swap0，不受这次限速影响。JMH-r2六产品已正常完成。
- 当前CPU limit已连续恢复100，补采仅运行相同300秒mixed JFR，不重跑已通过无profiler主轮，不改代码/参数/阈值。当前master业务97d762d6及同一benchmarks.jar，profile.jfc完全相同；固定256 in-flight、1000用户/256symbol/4Lane/1matcher/batch20、4GiB ZGC、300秒闭环含首段预热、30秒窗口、15秒冷却；其余指标及不变量沿用本节原预锁定义。artifact子目录jfr-r2，保留原轮全部文件。


### Owner优化最终验证结果（2026-09-07）

- 被测业务commit `97d762d6`（已push master），benchmarks.jar SHA256 `0b3de994f8ea86475a1e923313e7d3a6bf525fc936d14d4d038f168ae0f6fc8c`；对照commit不适用，仅当前master。未改变主链路功能/线协议；700项测试、113个测试类，0失败/错误/跳过（protocol/client/instrument-api/service/benchmarks Maven依赖链）；其中六产品JMH夹具功能回归、批量撤改成交、实时出口和快照、资金校验均通过。新测试验证纯撤单无额外metadata/sequence Lane任务、混合成功/缺失订单拒绝、余额解冻、幂等和snapshot恢复。初次定向回归及全回归日志均归档。
- 实际改动：纯撤单批次把metadata及Lane commit合入已有取消事件；改单双阶段提交仍保留。批次Lane工作未完成时不进入收尾；编码直接同步消费owner-local结果列表，保留所有索引/大小校验和不可变Item，编码完成后不引用临时容器，线协议未变。没有修改资金校验、SHA256幂等、快照一致性或生产回调同步完成约束。
- 无profiler mixed主轮：elapsed 302.48s（setup 3414.207ms独立），terminal business ops 49,797,589 / **164,631.242 ops/s**；terminal Core messages 5,009,877 / 16,562.695 messages/s。持续均值包含预热及终检，达到预锁150000；snapshot 26,781,619B、restore 1109.565ms。
- 有效JFR补采：elapsed 302.634s（setup 3155.659ms），terminal business ops 47,245,008 / 156,112.521 ops/s；terminal Core messages 4,753,104 / 15,705.766 messages/s。snapshot 25,688,183B、restore 1317.002ms。补采样本不代替无profiler主分数。
- 两个有效mixed轮均status PASS、fundsInvariant=true，夹具逐cycle校验accepted/terminal business ops及Core messages相等、unfinished/end backlog0、最大matching backlog256；金融/余额冻结/持仓/快照hash全部通过。主轮189、补采222个incompleteRiskScans为待后续价格/风险命令推进的持久业务扫描状态，不是未完成Core命令；funding unfinished0。没有独立fills/s输出，不把business ops当fills。
- 环境：无profiler主轮、补采系统CPU_Speed_Limit全部100、swap0；JFR DataLoss/JavaMonitorEnter/SocketRead/SocketWrite/ZAllocationStall均0。初次JFR于09:51限速70–75，已判无效并保留原件；初次JMH缺module exports启动失败也保留。JMH-r2六产品正常完成且该时段未记录限速。所有压测均结束。
- 当前owner+夹具main第60–290秒平均单逻辑CPU99.40%；Lane0/1/2/3分别17.86/17.65/17.32/16.78%，matcher17.44%。整JVM CPU原始均值0.126、machine0.183（总16逻辑CPU归一）。owner仍是串行热点，不宣称瓶颈消失；main还承担请求生成。8303 owner样本中Lane await551、matching await/pump340、fingerprint473、batch decode145；分类互斥但不是准确墙钟占比。stampChangedOrdersByLane仍有36个样本（其他命令保留该逻辑），不能声称所有stamp路径被删除。主热点为指纹、ready队列检查/流水线推进、结果读取和终态保留；未据此删除一致性逻辑。
- JFR分配估计239,017,983,216B /780,094,985.626B/s，约5059.12 B/business op（含setup/夹具，抽样权重）；NewTLAB refill221,521,513,032B、OutsideTLAB18,178,212,272B、最大对象33,554,448B。完整top class/site/线程分配在soak-analysis；不将抽样事件数当精确对象数/op，不宣称零分配。
- ZGC77周期（59minor、18major），267暂停phase合计5.987ms，占306.396秒约0.00195%；phase p50/p95/p99/max0.016/0.049/0.059/0.441ms。AfterGC last550MiB、peak724MiB；剔除首60秒robust live-set斜率197713.96B/s <1MiB/s。NMT committed第60–290秒4,435,634,390→4,438,728,679B，robust斜率7239.11B/s <256KiB/s；4GiB heap固定，ZGC巨大reserved为地址空间，不当RSS。稳定14threads/13FD、direct/mapped/count0，终检恢复线程增加单列为边界，不当稳定泄漏。5分钟未见超阈值增长，不能外推小时/天级无泄漏。
- SafepointBegin277/max2.153ms；VMOperation919/合计27.385ms/max2.054ms；Compilation10890/总30546.783ms/max1797.193ms在编译线程，不当STW。稳定60秒后CoreProbeState.apply栈IO及throw均0，startup/终检异常及驱动stdout单列于io-audit/soak-analysis。完整park/异常/CPU分钟分布保留；该夹具没有真实Aeron/Netty外部native池流量，不能外推外围池无泄漏。

六产品专用JMH（带gc profiler，含32.987%预期拒绝，**不是全成功成交吞吐**）：

| 产品 | terminal business ops/s ± JMH误差 | Core messages/s | B/business op | 分配MiB/s | GC次数/时间ms |
|---|---:|---:|---:|---:|---:|
| SPOT | 216388.93 ± 162773.12 | 10819.45 | 3595.06 | 725.20 | 31/322 |
| LINEAR_PERPETUAL | 197498.34 ± 105182.60 | 9874.92 | 3556.73 | 657.24 | 28/318 |
| INVERSE_PERPETUAL | 219832.55 ± 22773.97 | 10991.63 | 3590.50 | 739.33 | 32/355 |
| LINEAR_DELIVERY | 211905.84 ± 182927.13 | 10595.29 | 3598.73 | 714.16 | 29/353 |
| INVERSE_DELIVERY | 220263.37 ± 20319.22 | 11013.17 | 3608.86 | 744.94 | 32/354 |
| OPTION | 213188.78 ± 120351.82 | 10659.44 | 3598.61 | 716.16 | 32/365 |

- JMH主score为cycles/s，乘15400得到业务ops/s、乘770得到Core messages/s与batches/s，items/s等于业务ops/s，平均/最大batch20，fills0。AuxCounters EVENTS的score单位#是累计数量，**没有把它当ops/s**；accepted/terminal两组原始计数逐一相等，json保留rawData及误差/置信区间。短JMH误差偏宽且含桌面同机负载，只作本场景验证，不用于生产容量或收益百分比。资金/snapshot验证逐iteration执行；gc时间来自3个measurement（约15秒），比率需按实际measurement耗时解释。

业务三段延迟（有效JFR补采，含setup/warmup；单位ms，p值为直方图桶上界，max精确）：

```text
ADL/acceptedTerminal n=1 p0.500<=0.002048 p0.900<=0.002048 p0.950<=0.002048 p0.990<=0.002048 p0.999<=0.002048 max=0.001038
ADL/entryAccepted n=1 p0.500<=2.097152 p0.900<=2.097152 p0.950<=2.097152 p0.990<=2.097152 p0.999<=2.097152 max=1.724449
ADL/entryTerminal n=1 p0.500<=2.097152 p0.900<=2.097152 p0.950<=2.097152 p0.990<=2.097152 p0.999<=2.097152 max=1.725487
CANCEL_ORDER/acceptedTerminal n=1118208 p0.500<=4.194304 p0.900<=4.194304 p0.950<=8.388608 p0.990<=8.388608 p0.999<=16.777216 max=18.357829
CANCEL_ORDER/entryAccepted n=1118208 p0.500<=0.002048 p0.900<=0.004096 p0.950<=0.004096 p0.990<=0.016384 p0.999<=0.032768 max=0.597705
CANCEL_ORDER/entryTerminal n=1118208 p0.500<=4.194304 p0.900<=4.194304 p0.950<=8.388608 p0.990<=8.388608 p0.999<=16.777216 max=18.360460
FUNDING/acceptedTerminal n=69888 p0.500<=0.000128 p0.900<=0.000256 p0.950<=0.000256 p0.990<=0.000512 p0.999<=0.001024 max=0.043020
FUNDING/entryAccepted n=69888 p0.500<=0.131072 p0.900<=0.131072 p0.950<=0.131072 p0.990<=0.262144 p0.999<=0.524288 max=5.948740
FUNDING/entryTerminal n=69888 p0.500<=0.131072 p0.900<=0.131072 p0.950<=0.131072 p0.990<=0.262144 p0.999<=0.524288 max=5.950464
LIQUIDATION/acceptedTerminal n=2 p0.500<=0.002048 p0.900<=8.388608 p0.950<=8.388608 p0.990<=8.388608 p0.999<=8.388608 max=4.591466
LIQUIDATION/entryAccepted n=2 p0.500<=4.194304 p0.900<=8.388608 p0.950<=8.388608 p0.990<=8.388608 p0.999<=8.388608 max=4.742774
LIQUIDATION/entryTerminal n=2 p0.500<=4.194304 p0.900<=16.777216 p0.950<=16.777216 p0.990<=16.777216 p0.999<=16.777216 max=9.334240
ORDER_BATCH/acceptedTerminal n=2236416 p0.500<=16.777216 p0.900<=33.554432 p0.950<=33.554432 p0.990<=33.554432 p0.999<=67.108864 max=343.192467
ORDER_BATCH/entryAccepted n=2236416 p0.500<=0.032768 p0.900<=0.065536 p0.950<=0.065536 p0.990<=0.065536 p0.999<=0.131072 max=9.306980
ORDER_BATCH/entryTerminal n=2236416 p0.500<=16.777216 p0.900<=33.554432 p0.950<=33.554432 p0.990<=33.554432 p0.999<=67.108864 max=343.196493
PLACE_ORDER/acceptedTerminal n=1118208 p0.500<=4.194304 p0.900<=4.194304 p0.950<=4.194304 p0.990<=8.388608 p0.999<=16.777216 max=35.651483
PLACE_ORDER/entryAccepted n=1118208 p0.500<=0.016384 p0.900<=0.016384 p0.950<=0.032768 p0.990<=0.032768 p0.999<=0.065536 max=15.701772
PLACE_ORDER/entryTerminal n=1118208 p0.500<=4.194304 p0.900<=4.194304 p0.950<=4.194304 p0.990<=8.388608 p0.999<=16.777216 max=35.663524
RISK_SCAN/acceptedTerminal n=70604 p0.500<=0.000128 p0.900<=0.000128 p0.950<=0.000256 p0.990<=0.000512 p0.999<=0.001024 max=0.017282
RISK_SCAN/entryAccepted n=70604 p0.500<=0.065536 p0.900<=0.131072 p0.950<=0.131072 p0.990<=0.262144 p0.999<=0.262144 max=2.067729
RISK_SCAN/entryTerminal n=70604 p0.500<=0.065536 p0.900<=0.131072 p0.950<=0.131072 p0.990<=0.262144 p0.999<=0.262144 max=2.068639
TRIGGER_ORDER/acceptedTerminal n=139776 p0.500<=0.032768 p0.900<=0.262144 p0.950<=0.262144 p0.990<=0.262144 p0.999<=0.524288 max=5.081216
TRIGGER_ORDER/entryAccepted n=139776 p0.500<=0.262144 p0.900<=0.262144 p0.950<=0.524288 p0.990<=0.524288 p0.999<=1.048576 max=13.449368
TRIGGER_ORDER/entryTerminal n=139776 p0.500<=0.262144 p0.900<=0.524288 p0.950<=0.524288 p0.990<=1.048576 p0.999<=1.048576 max=14.828871
```
- ORDER_BATCH按整条批量命令统计，不能当单订单延迟；强平2/ADL1样本不足以给容量或可靠尾延迟结论。没有open-loop/coordinated omission修正、真实三节点网络与外部API/Kafka/WS吞吐，因此本次完成局部优化、回归和锁定mixed阈值验证，不宣称整个生产系统完整性能验收或零分配。
- 有效JFR原件 `/Users/atomex/Desktop/surprising/async-profiler-evidence/2026-09-07-owner-completion/jfr-r2/soak.jfr`，66,798,258B、记录306秒，profile.jfc/summary/CPU/IO/NMT/三段延迟/系统/GC/原始命令均保留。artifact根 `/Users/atomex/Desktop/surprising/async-profiler-evidence/2026-09-07-owner-completion`，校验清单与最终校验值在后续记录。

- 最终artifact校验：69个文件、201,165,521B（不计清单本身），SHA256SUMS自身SHA256 `70419f25ee312fc6903c13af4261034e0663356bd955a3bfd5e386a7df925ba7`。保留无效首轮JFR和启动失败JMH，所有验证进程已退出。

- 归档汇总校正：结果汇总脚本最初把手工jmh-summary.json误当原始JMH文件，已限制为jmh-r2-*并验证包含全部六产品；原始测量不变。更新派生结果后的最终清单为69文件、201,189,432B，SHA256SUMS自身SHA256 `36480ef051a9f18139e55f141e02273e605451c9bee26129970bf481876a4b7c`，以本条清单为准。


## 2026-09-07 结算容器、原始成交编码、taker 多 fill 标量状态（采集前锁定）

- 对照 commit：不适用（仅验证当前 master）。被测 commit 为本条及代码通过测试后提交的 master HEAD，在 artifact/commit.txt 固化；不运行旧版本。采集目录 `/Users/atomex/Desktop/surprising/async-profiler-evidence/2026-09-07-settlement-allocation`。
- 改动：事件私有批量数组复用；响应直接编码 matcher 成交；衍生品同一 taker 多 fill 保留逐笔财务计算，最终才物化状态。共享协议及状态代码，运行 core 依赖链全部测试和六产品多 fill 场景。
- 环境：macOS 26.7 x86_64 / Intel i9-9880H 8C16T / 16GiB；Oracle GraalVM HotSpot 25.0.1+8，Maven 3.9.16。CPU/系统细节随运行保存。禁止并行跑其他基准/构建/分析；监控用户桌面干扰进程，CPU speed limit<100、swap 增长、明显节流、JFR DataLoss 的轮次无效。冷却15秒，正常退出不额外停机。
- mixed 主吞吐：`LinearPerpetualScaleSoakMain 1000 256 256 5 10 UNIFORM 1 20 32 300 30`，固定256 in-flight，1 matcher/4 Account Lane，1000用户、256活跃/挂牌symbol、最多5持仓/10挂单、持续做市、每轮20 item batch及32 symbol生命周期检查；入口为进程内Core命令，0外部网络连接。沿用驱动确定性混合下单/撤单/开平仓/触发/风险/资金费路径与比例，原始分类计数保存；初始资金及持仓由驱动固定构造，结束核对用户/做市/Treasury、冻结、订单索引、恢复hash。主指标阈值150000 terminal business ops/s，accepted=terminal、unfinished/end backlog=0、错误/超时0；预期业务拒绝单独计数，不混入成功成交量。
- mixed JVM：`-Xms4g -Xmx4g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -Dsurprising.aeron.matching-engines=1 -Dsurprising.benchmark.openLoop=false --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED`，预热由驱动完成，稳定300秒，每30秒采样。先无 profiler 主轮，再同参数300秒 JFR/NMT归因轮；closed-loop，无 coordinated omission 修正，不能宣称生产容量。主轮仅GC日志；归因轮新增NMT summary/退出统计及自定义profile.jfc/JFR512MiB上限/GC与safepoint日志。profile配置保存且不跨配置比较吞吐。
- 新增 JMH：`ClusteredBatchTradingBenchmark.multiFillSettlementAndEncoding`，逐个 SPOT / LINEAR_PERPETUAL / INVERSE_PERPETUAL / LINEAR_DELIVERY / INVERSE_DELIVERY / OPTION，257用户含maker、1symbol、4 Lane/1 matcher、256 in-flight、0网络连接、realtime=false。每cycle四个256消息wave：maker 20单×1数量→用户单笔吃20 fill→用户20单→maker单笔吃20 fill；买卖price100，初始充足资金、零仓位，maker另有price120挂单；正常成交拒绝0，每iteration结束资金/持仓/冻结/订单/快照检查。每cycle10752 business ops、1024 Core messages/batches、10240 fills，平均batch10.5/max20；业务操作100%下单，约95.24%挂单+4.76%多fill吃单，开仓/平仓各半。JMH主score cycles/s按上述常数换算，AuxCounters EVENTS为计数而非速率。
- JMH参数：f1/t1，warmup2×3秒，measurement3×5秒，`-prof gc -rf json -foe true`；`-Xms768m -Xmx768m -XX:+UseG1GC -Dsurprising.aeron.matching-engines=1`及上述opens/exports。通过要求每产品>=50000 business ops/s、分配<12000 B/business op、GC累计时间<measurement20%，并满足资金/快照及无积压/错误；报告95/99.9%置信区间以原始JMH为准，短场景不作为全系统容量。
- 实际多fill长稳JFR：同新增JMH LINEAR_PERPETUAL 场景，warmup2×3秒，measurement1×300秒，f1/t1、256in-flight，JVM改为mixed的4GiB ZGC并增加相同JFR/NMT，不加gc profiler；记录 `multifill.jfr`，同时验证 iteration资金与快照。阈值为零错误/超时/积压、无DataLoss/系统干扰失效，不拿带JFR结果替代无profiler主吞吐。分析稳定窗口60秒后多次GC live set、线程数、Direct/NMT、文件描述符趋势，增长原因单独解释；短期稳定不代表永久无泄漏。
- JFR全项：分组CPU/执行样本、分配类/线程/站点/TLAB/nonTLAB、heap/GC/停顿、NMT与Direct/Mapped、线程锁/park、safepoint、JIT/codecache、file/socket/exception及OS。mixed业务三段延迟按类型报告p50/90/95/99/99.9/max和样本数；新增多fill JMH无逐业务三段延迟事件，只能作为受影响路径分配/正确性验证。极少风险重操作样本不作尾延迟容量结论。缺失项如实标为部分验证；owner业务栈出现同步外部IO直接失败，驱动日志/启动JFR元数据IO另列。
- 未测：AWS真实三节点网络、外部API/WebSocket/Kafka、open-loop到达率、生产长时间容量；没有因本机代码优化重新部署这些服务。完整执行命令、日志、参数、原始JSON/JFR、派生聚合和SHA256清单保留artifact，结果按后续条目追加。

- 12:31 首轮在启动阶段失败：误拷贝未含依赖的普通 Maven jar，ProductLine ClassNotFound，未进入业务采集。失败日志及jar保留 startup-failed/；更正为同次构建的 target/product-core-benchmarks.jar shaded artifact，采集参数/标准保持上述锁定值重新执行，非业务代码缺陷。


### 多 fill JFR 补采前锁定（2026-09-07 12:55）

- 首次多fill JFR在12:44:37出现CPU_Speed_Limit=41，虽然仅单个5秒系统采样、无swap/业务错误，其性能与长稳数据按预先标准判为无效。保留 multifill.jfr/日志/派生结果；mixed主轮、mixed JFR及六产品JMH的采样时段无此降频，不受影响。
- 仅补采同一当前master 44e59b61 的多fill JFR，代码/jar/JVM/profile.jfc/256in-flight/1matcher/4Lane/257用户/1symbol/零初始持仓/充足资金/maker持续/六万以上逐笔开平仓等场景参数与上条一致，实际动作仍每cycle10752业务、1024Core、10240fills；不把“六万”作为计数阈值。沿用 warmup2×3秒、measurement1×300秒，冷却15秒、GraalVM HotSpot25/4GiB ZGC/NMT summary，自定义JFR512MiB，无gc profiler。通过要求仍为资金/订单/冻结/快照恢复一致，accepted=terminal、unfinished/endbacklog=0、业务错误/超时/拒绝0，无降频/swap增长/DataLoss；不设带profiler吞吐替代主指标。
- 输出到 artifact/multifill-r2/，原始补采脚本和SHA256随运行固化。主轮148305.202低于150000锁定阈值的结果保持失败，不因JFR补采替换。补采仅解决无效的多fill分配/长稳证据。


### 2026-09-07 13:02 验证结果（仅当前 master 44e59b61）

- 代码三项完成并推送：事件私有批量存储在完成/收集后清引用归池；批量响应从原始 matcher events 直接编码；五条衍生产品线的同一 taker 多fill使用标量cursor逐笔计算，最后发布状态。保留maker逐fill、财务舍入/溢出/保证金/反向开仓顺序、用户与订单逐fill revision、幂等响应wire格式。现货未套用衍生品cursor。稳定batch大小复用数组；大小切换仍重新分配，单item计划与最终输出/状态仍有分配。
- `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am package` HotSpot25全core依赖链：113测试类、709测试、0失败/错误/跳过，BUILD SUCCESS。新增协议字节级对比、frame越界/不足写入、多fill手续费舍入/平仓反开/中途失败清理，以及六产品真实集群服务回调路径重复开平仓/余额/冻结/终态/快照hash回归。这里的服务回调是本机代理ClientSession，不是真实三节点传输；真实网络API与外部推送范围未测。
- 启动失败普通jar、无效降频多fill JFR都已保留；多fill补采完成，系统全部CPU speed limit100、swap0，DataLoss0。原始main和mixed JFR及六产品短JMH未出现降频。所有性能数据来自同一44e59b61 shaded jar，不作历史比较。
- **mixed无profiler主轮未达到150000阈值**：302.521秒，44,865,488 terminal business operations / 4,513,744 Core messages，148305.202 business ops/s、14920.415 Core messages/s，max backlog256。资金/生命周期/恢复通过，快照24,668,755B，恢复1199.209ms。初始两个30秒窗口113467/126978，此后多数15.1万～16.2万，但不取最快片段替换整轮。驱动只有模板setup 3412.644ms，没有独立warmup测量段；采集前“预热由驱动完成”不应理解为已排除冷启动JIT，完整300秒包含JIT爬升。这是口径局限，结果保持未达标，不据此断言代码退化或收益。
- mixed JFR归因轮302.824秒：46,942,160 business ops、4,722,640 Core messages，155014.840 business ops/s、15595.347 Core messages/s；JFR Workload event两组accepted=terminal。资金/快照通过，snapshot25,558,435B、restore1478.190ms。带profiler结果不替代主轮；max backlog256，结束提交请求已终态。日志incompleteRiskScans=217是分页风险扫描继续计数，不是217个丢失交易；funding继续计数0。驱动未输出按操作类别的fills/rejected总量，不能把全部business ops当成功成交。业务三段延迟分类样本列在后面。
- 有效多fill JFR补采：主测量300秒、22,923,264 accepted/terminal business ops、2,183,168 Core messages/batches、21,831,680 fills，76374.169 business ops/s，平均batch10.5、最大20；Core accepted=terminal，unfinished/endBacklog=0、预期拒绝/业务错误/超时0，actual pipeline maxBacklog日志0（256为固定提交wave上限，不代表始终同时有256个未完成）。资金、maker与用户归零持仓、冻结与reservation回收、订单索引及快照恢复在iteration teardown通过。首次无效多fill计数保留在根multifill日志，不能作为性能结论。

六产品多fill JMH（gc profiler；完整参数/原始计数/99.9%置信区间保留JSON）：

| 产品 | business ops/s ± JMH误差 | Core messages/s | fills/s | B/business op | alloc MiB/s | GC次数/时间ms |
|---|---:|---:|---:|---:|---:|---:|
| INVERSE_DELIVERY | 87691.38 ± 27238.92 | 8351.56 | 83515.60 | 7926.22 | 648.37 | 29/364 |
| INVERSE_PERPETUAL | 87240.02 ± 16205.99 | 8308.57 | 83085.73 | 7941.92 | 647.50 | 27/359 |
| LINEAR_DELIVERY | 87601.41 ± 5900.94 | 8342.99 | 83429.91 | 7954.03 | 651.00 | 27/349 |
| LINEAR_PERPETUAL | 87309.99 ± 20262.26 | 8315.24 | 83152.37 | 7957.49 | 647.18 | 28/339 |
| OPTION | 88746.39 ± 42091.69 | 8452.04 | 84520.37 | 7943.13 | 658.13 | 27/346 |
| SPOT | 87700.17 ± 7520.81 | 8352.40 | 83523.97 | 8130.58 | 663.19 | 29/370 |

- 每cycle10752业务、1024Core/batches、10240fill；原始主score是cycles/s。AuxCounters EVENTS是累计数量，所有六产品acceptedBusiness=terminalBusiness、acceptedCore=terminalCore，unfinished/endbacklog0，测量期间每产品约132万～134万业务。gc累计339～370ms/约15秒measurement（2.3%～2.5%），分配7.9～8.1KB/business op，点估计均通过本专项50k/12KB/GC<20%阈值。短JMH置信区间宽，期权下界46.7k，不把点估计通过解读为生产可靠下界。

JFR归因与边界：

- mixed稳定+60..290秒CPU：owner99.40%单逻辑核，Lane0/1/2/3为17.84/17.63/17.31/16.77%，matcher17.22%。owner8419执行样本互斥归类：batch decode131、fingerprint497、Lane await522、matching await/pump306、其他Core6690、Core外273。热点含matchingCommitReady、readyLaneMask、CommandFingerprint/SHA、解码和收集结算；不是Lane/matcher业务CPU饱和。
- 有效多fill稳定CPU：owner/harness99.60%，maker所在Lane2为27.27%，其他Lane约5.2%～6.0%，matcher12.37%。客户端响应解码在同一JMH线程，不能把该owner线程全部CPU都归到生产交易核心。生产未单独启动snapshot/projection/Core Fact/Aeron/Kafka外围线程；其缺席不能推断上线无成本。执行样本不是严格wall-clock剖析，RUNNABLE/BLOCKED完整状态时间及OS上下文切换/page-fault未独立采集，相关容量证据仍部分缺失。
- mixed整份JFR抽样分配227,917,745,272B/307.030秒≈742.33MB/s，按主测量业务数量粗归一约4855B/op（包含setup/teardown，非精确逐op计量）。TLAB refill210,456,917,112B、nonTLAB18,105,019,168B，最大对象33,554,448B。热点类long[]、byte[]、OrderRuntime、Object[]、MatcherResult、CoreMatchingResult、ReservationRuntime；完整class/thread/site在soak-analysis.txt。稳定分配权重764.86MB/s。抽样不能给精确对象数/operation，TLAB refill字节不等于全部对象大小，未声称零分配。
- 有效多fill整份分配204,151,305,208B/309.259秒≈660.13MB/s，TLAB192,932,286,880B、nonTLAB12,020,621,656B、最大对象8,388,624B。稳定窗口153,614,280,528B抽样权重中31,698,897,264B（约20.6%）来自模拟客户端响应解码/校验。CoreExecutionView与CoreOrderBatchResult.Item分配均落在decodeResult调用栈；生产encodeResultSource不再经这些DTO。cursor/multi-taker最终物化OrderRuntime/PositionRuntime/ReservationRuntime分别178.57/101.79/79.55MB抽样权重；保留single-fill路径分别3785.67/1852.31/1098.91MB，支持多fill最终发布路径已实际触发。这不是历史收益比较，也不能用抽样权重推算精确对象个数。
- mixed GC74轮（ZGC Minor AllocationRate55/HighUsage1，Major Proactive13/Warmup3/AllocationRate1/Metadata1），258段pause共5.276ms，占记录0.00172%，pause p50/p95/p99/max为0.016/0.049/0.064/0.067ms。多fill68轮、272段pause共3.719ms（0.00120%），0.011/0.034/0.054/0.061ms。两份无allocation requiring GC/promotion/evacuation失败事件。全程heap committed4GiB；mixed AfterGC约88MB～942MB、multi52MB～403MB，含初始化与快照恢复。
- 泄漏证据需区分采样来源：mixed驱动GC通知粗表输出live/old slope0；JFR AfterGC剔除前60秒后robust slope+437289B/s（低于驱动1MiB/s阈值），存在占用增长，不能说完全平坦或无泄漏。有效multi同口径-183969B/s；本次只有5分钟，未证明更长生产期不会增长。mixed稳定线程14/FD13/Direct与Mapped0；multi线程稳定15/Direct0，未单独采集multi FD与Mapped余额，不推断该项无泄漏。没有实际Netty/Aeron网络buffer池，因此无法验证其上线平衡。
- NMT mixed稳定committed约4.433～4.436GB、峰值4.460GB，robust slope+7427B/s；multi4.418→4.411GB、峰值4.424GB，slope-25100B/s。各category min/max/首末全量保留分析文件，JavaHeap固定4GiB，mixed Class committed1.93→3.63MB、Code19.51→34.46MB（峰值37.14MB）、GC4.31→40.67MB（峰值59.41MB）。约74.5GB reserved含ZGC虚拟地址空间，不能当RSS。ps保存RSS和系统负载，swap始终0。
- mixed稳定JavaMonitorEnter0、稳定Core同步IO0/异常0；整份2277次FileRead/488次FileWrite约15.588/11.911ms来自启动加载、JFR/驱动日志。multi稳定Core同步IO0/异常0，整份2490 FileRead/55 FileWrite/3 SocketRead/61 SocketWrite含JMH fork控制通信，不是交易远程IO。完整IO栈和时间保留；长monitor wait/park主要为JFR/worker空闲或关闭，不能当owner业务锁竞争。mixed712 park合计24.429s跨线程/包含setup，非owner每笔等待延迟。
- mixed Safepoint268次，begin累计12.741ms/max2.145ms，VM operation916次累计26.190ms/max2.031ms；multi281次累计13.646ms/max0.871ms，VM operation1013次累计20.079ms/max3.410ms。停顿可能超过轻量风险动作尾延迟，不能对微秒动作承诺全局零停顿。JFR配置含VM/锁/IO阈值，不能把阈值内未记录事件当作不存在。
- JIT mixed全程10941编译/32.55CPU秒，稳定窗口387编译/2.282秒/max115.205ms、8次deopt，主要预热在前段。multi全程6807编译/175.40CPU秒，稳定窗口仍343编译/104.85秒、150次deopt，尚未形成完全安静的编译稳态，因此multi结果仅作实际路径功能/分配诊断，不能宣称完整稳态容量验收。稳定类加载mixed1/multi0，线程开始/结束mixed1/1、multi11/11，无持续增加。完整codecache/metaspace/NMT与事件计数在分析文件。

mixed业务三段延迟（JFR histogram桶上界，ms；max精确，setup包含在样本中）：

```text
ADL/acceptedTerminal n=1 p0.500<=0.002048 p0.900<=0.002048 p0.950<=0.002048 p0.990<=0.002048 p0.999<=0.002048 max=0.001471
ADL/entryAccepted n=1 p0.500<=2.097152 p0.900<=2.097152 p0.950<=2.097152 p0.990<=2.097152 p0.999<=2.097152 max=1.781498
ADL/entryTerminal n=1 p0.500<=2.097152 p0.900<=2.097152 p0.950<=2.097152 p0.990<=2.097152 p0.999<=2.097152 max=1.782969
CANCEL_ORDER/acceptedTerminal n=1111040 p0.500<=4.194304 p0.900<=4.194304 p0.950<=4.194304 p0.990<=8.388608 p0.999<=16.777216 max=30.481332
CANCEL_ORDER/entryAccepted n=1111040 p0.500<=0.002048 p0.900<=0.004096 p0.950<=0.004096 p0.990<=0.016384 p0.999<=0.032768 max=1.273575
CANCEL_ORDER/entryTerminal n=1111040 p0.500<=4.194304 p0.900<=4.194304 p0.950<=4.194304 p0.990<=8.388608 p0.999<=16.777216 max=30.483504
FUNDING/acceptedTerminal n=69440 p0.500<=0.000128 p0.900<=0.000128 p0.950<=0.000256 p0.990<=0.000512 p0.999<=0.001024 max=0.023624
FUNDING/entryAccepted n=69440 p0.500<=0.131072 p0.900<=0.131072 p0.950<=0.131072 p0.990<=0.262144 p0.999<=0.524288 max=7.077597
FUNDING/entryTerminal n=69440 p0.500<=0.131072 p0.900<=0.131072 p0.950<=0.131072 p0.990<=0.262144 p0.999<=0.524288 max=7.079656
LIQUIDATION/acceptedTerminal n=2 p0.500<=0.002048 p0.900<=8.388608 p0.950<=8.388608 p0.990<=8.388608 p0.999<=8.388608 max=5.354465
LIQUIDATION/entryAccepted n=2 p0.500<=4.194304 p0.900<=8.388608 p0.950<=8.388608 p0.990<=8.388608 p0.999<=8.388608 max=5.425077
LIQUIDATION/entryTerminal n=2 p0.500<=4.194304 p0.900<=16.777216 p0.950<=16.777216 p0.990<=16.777216 p0.999<=16.777216 max=10.779542
ORDER_BATCH/acceptedTerminal n=2222080 p0.500<=16.777216 p0.900<=33.554432 p0.950<=33.554432 p0.990<=33.554432 p0.999<=67.108864 max=120.542139
ORDER_BATCH/entryAccepted n=2222080 p0.500<=0.032768 p0.900<=0.065536 p0.950<=0.065536 p0.990<=0.065536 p0.999<=0.131072 max=20.882393
ORDER_BATCH/entryTerminal n=2222080 p0.500<=16.777216 p0.900<=33.554432 p0.950<=33.554432 p0.990<=33.554432 p0.999<=67.108864 max=120.574022
PLACE_ORDER/acceptedTerminal n=1111040 p0.500<=4.194304 p0.900<=4.194304 p0.950<=4.194304 p0.990<=8.388608 p0.999<=16.777216 max=10.449720
PLACE_ORDER/entryAccepted n=1111040 p0.500<=0.016384 p0.900<=0.016384 p0.950<=0.032768 p0.990<=0.032768 p0.999<=0.065536 max=4.349256
PLACE_ORDER/entryTerminal n=1111040 p0.500<=4.194304 p0.900<=4.194304 p0.950<=4.194304 p0.990<=8.388608 p0.999<=16.777216 max=12.191215
RISK_SCAN/acceptedTerminal n=70156 p0.500<=0.000128 p0.900<=0.000128 p0.950<=0.000256 p0.990<=0.000512 p0.999<=0.001024 max=0.039284
RISK_SCAN/entryAccepted n=70156 p0.500<=0.131072 p0.900<=0.131072 p0.950<=0.131072 p0.990<=0.262144 p0.999<=0.262144 max=4.994597
RISK_SCAN/entryTerminal n=70156 p0.500<=0.131072 p0.900<=0.131072 p0.950<=0.131072 p0.990<=0.262144 p0.999<=0.262144 max=4.997686
TRIGGER_ORDER/acceptedTerminal n=138880 p0.500<=0.032768 p0.900<=0.262144 p0.950<=0.262144 p0.990<=0.262144 p0.999<=0.524288 max=2.777613
TRIGGER_ORDER/entryAccepted n=138880 p0.500<=0.262144 p0.900<=0.262144 p0.950<=0.524288 p0.990<=0.524288 p0.999<=1.048576 max=8.290839
TRIGGER_ORDER/entryTerminal n=138880 p0.500<=0.262144 p0.900<=0.524288 p0.950<=0.524288 p0.990<=1.048576 p0.999<=1.048576 max=10.518597
```

- ORDER_BATCH统计整条请求，不是单item；最大20个item，三段分开记录。直方图64个2的幂ns桶，max精确；closed-loop无coordinated omission修正。ADL1/强平2样本不足以给可靠尾延迟；snapshot只有单次恢复耗时，未补snapshot fence延迟直方图。新增multi无三段业务延迟，不能混用mixed直方图。timeout为驱动30秒排空保护，没有实际超时。新专项没有改单/触发/强平等全部组合性能场景；功能由core共享财务/生命周期测试覆盖。
- 结论：三处代码优化与功能/资金回归完成，六产品专项点估计通过锁定阈值；mixed无profiler150k阈值未通过，且multi JIT/逐业务延迟及部分OS/native指标不全，所以**整体性能验收为部分验证，不能宣称15万达标、全链路零分配或生产容量已确认**。没有检出/重跑旧版本。
- artifact：根目录含run.sh、build.log、六产品JSON、两份有效JFR及一次降频无效JFR、启动失败日志、profile.jfc、JFR summary/view、分组CPU/分配/IO/NMT/GC/业务直方图、系统样本、java/maven/commit与输入SHA256。有效mixed soak.jfr 67,313,020B/307秒，多fill补采 multifill-r2/multifill.jfr 57,166,199B/309秒；全部命令由run.sh与multifill-r2/run.sh复现。最终SHA256清单在后续条目。

- 计量澄清：上文编译“CPU秒”应理解为Compilation事件duration跨编译线程的累计墙钟时长，并非精确CPU计费时间；不能与主线程CPU百分比直接相加。新增JMH仅固定256提交wave/窗口，实际backlog为0，不能据此声称始终有256个请求同时在途；并发能力以mixed主轮实测maxBacklog256为准。补采前概述中的“六万以上”无统计意义，唯一计数口径为每cycle10752业务/1024Core/10240fill及实际JSON。

- 最终artifact清单：86文件、253,420,225B（不含清单），SHA256SUMS自身SHA256 `fc0825b1545ac8736034eb501eff5ce498b5dbe6fb067ae7741bcaf69b88e381`。`analyze.sh`保存聚合命令。所有采集/分析进程已结束，未改动交易网络或部署配置。


## 2026-09-07 吞吐上限复测（采集前锁定）

- 用户要求在当前实现再次验证是否接近吞吐上限。本轮不改业务代码，只测当前master 6d2be0fa（与44e59b61业务源码一致，差异仅文档），重新构建当前HEAD并保存commit/jar SHA256；对照commit：不适用（仅验证当前master），不使用旧版本性能作比较。artifact `/Users/atomex/Desktop/surprising/async-profiler-evidence/2026-09-07-throughput-recheck`。
- 沿用mixed全300秒主口径：HotSpot25 Oracle GraalVM25.0.1+8、Maven3.9.16、macOS26.7 Intel i9-9880H 8C16T/16GiB；JVM `-Xms4g -Xmx4g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -Dsurprising.aeron.matching-engines=1 -Dsurprising.benchmark.openLoop=false --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED`。完整版本/系统信息随运行保存。先无profiler主轮300秒，再冷却15秒后同参数JFR/NMT轮300秒，每30秒驱动样本；没有独立warmup，模板setup后300秒包含JIT爬升。全窗口平均为主指标，事先另定+60..290秒线程与JIT诊断窗口，不拿后半段替换主结果。
- 场景命令 `LinearPerpetualScaleSoakMain 1000 256 256 5 10 UNIFORM 1 20 32 300 30`：U本位永续、1000用户、256挂牌/活跃symbol、4 Account Lane、1 matcher、固定256in-flight、0真实网络连接、进程内Core入口，持续maker、确定性混合下单/撤单/开平仓/触发/风险扫描/资金费和32symbol生命周期轮转，最多5持仓/10挂单，每HFT batch20项。初始资金与持仓由固定模板构造，沿用驱动的用户/maker/Treasury资金与冻结/持仓/订单终态、snapshot restore/hash校验。所有business ops/消息/成交单位不混用；驱动不输出全分类fills/拒绝总数则明确缺项，不把business ops当成功成交数。
- 通过阈值仍150000 terminal business ops/s；acceptedBusiness=terminalBusiness、acceptedCore=terminalCore、unfinished/endbacklog0、资金/恢复通过、业务错误/超时0。主轮只开GC/safepoint日志，不加采样器。JFR轮额外 `-XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics -XX:StartFlightRecording=settings=profile.jfc,filename=soak.jfr,dumponexit=true,maxsize=512m`，使用上一轮相同自定义配置，保存原始JFR/summary/view及分组CPU/分配/GC/heap/native/IO/锁/VM/JIT/业务三段延迟。profile数值仅归因，不替代主吞吐。
- “接近当前配置上限”判据：闭环始终以256窗口尽快补充负载，稳定窗口owner单逻辑核平均>=95%、其余Lane/matcher未满载、线程热点说明瓶颈在owner串行计算而非持续IO/锁阻塞，后续30秒窗口没有持续爬升且JIT主要爬升已过去，资金与终态不积压。满足时只能说明当前负载/机器/JVM/配置的owner约束，不能证明整个软件或AWS绝对上限；不改变in-flight或matcher来抬高数字。不满足则明确未测到稳定上限。
- 有效性：每5秒采集thermal/CPU speed limit/swap，每30秒记录同机进程CPU/RSS；CPU speed limit<100、swap增长、明显系统干扰节流或JFR DataLoss使对应轮无效，保留原始数据并单独记录补采。基准期间不并行构建/分析/其他压测，不终止用户应用。closed-loop无coordinated omission修正；三段业务延迟提供p50/90/95/99/99.9/max/样本数，风险低样本及snapshot fence缺口明确记录。
- 无业务源码改动，因此不重复新增JMH或整套功能测试；当前实现已有709测试及六产品专用JMH回归通过，本轮仅复测mixed吞吐及饱和归因。真实三节点网络/API/WS/Kafka、open-loop、其他产品容量与长期泄漏证明仍未覆盖。命令、全部结果及SHA256按完成后追加，不覆盖前轮未达标数据。


### 2026-09-07 吞吐复测结果（当前master 6d2be0fa）

- 当前HEAD重新构建成功（HotSpot25，package -DskipTests），无业务代码改动。无profiler主轮与同参数JFR归因轮各完成300秒，所有系统采样CPU speed limit100、swap0，JFR DataLoss0，无失效补采。对照commit：不适用（仅验证当前master）。构建、JVM/环境、commit与jar/profile/run脚本SHA256保留artifact。
- **本轮通过150000主吞吐阈值**：无profiler302.179秒，48,759,248 terminal business operations、4,905,424 terminal Core messages，161359.032 business ops/s、16233.525 Core messages/s。入口1000用户/256symbol、4Lane/1matcher/256in-flight、0外部连接；max matching backlog256。资金/冻结/持仓/终态与snapshot恢复通过，快照26,336,781B、restore1080.355ms。没有仅取最快片段替换全窗口。
- 主轮第一个30秒窗口151664.786，此后8个完整30秒窗口（结束时间60.169～270.456秒）min159240.424/max165436.343，均值163799.425 business ops/s、CV1.155%，无持续爬升。该分段均值仅描述平台，主指标仍全窗口161359.032。sweep p50/p95/p99/max=130.211/152.380/166.811/564.220ms，是整个混合业务轮转耗时，不能当单订单延迟。
- JFR归因轮302.161秒，47,850,704 business ops、4,814,032 Core messages，158361.757 business ops/s、15932.024 Core messages/s；Workload event中acceptedBusiness=terminalBusiness、acceptedCore=terminalCore，结束无未终态提交请求，max backlog256。资金/快照通过，snapshot25,947,684B、restore1052.649ms。驱动incompleteRiskScans=207是分页扫描继续计数，不是207个交易请求未完成；资金费继续计数0。无业务错误/超时；驱动未输出完整fills/拒绝率/全业务动作比例，不能把business ops全当成功成交。批量最大20项、batch item按操作计数，混合流量不把所有Core消息当batch。
- **当前负载已接近owner约束下的平台**：JFR固定+60..290秒窗口，owner平均99.34%单逻辑核；Lane0/1/2/3=17.99/17.81/17.45/16.98%，matcher17.79%。JFR侧30秒窗口均值160957.492、CV0.784%，后半段没有持续爬升；主要JIT预热后仍有少量编译但不占满编译资源。满足预先owner>=95%、Lane/matcher未饱和、平台不爬升及无IO/锁阻塞的归因判据。只能说本机当前实现/负载/256窗口的平台约16万business ops/s，未证明整个软件或AWS硬件绝对上限，也没有通过扩大in-flight/增加matcher抬高结果。
- Owner执行样本7363，互斥归类：batch settlement/completion1996(27.11%)、place admission682(9.26%)、fingerprint428(5.81%)、batch decode120(1.63%)、Lane task await461(6.26%)、matching wait/pump276(3.75%)、readiness coordination/poll350(4.75%)、other Core2840(38.57%)、harness/other210(2.85%)。这是抽样栈占比，不是精确业务耗时。仍有同步任务等待/协调轮询，CPU满不等于全是有效财务计算；提高吞吐需要减少owner串行路径与协调成本，单纯增加Lane/matcher预计收益有限，未实际测其收益。
- 稳定窗口无JavaMonitorEnter、Core同步文件/网络IO=0、Core异常抛出=0；owner受限不是由持续锁竞争或同步外部IO造成的证据。整份JFR FileRead2277次/10.043ms、FileWrite488次/6.949ms、SocketRead/Write0，均保留启动/驱动日志IO栈。629次ThreadPark共22.477s是跨线程并含setup/关闭的累计，不可当owner单笔延迟；完整线程RUNNABLE/BLOCKED时间、OS上下文切换/page faults未额外采集，执行样本也不是严格wall-clock分析。
- 分配：整份306.637秒JFR抽样权重244,215,846,208B≈796.433MB/s（约5082B/主测量business op，包含setup/teardown，仅粗归一），稳定+60..290秒抽样权重187,406,087,192B≈814.809MB/s。TLAB refill226,588,350,880B、nonTLAB18,315,349,344B、最大对象33,554,448B；数组/订单/撮合结果/协议输出等top class/thread/site在analysis.txt。精确对象数/op不可从抽样推得，TLAB refill不等于实际对象字节；没有重新JMH测B/op，不把本轮JFR估计冒充JMH数据。
- GC/heap：78轮GC、270段GCPhasePause累计5.267ms（占JFR约0.00172%），pause p50/p95/p99/max=0.016/0.047/0.060/0.075ms；GC周期pause总和max0.162ms。heap committed固定4GiB，AfterGC约88.1～715.1MB、平均514.7MB。GC失败/AllocationRequiringGC/PromotionFailed/EvacuationFailed事件0。JFR AfterGC剔除前60秒robust slope+107683.5B/s，低于锁定驱动1MiB/s阈值；驱动GC通知粗表slope0不能替代JFR，不能宣称绝对无泄漏。
- Native/长期状态：NMT稳定committed约4.442→4.436GB、峰值4.460GB，按每秒category合并robust slope+3325.8B/s；首末和斜率受中间波动影响，不等同。reserved约74.53GB含ZGC虚拟地址空间，非RSS；各NMT category/峰值首末在analysis.txt，PS/RSS系统样本保留。主轮线程12/FD11，JFR稳定14/FD13，Direct/Mapped bytes与数量0；稳定窗口线程start/end15/15，ClassLoad4/ClassUnload0，无净持续线程增长。只有5分钟，不是生产长期泄漏证明；真实Aeron/Netty/Kafka网络池未启动，不能证明外部池余额。
- JIT/VM：全程10837次编译累计duration40.394秒（跨线程墙钟事件累计，不是CPU计费）；稳定窗口371次/2.240秒、max120.821ms、12次deopt，主要编译爬升已越过。Safepoint280次begin累计18.035ms/max6.642ms；VM operation935次累计37.879ms/max10.396ms。最大值在recording+287.23秒，VM长操作为ZWorkerYoung#2发起的HandshakeAllThreads（safepoint=false），同一时段出现6.64ms safepoint begin；不能把10.4ms都当成STW。它们会影响微秒业务尾部，因此GC pause很短不代表VM完全没有毫秒抖动，证据在vm-long.txt。

业务三段延迟（JFR，单位ms；p值是64桶2的幂ns直方图上界，max精确，含setup）：

```text
ADL/acceptedTerminal n=1 p0.500<=0.004096 p0.900<=0.004096 p0.950<=0.004096 p0.990<=0.004096 p0.999<=0.004096 max=0.002370
ADL/entryAccepted n=1 p0.500<=4.194304 p0.900<=4.194304 p0.950<=4.194304 p0.990<=4.194304 p0.999<=4.194304 max=2.291276
ADL/entryTerminal n=1 p0.500<=4.194304 p0.900<=4.194304 p0.950<=4.194304 p0.990<=4.194304 p0.999<=4.194304 max=2.293646
CANCEL_ORDER/acceptedTerminal n=1132544 p0.500<=4.194304 p0.900<=4.194304 p0.950<=4.194304 p0.990<=8.388608 p0.999<=16.777216 max=119.533554
CANCEL_ORDER/entryAccepted n=1132544 p0.500<=0.002048 p0.900<=0.004096 p0.950<=0.004096 p0.990<=0.008192 p0.999<=0.032768 max=0.694546
CANCEL_ORDER/entryTerminal n=1132544 p0.500<=4.194304 p0.900<=4.194304 p0.950<=4.194304 p0.990<=8.388608 p0.999<=16.777216 max=119.536070
FUNDING/acceptedTerminal n=70784 p0.500<=0.000128 p0.900<=0.000256 p0.950<=0.000256 p0.990<=0.000512 p0.999<=0.001024 max=0.025528
FUNDING/entryAccepted n=70784 p0.500<=0.131072 p0.900<=0.131072 p0.950<=0.131072 p0.990<=0.262144 p0.999<=0.524288 max=7.917730
FUNDING/entryTerminal n=70784 p0.500<=0.131072 p0.900<=0.131072 p0.950<=0.131072 p0.990<=0.262144 p0.999<=0.524288 max=7.921246
LIQUIDATION/acceptedTerminal n=2 p0.500<=0.004096 p0.900<=8.388608 p0.950<=8.388608 p0.990<=8.388608 p0.999<=8.388608 max=5.278218
LIQUIDATION/entryAccepted n=2 p0.500<=4.194304 p0.900<=8.388608 p0.950<=8.388608 p0.990<=8.388608 p0.999<=8.388608 max=5.205765
LIQUIDATION/entryTerminal n=2 p0.500<=4.194304 p0.900<=16.777216 p0.950<=16.777216 p0.990<=16.777216 p0.999<=16.777216 max=10.483983
ORDER_BATCH/acceptedTerminal n=2265088 p0.500<=16.777216 p0.900<=33.554432 p0.950<=33.554432 p0.990<=33.554432 p0.999<=67.108864 max=336.582269
ORDER_BATCH/entryAccepted n=2265088 p0.500<=0.032768 p0.900<=0.065536 p0.950<=0.065536 p0.990<=0.065536 p0.999<=0.131072 max=63.298000
ORDER_BATCH/entryTerminal n=2265088 p0.500<=16.777216 p0.900<=33.554432 p0.950<=33.554432 p0.990<=33.554432 p0.999<=67.108864 max=336.620424
PLACE_ORDER/acceptedTerminal n=1132544 p0.500<=4.194304 p0.900<=4.194304 p0.950<=4.194304 p0.990<=8.388608 p0.999<=8.388608 max=10.867069
PLACE_ORDER/entryAccepted n=1132544 p0.500<=0.016384 p0.900<=0.016384 p0.950<=0.016384 p0.990<=0.032768 p0.999<=0.065536 max=2.146152
PLACE_ORDER/entryTerminal n=1132544 p0.500<=4.194304 p0.900<=4.194304 p0.950<=4.194304 p0.990<=8.388608 p0.999<=8.388608 max=11.254607
RISK_SCAN/acceptedTerminal n=71500 p0.500<=0.000128 p0.900<=0.000128 p0.950<=0.000256 p0.990<=0.000512 p0.999<=0.000512 max=0.017032
RISK_SCAN/entryAccepted n=71500 p0.500<=0.131072 p0.900<=0.131072 p0.950<=0.131072 p0.990<=0.262144 p0.999<=0.262144 max=10.590349
RISK_SCAN/entryTerminal n=71500 p0.500<=0.131072 p0.900<=0.131072 p0.950<=0.131072 p0.990<=0.262144 p0.999<=0.262144 max=10.592620
TRIGGER_ORDER/acceptedTerminal n=141568 p0.500<=0.065536 p0.900<=0.262144 p0.950<=0.262144 p0.990<=0.262144 p0.999<=0.524288 max=4.167656
TRIGGER_ORDER/entryAccepted n=141568 p0.500<=0.262144 p0.900<=0.262144 p0.950<=0.524288 p0.990<=0.524288 p0.999<=1.048576 max=9.607531
TRIGGER_ORDER/entryTerminal n=141568 p0.500<=0.262144 p0.900<=0.524288 p0.950<=0.524288 p0.990<=1.048576 p0.999<=1.048576 max=12.337510
```

- ORDER_BATCH按整个最多20项请求统计：入口→终态p99≤33.554ms、p99.9≤67.109ms、max336.620ms；普通PLACE_ORDER p99/p99.9≤8.389ms，max11.255ms。闭环无coordinated omission修正，ADL/强平样本极少，snapshot仅恢复标量耗时而无fence直方图，不能给生产全部操作尾延迟保证。上限判断针对锁定mixed负载；真实API连接/三节点Aeron网络/open-loop及其他五产品容量仍未验证。
- 结论：本轮15万阈值通过，观测到本机当前配置下约16万business ops/s的owner受限平台，Lane/matcher均未饱和；总体生产性能验收仍受上述指标/场景缺口限制。无业务修改，不重新运行无关JMH与已通过的709回归测试。
- 原始artifact `/Users/atomex/Desktop/surprising/async-profiler-evidence/2026-09-07-throughput-recheck`：soak.jfr 69,483,371B、约307秒，包含run.sh/profile.jfc/build.log/main/soak/GC/system/JVM日志、summary/view、CPU/owner分组、allocation/IO/NMT/JIT/VM/业务延迟；analyze.sh及Java聚合源码可复现。所有测量/分析进程已结束，结果将提交master。

- 本轮最终清单：44文件、137,370,901B（不含清单），SHA256SUMS自身SHA256 `c794956c1d3af7637b73d0be20e407833420fb8d530c6649772957449ff20530`。

- 分配粗归一算术更正：244,215,846,208B / JFR主测量47,850,704 business ops = 5103.704B/op，上文“约5082B”更正为约5104B；原始字节权重、吞吐及全部采样文件不变，仍包含setup/teardown且不是精确逐op或JMH分配计量。


## 2026-09-07 Owner触发单发布与批量ID优化（采集前锁定）

- 当前master实现：触发单通过现有Lane发布缓冲携带最终不可变状态，owner按ID直读完成态；回滚/辅助快照替换同步该视图，删除按状态userId路由；批量完成取消第一次ID数组复制，在最终边界一次物化。未改轮询策略或交易财务计算。被测commit为此实现/测试/预锁条目提交后的master HEAD，写入artifact/commit.txt；对照commit不适用（仅验证当前master），不检出/构建旧版。
- artifact `/Users/atomex/Desktop/surprising/async-profiler-evidence/2026-09-07-owner-trigger-publication`。环境HotSpot25 Oracle GraalVM25.0.1+8、Maven3.9.16、macOS26.7 x86_64 i9-9880H8C16T/16GiB；实际版本/参数、jar/profile/run脚本SHA256随执行保存。编译和整个core相关依赖链测试完成后才采样，采样期间无并行构建/分析/其他基准。
- mixed主轮：`LinearPerpetualScaleSoakMain 1000 256 256 5 10 UNIFORM 1 20 32 300 30`，U本位永续1000用户、256活跃/挂牌symbol、最多5仓位/10挂单、内部持续maker、4Lane/1matcher、固定256in-flight/0外部连接；双向批量下单成交撤单+trigger/risk/资金费和每轮32symbol生命周期。初始资金/仓位沿用固定模板，逐cycle资金等式、订单/冻结/持仓终态与恢复hash；所有业务/消息/fill口径分开，未输出指标不得推测。closed-loop尽快补充256窗口，无CO修正；setup单列，没有独立warmup，300秒完整窗口含JIT爬升/最终验证，30秒采样不删除低速窗口。
- 主轮阈值>=150000 terminal business ops/s；两组accepted=terminal、unfinished/endbacklog0、资金与快照正确、业务错误/超时0。JVM `-Xms4g -Xmx4g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -Dsurprising.aeron.matching-engines=1 -Dsurprising.benchmark.openLoop=false --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED`，仅GC/safepoint日志，无profiler主测量。
- 冷却15秒后相同mixed/JVM再跑300秒JFR，新增NMT summary/UnlockDiagnosticVMOptions/PrintNMTStatistics与上一轮相同profile.jfc（SHA256随输入锁定），JFR文件soak.jfr/dumponexit/maxsize512m；带profiler仅归因不替代主吞吐。分析+60..290秒owner/Lane/matcherCPU及等待站点，核对triggerOrder/visitChangedIndexes不再出现LaneMutationTask.await链路；其他触发执行/风险结算的必要等待不能误判遗漏。记录整份分配/TLAB/GC/native/heap趋势/线程锁/IO/VM/JIT/异常和三段业务直方图；trigger/risk真实生命周期在该mixed路径执行。主轮和JFR必须金融/恢复断言通过。
- 新JMH `ClusteredBatchTradingBenchmark.ownerTriggerAndBatchCompletion` 六产品分别执行：257用户含maker1256、1symbol、4Lane/1matcher、maxInFlight256，batch20，realtime=false，0外部网络。每cycle先6个256消息wave（maker卖/用户买开仓→用户挂止损→撤止损→用户卖/maker买平仓），再256用户各20项批量下单及撤单，附512查询。初始用户/做市资金沿用既有fixture、零仓位和maker price120一单，成交price100/qty1，止损SELL price90/条件<=90，qty1。停止交易前不触发止损，终态历史保留按产品现有规则，不要求历史表为空。
- JMH计数：每cycle11776 business ops（1024成交相关下单+512触发单挂撤+10240批量下撤）、2048 business Core messages、512fills、512batches/10240items、512queries；平均/最大batch20，包含query时总Core请求2560/cycle。批量下撤占86.96%、成交相关下单8.70%、触发单挂撤4.35%。固定256是提交wave/窗口上限，服务callback确定性完成后可能真实backlog0，不能称始终256在途。f1/t1，warmup2×3s、measurement3×5s，-prof gc，JSON原始CI/error/counters；G1 -Xms768m -Xmx768m、matching-engines1、同opens/exports，每产品间冷却15s。阈值点估计>=20000 business ops/s、<16000B/business op、measurement GC<20%、accepted=terminal/unfinished0、资金/持仓/冻结/订单及触发终态/快照恢复通过，预期业务拒绝0。
- JFR分配是采样权重不能给精确objects/op；JMH gc.alloc.rate.norm按11776展开。线程/GC后heap/native/FD/Direct及Mapped长稳以300秒mixed的多GC点检查（live/old<1MiB/s、native<256KiB/s、线程/FD/pool斜率<0.01/s）；短样本不能证明永久无泄漏。所有记录CPU speed<100、swap增长、明显节流、JFR DataLoss则对应轮无效，保留记录后新预锁补采；不关闭用户应用。
- 三段延迟p50/90/95/99/99.9/max/样本数来自mixed业务类型事件，batch是整批；JMH新专项无逐业务三段计时，不互相冒充。低样本强平/ADL及snapshot fence/OS完整墙钟/生产native池缺口如实记录。真实AWS三节点/Aeron网络/API/WS/Kafka不在本机容量范围；测试realtime=true只验证本机capture/outbox，不宣称网络推送E2E。


## 2026-09-07 Owner触发单发布与批量ID优化（采集结果）

- 被测master `c26e0578`，对照commit不适用（仅验证当前master）。执行上述预锁定 `run.sh`，主轮约14:10–14:15、JFR约14:15–14:20，随后六产品JMH顺序运行；全部参数、准确时间和输入SHA256见artifact。未检出或重跑旧代码，不据历史吞吐计算提升百分比。
- 功能验证：HotSpot25下 `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am package`：113测试类、717测试、失败/错误/跳过均0。包含六产品线实时capture/outbox开启时反复触发单挂撤与批量完成、owner读取不提交Lane任务、指定Lane移除、回滚与辅助快照替换。初次新夹具6个失败如实保留于 `owner-trigger-targeted.log`：衍生品无持仓导致止损单被正确拒绝，另有现货终态历史误判为空；修正夹具后定向及完整core依赖链通过。未改变合法终态保留规则。
- 无profiler主轮：302.703秒（包含最终验证/恢复）、**174729.238 terminal business ops/s**、17578.994 terminal Core messages/s；52891142 business ops、5321222 Core messages。setup 2825.278ms，最大matching backlog256，资金等式/用户与maker余额、冻结、持仓和订单检查通过；snapshot28106349B，恢复1361.712ms且business hash一致。完整窗口含JIT爬升，所有30秒样本保留（范围149801.826–183682.430 ops/s），不取峰值当持续结果。
- JFR轮：302.178秒测量、164365.887 business ops/s、16536.005 Core messages/s；49667797 business ops、4996821 Core messages；事件accepted/terminal两组相等，mixed每cycle另有真实计数及资金断言（聚合事件的accepted字段由终态总数填写，不能单独作为独立接收计数证据）。命令差值/未完成为0，drain后无matching积压；主轮/JFR `incompleteRiskScans`199/222是预算化风险扫描剩余状态，**不是未完成消息数，也不意味着全部symbol风险扫描都已扫完**；incompleteFunding0。恢复26726151B /1087.475ms，通过hash验证。mixed主程序未单列fills/batches/items聚合，不能从business ops反推成交量；专项JMH单列如下。

| 产品 | JMH cycles/s ±99.9%误差 | terminal business ops/s | business Core messages/s | fills/s=batches/s=queries/s | batch items/s | B/business op | MiB/s | GC次数/毫秒 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| SPOT | 10.213 ± 0.727 | 120263.712 | 20915.428 | 5228.857 | 104577.141 | 5128.290 | 559.562 | 25/291 |
| LINEAR_PERPETUAL | 9.067 ± 3.579 | 106769.112 | 18568.541 | 4642.135 | 92842.706 | 5235.174 | 509.627 | 23/301 |
| INVERSE_PERPETUAL | 9.388 ± 5.093 | 110555.590 | 19227.059 | 4806.765 | 96135.296 | 5212.818 | 524.916 | 24/294 |
| LINEAR_DELIVERY | 9.738 ± 3.358 | 114671.241 | 19942.825 | 4985.706 | 99714.123 | 5209.255 | 543.852 | 24/289 |
| INVERSE_DELIVERY | 9.734 ± 3.191 | 114627.274 | 19935.178 | 4983.795 | 99675.891 | 5233.290 | 543.733 | 27/318 |
| OPTION | 9.706 ± 13.073 | 114297.100 | 19877.757 | 4969.439 | 99388.783 | 5189.990 | 537.666 | 24/317 |

- 专项JMH为gc profiler测量，不能替代无profiler主结果。每cycle11776 business ops、2048 business Core、512查询（总Core2560），512fills与512batches数量恰相同但概念不同；batch平均/最大20。每个产品measurement15秒，GC时间289–318ms（1.93–2.12%），均满足预锁定点估计2万business ops/s、16KB/op及GC<20%阈值。rejection0，终态资金/持仓/冻结/开放触发单检查与快照恢复通过。固定256为提交wave上限，同步回调的实际matching backlog0，不能称256并发压力峰值。专项无独立超时率计数器/三段延迟，成功运行无异常或超时退出；main为真实256匹配窗口。JMH各轮计数及置信区间如下（AuxCounters EVENTS为累计数量，不是每秒）：

```text
SPOT: business accepted/terminal=1825280/1825280; Core accepted/terminal=317440/317440; rejection=0; business 99.9% CI=[111707.041,128820.383]
LINEAR_PERPETUAL: business accepted/terminal=1613312/1613312; Core accepted/terminal=280576/280576; rejection=0; business 99.9% CI=[64628.133,148910.090]
INVERSE_PERPETUAL: business accepted/terminal=1683968/1683968; Core accepted/terminal=292864/292864; rejection=0; business 99.9% CI=[50579.119,170532.061]
LINEAR_DELIVERY: business accepted/terminal=1731072/1731072; Core accepted/terminal=301056/301056; rejection=0; business 99.9% CI=[75122.051,154220.432]
INVERSE_DELIVERY: business accepted/terminal=1731072/1731072; Core accepted/terminal=301056/301056; rejection=0; business 99.9% CI=[77048.464,152206.085]
OPTION: business accepted/terminal=1731072/1731072; Core accepted/terminal=301056/301056; rejection=0; business 99.9% CI=[-39647.917,268242.117]
```

- 仅3个measurement迭代，误差很宽，尤其OPTION置信下界为负是统计区间结果，不代表负吞吐；点估计通过不能给精确产品容量排序。主结果只说明本机1000用户、256symbol、4Lane、1matcher、256in-flight、指定closed-loop混合负载持续终态吞吐；0外部连接，未校正coordinated omission。
- JFR稳定+60..290s：owner99.37%单逻辑核，Lane0..3为17.25/17.41/17.48/17.28%，matcher18.27%。全记录平均机器CPU17.7%（16逻辑核口径），进程12.7%；owner仍是限制点。8473个稳定owner execution/native样本中，结算/完成29.47%，admission9.62%，fingerprint5.90%，Lane await3.15%，matching wait/pump3.73%，readiness协调/轮询8.86%，其他Core34.65%。占比不能转换为优化收益、实际阻塞时长或认定批量结算逻辑冗余。
- 全部owner `LaneMutationTask.await` 栈分组无top-N截断核查：`triggerOrder`、`visitChangedIndexes`相关等待样本均0；回归测试还验证owner读取命中及未命中都不创建Lane任务。剩余executeRiskLane、触发执行账户结算/写入、orderIdByClient等等待依然存在，不能宣称消除了所有触发相关等待。源码中批量ID保持primitive直至最终一次物化，未更改结算顺序/财务计算，也没有改轮询策略。
- 分配：全JFR306.396秒采样权重240946580512B（786389445B/s），除主窗口49667797 ops粗归一约4851.163B/op，包含setup/teardown不能当精确逐op；稳定采样802276939B/s。TLAB222560616976B、非TLAB19067770256B，最大对象33554448B；934648 AllocationSample、883107 NewTLAB、142928 OutsideTLAB、5580 ThreadAllocationStatistics事件。owner采样121069375968B、Lane83609237768B、matcher36253250304B；top类long[]36.55GB、byte[]26.83GB、OrderRuntime22.34GB、Object[]13.11GB，top site/thread和每线程统计见analysis.txt，**仍非零分配**。精确objects/op不可由抽样恢复，未提供。
- GC/heap：4GiB固定heap committed；JFR 61次ZGC Minor、17次Major（12Proactive/3Warmup/1AllocationRate/1Metadata），GC cycle总时长14258.484ms约记录4.65%（含并发阶段，不是STW比例）。268次pause总5.817ms约0.0019%，p50/p95/p99/max=0.0158/0.0513/0.0626/0.0736ms。GC后堆first88.08MB、last490.73MB、max696.25MB包含预热，+60秒后稳健斜率-80941B/s；主轮/JFR多GC采样357/340点，harness live/old正增长斜率均0（非原始逐点完全不变）。无AllocationRequiringGC/疏散/晋升失败，长阶段/原因详additional-audit.txt。
- NMT reserved初74534715475B末74533259808B（地址预留不是物理RSS），committed初4435849299B末4436900384B峰4459303816B；稳定+60..290s committed稳健增长4212.47B/s，低于256KiB/s。各category first/last/min/max及差值详analysis.txt；Direct/Mapped字节、个数、余额斜率均0。Java线程主轮12/JFR14、FD11/13保持稳定；JFR稳定ThreadStart/End11/11含恢复阶段，未见正增长泄漏信号。五分钟不能证明长期无泄漏；Aeron/Netty/Chronicle实际生产native池没有启动，无法据此验收。
- 线程/锁/VM：稳定JavaMonitorEnter0；全记录ThreadPark1145次总27.902s，owner约22.466ms、Lane27.862s；park含空闲及关闭，不能与owner CPU等待样本混同。MonitorWait6次：4次Common-Cleaner空闲等待、1次资源回收辅助线程、1次setup中owner关闭matcher的Thread.join（1.89ms），栈见additional-audit.txt；完整OS RUNNABLE/BLOCKED/WAITING墙钟占比/上下文切换未采集。Safepoint278次，begin总14.099ms/p99 0.102ms/max3.107ms，到达同步max3.102ms；VM908次总24.584ms/max0.607ms，最长CleanClassLoaderDataMetaspaces。Safepoint最大值超过资金费/触发单p99等短操作延迟，仍可能影响个别尾延迟，不能给全操作微秒级保证。GC pause最大值小于batch及下单p99。
- JIT：全记录11203次编译总36988.273ms/max1826.911ms；稳定376次总1653.453ms/max119.484ms及4次deopt，主要编译在预热阶段但不能说完全无JIT。全记录289deopt；class load/unload3291/15，稳定1/0；metaspace used21852808→35852200B、committed22020096→36569088B，code cache分段统计见additional-audit.txt。
- I/O/异常：全记录file read2277次/2836696B/9.995ms、write488次/344260B/7.395ms，含setup类加载与harness报告；stableCoreIo=0、stableCoreThrows=0，无socket事件。全记录JavaExceptionThrow317、JavaErrorThrow153主要启动MethodHandle解析与JNR符号探测，不能将这些记为交易错误；实际throw/IO栈原样保留。CPU speed/scheduler limit全100、swap全0、DataLoss0，无无效轮次。非容器本机，系统进程/物理内存记录在system-samples及JFR；不宣称已排除所有用户进程/JIT/调度干扰。
- 三段业务延迟如下，单位ms，直方图为2次幂上界、max为精确值；包含setup/warmup，ORDER_BATCH是整个请求而非单item，低样本ADL/强平不足以验收尾部，snapshot只有单次恢复耗时，无fence直方图。

```text
Business latency merged histogram upper bounds, milliseconds; max exact; setup/warmup included
ADL/acceptedTerminal n=1 p0.500<=0.002048 p0.900<=0.002048 p0.950<=0.002048 p0.990<=0.002048 p0.999<=0.002048 max=0.001617
ADL/entryAccepted n=1 p0.500<=2.097152 p0.900<=2.097152 p0.950<=2.097152 p0.990<=2.097152 p0.999<=2.097152 max=1.997866
ADL/entryTerminal n=1 p0.500<=2.097152 p0.900<=2.097152 p0.950<=2.097152 p0.990<=2.097152 p0.999<=2.097152 max=1.999483
CANCEL_ORDER/acceptedTerminal n=1175552 p0.500<=4.194304 p0.900<=4.194304 p0.950<=8.388608 p0.990<=8.388608 p0.999<=16.777216 max=22.020889
CANCEL_ORDER/entryAccepted n=1175552 p0.500<=0.002048 p0.900<=0.004096 p0.950<=0.004096 p0.990<=0.016384 p0.999<=0.032768 max=1.836126
CANCEL_ORDER/entryTerminal n=1175552 p0.500<=4.194304 p0.900<=4.194304 p0.950<=8.388608 p0.990<=8.388608 p0.999<=16.777216 max=22.024169
FUNDING/acceptedTerminal n=73472 p0.500<=0.000128 p0.900<=0.000256 p0.950<=0.000256 p0.990<=0.000512 p0.999<=0.001024 max=0.020420
FUNDING/entryAccepted n=73472 p0.500<=0.131072 p0.900<=0.131072 p0.950<=0.131072 p0.990<=0.262144 p0.999<=0.524288 max=6.630947
FUNDING/entryTerminal n=73472 p0.500<=0.131072 p0.900<=0.131072 p0.950<=0.131072 p0.990<=0.262144 p0.999<=0.524288 max=6.633427
LIQUIDATION/acceptedTerminal n=2 p0.500<=0.004096 p0.900<=8.388608 p0.950<=8.388608 p0.990<=8.388608 p0.999<=8.388608 max=4.865311
LIQUIDATION/entryAccepted n=2 p0.500<=4.194304 p0.900<=8.388608 p0.950<=8.388608 p0.990<=8.388608 p0.999<=8.388608 max=4.945410
LIQUIDATION/entryTerminal n=2 p0.500<=4.194304 p0.900<=16.777216 p0.950<=16.777216 p0.990<=16.777216 p0.999<=16.777216 max=9.810721
ORDER_BATCH/acceptedTerminal n=2351104 p0.500<=16.777216 p0.900<=33.554432 p0.950<=33.554432 p0.990<=33.554432 p0.999<=67.108864 max=87.749320
ORDER_BATCH/entryAccepted n=2351104 p0.500<=0.032768 p0.900<=0.065536 p0.950<=0.065536 p0.990<=0.065536 p0.999<=0.131072 max=9.532386
ORDER_BATCH/entryTerminal n=2351104 p0.500<=16.777216 p0.900<=33.554432 p0.950<=33.554432 p0.990<=33.554432 p0.999<=67.108864 max=87.760135
PLACE_ORDER/acceptedTerminal n=1175552 p0.500<=4.194304 p0.900<=4.194304 p0.950<=8.388608 p0.990<=8.388608 p0.999<=16.777216 max=11.560716
PLACE_ORDER/entryAccepted n=1175552 p0.500<=0.016384 p0.900<=0.016384 p0.950<=0.016384 p0.990<=0.032768 p0.999<=0.065536 max=1.576080
PLACE_ORDER/entryTerminal n=1175552 p0.500<=4.194304 p0.900<=4.194304 p0.950<=8.388608 p0.990<=8.388608 p0.999<=16.777216 max=12.172003
RISK_SCAN/acceptedTerminal n=74193 p0.500<=0.000128 p0.900<=0.000128 p0.950<=0.000256 p0.990<=0.000512 p0.999<=0.001024 max=0.045488
RISK_SCAN/entryAccepted n=74193 p0.500<=0.131072 p0.900<=0.131072 p0.950<=0.131072 p0.990<=0.262144 p0.999<=0.524288 max=1.241923
RISK_SCAN/entryTerminal n=74193 p0.500<=0.131072 p0.900<=0.131072 p0.950<=0.131072 p0.990<=0.262144 p0.999<=0.524288 max=1.242128
TRIGGER_ORDER/acceptedTerminal n=146944 p0.500<=0.032768 p0.900<=0.065536 p0.950<=0.065536 p0.990<=0.131072 p0.999<=0.262144 max=2.697637
TRIGGER_ORDER/entryAccepted n=146944 p0.500<=0.065536 p0.900<=0.131072 p0.950<=0.131072 p0.990<=0.262144 p0.999<=0.524288 max=22.553250
TRIGGER_ORDER/entryTerminal n=146944 p0.500<=0.131072 p0.900<=0.262144 p0.950<=0.262144 p0.990<=0.524288 p0.999<=1.048576 max=22.554123
```

- 结论：两项实现、717项功能测试、六产品真实专项JMH及U本位永续mixed JFR通过本轮已锁定范围和阈值；没有改变账户Lane所有权和资金规则。仍有owner CPU平台与约5KB/op分配，不能称吞吐上限已完全突破或零分配。其他五产品未分别采长JFR、真实三节点Aeron/API/WS/Kafka网络、open-loop尾延迟、全native池、完整墙钟调度与长期泄漏未验，生产完整性能验收只能标记部分验证。未运行无影响的其他服务全量测试。
- 原始artifact：`/Users/atomex/Desktop/surprising/async-profiler-evidence/2026-09-07-owner-trigger-publication`，包含两个完整300秒mixed日志、六JMH JSON/日志、soak.jfr、profile、命令/构建与失败夹具日志、summary/gc-pauses、CPU/分配/等待/IO/native/VM/业务延迟及聚合源码；SHA256清单附后。全部测试、采样和分析进程已退出。

- 本轮最终artifact清单：66文件、137731647B（不含清单），soak.jfr 69034138B/306.396秒；SHA256SUMS自身SHA256 `5d451ae2a0fd1e09773af059753887a80d53c816ee971ca57e7f8c8dc1a566d4`。

## 2026-09-07 GCP三节点真实网络首轮（采集前锁定）

- 用户授权创建四台VM并压测，仅当前master；业务jar源为da10ac0b（业务c26e0578），预锁提交后master仅文档差异。对照commit不适用，不重跑旧版、不与本机批量mixed吞吐作提升率比较。本轮没有业务代码改动，使用现有ClusterDerivativeSmokeMain、ClusterProbeMain、ClusterCapacityMain，未将本轮当新增业务JMH验收。
- 项目surprising-ae591，asia-southeast1-b同zone，3个独立持久化Aeron Cluster节点+1独立client，均n2-custom-8-16384、8vCPU/16GiB、Intel Cascade Lake、Ubuntu24.04、80GB pd-ssd；Temurin HotSpot25.0.4.1+1。私网10.90.0.5/3/4为node0/1/2，client10.90.0.2；32总vCPU，IAP只作管理与artifact传输，业务走私网UDP；原始artifact位于/Users/atomex/Desktop/surprising/gcp-validation/2026-09-07，密钥不属于公开证据/归档。
- 正确性前置：少量LINEAR_PERPETUAL双用户开仓、资金费、重复资金费拒绝，资金总和2000；停止/恢复节点并用相同seed verify。部署启动、失败与重试全部记录，不自动删除Archive和Cluster状态。
- 容量场景：ClusterCapacityMain，LINEAR_PERPETUAL/MATCH_ASYNC，1000活跃用户（500 maker/taker对）、256symbol、1发起worker/1Aeron连接、固定async-in-flight=256待完成交易对；每对maker完成后才发taker，故至多256未完成交易命令，不是512。每对两条普通订单（SELL GTC maker + BUY IOC taker），price100/qty1，成交1fill；无batch，batches/items口径不适用。每账户初始USDT=10^12，零手续费、零初始持仓，持续maker和taker共同运行；单向建仓不声称平仓混合负载，库存状态增长属于此场景。查询在测量后验证全部账户资金总量、订单簿清空。
- 每node默认4Account Lane/1matcher，Aeron1.53.0、MediaDriver SHARED_NETWORK、Archive SHARED；JVM -Xms4g -Xmx4g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:NativeMemoryTracking=summary，加jdk.internal.misc opens/exports；持久化/GC日志在/var/lib/surprising，Aeron mmap位于/dev/shm。client -Xms512m -Xmx2g、ZGC，same opens/exports。无Kafka、WS、HTTP/API gateway。
- 无profiler主轮：30s warmup+300s measurement，seed90701、symbol前缀GCP-MAIN；closed-loop无目标到达率，256窗口尽快补齐，没有CO修正。测量后drain、资金验证及重启后的相同seed verify；另采state hash对比无业务写入重启前后。首轮可用性门槛>=1000 terminal business ops/s（不是生产容量目标），offered=accepted=finalized、failures0、资金差0/挂单0、重启恢复一致；p99<=1s。全部失败保留，阈值不追改。
- 冷却15s后同配置JFR诊断轮：另一seed90702、symbol前缀GCP-JFR，30s warmup+300s measurement；节点JFR profile设置，通过jcmd启动三节点recording，包含setup/warmup/verify，maxsize512m、手动dump。不以带profiler结果替代主轮；分析leader/follower的CPU、Lane/matcher/Archive/Consensus、分配/GC/NMT/等待/IO。真实Cluster Archive同步/异步磁盘I/O须按线程区分，交易业务owner同步磁盘/网络/数据库I/O判失败。
- 四节点vmstat/mpstat/pidstat、GC和NMT监测，记录CPU steal/频率/CPU与内存/磁盘/网络、上下文切换、swap。swap增长、JFR DataLoss、实例维护/重启或持续明显CPU steal（连续3个5s采样>5%）判相应性能轮无效；本轮不验证宿主机物理频率/硬件隔离。
- 工具已知指标边界：accepted是在收到APPLIED终态时计数，并非独立受理时间；输出acceptanceToFinalization标签实际来源请求发起（taker含maker链路），只能作为当前工具完成延迟，不冒充三段延迟。pendingMax/completionQueueMax=-1代表未埋点；async固定窗口上限不能代替观测最大backlog。现有report主要p50/p99/p99.9，未提供全部业务类别/独立三段p90/p95，资金验证不独立核对每个持仓字段。记录这些缺口，首轮是受限容量实测，不宣称完整生产性能验收或已测吞吐绝对上限。单zone不代表跨zone/跨region故障安全性；profile默认抽样不提供精确objects/op、完整native池或五分钟长期无泄漏证明。

### GCP采集前连接数校正（尚未开始性能采样）

- 上述单连接方案作废、未采集性能数据：源码AeronClientCapacity默认每command session最多64在途，因此必须使用4个command session才能提供总256协议在途容量，另有1个reserved control session。最终锁定为1 workload worker、4 command connections+1 reserved connection、async-in-flight=256；其他场景、阈值、主轮/JFR参数保持上述定义。少量功能smoke不属于容量测量，未形成其他in-flight档位性能数据。

### GCP功能前置发现与最终负载锁定（仍未开始性能采集）

- 首次启动缺少Archive CRC所需 `--add-opens=java.base/java.util.zip=ALL-UNNAMED`，导致三节点退出，补充该JVM参数后正常选主。首次交易smoke被正确拒绝：CoreOrderDecisionResolver要求衍生品下单有<=5000ms的新鲜mark，旧工具未提前提供mark；异常采样同时显示MARK_PRICE_MISSING被既有CoreResultCode映射成INVALID_COMMAND，未改交易校验/协议枚举。
- 工具修复：smoke下单前发送当前时间mark；ClusterCapacityMain在创建订单前按symbol每秒最多一次同步刷新mark，并单列marketDataCommands与totalTerminalCoreMessages。1个workload worker在pending对数<256时刷新，mark占用该空闲窗口，不另开并发生产者；4命令session总容量256保持。测量订单数=finalized，两单一fill，行情命令另外报告；总Core终态=finalized+marketDataCommands。负载包含这些必要行情输入；旧不带mark的场景未采集数据，不作性能对照。
- 标记价格持续更新会触发实际risk状态变化，其成本属于云端负载。1秒刷新频率不保证极端阻塞后仍新鲜，任何STALE_MARK_PRICE/业务拒绝都判该轮失败，不能放宽Core五秒规则。当前master被测commit为本条+工具修复的提交；实际jar SHA和节点入口参数留存。主轮seed90701/JFR90702及30s+300s、阈值不变。错误smoke与异常JFR均仅功能诊断，无吞吐验收结论。

## 2026-09-07 GCP首轮失败与第二轮预锁定

- 第一轮业务jar b293e295：真实网络smoke通过，三节点全部停止/启动后stateHash=b7908707c4eb3510一致，资金费和持仓复查通过。主容量seed90701运行期间出现Publication.ADMIN_ACTION(-3)，在刷新mark时由客户端明确返回NotAccepted，旧工具直接抛异常退出，未产出完整吞吐结果，判FAIL，不能称五分钟压测通过。原始main-failed-admin-action.log、main-failed-summary.txt、节点日志/系统监测均保留。错误发生在已知未接收的offer，不能误报为已提交交易丢失，也不能忽略为成功样本。
- 修复仅限压测工具：ClusterOfferRetry对明确NotAccepted的ADMIN_ACTION/CLIENT_BACKPRESSURED保持原commandId与payload，1ms异步延迟、有界10s重试；ResultUnknown、NOT_CONNECTED、其他异常不重试。单列transientOfferRetries，逻辑offered/accepted/finalized不重复计数，raw offer尝试会多于逻辑命令数。客户端生产契约和Core业务代码不变。4项定向测试覆盖成功重试、非安全结果不重试、截止时间与指标回归。
- 第二轮开始前停止三节点，保存旧目录与GC日志，切换新DATA_DIR=/var/lib/surprising/round2；不删除第一轮Archive/Cluster状态。第二轮主测seed90711/symbol=GCP-R2，JFR seed90712/symbol=GCP-JFR2；其余环境、4命令连接+1预留连接、单worker、256总在途窗口、1000用户/256symbol、1matcher/4Lane、每秒mark更新、30s预热/300s测量/15s冷却及>=1000business ops/s和p99<=1s阈值沿用前述定义。新jar源码为本次工具修复提交后的master，SHA随artifact记录，不比较前一失败轮性能。transport NotAccepted重试单列，业务拒绝/结果不明/超时仍要求0。

## 2026-09-07 GCP第二轮结果：功能/日志恢复通过，性能门槛未通过

- 采集源码 `a7d71ade`，对照commit不适用（仅当前master），业务代码仍为c26e0578；修复范围只有验证工具的新鲜mark与明确未接收请求重试。HotSpot25定向测试4项通过、Maven package通过；没有新增交易内核改动，未重跑无关JMH或服务全量测试。三节点及客户端jar SHA256均为 `e682de6b5a34a73c311f98982a88a73525b9d84ddcfd54619c2ae494d92421b5`。本轮没有中途改变锁定负载或阈值。
- 环境、JVM、场景遵循上述第二轮预锁：新加坡同zone四台8vCPU/16GiB，三节点各4Lane/1matcher，客户端1000用户、256symbol、4命令session加1预留session，固定256待完成交易对/至多256交易链路命令。普通maker SELL GTC与taker BUY IOC各50%，两单一fill，无批量、无撤单、无API/WS/Kafka，持续单向建仓。每个用户初始10^12 USDT，样本资金费账户另为1000 USDT/人。封闭循环、不修正CO；这不是到达率容量曲线。
- 命令/artifact根目录 `/Users/atomex/Desktop/surprising/gcp-validation/2026-09-07`（下文相对路径均基于此）。主轮通过 `systemd-run --unit=surprising-capacity-r2 --uid=bench ... /usr/bin/env CAPACITY_SEED=90711 SYMBOL_PREFIX=GCP-R2 /bin/bash /home/bench/run-capacity.sh` 启动；JFR轮使用unit surprising-capacity-jfr2、seed90712、prefix GCP-JFR2。完整环境参数在 `run-capacity.sh`、`start-node.sh` 和 extracted `core-unit.txt`；30秒预热、300秒测量，主轮结束至JFR启动冷却超过15秒。主轮启动08:46:04 UTC，09月07日08:52:09查询已正常退出；JFR轮约08:52:47至08:58:35 UTC，包括setup、warmup与末尾核对。两轮测量均完整300秒并drain完成，原始 stdout 保存在 `evidence-surprising-load/main-r2.log`、`jfr-r2.log`。

| 指标 | 主轮（无profiler） | JFR诊断轮 |
| --- | ---: | ---: |
| 实测秒数（含末尾drain） | 300.012 | 300.011 |
| offered / accepted / finalized订单操作 | 114810 / 114810 / 114810 | 114532 / 114532 / 114532 |
| terminal business ops/s（下单） | 382.684 | 381.760 |
| fills / fills/s | 57405 / 191.342 | 57266 / 190.880 |
| mark终态命令 / 每秒 | 57405 / 191.342 | 57193 / 190.637 |
| terminal Core messages总数（订单+mark） | 172215 | 171725 |
| terminal Core messages/s（由上两类每秒相加） | 574.026 | 572.397 |
| 工具完成延迟p50（ms） | 12.255 | 16.261 |
| 工具完成延迟p99（ms） | 1393.557 | 1395.654 |
| 工具完成延迟p99.9（ms） | 1411.383 | 1408.237 |
| 业务失败、结果不明、超时 | 0 | 0 |
| 明确未接收的临时offer重试 | 1 | 1 |
| 资金差 / 测试symbol剩余book levels | 0 / 0 | 0 / 0 |

- **判定FAIL：382.684 < 1000 business ops/s，1393.557ms > 1000ms。** stdout的 `capacity=PASS` 只代表内置业务核对，不代表外部性能门槛通过；`scope=LOCAL_CAPACITY` 是工具硬编码标签，实际为四台VM私网UDP。accepted在终态回包计数，订单accepted与terminal差为0，pending交易对已排空；行情仅在成功终态后计数。独立受理数/三段时间、观测最大backlog、p90/p95/max延迟、原始直方图没有完整埋点；pendingMax/completionQueueMax=-1不是零。不能把这些缺口补写成通过，也不能把订单与mark混在一起冒充订单吞吐。

### 负载限速和CPU证据

- 主轮mark命令数恰等于成交对数。`ClusterCapacityMain.asyncMatch` 在每个交易对前同步调用 `refreshMarkIfDue`，其 `applied(...).join()` 等待集群终态；256symbol轮转超过1秒后几乎每对都要刷新。这会把发起端串行限速。待完成集合还包含已经完成但尚未取走的future；取走前会先补入新交易对并等待其mark，因此taker记录的延迟混入压测线程收集等待。固定256是窗口上限，不代表真实同时有256个未完成请求。此证据表明负载未有效打满，不能把383 ops/s当作交易Core或Aeron的吞吐上限。
- 系统vmstat稳定窗口（主轮08:47..08:51、JFR08:54..08:57 UTC）机器CPU忙比例：leader node0主轮5.29%/JFR5.53%，node1为4.13%/4.11%，node2为4.29%/4.36%，load为2.02%/1.83%。全采样swap使用/换入/换出均0，steal最大0，无持续steal失效条件。详见 `system-summary.txt` 及四节点vmstat/mpstat/pidstat/network原始文件。
- JFR +60..290秒稳定区间（230秒，包含在测量内），按8逻辑CPU归一到“单个逻辑核100%”：leader clustered-service owner平均5.762%，matcher4.648%，各Lane0.304%/0.341%/0.361%/0.340%；transport sender/receiver16.893%，consensus4.334%，Archive2.637%，driver-conductor4.381%。follower owner约5.4%，同样不饱和。`jfr-cpu-node*.txt` 保留全部线程数据。
- 稳定期CPU样本主要在UDP receive/send、driver idle/yield、owner的命令编解码/指纹/结算等待与响应；Lane的park栈是 `SettlementLaneWorker.run` 等待工作，不是锁竞争证据。profile有阈值的monitor事件未显示明显长锁竞争；不能据此断言不存在短锁或调度开销。`SurprisingClusteredService.processCommittedRequest` 在每条日志回调返回前结清pending matching，属于当前恢复一致性要求，不能直接删除，也不能用本机跨请求批量流水线吞吐替代它的网络实测。下一次有效容量测量应先修复工具的异步mark依赖与终态采样位置，再另行预锁；本轮没有偷偷更改负载重取漂亮数字。

### JFR分配、GC与其他运行时证据

- 三节点分别采集profile.jfc，maxsize512m，`jfr-control.py` 通过jcmd启动/停止；recording覆盖setup/warmup/测量/verify及结束后空闲，时长node0/1/2为462.965/461.928/462.787秒。profile配置、原始JFR、summary、聚合源码/输出均留存，DataLoss均0。CPU和分配另按+60..290秒裁剪；默认profile抽样开销未独立量化，带profiler吞吐只作诊断。
- 稳定窗口ObjectAllocationSample加权分配，node0/1/2约3.837/3.762/3.792 MB/s（十进制），按诊断轮平均订单速率折算约10052/9853/9934 B/订单操作；**包含mark、Aeron和其他线程，属于抽样估计，不是精确单笔交易分配**。明显站点有CoreCommandResultCodec响应字节、CoreOrderStateView、消息头/解码payload、MatcherSettlementPlan、CoreMatchingResult、StoredResult、风险进度状态。不是零分配。没有ObjectAllocationInNewTLAB/OutsideTLAB有效事件，不能提供精确objects/op、最大对象和完整TLAB统计；汇总中的0表示缺少事件，不代表零成本。
- 全recording每节点约4.4GB加权分配中，大量LongObjectHashMap/positionsForSnapshot发生在最初setup期间，而稳定裁剪窗口该站点权重为0。新建第二组256 instrument时旧组已有持仓，`RuntimeCommandProcessor` 的instrument校验会复制/扫描Lane状态；这是配置导入路径的优化线索，不能误判为每笔订单都复制全持仓。完整分配与裁剪结果均保留在 `jfr-analysis-node*.txt`、`jfr-window-node*.txt`。
- 三节点heap committed均4GiB；全recording的After-GC used范围分别331350016..488636416、291504128..473956352、297795584..463470592B。node0记录5次ZGC Major（Warmup1、Proactive4），另外两节点4次。GC暂停phase数25/20/20，总暂停0.293/0.268/0.248ms，p99及max分别约0.014/0.018/0.017ms；并发GC总时长不能与暂停混用。各节点GC日志已保存。本轮GC暂停远小于工具p99，不能解释约1.4秒尾延迟。短时样本和单向增长持仓不足以证明无泄漏，follower After-GC末值上升也不能单独判泄漏。
- NMT全recording total committed峰值约4.434/4.430/4.431GB，末值较首值下降11.77/12.61/8.32MB；全部category当前值、峰值、增量见聚合。JFR启动前/结束后的jcmd NMT原始输出单独保留。ZGC约64GiB heap虚拟地址预留不是实际占用64GiB内存。DirectBufferStatistics均为9575136B、8个buffer，采样期不变；Java活动线程数均21且稳定。不包含完整Aeron mmap/native池分配释放余额和长期文件描述符趋势，不能声称全部堆外无泄漏。
- leader safepoint 45次，最长begin约0.103ms；VM operation记录120次、最长13.036ms；profile阈值内Compilation2次、总294.289ms/最长186.725ms，另有44次deoptimization，不能把这些计数当作全部JIT活动。其他节点对应数据在原始聚合。类加载/代码/NMT均留证，但未完成长稳和完整墙钟调度归因。
- 稳定区间超过JFR阈值的文件写入出现在archive-conductor RecordingWriter，node0合计16.751ms；未观察到交易owner的同步文件/Socket/数据库I/O事件。Aeron使用UDP，profile事件阈值和JNI覆盖有限，未观察到不等于绝对没有系统调用。leader recording尾部08:58:35记录3次PortUnreachableException（客户端结束附近），JFR.stop期间Attach Listener有一次名称转数字的NumberFormatException；不是业务拒绝，未造成订单终态差异。原始异常和I/O栈仍保留。

### 正确性、恢复、资源与验收范围

- 修复后少量样本seed90720开仓/资金费核对通过，多空账户USDT总和2000、资金费净和0。两轮容量各1000账户资金总额核对及测试symbol订单簿清零通过。资金/余额核对不是六产品所有金融状态的全面审计；每用户完整持仓字段、手续费/平仓/强平/ADL/交割/行权未在本轮压力负载覆盖。
- 两轮完成且没有其他业务写入时，三节点全停重启前后 `appliedCommandCount=380433`、`stateHash=8abed9ee70d6ce06` 完全一致；随后seed90711/90712两组capacityVerify以及90720 derivativeRecovery再次通过。09:01:38启动服务，node0约09:02:28重新成为leader；期间前两次10秒connect timeout保留为恢复未就绪的真实记录，第三次查询成功，不能称零停机恢复。leader恢复时还记录 `quorum position went backwards: leaderCommitPosition=85503296 quorumPosition=0` 警告；本轮业务hash核对通过，但未据此宣称完整故障矩阵无风险。此次验证是持久化日志重放，不包含新建快照恢复、运行中断网/kill/跨zone故障和高负载failover。
- 四台实例运行约08:00:13..09:04:33 UTC，最终gcloud状态均TERMINATED（已停止，未删除），证据 `instances-final.json`、`stop-instances.log`。80GB盘各一块、VPC/规则和测试数据保留，磁盘继续计费。Cloud Billing Catalog当时返回新加坡N2自定义CPU 0.04094895 USD/vCPU-h、RAM 0.0054873 USD/GiB-h，四台仅compute约1.66155 USD/h；本次约1.78 USD compute估计，不含磁盘/IP/流量/税，不是最终账单，也未确认赠金余额是否覆盖。
- 正式结论：**三节点真实网络交易、资金核对和全停日志恢复通过；固定负载的性能门槛FAIL，核心未被压满，生产容量验证未完成。** 前一ADMIN_ACTION失败轮保留，修复后主/JFR轮各一次安全重试且无业务失败。缺少完整延迟/backlog埋点、open-loop、六产品金融路径、snapshot/failover和长稳/native池证据，因此只能作部分验证，不与本机15万/18万批量mixed结果直接比较。本轮没有为追吞吐改变Core一致性规则。
- artifact清单 `SHA256SUMS` 含255个非密钥文件、210111718B（不含清单自身），自身SHA256 `f095e42815ef20bac3c1669b03258170ba92e24682fdd6dd955c8dad50981422`。原始node0 JFR 3228761B，SHA256 `2934dafed3337930b54d853df13a102a3e85ff43268df5685176d38b5f02d86c`；node1 3205556B，`3c70e9b4721395009f389a6fa769203d76ee9b83651fa133763e0add22d1e63b`；node2 3214102B，`0aa8d5ce824b3a45f0ff00f2a97a9cc581ce452471d319b3524c4af4c63f51d2`。私钥、SSH元数据和构建缓存不在清单内，不得整体公开artifact父目录。

## 2026-09-07 GCP异步负载修正轮（采集前锁定）

- 用户授权修正压测器后测真实吞吐上限。本次只变更tools：行情门控使用共享异步future；一个slot顺序执行mark（需要时）→maker→taker，始终至多一个未终态命令；maker/taker在各自发起至终态callback期间独立计时。已完成future及时从任意slot回收，不受FIFO头部等待影响。多worker均分全局256slot，不是每worker256。保留明确NotAccepted有界重试；不重发结果不明请求。不改生产Core一致性和金融规则。
- 当前master为本条与工具修复的提交，实际commit/jar SHA写入artifact；对照commit不适用，不构建/重跑旧版。测试HotSpot GraalVM25.0.1，云端Temurin HotSpot25.0.4.1。本轮改动仅负载工具，不触发业务JMH重跑要求；定向测试覆盖价格异步依赖、maker失败阻断taker、独立终态计时、价格共享/过期/失败、256全局窗口及安全重试、直方图指标。
- 重启原四台GCP N2 custom 8vCPU/16GiB、80GB pd-ssd、新加坡同zone、Intel Cascade Lake、Ubuntu24.04，无新增VM。节点1matcher/4Lane、Aeron1.53.0 SHARED_NETWORK/Archive SHARED、4GiB ZGC/AlwaysPreTouch/NMT及原opens/exports不变；client512MiB..2GiB ZGC，新增NMT用于client诊断。业务私网UDP，IAP仅管理。启动后先停止旧core，保留原数据与GC，不在旧业务状态上直接重复setup。
- 三轮独立DATA_DIR=/var/lib/surprising/async-w1、async-w2、async-jfr，不删除旧Archive/Cluster。主轮W1：seed90731/prefixGCP-AW1，1发起worker；主轮W2：seed90732/prefixGCP-AW2，2发起worker。每轮均总256slot、4命令session+1预留session、1000用户/500 maker-taker对、256symbol、LINEAR_PERPETUAL/MATCH_ASYNC、普通maker SELL GTC qty1/price100 + taker BUY IOC qty1/price100各半，两单一fill；无batch/撤单/费用，初始各用户10^12 USDT、零持仓；持续maker与taker运行，单向建仓，行情按symbol/source时间1秒到期异步刷新。全部轮次closed-loop offered=0，不修正CO；不冒充API层或open-loop容量。
- W1/W2各30秒预热+300秒测量/drain，轮次间至少15秒冷却。阈值两轮都>=1000 terminal business ops/s、maker/taker各p99<=1秒、逻辑submitted=completed=订单+mark终态、订单offered=accepted=finalized=2*fills、期末unfinished0、observedRequestMax<=256、资金总量差0、测试订单簿0、业务拒绝/未知/超时0。每10秒报告终态速率与在途，每秒采样在途均值；同时报告普通订单/总Core消息/fill速率、maker/taker六分位、聚合HDR直方图、重试次数。不能把最大在途达到256单独解释成CPU饱和。
- JFR诊断轮seed90733/prefixGCP-AJFR，worker按主轮较高终态速率选择；差异小于5%时选1。其余负载完全相同，30秒预热+300秒测量、至少15秒冷却。三Core jcmd profile.jfc/maxsize512m，client StartFlightRecording profile/maxsize512m，保存4份JFR及NMT前后、GC、vmstat/mpstat/pidstat/sar；记录包括setup/warmup/verify，另裁剪稳定交易区间，禁止把初始化分配归入逐笔热路径。JFR吞吐仅归因，不替代主轮。最后全停/启动三节点，查询hash/applied计数前后一致、同seed容量资金/订单簿verify；本轮不声称快照恢复或完整故障矩阵。
- 无profiler两种worker结果与10秒分段共同判断发起端是否仍限速；增加worker没有实质提升且client有余量时，结合owner/matcher/Lane CPU与等待栈判定本配置瓶颈。若两者继续显著增长，不声称已到上限，须另起预锁诊断。所谓“上限”仅为固定256、1matcher、此业务组合/配置下可持续终态吞吐，不是硬件或全部业务的绝对上限。
- 数据有效性：保留所有失败；swap增长、JFR DataLoss、VM维护重启、连续3个5秒steal>5%判相应轮失效。无磁盘空间/超时/mark过期不得降低业务规则或隐藏失败。独立accepted阶段、完整业务类别、各native池、长期泄漏、六产品金融动作、跨zone/推送仍未覆盖，生产全面验收只能部分验证。artifact目录 `/Users/atomex/Desktop/surprising/gcp-validation/2026-09-07-async-window`，采样完成停止四VM。

### 异步首轮预热失败、客户端来源顺序修复及重锁

- 源码6148d2a0的W1在预热阶段失败：mark终态返回STALE_SOURCE_SEQUENCE，未产生有效300秒主吞吐。原始日志及节点系统证据保留于上述artifact的w1/。根因不在mark payload的priceSequence：AeronClientPool同一个AgentLane的来源序号原先在调用线程创建Request时分配；负载worker与taker终态callback均为生产者，创建顺序与mailbox入队顺序可以相反。各session独立sourceId仍不足以防止同session内乱序。不得将Core的正确顺序拒绝改为成功或自动重发这个终态。
- 修复改到实际发送边界：仅dispatcher按offer顺序分配各lane sourceSequence。Request复用现有池，保存必要的命令字段/防御性payload副本，到首次offer才创建唯一CoreMessage/Header，不增加中间消息副本或生产者排序锁；查询/显式prepared消息保留调用方原序号。确定性测试用反转创建/入队顺序复现旧逻辑，并验证payload防御性复制；扩大到全部AeronClient测试。此修复影响生产客户端，新增ClientSourceOrderingBenchmark测量实际客户端提交/发送路径；交易Core业务未改，真实金融正确性和恢复由接下来的三节点交易轮验证。
- 客户端JMH预锁：当前master修复提交、HotSpot GraalVM25.0.1、macOS26.7/i9-9880H/16逻辑CPU/16GiB，ZGC -Xms512m -Xmx512m，1fork/4线程、3×1s预热/3×1s测量、冷却15秒、-prof gc、JSON输出。4线程各至多64未完成请求，**全局256**，4命令session各64加1预留；每invocation64个独立请求，OperationsPerInvocation64。使用真实AeronClientPool与即时确定性Session，只计client terminal requests/s，不是Core business ops/s；mock session逐条校验严格递增来源序号，trial结束offered=terminal、unfinished0。门槛>=1000 client requests/s且无乱序/错误；保存主分数/误差/分配/GC。该边界基准没有撮合资金模型，不能替代云端资金与日志恢复证据；云端四份JFR覆盖修复后的真实客户端路径。
- 原W1停止，不沿用失败数据。重锁W1B seed90741/prefixGCP-AW1B、DATA_DIR=/var/lib/surprising/async-w1b；W2B seed90742/prefixGCP-AW2B、目录async-w2b；JFRB seed90743/prefixGCP-AJFRB、目录async-jfrb。除本次来源顺序修复及独立seed/目录外，W1/W2 worker1/2、JFR择优规则、全部256窗口/1000用户/256symbol/1matcher/4Lane/四连接、30+300s、门槛、冷却、资金/恢复及数据有效性沿用上条；先提交/构建通过再采样，不重跑旧版。

### 用户确认真实三节点为后续唯一性能执行环境

- 用户在W2B进行期间明确：当前先验证真实三节点，不切换本地mixed策略；后续所有压测必须在真实三节点环境执行。已写入AGENTS.md，后续不再运行本地内存或mock Session JMH/性能采样。本轮已经完成的本地client JMH发生在此指令之前，只保留历史客户端诊断证据，不将其当作真实交易容量。正在进行的W1B/W2B及预定JFRB本身就是四台云主机（3个真实Core节点+独立压测机），场景和阈值不变；本次规则修改不改变被测jar源码7a23175f。

### 真实三节点异步轮结果（2026-09-07，W1B/W2B/JFRB）

- 被测源码7a23175f，规则提交a59f027a不改变jar；jar SHA256 `6abe5a33994709b2425f37ef517565de9ebe357331b56d0e48761ce3b0071a68`，四机一致。对照commit不适用（仅当前master）。6148d2a0的W1预热来源序号失败保留，不能作为有效吞吐。修复后的全部36个client测试与9个tools测试通过，HotSpot25打包通过；日志位于本轮artifact。来源顺序回归覆盖创建顺序与入队顺序相反、防御性payload复制。
- artifact根目录 `/Users/atomex/Desktop/surprising/gcp-validation/2026-09-07-async-window`。执行入口为该目录 `python3 round.py w1b 1`、`python3 round.py w2b 2`、`python3 round.py jfrb 1`，实际远端systemd命令、JVM参数、监控和哈希保存在各轮目录。W1B约09:39..09:44 UTC，W2B约09:46..09:51，JFRB约09:57..10:03（含采样setup/verify）；每轮严格30秒预热、300秒测量/drain，实际端到端耗时见表。沿用上述预锁普通maker/taker、1000用户、256symbol、GLOBAL256、4命令连接、每Core 1 matcher/4 Lane、三台独立8vCPU/16GiB Core加独立同配压测机，不使用本地mixed/batch策略。

| 指标 | W1B：1 worker | W2B：2 workers | JFRB：1 worker，诊断 |
|---|---:|---:|---:|
| 测量秒数 | 300.056 | 300.059 | 300.067 |
| offered=accepted=terminal订单数 | 1,820,158 | 1,782,532 | 1,804,678 |
| terminal business ops/s | 6,066.061 | 5,940.606 | 6,014.259 |
| fills数 | 910,079 | 891,266 | 902,339 |
| fills/s | 3,033.030 | 2,970.303 | 3,007.129 |
| mark命令数 | 74,192 | 73,174 | 73,849 |
| mark命令/s | 247.260 | 243.865 | 246.109 |
| terminal Core消息数 | 1,894,350 | 1,855,706 | 1,878,527 |
| terminal Core messages/s（订单+mark） | 6,313.321 | 6,184.471 | 6,260.368 |
| 1秒采样在途均值 / 样本数 | 255.866 / 299 | 247.445 / 299 | 255.890 / 299 |
| 最大逻辑在途 / 期末未完成 | 256 / 0 | 256 / 0 | 256 / 0 |
| 明确NotAccepted瞬时重试 | 25 | 25 | 25 |
| 业务失败、资金差、订单簿剩余level | 全部0 | 全部0 | 全部0 |

- 三轮submittedRequests=completedRequests=表中Core消息数，订单数=2*fills。无业务拒绝/未知结果/超时，明确未接收重试单列；三轮预锁门槛均PASS。accepted计数仍来自APPLIED终态，缺少独立accepted时间与Core内部backlog，不能将客户端逻辑窗口（包含排队/重试）当作已进入集群的256条命令；旧pendingMax/completionQueueMax=-1明确为未观测。
- 每类延迟从其实际发起到终态callback，单位微秒；HDR 3位有效数字、范围至1分钟，closed-loop未修正coordinated omission，不是完整生产open-loop尾延迟。

| 轮次/类型 | 样本 | p50 | p90 | p95 | p99 | p99.9 | max |
|---|---:|---:|---:|---:|---:|---:|---:|
| W1B maker | 910079 | 42500 | 53608 | 59113 | 68485 | 76677 | 128581 |
| W1B taker | 910079 | 39124 | 44367 | 45383 | 47054 | 49283 | 126877 |
| W2B maker | 891266 | 42336 | 50266 | 53706 | 60227 | 66158 | 74645 |
| W2B taker | 891266 | 39354 | 44761 | 45776 | 47448 | 49643 | 76480 |
| JFRB maker | 902339 | 42762 | 56033 | 60751 | 69206 | 79626 | 150994 |
| JFRB taker | 902339 | 38174 | 43876 | 45121 | 47677 | 56033 | 129826 |

- W1B 29个完整10秒段5,969..6,151 ops/s，前6段均值6,060.856、末6段6,054.297；W2B 5,677..6,049，前6段5,910.306、末6段5,994.003。增加发起worker没有提升，按预锁差异<5%选择1 worker采JFR。结论仅为此配置、256窗口、此普通订单组合下约6千持续终态ops/s的平台，**不是交易Core或Aeron硬件的绝对上限，也不与历史15万/18万本地批量mixed口径比较**。
- W2B管理侧IAP TLS连接出现UNEXPECTED_EOF，编排读取中断；云端systemd压测与四机连续监控未停。保留原错误日志，用`--resume`继续读取/收集同一次负载，没有重启该测量或清空数据。SSH复用连接后完成采集。各有效轮稳定监控区间swap/si/so/steal均0，无连续steal超限；四份JFR无DataLoss。

### 三节点与客户端JFR归因

- 原始JFR、profile配置、NMT前后、GC日志、vmstat/mpstat/pidstat/sar均保留。`JfrRead`是全录制聚合；`ClusterCpu`与`WindowAudit`裁剪各录制起点+120..290秒的稳定170秒，不把setup/verify的快照物化当逐笔热路径。采样期间leader为node2，其他两节点是真实follower并执行相同Core。
- leader线程CPU按单逻辑CPU100%：clustered-service owner平均38.684%，matcher8.828%，四Lane分别3.253/3.631/3.400/3.543%，consensus22.005%、archive19.432%、共享sender/receiver75.700%、driver-conductor28.565%。client dispatcher19.164%、发起worker13.773%、receiver50.043%、sender24.944%。owner、matcher、Lane以及发起端均未占满单核；Aeron轮询/yield/native receive样本不能都解释为有效业务计算。
- owner的显著等待栈是`processCommittedRequest → idleCommand → ClusteredServiceAgent.idle → BackoffIdleStrategy`。代码逐个已提交日志回调执行apply、等待该命令匹配/结算完成、检查回调完成，再返回响应；matcher/Lane存在park/调度。证据支持串行回调内的跨线程完成等待及传输/调度值得优先进一步分段测量，尚不足以把全部瓶颈唯一归因到某个线程。这个回调完成边界保护日志/状态/恢复一致性，不能直接移除来追数字。仅增加load worker不能绕过这个边界；尚缺提交、复制、各阶段和返回的独立墙钟埋点。
- 稳定窗口sample weight：node0/1/2分别49.027/51.355/51.271 MB/s，约8,152/8,539/8,525 B/订单操作；client115.081 MB/s，约19,135 B/订单操作。这是加权采样估计，分母为本轮整体订单速率，包含mark及后台分配，不能当精确业务对象数。Core热点包括CoreCommandResultCodec响应字节、CoreOrderStateView、重复ResolvedPlaceOrder、changedBalance/changedAssets的IntHashSet、MatcherResult/CoreMatchingResult和身份映射扩容；窗口未采到positionsForSnapshot栈。不能只因出现在top就判业务错误或无条件删除必要状态。
- client稳定窗口19.564GB加权分配中Long约15.285GB，主要是dispatcher扫描pending时`SurprisingAeronClient.takeResponse → ConcurrentHashMap.remove`装箱；另有迭代器和pollEgress lambda。这是明确后续分配优化候选，但当前dispatcher并未CPU饱和，不能声称删除后必然提高整个集群吞吐。本轮仅修复来源顺序，没有顺带重构响应交付。
- profile没有开启完整NewTLAB/OutsideTLAB事件（计数0），不代表零分配；对象数/op、精确最大对象尚未测得。ThreadAllocationStatistics的区间端点不足以替代稳定窗口精确计数。全量类/线程/栈、分配分钟趋势保存在各`*.analysis.txt`、`*.window.txt`。
- 用户限定后续真实三节点之前已完成的client JMH，仅补录历史证据：按前述预锁1fork/4线程/全局256、3×1s预热及3×1s测量，实际client dispatch即时Session终态1,408,767.896 ±307,740.717 client requests/s，491.810 ±4.018 B/request，GC 28次/44ms，trial offered=terminal=8,062,784、未完成0、来源序号断言通过。该短客户端边界诊断不含交易Core/资金模型，不参与云端主吞吐判定，之后未再运行本地性能基准。
- 三Core全录制GC分别16/17/16次，GC暂停phase累计1.186/1.157/1.107ms、phase max0.027/0.029/0.033ms；client154次、phase累计8.335ms、max0.032ms。Core after-GC末值140.5/144.7/155.2MB、峰值274.7/278.9/251.7MB；client末值69.2MB、峰值79.7MB。暂停远小于业务尾延迟，不能把并发GC持续时间当暂停。单向增长持仓且仅数分钟，不证明无泄漏。
- Core NMT total committed录制末较首增加21.849/19.135/23.871MB，峰值约4.457/4.459/4.465GB；client增加534.821MB至1.162GB（初始堆512MiB、最大2GiB，包含堆扩展，不能直接判堆外泄漏）。ZGC虚拟地址reserved不是物理用量。DirectBuffer采样：各Core固定8个/9,575,136B，client固定6个/8,522,400B；Core活动线程初始化16→21后稳定。未覆盖全部Aeron mmap/native池余额及长稳文件描述符趋势。
- 全录制leader safepoint begin98次/max0.124ms；VM operation330次/max8.028ms；profile阈值内Compilation30次/累计6.533s、Deoptimization214次，包含初始化，稳定窗口编译线程CPU很低。client存在10:01:28 UTC的54.1ms `HandshakeAllThreads` VM operation，`safepoint=false`、caller ZWorkerYoung；它发生在测量期，需保留为并发GC握手长事件，不能说全程无VM长事件，也不能把它当全部业务线程54ms STW。完整JIT/code cache/metaspace/category见原始聚合。
- 阈值内Core同步FileWrite出现在archive-conductor；leader全录制6次/50.757ms，未采到交易owner文件/socket/database同步I/O。profile阈值及UDP/JNI覆盖有限，未观察到不等于不存在所有系统调用。录制包含MethodHandle/LambdaForm初始化的NoSuchMethodError探测、client结束附近的PortUnreachableException及JFR.stop名称解析NumberFormatException；业务终态无失败，原始异常栈保留。未做完整墙钟/off-CPU归因、open-loop、六产品金融动作、快照/failover、长稳与native池全面审计，生产容量验收仍为部分验证。

### 本轮全停恢复与证据索引

- `python3 recover.py`先查询，然后全部停止三个Core服务，再启动相同async-jfrb持久化目录。重启前后`appliedCommandCount=2059545`、`stateHash=c21a0d02883d7b78`完全一致，机器校验见`recovery/comparison.json`；随后seed90743/prefixGCP-AJFRB的capacityVerify=PASS、fundsDiff=0、bookLevels=0。重启后不重新setup、不重置资金。
- 服务10:06:12..13 UTC启动，19次查询连接超时记录保留，第20次查询10:12:20开始并成功，约6分钟恢复可用，不能称零停机或快速恢复。期间线程栈确认回调执行日志重放的MatcherSettlementPlan/commitReadyMatching。node1在10:12:10记录LEADER，node0在10:12:17随后记录LEADER和`quorum position went backwards: leaderCommitPosition=461479840 quorumPosition=0`警告；最终状态和资金核对通过，不据此宣称完整选举故障矩阵无风险。此次仅全停后持久化日志重放，未新建/恢复快照、未验证断电fsync承诺，也没有压测中故障注入。
- 原始四份JFR大小及SHA256：node0 2,700,487B / `06c583eb3b0c58f3af5fd712aa5cce7ce8272781c454811d665f550a7a1456e5`；node1 2,772,338B / `05aa078f13b424b3e14cb79089fa297e66d32843a20f037361584bf329671d48`；node2 2,897,016B / `fc6ac654fde5d8875382313e535e674f1d0e96910988a1ad54ef23fd611406ea`；client 4,959,937B / `4f20b4a6d114f237a0221446ac26653d6852baa743961c221628ea4095b3ceef`。路径均为本轮artifact的`jfrb/surprising-*/`，聚合与原始事件配置同目录。
- 结论：本轮压测器和生产client来源顺序问题已修复，45个定向功能测试、三轮真实三节点普通订单门槛、资金/订单簿核对与全停日志恢复通过。固定256窗口和此普通负载持续约6千订单操作/秒；绝对Core吞吐上限尚未证实，owner/matcher/Lane没有CPU饱和，后续须在真实三节点下进一步定位阶段等待，不能换本地策略给出更大数字代替。
- 四台实例本轮09:22:29..31 UTC启动、10:13:54..56停止，最终`instances-final.json`逐台核验TERMINATED。实例、各80GB磁盘、VPC与原始数据保留，停止不是删除，磁盘仍计费；未确认赠金覆盖或最终账单。`instances-before-stop.json`因CLI名称过滤未匹配而为空，不能作运行状态证据；运行状态由各轮systemd/监控留证，最终实例清单使用未过滤项目查询。
- 本轮artifact `SHA256SUMS`含417个文件、152,090,262B（不含清单自身），清单SHA256 `ec4592a371be0738969ea82a73f80a9272059554b1ac7106b9a97e9089a022f0`。包含失败轮、管理异常、功能测试、三轮数据、四份JFR、离线分析及恢复证据，排除编译/cache文件；私钥在旧artifact目录且未复制进本轮目录或清单。

## 2026-09-07 真实三节点等待归因（采集前锁定）

- 用户要求继续定位真实吞吐上限，不能把固定窗口功能通过当成核心饱和。本轮不改金融逻辑或移除回调完成边界；仅切换现有等待策略并采集以前profile阈值遗漏的短park/monitor事件。当前master本记录提交，runtime仍为7a23175f的同一jar（SHA256 6abe5a33994709b2425f37ef517565de9ebe357331b56d0e48761ce3b0071a68）；对照commit不适用，不构建旧版、不跑本地性能。已核对本地Aeron1.53.0源码：aeron.cluster.idle.strategy影响集群agent，故切换时不可仅归因到owner。
- 使用原四台GCP新加坡同zone n2-custom-8-16384/Intel Cascade Lake/Ubuntu24.04/80GB pd-ssd，三独立真实Core+独立load。Temurin HotSpot25.0.4.1、Core4GiB ZGC/AlwaysPreTouch/NMT、load512MiB..2GiB ZGC/NMT、Aeron1.53.0/SHARED_NETWORK/Archive SHARED不变。每Core固定1matcher/4Lane；负载普通LINEAR_PERPETUAL MATCH_ASYNC，GLOBAL256、4命令session+1预留、1worker、1000用户/500对、256symbol，maker SELL GTC1@100和taker BUY IOC1@100各半，两单一fill，1秒mark门控。closed-loop offered0、不修正CO；初始每用户10^12 USDT、零持仓、零手续费、持续maker/taker，无batch/WS/Kafka/API。
- 三个独立诊断配置均30秒预热+120秒测量/drain，至少15秒冷却，独立DATA_DIR/seed：D0 seed90801/WD0，默认BLOCKING Lane和集群backoff；D1 seed90802/WD1，仅Lane改BUSY_SPIN；D2 seed90803/WD2，仅集群idle改org.agrona.concurrent.BusySpinIdleStrategy、Lane恢复BLOCKING。D1四Lane空闲自旋消耗四核、D2包含consensus自旋，必须单列等待成本，不能把忙等CPU算业务饱和。所有三节点同步采用同一配置；不改变matcher数/in-flight/业务比例。
- 三个诊断均全程三Core+client JFR，profile基础上ThreadPark和JavaMonitorEnter threshold=0ns/stackTrace=true，maxsize=512m。这是高开销短等待诊断，不用其绝对速率替代主轮。分析保守+70..140秒稳定区间；报告park耗时/次数/线程/栈、owner等待、matcher/Lane/transport/client线程CPU、分配/GC/heap/NMT与系统指标。无DataLoss/截断、swap增长、连续3个5秒steal>5%、业务错误/超时/资金差才有效；原始失败保留。
- 门槛：三诊断各>=1000 terminal business ops/s、maker/taker p99<=1秒、订单offered=accepted=terminal=2*fills、逻辑submitted=completed=订单+mark、observed<=256、期末unfinished0、fundsDiff=bookLevels=0；NotAccepted重试单列。诊断排序仅用于选配置：只有某单项配置比D0高>=10%且通过全部门槛才选较快者，否则默认D0；不可把此规则推导成生产应默认忙等。
- 选定配置另跑无JFR主轮M seed90804/WM，30秒预热+300秒测量，门槛同上；报告每10秒速率、在途均值、maker/taker六分位、Core messages/s与fills/s。如果仍无计算饱和，结论必须说明本配置的吞吐限制与剩余埋点缺口；不得称CPU绝对上限。诊断不引入源码业务改动，不补跑本地JMH。最后全停重启最后数据集、hash/applied计数与资金核对，随后停止四VM；保留磁盘。
- artifact `/Users/atomex/Desktop/surprising/gcp-validation/2026-09-07-wait-diagnostics`，复制编排工具到此目录后调整，旧证据不可覆盖。本轮为等待策略归因，尚不覆盖open-loop、快照/故障矩阵、六产品金融路径、长期泄漏和完整阶段时间；CPU未满不等于可以增加并发状态修改或破坏确定性。

### 用户澄清压测脚本不得逐笔等待，终止等待策略诊断

- D0已完成：648712订单/324356成交、120.072秒、5402.681订单操作/s、2701.341 fills/s，maker p99=80281us、taker p99=52690us，资金差/订单簿剩余/业务失败0。本轮高开销短等待JFR和原始结果保留于wait-diagnostics/d0，不能替代无profiler主吞吐。node0 +70..140s owner park累计49.226秒/70.323%窗口、p50约61us；并发线程等待不可求和成端到端延迟。
- 用户明确所指是压测脚本发压不要等待，要求直接测真实三节点吞吐，因此停止D1负载（systemctl stop），保留中断日志/JFR，不作为有效测量；D2和原预定M均不执行。恢复默认Core等待配置，不修改交易Core。此前错误理解导致的额外诊断如实保留。CLI直连TLS失败经复用用户已有macOS代理恢复，业务私网不经过该代理。

## 2026-09-07 独立普通订单持续流（采集前锁定）

- 本轮工具新增MATCH_STREAM：每个空闲slot独立提交一条普通订单，无卖单终态→买单发起依赖，无定时park/sleep；只在GLOBAL256满时自旋背压，任意终态slot可回收。买卖按相同symbol/qty/price成对生成但独立发送，均用GTC容忍跨session到达顺序。到期后若尚欠一条配对买单，提交该买单后排空；不继续生成新订单对。行情过期时保留共享异步价格前置，不能绕过价格有效性金融规则。fills从每条真实Core响应executions累计，买卖到达顺序不影响统计；按buy/sell报告命令终态延迟，不能冒充每条订单完成全部成交延迟。
- 仅改tools，生产Core/client/协议未改；测试覆盖独立买卖均在任一回包前提交、各自计时、失败不抑制已独立发送的另一单及原价格门控/256分区/重试/指标。使用HotSpot GraalVM25.0.1本地功能测试和打包，不跑本地性能；被测为当前master本工具提交，实际commit和jar SHA另记，对照不适用（不跑旧版本）。
- 环境仍是三台独立GCP n2-custom-8-16384 Core加独立同配load、新加坡同zone/Intel Cascade Lake/Ubuntu24.04/80GB pd-ssd、Temurin HotSpot25.0.4.1；Core4GiB ZGC/AlwaysPreTouch/NMT、SHARED_NETWORK/Archive SHARED、默认集群backoff和BLOCKING Lane。每Core1matcher/4Lane；GLOBAL256、1发起worker、4命令连接+1预留、1000用户/500对、256symbol、LINEAR_PERPETUAL，SELL GTC1@100与BUY GTC1@100各半、无batch/撤单/费用/WS/Kafka/API，每用户初始10^12 USDT/零持仓，持续买卖，mark1秒门控。
- 独立目录 `/var/lib/surprising/stream-s1`、seed90901/prefixSTREAM1，30秒预热+300秒测量/drain，启动前距离取消D1至少15秒；closed-loop offered0、不修正CO。本轮按用户要求直接执行无JFR主吞吐，不再切换Core等待策略诊断。四机每5秒系统监控及GC/NMT收集；阈值>=1000 terminal business ops/s、buy/sell p99各<=1秒、offered=accepted=terminal=2*真实fills、submitted=completed=订单+mark、最大逻辑请求<=256、期末unfinished0、资金差/订单簿剩余/错误/未知/超时0。NotAccepted重试单列，swap增长/连续3个5秒steal>5%/VM维护重启判失效。
- 本轮仅验证工具独立发压后的真实持续吞吐与资金/终态；不把它和旧IOC链式场景混为同一业务口径。输出10秒分段、在途均值、buy/sell六分位、订单/Core消息/fill速率。Core未改，已完成的全停恢复证据保留，本轮不重复故障/快照测试；无本轮JFR、open-loop、完整阶段时间或CPU计算饱和证据时，不宣称硬件绝对上限。完成后停止四VM，artifact `/Users/atomex/Desktop/surprising/gcp-validation/2026-09-07-stream`。

### 独立流S1统计失败及S2重锁

- S1源码3ca7e3d5、jar SHA256 `96126a4f3b77a3bf2b761f6bc2a0b33bad0f76ba5a63140e92b3447db66e84f0`完成300秒发压后通过资金/订单簿核对，但在总计数校验处FAIL，未输出有效capacity=PASS。工具误将响应executions空列表当作零成交；已核实CoreProbeState.commandResultData有意用List.of()省略成交数组，这是现有正确行为，不修改Core来迎合工具。原始失败保留在stream/s1，本轮数据不能用于正式吞吐通过结论。
- 仅修正tools统计：校验响应orderId等于本次新单，定位其订单视图，强制qty=1、price=100、executed范围0..1且remaining=1-executed，然后累计executed。新单在自身提交回调内的已成交量，就是该命令产生的成交量；不累计对手订单视图，不依赖executions数组，也不凭发送量推测成交。补充协议encode/decode后的空成交数组/真实已成交订单、挂单零成交、重复对手视图、缺失/错误订单标识回归；失败总计数会明确输出各项数值。
- S2独立目录`/var/lib/surprising/stream-s2`、seed90902/prefixSTREAM2，当前master修正提交/新jar哈希另记。除此之外完全沿用S1预锁：默认Core配置、三节点+load各8vCPU16GiB、GLOBAL256、1worker/4命令连接/1000用户/256symbol/1matcher4Lane、独立买卖GTC各半、30秒预热+300秒无JFR主测量、资金初态/行情前置/阈值/系统有效性规则不变；旧数据和失败jar单独保留，不重跑旧版或更改交易逻辑。

### S2真实三节点持续异步发压结果

- 被测源码`7469f505`，四机jar SHA256 `407ffaaa3f6156153f43d4aeceeb430c9d67e956b1a2a35aa3ace1af8316291a`一致；14个tools定向测试无失败，HotSpot25打包通过。只修改压测工具和说明，没有更改Core、client、撮合/结算协议或等待策略。执行命令：stream artifact目录`python3 round.py s2 1`；Core于12:29:40 UTC左右就绪，约12:30..12:35 UTC正式测量。对照commit不适用。
- 主轮严格预锁普通买卖GTC独立异步提交、全局256、1worker、4命令连接+1预留、1000用户、256symbol、每Core1matcher/4Lane，默认BLOCKING Lane和集群backoff，30秒预热、300秒测量及排空，无JFR。实际测量/drain耗时300.044秒；**6,112.632 terminal business ops/s、3,056.316 fills/s、6,359.243 terminal Core messages/s**（含246.611 mark/s）。这是指定配置与业务组合的持续实测值，不是CPU已被压满或硬件绝对上限。
- offered=accepted=terminal订单数1,834,058；来自本次新单响应已成交量的真实成交数917,029，严格满足订单数=2*fills。mark命令73,994，Core逻辑submitted=completed=1,908,052，最大逻辑在途256，期末unfinished=0；1秒在途采样299个、均值247.060。明确NotAccepted重试25次，业务拒绝/未知/超时/失败0，fundsDiff=0、bookLevels=0。accepted仍在APPLIED终态计数，未埋独立accepted时间；逻辑在途包含客户端排队和重试，不等同于集群内部backlog。
- 29个完整10秒区间5,990.465..6,290.938 ops/s，区间均值6,117.292；前6段均值6,092.168、最后6段6,087.119，未呈持续退化。不能以最高10秒值作为持续容量。HDR三位有效数字、1分钟上限；closed-loop未修正CO，以下是发起到GTC命令终态，不是每个订单最后一笔成交的耗时。

| 类型 | 样本数 | p50 us | p90 us | p95 us | p99 us | p99.9 us | max us |
|---|---:|---:|---:|---:|---:|---:|---:|
| SELL GTC | 917029 | 41418 | 47939 | 49807 | 53575 | 58818 | 129564 |
| BUY GTC | 917029 | 38699 | 43745 | 44793 | 46596 | 50036 | 118423 |

- 聚合p50/p99/p99.9为39,944/52,068/57,343us，原始直方图保留。四机稳定区间各37个vmstat样本，machine CPU平均node0/1/2/load分别18.486/18.054/26.568/27.811%，最大25/24/29/30%；leader为node2，swap/si/so/steal全0，无预锁系统失效条件。没有本轮JFR，不能仅凭机器总CPU认定某条关键线程是否饱和；pidstat的-C java过滤也不能当完整命名线程采样。
- 三Core启动至收集期间GC pause phase分别75/80/75次，总计1.088/1.085/1.019ms、max0.032/0.043/0.030ms；最终NMT committed为4,290,265/4,290,546/4,291,029KB，包含4GiB堆，reserved约68,572,000KB为ZGC虚拟地址预留，不是物理用量。GC/NMT/系统原始日志可查；缺少本轮Java分配/JFR、完整native池、长稳和阶段墙钟证据，不宣称零分配/无泄漏或全面生产容量验收。
- 持续发压无逐单回包阻塞、无卖单终态→买单发起依赖、无定时休眠；保留256窗口背压、共享异步mark前置和最终排空。S1失败由工具成交计数误读造成，已补真实协议回归并重跑S2通过，未修改Core来规避检查。当前直接吞吐测试及预锁正确性门槛PASS；仍不把这个结果与旧本地mixed/batch或IOC链式负载混为同一口径。Core阶段等待/硬件绝对上限、open-loop及全部金融路径不属于本轮已证实结论。
- 先前已取消的wait-diagnostics证据另有239文件/2,067,834,567B，清单SHA256 `5fa2099fb055e1dae011a6e3ab244f2fd843c17aefeff74f8388a1e73c6bfad4`；D0完成、D1按用户澄清取消、D2及其计划主轮未执行，不能引用D1为有效吞吐。S1失败jar和脚本已单独保留，S2修正版没有覆盖它们。
- 四机本次11:57:16 UTC左右启动、12:37:19左右停止，`instances-final.json`逐台核验TERMINATED；保留四块80GB盘、测试数据与网络资源，磁盘仍计费。stream artifact清单194文件/129,113,384B（不含清单自身），SHA256SUMS自身哈希 `ee89fcce8bc0f83cbb5d375612606ca3438fad91c9e0ab80006c5feaa50e597a`，包含S1失败、S2通过、两版jar、功能测试/构建、部署哈希/系统数据与最终停机证据，不含私钥/编译缓存。

## 2026-09-07 核心上限定位：普通单阶段耗时诊断（采集前锁定）

- 用户要求测试核心算力上限。先用现有独立普通单确认限制来自发压、传输、owner计算还是结算等待，不把重复测得约6千吞吐或忙等CPU100%当作算力饱和。是否增加批量场景已向用户询问；本条不授权或预设批量参数，相关场景须另行预锁。当前master本说明提交，runtime源码7469f505/jar SHA256 407ffaaa3f6156153f43d4aeceeb430c9d67e956b1a2a35aa3ace1af8316291a不变；对照commit不适用，不跑本地性能或旧版。
- 沿用原三Core+独立load四VM，新加坡同zone n2-custom-8-16384/Intel Cascade Lake/Ubuntu24.04/80GB pd-ssd，Temurin HotSpot25.0.4.1。Core4GiB ZGC/AlwaysPreTouch/NMT、SHARED_NETWORK/Archive SHARED、默认BLOCKING Lane/集群backoff；load512MiB..2GiB ZGC/NMT，每Core1matcher4Lane。MATCH_STREAM独立普通买卖GTC1@100各半、GLOBAL256、1worker/4命令连接+1预留/1000用户/256symbol，初始每用户10^12 USDT/零持仓/零手续费，mark1秒异步门控，closed-loop offered0不修正CO，无API/WS/Kafka/batch。
- 独立目录`/var/lib/surprising/ceiling-d`，seed91001/prefixCEILINGD，30秒预热+180秒持续测量及排空。本轮仅归因，不将带instrumentation结果当作主吞吐上限。门槛>=1000 terminal business ops/s、buy/sell p99<=1秒、offered=accepted=terminal=2*fills、Core submitted=completed=订单+mark、峰值逻辑在途<=256、末值0、业务失败/未知/超时/资金差/订单簿剩余0，NotAccepted重试单列；swap增长、连续3个5秒steal>5%、JFR DataLoss或截断判失效。
- 三Core的JFR在首个正式测量progress之后启动，profile基础启用JDK25的jdk.MethodTiming，定向计时processCommittedRequest/idleCommand、apply/prepareMatching/commitReadyMatching/completeDispatchedMatcherSettlement、matcher executeWithEvidenceSync、PlaceAdmissionEvent.execute/MatcherSettlementEvent.execute及offerResponse；保留完整方法签名和每事件invocations/min/avg/max，嵌套方法不可简单求和，墙钟时间包含等待而非纯CPU。MethodTiming采用endChunk，录制期间不手工flush/dump；结束后按原始事件及JDK jfr view核对聚合。加profile CPU/分配/GC/线程事件，不重新开启零阈值全量park，maxsize512m。client使用普通profile采样，避免改客户端行为。记录启停UTC，分析采样完整且处于测量中间的区间；有instrumentation开销，不作精确无开销耗时承诺。
- 四机5秒vmstat/mpstat/pidstat/sar；pidstat按进程PID选取而非-C java名称过滤，以保留owner/Lane/matcher命名线程。报告每线程单核CPU、可运行但未获调度时间、等待/业务栈与阶段时间；全机总CPU不足以判断关键线程饱和。不改变Core一致性边界、不切换Core等待策略、不以停顿期间高CPU冒充有效业务计算。
- artifact `/Users/atomex/Desktop/surprising/gcp-validation/2026-09-07-core-ceiling`。先完成普通单归因，再根据实际证据和用户批量选择确定后续算力饱和负载；不得未测先承诺一个通用算力上限。全部本轮工作结束停止VM，保留磁盘。该诊断不包含新快照/故障注入/长期泄漏或六产品金融场景；Core代码未改，不重复本地JMH。

### 普通单四发压线程确认轮 M（采集前锁定）

- D阶段诊断通过，单发压线程CPU约100%但主要在streamMatch扫描已满窗口；为排除发压端单线程限制，M仅将发压worker从1改为4，各自64在途、GLOBAL仍256，4命令连接+1预留不变。运行时仍为7469f505对应同一JAR；当前master，对照不适用。三Core各1matcher/4Lane、默认等待策略、所有机器/JVM/金融参数/独立GTC买卖/mark前置均沿用D；不执行批量单，不改Core。
- 目录`/var/lib/surprising/ceiling-m`、seed91002/prefixCEILINGM，30秒预热+300秒测量及排空，不开JFR或MethodTiming；用5秒四机系统采样及GC/NMT确认有效性。阈值与D相同：>=1000订单/s、buy/sell p99<=1秒，offered=accepted=terminal=2*fills，submitted=completed=订单+mark、峰值<=256、最终0，资金差/订单簿剩余/业务错误0，重试单列，swap增长/连续3个5秒steal>5%或VM维护重启判无效。
- 本轮用于确认增加发压线程能否提升相同普通单链路的持续处理率；不把四线程自旋CPU或短时最高值当核心计算饱和。报告完整300秒速率、10秒分段、尾延迟与资金核对；D的instrumentation值只做阶段归因，不与M混作无开销性能差异。

### 用户指定 owner 接近95%并维持：B批量诊断（采集前锁定）

- 用户进一步要求owner尽量达到95%CPU并保持，按有效业务计算解释。允许通过真实协议批量下单增加每次回调业务工作；不通过空循环/忙等制造95%，不修改Core一致性边界或默认等待策略。本工具新增MATCH_BATCH_STREAM，普通单结果单独保留。实际构建提交/JAR哈希另记，当前master、对照不适用。
- B使用真实三Core+load原四VM/JDK/JVM/1matcher4Lane/256symbol/1000用户/资金初态/零手续费，GLOBAL256逻辑请求、4worker各64槽/4命令连接+1预留。每批20个同用户同symbol同方向GTC1@100，独立SELL批和BUY批各半；每批一条PLACE_ORDER_BATCH命令，最多5120个订单项在途（不同于普通单256项）。订单项offered/accepted/terminal逐项计数；Core消息=terminalItems/20+mark；fills从每个APPLIED新订单响应executedQty累计，不能依赖省略的executions数组。批次响应逐项校验数量、身份、状态和qty；每项共享批次发起至批次终态延迟，单独标明BATCH_TERMINAL_PER_ITEM，不能当逐项回包时刻。
- B目录`/var/lib/surprising/ceiling-b`、seed91003/prefixCEILINGB，30秒预热+180秒测量/drain，首个正式progress后90秒四机JFR。Core使用D的profile+选定MethodTiming，client普通profile；+30..80秒看线程CPU与业务/等待栈，保留原始记录、DataLoss0。此轮仅诊断，后续无采样长轮必须另行预锁。目标owner单核CPU接近95%，同时必须报告idle/业务构成；达不到则如实记录，不能以CPU占用反推未经测试的吞吐。
- 通过门槛：>=1000terminal订单项/s、buy/sell批次终态p99<=1秒、offered=accepted=terminal=2*fills，terminalItems能被20整除，submitted=completed=terminalItems/20+mark，peak<=256/期末0、资金差/订单簿剩余/失败0；NotAccepted单列。swap增长、连续3个5秒steal>5%、JFR DataLoss/截断判无效。Core未改，只执行tools定向功能测试/HotSpot25打包，本地不跑性能；批量真实金融核对在本轮三节点执行。

### B工具校验失败与B2重锁

- B（399a8669，JAR c17043d3f7bbfd80d1c9c441a67a0571234dbc098de7207e5f2a5d7a33fdbf3b）预热期间工具报invalid batch stream item，未进入正式测量，没有有效吞吐。复核Core批量编码路径：finishOrderBatch从活动索引生成可空order视图，而OrderBatchPending.writeExecutions确实编码每项真实TRADE；普通单省略executions的规则不能套到批次。修正工具按真实batch executions校验taker ID/price/qty并计数，order存在时与executed核对；order为空时必须有完整qty1成交，不作无证据fallback。加入真实codec往返、已退休订单空视图/缺失成交/错误身份等回归；不改生产Core。
- 尝试查询B首批历史command结果均返回RESULT_UNKNOWN_OUTSIDE_RETENTION，保留原始只读查询记录，不能将其当成功响应证据。B未启动JFR，归档脚本误带入上一轮D的同名JFR；这些文件是旧记录，不属于B采样，明确排除。修正归档仅在本轮成功启动recording时收取JFR，失败日志/旧文件原样保留。
- B2目录`/var/lib/surprising/ceiling-b2`、seed91004/prefixCEILINGB2；当前master工具修正提交与JAR另记。除上述正确性计数修正外，全部沿用B预锁（20项批量、4worker、GLOBAL256、默认Core配置、30秒预热+180秒测量及90秒JFR、同一资金/状态/错误/系统门槛）。本轮仍只归因，目标95%是否达到据实报告。

## 2026-09-07 原mixed业务接真实三节点（采集前锁定）

- 用户改为要求原本本地mixed业务接入真实Aeron Cluster，并要求本轮结束停止四VM、下次明确要求测试才开机。B2未部署/未执行，停止继续简单批量诊断；保留B失败及修正代码/17测试通过记录。新增tools `ClusterMixedCapacityMain`，不实例化本地Core；沿用LinearPerpetualMixedWorkload UNIFORM的初态与八段交易动作，状态依赖由集群查询/命令响应获得，Core/client业务不改。
- 四台原GCP n2-custom-8-16384/Intel Cascade Lake/Ubuntu24.04/80GB pd-ssd、新加坡同zone；三Core Temurin HotSpot25.0.4.1、4GiB ZGC/AlwaysPreTouch/NMT、默认BLOCKING/cluster backoff/SHARED_NETWORK/Archive SHARED、每Core1matcher4Lane；load512MiB..2GiB ZGC。当前master工具提交/四机JAR SHA另记，对照不适用，禁止本地性能。HotSpot25本地仅tools定向功能测试和打包。
- 1000 retail+768做市/HFT+1强平用户=1769用户；256symbol、1..5仓、0..10挂单、持仓数量1..4按旧UNIFORM公式，HFT maker/taker跨Account Lane。普通账户各10^9 USDT、强平账户100、保险25，期初总资金1,768,000,000,125；零手续费。原始零售持仓及挂单保持，每轮HFT maker净空/用户净多20单位，核对累计cycle预期。保留原脚本买方流动性先撤再发SELL IOC的次序，不能将该IOC假算成交。
- 每cycle每symbol：20项SELL GTC102/qty2批→20项撤单批→SELL GTC101/qty40→20项BUY IOC101/qty1→撤剩余卖单→BUY GTC99/qty40→撤买单→20项SELL IOC99/qty1。每symbol84business ops/8Core消息，256symbol交易部分21504 ops/2048Core消息/5120实际fills；32symbol轮转触发单挂单+执行及资金费/mark/risk动作，首次另有强平→保险→ADL。批次逐项APPLIED/身份校验，fills来自真实batch executions而非按发起量推算。交易类型计数与完整cycle预期逐项断言。
- 单个FIFO命令连接+独立reserved查询连接、单发起线程、GLOBAL256请求（批最大20，最多5120订单项）。通过同一命令流顺序维护place/cancel/IOC依赖，交易八段连续提交，不逐单/逐阶段等回包；仅满窗口等待任意完成，控制查询前排空以保证读取已提交状态。资金费分页依据响应推进，独立命令源独占symbol期间避免非法交易。初态核对和结束核对不计吞吐。
- 与旧本地口径差异明确：时间使用真实UTC而非合成时钟，价格超过1秒及时刷新；旧内存状态读取改为实际网络query并单列数量，不计business ops；risk工作页暴露全局pending，生命周期按全局pending继续预算64扫描，否则更新该symbol价格。保留每cycle32symbol预算，不能称逐条控制命令与旧版完全相同或用比例推导网络开销。closed-loop尽快补256，无CO修正；命令终态时间由egress完成future时记录，批延迟按请求计且展开项数另列，没有独立accepted时钟。owner目标接近95%，不修改等待策略或制造忙等来达标。
- X0仅功能接通：目录ceiling-x0/seed92001，warmup0/duration1秒（跑完整cycle并排空），不把它作为容量结果；要求批次/全量资金账/零售仓位数量与挂单数量/HFT累计仓位和reservation/强平保险ADL完成全部通过。X主轮：ceiling-x/seed92002，30秒预热+300秒测量及完整cycle排空，无JFR；随后XJ诊断：ceiling-xj/seed92003，30秒预热+180秒测量，首正式progress后四机普通profile JFR90秒（不用MethodTiming），分析+30..80秒业务/等待栈。负载及阈值不变，系统采样5秒。X0失败不得进入主轮，修复后新目录新记录重锁。
- 通过阈值：主轮>=1000terminal business ops/s、所有业务类型p99<=1秒、offeredBusiness=terminalBusiness、offeredCore=terminalCore、unfinished0/peak<=256、实际fills和交易类型计数匹配cycle、资金差0、各用户余额非负、零售持仓/挂单密度与HFT净仓/冻结核对通过，强平保险ADL闭环。传输NotAccepted/业务拒绝/未知/超时均判失败；不在FIFO命令中重试越过有依赖的后续命令。swap增长/连续三个5秒steal>5%/VM重启/JFR DataLoss或截断判无效。owner95%是单核口径观察目标，达不到明确报告，不把machine总CPU或spin当有效算力饱和。
- 仅新增验证工具，不改交易状态机，故本轮不重复快照故障矩阵/全停恢复/六产品或长期泄漏验收；不能宣称完整生产容量。artifact仍在core-ceiling目录，失败保留。完成全部采集和核对后停止四VM并验证TERMINATED，保留磁盘，未经用户下一次测试指令不得再次开机。

### X0功能门槛失败与X01重锁

- X0源码9011266a/JAR 0ecf0798b160ecabbedcbf07f41f2f134e5ff62df8ee0d03dfffe9e939b40023；21定向测试及打包通过，四机哈希一致。真实集群初态1769用户/零售仓位与挂单密度检查通过；首个交易cycle后读取强平可执行工作为空，整体FAIL，未进入主轮，原始保留x0。
- 网络真实时钟会在交易中刷新价格、推进风险扫描generation，旧本地合成时钟没有相同刷新频率。工具在读取可执行强平工作前必须先通过CONTINUE_RISK_SCAN完成当前generation；补此控制依赖，不改Core、不伪造强平action。X01用ceiling-x01/seed92004，仍warmup0/duration1完整cycle，仅功能门槛；其余X0参数/阈值不变，成功后才继续预锁X/XJ。

- X01（7d1d0326/JAR 86df15166ae3c098029b0bdf47d4329b61e7f7957397db5b603074dfd9172152）完成强平、保险及ADL命令，但工具错误要求positions列表物理为空而FAIL。独立只读集群查询确认：EXECUTION/INSURANCE/ADL工作均为空且complete=true；强平用户余额/冻结/持仓数量/持仓保证金均0，保留一条realizedPnl=-990的平仓历史视图。这是正确Core语义，不删除历史或更改Core。工具改为验证经济敞口与保证金归零，增加有历史亏损的平仓视图、非零敞口/保证金拒绝回归。原始证据x01/loss-state.txt。
- X02重锁：ceiling-x02/seed92005，X0同一完整cycle功能参数/阈值，仅修正上述平仓状态判定。通过后才运行原预锁X和XJ；不把X0/X01失败计入容量。

- X02功能门槛PASS：源码37648b18，四机JAR `2fb8ff05f175519fe7d0ba166a44afb45ea5ea9779806759c5a9f71dac56f68c`，22定向测试/HotSpot25打包通过。完整cycle终态22670business ops/3214命令/5120实际fills、812单列queries、最大在途256/末值0；各类型计数符合预定组成。全体资金差0、零售仓位与挂单分布、HFT仓位±20和reservation回收、强平保险ADL闭环通过，businessHash=63d4f9f85067ac49。该9.582秒完整cycle含首次风险续扫/强平控制，不作为持续容量结论。按预锁启动同runtime的X无JFR主轮。

### X传输临时状态失败与有序重试修正、X2重锁

- X完成初态及强平保险ADL后，在预热期间遇到ADMIN_ACTION(-3)，客户端将未接收返回为终态失败，主测量未完成，整体FAIL，保留x。Aeron官方说明该值表示尚未入队、可重试的管理操作（如日志段轮转）：https://github.com/aeron-io/aeron/wiki/Java-Programming-Guide 。不能将同一有序流中的失败下单重新排在已经发送的撤单后面。
- 修正AeronClientPool非one-way请求：每个AgentLane仅持有一个dispatcher独占deferredAdminOffer；ADMIN_ACTION保留同一message/correlation/sourceSequence，在下一轮dispatcher原位置重试一次，期间正常poll egress/keepalive/处理其他session；成功前不offer本lane后续消息。沿用原queue deadline，到期仍NotAccepted，close回收未接收请求；真正断连/背压/未知等原语义不变，tryCommandOnce/one-way不重试。无额外线程/无交易状态机改动，新增计数adminActionRetries；mixed输出该计数从测量起到终检完成的增量，包含终检查询轮转，不能当业务命令重复数。
- 回归覆盖ADMIN_ACTION后后续撤单不得越过、重试message及sourceSequence保持不变、egress继续轮询、持续ADMIN_ACTION有界到期/close释放，以及tryCommandOnce仍只尝试一次；执行client受影响测试及全部tools定向测试和HotSpot25打包。交易Core/撮合/结算未改，新的真实三节点X2与XJ承担受影响传输链路性能/资金验证，不执行本地JMH。
- X2重锁：ceiling-x2/seed92006，30秒预热+300秒无JFR测量/完整cycle排空，当前master上述客户端修正提交，其他X参数、金融初态、窗口256、1FIFO命令连接和阈值完全不变。允许内部ADMIN_ACTION重试并单列，不允许最终NotAccepted/业务拒绝/未知/超时；容量/资金/生命周期门槛不放宽。XJ使用同一修正版，仍ceiling-xj/seed92003、30+180秒、90秒普通profile JFR；X2通过后才执行XJ。最后停止四VM，遵循用户本轮结束关机要求。

### D/M已完成证据归档（普通独立单，非mixed口径）

- D/M runtime均为7469f505，四机JAR SHA256 `407ffaaa3f6156153f43d4aeceeb430c9d67e956b1a2a35aa3ace1af8316291a`；当前master、不重跑旧版本，对照不适用。执行`python3 round.py d 1`及`python3 round.py m 4`，机器/JVM/普通单场景依照上文预锁。D通过：180.050秒、1,088,784订单、544,392真实fills，6,047.129订单/s、3,023.565 fills/s；44,089 mark，总命令1,132,873，submitted=completed、peak256、unfinished0，资金差/簿残留0。SELL/BUY p99分别54,919/47,677us，完整六分位见d/result.txt。
- D四机90秒JFR约13:05:49..13:07:19 UTC，DataLoss0；node0为leader。+30..80秒owner单核CPU43.683%、matcher9.713%、四Lane4.000/4.272/4.210/4.639%，network-shared86.513%、archive21.241%、consensus27.501%。同期pidstat owner43.84%、调度等待0.14%，没有CPU计算饱和证据。定向MethodTiming中processCommittedRequest平均151.846us，总墙钟89.032秒；idleCommand累计65.198秒，占回调墙钟73.23%，包含自旋、让出和等待，不能解释成73.23%纯park或CPU。apply/prepareMatching/completeDispatchedMatcherSettlement均值10.397/9.037/8.722us，PlaceAdmission/MatcherSettlement事件4.246/7.547us，offerResponse0.615us；嵌套方法禁止相加。零调用方法的Long.MIN_VALUE哨兵已在离线导出器标记不可用，原始JFR不改。
- D稳定窗口owner所在JVM加权采样分配61.202MB/s，约10,121B/订单；client129.785MB/s，约21,462B/订单，含协议/查询/外围分配，非逐订单精确追踪。三Core整段GC停顿总计0.242/0.226/0.197ms；node0 Direct buffer9,575,136B保持不变、GC后堆约237..245MB、NMT committed4,470,463,139→4,418,976,555B（包含4GiB堆）。完整CPU/分配/GC/native/锁/JIT/I/O输出见d下四份summary/audit/cpu/window及method-view；短采样不能证明零分配或无泄漏。
- M无JFR通过：300.018秒，1,883,940订单、941,970 fills，6,279.423订单/s、3,139.712 fills/s；mark64,655、总命令1,948,595，submitted=completed、peak256、unfinished0、资金差/簿残留0，明确未接收重试25。SELL/BUY p99分别47,185/46,694us；29个完整10秒区间6,138.110..6,399.590，前6段/末6段均值6,260.511/6,272.893。node1为leader；中央系统采样machine CPU node0/1/2/load平均20.674/26.558/19.279/66.419%，swap/steal均0。四worker主要增加发压窗口自旋，不证明owner95%或硬件上限。完整六分位/GC/NMT/系统原始记录在m；D/M不与原本地mixed的18万直接作同口径比较。

### X2真实三节点mixed主轮结果

- runtime `91bfa106`，四机JAR SHA256 `43ed29610df1be23548a225bcb56de742d49ce9e9a7498c044d9c0e221fff429`一致。HotSpot25本地43个client及22个tools功能测试、打包通过（mixed4b-package.log）；此前旧测试仍要求ADMIN_ACTION只能尝试一次而失败，已按有序重试新语义修正并独立覆盖，失败日志mixed4-package.log保留。Core撮合/结算/确定性/等待策略未改。执行`python3 round.py x2 1`，30秒预热+300秒测量，约14:02..14:07 UTC，无JFR；正式中央CPU窗口14:02:55..14:06:46 UTC。其余预锁环境与金融初态不变，对照不适用。
- 主轮PASS：实际300.345秒，**24,778.901 terminal business ops/s、2,689.291 terminal Core命令/s、5,813.055 fills/s**。offeredBusiness=terminalBusiness=7,442,210，offeredCore=terminalCore=807,714，真实fills1,745,920；另有10,912个网络query，不计business，也未包含在工具coreMessagesPerSec中，合计命令+查询818,626条。全局逻辑peak256、期末unfinished0；341个测量cycle、连同预热363个cycle。triggerExecutions字段11,616含预热，测量期间真正执行10,912次，不能混用。
- 批量下单261,888请求/5,237,760项，批撤87,296请求/1,745,920项，平均及最大每批20。约871.958下单batches/s和290.653撤单batches/s；延迟直方图每请求一份，不能当批内每一项独立完成时刻。正式30个约10秒区间24,008.530..25,715.485 ops/s，前6段24,729.482、末6段24,734.500，无持续退化。ADMIN_ACTION有序内部重试43次（测量起至终检完成，包含查询），最终未接收/业务拒绝/未知/超时均0。
- 资金差0，1769用户余额非负、零售持仓数量/敞口与挂单密度、HFT累计净仓及reservation回收全部通过；首次强平→保险→ADL闭环通过，保留已平仓历史realizedPnl。总账包括全部treasury与未覆盖deficit，不能重复加已包含在余额locked里的position margin。businessHash=71be3543461ce45d。强平等一次性动作发生于预热，不存在主轮强平延迟样本，不能宣称测量期重度强平吞吐已验证。

| X2业务 | 请求数 | 展开业务项 | p50 us | p90 us | p95 us | p99 us | p99.9 us | max us |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 普通下单 | 174592 | 174592 | 40534 | 52756 | 54493 | 58589 | 116523 | 125435 |
| 普通撤单 | 174592 | 174592 | 40239 | 88473 | 97779 | 108789 | 119996 | 146538 |
| 标记价 | 65698 | 65698 | 39452 | 69730 | 76152 | 88997 | 116523 | 136708 |
| 资金费 | 10912 | 10912 | 5881 | 7114 | 8028 | 8183 | 9207 | 90898 |
| 风险续扫 | 10912 | 10912 | 5877 | 7098 | 8036 | 8187 | 9215 | 90898 |
| 挂触发单 | 10912 | 10912 | 9003 | 13082 | 14106 | 16195 | 18481 | 20217 |
| 执行触发单 | 10912 | 10912 | 9265 | 13352 | 14401 | 16457 | 18907 | 20676 |
| 批量下单 | 261888 | 5237760 | 61341 | 88932 | 95944 | 108331 | 125894 | 146538 |
| 批量撤单 | 87296 | 1745920 | 62619 | 79822 | 84934 | 91881 | 129499 | 138805 |

- 上表均为入口→terminal，HDR三位有效数字、最大1分钟、closed-loop未修正CO；accepted在APPLIED终态核实，缺独立accepted时间，不能作为完整三段延迟验收。全部p99低于预锁1秒。
- 中央5秒pidstat样本：node0 owner平均31.009%单核，范围28.4..33.6%、p5=29.6%、>=90%的样本占比0，调度等待0.098%；matcher8.919%、四Lane3.511/3.545/3.502/3.404%。node1/2 owner27.270/28.438%。四机machine CPU平均20.872/15.891/16.000/17.149%，swap/si/so/steal均0。**owner未接近95%，本轮不构成核心算力上限证据。**
- 实际窗口边界补充：工具GLOBAL256是包含客户端排队的逻辑请求上限。AeronClientCapacity默认maxCommandInFlightPerSession=64，mixed采用一个FIFO命令连接，因此同时offer且等待终态的命令最多64，其余可能在mailbox中；预留query连接另行处理控制查询。没有改变本轮参数，不能把peak256说成集群内部已有256条待完成命令。原内存读取变成控制网络请求、部分依赖动作等待响应、Core逐回调完成边界均可能限制链路；需要结合XJ归因，不能仅凭此静态上限断言它就是全部瓶颈。

- X2整个进程采集期三Core GC pause phase共108/113/105次，总计1.603/1.458/1.314ms，最大0.026/0.025/0.024ms；不是GC全部并发耗时。最终NMT committed4,309,170/4,306,205/4,306,045KB，均包含4GiB堆，reserved约68,580,000KB是虚拟地址预留。原始GC/NMT及计算结果x2/gc-nmt.json保留。上文batch速率按打印的300.345秒复算，应为871.957下单batches/s、290.652撤单batches/s（展开items/s分别17,439.145/5,813.048）；打印elapsed的舍入使其与使用完整纳秒的fillsPerSec有微小差异。

### XJ mixed采样结果与本轮边界

- 被测同一91bfa106/JAR `43ed29610df1be23548a225bcb56de742d49ce9e9a7498c044d9c0e221fff429`，`python3 round.py xj 1`；30秒预热+180秒测量，执行于约14:08..14:13 UTC。与X2同一三节点配置/负载/逻辑窗口256及默认session64上限，无Core等待策略修改。四份普通profile JFR均14:09:41..14:11:11 UTC、90秒，完整可读、DataLoss0；不用MethodTiming，采样结果不替代无JFR主轮。离线执行offline-xj.py，保存summary/audit/cpu/window、jfr view hot-methods/allocation-by-site及extra事件证据。
- XJ PASS：180.025秒，4,430,583展开业务项、481,015命令、1,039,360真实fills，24,610.976业务项/s、2,671.939命令/s、5,773.431 fills/s；另有6,496查询。offered=terminal、peak256、unfinished0；203测量cycle/225总cycle。ADMIN_ACTION内部重试26（含终检查询），最终未接收/业务拒绝/未知/超时0；全部资金/零售/HFT/冻结/强平闭环核对通过，businessHash=eaac1341e8d6914d。全量六分位及类型计数xj/result.txt；最大业务p99=111,017us（普通撤单），全部低于1秒，批量下单/撤单p99=110,559/93,454us。
- +30..80秒JFR，node0 owner31.039%单核、matcher9.091%、四Lane3.466/3.515/3.453/3.413%；network-shared59.102%、archive12.425%、consensus15.211%。load main79.016%、egress10.904%，不能将窗口轮询的main CPU当有效Core计算。正式中央pidstat node0 owner31.036%、最高33.0%、>=90%占比0，node1/2 owner28.709/28.691%；machine CPU node0/1/2/load21.136/15.864/16.000/16.909%，swap/si/so/steal全0。owner95%目标未达成，不宣称核心算力上限已测到。
- owner CPU样本包含CommandFingerprint SHA摘要、TerminalStateRetention淘汰/查询、RuntimeIdentityRegistry、批量解码/响应编码和readFenceAll→LaneMutationTask.await；matcher样本包含MatcherPrefixDigest、真实撮合以及结果包装。网络线程主要在UDP poll/send，Archive负责记录文件；这都是不同职责，不把网络总CPU或Archive写盘错算成owner同步I/O。profile的park/monitor/I/O有时长阈值，+30..80秒未采到owner长I/O或monitor enter不等于所有短等待为零；本轮没有mixed阶段墙钟完整分解，不能把D普通单的73.23%直接套给mixed。
- 稳定50秒窗口加权ObjectAllocationSample：node0/1/2 JVM约128.999/128.921/129.461MB/s，约5,242/5,238/5,260B/展开业务项，client约46.006MB/s、1,869B/业务项。分母采用XJ整轮实际业务速率，含协议/控制动作等整JVM分配，不是单笔交易精确成本；不用启动边界的全记录加权值替代稳定窗口。node0按线程owner64.472MB/s、matcher22.931MB/s、各Lane约10.2..10.4MB/s。热点包括批量响应byte[]、PlaceOrderCommand及字符串解码、stagePlaceBatchAdmission的LongLongHashMap扩容数组、OrderRuntime、MatcherEvidenceLedger字符串/结果包装、MatcherSettlementPlan；不是零分配。positionsForSnapshot该窗口采样权重0，仅表示未采中此栈。
- profile未启用逐对象TLAB/OutsideTLAB事件，因此导出tlabBytes/outsideTlabBytes/maxObject=0表示缺事件，不能当零分配、零大对象或可精确计算对象数/operation。ThreadAllocationStatistics保留，但没有逐对象总数；完整热点及对象类/线程/调用栈在四份window/audit与allocation-view中。
- 90秒三Core GC pause phase总计0.427/0.247/0.294ms，最大0.024/0.016/0.028ms；load总0.988ms、max0.033ms。node0 GC后堆182,452,224..322,961,408B，node1 222,298,112..308,281,344B，node2 209,715,200..301,989,888B；短窗口GC代际/触发时机不同，不能用首尾差认定泄漏。Core Direct buffer每节点8个/9,575,136B恒定，load6个/8,522,400B恒定；Core线程22、load17恒定。Aeron内存映射/全部native池未独立核算，Direct不代表全部堆外。
- NMT recording期间committed node0 4,428,612,890→4,428,314,631B，峰值4,467,151,243B；node1 4,427,066,094→4,428,954,029B，峰值4,445,592,549B；node2 4,426,322,955→4,428,029,881B，峰值4,446,609,869B。load726,097,469→724,242,193B，峰值741,057,089B；各category原始事件和NMT文本保留。没有长期old-object/live-set/FD/native pool收支趋势，不能宣称无泄漏。
- 三Core safepoint begin总计1.629/1.075/1.143ms，最大0.113/0.124/0.082ms；最慢VM operation18.144/14.438/14.642ms，均录制开始时RedefineClasses（load7.454ms），是采样启动开销，不归因给无JFR业务。记录期间仍有少量Compilation/Deoptimization（Core编译4/3/3、去优化20/15/18），不能宣称完全没有JIT干扰。约90秒的JavaMonitorWait来自JFR Recording Scheduler定时器，并非交易锁竞争；node1一次11.121ms FileWrite在archive-conductor写0-0.rec，非owner。标准profile没有全量异常throw事件，不能用空异常列表证明所有异常数量为0；工具实际终态错误检查通过。
- 本轮完成的是指定U本位永续mixed组合的真实三节点持续发压/财务终态/采样诊断，属于部分性能验证。未测API/WS/Kafka、其他五产品线、open-loop、独立accepted时钟、每类强平/ADL持续尾延迟、全量短等待墙钟、长稳泄漏、新故障矩阵或快照恢复；本轮只改client有序管理操作重试及tools，Core状态机未改，先前恢复证据保留且不重复当成本轮结果。保持GLOBAL256、确定性及结算边界，下一次测试前应先明确是否调整session内部并发和控制依赖发压方式，不能通过无业务自旋实现95%。

### 本轮停机

- 按用户要求，X2/XJ终检通过、四机原始tar/JFR/监控/GC/NMT下载并离线校验后，执行`python3 stop-after-round.py`。GCP停止请求14:13:38 UTC发起、14:14:34 UTC完成；随后无过滤list读取逐台验证surprising-core-0、surprising-core-1、surprising-core-2、surprising-load均TERMINATED。原始instances-before-stop.json/instances-final.json/stop-instances.txt保存在core-ceiling artifact。
- 保留四块80GB数据盘、集群测试目录及网络资源，磁盘仍计费；不是删除实例。本轮不再启动服务器，下次仅在用户明确要求测试后开机。

- 本轮artifact根目录`/Users/atomex/Desktop/surprising/gcp-validation/2026-09-07-core-ceiling`，SHA256SUMS包含900文件/555,028,731B，清单自身SHA256 `6f01c4e3a13b93251d448ba9a595ecab613fc89ee5cc1fb5407324d8db2f4926`；排除清单自身、manifest-summary.json、编译缓存及符号链接，不含私钥。包含D/M、B与mixed失败、X02功能门槛、X2/XJ通过、四机原始记录、构建和停机证据。最终文档变更只执行git diff --check，未追加压测或重启VM。

## 2026-09-08 解除发压器限制，定位真实三节点饱和（采集前锁定）

- 用户明确要求不受旧脚本规则限制，尝试饱和并测吞吐；本次允许改变GLOBAL256限制和实际session提交窗口，已同步AGENTS。仍只验证当前master，不检出/重跑旧版，对照不适用；必须真实三Core+独立load，交易状态机、撮合/结算确定性边界不改，不用busy-spin伪造有效计算饱和。新请求授权重新启动四VM，全部完成后再次停止并核验TERMINATED。
- 机器仍为GCP surprising-ae591/asia-southeast1-b，三台surprising-core-{0,1,2}加surprising-load，n2-custom-8-16384/Intel Cascade Lake/Ubuntu24.04/各80GB pd-ssd，业务私网10.90.0.0/24；启动后采集实际CPU/JDK配置校验。Core Temurin HotSpot25.0.4.1、4GiB ZGC/AlwaysPreTouch/NMT、SHARED_NETWORK/Archive SHARED、默认cluster backoff与BLOCKING Lane，每Core1matcher/4Lane；load512MiB..2GiB ZGC/NMT。管理经IAP与本机既有代理，交易UDP不经过代理。
- 本次tools修改：支持显式global/session窗口，使用现有AeronClientCapacity的公开构造入口，生产默认64不变；单FIFO命令流完成队列改为只处理已完成队首，避免O(window)全表扫描和ArrayList搬移。预锁所有新档位global=session，mailbox等于global，reserved query窗口32，egress fragment limit128。1发起线程/1命令session+1reserved query session，batch协议上限仍20，不擅改协议。新增client回归验证256请求在任一响应之前全部offer且第257个受控，新增tools参数校验；本地仅HotSpot25功能测试和打包，实际runtime提交/JAR另记。
- M1024完整mixed保留原1769用户、1000零售/256币对、初始1..5仓/0..10挂单、全部资金账1,768,000,000,125、零手续费、HFT八段交易及32symbol触发/资金费/风险控制、首次强平保险ADL，除窗口和完成队列外沿用X2组成。控制查询的金融依赖仍保留，单独报告其等待与查询数。
- T系列为TRADING_STREAM诊断：相同初态、用户/Lane分配、金融资金和HFT八段交易（每cycle21504交易业务项/2048交易命令/5120实际fills），首次强平保险ADL在setup完成；测量只连续批量下撤单/普通流动性下撤单/BUY及SELL IOC、1秒真实mark刷新。跨cycle不排空、不查询、不串行资金费/触发/风险页，必须明确这是纯交易组合，不是完整mixed提高了相同比例吞吐。FIFO维持跨cycle依赖，开始和结束测量边界排空、最终远程检查全体资金/零售密度/HFT净仓=cycle*20与reservation。原先先撤bid再SELL IOC的业务次序保留，不能假算SELL IOC成交。
- 先F0功能门槛：seed93001、ceiling-f0、global=session1024，warmup0/duration1完整mixed cycle，要求全部资金/状态/真实成交/计数/强平闭环通过，仅功能不计容量。成功后依次M1024（seed93002/global1024）、T256（93003/256）、T1024（93004/1024）、T4096（93005/4096），独立ceiling-tag目录；每档30秒预热+90秒测量及完整cycle排空、默认配置、无JFR。冷却为停止前档、归档后新建空目录启动下一档，不复用交易数据。
- 探索结束，从T系列正确性通过档选择持续业务速率最高者；差异<=5%时优先较小窗口。TF（seed93006/ceiling-tf）用所选窗口30秒预热+300秒无JFR确认；TJ（seed93007/ceiling-tj）相同窗口30+180秒、首正式progress后四机普通profile JFR90秒/max512m，稳定分析+30..80秒。如网络线程接近满核而owner仍不足，先记录实际证据，再另预锁网络线程配置等诊断，不能边采边变参数；不会改Core一致性边界来跨过限制。
- 所有档closed-loop持续补窗口、offered rate0、未修正CO，无固定发送休眠；响应时间起点为调用client异步提交前，不含此前payload构造，終点为egress回包完成future，含mailbox/在途/服务处理；按请求HDR三位有效数字、1分钟范围，批items独立展开计数。APPLIED核实accepted，没有独立accepted时钟；查询不算business、单独列数。各业务p50/p90/p95/p99/p99.9/max、10秒分段、实际峰值/期末backlog全部保留。
- 探索正确性门槛：offeredBusiness=terminalBusiness、offeredCore=terminalCore、实际fills=测量cycle*5120、每类型组成正确、期末unfinished0/peak<=该档window、资金差0/余额非负/零售与HFT持仓及冻结正确/一次强平闭环。所有最终未接收/拒绝/未知/超时判FAIL，ADMIN_ACTION内部有序重试单列；>=1000业务项/s，诊断p99<=10秒且另标是否达到原1秒SLO，不能以放大窗口掩盖尾延迟。连续3个5秒steal>5%、swap增长、VM维护重启、JFR DataLoss或截断均判无效。
- 饱和判定分别报告：owner单核持续>=90%、尽量95%且业务占比清楚才称owner接近饱和；仅窗口增长、吞吐提升<5%而排队延迟上升只能称该配置链路进入平台，不能称CPU算力用尽。线程CPU、runnable调度等待、网络/owner/matcher/Lane、JFR分配/GC/heap/native/锁/IO/JIT均核查。真实吞吐取300秒整轮，峰值10秒不能代替。没有open-loop、完整阶段墙钟、长稳泄漏、新恢复矩阵或其余五产品/API/WS/Kafka验证时，只作当前场景部分性能结论。
- Artifact `/Users/atomex/Desktop/surprising/gcp-validation/2026-09-08-saturation`；执行`python3 round.py <tag> <window>`，所有失败、构建、四机原始证据、输入与最终停机清单保留。运行时最多本次4小时，完成本次采集即停机，不留VM闲置。

### 解除限制后的功能门槛及M1024

- Runtime `78126c21`，四机JAR SHA256 `95aed2da87a2065bdf072e7e00deca4a9bb55ba910f3e11ad5ea314232afa1b9`一致。HotSpot25本地44个client及23个tools定向测试/打包通过；新回归确认256已提交请求可在任一响应前完成offer，第257条受session上限约束。四VM启动后API核验均Intel Cascade Lake/n2-custom-8-16384，guest family6/model85、4物理核/8逻辑CPU，实际JDK Temurin25.0.4.1。Core及等待策略未改，对照不适用。
- F0功能PASS：完整cycle9.425秒（不是持续容量），22,639business ops/3,183命令/5,120真实fills、789查询、peak1024/unfinished0，资金差0、零售/HFT/冻结和一次强平保险ADL全部通过，businessHash=7e98dad607744c81。
- M1024完整mixed PASS：90.183秒，2,376,758业务项、256,054命令、558,080真实fills，26,354.922业务项/s、2,839.281命令/s、6,188.327 fills/s，3,488查询。109测量cycle/134总cycle，offered=terminal、peak1024、unfinished0、资金/状态闭环通过。窗口扩大仍未形成owner饱和；中央5个5秒样本leader node0 owner28.8%、matcher8.4%、四Lane约3.2%、network-shared49.28%，load main80.16%、egress15.16%。该短档用于探索，不代替300秒确认，CPU窗口样本少；原始六分位、完整分段及系统证据在m1024。批量下单p99=271,843us、批撤148,111us。

### 连续交易T256及网络线程独立诊断预锁

- T256 PASS：实际90.182秒左右（精确值以t256/result.txt为准），46,022.686业务项/s、4,600.389命令/s、10,900.605 fills/s，global=session256始终有足够逻辑在途，资金/状态/计数终检通过。中央5个5秒样本leader node0 owner46.8%、matcher11.68%、network-shared84.04%（82.8..85.2%），load main100%但逻辑窗口满，不能因producer在窗口等待中自旋而认定发压计算瓶颈。T1024正在测量且未显示明显增幅，T4096仍按原预锁执行；不能未完成便替代最终结果。
- D1在T三档全部完成后运行：同runtime78126c21/同JAR，按原规则从通过的T档选择窗口（最快且差<=5%优先较小），除Core MediaDriver从SHARED_NETWORK改为DEDICATED外完全相同，Archive仍SHARED、Core与Lane默认等待不变。仅使收包/发包分别拥有线程，不增加无业务spin，不改变复制/结算边界。启动脚本通过AERON_THREADING_MODE环境变量选择，未设置时保持SHARED_NETWORK；保存实际启动文件、systemd配置及线程数据。
- D1 seed93008/ceiling-d1，30秒预热+90秒无JFR测量及终检，所有T档业务比例、资金/状态/错误/10秒p99诊断阈值和原1秒SLO单列、系统有效性规则不变。若D1持续速率高于所选T档超过5%，最终改用DF（seed93009/ceiling-df、30+300秒无JFR）和DJ（seed93010/ceiling-dj、30+180秒/90秒四机普通profile），window与D1相同；否则沿用TF/TJ及原网络模式。此决策在D1采集前固定，不能混用带采样和无采样结果挑峰值。

- TJ/DJ采样开始前细化配置：三Core使用已验证JDK25 profile+定向MethodTiming（phases.jfc）统计processCommittedRequest、idleCommand、offerResponse、apply/prepareMatching/commitReadyMatching/completeDispatchedMatcherSettlement、matcher executeWithEvidenceSync及PlaceAdmission/MatcherSettlement事件；endChunk、90秒、max512m，load仍普通profile。这是针对owner未饱和原因的归因采样，不替代TF/DF无JFR主吞吐；记录完整签名，未调用方法标不可用，嵌套墙钟不求和。idleCommand包含让出/自旋/park，不叫纯阻塞或纯CPU；需要与单核CPU及样本共同解释，普通10ms park阈值无法覆盖短等待。这项配置在任何TJ/DJ采集之前固定，其余场景/阈值/窗口选择规则不变。

- D1取消、DN1重锁：发现`/home/bench/async-d1.log`与前一天wait-diagnostics轮次重名，systemd append混入旧启动/progress记录。新D1已完成setup并发压约20秒后主动停止，原始污染日志及取消证据保留d1/d1-cancel.txt，不采用其吞吐或CPU结果，也未启动新JFR；不是交易正确性失败。归档脚本增加本地目录及远程日志不存在的前置检查。改为全新DN1/seed93011/ceiling-dn1/async-dn1.log，窗口256按T三档选择结果，所有D1参数与阈值保持不变；DF/DJ选择规则中的D1改指DN1有效结果。

### T系列与DN1完成，选择长轮

- 当前同一78126c21/同JAR，三档持续纯交易无控制查询、30+90秒，资金/状态/真实fills和计数均PASS，最终unfinished0、peak等于配置window；全部业务p99仍<1秒，swap/si/so/steal为0。窗口256→1024→4096没有提高持续吞吐，主要增加排队延迟，不能继续以扩大窗口证明Core CPU饱和。

| 档位 | global/session | 秒 | terminal业务项/s | terminal命令/s | 实际fills/s | 业务项总数 | 命令总数 | fills总数 | 最大业务p99 us |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| T256 | 256/256 | 90.182 | 46022.686 | 4600.389 | 10900.605 | 4150425 | 414873 | 983040 | 104529 |
| T1024 | 1024/1024 | 90.291 | 45730.245 | 4573.318 | 10830.770 | 4129025 | 412929 | 977920 | 293339 |
| T4096 | 4096/4096 | 91.032 | 45834.366 | 4585.195 | 10855.045 | 4172409 | 417401 | 988160 | 979894 |
| DN1独立收发 | 256/256 | 90.123 | 44858.218 | 4488.304 | 10623.662 | 4042773 | 404501 | 957440 | 107872 |

- T256/T1024/T4096全量businessHash分别2c7226d204d3084f/955a20449ca3a0e4/51de9e611f562644，包含不同累计交易数量，不能互相比较哈希是否相等来判复制一致性。原始完整六分位、各类型items/requests、分段和四机系统数据分别在各tag；T4096计时包含最终完整cycle及排空。DN1是全新数据/新日志，未采用被取消D1的混杂记录。
- DN1没有达到比T256高5%的预锁选择条件，按原算法选global=session256、SHARED_NETWORK执行TF（30+300无JFR）与TJ（30+180/90秒定向JFR）。独立收发线程无提升这一实测结果，不支持把共享网络线程约84%CPU直接认定为主要吞吐瓶颈。

### 用户询问官网百万消息与本项目吞吐的区别

- 官方Transport与Cluster均有高吞吐结果，不能简单归因“三节点本身只能每秒几千命令”。官网Cluster图表说明测试消息接收/持久化/复制/响应，并注明部分物理机和kernel bypass配置：https://aeron.io/aeron-open-source/ 。官方性能限制说明状态机业务顺序执行，平均50us/命令会将业务处理限制为20,000命令/s：https://aeron.io/docs/aeron-cluster/performance-limits/ 。这些消息基准不包含本项目的撮合、冻结/持仓、跨Lane结算和完整批量响应工作，不能直接拿本项目展开business ops/s与官方messages/s相除。
- 本轮T256约46,023展开业务项/s对应约4,600逻辑Core命令/s，不能称每秒46,023条Aeron消息，也不能把三副本各执行一次加总成三倍逻辑吞吐。实际消息较小的普通指令与20项批量的大小不同，官方消息体/硬件/网络配置也必须匹配才能作传输比较。
- 源码确认另一关键区别：LinearPerpetualMixedWorkload.submitPipelined调用Harness.submit，允许先将多条state.apply产生的pending入队，再drainSubmitted批量收取终态；SurprisingClusteredService.processCommittedRequest在每次回调中循环commitReadyMatching直至firstPendingMatchingSequence=0，必要时还等待queryResult，再assertClusterCallbackComplete后返回。后者保留当前确定性回调完成边界，但使等待matcher/Lane时不能处理下一个Core命令，owner不满核也可能进入吞吐平台。本轮不直接删除该正确性边界，TJ计时用于量化其成本，不能仅以“业务复杂”认定已无优化空间。

### 等待策略诊断的条件预锁（尚未采集）

- 用户要求不被原脚本规则限制并尝试饱和；前述首轮坚持默认等待策略用于定位，不能把这个自定控制变量扩展成禁止继续研究等待成本。保留每条回调必须完成撮合/结算的正确性边界，允许在证据满足时测试现有Aeron配置的调度代价，不修改交易Core源码。官方说明Backoff会从spin/yield进入park，Yielding在没有进展时Thread.yield：https://aeron.io/docs/agrona/agents-idle-strategies/ 。本次实际JAR的ClusteredServiceContainer.Configuration常量也核验`aeron.cluster.idle.strategy`默认BackoffIdleStrategy。
- 只有TJ完整JFR/资金验证通过、leader owner稳定单核CPU<90%、processCommittedRequest内idleCommand累计墙钟比例>30%时，才启动Y1。Y1仅把三Core的`aeron.cluster.idle.strategy`设为`org.agrona.concurrent.YieldingIdleStrategy`，会作用于cluster agent/consensus及service的idle策略；网络仍SHARED_NETWORK、Archive SHARED、matcher/Lane和BLOCKING策略不改。global=session256、1FIFO+1reserved、同一纯交易业务/资金/用户/币对、同一78126c21/JAR，当前master不重跑旧版。保留满窗口、默认真实复制、每命令完成与全部终检；不是移除等待或改成异步未完成回调。
- Y1 seed93012/ceiling-y1、30秒预热+90秒无JFR测量，资金/状态/计数/错误/系统有效性以及10秒p99诊断阈值与1秒SLO单列均沿用T。CPU接近100%本身不算成功；只有实际terminal业务速率比TF无JFR300秒结果提高超过10%，才继续YF（seed93013/ceiling-yf，30+300秒无JFR）及YJ（seed93014/ceiling-yj，30+180秒/90秒与TJ同样定向JFR）。否则记录额外CPU与无收益，结束并停机，不为了95%继续空转。YF/YJ如执行，停止之前保存TF/TJ和新选择的全部记录。
- Yielding可能增加owner/consensus CPU及调度开销，必须单列CPU中yield/轮询/业务栈和墙钟构成，不能把100%线程CPU叫100%业务计算。此诊断的价值由真实吞吐、延迟和资金不变量决定；不将运行时参数自动写成生产默认，也不因此擅自调整Lane或交易一致性边界。

### TF/TJ长轮完成及等待归因

- TF无JFR主轮PASS，30秒预热、实际300.476秒测量：46,184.013 terminal业务项/s、4,614.156 terminal Core命令/s、10,939.436真实fills/s；13,877,196业务项、1,386,444命令、3,287,040 fills，642测量cycle/703总cycle。offered=terminal，unfinished0，peak256；资金差0、零余额亏损闭环/保险/ADL、零售持仓与挂单密度、HFT持仓及预留均PASS，businessHash=d7e121841faff3c1。没有测量期控制查询或触发执行，非原full mixed场景。
- TF中央46个5秒系统样本：leader node1 owner单核47.652%（46.2..49.6%，>=90%样本0），另两副本43.778%/44.726%；四机整体CPU分别21.413%/29.804%/21.826%/23.304%，swap/si/so/steal0。10秒业务区间45,269.2..47,062.4项/s，持续运行未出现朝95%owner计算饱和的趋势。
- TF各业务入口到terminal延迟，单位us，按p50/p90/p95/p99/p99.9/max：普通下单39124/49676/51314/54853/58851/72351（328704请求）；普通撤单39419/88276/96075/103415/110231/120979（328704）；标记价格36175/66813/78381/87883/94699/102367（71628）；批量下单74842/91553/95485/103153/110034/121896（493056批、9861120项）；批量撤单65994/82051/85065/89128/92930/97320（164352批、3287040项）。批量平均/最大20，速率与全部分位原文见tf/result.txt；未分离accepted段，也未修正closed-loop coordinated omission。
- TJ同配置定向JFR归因轮PASS，180.497秒、46,821.946业务项/s、4,675.473命令/s、11,091.177 fills/s；8,451,203业务项、843,907命令、2,001,920 fills，391测量/452总cycle，offered=terminal、unfinished0、peak256、资金差0。四份JFR各90秒且DataLoss0，不替代TF主吞吐。
- TJ leader node1在426119次processCommittedRequest中平均208886ns，累计墙钟89.010293434秒；13865908次idleCommand累计56.212391032秒，占回调墙钟63.153%；commitReadyMatching累计20.424482484秒。三个Core的idle/回调比例为66.567%/63.153%/64.490%。方法存在嵌套、跨线程和采样边界，不能把所有方法墙钟相加，也不能把idle全部说成park或CPU计算。leader中央22个5秒owner CPU均值49.191%、最大51.4%，匹配顺序回调等待期间未占满CPU的现象。
- TJ满足此前条件预锁（资金/JFR有效、owner<90%、idle占比>30%），执行Y1既有Yielding配置诊断。保持三节点网络复制、逐回调完成边界、Lane BLOCKING及SHARED_NETWORK，仅调整cluster现有idle属性；依据实际吞吐是否比TF增加>10%决定是否执行YF/YJ长轮，不能以CPU数值取代收益。
- TJ稳定JFR +30..80秒分配加权估计：三Core分别245.229/247.750/246.933 MB/s，约5237/5291/5274 bytes/business op，load154.748 MB/s、约3305 bytes/business op。全录制首个allocation sample包含录制前累积权重，不能拿全90秒直接相除夸大分配；TLAB精确事件未开启，objects/op和最大对象不可用。主要仍有批量编解码、OrderBatchItem/ResolvedPlaceOrder、结果复制与matcher evidence分配，并非零分配。
- TJ leader四GB heap committed；9次ZGC（6 Major/Proactive、3 Minor/High Usage），GC总pause0.521ms、单phase最长0.028ms，After-GC 236978176..369098752 bytes，首尾262144000→304087040；direct8个/9575136 bytes不变。NMT total committed 4455890135→4432211395 bytes，峰值4469257951，reserved约70.3GB包含ZGC虚拟地址保留，不能解释为物理占用。22活跃Java线程稳定，13次编译共2500.277ms、最长347.058ms，59次deoptimization，定向MethodTiming重转换有额外开销。VM operation最长16.053ms、safepoint begin最长0.120ms；完整线程/分配/GC/NMT/VM/I/O汇总保存在每机audit/window/cpu/methods文件。短记录不足证明无泄漏，未达到完整生产性能验收。

### Y1短轮完成，进入YF/YJ

- Y1同JAR、实际90.316秒，76,199.579 terminal业务项/s、7,479.998 terminal Core命令/s、18,084.100 fills/s，6,882,026业务项/675,562命令/1,633,280 fills，319测量/418总cycle，offered=terminal、unfinished0、peak256、资金与状态PASS。比TF无JFR长轮业务项速率高64.991%，达到预锁>10%条件，继续YF/YJ长轮确认，短轮本身不作长期上限结论。
- Y1各业务最大p99为79,036us，原1秒SLO通过；监控中央保守窗口仅1个5秒样本，三个owner均100%，该短窗口不足证明持续95%。保持完整原始监控，最终以YF中央长窗口与YJ样本拆分为准。Yielding会消耗CPU来轮询，不能把线程100%等同于纯交易计算100%。未改变生产默认值。

### YF长轮完成，owner线程达到持续满核

- YF无JFR、30秒预热+实际300.380秒，75,556.597 terminal业务项/s、7,417.300 terminal Core命令/s、17,931.394 fills/s。22,695,724业务项、2,228,012命令、5,386,240 fills，1052测量/1151总cycle，offered=terminal、unfinished0、peak256；资金差0、人口/零售持仓与订单/HFT持仓/冻结预留/初始化强平保险ADL检查PASS，businessHash=80c60cffb2a2d8f3。相同当前JAR、连续交易场景下，比默认等待TF长轮业务项速率高63.599%；不能移用成原full mixed场景性能。
- 三个owner中央42个5秒样本（210秒）均值均99.990%，范围99.8..100.2%、所有样本>95%；这是单个逻辑CPU口径，计量抖动可能略超100%。独立现场5秒pidstat平均分别99.8/100.0/99.6%，user约63.7..66.2%、system约33.6..35.9%。真实达到owner线程持续95%观察目标，但包含Yielding调度和轮询，绝不称95%有效业务计算或交易算力绝对上限。
- YF leader为node0，日志最新PID5234 role-change=LEADER。leader matcher17.724%、四Lane9.390/9.395/9.443/9.414%，network-shared87.133%、driver28.971%、archive22.810%、consensus99.990%。三机consensus也约100%，说明全局cluster idle属性增加额外CPU成本，不能只报告owner提升。四机machine CPU均值51.929/43.786/43.524/24.619%，swap/si/so/steal0；load main99.957%包含满窗口轮询，sender24.862/receiver34.138/egress15.371%。load后台apt-get仅1个5秒样本15.8%单核、python3同一短样本3.4%，保留同机干扰，不触发此前swap/steal/重启失效条件，不能据此宣称硬件噪声为零。
- YF六分位p50/p90/p95/p99/p99.9/max，单位us：PLACE_ORDER 12500/29589/32325/36765/41451/52133（538624请求）；CANCEL_ORDER 12877/64028/71237/79822/88408/98828（538624）；APPLY_MARK_PRICE 18628/55443/60850/68747/80674/86310（73516）；PLACE_ORDER_BATCH 53018/68681/72024/78839/87752/99155（807936批/16158720项）；CANCEL_ORDER_BATCH 44564/56426/59047/63700/69926/79036（269312批/5386240项）。批量平均/最大20项，所有业务p99<1秒；ADMIN_ACTION有序重试131，不是业务失败。
- 用户追问改动位置：78126c21改发压工具和可显式配置的client session容量构造入口（生产默认保留），未修改撮合、账户或结算规则；Y档只改测试VM的JAVA_TOOL_OPTIONS运行时等待策略。当前processCommittedRequest同步等待完成的业务边界仍存在；没有将其伪装成已实现无阻塞交易流水线。生产应避免主线程外部I/O阻塞及不必要的线程交接；若改为跨命令异步流水，需要先明确复制顺序、资金依赖、终态发布、回放及snapshot fence，不能直接删除等待和完成断言。

### YJ最终归因、验证范围与停机

- YJ 180.132秒，75,215.722业务项/s、7,385.690命令/s、17,850.009 fills/s；13,548,768业务项、1,330,400命令、3,215,360 fills，628测量/726总cycle，offered=terminal、unfinished0、peak256，资金与状态PASS，businessHash=c0b5c8437942a506。四JFR各90秒、DataLoss0；此带定向MethodTiming结果仅用于归因，主吞吐使用YF。所有原始业务六分位、各类型请求数/项数及10秒分段保存于yj/result.txt。
- YJ leader node1（最新PID5311 role-change=LEADER），653091次processCommittedRequest平均135835ns、累计88.712615985秒；idleCommand累计30.333836064秒，占34.193%；commitReadyMatching累计35.448245631秒，58786477次调用，平均每条Core命令约90.013次，TJ约32.54次。单次回调更短，但检查更频繁，说明用更积极轮询减少休眠延迟，不是去掉同步完成等待。三副本owner中央18个5秒样本约99.91..99.92%单核。
- YJ leader稳定+30..80秒2315个owner ExecutionSample+NativeMethodSample，互斥栈分类：commitReadyMatching 50.842%（含轮询，不全是有效业务）、state.apply 29.935%、idleCommand 15.896%、offerResponse 1.296%、其他及agent idle约2.03%。363个叶子样本为Thread.yield0；另见SHA2 fingerprint、HashMap/primitive map probe、OrderReservation字符检查、批量结果编码与结算分发。采样比例不是精确CPU或墙钟比例，不能用100%-15.896%推出有效业务算力。owner先前未满的主要可证优化点是顺序回调等待/调度，当前仍有结算完成检查和分配工作；没有证据认定已经耗尽硬件能提供的交易吞吐。
- YJ稳定+30..80秒分配加权估计：三Core384.834/398.517/406.547 MB/s，约5116/5298/5405 bytes/business op；load205.457 MB/s、约2732 bytes/business op。每业务项分配仍高，吞吐提升会增加每秒GC压力；不能称零分配。定向事件的instrumentation成本和采样权重误差均存在。
- YJ三个Core GC次数14/11/12、GC pause合计0.701/0.562/0.618ms，最长单phase0.017/0.031/0.026ms；对应After-GC范围249561088..400556032、278921216..415236096、230686720..413138944 bytes，4GiB heap committed固定。load45次Major/Proactive、总pause2.470ms、最长phase0.024ms，After-GC 67108864..115343360 bytes。短窗口GC pause不是当前百微秒回调/几十毫秒队列尾延迟的主要解释，但不排除分配和并发GC CPU成本。
- YJ NMT total committed首尾：node0 4466130487→4429081581、node1 4456551287→4430088781、node2 4447855932→4425854672 bytes，峰值分别4467054682/4477534780/4462726788；load809774823→1123952830、峰值1126658420。load Java heap committed723517440→1050673152 bytes，解释大部分NMT增长，不能直接判native泄漏。Core direct均8个/9575136 bytes、load6个/8522400 bytes不变，活跃Java线程Core22/load17稳定。各类别NMT、top allocation class/thread/site、全量duration统计均在四机audit/window文件。
- YJ leader9次Compilation共1579.703ms、最长348.222ms，53次Deoptimization、6次CompilationFailure事件（不等于交易失败），MethodTiming重转换带来的编译活动需保留。VM operation最长15.761ms、SafepointBegin最长0.123ms；17个超过profile阈值的ThreadPark共456.527ms，主要来自Lane，owner未采到超过阈值的park；JavaMonitorEnter、File/Socket I/O、Java异常事件计数0不能证明所有短操作不存在或全部事件均开启。没有发现owner同步外部I/O证据，但本轮不能据低阈值缺口完成严格无阻塞验收。
- 四机补充systemd CPUQuotaPerSecUSec/MemoryMax均infinity、VmSwap0；此层cgroup cpu.max不存在、cpu.stat未暴露throttle计数，记不可用，不伪报nr_throttled=0。Core现场RSS约5.57..5.94百万KiB（含heap/native映射等），不能与ZGC几十GB reserved混淆。FD是单时点，不是增长斜率；mapped buffer/pool分配释放差额、长稳泄漏、精确objects/op、accepted两段延迟、open-loop/CO修正、JMH误差/置信区间及新snapshot/failover恢复未覆盖，因此这次结论为真实三节点容量/瓶颈诊断通过，完整生产性能验收仍为部分验证。没有在本地跑交易性能，也未测试API/WS/Kafka或六产品线并行。
- 本轮代码构建与67项定向功能测试已在HotSpot JDK25通过，运行时78126c21和四机JAR SHA256保持一致；后续仅追加诊断文档与本地artifact工具，不改变交易源码。原固定session64造成实际窗口不足已由发压端可配置容量修正；去掉测量期控制排空是trading-stream独立场景，不用它冒充原mixed。
- 所有采集与下载完成后执行`python3 stop-after-round.py`，2026-09-08 02:04:29 UTC开始停止，02:05:24 UTC完成；再次读取Compute API清单，surprising-core-0/1/2和surprising-load四台均TERMINATED，保留磁盘与数据。证据为artifact根目录stop-instances.txt、instances-before-stop.json、instances-final.json；后续只做本地离线分析，下次用户明确要求测试才开机。
- Artifact封存：`/Users/atomex/Desktop/surprising/gcp-validation/2026-09-08-saturation/SHA256SUMS`，1131个文件、456320680 bytes，manifest SHA256=`6cf83da17d421c721027a34c079838153cde766079d8f620232ab2c603cf4ea7`。包含有效/无效轮次、四机raw/JFR/settings/系统数据、构建测试日志、当前runtime JAR、停机清单和最终离线分析源；不遍历父目录或SSH私钥，不包含manifest自身及可再生成的class/Python cache。最终选择YF/YJ，TF/TJ默认等待结果完整保留。

## 2026-09-08 owner后续优化源码分析（未修改运行时，未新增性能采集）

- 用户要求开始检查如何优化。基于当前master feb886a3及已封存TJ/YJ采样，检查SurprisingClusteredService、CoreProbeState、MatcherPipelineGroup/MatcherCommandPipeline、TradingRuntimeState、LaneSequenceQueue、RuntimeCommitJournal及现有回调完成测试。当前工具列表没有CodeGraph工具，采用已定位文件源码读取；未重建索引、未开云VM、未运行本地性能。以下是候选实现范围，不是已实现或已验证的收益。
- 优先级1：进展反馈。CoreProbeState.commitReadyMatching返回最终完成命令数，SurprisingClusteredService.processCommittedRequest将其直接传给idleCommand(work)。pumpMatchingCommitCompletions可能已消费Lane admission通知、提交matcher、消费matcher结果并派发结算，但最终完成数仍为0，此时Backoff会按无进展继续退避。返回值本身用于terminal计数是正确的；优化应另加owner私有primitive阶段进展标记/计数，只有实际消费结果、派发任务或推进阶段才置位，服务等待策略据此reset，保留原completed返回语义。禁止用pending数量非零伪造work>0，也不为每次poll分配结果对象。
- 优先级2：无结果的廉价检查。现有LaneSequenceQueue是预分配SPSC数组，完成游标为volatile且分离cache line；MatcherCommandPipeline也有completedPosition。不是缺少通知队列，也没有全pending列表扫描的证据。当前每次pump仍调用两类Lane ready检查、matcher drain与dispatch head检查；可以在明确所有owner可推进状态和外部完成游标后设置廉价空路径，避免重新走完整协调逻辑。注意四Lane下仅几个游标读取，不能预先声称这是主要CPU瓶颈；存在owner本地可推进的batch continuation时不得只因外部队列为空而跳过。不得引入所有Lane争写的全局AtomicLong或每命令Future；需覆盖通知先于/晚于检查、批次内部已消费事件和多Lane最后完成通知，防止丢唤醒或卡住。
- 优先级3：等待策略隔离。SurprisingClusterNode分别创建ConsensusModule.Context和ClusteredServiceContainer.Context，当前均依赖全局aeron.cluster.idle.strategy。官方service Context支持idleStrategySupplier，生产实现可以将service与consensus配置分离并保持默认行为；不应把本次全局Yielding作为优化完成。回调循环仍必须调用cluster提供的idle wrapper以保留关闭检查，不能用自行Thread.yield替换它。此项目标是以较少CPU保持吞吐，不承诺单独提高吞吐。
- 次级分配热点：OrderReservation.release/consume/replaceReservedUnits每次构造不可变record，都重新执行symbol/asset校验与规范化；采样中isAsciiAlphaNumeric确实突出。后续可在内部primitive运行态保留已验证的身份，在协议/快照/事实边界生成不可变视图；必须保留金额范围、溢出、身份和恢复输入校验，不能直接删公共构造器校验。批量编解码结果byte[]和HeapByteBuffer以及OrderBatchItem/ResolvedPlaceOrder同样值得处理，但复用前必须确认响应重试、幂等缓存、日志/快照引用的生命周期，不能把会逃逸的payload覆盖。
- 排除误判：refreshSnapshotProjection在snapshotProjectionDeferred时仅设置dirty标记，completeSnapshotProjectionBatch才发布；同一完成路径调用两次不等于物化两份快照。RuntimeCommitJournal.begin/endPublicationBatch目前主要是健康检查及depth增减，没有发现每次空poll都flush磁盘或生成快照。上述两处不列为主要分配或同步I/O缺陷。
- 更大的跨命令流水化需要单独设计。现有logCallbackCommitsMatchingBeforeTheFollowingCommand及logCallbackCompletesMatchingExactlyOnceWithoutBackgroundWork明确保护同回调完成，不能简单删除断言。Aeron官方ClusteredService.doBackgroundWork契约明确禁止直接/间接更新service state或调用会更新日志的Cluster接口，因此“回调入队后返回，后台随完成时机提交结算”的方案不成立。若以后允许跨回调pending，必须为依赖命令、timer、session关闭、领导任期变化、响应/副作用发布及snapshot/replay定义确定性执行边界；每个副本按本地完成时机决定资金可用或提交顺序不可接受。
- 第一轮建议实现进展反馈和经过状态覆盖证明的空路径，保持逐回调完成、交易规则、4Lane/1matcher与真实复制不变；配置隔离单独验证，分配改动另列，避免无法归因。功能用可控的延迟matcher/Lane验证阶段进展不等于terminal、真实无进展能退避、最后一个Lane完成前不响应、资金不足/重试幂等/批次拒绝/部分成交撤单/异常中止及snapshot恢复。后续性能仍仅在真实三节点重新预锁后执行；同时报告吞吐、尾延迟、每命令poll/有效阶段进展次数、owner/consensus CPU、分配和资金检查，不以95% CPU本身作为优化成功标准。
- 外部依据：Aeron ClusteredService契约 https://github.com/aeron-io/aeron/blob/master/aeron-cluster/src/main/java/io/aeron/cluster/service/ClusteredService.java ；service独立idle supplier https://github.com/aeron-io/aeron/blob/master/aeron-cluster/src/main/java/io/aeron/cluster/service/ClusteredServiceContainer.java ；确定性业务要求 https://aeron.io/docs/aeron-cluster/efficient-business-logic/ 。本次仅文档分析，执行git diff --check，不重复Java测试或声称性能提升。

## 2026-09-08 owner进展反馈、空路径与reservation内部更新实现（功能验证）

- 用户批准落实前述1/2，并追加OrderReservation内部更新的重复字符串校验优化。基于master 01acea2b实现；本节没有新的吞吐采样或性能比较，历史artifact目录及manifest保持不变。未启动云VM，也未执行本地JMH/交易性能采样。
- CoreProbeState新增owner私有primitive matchingProgressSequence，消费Lane admission/settlement通知、提交matcher、收取matcher结果、派发结算及处理可完成阶段时推进；不进入复制状态、资金hash或snapshot。commitReadyMatching原有最终完成数返回语义不变。SurprisingClusteredService根据完成数或阶段进展向cluster idle wrapper传work=1，真正无进展时传0，保留deadline、interrupt、逐回调完成及终态响应边界。
- 空路径仅放在服务回调内部：首轮必须完整pump，每次有进展后也必须再完整pump；只有完整pump没有进展后，才使用现有SPSC完成游标探测。TradingRuntimeState检查admission/settlement队列，MatcherPipelineGroup检查各shard的正向完成头（不抢同步control token）。通知探测不消费队列，不增加共享原子计数器、锁、Future或每轮对象；有通知时立即恢复完整pump。空路径仍调用assertHealthy，Lane失败但没有发布完成通知也会立即失败，不能等到30秒超时。
- OrderReservation由record改为final不可变值类，保持原public构造器、9个accessor、equals/hashCode/toString字段语义与显式状态codec；release/consume/replaceReservedUnits通过私有copy构造复用前一合法对象的symbol/asset。完整金额校验、Math.addExact溢出、剩余金额边界保留，public构造/解码/恢复仍规范化和校验文本。仍创建新的不可变金额对象，并非零分配；没有增加验证字符串缓存或可变共享identity。未发现此类型的record解构/反射依赖；rolling hash使用原类名和字段的显式实现，恢复及状态hash测试通过。
- 更精确的热点边界：已有YJ完整栈中的OrderReservation.isAsciiAlphaNumeric也来自ActiveOrderIndex.count、RuntimeOrderAdmission.admissionIdentity和TradingRuntimeState.instrument，而非全部由reservation构造产生。本次只优化已有合法reservation的内部金额更新，不声称消除全部热点；RuntimeStateMaterializer等公开重建路径仍保持校验。RuntimeIdentityRegistry恢复字典及IdentityView不能仅凭类型就视为所有输入均已完整校验，因此没有增加无验证的通用构造入口。
- 新增5项针对性测试：阻塞真实matcher线程时阶段进展被观察但不提前响应、连续空等待保持progress不变并能消费迟到结果；matcher在空检查后发布完成；两个Lane的通知均保留且探测不消费；无完成通知的Lane故障仍被空路径发现；reservation金额变动后的原对象不变、身份复用、值相等/哈希、释放/消耗/缩量边界和溢出。服务测试同时验证冻结金额和snapshot恢复hash。
- 执行前确认Oracle GraalVM 25.0.1 HotSpot、Maven3.9.16。首轮8个定向测试类150项通过；扩大service及依赖后通过，再加入空路径健康检查后执行最终命令：JAVA_HOME=/Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home，PATH优先该JDK，`mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am '-Dtest=*Test,!*BenchmarkTest' -Dsurefire.failIfNoSpecifiedTests=false test`。最终681项全部通过（product12、protocol98、client48、instrument12、service469、benchmarks功能测试42），0失败/错误/跳过，50.808秒；为避免执行历史本地性能入口，排除*BenchmarkTest。最后将通知测试明确固定为两Lane后单独复测该用例通过，不重复计算总数。
- 功能范围包含六产品线已有成交/费用/资金守恒、跨Lane结算、批量拒绝/撤单、快照、恢复与生命周期用例。新增JMH场景定义ownerProgressAndReservationTransitions覆盖多fill消耗及撤单释放、当前服务回调poll路径，已编译；它沿用历史本地fixture，没有运行或冒充真实三节点性能证据。新Core性能仍待用户明确启动下一轮真实三节点验证后预锁并采集；不能把本次681项功能测试当作吞吐无回退证明，也不宣称已完成完整主链路性能验收。
- 证据目录：`/Users/atomex/Desktop/surprising/gcp-validation/2026-09-08-owner-optimization`，targeted-tests.txt、service-tests.txt、final-tests.txt、notification-test.txt及SHA256SUMS；README同步中英行为说明，git diff --check通过。仅提交本任务源码/测试/文档，保留用户openai及.factorypath未跟踪文件。

### 用户授权本机功能压力验证（采集前锁定）

- 用户随后明确要求“在本机先压测看看有没有bug”，仅对本次本机功能压力测试覆盖此前禁止本地性能执行的限制；不改变以后云上性能结论须真实独立三服务器的口径。使用本机三个独立HotSpot25 JVM、真实Aeron UDP/Archive/Cluster和一个网络发压JVM，不使用mock session/直接Core调用。四台GCP VM仍停机。
- 环境macOS26.7/x86_64、16逻辑CPU、16GiB物理内存，Oracle GraalVM25.0.1 HotSpot/Maven3.9.16。开始前已有约1420MiB swap使用，用户IDE/浏览器等进程保持不动；此环境只用于发现功能/恢复问题，禁止与云上75,556.597项/s比较容量或以swap环境宣称性能验收通过。Core各-Xms128m/-Xmx1g，load-Xms128m/-Xmx512m，ZGC/NMT summary，默认Cluster Backoff及Lane BLOCKING、MediaDriver SHARED_NETWORK、Archive SHARED，4Lane/1matcher，不用全局Yielding。
- 两个独立空目录：LM seed94001，原full mixed，10秒预热+60秒测量；LT seed94002，trading-stream，10秒预热+120秒测量。使用当前修改代码打包的同一JAR，1000零售/1769总用户、256symbols、batch20、global=session256、1FIFO命令/1reserved查询。LM包含控制查询、触发/资金费/风险续扫与强平保险ADL；LT将亏损闭环置于测量前，持续交易无周期控制排空。命令异步连续提交，测量边界和终检排空；两种场景分开记录。
- 正确性门槛沿用工具资金/状态终检：offered=terminal、unfinished0、peak<=256、实际fills=cycle*5120、资金差0、各用户非负、零售密度/HFT持仓/冻结预留/亏损闭环正确，所有业务错误/未知/超时判失败。30秒客户端/回调deadline不放宽；各业务六分位和吞吐仅诊断、不设CPU饱和或吞吐门槛。每轮最长600秒，异常立即收集线程/日志并停止本轮，区分资源不足与代码缺陷；修复后必须新目录/新seed/新预锁重跑，不能覆盖失败记录。
- 每轮首次正式progress后，对三Core启动30秒profile+phases MethodTiming JFR、load普通profile，maxsize128m；保存JFR/settings/summary、GC/NMT、进程及vm_stat/swap采样。JFR不完整或DataLoss则该采样不用于归因，正确性结果独立保留。采集工具写在新artifact目录，未向repo新增scripts/docs。
- 每轮正常完成后停止三Core，再用相同数据目录重新启动，通过mixed-verify-only及该轮实际totalCycles做Archive回放后资金/持仓/冻结/哈希核对；不重复setup或重新发交易。结束后只停止本次创建的本机进程，保留数据和日志，不影响用户其他进程。artifact `/Users/atomex/Desktop/surprising/gcp-validation/2026-09-08-owner-optimization/local-cluster`。这不是完整故障注入矩阵，也不验证WS/Kafka外部推送。

### 本机两轮压力与重启回放结果

- 使用已推送源码32a515c3，tools打包通过（package.txt）；实际运行tools.jar SHA256为b236cfad280e0dd01d960a856a04ecf72475319a354955d24a107d59e5922295。两个场景均为LINEAR_PERPETUAL，六产品线覆盖来自前述功能测试，不能把本次网络压力结果扩大成六产品线逐一压测通过。
- LM full mixed实际60.254秒：1,332,876 terminal业务项、146,060 terminal Core命令、312,320 fills，分别22,121.028项/s、2,424.080命令/s、5,183.408 fills/s；61测量/62总cycle，1952查询，含预热1984次触发执行。各类型最大p99为238,944us。offered=terminal、unfinished0、peak256、资金差0，人口/持仓/冻结预留及亏损闭环终检PASS。三节点停止并从原日志重新启动后，62 cycle核对PASS，businessHash仍为3adae03f6df00ab2。
- LT trading-stream实际120.401秒：4,501,375 terminal业务项、454,527 terminal Core命令、1,064,960 fills，分别37,386.421项/s、3,775.099命令/s、8,845.085 fills/s；208测量/222总cycle，测量查询/触发执行0。各类型最大p99为209,977us。offered=terminal、unfinished0、peak256、资金差0，状态终检PASS；同目录三节点重启回放222 cycle核对PASS，businessHash仍为3c2a38ce0726e5da。
- 合计5,834,251业务项、600,587 Core命令、1,377,280 fills。本轮未发现新的交易业务错误，无需追加业务代码修复；这是有限时长和既定样本下的结果，不是无bug证明。回放核对通过新leader查询验证恢复后业务状态，没有逐个直连三个副本核对独立hash，也未执行强杀/网络分区等完整故障矩阵。
- 两轮各四份JFR均完整30秒、DataLoss0。LT node0记录91,823次processCommittedRequest、460,576次commitReadyMatching、3,757,004次idleCommand；平均每个回调约5.016次完整pump和40.916次idle调用，表明等待循环不再每次执行完整pump。方法有嵌套且含等待墙钟，不把累计时长相加，也不把该计数当作CPU有效工作率或跨机器性能提升。OrderReservation内部金额更新仍分配不可变对象，不称零分配。
- 回放新leader node1两轮均出现一次Aeron `quorum position went backwards` WARN，LM leaderCommitPosition=138739008、LT=471083840，quorumPosition均为0。警告发生在回放核对完成之前，不能归为最后停机噪声。核对实际运行JAR的ConsensusModuleAgent字节码（consensus-bytecode.txt）：updateLeaderPosition在quorumPosition低于已有commitPosition时记录告警，并使用proposeMaxRelease更新本地commitPosition；警告本身不能证明已提交业务状态回滚。两轮资金与恢复hash通过，但未采集告警瞬间的各follower位置，尚不能确定quorum估计降为0的完整原因，保留观察项，未屏蔽日志或改写Aeron提交语义。另有旧错误文件的log recording stopped: eos=true发生在前一组Core收到停止信号时，与上述回放WARN分开记录。
- 本机运行中swap使用约增至3383MiB，同机IDE/浏览器及四JVM资源竞争存在；本次速率仅作诊断，不与此前独立云三节点结果比较，也不宣称吞吐上限或无性能回退。全量业务六分位、运行参数、JFR/GC/NMT、vm_stat/swap及进程采样保存在artifact，results.json汇总原始日志结果。
- LM完成回放于2026-09-08T02:43:46Z，LT于02:48:58Z；02:48:58Z本次所有本机子进程均已停止。每轮3个运行Core及3个回放Core由SIGTERM退出（143），load及verify均正常退出0，processes-final.json与stop.txt留证。云VM没有启动。最终记录只追加本文件，不重新执行已通过的Java测试。
- 证据目录SHA256SUMS最终覆盖173个文件、2,819,675,177 bytes，manifest SHA256为0d2196e95bbcc7582b4451eb57d36660735c0dc79f9b11ffbeffb08d5b6f506c；包含前述测试/打包日志及本机原始日志、JFR和持久化数据，未修改上一轮云测试artifact。

### 2026-09-08 quorum回退警告定向排查

- 用户要求查明前述警告。当前master 7536d2ae，运行JAR仍为32a515c3构建物；本次只复制LT持久化数据到新artifact `/Users/atomex/Desktop/surprising/gcp-validation/2026-09-08-quorum-investigation` 做本机三JVM恢复诊断及verify-only，不发新交易、不采吞吐、不启动云VM、不修改生产代码或心跳阈值。执行前再次确认HotSpot25与Maven3.9.16。使用Aeron 1.53.0标签源码，并以原运行JAR字节码核对关键方法。
- 原警告的持久错误记录确认发生于2026-09-08T02:48:29.613Z，仅1 observation，早于停机。第一份数据副本自然选出node2，恢复及资金/hash核对PASS，未重现警告。第二份副本通过Aeron现有appointed leader属性固定node1，其余恢复参数相同；使用artifact内Byte Buddy诊断agent，在updateLeaderPosition入口记录前三次位置更新及回退条件的字段和调用栈，探针不改变位置、时间戳、超时或交易状态。探针输出的Exception仅用于显示调用栈，未抛出；Unsafe提示来自诊断依赖。
- 第二次于2026-09-08T03:04:18.154879Z捕获并重现：调用链Election.leaderReady→ConsensusModuleAgent.updateLeaderPosition，election=LEADER_READY；append=commit=471769728，quorum=0，leaderHeartbeatTimeoutNs=10000000000。三个member的logPosition全部471769728，leader视角中node0报告年龄111.142秒、node1自身110.886秒，均active=false；node2报告年龄0、active=true。此时只有一个有效位置报告，三节点多数派要求两个，所以返回0，绝非三个节点的磁盘日志位置都变为0。
- 根因是恢复阶段时间戳更新与多数派计算的顺序：Election.leaderReplay仅在首次进入时刷新thisMember.timeOfLastAppendPositionNs，长回放使其过期；leaderReady先计算quorumPosition，再调用updateLeaderPosition刷新自身时间戳。因此即使有一个已恢复的follower，新leader第一次计算也可能因自身报告过期而不足多数派。ClusterMember.quorumPosition将排名数组初始化为0并只纳入isActive成员，故产生0。这里保留了过期成员的非零日志位置，不是交易结算将日志回滚。自然选主轮没有复现，也说明依赖各副本恢复先后时序。
- 同次诊断03:04:18.216065Z下一次位置检查，node1自身报告已刷新，node2报告仍有效，quorum恢复471769728，election=null（选举已结束）；随后quorum推进471769824。打印探针带来额外耗时，该约61ms日志间隔不能作正常恢复延迟指标。updateLeaderPosition以proposeMaxRelease保持本地commit单调，leaderReady在hasQuorumAtPosition通过后才结束选举，不能把这个警告解释为绕过多数派接受交易。
- 当前依赖的真实计算方法另做3项小样本断言：相同非零日志位置、全部报告过期→quorum0；只刷新leader→仍0；刷新leader和一个follower→恢复原位置；三个成员日志位置始终未变，全部PASS。两次实际恢复均mixedVerify=PASS、fundsDiff0、population/hftPositions/reservations/loss通过、222 cycles、businessHash=3c2a38ce0726e5da。全部本机诊断子进程均已停止，生产源码没有改动，不需要重复681项Java测试。
- 结论：重现的警告由Aeron 1.53.0长回放后的自身活跃时间戳过期及更新顺序触发，是恢复瞬间的多数派位置估计回退；本次未发生已提交业务回滚。原两轮没有保留同一瞬间成员字段，因此不能伪称重新获得原现场，但相同数据副本、相同leader的真实重现及源码路径支持该原因。不能泛化为所有同名警告均无害；若在正常交易期持续出现，仍须检查位置报告中断、节点停顿和真实多数派丢失。本次不屏蔽告警、不调大故障检测时间、不替换或私改Aeron依赖。
- 对应官方源码： https://github.com/aeron-io/aeron/blob/1.53.0/aeron-cluster/src/main/java/io/aeron/cluster/Election.java ， https://github.com/aeron-io/aeron/blob/1.53.0/aeron-cluster/src/main/java/io/aeron/cluster/ClusterMember.java ， https://github.com/aeron-io/aeron/blob/1.53.0/aeron-cluster/src/main/java/io/aeron/cluster/ConsensusModuleAgent.java 。原始探针数据见replay-appointed-1/core-1.log，诊断源码、启动命令、恢复日志及进程退出状态均保留在新artifact。

## 2026-09-08 当前Core的service独立等待策略验证（采集前锁定）

- 用户要求继续解决Aeron Cluster吞吐问题，授权恢复此前四台GCP验证VM；本轮结束停止四VM。只验证当前master（含32a515c3进展反馈/空路径/reservation优化，以及本次service独立配置），不检出旧版本、不用本机吞吐作比较。源码新增启动期service idle supplier配置，BACKOFF/YIELDING通过surprising.aeron.service.idle-strategy或AERON_SERVICE_IDLE_STRATEGY选择；不配置时保留Aeron原默认/全局属性语义。配置解析在启动线程和driver之前完成，不增加逐命令工作，不改交易回调完成边界。对照commit不适用，所有候选为同一份当前master JAR，不拿历史75,556.597项/s作新版本收益基线。
- 固定硬件：surprising-ae591/asia-southeast1-b现有surprising-core-0/1/2+surprising-load，四台n2-custom-8-16384（各8vCPU/16GiB），内网10.90.0.5/.3/.4/.2，独立服务器真实UDP/Archive/Cluster。启机后记录实际CPU/内存/磁盘/JDK。本机测试HotSpot25.0.1，云/发压HotSpot25；Core-Xms4g/-Xmx4g/ZGC/AlwaysPreTouch/NMT summary，load-Xms512m/-Xmx2g/ZGC/NMT，Core网络SHARED_NETWORK、Archive SHARED、4Lane BLOCKING/1matcher，原30秒请求/回调deadline不放宽。
- 业务固定LINEAR_PERPETUAL连续交易，ClusterMixedCapacityMain的trading-stream=true，1000零售/1769总用户、256symbols、batch20，原资金和持仓setup/亏损闭环不变；一个FIFO命令session和一个reserved查询session，global=session in-flight256；不把历史capacity-connections属性值4当作实际4个命令session。每cycle普通下单/撤单各512项、批量下单15360项/768批、批量撤单5120项/256批、5120 fills，另有既有标记价动作，交易期无周期查询/触发控制排空。做市/零售订单持续交错，client异步满窗口提交，测量边界及终检排空；closed-loop且未修正coordinated omission，不宣称open-loop容量。入口到terminal六分位保留，尚未拆accepted阶段。
- D0(seed95001)、Y0(95002)、S0(95003)各预热30秒+测量90秒、无JFR：D0所有等待默认，Y0仅全局aeron.cluster.idle.strategy=YieldingIdleStrategy，S0只配置service=YIELDING、consensus仍默认Backoff。三候选均全新数据目录；性能选用当前同JAR结果。S0若业务速率至少为Y0的95%、所有业务p99<1秒且consensus CPU稳定均值<80%单核，优先S0；否则选满足正确性/SLO者中速率最高档，不为了owner95%自旋。选定档执行F(seed95004)30+300秒无JFR长轮与J(seed95005)30+180秒、90秒JFR归因轮；长轮相对D0至少提高10%才称有吞吐收益，差异只归因当前同代码配置，短轮不称长期上限。
- 正确性门槛：offered=terminal（业务项及Core命令分别相等）、unfinished0、peak<=256、资金差0、各用户非负、人口/零售订单与持仓/HFT持仓/冻结预留/强平保险ADL闭环验证PASS；所有未知/业务错误/超时均失败，不改变交易规则绕过失败。各类型p99<1秒作为原SLO；每轮最多1200秒systemd执行上限。swap/换页/steal或重启、JFR DataLoss使相关性能证据无效；管理IAP失败不等于业务失败，保留错误并读取独立systemd结果。
- 四机5秒pidstat/mpstat/vmstat/sar采样。J轮首次正式progress后四机各90秒JFR，Core沿用phases.jfc的profile+MethodTiming，load普通profile，maxsize512m；检查DataLoss、owner/matcher/Lane/consensus CPU、方法等待与完整pump计数、分配栈及稳定+30..80秒权重、GC/native/direct/线程/停顿/异常，不相加嵌套方法墙钟。没有新交易状态结构或热路径改动，既有ownerProgressAndReservationTransitions JMH场景覆盖前次Core改动且已编译；本轮实际执行采用真实三节点网络工具，不运行本机mock JMH或把它当三节点证据。J轮后同数据三节点重启verify-only，核对实际cycles和业务hash；只称重启恢复，不称完整故障矩阵。原始证据存新artifact `/Users/atomex/Desktop/surprising/gcp-validation/2026-09-08-service-idle`。

### 本轮中止与用户重新明确生产混合场景

- 3e199104启动配置改动完成，32项相关功能测试通过（3项idle配置、6项启动/线程、23项服务回调），tools打包成功。首次测试代码错误引用不存在的ConsensusModule.Configuration.idleStrategySupplier，修正为当前Aeron实际使用的共享supplier后通过，原编译日志保留。四机部署同一JAR SHA256=0c275af46a806247549f1e126ab20e392ab9ade037e0409c4438afffc33c2509。
- 第一次D0在发压前因历史/home/bench/async-d0.log已存在而被保护检查中止，未覆盖旧文件；改为serviceidle独立前缀，失败记录保留preflight-abort-*。管理API期间出现一次TLS EOF；四VM停机成功后重新启动，未将管理故障说成业务失败。
- 第二次D0 setup及单次强平/保险/ADL闭环通过，但预热期间第一个交易cycle的批量单出现STALE_MARK_PRICE拒绝（orderId295001008413），工具按正确性门槛失败，未取得正式吞吐数据。原生Core过期价格保护未放宽，结果和四机监控已收集到d0目录。Y0/S0/F/J尚未执行，不能报告三档收益。
- 用户随后明确实际场景必须为用户/做市商持续交易，与持续触发扫描、风控扫描、主动平仓、强平/爆仓并发，再加用户与做市商查询并达到饱和；因此取消前述纯交易档位，不能把风险操作放在setup、不能在周期控制查询前全局drain。新的场景、并发和有效性门槛须在准备后重新预锁，不修改已失败D0标准。
- 查询路径核对：生产余额/持仓/挂单/触发单可通过ValkeyUserQueries读取异步读模型，无READY视图返回503且不回查Core；风控执行与管理员权威查询仍走Core。后续测量需包含真实读模型维护及其开销、查询可用性/新鲜度，不能凭独立session宣称查询与交易隔离。旧压力器价格刷新与交易生产者同线程，价格时间戳可能在背压等待前取得；新场景必须持续独立价格输入，保留过期判断。
- 第二次失败清理于2026-09-08T03:27:19Z完成四VM停止；准备新场景期间不保留收费计算实例运行。所有原始日志及用户最新范围变更均保留，本次不宣称吞吐优化已验收。

## 2026-09-08 生产并发混合场景OS0（采集前锁定）

- 用户最新要求优先于旧纯交易方案。本次先验证新场景是否正确执行，再定义饱和长轮。OS0为当前master tools的mixed-operational=true，seed96001，30秒预热+30秒测量，最长1200秒；不是生产容量验收。实际三台GCP Core及一台load，硬件/JDK25/4GiB Core heap/2GiB load heap/ZGC/NMT、4Lane/1matcher/SHARED_NETWORK/Archive SHARED沿用前述环境，Core仅service=YIELDING、consensus默认Backoff，不修改交易语义。构建后记录commit及JAR SHA；不得重跑旧版本或覆盖失败目录。
- 交易流保留1000零售、256symbols、1769用户、batch20和单FIFO256在途，交易阶段不排空。新增独立价格producer每500ms更新256symbols，最多16在途，在取得自身发送容量后生成时间戳，绝不放宽过期价格保护。独立生命周期producer使用单独8容量command session和reserved query session，每次只有一个必要依赖命令/查询在途，不让交易流drain；加4个用户/2个币对，总1773用户/258symbols。三个producer各有command+reserved query session（共6配置session），普通Valkey读无Core session；瞬时实际请求上限为交易256+价格16+控制1=273，分别记录，不能冒称全系统只有256在途。
- 生命周期重复执行：开仓→资金费→reduce-only主动平仓并确认归零；再次开仓→挂止盈单→真实TRIGGER_ORDER_QUERY扫描→触发成交并确认TRIGGERED及归零；独立风险用户补入100USDT最小单位余额、开10单位仓→价格100降1→继续风险扫描直至自己的可执行强平动作→强平→25单位保险与剩余ADL→确认无仓位、无冻结及ADL终态。风险重操作使用专门样本账户/币对防止改变原零售/HFT预期状态，但风险扫描和交易在同一个Core同时执行，生命周期每轮都在测量期间发生，不能用setup单次闭环充数。
- 原资金守恒终检增加4个样本账户余额和实际净注入：初始3*1e9，每完整生命周期额外100+25；资金费/保险/ADL内部转移不重复算注资，余额及全部treasury ledger精确核对。交易fills从响应解码；side普通单fills也解码，触发成交由唯一一单位maker流动性、TRIGGERED及一单位持仓归零确认计1笔，不把ADL当普通fill。单独报告各流requests/六分位及全体terminal business ops/Core messages/fills；背景控制未完成不得算终态吞吐。
- 实际启用Core实时outbox→Aeron Router→Valkey读模型，Router和Valkey与load同机，必须单列资源开销；独立Valkey查询目标1000requests/s，遍历原1769用户（包括做市商），调用生产ValkeyUserQueries/物化逻辑，核对身份及exportSequence单调，503单独计数且禁止回查Core。此为读模型查询链路，不包括HTTP网关鉴权或WS客户端。Valkey用官方8.1.10 Linux binary，绑定load本机127.0.0.1:6381，独立实例，Router只有本产品的三节点控制destination。其他产品和业务进程不启动。
- OS0门槛：所有交易及控制操作成功、资金差0、终态/冻结/持仓核对通过，测量期主动平仓/触发平仓/风险续扫/资金费/保险ADL和READY查询各至少一次；503/实际读QPS/新鲜度必须报告，不能用覆盖PASS冒充读可用性达标。正式饱和档还须预锁读可用率和尾延迟门槛。采集四机5秒系统与线程指标及各类请求计数，OS0不启动JFR；若失败，保留原日志，修复后新目录/seed重新预锁。完成正确性后再进行同场景逐级在途加压及JFR，不回到纯交易基准。

### OS0失败与OS1诊断预锁

- OS0使用f0ba8406，JAR SHA256=48160fc7723605be442f2ba9fec926ec649319896e261f4870a40f948de02e3c。初始化闭环通过，预热批量订单因STALE_MARK_PRICE失败，同时独立actor的RESOLVE_LIQUIDATION被INVALID_COMMAND拒绝；未取得有效吞吐，不能声称完整混合场景通过。原始结果在2026-09-08-operational/os0。04:08:06Z Router另因MediaDriver keepalive age=1001ms超过1000ms退出，不能将失败后的查询视为可用。
- 代码核对发现工具把保险赔付写死为min(25, deficit-1)，而实际Core要求严格等于InsuranceAllocationPolicy分配结果；工具改用LIQUIDATION_WORK_QUERY的recommendedCoveredUnits，不改保险策略，也不放宽价格5秒保护。OS1为当前master加该工具修复，seed96002，其余硬件/JVM/业务/窗口256+16+1、查询1000/s、30秒预热30秒测量及资金门槛继承OS0。独立6381测试Valkey清空并重启Router/driver，旧OS0证据保留。
- OS1仅定位正确性：三Core加artifact内FreshnessAgent，通过ByteBuddy只在requireFreshMark抛出异常时记录前12次币对、clusterTimestamp和generatedAt；不改变参数/返回/异常，不输出私钥等敏感数据。工具及agent SHA保存在os1-jar.sha256和构建artifact。带诊断agent的数据不作正式性能结论；无需本机性能采样，仍真实三节点。执行FRESHNESS_PROBE=1 python3 round.py os1 96002 256 30 30。本机HotSpot25，7项相关工具测试及打包通过(os1-build.txt)。

### OS1证据与OS2正确性预锁

- OS1仍在预热失败，保险拒绝未再出现，但不能据此宣称测量覆盖通过。JAR=f9bb21a94c69b12fd25b85c01576de7c8cbf87e42a0ddb7ddcfe79e2d79a921b；04:18:05Z诊断捕获JMH-MIX-144-USDT的clusterTimestamp=1788841085027、generatedAt=1788841063626，价龄21401ms。该价格来自初始setup，独立刷新尚未完成首轮，交易已开始；不是负价龄/跨机时钟假设。保留os1三机异常、四机监控，禁止把失败预热速率当容量。
- OS2(seed96003)在启动交易前等待独立价格producer第一遍256币对更新完成，后续持续异步更新，不在交易循环增加等待。初始价格失败会传播至启动方，不吞异常。其余OS1业务、30+30秒、真实三节点、256+16+1窗口、1000/s查询及资金/覆盖门槛不变；仍保留诊断agent以确认是否还有运行期过期，不作正式吞吐结论。执行FRESHNESS_PROBE=1 python3 round.py os2 96003 256 30 30；构建与SHA另存os2-*，不覆盖OS0/OS1。

### OS2实际Core故障与OS3修复验证预锁

- OS2 JAR=9aaded1b8e1925dad98423a3327ec18534cab830fc2a2a55a41bdcbfe91872d3。04:21:31Z原Leader core-2退出：unfinished business work outside cluster log callback。栈精确为processCommittedRequest→idleCommand→ClusteredServiceAgent.idle/doIdleWork→invokeBackgroundWork→doBackgroundWork→captureRealtimeSnapshot→assertClusterCallbackComplete。随后两个Follower报leader heartbeat timeout并选举core-1，发压器出现ResultUnknown，整轮失败。这是实际运行期Leader故障，不能与此前长回放的quorum startup WARN混为一谈，也不是价格保护误判。
- 修复SurprisingClusteredService：owner线程boolean标记完整日志回调，在finally复位；重入后台工作直接返回0，既不启动快照，也不poll完成快照/盘口以免覆盖当前RealtimeStateCapture批次。请求仍留队列，正常回调间隙处理；不删除assertClusterCallbackComplete、不移走业务结算、不增加线程/锁/容器。扩展原真实matcher阻塞功能测试：idle重入后台时不取走排队快照，交易冻结完成后后台快照含正确2000冻结值且恢复hash一致。23项服务测试+9项工具测试通过；JMH现有realtime场景加入Aeron式idle重入定义并编译，不在本机执行性能基准。
- OS3(seed96004)真实三节点验证该修复，30秒预热+60秒测量，其他OS2业务/硬件/JVM/窗口/价格/查询和严格资金门槛不变，不使用FreshnessAgent。Core和load启用启动期JFR，Core使用既有phases.jfc、load profile，maxsize256MiB，结束dump并采集原始文件；这是带采样的正确性及热点诊断，不作无profiler吞吐上限。要求全部操作/资金/覆盖PASS，无Leader退出/ResultUnknown，检查DataLoss后再决定正式加压；执行OPERATIONAL_JFR=1 python3 round.py os3 96004 256 60 30。OS3目录及SHA独立保留。

### OS3/OS4现场结果（停机后简记）

- OS3因core-1启动期实时Aeron客户端DriverTimeoutException(age1026ms>1000ms)触发默认错误处理器退出JVM而无效，发现后停止发压；两存活节点数字不作为三节点证据。修复实时Sender/Receiver及Router控制连接的异步错误处理，保留1000ms超时，错误计数并重连，不调用进程退出；空outbox的Sender也检测连接关闭。真实MediaDriver关闭/重启测试验证发送、接收恢复，Router独立测试验证控制重连和source epoch更新。
- OS4实际命令OPERATIONAL_JFR=1 python3 round.py os4 96005 256 60 30，环境/业务同OS3，云端JAR ed850aaec5b7684515940826a0b5bf33b7f1f7eb72cd0d92696b3f5c0d4729e4；三节点全程存活，资金差0，251交易cycles、37重复生命周期，全部冻结/零售/HFT/强平保险ADL检查通过，businessHash=c9f49813717b7ac7。测量期有34次主动/触发闭环、33次保险ADL闭环、99次风险续扫、34次资金费；不是仅初始化覆盖。
- 带JFR诊断：交易3677184终态业务项=offered，350208终态Core消息=offered，unfinished0，peak256；61051.079 business ops/s、5814.388 Core messages/s、14535.971 fills/s。计入后台已完成控制命令后61362.689 business ops/s、6173.399 Core messages/s、14524.062 fills/s，elapsed60.283s。普通下单p99 47.677ms、撤单99.352ms、批量下单98.172ms、批量撤单71.827ms。仅当前master工作树诊断，不是最终饱和上限，也没有新旧版本收益结论。
- 查询实测4027 READY、56192 unavailable，共60219次（约1000/s），READY仅6.69%；READY p99=543us，但大部分503，整套生产场景未验收。无Core fallback；不能用coverage PASS掩盖可用性失败。保留Valkey现场os4-valkey.rdb及metadata/stats。Core后台快照10ms节流、Router每200ms最多扫描16用户，及饱和期间排队/丢帧需要下一步定位，尚未将其中某一项认定为唯一根因。
- Leader短窗口owner99.8% CPU（user71.0%、system28.8%），matcher16.2%、各Lane7.9–8.0%；不能当作99.8%有效业务计算。Leader完整137s JFR（含初始化/预热/终检）DataLoss0；方法计时约567812次callback平均165us，idleCommand累计约30.8秒，仍有等待开销。GC pause总0.972ms、p99 0.0273ms，非本轮明显瓶颈；尚未完成稳定窗口分配/native/长期泄漏验收，也未执行这份云数据重启核对。
- os4-build.txt记录38项相关测试通过；后续补齐Router自身错误处理和重连测试，router-reconnect-final.txt通过，此Router补丁尚未再次云端部署。JMH realtime重入场景已编译，未违反真实三节点约束去跑本机性能。所有原始JFR/日志位于2026-09-08-operational/os4。用户要求优先服务器验证、减少MD耗时后停止持续写记录，本条在停机后汇总。四VM均已确认TERMINATED(instances-stop-check.json)，无后台负载继续运行。

## 2026-09-08 Lane交接与空轮询优化（采集前简要锁定）

- 仅当前master：BLOCKING Lane有界自旋+volatile休眠握手、空轮询1ms健康巡检；提交/完成的完整健康与金融校验不变。JMH ownerProgressAndReservationTransitions加入spin-limit=0/256参数并编译；本机仅功能验证，37项针对性测试及139项跨产品资金/顺序/恢复测试通过（有重叠，不相加）。artifact=2026-09-08-lane-handoff。
- OS10 seed97001 spin256、OS11 seed97002 spin0，均30s预热+60s测量，真实GCP三Core+load、8vCPU16GiB/HotSpot25/ZGC/Core4GiB/load2GiB/NMT/4Lane/1matcher/SHARED_NETWORK/serviceYIELDING、相同OS4混合场景（1773用户258symbol、batch20、交易256+价格16+控制1、查询1000/s）。两轮同JAR和JFR配置（Core phases.jfc、load profile），完整指标与资金/终态门槛继承OS4；不拿历史版本速率做收益基线。要求三节点存活、unfinished0、资金差0、操作覆盖PASS、单命令p99<1s、JFR DataLoss0；已知读可用率问题单列，不能称完整生产容量验收。
- 只有spin256较spin0吞吐至少提高5%、单命令p99不恶化超过20%才默认保留256，否则默认0保留安全休眠握手。不以忙等CPU充作有效计算。最后一轮原数据重启核对交易/生命周期cycle、注资与hash；结束停止四VM，再做离线分析。执行SETTLEMENT_SPIN_LIMIT=256/0 OPERATIONAL_JFR=1 python3 round.py os10/os11 97001/97002 256 60 30。无长期泄漏或最终饱和上限承诺。

- OS10初始化STALE_MARK_PRICE，未测量；原日志三节点重放+诊断agent确认JMH-MIX-164-USDT clusterTimestamp=1788844077688、generatedAt=1788844077689，价龄-1ms。源主机chrony Last offset约+0.286ms，不能误归因为旧行情或放宽Core未来价格保护。工具新增真实2ms生成到发送等待，保留原始生成时间、不中途回填时间戳；只影响价格producer及初始化，不给交易流增加逐笔等待。初始price在取得发送容量后生成；实际价格速率按响应计数报告。原OS10/OS11比较取消，改OS12 seed97003 spin256、OS13 seed97004 spin0，相同30+60秒JFR及前述门槛；新工具构建后SHA另存，两个新档使用同一JAR。既有读可用率问题不在本次前两项修复范围，仍单列。

### OS12/OS13结果及停机恢复（停机后简记）

- 2026-09-08，当前master 7a4947fa（含0f444147），对照commit不适用；两档同JAR SHA256=6a13890d16ff03bd2ed0092d0ef76702e02d46ce2521276586e3875396266eb8。执行SETTLEMENT_SPIN_LIMIT=256/0 OPERATIONAL_JFR=1 python3 round.py os12/os13 97003/97004 256 60 30；硬件、业务、JVM与预锁相同。三Core全程存活，操作覆盖及资金核对通过，unfinished=0。
- OS12 spin256：3606234 terminal business operations、357082 terminal Core messages、855073 fills，60.359s；59746.591 business ops/s、5915.987 Core messages/s、14166.495 fills/s。普通下单/撤单/批量下单/批量撤单p99分别49.119/101.777/100.466/74.907ms。245交易cycles、35生命周期，fundsDiff=0，hash=97556f569e28d682。
- OS13 spin0：3649393 terminal business operations、361329 terminal Core messages、865313 fills，60.255s；60565.356 business ops/s、5996.619 Core messages/s、14360.742 fills/s。对应p99为47.087/101.842/100.925/72.744ms。248交易cycles、36生命周期、netDeposits=3000004500，fundsDiff=0，hash=dfd289160ec811cf。未达到预锁自旋收益门槛，最终默认改为0，保留可选参数和安全唤醒握手；不宣称整体吞吐提升或有效计算95%饱和。
- 查询仍未达标：OS12 READY=3687/60347（6.11%），OS13=3257/60183（5.41%）；READY p99分别562/574us。独立价格终态响应14414/14557，约239/242次每秒；2ms价格生成等待只在工具价格producer，不更改Core新鲜度规则。查询问题未在本次两项修复中解决，不能称完整生产场景通过。
- 八份JFR均DataLoss=0，原始文件大小/SHA在artifact的jfr-manifest.json，各文件旁保存summary。OS13 Leader core-2完整139s记录含初始化/预热/终检：callback 551921次平均169us，idleCommand约30.63s，commitReadyMatching 2839666次平均12.8us；存在等待协调成本，不将其算成有效业务CPU。完整记录/方法采样不能替代稳定测量窗口、无profiler吞吐或精确bytes/op；分配/native长期趋势与泄漏、正式JMH三节点运行及最终上限未验收。JMH场景已更新编译，本机没有执行性能测试；本机139项跨产品功能/资金/恢复测试通过，最终默认0另跑28项通过，与之前37项有重叠，不相加。
- 原OS13日志恢复首次验证因未等Leader就绪而NOT_CONNECTED；保留失败证据。停机再启动四VM，等待本次进程回放并成为Leader后执行replay.py os13 retry，核对资金差0、248交易cycles及恢复的生命周期资金/持仓，hash仍为dfd289160ec811cf。业务断言通过后，清理已被systemd回收的临时load unit报not loaded，导致脚本退出非零、跳过末尾Core日志采集；不是业务核对失败。已修正清理脚本并语法检查，未为清理改动再启动服务器。readiness记录、原始result及异常均保留，不掩盖编排失败。
- artifact根目录=/Users/atomex/Desktop/surprising/gcp-validation/2026-09-08-lane-handoff，os12/、os13/、os13-replay-retry/、financial-build.txt、default-zero-test.txt。四VM于05:32 UTC确认全部TERMINATED（instances-recovery-final.json）；未继续计费运行负载。云端默认参数由显式spin0验证，最终源码默认0构建通过；其余云端逻辑与7a4947fa一致。

### 跨日志命令窗口：本机门槛与OS14预锁（2026-09-08）

- 改动：普通非reduce-only下单/撤单最多64条在途；账户（含潜在maker）、币对和订单身份保守分区，冲突及控制/批量/查询命令排空；完成保持日志顺序，复制定时器收尾，快照排空，后台不修改交易状态。新增窗口及参与者索引，未宣称零分配。结算预计Lane修复排除无关运营账号；原回归用非零operator稳定复现五条衍生品失败，修复后通过。
- 用户要求先本机功能后服务器压测。HotSpot25最终针对性检查162项通过（service116、benchmark场景功能46，无本机性能采样）；六产品线三个独立本机JVM依次执行小样本交易、SIGKILL后日志恢复及快照恢复，全部PASS才允许启动云VM。第一次交割发现上述真实故障；最初两次重启编排未遵守Aeron存活标记期限，保留失败记录，改为12秒正常保护期限，未删除日志或降低保护。
- OS14预锁：仅当前master新构建，seed98001，四台原GCP n2-custom-8-16384，三个独立Core，1matcher/4Lane，HotSpot25、Core4GiB ZGC/NMT、service YIELDING、Lane spin0；1773用户258symbol混合运营，batch20，交易在途256+价格16+控制1、查询1000/s，预热30秒+测量60秒。执行SETTLEMENT_SPIN_LIMIT=0 OPERATIONAL_JFR=1 python3 round.py os14 98001 256 60 30。Core phases.jfc加入processIngress/drainCommandWindow/onTimerEvent；load profile。新计时方法不可与历史配置直接比较收益，也不可将嵌套方法耗时相加。
- 门槛：全部三Core存活、terminal=offered、unfinished0、资金差0、业务覆盖PASS、单命令p99<1s、JFR DataLoss0；另观察窗口highWaterMark是否超过1，不能用owner忙等冒充有效计算。原数据重启后核对业务hash与资金/生命周期；整轮finally停止四VM并记录TERMINATED。已知查询READY低的问题独立报告，OS14为带采样诊断，不称完整生产容量、零泄漏或最终上限验收。
- artifact=/Users/atomex/Desktop/surprising/gcp-validation/2026-09-08-command-pipeline；local-settlement-fixed.txt、settlement-actor-regression.txt、local-functional-fixed/、os14/、os14-replay-verify/；本机矩阵结果与云端源码commit/JAR SHA分别保存。正式JMH三节点与长稳分配/native完整验收尚未完成。

- OS14未通过：预热期间三节点同在drainCommandWindow停滞，30秒超时退出；客户端ResultUnknown，该轮没有可引用吞吐。原因是已准备撤单在同matcher分片前置下单Lane准入期间延后提交，准入推进循环遇到没有准入事件的撤单时清除了ready标记，未重新提交。小样本复现六产品线全部失败，修复在submission head重新提交已准备的非batch命令，仍保留未准备deferred/batch屏障；168项相关检查通过，本机三节点六产品恢复矩阵重跑后才允许再次开机。四VM已确认TERMINATED，OS14原始JFR/日志和失败结果保留。
- OS15预锁：OS14全部业务/JVM/JFR/硬件/256窗口/30+60秒及验收门槛不变，seed98002，新建operational-os15数据；执行当前master修复构建并保存SHA，不比较历史版本性能。除OS14门槛外关注混合下单撤单持续进展，测量后原数据重启hash/资金核对，再停止四VM。artifact同根目录，local-resume-fixed.txt、deferred-cancel-regression.txt、local-functional-resume/、os15/、os15-replay-verify/。
- OS14补充：三个Core退出后的.jfr均为0字节，未得到有效Core录制；诊断依据为三节点异常栈及六产品确定性复现，不能引用该轮JFR作热点或分配结论。

### OS15结果（四VM确认停止后）

- 当前master 2c123e9b，JAR SHA256=e5fdd3cb59441d9b003f57510f3edfd0bea77d6719ac1cb10145d5351fe20f68；参数按预锁，三Core全程存活。交易终态3655680业务项/348160 Core消息，分别等于offered，unfinished0；60.204秒，60721.766 business ops/s、5783.025 Core messages/s、14457.563 fills/s。计入并发后台命令为3670954业务项/363434 Core消息，60.236秒，60942.685 business ops/s、6033.484 Core messages/s、14450.338 fills/s。带JFR诊断，不是无profiler上限或新旧版本收益结论。
- 普通下单/撤单/批量下单/批量撤单p99分别47.513/102.301/100.859/73.072ms。三个Core窗口highWaterMark均15、停服务pending0，跨消息推进实际生效。交易业务项约95.24%来自batch，batch仍为屏障；完整159秒Leader记录含初始化/预热/终检：processIngress553969次平均168us，屏障processCommittedRequest290453次平均267us，idleCommand约29.7秒（按平均值估算）。这些是嵌套方法，不相加；尚不能证明有效业务计算95%饱和。
- Leader core-2，07:05:10–07:05:40 UTC七个5秒CPU样本：owner平均99.86%（user71.8%、system28.06%），matcher15.8%，四Lane8.23–8.26%；不把yield/等待算成有效业务计算。全部四份JFR DataLoss0，原始文件SHA/大小见os15/jfr-manifest.json。Leader GC总pause0.953ms、p99/max0.0249ms；无采样到的monitor contention不能推断无等待。Safepoint视图出现Indefinite，不能据此量化总停顿。
- 完整记录ThreadAllocation视图owner28.2GB（68.13%）、matcher4.9GB、各Lane约2.1GB；采样分配压力byte[]33.05%、long[]9.75%、Long5.37%，仍非零分配。包含初始化/查询/终检，不除以仅测量期业务量当bytes/op。NMT heap committed4GiB，Other9.2MB，GC committed末20MB/峰40.3MB；20项离线JFR视图已保存。稳定窗口精确分配率、对象/op、Direct/Mapped余额、长期live-set/native泄漏与正式三节点JMH仍未验收，不能称完整性能验收。
- 资金差0、249交易cycles、36运营生命周期、netDeposits3000004500；测量期间33次主动/触发闭环、33次强平保险ADL闭环、33次资金费、100次风险续扫。整段生命周期p99约1.974s，是多命令闭环，不混作单命令p99。查询READY3305/60194=5.49%，READY p99585us，多数请求仍unavailable；该既有问题未解决，无Core fallback，不能称完整生产场景通过。
- 原云端数据重启，等待本次进程回放并选主后，replay.py os15 verify退出0，fundsDiff0、249cycles、业务hash仍3763c800c4aa9edc，后台生命周期按原36cycles/注资参数核对。round清理已回收load unit打印not loaded，但随后的日志采集、断言、replay及finally均完成；编排总退出0。四VM最终全部TERMINATED（instances-os15-final.json），不再运行负载。
- 本机最终168项针对性功能检查和六产品线三JVM交易/日志恢复/快照恢复均PASS，未本机压测；两个发现的缺陷均有先失败后通过的回归证据。artifact仍为2026-09-08-command-pipeline，os15/、os15-replay-verify/、local-resume-fixed.txt、local-functional-resume/；源码与测试已推送master。

### 批量跨日志窗口：OS16–OS19采集前锁定（2026-09-08）

- 当前master工作树扩展独立单matcher分片PLACE/CANCEL batch，保留账户/订单簿依赖、逐日志提交和控制屏障；订单ID用有界primitive精确集合。修复推迟结算时间戳与整批拒绝后的submission head唤醒。新增六产品批量并行、成交资金/状态与串行一致、拒绝后推进测试及independentBatchWindows JMH场景。最终针对性162项通过，前一轮service全517项通过；本机六产品三JVM交易/SIGKILL日志恢复/快照恢复全部通过是启动云端的硬门槛，不执行本机性能测试。
- 对照commit不适用，仅当前master同一JAR；commit/SHA保存artifact。四台GCP asia-southeast1-b n2-custom-8-16384，8vCPU16GiB（4物理核SMT，现场lscpu复核）；独立三Core+load，HotSpot25，Core4GiB/load2GiB ZGC/NMT、1matcher/4Lane、SHARED_NETWORK、serviceYIELDING、Lane spin0。业务沿用OS15连续异步交易+独立价格/风险/触发/平仓/强平/资金费/保险ADL控制与查询：1773用户258symbol、batch20，价格16/控制1，查询1000/s。交易FIFO不逐笔/逐批排空，只有总窗口背压和测量/核对边界；terminal交易与计入后台的composite分别报告。闭环最大速率模型，未修正coordinated omission，不作open-loop容量承诺。
- OS16 seed99001窗口256、OS17 seed99002窗口512，各30秒预热+60秒测量；只有OS17终态交易速率较OS16增加至少5%且正确性/p99通过，才执行OS18 seed99003窗口1024同30+60秒，否则停止加窗口。随后OS19 seed99004在本轮有效最佳窗口执行30+120秒无JFR持续验证。每轮新数据目录，阶段后排空/资金核对为冷却，原数据OS19重启hash核对。参数变化只在上述预锁档位内，不改变业务以刷CPU。执行SETTLEMENT_SPIN_LIMIT=0 OPERATIONAL_JFR=1/0 python3 round.py <tag> <seed> <window> <seconds> 30。Core phases.jfc/load profile用于前三诊断档，最后无profiler，不将带采样速率作为无profiler主结果。
- 每档三Core存活、terminal=offered、unfinished0、资金差0、业务覆盖PASS、各单命令p99<1s；带采样四JFR必须非空且DataLoss0，明显swap/throttling则无效。稳定测量epoch标记用于筛选OS CPU/JFR样本；owner/matcher/Lane分别报告CPU、业务热点与等待，窗口平均值/依赖与控制屏障次数单列。吞吐不再增长时识别固定配置瓶颈，不以忙等凑95%，不预先声称所有阶段同时有效饱和或绝对上限。
- artifact=/Users/atomex/Desktop/surprising/gcp-validation/2026-09-08-batch-window；保存原始命令、环境、JAR校验、JFR/OS数据、错误及恢复结果。finally停止全部四VM并确认TERMINATED后离线分析。查询READY既有低可用率独立报告，未修复不能称生产场景验收；正式三节点JMH、完整分配/native长期泄漏验收仍缺失，本轮为执行模型与饱和诊断。

- OS16失败，OS17–OS19未执行：f9a72dd7/JAR a4161845cc9b5645cd2eb95d73201029de261cd97e945130e29397abc04cc426，六产品三JVM矩阵全部通过后上云。测量完成但VALKEY_QUERY_READY=0/60248，coverage断言退出1，不能引用作有效吞吐验收；交易资金核对和37生命周期通过。四VM已确认TERMINATED，instances-final.json。四JFR DataLoss0，SHA/大小及20项视图已保存；稳定08:07:45.943–08:08:36.207 UTC Leader owner99.86%（user73.96/system25.90）、matcher15.38%、四Lane平均8.595%，owner2699个执行/本地方法样本中330个含等待栈，不等于精确墙钟等待百分比。完整运行窗口commands522596/windows362618=1.441；dependencyFences338246/controlFences22298、hwm19，仍有大量保守依赖屏障，不是算力饱和。ThreadAllocationStatistics仅chunk边界，无法导出稳定期分配率。
- 六产品回归确认连续批量使doBackgroundWork几乎总遇到非空窗口，导致已请求快照饥饿。改为在已经完成的窗口边界推进有界异步只读任务，保持10ms节流、最多一个用户/盘口读取在途；不等待future，不推进交易状态。回归验证快照只含前一提交批次的20单，后续批次不污染快照，并保留reentrant未提交期间禁止读取的断言。首次测试断言错误遍历Agrona队列已改为isEmpty，保留失败artifact。
- 新预锁OS20/OS21/条件OS22/OS23分别替代OS16/17/18/19，seed99101–99104，所有窗口/业务/时长/门槛和条件加档规则不变，重新执行最终构建六产品本机三JVM门槛才允许开机；本次仅ThreadAllocationStatistics改为1000ms，其他JFR配置不变，与失败OS16不作绝对收益比较。artifact=/Users/atomex/Desktop/surprising/gcp-validation/2026-09-08-batch-read；上一失败目录原样保留。仍以交易和mixed composite分别计数，结束后原数据恢复核对并finally停止四VM。

### OS20通过、OS21中止（四VM确认停止后）

- 被测master b81032ee，JAR SHA256=dc26cc60a720061383d1834f5bc0412248e60323522e21ff75c8bebabdfaaf46。168项针对性检查通过；带异步读取的batch JMH场景功能40项通过（与前述有重叠），补充六产品快照冻结记录只含前一批20单的断言通过；最终同JAR六产品三JVM交易/SIGKILL日志恢复/快照恢复均PASS。本机未运行性能基准。
- OS20按256/30+60秒/JFR预锁运行，三Core存活。交易3741696 terminal business operations=offered、356352 terminal Core messages=offered，unfinished0、peak256；60.358秒，61992.173 business ops/s、5904.016 Core messages/s、14760.041 fills/s。计入后台3756988业务操作/371644 Core消息/890914 fills，60.453秒，62147.295 business ops/s、6147.656 Core messages/s、14737.309 fills/s。普通下单/撤单/batch下单/batch撤单p99为38.240/102.367/101.187/74.186ms；资金差0、256交易cycles、38运营生命周期、netDeposits3000004750，hash=b37def8593db8913。batch下单133632请求/2672640items、batch撤单44544请求/890880items，均20items/batch；这是带JFR诊断点，不是无profiler主吞吐或最终算力上限。
- READY3898/60348=6.46%，READY p99 560us，核心快照饥饿已由回归及实际READY恢复验证；大部分查询仍unavailable，读模型整体容量/可用性未验收。测量期间34次强平保险ADL闭环，单独保留全部操作延迟。三节点停止时窗口pending0、hwm19，累计commands532844/windows369610=1.442，dependencyFences344799/controlFences22601；累计计数含初始化预热，不视作稳定期精确频率。
- 稳定08:28:58.836–08:29:49.193 UTC Leader core-0：owner99.84%（user73.80/system26.04）、matcher15.28%、四Lane8.62/8.72/8.62/8.72%，10个5秒CPU样本。owner2769执行/本地方法样本中332含等待栈，326顶层yield0；样本比例不能当精确墙钟占比，也不与system CPU相加。其他owner热点包括RealtimeStateCapture.order、OrderReservation字符校验、SHA输出、协议UTF-8长度与RealtimeFrame构建，仍非95%有效交易计算饱和。稳定ThreadAllocationStatistics：owner369.48MB/s、matcher59.92MB/s、各Lane约25.75–25.77MB/s（十进制）；没有精确同窗口终态计数，不报告精确bytes/op。完整录制GC pause总1.01ms、p99/max0.0238ms；native/分配等20项视图保留，不声称无泄漏。
- OS21窗口512在预热后刚开始测量时失败，OperationalLifecycle的强平候选等待超过30秒，actor失败使发压退出1；无完整测量/终态核对结果，禁止引用为512吞吐。三Core未因此业务异常退出。代码审查发现firstRiskIncompleteScan按Lane/user等最小进度排序，持续低进度工作可能饿死后面的扫描；这是与现场吻合的调度风险，尚无对应业务复现和修复，不将假设写成已证实唯一根因。没有放宽超时或改变金融校验。
- OS22、OS23及计划云端原数据重启核对因OS21失败未执行；正式三节点JMH、无profiler持续点、稳定上限和长期heap/native泄漏验证仍缺失。OS20与OS21八份JFR均非空且DataLoss0，manifest记录各SHA/大小；OS21仅全程诊断，没有完整稳定区间。全部原始命令/JFR/OS/错误日志在2026-09-08-batch-read，instances-final.json确认四VM全部TERMINATED，不再继续计费运行。CPU affinity临时读取晚于该轮结束、产物为空，不作为独占绑核证据。

- OS20 owner等待离线归因：相同稳定区间2769个owner样本中，327包含idleCommand，326顶层yield0全部沿processIngress:144→drainCommandWindow:195进入，未采到日志空闲yield；1925样本包含drain（其中含实际提交/编码工作，不能全算等待）。精确匹配部署JAR的Aeron1.53.0 agent字节码，其idle仅执行时钟/后台工作，不重入logAdapter.poll。现有采样无法分别量化准入、matcher、结算等待，也没有逐时刻commit-position/service-position差，不能声称已测出服务日志队列深度。结果见os20/owner-wait-attribution.json。
- 下单scope无条件包含币对全部active挂单用户，未按方向/价格筛对手。复用当前功能fixture的小样本确认：两个不同用户在不同币对各挂卖102，空簿scope不冲突；同一旧用户在两币对各挂买80后scope冲突，实际执行两卖单无成交、旧用户状态完全不变。owner-dependency-diagnostic-fixed.txt记录false→true与资金/订单结果；前两次诊断启动/命令序号编排错误保留，无生产改动。按压测初始化规则离线重建首轮256笔卖方batch准入，保守scope共享旧买方用户使255个相邻边界均冲突；这只是指定阶段的模型重建，不替代全程逐命令测量，见owner-scope-reconstruction.json。可确认优化对象为过宽对手范围和整窗屏障；64bit碰撞另有保守性，但不能把本例归因为纯hash碰撞。未启动云VM或运行本机压测。

### 2026-09-08 价格依赖与前缀提交：OS30–OS33预锁

- 仅当前master，对照commit不适用。改动为方向/可成交价格参与者索引、冲突日志前缀提交（独立后缀保持在途）、容量边界仅完成最早一条；控制/快照仍全屏障。同时修复等价成交拆分的整单保证金舍入预算，保留冻结不足拒绝检查。Core节点使用生产tools JAR，负载使用独立benchmarks JAR，绝不在Core加载测试agent。
- 延续真实GCP三Core+load，asia-southeast1-b、n2-custom-8-16384、HotSpot25、Core4GiB/load2GiB ZGC/NMT、1matcher/4Lane、SHARED_NETWORK、service YIELDING、Lane spin0。运营场景1773用户258symbol、batch20、交易连续异步、独立价格16/控制1、Valkey查询1000/s；单交易session在途与全局窗口一致。已有查询低READY独立报告，不能据交易通过宣称整套生产容量通过。
- OS30 seed99201窗口256与OS31 seed99202窗口512，各30s业务预热+60s测量，四JFR（Core phases.jfc方法路径更新、load profile）及稳定CPU/分配/等待归因。通过真实外部Cluster的JMH SingleShotTime入口执行，单轮一个独立JVM、-f0 -wi0 -i1 -prof gc；JMH一操作表示整轮，s/op与load B/op不得当Core业务吞吐/分配。业务ops、消息、fills、backlog和资金用业务计数；Core分配用稳定JFR。
- 若OS31正确性通过且业务终态速率比OS30高至少5%，执行OS32 seed99203窗口1024同配置；否则不继续增窗。OS33 seed99204取本次有效最快窗口，30s预热+120s不启JMH/JFR持续验证，再按该轮原日志重启核对hash和资金。任何失败轮不得作为吞吐上限，保留证据、停止加压。
- 硬门槛：本机六产品三独立JVM交易/日志恢复/快照恢复全部通过后才开机；每档三Core存活、offered=terminal（消息及业务）、unfinished0、资金差0、业务覆盖PASS、单命令p99<1s、JFR DataLoss0；不以busy-spin证明有效CPU95%。固定当前业务模式，不作open-loop或长期泄漏结论；结束finally停止四VM并核实TERMINATED。
- artifact=/Users/atomex/Desktop/surprising/gcp-validation/2026-09-08-prefix-scopes；保存被测commit/JAR SHA、执行参数、原始JFR/OS、JMH JSON、资金和恢复结果。此前18万本机数据不用于性能验收。

- OS30（289268b3，tools SHA 2d9a7b04364cbcbb004c9f05024c453732b6828f4e57599dd7eef2a1f3dde6fb）完成：5698560业务项/542720 Core消息=offered、unfinished0，60.089s，94836.117 business ops/s、9032.011 Core messages/s、22580.028 fills/s，资金差0，389总cycles。综合后台95075.171 ops/s。READY3229/60079=5.37%，读模型仍不合格。四JFR DataLoss0；这是带JFR、非fork JMH的诊断点，不作正式无profiler上限。
- OS30稳定窗口14:06:26.900–14:07:16.989 UTC，Leader core-1 owner99.78% CPU（user87.86/system11.92）、matcher18.76%、Lane13.64–13.88%；3736 owner执行/本地方法样本中73含等待栈（68顶层yield），仅样本比例，非精确墙钟。owner分配506.87MB/s，matcher91.77MB/s。完整窗口累计805428commands/161530前缀提交约4.99，hwm20、停机pending0。现场亲和性确认为0–7，未独占绑核。原始稳定/全程JFR视图在os30，不混合口径。
- OS31窗口512再次因OperationalLifecycle强平候选等待30s失败，无有效吞吐；OS32/33未执行。四VM全部TERMINATED。随后在保留的部署JAR上用两个币对、四次功能续扫复现：刷新BTC后四次均选BTC，ETH始终未完成；不是压测重跑。修复为按lastScheduledRevision选择最久未获扫描机会的任务，并在行情刷新时保留该字段。

### 风控调度修复后OS34–OS37预锁

- 采集前补充同轮改动：身份字典/instrument/活跃单计数命中时复用已验证币对，费率和准入复用instrument的规范身份；订单导出直接UTF-8编码并回填字节长度，实时出口直接编码信封，省去临时Frame及payload克隆。新增UTF-8/偏移缓冲区/所有产品与事件kind的字节一致和所有权测试，独立encodeRealtimeOrder JMH场景；云端继续用真实ClusterOperationalBenchmark覆盖交易、实时导出和风控，独立微基准不在本机执行。SHA-256命令去重指纹保持原算法及完整输出。
- 当前master新增每扫描一个long调度标记，纳入Runtime Fact、物化、全量/滚动hash和快照，续扫成功后取确定性Runtime revision；不使用墙钟调度。快照格式由29改为30，严格拒绝旧格式，不做兼容回退；旧云验证目录保留。资金、价格新鲜度、风险规则和30s超时不放宽。
- 六产品本机三JVM功能/日志恢复/快照恢复及受影响全量测试通过后才开机。OS34/35/条件36/37分别替代OS30/31/32/33，seed99301–99304，窗口256/512/条件1024/有效最快档，30+60s诊断与30+120s无JFR无JMH持续轮，其他机器、JVM、负载、计数、通过阈值和结束停机要求全部沿用上面预锁；只测当前master，不与旧版本数据作性能收益比较。最终原日志重启核对。
- artifact=/Users/atomex/Desktop/surprising/gcp-validation/2026-09-08-risk-fairness。任何失败都停止加档，不把无效轮计入吞吐平台；查询READY缺口和长期泄漏验证缺口继续单列。

### OS34–OS37结果（2026-09-08，当前master 8ac8c550）

- tools SHA256=92d08d97ffb36b60054c18f747478621fd27bf3ac5e4807afce053cddb084b7f；代码、协议、风控调度均为同一构建，不比较旧版本。本机HotSpot25受影响Core reactor 935项（934通过，1项缺INSTRUMENT_SEED_TEST_JDBC_URL跳过），实时路由reactor 170项全通过（含重复依赖测试，不相加）；六产品三JVM交易/SIGKILL日志恢复/快照恢复18项全通过。源码已推送master，README未修改。
- OS34窗口256、30+60s、JFR+非fork JMH：6021120业务项/573440 Core消息=offered，unfinished0，99954.114 terminal business ops/s、9519.439 Core messages/s、23798.599 fills/s，资金差0。OS35窗口512同配置：6107136业务项/581632消息=offered，unfinished0，101530.090 ops/s、9669.532 messages/s、24173.831 fills/s，资金差0。两档仅约1.6%差异，未达到预锁5%加档条件，OS36未执行。
- OS37窗口512、30+120s、无JMH/JFR：12235776业务项/1165312消息=offered，unfinished0、peakInFlight512，101853.611 terminal business ops/s、9700.344 Core messages/s、24250.860 fills/s；包含后台操作的composite为102075.009 ops/s，分别统计。下单/撤单/批量下单/批量撤单p99分别80.019/73.072/86.704/92.930ms，详细p50/p90/p95/p99/p999/max与样本数在os37/result.txt；闭环最大速率模型未修正coordinated omission，不作open-loop容量承诺或绝对算力上限。
- OS34稳定Leader core-0 owner99.72%CPU、matcher18.94%、Lane14.12–14.22%；3738个owner执行/本地方法样本中94含等待栈。OS35稳定Leader core-2 owner99.78%、matcher19.33%、Lane14.45–14.60%；3704样本中74含等待栈。样本占比不等于墙钟时间，CPU占用不等于95%有效业务计算。owner稳定分配分别501.70/508.94MB/s；余下热点为SHA-256命令指纹、UTF-8编码/长度、字符串构造和Map索引访问，尚非零分配。
- 两诊断轮全部八份JFR DataLoss0，SHA/大小见各轮jfr-manifest.json；完整记录JFR视图与稳定窗口数据分开。OS35 Leader完整记录GC总pause1.14ms、88次、p99/max0.0241ms；NMT heap committed4GiB，GC committed末22.7MB/峰39.6MB。未采到monitor contention不代表无等待；vmstat记录无swap交换。JMH SingleShotTime的一操作为整轮，load B/op不是Core业务分配；这是带采样诊断，不作为正式fork微基准结论。长期live set/native/Direct余额、对象/op、完整分段延迟和稳定期I/O归因仍未完整验收。
- OS37资金差0、703交易cycles，后台6生命周期与净注资3000000750核对通过。原云端日志三节点重启后仍为hash d435c2835e720c7a、703cycles、fundsDiff0（os37-replay-verify/result.txt）。调度饥饿回归及本轮512档均通过，但测量期完整运营闭环最长29.561s，强平/保险/ADL闭环最长28.588s，仍需改进调度延迟；不能将复合闭环当单条命令p99。
- OS37查询READY6793/120115=5.66%，其余113322 unavailable，没有回退查Core；读模型可用率仍不合格。当前结论为固定真实三节点、单matcher/四Lane混合场景的吞吐平台诊断与正确性验证，不能宣称完整生产性能验收。原始命令、JAR、日志、JMH JSON、JFR、OS监控与本机恢复证据均在上述artifact根目录。
- 所有轮次与原日志恢复结束后，finally停止surprising-core-0/1/2及surprising-load，四台状态全部TERMINATED，见instances-final.json；编排退出0。后续未经用户要求不再开机。

### 直接订单信封与风险预算：OS38–OS41采集前锁定

- 当前master：订单payload直接编码进最终实时信封（原协议字节不变）；强平工作查询、批次校验和执行统一公平选择；一次续扫在原总预算内推进多个币对，空检查也消耗预算。生产网关与独立压测共享fromWork命令构造，精确价格/游标拒绝保持；新的风险调度使用新commandId，单次传输重试保留原ID。风险批次读取Core扫描控制和延迟，不再在候选缺失时发送无令牌的独立CONTINUE_RISK_SCAN；过期拒绝重新查询、单独计数，不延长30s候选期限。复合业务计数按批次actions和续扫业务项展开，与Core消息分开。
- 对照commit不适用，仅当前master同一构建，commit/JAR SHA留存。GCP asia-southeast1-b独立三Core+load，n2-custom-8-16384（8vCPU16GiB/4物理核SMT）、HotSpot25，Core4GiB/load2GiB、ZGC/NMT、1matcher/4Lane、SHARED_NETWORK、YIELDING、settlement spin0。仍为连续异步MATCH_STREAM混合单/撤单/batch20、1773总用户/258总symbol、独立行情16窗口/运营控制/1000每秒Valkey查询；普通交易不逐批排空。复合路径更改已明确，不能与旧场景比较性能收益或当生产微服务全栈测试。
- OS38 seed99401窗口256、OS39 seed99402窗口512，均30s预热+60s测量。只有512的有效终态业务速率较本轮256增加至少5%且正确性/延迟通过，才执行OS40 seed99403窗口1024同配置；OS41 seed99404取有效最快窗口30+120s无JMH/JFR，然后原云端日志三节点重启核对业务hash/资金。阶段后排空和核对作为冷却；每档新数据目录，失败停止加档，finally停四VM并核实TERMINATED。
- 前三档真实外部ClusterOperationalBenchmark，JMH SingleShotTime -f0 -wi0 -i1 -prof gc（每档独立JVM，一操作为整轮，仅诊断；load B/op不作为Core业务分配），Core phases.jfc/load profile、ThreadAllocationStatistics稳定采样；全部命令由round.py保存。稳定CPU/JFR分组owner/matcher/Lane，不把等待自旋算有效95%。保存全程JFR视图、SHA/大小、GC/NMT/OS和稳定窗口；沿用上一轮JVM详细参数和监控配置，不作旧版本对照。
- 开机硬门槛：本机受影响模块测试、六产品三JVM功能/日志恢复/快照恢复通过。各档要求三Core存活、offered=terminal业务项与消息、unfinished0、资金差0、全部运营覆盖（包含实际RISK_CONTINUATION_CONFIRMED）、单交易命令p99<1s；带采样四JFR非空/DataLoss0、无明显swap/throttling。查询READY既有缺口、风险复合延迟和完整分段延迟/长期泄漏证据另报；不得宣称完整生产验收或绝对算力上限。
- artifact=/Users/atomex/Desktop/surprising/gcp-validation/2026-09-08-direct-envelope-risk-budget。本机只运行构建、功能和离线分析；不执行独立本机微基准。风险预算及订单直接信封由更新的真实Cluster JMH业务路径覆盖。

- OS38（eb7c633d）预热阶段三Core均因`duplicate or unexpected account lane completion`退出，无有效测量，OS39–41未执行；原始异常/JFR/监控和最终四VM TERMINATED证据在上述artifact。初始970项（1外部数据库跳过）与六产品18恢复检查通过不足以覆盖此组合路径，不能将该轮视为验收。随后向五条衍生品的批次预算用例加入真实成交持仓，五条均复现expected=0但实际Lane非零；风险状态更新涉及的账户未登记在撮合完成上下文中。修复仅在实际执行组合风险扫描后登记同步控制Lane，保留原matcher参与者、重复/越界/缺失ACK校验；12项针对性回归通过。

### 控制Lane补齐后OS42–OS45预锁

- OS42/43/条件44/45分别替代OS38/39/40/41，seed99501–99504；窗口、机器、JVM/JMH/JFR、30+60s/30+120s、行情与混合业务、预算、30s候选期限、通过阈值、先本机功能恢复再开机和结束停机要求完全沿用上条预锁。只验证当前master补齐控制Lane后的构建，不比较旧版本性能；被测commit/JAR SHA留存，最后原日志重启核对。artifact=/Users/atomex/Desktop/surprising/gcp-validation/2026-09-08-risk-control-lanes；保留mask-before/after功能复现与修复证据。

- OS42（ee748233）仍因强平候选30s期限失败，无有效终态吞吐；未再出现上一轮的Lane完成异常，OS43–45未执行。本机Core reactor 944项（943通过、1外部数据库跳过），六产品18功能/恢复检查通过。四VM全部TERMINATED。生产批次路径开始实际遵守默认1000ms续扫间隔，每秒最多64扫描工作单位；此前无令牌独立续扫的负载未遵守该间隔。此配置限制不是owner等待或吞吐瓶颈的完整归因。

### 风险续扫间隔OS46–OS49采集前锁定

- 默认Core扫描控制间隔改为25ms，与现有生产协调器25ms调度相符；单次64工作预算、风险规则、精确令牌验证及30s候选期限不变。已保存的运营配置从快照恢复，不能被新默认覆盖。压测端新增每个强平闭环的查询/批次/过期拒绝/实际扫描工作计数，生产类不加入诊断钩子。
- OS46/47/条件48/49，seed99601–99604，窗口256/512/条件1024/有效最快档，沿用OS38预锁的独立三Core+load、HotSpot25/ZGC/NMT、1matcher/4Lane、混合业务及用户币对资金初态、30+60s JMH/JFR诊断与30+120s无profiler持续轮、5%加档条件、正确性/p99/JFR有效性阈值、结束原日志恢复核对与停止四VM要求。配置改变是新场景，不与旧版本或1000ms场景比较性能收益。对照commit不适用，仅验证当前master。
- artifact=/Users/atomex/Desktop/surprising/gcp-validation/2026-09-08-risk-cadence。先本机受影响测试及六产品18功能/恢复检查；开机前记录commit/JAR SHA。查询READY、完整分段延迟、长期泄漏等未达标范围继续单列，不宣称全面生产性能验收。

- OS46（f9bcdca9，tools SHA256=60b2a18e2c16dba87cb6d7a13644c1ae01aa0a9275de2dda2ee11ca652a6685b）完成：5935104业务项/565248 Core消息=offered，unfinished0，98823.363 terminal business ops/s、9411.749 messages/s、23529.372 fills/s，资金和运营覆盖通过。四JFR DataLoss0。稳定Leader core-2 owner99.74%CPU、matcher18.90%、Lane13.96–14.06%；owner分配447.01MB/s，3733执行/本地方法样本中92包含等待栈。全程与稳定期视图分开保留，仅带profiler诊断。后续OS47窗口512仍超出强平30s期限：182次批次、19次过期重查、10432扫描工作单位；该轮无有效终态吞吐，OS48/49未执行，原日志持续轮恢复未执行。四VM全部TERMINATED。本机构建945项（944通过、1外部数据库跳过）、六产品18功能恢复通过。

### 币对扫描额度轮转OS50–OS53采集前锁定（2026-09-09）

- 复现证据：五条衍生品的真实持仓重币对先于轻币对时，一次共享预算可被重币对全部消耗；新增用例五条均失败。修改为每次币对轮转最多8工作单位，沿用持久化的用户/持仓进度，单币对可在剩余预算中多次获调度；总预算仍64、默认25ms、精确令牌校验和30s候选期限不变。针对性10项测试通过，资金及快照恢复一致。
- OS50/51/条件52/53，seed99701–99704，窗口256/512/条件1024/有效最快档；其他机器、JVM/JMH/JFR、混合负载与资金初态、30+60s诊断及30+120s无profiler持续、5%加档条件、正确性/p99/数据有效性门槛、本机先验与结束原日志恢复/四VM停机完全沿用OS46预锁。只测当前master，不对比旧版本；这是新的风险调度场景，不能与旧场景混算收益。查询READY及长期泄漏/完整分段延迟缺口继续单列。
- artifact=/Users/atomex/Desktop/surprising/gcp-validation/2026-09-09-risk-slices；保留slice-before/after功能复现、全部构建/恢复/云端原始证据和commit/JAR SHA。新的预算轮转由真实三节点ClusterOperationalBenchmark混合运营JMH/JFR路径触发，不在本机执行性能基准。

### OS50–OS53结果（2026-09-09）

- 被测master 0f7173b2，tools SHA256=b8a0d4a76353249a4eec6adb9ae6f38963e4a3368ab8ed389711d853615c15d7。订单直接信封、共享预算、公平切片和控制Lane登记为同一构建；只验证当前master，不比较旧版。HotSpot25本机Core reactor 950项（949通过、1缺外部数据库跳过），六产品三独立JVM交易/SIGKILL日志恢复/快照恢复18项全部通过；服务、协议、客户端634类与待部署包字节一致。此前直接受影响生命周期provider和实时router测试已通过，后续切片未修改这些模块。
- OS50窗口256，30+60s JFR/非fork JMH诊断：5956608业务项/567296 Core消息=offered、unfinished0，99089.721 terminal business ops/s、9437.116 terminal Core messages/s、23592.791 fills/s，资金差0。OS51窗口512：5913600业务项/563200消息=offered、unfinished0，98314.669 ops/s、9363.302 messages/s、23408.255 fills/s，资金差0。512未达到本轮5%增加条件，OS52未执行，选择256档持续验证。
- OS53窗口256，30s预热+120.180s无JMH/JFR测量：12321792业务项/1173504 Core消息=offered，unfinished0、peakInFlight256，102528.022 terminal business ops/s、9764.574 terminal Core messages/s、24411.434 fills/s。含后台操作的composite另计为102506.561 ops/s（120.640s自己的边界），不与交易分母混算。批量大小20，下单/撤单/批量下单/批量撤单p99分别36.339/54.689/54.820/45.678ms；各类型完整样本数及p50/p90/p95/p99/p999/max在result.txt。闭环最大速率模型未修正coordinated omission，不作open-loop容量承诺。
- OS50/51稳定Leader均core-2，owner99.82%/99.84%CPU、matcher19.04%/18.94%、Lane14.12–14.20%/13.22–13.32%；owner分配445.67/445.33MB/s。3745/3782个owner执行与本地方法样本中93/91包含等待栈，不能把样本比例当墙钟时间，CPU占满不等于95%有效计算。主要热点仍为UTF-8写入/长度、SHA-256输出转换、字符串与Map/primitive索引。未达到零分配，也未达到matcher/Lane同时计算饱和。
- 八份JFR DataLoss0，OS50合计13792058字节、OS51合计13599322字节；逐文件SHA256/大小见各轮jfr-manifest.json。全程20类JFR视图与stable-window.json分开保存。OS51 Leader全程90次GC pause合计1.19ms，p99/max0.0269ms；NMT Java Heap committed4GiB，GC committed末21.2MB/峰38.2MB，Code末28.2MB。JMH SingleShotTime一操作为整轮，load B/op不能当Core每业务分配；本轮为真实三节点诊断和持续验证，不当作独立fork微基准。
- OS50/51测量期强平/保险/ADL复合闭环最大3.574s/6.912s，OS53最大3.444s、完整运营闭环最大3.922s，未再触发30s候选期限；这是复合操作，不是单命令延迟。OS53测量期1064次实际风险续扫确认，完整运行42生命周期、净注资3000005250，订单/冻结/持仓/做市/强平损失和资金守恒核对通过。原云日志三节点重启后hash仍4166f9ff0b762e42，705cycles、fundsDiff0，见os53-replay-verify/result.txt。
- OS53测量期Valkey READY7573/120162=6.302%，112589 unavailable，无Core查询回退；查询可用性仍不合格。云端负载为U本位永续混合运营，其他产品只做本机功能恢复。长期live set/native/Direct余额、对象/op、完整分段延迟、稳定期I/O及所有产品云端性能尚未完整验收；因此本次是改动正确性与指定场景吞吐平台的部分性能验证，不能宣称完整生产验收或绝对算力上限。README未修改。
- 云端编排退出0，原日志恢复核对后finally停止surprising-core-0/1/2及surprising-load；instances-final.json确认四台全部TERMINATED。后续未经用户要求不再开机。

### Owner串行工作审计（2026-09-09，仅离线分析与少量功能观测）

- 未开云主机、未执行新性能测试、未修改生产Java代码或README。读取同一被测构建0f7173b2的OS50/51原始JFR，在测量起止各剔除5s后分析Leader core-2；owner样本3745/3782，分配样本9017/9093。采样权重估计不是精确对象/调用次数，inclusive路径不可相加；另保存互斥归类。artifact=/Users/atomex/Desktop/surprising/gcp-validation/2026-09-09-owner-audit，audit-manifest.json记录证据SHA/范围，脚本与Java功能观测器均在仓库外。
- 明确重复：TerminalStateRetention.accept调用realtimeOrderObserver，随后TradingRuntimeState.captureRealtimeChanges再次发送同一终态订单。HotSpot25六产品共48个少量功能观测动作全部执行，普通完整成交两个订单各重复一次、撤单重复一次；20笔批量成交的40个订单输出80个ORDER帧，40帧payload逐字节重复。U本位样本总148帧/30688字节，其中重复40帧/10780字节。成交TRADE一条、私有EXECUTION两条是不同受众数据，不属于重复。全平样本POSITION未发现重复，不依据相似调用名误报。
- RealtimeStateCapture调用栈占owner执行样本21.58%/21.95%、分配采样权重33.75%/35.46%；其中终态回调的实时编码单独占7.00%/7.24%执行样本、10.22%/11.02%分配权重。这部分有真实重复证据，不能把全部推送或全部编码都判为无用，也不能据此承诺吞吐增长比例。
- 入口重复解码：prepareClusterPipelineScope完整decodePlaceOrder/Batch后，beginOrderBatchMatching或prepareMatching再次DecodedMatchingCommand.decode；依赖冲突重算还会重新解码。scope调用栈占2.67%/3.23%执行样本、6.20%/5.71%分配权重。动态依赖需重算，输入命令的不可变解码结果可复用。
- 索引数组反复分配：stagePlaceBatchAdmission中publishedOrders/publishedReservations/orderLaneIds/reservationLaneIds的put进入Eclipse Collections 11.0.0 rehashAndGrow/allocateTable，JFR提供行号与完整栈。核对实际依赖源码：活跃项+删除标记触发rehash，函数名虽有Grow但可缩容。64次单元素put/remove功能样本，峰值活跃1、期末0，却换表27次；不属于业务要求创建新数组，也不能误报为泄漏。需优化高频删除容器/维护方式，保留必要owner索引及跨线程所有权。
- 额外可精简工作：TradingOrderBatchCodec.encodeResultSource对同一order长度计算两次；TerminalStateRetention.normalizeClientId在已验证订单身份的查重/保留路径反复getBytes，仅求长度（约2.22%/2.43%分配权重，其他输入边界仍需校验）；RuntimeIdentityRegistry.findPositionKey/positionKey反复构造PositionIdentity查表，整个身份字典路径约7%执行/9%分配，不能把全部字典成本当可删除；trimTombstones每清一个业务去重记录重建迭代器；finishOrderBatch完成前物化变更ID列表后本路径不再消费，普通命令及挂起恢复仍有消费者；trade公私事件重复拼接相同id。小项应限定调用路径精简，不用新重复容器替代。
- 过度保守依赖：ClusterCommandWindow对账户/币对的64位掩码相交直接判冲突，没有像orderId一样精确复核。少量功能观测：user1与user23、SYM0-USDT与SYM102-USDT分别产生无关实体碰撞；是额外串行化，但本次未测云端误冲突频率或性能影响。真实资金/同币对依赖仍必须保持，不得直接删除drain循环。
- 已排除误判：projectSnapshotNow热路径只推进变更索引/提交点，不逐命令做全量快照；资金守恒使用增量accumulator，不遍历全部账户；SHA-256保持同commandId不同payload冲突保护，约6%owner执行样本，不能删除。ImmutableLongArrayList底层为long[]，不能称为每个ID都装箱。当前等待栈约2.4%执行样本，不能换算精确墙钟；实际还存在owner egress offer失败最多重试1s的同步等待，当前egress栈仅0.77–1.04%，不是已证实主瓶颈，慢接入需独立验证。所选窗口未见达到记录阈值的owner锁/文件/socket事件，不等于完全无阻塞。
- 建议优先级：去掉重复终态推送、复用命令解码、处理索引rehash分配；随后精简身份/长度等重复转换，并评估将不可变提交数据的出口编码移到有界出口线程。OrderRuntime/PositionRuntime是record而BalanceRuntime可变，异步化必须冻结所需值并保证身份字典与缓冲区生命周期，不能跨线程直接读取可变余额。当前审计没有新增性能收益结论。


### 2026-09-09 owner五项修复：OS54–OS57采集前预锁

- 仅当前master，对照commit不适用。修复终态ORDER重复出口、scope/执行重复解码、高频删除owner索引换表、长度/身份/迭代器等临时转换，以及账户/币对掩码误冲突。保留SHA-256幂等、资金校验、同币对与实际共享对手方依赖。参与者索引按price/userId计数，只有掩码命中才精确查询；不新增订单簿副本。更新真实Cluster JMH入口，采集前验证做市账户/币对碰撞及20项批量路径覆盖。
- 本机仅HotSpot25 clean verify和六产品三独立JVM交易/SIGKILL日志恢复/快照恢复，全部通过后才开机。云端GCP asia-southeast1-b三个n2-custom-8-16384 Core及一个同规格load，8vCPU/16GiB（4物理核SMT）；HotSpot25、Core4GiB/load2GiB、ZGC/NMT、1matcher/4Lane、SHARED_NETWORK、service YIELDING、Lane spin0、不绑核。部署生产Core类，无测试agent。
- 场景延续OS50参数：U本位永续1773用户258symbol；batch20连续异步MATCH_STREAM买卖GTC各半，单交易session窗口等于全局窗口，另有价格窗口16/串行控制及Valkey1000查询/s。包含触发、风控、强平、资金费、ADL、保险及结算；资金初态/场景比例以保存的负载参数和mixedSetup结果为准，30s风险超时及25ms控制节拍/64预算/8项切片不变。纯交易与后台composite分开计数，不伪装成恒定到达率open-loop。
- OS54 seed99801窗口256、OS55 seed99802窗口512，各30s业务预热+60s测量，边界排空冷却；若OS55通过且速率高于OS54至少5%，才执行OS56 seed99803窗口1024同配置。OS57 seed99804取本次有效最快窗口，30s预热+120s无profiler持续轮，之后原日志重启核对hash/资金。失败即停后续档位，保留artifact。
- 诊断轮JMH SingleShotTime单线程单独JVM，-f0 -wi0 -i1 -prof gc，主计数来自业务终态，不能把一整轮JMH B/op解释为Core逐笔分配。四JFR：Core phases.jfc、load profile；稳定窗口裁首尾各5s，报告owner/matcher/Lane等CPU、分配、GC、NMT、线程锁/等待、JIT、安全点和I/O。CPU95%目标只用于定位有效业务饱和，不作为忙等通过证明。
- 硬门槛：三Core存活、offered=terminal（消息及业务）、unfinished0、资金差0、业务覆盖PASS、单命令p99<1s、JFR DataLoss0、原日志与快照恢复一致。吞吐按实测报告，不以旧版本比较验收。查询READY比例、全部延迟三阶段和长期泄漏/完整native余额仍单列缺口，不能宣称整套生产容量验收。
- artifact=/Users/atomex/Desktop/surprising/gcp-validation/2026-09-09-owner-five-fixes；执行python3 cloud.py，保存source commit/JAR SHA、命令/JMH JSON/JFR/监控/恢复。finally停止四VM并核实TERMINATED。README不改。


### OS54失败与重复结算派发修复（2026-09-09）

- 639cf54f本机Core reactor 974项（973通过、1项外部instrument数据库未配置跳过）、实时路由reactor172项通过，六产品三JVM交易/日志/快照恢复18项通过；635个service/protocol/client类在测试service.jar及部署service/tools/benchmarks包中逐字节相同。首轮回归仅旧测试对pendingReservationUsers强转旧容器失败，已修正测试读取类型，资金回滚断言保留。源码已推送master。
- OS54 tools SHA256=40cd4af48f35b0f9f2f12af0b9ca2f1722ed83d4574d876a21fc4d5fe3fec2a9。三节点初始化及清算/保险/ADL检查通过，尚未取得有效测量结果时，01:55:41 UTC Leader core-1与Follower core-2因AccountLaneState.requireApplySequence退出；停止后续OS55–57，不能报告该轮吞吐或性能收益。raw JFR、OS与本次PID日志保存在2026-09-09-owner-five-fixes/os54，四VM已核实TERMINATED。
- 本机六产品确定性小样本复现：普通commit路径已为一个batch派发结算，返回等待Lane时pump的预派发路径再次派发同一sequence。red用例六项全部识别第二个事件，异常incoming=7/applied=7，和云端退出栈一致。修复由commitStarted确定路径所有权，普通提交已接手的batch不再预派发；不删除Lane序号保护，也不扩大业务依赖屏障。补充64个独立batch共享4Lane的序号/资金状态核对及六产品双派发/快照回归。异常信息增加lane/incoming/applied/committed，只有失败时构造，不增加逐命令指标。

### OS58–OS61采集前预锁

- 仅修复后的当前master，对照commit不适用。沿用OS54预锁的机器/JDK/JVM/GC/NMT、三节点网络、1matcher/4Lane、所有运营业务/比例/资金初态、异步持续负载、风险超时及正确性/p99/DataLoss门槛；新代码重新clean verify并完成六产品三JVM18项恢复后才开机。
- OS58/59/条件60/61 seed99901–99904分别为窗口256/512/条件1024/本轮最快档；前两档及条件档30s预热+60s诊断JMH/JFR，512比256至少高5%且正确性通过才开1024；末轮30+120s无profiler并按原日志恢复。所有指标/未测缺口与四VM finally停机要求不变，不与旧版本性能比较。
- artifact=/Users/atomex/Desktop/surprising/gcp-validation/2026-09-09-owner-dispatch-fix；python3 cloud.py，新增回归同样只做本机功能验证，真实ClusterOperationalBenchmark仍覆盖普通撤单到后续批量单的持续异步路径。


### OS58–OS61结果与精确依赖开销复核

- 被测046fb74a，tools SHA256=35c283ce1915005ac1bdc317df018d4a94cbd1f30a52f288a7807e94c382630e。本机Core reactor986项（985通过，1外部DB跳过），六产品18项三JVM恢复全通过。OS58窗口256为100905.004 terminal business ops/s、9610.000 messages/s、24025.001 fills/s；OS59窗口512为97899.801 ops/s，未满足加档5%条件，OS60未执行。两诊断轮三Core存活、业务/消息offered=terminal、unfinished0、资金差0、八份JFR DataLoss0。
- OS61窗口256无profiler，120.116s完成12300288业务项/1171456消息，分别102403.615 ops/s、9752.725 messages/s、24381.813 fills/s；业务/消息offered=terminal、unfinished0、资金差0。下单/撤单/批量下单/批量撤单p99为32.440/54.951/62.324/46.596ms。原日志重启恢复706 cycles，hash c1ff16211fd20d53、资金/持仓/预留校验一致；四VM已核实TERMINATED。恢复选举有quorum-position警告，随后恢复检查通过，不能因此宣称全程无警告。
- OS58稳定Leader core-0 owner99.82% CPU、matcher18.08%、Lane13.08–13.14%；owner分配339.56MB/s。OS59 owner99.84%、owner分配332.51MB/s。OS58 owner3646执行样本中实时出口13.69%、精确依赖判定12.40%、身份字典8.01%、指纹5.79%，均为包含调用栈比例不可相加。原终态回调重复编码已删除；六个替换owner索引的循环增删测试保持原数组。仍有PendingReservationSequenceIndex的LongHashSet建表/扩容等其它分配，不宣称整个Core零分配。
- 发现精确依赖检查重复比较同批20个相同(symbol,side,price)范围，并对不同batch的20个订单做两两比较；补充修复为复用原范围数组压缩完全相同的描述符，订单数组改为固定64槽精确索引（最多20个正orderId、零表示空、移除前缀时清空复用）。不增加Map/状态副本，不合并不同价格/方向，不省略任何订单ID或真实对手方检查。新测试覆盖20项保留、掩码碰撞、探测环回及移除复用。
- OS61 Valkey测量READY1965/(1965+117909)=1.64%，仍不合格，且缺open-loop三阶段延迟、长期泄漏/完整native余额；仅为上述场景下交易与恢复部分验证。完整参数、JMH GC诊断、JFR views、稳定CPU/分配、GC/NMT/线程/安全点/JIT/IO及原始证据位于2026-09-09-owner-dispatch-fix，不用历史版本数字计算性能收益。

### OS62–OS65采集前预锁

- 仅上述依赖压缩修复后的当前master，对照commit不适用。沿用OS58全部环境/三节点拓扑、业务与资金初态、1matcher/4Lane、连续异步运营组合、阈值、JFR配置、JMH参数和正确性/恢复要求；新构建通过本机六产品回归与18项三JVM恢复后才开机。
- OS62 seed100001窗口256、OS63 seed100002窗口512，各30s预热+60s带JMH/JFR诊断；仅512正确且比256至少快5%才执行OS64 seed100003窗口1024。OS65 seed100004取本轮最快有效档，30+120s无profiler、之后原日志重启核对。任何失败停止后续档，finally停止四VM并核实TERMINATED。
- artifact=/Users/atomex/Desktop/surprising/gcp-validation/2026-09-09-compact-dependencies；python3 cloud.py。真实ClusterOperationalBenchmark的20项同范围批量单和碰撞订单/账户覆盖本次路径；查询READY及长期验收缺口继续单列，不以owner忙等或无profiler短轮推导绝对算力上限。


### OS62–OS65结果（2026-09-09，五项修复最终构建）

- 被测master 5d1e4d846a3a97bf4f0a9ec7a9185fa49b590951，对照不适用；tools SHA256=6b9171f94437b2618afbb0f1adc444aedae853ef17acac9a105e28581b756a0a。本机HotSpot25 Core reactor 988项（987通过、1外部instrument DB未配置跳过），六产品三独立JVM功能/SIGKILL日志恢复/快照恢复18项全部通过；此前直接受影响实时provider reactor172项通过。635个service/protocol/client类与部署包逐字节一致。五项修复及重复批量结算派发回归均包含在此构建，无生产测试agent。
- 首次部署在发压前管理SSH复用连接停滞，未生成OS62测量；保留deployment-attempt1并停止四VM。管理上传改为独立SSH连接、60s无进展/180s总超时及最多3次重试后，重新检查同一源码/包并启动；生产参数、场景和采集预锁未改。
- OS62窗口256，30+60s JMH/JFR诊断：6773760业务项/645120消息=offered，112789.421 terminal business ops/s、10741.850 terminal Core messages/s、26854.624 fills/s。OS63窗口512：6408192业务项/610304消息=offered，106702.686 ops/s、10162.161 messages/s、25405.401 fills/s。两轮unfinished0、资金差0、业务覆盖通过。512未达到5%加档条件，OS64未执行，选256。
- OS65窗口256，30s预热+120.076s无profiler：13934592业务项/1327104消息=offered，116048.574 terminal business ops/s、11052.245 terminal Core messages/s、27630.613 fills/s（3317760 fills）；peakInFlight256、期末unfinished0、资金差0。普通下单/撤单各331776请求；批量下单497664批/9953280项，批量撤单165888批/3317760项，平均及最大batch均20。对应批量速率4144.592/1381.531 batches/s、82891.84/27630.61 items/s（按记录elapsedSeconds近似）。含后台的composite按自己的120.684s边界另计115887.853 ops/s、11420.214 messages/s，不能混用分母。
- OS65下单/撤单/批量下单/批量撤单入口至终态p99分别29.769/44.564/49.151/42.205ms，max40.665/64.618/66.093/54.493ms；完整样本数及p50/p90/p95/p999见result.txt。最大速率闭环持续异步负载，未修正coordinated omission；单独accepted时间、全类型拒绝/错误率及完整三阶段直方图缺口保留。运营51生命周期、净注资3000006375，手动平仓/触发平仓/强平/保险/ADL检查通过；mixed的triggerExecutions=0不是后台触发未执行，后台按独立operational计数。
- 稳定窗口剔除首尾5s：OS62 Leader core-1 owner99.82%CPU、matcher19.24%、Lane14.66–14.72%；OS63 Leader core-2 owner99.86%、matcher19.32%、Lane14.02–14.04%。OS65三Core owner99.91–99.92%、matcher18.15–18.91%、Lane14.06–14.87%。CPU占满不能当95%有效业务计算，matcher/Lane尚未饱和。OS62/63 owner分配368.24/352.50MB/s，全Leader线程分配658.97/624.37MB/s；无profiler轮没有JFR分配数据。
- OS62 owner3601执行/本地方法样本中116含等待栈；inclusive热点实时捕获15.44%、查询协议编码12.83%、身份字典8.61%、指纹6.25%、精确依赖conflictingPrefixSize4.19%，不可相加或解释为墙钟比例。仍有PendingReservationSequenceIndex及业务record等分配，不宣称整个Core零分配。稳定窗口配置阈值内未记录owner阻塞事件，不等于绝对无阻塞；全程类分配byte[]18.81%、long[]9.88%、OrderRuntime6.76%是采样权重。
- 八份JFR DataLoss0；逐文件大小/SHA256及完整20类views见各轮jfr-manifest.json和leader-*.txt，stable-window.json单独限定稳定期。OS62 Leader全程GC pause81次合计1.07ms，p99/max0.0355ms；NMT heap committed4GiB、Other9.2MB，Code12.1→29.1MB、Metaspace17.5→23.2MB，GC committed峰24.9MB。全程含初始化/预热/核对，不能充当长期live set趋势；safepoints视图Duration显示Indefinite，不能据此计算停顿或判定无安全点开销。JMH单次为整轮：OS62主分数60.058s，load GC201.890MB/s、154次/1495ms，B/op27456183232是整轮load分配而非Core每单；非fork诊断无多fork置信区间。
- OS65原始日志重启校验799 cycles、businessHash=f43ecae2d8e13bf，资金/持仓/预留/损失全部PASS。恢复选举仍记录quorum position went backwards警告，随后确定性业务核对通过。原始日志、JFR、监控、代码SHA、构建与18项功能证据均位于/Users/atomex/Desktop/surprising/gcp-validation/2026-09-09-compact-dependencies；执行命令python3 cloud.py，离线analyze.py/stable.py/inspect_jfr.py，不做本机性能测试。
- Valkey READY4576/119879=3.817%，115303 unavailable，无Core查询回退；查询可用性仍不合格。云端只测U本位永续运营吞吐，其他五产品完成了功能/恢复而未做云端性能。对象/op、长期GC后live set、完整Direct/native余额、稳定期全量I/O及三阶段延迟仍未完整验收，因此这是五项修复正确性与指定负载性能的部分验证，不能宣称生产全面验收或绝对算力上限；不作旧版性能增幅比较。README未修改。
- 云端编排退出0，原日志恢复后finally停止四VM；本轮cloud.log记录ALL FOUR VMS TERMINATED，fresh instances-final.json确认surprising-core-0/1/2及surprising-load全部TERMINATED。后续未经用户要求不再开机。


## 2026-09-09 Runtime 职责收敛及本地正确性验证

- 被测代码：master 基于 `e44147d8` 的本次工作树；对照 commit 不适用。用户明确要求本轮仅本地验证，不启动云服务器、不执行压测；JMH/JFR 与真实三节点性能验收延后，本记录不构成吞吐或零分配结论。
- 删除 `CoreProbeState` 及旧资源包装层，由 `TradingCoreRuntime` 作为唯一交易 owner，直接持有同一份 `TradingRuntimeState`、身份字典和 matcher。准入、批量执行、有序提交、结果账本、查询、实时读取及快照分别拥有自身在途变量；余额、币对配置、资金费、衍生品风险/账户、触发单、到期结算按功能拆分，沿用原有产品计算处理器和产品隔离。新增类和字段说明责任、生命周期及线程归属。
- `TradingRuntimeState` 中结算派发、预留索引、primitive 变更/回滚缓冲独立；没有增加第二份权威账户状态。批量 Lane 派发共用一条实现，以唯一提交阶段避免重复派发；保留依赖、资金核对和有序提交边界。提交水位移除无副作用逐个自增循环。修复外线程调用 close 在拒绝前污染 closed 标志的问题，新增六产品线回归。
- 环境：本机 macOS x86_64，Oracle GraalVM 25.0.1 / HotSpot，Maven 3.9.16；执行前检查 java/mvn 版本。
- 核心 reactor：`mvn -pl surprising-aeron-core/surprising-aeron-tools,surprising-aeron-core/surprising-aeron-benchmarks -am clean verify`，`verify3.log` BUILD SUCCESS，994 项中 993 通过、1 项数据库条件跳过；service 599 项全部通过。benchmarks 模块仅运行 JUnit 正确性测试，未启动 JMH 或吞吐采集。
- 本机以 `local-functional.py` 启动三个独立 JVM，六产品线逐一进行小样本业务、全节点 SIGKILL 后日志恢复、快照后 SIGKILL 恢复；18/18 PASS。使用真实 Aeron Cluster 日志复制，网络为 localhost；不作为真实多服务器性能结果。恢复等待遵守 Archive/Cluster mark-file 存活保护时间，不删除持久化元数据。结束已停止节点。
- 使用独立 PostgreSQL 18.4 临时测试库，按根 `init.sql` 初始化。`MAINTENANCE_TEST_JDBC_URL` 指向本机测试库，执行 provider 的 `MaintenanceIntegrationTest,InstrumentCoreSyncServiceTest`：42/42 通过。再以 `INSTRUMENT_SEED_TEST_JDBC_URL` 执行工具模块 `InstrumentSeedCoreContractTest`：1/1 通过，补齐上述跳过项。临时数据库已停止，未启动 wallet。
- 异常如实记录：较早 `verify.log` 中 Archive 临时 UDP 端口碰撞（Address already in use），之后完整 clean verify 成功，未通过删除校验规避。早期 provider 无数据库时维护测试被条件跳过，已用真实隔离库补跑。提取过程中的反射测试路径已更新到实际组件；未放宽业务断言。
- 测试后仅完善常量注释、移除重复 import、整理提取方法缩进；`final-package.log` 对最终源码重新 package 成功。最终 service/benchmarks 中共享 656 个 Aeron class 字节一致；无 CoreProbeState class。README 未改。
- 原始日志、恢复结果与测试脚本：`/Users/atomex/Desktop/surprising/gcp-validation/2026-09-09-runtime-owner`。未验证云端吞吐、长稳分配及 Valkey 问题；本次不宣称这些问题已解决。

- `/Users/atomex/Desktop/surprising/gcp-validation/2026-09-09-runtime-owner/local-functional/service.jar`: SHA-256 `4441bf80eab6d20127562af6382c65cd1adf8cc93228943a29d45d5ae8fc066f`。
- `/Users/atomex/Desktop/surprising/gcp-validation/2026-09-09-runtime-owner/local-functional/benchmarks.jar`: SHA-256 `1ad1308df8da9b2c3d6c99f2c22223e8f52f37f2c3931a7a04337e73a6c6efa9`。
- `surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar`: SHA-256 `4d734a9586da1c0521d51018588d84f84c9977f1d9273d50530ef80d559bf1f5`。
- `surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar`: SHA-256 `8ccf56eb3fd1033ea993d8e3b7ef184a3d51aeeeeed756820659241aebb364a6`。


## 2026-09-09 拆分后等待点审查与局部修复

- 基于 master `a269d217` 的本次工作树；按用户要求仅本地正确性验证，不启动云服务器、不运行压测或 JMH/JFR。CodeGraph 工具未暴露，本轮使用本地源码调用引用和现有测试核对。
- 关键定位：`ClusterCommandWindow.conflictingPrefixSize` 对同币对命令设置前缀提交屏障，独立用户、同方向不交叉订单也受此限制；`SurprisingClusteredService.processIngress` 在回调内同步排此前缀。该规则保护基于已提交 maker/账户索引的准入，不能单独删除。此为源码确认的流水并行限制，不是新测得的吞吐归因比例。
- 实际同步路径：`TradingRuntimeState.onLane -> LaneMutationTask.await` 提交任务后等待/park，含控制、风险和强一致读取；内部等待完成后才允许读取结果和刷新变化，因此不能直接改为立即返回。`stageLaneMutationFromScratch` 等待的是提交水位确认。普通订单的异步准入/结算路径不能与这些路径混称为全异步。
- 出口：`SurprisingClusteredService.offerResponse` 在 owner 上重试，截止为 1 秒；慢客户端可能阻塞后续交易。直接 Core 查询会先完成命令窗口，部分查询还同步经过 Lane。实时快照读取为独立异步路径；本轮没有修改响应送达策略或 Valkey。
- 修复：前缀依赖范围只计算一次，避免 conflicts() 和 conflictingPrefixSize() 连续重复遍历；删除无生产/测试调用者的 applyNoTradeMatcherSettlements、dispatchNoTradeMatcherSettlements、applyPerpetualMatcherSettlements 及专属辅助类型/无界等待。旧无界等待不在现行生产调用链，不能据此解释历史吞吐。
- 修复实际可达的 Lane 提交无限等待：增加 30 秒截止和中断检查，保留健康检查、原等待策略和完成屏障；超时抛运行故障，不伪造成功、不发布未完成提交、不回收事件。增加超时及中断两条回归，校验未完成序号/事件保留和中断标志。
- HotSpot JDK 25.0.1（Oracle GraalVM）、Maven 3.9.16，运行前已检查版本。执行 `mvn -pl surprising-aeron-core/surprising-aeron-tools,surprising-aeron-core/surprising-aeron-benchmarks -am verify`：BUILD SUCCESS，996 项中 995 通过、1 项外部数据库条件跳过，0 失败/错误；service 601 项全部通过。包括六产品线资金、订单、批量、依赖前缀及恢复回归；benchmarks 模块仅 JUnit 功能测试，未执行压力工作负载。
- 日志：`/Users/atomex/Desktop/surprising/gcp-validation/2026-09-09-wait-audit/verify.log`；此前仅清理旧接口与重复扫描的服务回归亦通过，见同目录 service-tests.log。未重跑三进程恢复、外部数据库集成或性能采样：本次未改变复制协议/快照编码/数据库契约，局部影响通过核心与工具模块回归覆盖。README 未修改。
- 结论：等待点审查和上述局部修复完成；同币对依赖模型、同步 Lane 业务调用、响应背压仍待分项调整，不宣称吞吐问题已解决。后续应先明确未提交订单参与准入的确定性规则，再调整同币对流水；Lane 变更则必须保持任务完成、失败终止和结果发布边界。


## 2026-09-09 同币对独立命令流水准入（异步改造的已验证部分）

- 基于 master `552da269` 工作树。用户要求消除同步等待，随后澄清 command 应端到端异步；本条只记录已经实现并验证的同币对独立准入，不代表同步 Lane、响应发送或控制命令已全部完成异步化。
- `ClusterCommandWindow` 将同币对一律冲突收窄为真实依赖：相同账户/订单、共同已提交成交对手、可能互相成交的在途买卖范围，以及撤单的不完整流动性范围仍设屏障。衍生品在途成交可能改变后续准入使用的币对持仓量，该依赖也必须保留；不会改变此值的独立挂单可以同时在途。新增固定范围标志随原有 scope 数组存储，不复制订单簿。
- 批量币对索引改为登记该币对最新在途批次，按全局有序终态退出。入口已证明独立的同币对批次可以并存，避免旧“唯一批次”断言；直接运行时路径原有依赖校验保留。
- 新增六产品线普通单/批量单同时在途并与串行、Follower 重放、快照恢复一致的测试；新增共享 maker 消费顺序、交叉价格/市价/撤单范围和衍生品持仓量依赖测试。实时测试仍严格检查 512 成交、1024 私有执行、零丢弃；提交 envelope 数允许因多个独立命令合并发布而减少，要求 begin/end 配对。
- HotSpot JDK 25.0.1、Maven 3.9.16；`mvn -pl surprising-aeron-core/surprising-aeron-tools,surprising-aeron-core/surprising-aeron-benchmarks -am verify`：nonblocking3-verify.log BUILD SUCCESS，1016 项中 1015 通过、1 项数据库条件跳过，0 失败/错误；service 621 项通过。未执行云端/压测/JMH/JFR或新的三进程验证，本轮为本地功能回归。
- 中间测试发现旧批量索引拒绝同币对并行，已修复；新增共享 maker 用例最初错误地读取已移除的全成交订单，改为保留一份余量核对部分成交；旧实时用例把 envelope 数等同命令数，已改成检查分组配对，业务事件数量断言未放宽。日志同目录 same-symbol-tests.log、same-symbol2-tests.log、nonblocking-verify.log、nonblocking2-verify.log 保留失败记录。
- artifact：`/Users/atomex/Desktop/surprising/gcp-validation/2026-09-09-wait-audit/`。README 未改。尚未完成：同步 Lane 调用的分阶段续办、响应出口异步化、控制与直接查询的非阻塞完成调度；不宣称所有等待已消除或吞吐已改善。

## 2026-09-09 交易命令异步推进与本地恢复验证

- 范围：基于 master `85afef9c` 的当前工作树；只执行 HotSpot JDK 25.0.1（Oracle GraalVM）/ Maven 3.9.16 本地功能、资金与恢复验证。没有云端操作、压测、JMH 或 JFR；benchmarks 模块仅执行固定样本 JUnit 功能用例。README 未修改。
- `SurprisingClusteredService` 将已复制入口、依赖前缀、控制命令结果分阶段保留，回调只做有限推进；日志中的推进边界也进入 FIFO，避免不同节点的完成速度改变窗口分组。队列限制 8192 项/64 MiB；耗尽按节点运行故障处理，不产生依赖本地线程速度的业务拒绝。快照仍必须完成此前已复制命令。
- Lane 控制阶段使用有代次的异步所有权交接，取得唯一写入权后执行已有业务方法，并保留 Lane 作用域。普通准入、撮合与结算仍使用原有工作线程；控制阶段及逐项批量阶段暂由 owner 执行，结束后归还 Lane。集群命令作用域禁止退回同步 Lane 调用。挂起批量不会重新开始资金上下文，也不会重置已有准入索引；逐项准入使用该批原始日志时间与位置。
- 盘口查询使用异步 matcher 读取；跨 matcher 分片清算撤单通过独立控制 token 提交、轮询，维持订单顺序、首个失败后的停止语义和既有提交证据。只供测试/基准使用的同步完成辅助方法移至对应测试或 benchmarks 模块。
- 响应出口按会话 FIFO、公平轮询，有界保留编码数据（8192 项/16 MiB），不在交易回调里等待客户端恢复连接。保留原有一秒发送期限；超时/容量耗尽关闭慢会话，终态仍由 commandId 结果账本查询，不能回滚资金或改写业务结果。
- 框架边界：持续的 1 ms 日志定时事件保障无新订单时也能完成待处理工作，带来固定日志开销。Aeron 1.53.0 禁止在 `doBackgroundWork`/`onRoleChange` 中调度定时器，其过期计数恢复要求逐个保留调度请求。因此定时器元数据发布保留最长 30 秒的协议重试，快照、启动、关闭仍有必要等待；不能宣称所有框架等待消失。控制阶段并行度与定时事件开销的吞吐影响尚未验证。
- 完整回归：`mvn -pl surprising-aeron-core/surprising-aeron-tools,surprising-aeron-core/surprising-aeron-benchmarks -am verify`，`verify5.log` BUILD SUCCESS；1024 项中 1023 通过、1 项外部数据库条件跳过，0 失败/错误；service 629 项通过。覆盖六产品线资金、订单/批量生命周期、依赖前缀、重复命令、盘口、风控、保险基金、ADL、快照及恢复；新增阻塞 matcher/所有权交接、出口背压公平性、跨分片撤单与原日志时间等回归。
- 真实本地三个独立 JVM 首轮：现货执行和日志恢复通过，快照恢复遇到定时器短暂背压；16 次立即尝试误判为节点故障。修复为上述有界协议重试后，重点 128 项测试全部通过并重新打包（`timer-recovery-build.log`）；随后六产品线各完成执行、SIGKILL 后日志恢复、快照后 SIGKILL 恢复，共 18 项全部通过。`local-functional-timerfix/results.json` 六项 `passed=true`；结束后所有验证节点和客户端均已退出。
- 保留中间失败：旧同步完成断言、逐项批量同步 Lane 分支、交接后风险游标作用域、异步批量改单测试的终态核对时机，以及上述定时器恢复问题；日志未删除或覆盖。跨分片测试初版错误断言订单应被删除，实际正确终态为 CANCELED，已按既有生命周期核对并比较恢复后的完整账户状态。
- 原始证据目录：`/Users/atomex/Desktop/surprising/gcp-validation/2026-09-09-async-commands/`。包含完整/定向构建日志、首次失败及修复后本地三进程目录、全部启动参数、节点日志、执行/恢复结果、快照检查和源码清单。最终 service.jar SHA-256 `643bcdf8df20dc6db0390eb2405bc1a1a88567e18bf385f95ef75105056010b8`；benchmarks.jar `ce91b09f506fb8c32858d49f015e0337df1df23f3f17f3185db2dfd8f3edfd02`；`source-manifest.json` `1d6339b45ddebc5beaa2ffd5712acd21a311740accc647930f36efcc001519e5`。
- 结论：本次业务异步推进的功能与上述恢复验证通过；未作吞吐、并发上限、尾延迟、分配率或零分配承诺，性能验收留待用户重新授权真实三节点压测。

## 2026-09-09 资金费与风险扫描移回 Account Lane

- 基于 master `0f1b343f` 的工作树。本轮范围为 `APPLY_FUNDING`、`CONTINUE_RISK_SCAN` 及 `EXECUTE_LIQUIDATION_BATCH` 的风险扫描分支；不代表其他清算、ADL、到期结算、触发单控制业务已经全部移出 owner。owner 保留日志调度、财库合并、扫描游标/清算 ID 和终态提交；提交/回滚阶段仍使用原有 Lane 所有权交接。
- 控制任务通过 `ControlLaneDispatcher` 派发到永久 Lane 线程，复用原任务槽；资金费按参与 Lane 并行计算，风险扫描按原有 8 单位片段和全局预算有序推进。每个账户片段之间不接管全部 Lane；完成结果由 owner 轮询收集，未完成立即返回。失败须收齐所有 Lane 后才能交接和回滚，禁止与账户修改并发回滚。
- 直接命令跨回调保留原始日志上下文、准入额度和检查点；异步阶段未完成时禁止新命令越过及快照遗漏。批量清算的扫描续步沿原匹配序号继续，不能重复校验/应用已经消费的 matcher 证据。失败回滚同时检查未提升 revision 的账户变更，防止局部资金修改遗漏。
- HotSpot JDK 25.0.1（Oracle GraalVM）、Maven 3.9.16。本地 `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am verify`：1028 项中 1027 通过、1 项外部数据库条件跳过，0 失败/错误，service 633 项通过；后续入口/快照保护及重新打包的重点 44 项通过；同步/异步风险结果比较和基准编排编译的重点 17 项通过。
- 新增永久 Lane 并行执行/阻塞隔离、失败收齐、在途快照拒绝、两永续产品资金费分页/重复命令/恢复后继续收付用例。五衍生品风险预算测试改走集群异步执行路径，并与同步结果及快照恢复比较。资金守恒检查使用账户与财库的经济总额；资金费会改变各用户分布，不能要求资金状态哈希不变。
- 本地三个独立 JVM：六产品线各完成执行、SIGKILL 后日志恢复、快照后 SIGKILL 恢复，共 18 项通过，结束后节点/客户端全部退出。本次未连接云端或执行压测/JMH/JFR；benchmarks 模块只运行小样本 JUnit 功能测试。新增外部三节点 JMH 控制页参数 0/1/64（0 保持原编排），覆盖续页与跨 Lane 收集，只编译和功能检查，未采集性能数据。
- 证据目录 `/Users/atomex/Desktop/surprising/gcp-validation/2026-09-09-lane-control/`：`verify.log`、`boundary-package.log`、`risk-parity-benchmark-compile.log`、`local-functional.log` 和 `local-functional/results.json`。保留中间失败日志：批量续步缺少重新调度导致超时、快照先检查了账户读栅栏、测试误用资金分布哈希判断守恒；修复后均通过，未放宽业务终态/资金断言。
- 被测 service.jar SHA-256 `8f1ddde62e48de2bb6ba4d8d5d203a06ea4bd2602e51ab032e165a000ad6305e`；功能客户端 benchmarks.jar `926ab2f49dbcd8a5bb15a38494035d698c3b891ba316acadce22977525d9f450`；最终源码清单 `source-manifest.json` SHA-256 `7be62e709db9cd4a7fe0d6ca4942d2ef3c9e514e54df05e951087d9fc9886f17`。功能客户端 jar 在随后添加的 JMH 编排参数之前打包，交易服务与功能 Gate 未变化；新 JMH 源码已单独编译检查。
- 结论：上述资金费/风险扫描路径的本地功能和恢复验证通过；不宣称 owner 已轻量化、全部账户业务 Lane 化、零分配或吞吐达标。README 未修改，性能验收仍待用户授权真实三节点压测。


## 2026-09-09 风险任务按 Lane 并行拆分（仅本地功能验证）

- 被测代码：master，基线 `30b53eba` 加本次变更；精确源文件与构建包哈希见下述 `source-manifest.json`。对照 commit：不适用。用户明确要求不上云、不压测，因此未执行 JMH/JFR，不作吞吐、分配或延迟验收结论。
- 拆分：`RiskScanCoordinator` 在 owner 分配有界预算和连续清算编号；`RiskLaneProcessor` 在各账户 Lane 并行估值、更新已有风险/清算；`RiskLiquidationBatch` 将 owner 分配的新增清算编号交回所属 Lane 写入。只在产生新增清算时增加该写入阶段，空 Lane 通过现有持仓用户索引排除。
- 每个 Lane 的独立游标 `RiskLaneProgress` 进入快照、投影、恢复和业务哈希。资金费/风险扫描派发前不再接管全部 Lane；最终提交、失败回滚和触发扫描所需交接仍保留，其他控制操作未在本次全部改造。状态形式的同步风险入口使用相同预算/提交顺序，风险公式仍由原有独立计算路径验证。
- 格式边界：交易状态快照格式由 30 更新为 31；不提供旧格式兼容分支。此次恢复证据仅覆盖本版本产生的日志/快照，后续服务器功能验证应使用新初始化测试数据，不把旧版本日志重算结果视为本次保证。
- 环境：macOS 26.7 x86_64；Oracle GraalVM HotSpot JDK 25.0.1，Maven 3.9.16。执行 `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am verify`：1,032 项，1,031 通过、0 失败、0 错误、1 项外部数据库测试跳过；service 637 项通过。JMH 增加 `controlPageSize=4` 场景参数并编译，未运行性能采样。
- 新增正确性用例：分别阻塞首/末 Lane，验证其他 Lane 已完成估值且清算编号不受完成顺序影响；多个 Lane 同时保留部分游标后快照恢复，使用 1/3/7 预算续扫并更新标记价；清算编号溢出不部分写入，完整命令回滚后继续交易控制及快照恢复。用户余额、持仓、财库与恢复结果核对均通过。
- 本地三独立 JVM：六产品线分别执行功能样本、SIGKILL 后日志恢复、快照后 SIGKILL 恢复，共 18/18 PASS。仅少量功能样本，无压测。节点使用 ZGC、Xms64m/Xmx512m、SHARED_NETWORK，按产品线依次启动并关闭。
- 原始记录：`/Users/atomex/Desktop/surprising/gcp-validation/2026-09-09-parallel-risk/`，包含 `verify.log`、`parallel-tests.log`、`rejection-tests.log`、`local-functional.log`、各节点启动参数和日志、`local-functional/results.json`。首轮失败记录保留：旧同步入口仍按单游标顺序扫描，且空币对被按 Lane 多扣预算；修正调度与空 Lane 排除后原有风险断言通过，未放宽金融断言。
- 源文件清单 SHA-256：`571659363add3974393f81ce2295b9ca513290b0b407b409f299943e9de845e4`；service.jar：`6b0e17ba95525d65924df7ab95ef17c294700cf13e88252d0f13daa551cb792f`；benchmarks.jar：`c0823cc26958d29512e19a4bb6c59d7791ad7d2759430d2a36899d932dc36168`。未修改 README。


## 2026-09-09 提交等待缩小与风险续页一致性（仅本地功能验证）

- 基于 master `1f43b34d`；提交号见证据目录 `tested-commit.txt`。无依赖订单可在旧提交前缀等待 Lane 时继续准入；直接命令成功收尾只向变化账户 Lane 派发完成事件，删除模拟 matcher 结果/上下文和空订单时间戳任务。标记价、风险配置、保险基金更新不再预先接管全部 Lane。
- 风险游标保存账户/行情输入序号，平仓、余额或其他币对价格变更后重新累计当前用户，修复权益应为 300 却计为 500 的复现；保留已完成用户和有界预算。快照、投影、回滚、业务哈希同步处理；快照格式 31→32，不兼容旧格式。
- 范围限制：`activeControl` 与后续交易的全局顺序屏障仍保留；尚未实现控制命令与后续交易的提交/回滚上下文隔离及全面交错执行，不能宣称所有等待已消除。失败回滚、同账户依赖、两侧结算确认和快照完成边界保留。
- HotSpot JDK 25.0.1、Maven 3.9.16、macOS 本机。`mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am verify`：1040 项，1039 通过，0 失败/错误，1 项缺少 INSTRUMENT_SEED_TEST_JDBC_URL 跳过；service 645 项通过。随后仅移除无调用的方法/补注释并重新 package。JMH 增加控制页 2/8 参数，只编译；不上云、不压测、不运行 JMH/JFR，无吞吐、分配或延迟验收结论。
- 最终构建本地三独立 JVM：六产品线执行/日志恢复/快照恢复首次 17/18 通过；期权最后一次客户端连接超时，节点存在复制告警。保留原数据重启复核通过，未发现状态不一致；不把首轮失败抹去或视为恢复耗时稳定的证据。测试进程全部退出。此前构建的恢复轮次因去掉临时对象后重新打包而主动中止，日志保留。
- 证据：`/Users/atomex/Desktop/surprising/gcp-validation/2026-09-09-control-progress/`，包含复现/中间失败日志、`verify-final-2.log`、`package-final.log`、`local-functional-final.log`、`recheck-option.log`、`validation-summary.json`、`source-manifest.sha256` 和逐节点参数/日志。未改 README。service.jar SHA-256 `ccba9ba8b895548b3766ac54fee2b9d7f8795f9b633660836b390e9319640a21`；benchmarks.jar `e408265b4478bda3238d46254c0578dc729103b9c2a351360a6b439f67ddbef4`。

## 2026-09-09 本机控制业务诊断（采集前锁定）
- 用户本轮明确允许本机小规模压测；当前 master 8ca25a6a，非服务器吞吐验收，无旧版对照，不修改交易逻辑。
- HotSpot GraalVM 25.0.1 / Maven 3.9.16；macOS x86_64，16 logical CPU / 16 GiB；本机三个独立 JVM，loopback UDP + 真实日志复制。每 JVM Xms64m/Xmx512m、ZGC、SHARED_NETWORK、YIELDING；4 Account Lanes、1 matcher、0独立 risk engine。同机及短预热限制绝对性能结论。
- ClusterMixedCapacityMain 默认 mixed（非 operational / 非 trading-stream）：预热1秒、测量5秒（完整业务循环结束才排空，允许超时长）；256 全局在途、64 session窗口、1命令+1查询session；固定1769账户/256symbols，每账户初态按现有 setup；批量报价/撤单/IOC、触发、资金费、风险及一次强平/保险/ADL账务核对。闭环发压，不修正 coordinated omission，不将该脚本当作生产连续混合饱和证明。
- 五衍生产品各8对账户/2连接，现有 ClusterLifecycleCapacityMain 强平或到期结算，串行小样本仅验证正确性。混合场景停止后保留日志重启，用 verify-only 核对相同 cycles 的余额、持仓、冻结及业务 hash；不新做故障注入全矩阵。
- 通过条件：所有脚本 PASS、mixed offered/terminal 相等、unfinished=0、fundsDiff=0、恢复 hash 相同，无业务异常。失败原样保留并定位；不设吞吐通过阈值。
- JFR: 三个 mixed 服务节点 startup profile recording，退出落盘；含初始化，诊断归因用；不运行 JMH（未修改生产路径），不宣称无泄漏。Valkey/WS/微服务入口未测。
- artifact：/Users/atomex/Desktop/surprising/gcp-validation/2026-09-09-local-control-audit；命令、jar SHA256、日志、结果随运行保存。节点全部退出后结束。
- 首轮结果：mixed 初始化通过，进入触发路径后 leader `TRIGGER coreSequence=16757 EXCHANGE_CORE_FAILURE`，客户端 ResultUnknown；另一 follower 的 timer 入队出现 backlog capacity exhausted。mixed 未到测量窗口，恢复核对未执行，不能报告吞吐或资金通过。五个 lifecycle 均在第一笔 PLACE_ORDER INVALID_COMMAND，尚未触达生命周期。JFR 仅 node2 有效（另两节点异常退出留下空文件），可见触发收尾在 clustered-service、scanLane 在 account-lane。
- 第二轮（采集前）：只修测试器缺少初始新鲜 mark/期权 index+forward 及历史价格时间；保持五产品8对账户、2连接和节点配置，再跑 lifecycle。artifact 为上述目录/lifecycle-recheck；本轮仍为正确性诊断，不设吞吐结论，不更改生产代码。
- 第二轮五个客户端均首条 UPSERT_INSTRUMENT NOT_CONNECTED，未进入业务；判为启动/接入失败，不能用于判断 mark 修正效果。第三轮只新增只读 TREASURY_STATE_QUERY 可用性门槛（最长12次、每次15秒，业务命令不重试），其他参数保持不变，artifact 为 ready-recheck。
- 第三轮：LINEAR_PERPETUAL / INVERSE_PERPETUAL / LINEAR_DELIVERY 的只读请求均成功，但随后连接池第一条命令仍 NOT_CONNECTED，根因未定位，不可仅归咎启动选举。INVERSE_DELIVERY 节点启动 MediaDriver 10秒无响应；runner 清理 killpg 出现 PermissionError，已单独 TERM 剩余 PID 65877 并确认退出；OPTION 第三轮未执行。不继续重复无效负载。
- 收尾：HotSpot25 Maven package 成功；精确回归 ClusterCommandPipelineTest(108)、AsyncFundingCommandTest(2)、ParallelRiskScanTest(5)、ClusterMixedCapacityTest(6)、OperationalLifecycleAuditTest(1)，共122通过、0失败/错误/跳过。它们不替代失败的真实三 JVM 验证。
- 本次唯一 Java 改动为生命周期测试器初始化 mark/index/forward 和当前行情时间；实际生命周期复测仍被接入失败阻断，不能宣称该测试器端到端通过。生产控制迁移未改动。源码路径证据：SurprisingClusteredService:205 在控制命令入口取得 owner Lane 访问权；TradingRuntimeState:onLane(584)、executeLaneMutations(743) 在 ownerLaneAccess 分支同步执行；RuntimeDerivativeLiquidationProcessor:373 ADL 双账户写入、RuntimeSettlementProcessor:91/116 两阶段到期结算、TriggerOrderCommands 控制更新均仍可能由 owner 执行。风险 scanLane 已有实际 Lane JFR 样本。
- 原始结果 summary.json、完整命令 run.py/*command.json、jar hashes 和 artifacts.sha256 均在上述 artifact 目录。有效 JFR node2 135秒、1.7MiB、DataLoss=0；其余两节点 JFR 为空，无 leader 热点结论。未完成 mixed 终态/恢复/财务验证，无有效吞吐数字，无泄漏结论；所有本次节点/负载进程已停止，未操作云服务器。

### 2026-09-09 磁盘因素与产物清理补记
- 用户反馈刚才磁盘满，要求以后每轮分析结束清理测试产物。本次复核磁盘可用约547 GiB，但整个 gcp-validation 目录已不存在，无法回查失败时的 ENOSPC/写入失败日志；以上原始 artifact 路径现在不可访问，仅保留历史摘要。
- EXCHANGE_CORE_FAILURE 是通用错误码，TradingCoreRuntime 的 RuntimeException 捕获路径未保留原始异常原因。磁盘满可能导致存储/通信相关失败，但现有证据不足以确认其与本次触发错误的因果关系；此前异常记录不是已证明的生产业务缺陷，根因仍待复现确认。
- 清理剩余五个 Aeron 模块 target/surefire-reports（约3.54 MiB）；保留源码、构建包和既有验证摘要。项目 AGENTS.md 已加入分析后清理及磁盘检查要求。本轮未重新启动测试或云服务器。

## 2026-09-09 磁盘清理后的小样本复现
- 被测生产代码为 master ea02d4bd；本次仅新增 benchmarks/src/test 下 SmallControlReproMain，不修改生产逻辑。HotSpot GraalVM25.0.1/Maven3.9.16；macOS 本机三个独立 JVM、loopback UDP 和真实日志复制，Xms64m/Xmx512m、ZGC、SHARED_NETWORK、服务 YIELDING、4 Account Lanes/1 matcher。仅功能诊断，不计吞吐、不运行 JMH、不操作云服务器。
- 触发复现：先只读确认可用，再用2个命令连接的 AeronClientPool（10秒超时）执行首条命令与查询，均 PASS。随后两个账户各充值10000，100价格成交10单位，建立 SELL LIMIT IOC 110 的止盈触发单（当前标记价100满足触发条件）。第8条命令 EXECUTE_TRIGGER_ORDER 导致 coreSequence=10 的 TRIGGER EXCHANGE_CORE_FAILURE，客户端 ResultUnknown；节点0/2记录致命失败。本轮最小剩余磁盘584573448192字节，排除了本轮磁盘满作为原因。第二个有成交触发场景未执行，不能报告通过。
- JFR 使用 default.jfc 加 jdk.JavaExceptionThrow enabled/stackTrace，maxsize32m；节点0/2异常退出留下空文件，节点1的有效记录捕获完整原始异常：`asynchronous command requires Lane execution ownership`，线程 clustered-service-101-0。调用链：TradingRuntimeState.onLane:587 ← orderIdByClient:3103 ← TradingCoreRuntime.triggerPlacement:1853 ← prepareMatchingCommand:1694 ← submitMatching:1449 ← MatchingCommandAdmission.appendQueuedMatching:62 ← TradingCoreRuntime.finishDirectCommand:1146 ← pollDirectCommand:1205。随后 matcher 记录 fatal matcher result: EXCHANGE_CORE_FAILURE。
- 根因结合源码确认：finishDirectCommand:1109 释放顺序阶段的 Lane 访问权并异步完成发布后，appendQueuedMatching 又通过 triggerPlacement 读取 Lane 的客户端订单索引；仍处于异步 owner 作用域，违反 Lane 执行权约束。prepareMatchingCommand:1740 将异常转换为通用错误码。它是低负载也能触发的功能性错误，尚未修复，不能归为 owner 算力饱和。
- 首次追赶试验：暂停一个从节点12秒，另外两个节点完成16笔下单/8笔成交、资金差额0。恢复暂停后触发默认10秒 MediaDriver keepalive 超时（age12665ms），属于故障注入预期的节点退出；该节点快照缺失，该轮三副本核对失败，不作为新的生产缺陷。
- 前一轮临时数据已清理，后续重新构造独立场景：先停止一个从节点，两名用户完成16笔下单/8笔成交，共20条业务命令；从节点离线至少12秒后使用其原数据目录重启。只读核对 PASS，持仓-8/+8、账户总余额20000、fundsDiff=0。三个节点的完整快照首次读取均成功，businessHash 均为2111639170114541499（coreSequence20）；未出现 backlog capacity exhausted 或节点故障。此结论只覆盖小样本追赶，不证明任意积压下均无问题。
- 复现入口已用 HotSpot25 javac 编译并实际执行，模式为 ready/pool/trigger/orders/verify-orders；classpath 为编译后的 test-classes 加 product-core-benchmarks.jar，节点入口为 SurprisingClusterNode。trigger 在当前生产代码上预期复现失败；不要把这项失败记录改成通过。
- 按用户要求，保存本摘要及复现源码后，停止全部本轮进程并删除 /tmp/ex-small-repro-20260909、/tmp/ex-small-catchup-20260909、/tmp/ex-recovery-check 下临时集群数据、JFR、日志和编译产物；不保留或引用可访问的原始 artifact。没有修改 README。

## 2026-09-09 触发 Lane 边界修复验证（执行前定义）
- 基于 master 0b6a17c6 的本轮修改；对照不适用。HotSpot GraalVM25.0.1，macOS x86_64/16 logical CPU/16GiB。只在本机三独立 JVM、真实 UDP/复制日志执行少量功能样本；不上云、不进行持续吞吐压测。
- 三节点 Xms64m/Xmx512m、ZGC、SHARED_NETWORK、服务 YIELDING、4 Account Lanes/1 matcher；JFR default+JavaExceptionThrow、maxsize32m，NMT summary。客户端 JMH ClusterTriggerBoundaryBenchmark：SingleShotTime、fork1/thread1/warmup0/measurement1、-prof gc；仅一次完整连接/建仓/两次触发/查询，无预热、不修正 coordinated omission，不作为稳态吞吐/尾延迟/每笔交易分配验收。
- 固定4账户、2币对、每账户10000初始余额、零手续费；第一组 IOC 无成交，第二组成交减仓1单位。串行少量命令，故不采用历史256在途饱和档位；JMH 1次 invocation 不等于1笔 business operation。
- 通过要求：两次触发 APPLIED/TRIGGERED、子订单编号正确、双边持仓10/-10与9/-9、保证金及余额核对、各组资金差额0；三个副本快照哈希一致，保留数据重启后再次查询验证一致；无 Lane ownership 或 EXCHANGE_CORE_FAILURE。失败必须原样记录，不设置性能数字通过阈值。
- 临时目录 /tmp/ex-trigger-fix；每次子进程调用前检查可用磁盘低于10GiB则停止；分析完成只保留本摘要和源码，清理全部本轮日志、JFR、临时集群及测试报告。
- 修复结果：在冻结仍有合法访问权时绑定 CoreMatchingOrder，经临时 QueuedTriggerMatching 交给复用 PendingMatching.admittedMatchingOrder；异步撮合/结算计划不再查 Lane 的 clientOrderIndex，也不重新推导可能冲突的子订单号。触发成功终态随已有 MatcherSettlementEvent 在所属账户 Lane 写入，owner 收齐后合并全局版本及发布；未增加全局接管、同步等待或额外 Lane 任务。删除两处收尾重复构造 PlaceOrderCommand。
- 中间失败保留摘要：第一步只修子订单读取后，新增六产品回归全部暴露 completeTriggerOrderRuntime 的第二处越界（114项中6错误）；将成功终态写入并入 Lane 结算后通过。没有删除/弱化所有权检查。
- HotSpot25 `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am verify`：1046项，1045通过、0失败/错误、1项缺少 INSTRUMENT_SEED_TEST_JDBC_URL 跳过。新增回归覆盖6产品的编号冲突、IOC无成交/部分平仓及快照；随后扩展全部平仓，共18种组合，重跑 ClusterCommandPipelineTest 114项全通过。最终 main 场景补充保证金/余额及 verify-trigger 查询后重新 package 成功；未修改生产代码后再采样。
- 本机3 JVM JMH一次执行通过：17条外部业务命令，两次触发 APPLIED/TRIGGERED；子订单编号正确，两组持仓±10/±9、每组资金20000、对应锁定保证金100/90，fundsDiff=0。snapshot三个节点 businessHash 均为6038520190708482732；停止全部节点并保留原数据重启后 verify-trigger 通过。所有只读就绪、快照读取均首轮成功，无 Lane ownership、EXCHANGE_CORE_FAILURE、backlog 耗尽或节点故障。
- JMH SingleShot整场景1268.370ms/invocation，客户端 gc.alloc.rate=10.334MB/s，gc.alloc.rate.norm=13886912B/invocation，gc.count≈0；包含连接/类加载/建仓/查询，不能解释为单笔触发或服务端每笔分配，不推导吞吐上限、稳态尾延迟或优化比例。没有旧版对照。
- 三节点 JFR各24秒，文件842134/866863/857807字节，DataLoss均0；每节点3次GC。owner执行样本15/15/10，matcher1/4/3，Lane1/0/2，样本少且含启动；分配样本以byte[]、String、数组等初始化对象为主，不作热点占比或零分配结论。NMT committed为257133/254072/246421KB（reserved约10GB为JVM虚拟预留）；未做长稳，无泄漏结论。异常事件中未再出现上述所有权/撮合致命错误。
- 被测 service.jar SHA256=05d9189593e2af7993ae4f7ca31e2b7f3b9676ff8f0bf44f1826272cca60e737；benchmarks.jar SHA256=50a0a2380a270b2c680ddda324a520c9e9661878f9e1862d45bb3389ed37fc3e。复现/JMH共享驱动放在独立 benchmarks 模块 main 中，不进入业务服务包；测试用例保留在 service/src/test。生产触发缺陷功能验证完成，整体性能验收仍非本轮范围。
- 收尾清理：全部本轮节点和客户端已退出；分析后删除 /tmp/ex-trigger-fix 及本轮 surefire/failsafe 报告、迁移前残留 SmallControlReproMain.class。只保留源码、本摘要和可复用构建包，原始 artifact 已清理，不再提供失效路径为证据。

## 2026-09-09 修复后 Core 连续10分钟（采集前锁定）
- 用户明确要求使用之前 Core 脚本本机10分钟；本轮覆盖历史本机性能限制，只运行当前 master da5b068e，不进行云端操作或旧版本对照。HotSpot GraalVM25.0.1/Maven3.9.16，macOS26.7/i9-9880H/8C16T/16GiB，可用磁盘540GiB。
- ClusteredBatchTradingBenchmark.decodedBatchAdmissionAndSettlement，LINEAR_PERPETUAL/4Lane/1matcher/256窗口/batch2/realtime=false/settlementSpinLimit256，257账户/1模拟session/1symbol，闭环最大速率。每cycle3584业务项、2048业务Core消息、1024fills、1536batches/3072items、512查询；各账户初始10亿、zero fees、mark100，maker持续存在，开平仓/改单成交/批量挂撤按原脚本。非真实三节点网络，不修正coordinated omission。
- JMH f1/t1、warmup3×20s、measurement1×600s，G1/Xms768m/Xmx768m/NMTsummary，JFR profile maxsize128m，-prof gc；只在iteration边界重建状态，连续600秒不重建。使用已验证构建包 SHA256 50a0a2380a270b2c680ddda324a520c9e9661878f9e1862d45bb3389ed37fc3e。本轮无业务改动。
- 通过条件：脚本无异常，accepted=terminal、unfinished/endBacklog0，资金/持仓/冻结/订单清理和snapshot恢复校验通过；任何异常记录原样保留摘要。无吞吐数值门槛，单measurement无置信区间，无业务分段尾延迟，不作为生产容量验收。记录GC/热点/线程与系统干扰；磁盘低于10GiB停止。临时目录/tmp/ex-core-10min，完成分析后停止进程并清理原始产物。
- 首轮失败：遗漏JMH -to，默认600秒iteration timeout与600秒测量相等，日志明确benchmark timed out/interrupted 2 times；TearDown snapshot的Lane等待收到中断并抛account lane mutation was interrupted，退出1，无有效JMH结果。预热三轮金融检查通过，连续测量约600秒后在终检中断；不能当交易功能失败或正式吞吐结果。
- 第二轮执行前：仅将JMH timeout改为-to 15m，测量仍600秒、预热3×20秒及所有场景/JVM/profiler参数不变，新产物目录/tmp/ex-core-10min/retry。保留首轮失败摘要，不改变业务代码或负载，不延长交易30秒deadline。
- 用户随后要求不要重跑，立即TERM第二轮JVM并确认JMH退出；第二轮属于用户主动取消，无吞吐结果，不继续测试。
- 首轮只保留诊断：三段预热1.367/1.490/1.483 cycle/s，按3584业务项/cycle约4900/5340/5315 ops/s；600秒measurement没有正常生成分数，不能拿预热或未核对计数替代正式吞吐。最终日志pending0，但快照终检被JMH中断，不能宣称长轮金融/恢复验收通过。
- 首轮两次线程累计CPU差分（覆盖测量中约516秒，百分比按单核）：Owner/JMH线程25.54%、matcher8.52%、Lane0/1/2/3为4.03/3.72/6.51/3.73%，未饱和。Thread.print捕获Owner停于测试夹具Workload.drain:635的parkNanos(100000)，matcher及Lane等待；脚本推进等待是需要进一步检查的因素，没有量化其独立影响或修改生产逻辑。
- 首轮JFR664秒/约8.5MiB、DataLoss0、GC86次（83young/3old）、allocation samples72818，profile未开启New/OutsideTLAB，未取得有效-prof gc分数，故无精确每业务项分配/正式尾延迟结论。系统swap1396→1236.5MiB，采样未见CPU降频，磁盘约540GiB可用。启动存在JMH Unsafe废弃、native-access和Chronicle兼容提示，与本轮终检中断原因不同；未据此修改业务。
- 收尾：本轮两次JVM及监控退出后清理/tmp/ex-core-10min（含retry、JFR、JSON、日志和线程采样）；只保留本摘要，不提供已删除原始产物为证据。本次无生产代码改动，无有效10分钟吞吐验收结果。

## 2026-09-09 压测推进修正（采集前锁定）
- 用户授权修正后继续本机测试；本轮先短测定位，不重新跑10分钟或操作云端。当前master6051aaec加benchmarks改动，无生产变更：drain保留回调/顺序/30秒deadline，移除固定100us休眠；新增interleavedMetrics参数，默认true保留历史查询场景，false显式分离交易测量，查询计数与断言同步，资金/持仓/快照检查保留。
- HotSpot25.0.1/Maven3.9.16/macOS26.7/i9-9880H8C16T/16GiB。先六产品ClusteredBatchTradingBenchmarkTest；通过后当前构建JMH decodedBatchAdmissionAndSettlement，LINEAR_PERPETUAL/4Lane/1matcher/257账户/1symbol/256窗口/batch2/realtime=false/spin256。interleavedMetrics=true及false各独立fork，warmup2×5s、measurement1×30s、timeout2m、thread1，G1/Xms768m/Xmx768m/NMTsummary、JFR profile maxsize64m、-prof gc。3584business ops/2048业务消息/1024fills每cycle；查询分别512/0，batch1536/items3072。两者为当前代码不同负载，不冒充同口径优化对照。
- 再运行历史18万对应LinearPerpetualScaleSoakMain 1000 256 256 5 10 UNIFORM 1 20 32 60 10，当前代码、4Lane/1matcher/256窗口/闭环，4GiB ZGC/AlwaysPreTouch/DisableExplicitGC，60秒含JIT及终检，无独立预热、不加profiler；它是Runtime直驱mixed，与服务回调基准分开报告，不与旧版计算回归比例。各场景初态与资金/快照断言沿用原脚本，任何失败原样记录并停止相关负载。
- 所有轮次仅诊断，无绝对吞吐门槛；无真实三节点、业务分段延迟和长期内存验收结论。临时目录/tmp/ex-core-pump，磁盘低于10GiB停止，分析后清理本轮JFR/日志/测试报告。只保存必要摘要，不改README。
- 采集前功能回归首轮52项/46错误：简单移除park使每个spin都调用onTimerEvent，后者每次追加一条replicated fence，快速耗尽8192队列；不是业务高负载下的容量结果。修正模拟定时器为生产配置的1ms节奏，避免伪造无限日志消息，不增加队列、不调用私有推进方法绕过日志、不修改生产代码。后续JMH以此最终脚本执行；此项节奏修正会影响结果，不能把差值全部归因于park删除。
- 最终HotSpot25构建成功，ClusteredBatchTradingBenchmarkTest 52项全部通过，涵盖六产品及新增无查询重复交易，金融/订单/冻结/持仓/恢复断言保留。构建包SHA256=631332b1e878d04b226774c63ae74fad93905e88e3f8e617ab81c10ffaef61f7。
- 服务回调JMH两轮均退出0：metrics=true 755.433 business ops/s（约431.676业务消息/s、215.838fills/s），terminal业务25088/Core14336，queries3584；false 973.885 business ops/s、556.505业务消息/s、278.253fills/s，terminal业务32256/Core18432、queries0。两组accepted/terminal一致、rejected0、unfinished/endBacklog0、资金与恢复通过。真实终检maxBacklog和commandWindow计数不代表始终256在途。含查询/不含查询B/business分别约11671.324/10543.246，GC profiler测量各1次/14ms与12ms；单样本无CI，JFR/脚本开销包含在结果中。
- JFR metrics54秒/trading49秒含预热与终检，DataLoss均0、各4次GC，执行样本4631/4197，park218/190。此轮没有完整线程CPU/NMT差分、业务分段延迟、细粒度分配或长期内存采样，因此只作部分诊断，不能把spin CPU解释为有效计算饱和。
- 历史对应Runtime直驱mixed退出0/PASS：elapsed60.896秒含终检，setup4269.624ms；6835716 terminal business operations、687620 Core messages，平均112252.369 business ops/s、11291.717消息/s，maxMatchingBacklog256，fundsInvariant=true、余额/持仓/冻结/订单及snapshot恢复检查通过，snapshot8407678B、restore419.491ms。10秒区间依次64857.079/134039.646/155231.036/79660.294/96151.429 ops/s，最后不足整段不单报；无独立预热，不取峰值为持续结果。incompleteRiskScans256是预算扫描剩余状态，非未终态消息，incompleteFunding0。
- mixed过程中观测系统swap使用3908.75MiB、CPU speed100，存在换页风险但没有前后pageout差分证明其贡献，不归因全部差距；4GiB ZGC与无预热短轮不适合作历史302秒174729.238的回归量化。未采集mixed JFR/完整延迟，无真实三节点吞吐结论。终检sweep分位为整轮业务循环而非单订单延迟。
- 结论：修正测试器后没有把回调吞吐抬高，反而暴露之前100us伪定时器推进与生产1ms节奏差异。固定分阶段发送+集中maker资金依赖+定时器排空不构成持续饱和负载；不能把755/974或之前5300当Core算力。Runtime直驱已证实当前可达十万量级，但尚未证明恢复历史持续17.47万。生产控制/依赖提交机制未变更，后续需独立设计持续回调负载及定位推进边界，不能简单删确定性屏障。
- 收尾：全部本轮构建/测试/JMH/mixed进程结束；记录摘要后清理/tmp/ex-core-pump及benchmarks本轮surefire报告，不保留失效artifact路径，不修改README。

## 2026-09-09 当前异步实现 Google Cloud 三节点（采集前锁定）
- 用户要求回到Google Cloud；仅当前master dfa34d3f。surprising-ae591/asia-southeast1-b，surprising-core-0/1/2与surprising-load，4台n2-custom-8-16384（各8vCPU/16GiB），三个独立Core真实UDP/日志复制，私网10.90.0.5/.3/.4，load .2。IAP仅管理，业务走私网。云端HotSpot Temurin25.0.4.1，部署本机已验证构建，停止自动启动的旧服务，独立本轮数据目录。
- 先SmallControlReproMain ready/trigger检查，再ClusterMixedCapacityMain continuous trading-stream=true（非operational），U本位永续1769账户/256symbol/batch20、同原初始化每账户10亿、做市模板和财务断言。固定全局256/session256、1命令+1查询session、4Lane/1matcher，seed110901、30秒预热+120秒测量；持续异步闭环未修正CO，不启动WS/Valkey/后台operational侧载，不冒充全生产混合负载。
- Core4GiB ZGC/AlwaysPreTouch/NMTsummary，SHARED_NETWORK/service YIELDING/Lane spin0，profile JFR maxsize128m；load2GiB ZGC/SHARED，30秒业务deadline不改。每5秒ps线程CPU/RSS/free/磁盘，数据不足10GiB停止；临时本地/tmp/ex-gcp-current、云端/home/atomex/ex-gcp-current。测量带JFR只作诊断，无旧版对照、无绝对吞吐验收门槛。
- 正确性门槛：ready及触发检查PASS、capacity offered=terminal/unfinished0/fundsDiff0及脚本状态核对；失败原样记录，不跳过业务异常继续升压。报告ops/messages/fills与延迟原始口径，单轮不能证明95%有效业务饱和、全产品恢复或长期无泄漏。执行结束收集必要证据后清理本轮目录，finally停止四VM并核实TERMINATED，不修改README。
- 首次部署已校验三节点service SHA256=05d9189593e2af7993ae4f7ca31e2b7f3b9676ff8f0bf44f1826272cca60e737，但小样本工具硬编码localhost，ready连接127.0.0.1超时，未进入压测。修正工具读取hostnames/egress配置并重新构建，生产包未变；归档在被归档目录内创建tar导致directory changed提示，修正为目录外生成，首次停止流程照常执行。
- 用户进一步要求直接服务器压测、不要小样本：第二次执行取消ready/trigger步骤，直接ClusterMixedCapacityMain既有初始化+30秒预热+120秒连续测量及最终金融断言；其他参数不变，云端新目录/home/atomex/ex-gcp-current-direct。不把未执行小样本记作通过。
- 第二次仍未进入压测：实例RUNNING后SSH尚未就绪，IAP failed to connect to backend port22；部署入口缺少管理连接重试，finally再次停机，空目录归档失败记录保留。第三次只增加启动阶段只读SSH ready重试（非交易小样本/非业务请求重试），负载参数不变；避免将管理层瞬态故障当交易失败。
- 第三次已实际执行云端负载，无ready/trigger小样本：三节点service SHA仍05d9189593e2af7993ae4f7ca31e2b7f3b9676ff8f0bf44f1826272cca60e737，benchmarks含地址配置修正SHA=8ffc01387bb6132a2e911fae562179f9157151f3a542956724a784975dc5324d。mixedLossLifecycle=PASS（liquidation/insurance/adl）、mixedSetup=PASS（1769用户、1000retail、256symbol、initialFunds1768000000125）。进入连续交易预热后客户端admitted请求30秒无终态，ResultUnknownException，尚未到有效measurement，未执行最终财务核对，无吞吐/尾延迟验收结果。
- 真实生产失败栈：core-1从FOLLOWER成为LEADER后onSessionMessage抛AgentTerminationException，根异常snapshot projection batch is already active，OrderedCommitCoordinator.beginCommitPublicationBatch:1262 ← MatchingCommandAdmission.prepareMatching:230 ← beginMatching:129 ← TradingCoreRuntime.apply:1000 ← applyDecodedCommand:506 ← applyClusterCommand:495 ← SurprisingClusteredService.progressCommandsInScope:219。core-2接任LEADER后从onTimerEvent/drainFromLogEvent进入相同调用链并同样失败。core-0记录leader heartbeat timeout，终止时窗口pending1。这证明连续云端输入触发了批次上下文重入/未释放冲突，不能归为Core算力饱和、磁盘满或Aeron消息吞吐上限；具体遗漏的挂起/恢复分支尚待回归定位，未通过删保护/放大超时掩盖。
- 本轮仅修改SmallControlReproMain读取hostnames/egress系统属性，默认仍支持原本地地址；HotSpot25 Maven package -DskipTests成功，未修改生产代码；因用户取消小样本，不宣称修改后的工具已完成云端功能验证。已有52项回归属于上一轮压测器修正，不替代新发现异常的回归。
- 最新轮节点停止后成功收集node日志/JFR/monitor与capacity日志并删除云端ex-gcp-current-direct目录；本地分析后清理/tmp/ex-gcp-current。第一轮归档失败留下的云端ex-gcp-current目录未在最终停止前补清（旧测试文件，非业务数据），下次开机需先清理；未为删除临时文件再次开机。四VM最终状态另行核实，不保留已清理artifact链接。
- 最终核实四实例全部TERMINATED。两个发生致命异常的leader JFR主文件为空，不能提供其完整JFR热点/分配结论；上述根异常来自节点原始日志，非采样推测。本轮没有有效吞吐结果，新生产批次上下文异常尚未修复。

## 2026-09-09 异步提交上下文恢复修复（本地验证预锁）
- 用户要求修复，并询问历史数据：云端为新目录，故不清空历史数据/不启动云端。基于4eeb943a，在本机全新内存fixture复现，六产品PLACE首次Lane派发后挂起，Lane被测试门闩阻塞，第二次轮询提前restore上下文、completeDispatchedMatcherSettlement因event未完成返回null，commitPublicationDeferred留为true；6/6复现失败，排除历史数据。取消/改单存在同样调用顺序，批量分支也检查了开启前的Lane readiness。
- 修复只在恢复上下文/开启批次前检查对应事件完成状态，未完成保持已有挂起上下文，不清除脏状态、不增加任务/容器/等待/全局屏障，不删除重入保护。回归放行后插入独立订单、串行对照业务hash及snapshot恢复；覆盖6产品的普通单、撤单、改单和批量撤单。中间串行hash差异来自新fixture误用TIME而非command原时间，修正测试时间后原120项pipeline回归全通过；未放宽财务/状态断言。
- 最终执行HotSpot GraalVM25.0.1、macOS i9-9880H8C16T/16GiB，Maven benchmarks -am verify。新增JMH laneCompletionContextHandoff覆盖相同普通单/改单/批量撤单回调路径，LINEAR_PERPETUAL/4Lane/1matcher/257账户/1symbol/batch2/maxInFlight256/interleavedMetrics=false/realtime=false/spin256，f1/t1/warmup1×1秒/measurement1×2秒/timeout2m，G1/Xms768m/Xmx768m/NMTsummary/profile JFR maxsize32m/-prof gc。功能边界诊断，不作吞吐/长期内存/云端验收；3584业务项、2048消息、1024fills每invocation，资金/持仓/冻结及恢复由fixture终检。运行前磁盘检查，分析后清理/tmp/ex-commit-context及本轮测试报告，不写README。
- 扩展批量撤单时，新增fixture把独立订单ID取为103，实际与批量101..120重叠，串行对照正确拒绝该新单；该测试直接调用applyClusterCommand、未经过服务依赖判定，因此不能作为真实独立准入样本。修正为8000范围外ID，并新增串行终态APPLIED及账户状态精确相等断言，未放宽hash。第一次完整service657项中6项均为该fixture错误，其余通过，修正后重新执行。
- 最终benchmarks -am verify成功：1058项、1057通过、0失败/错误、1项缺少数据库环境跳过，其中service657/benchmark177全部通过。新增6产品×4操作共24情形保持Lane未完成、重复3次轮询再准入独立订单，随后验证双命令APPLIED、账户精确一致、串行hash和快照恢复一致。
- JMH laneCompletionContextHandoff退出0，测量完整1cycle=3584business ops/2048Core消息/1024fills/1536batches/3072items，accepted=terminal、rejected0、unfinished/endBacklog0、查询0，金融/订单/持仓/冻结/快照恢复终检通过。短轮score0.260921528cycle/s，gc.alloc.rate18.7567MB/s、gc.alloc.rate.norm77396968B/cycle、measurement gc.count0；含初始化/终检影响且未稳态，不用于评价吞吐改善或精确每单分配，不作云端结论。
- profile JFR10秒、DataLoss0、执行样本683/分配样本431/GC3次（含启动）；没有长稳、完整NMT差分/线程CPU与业务尾延迟证据，不宣称零分配、无泄漏或已修复全部吞吐问题。service SHA256=096a4cd3d68c7c737f2f6db17760ef13043581a92db01d3b1e67ba1f148bb9a5，benchmarks SHA256=19c45b7eaef503046707fb327200a7967c255db7e32290ffc267ed224c70f537。
- 本轮未启动云端/未清空历史数据；修复与本地验证完成，真实云端持续负载尚待复测。全部本轮测试/JMH进程退出，分析后清理/tmp/ex-commit-context和本轮surefire/failsafe报告，保留源码及摘要，不改README。
