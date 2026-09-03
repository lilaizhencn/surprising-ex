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
