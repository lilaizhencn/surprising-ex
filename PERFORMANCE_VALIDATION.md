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
