# Aeron Core 性能限制分析与解决方案（2026-10-11，基于 master `5a46d779`）

> 结论类型：**诊断分析 + 方案提案（待评审）**。本文不是验收结果。
> 当前 master 尚无有效的真实单节点吞吐结果（见 `PERFORMANCE_VALIDATION.md` 最新几节），因此下文所有“瓶颈”都按
> “现象 → 证据 → 假设 → 下一项最小验证”给出，未经实测的项明确标注“待验证”。

## 0. 已确认的事实（证据来源）

| # | 事实 | 证据 |
|---|---|---|
| F1 | 多 Lane worker 启用后，Owner 线程直接读取 Lane 线程正在写的 `LongLongHashMap`，在扩容瞬间抛 `ArrayIndexOutOfBoundsException: Index 2012 out of bounds for length 1024` | 实验 worktree 压测 `20261011T002525Z/node.log`：`TradingRuntimeState.order → AccountLaneState.pendingReservation`，发生在第 1,760,265 条命令 |
| F2 | Owner 跨 Lane 读订单归属会读到不一致状态，导致业务误拒 | 压测 `20261011T005530Z/client.log`：`CANCEL_ORDER_BATCH rejected ORDER_OWNER_MISMATCH`（master 已改为读 `activeOrderIndex` 路由） |
| F3 | Lane 等待策略在 `BUSY_SPIN` 时不 `unpark`，而等待分支仍会 `park`，导致唤醒丢失、请求 `ResultUnknown` 超时 | 压测 `20261011T002108Z/threads.txt`：lane-0/2 永久 `park`，Owner 空转；master 已改为无条件 `unpark + parkNanos` |
| F4 | Owner 仍承担资金写入、强平/保险/ADL、批量改单/触发等路径的权威读取 | `PERFORMANCE_VALIDATION.md`「尚未完成：SystemLedgerLane 生产接线…」 |
| F5 | 热路径上仍存在 ThreadLocal 查找与捕获型 lambda | `TradingRuntimeState.laneCommandScope`（ThreadLocal，`order()/position()/provisionalOrder()` 每次调用）；`onLane(...)` 共 87 处，多数为捕获型 lambda |
| F6 | Owner 仍有 7 处“遍历全部 Lane 表”的读取 | `TradingRuntimeState` 中 `for (int i = 0; i < accountLanes.length; i++)` 共 7 处（`order`、`provisionalOrder`、`position` 等） |
| F7 | 为止住 F1，在 `AccountLaneState.pendingReservationSequences` 上加了 `synchronized` | master `AccountLaneState.java:157/185`。这是症状修复：它让 Owner 与 Lane 在热路径上争同一把监视器，违背单写者原则 |
| F8 | 本机 8 物理核，压测时热线程约 10 个：Aeron sender/receiver、consensus、archive、clustered-service（YIELDING）、owner、matcher、4 个 Lane | `qualify-aeron-async-stages.sh`（`SHARED_NETWORK`、`idle-strategy=YIELDING`）及 `threads.txt` CPU 时间：clustered-service 30.1s/32.7s 墙钟 |

## 1. 限制性能的根因（按影响排序）

### L1. 状态所有权没有真正切开：Owner 仍读写 Lane 状态（最高优先级，同时是正确性问题）

- **现象**：F1、F2、F6、F7。
- **原因**：多 Lane 并发的前提是“每个 Lane 状态只被其 worker 线程读写”。但 `TradingRuntimeState` 仍是 4,589 行的共享门面，
  Owner 通过 `order()/position()/onLane()` 直接访问 Lane 的 HashMap。单线程时代这是安全的，worker 一启动就变成数据竞争。
- **对性能的影响**：
  1. 要么出错（F1/F2），要么只能加锁（F7），锁让 Owner 与 Lane 串行化，抵消并行收益；
  2. Owner 每命令多次遍历 4 个 Lane 的哈希表（F6），这是纯 Owner CPU，直接压低单线程上限。
- **方案**：
  1. Owner 只读**自己拥有的不可变路由索引**（`activeOrderIndex` 的 `orderId → userId/laneId/symbol`），master 已在批量撤单身份校验上这样做，
     需推广到 F6 的全部 7 处与 87 处 `onLane` 中由 Owner 线程调用的部分；
  2. 需要 Lane 内业务判断（余额、持仓、冻结）的，一律作为 Lane 邮箱命令由 Lane 执行并回填结果槽，不在 Owner 侧读；
  3. 完成后**删除 F7 的 `synchronized`**，并加一条源码边界测试（类似 `ProductionSourceBoundaryTest`）：Owner 包不得引用 `AccountLaneState` 的可变表。
- **验证**：改造后用 `-XX:+UseG1GC` 同配置真实压测；正确性门槛为 accepted=terminal、unfinished=0、资金差 0；
  JFR 中 `jdk.JavaMonitorEnter` 在 Lane/Owner 线程应为 0。

### L2. Owner 职责仍过多（F4）

- Owner 线程当前做：解码 → 路由 → 准入编排 → 订单批量展开 → 资金写入（Treasury）→ 强平/保险/ADL → 提交排序 → 响应编码 → realtime 输出。
- 吞吐上限 ≈ 1 / (Owner 每命令耗时)。在 Lane 已并行的前提下，Owner 是唯一串行点，任何留在 Owner 上的业务都直接限制总吞吐。
- **方案（最小职责 Owner）**：Owner 只保留 ①Aeron 日志顺序与确定性序号 ②路由（userId→Lane、symbol→Matcher）③有序提交与快照 fence ④响应发布。
  - 资金写入：按既定计划接入唯一 `SystemLedgerLane`（Treasury 单写者），Owner 只投递 delta；
  - 强平/保险/ADL：作为 Lane/风险线程命令，结果以事实事件回到 Owner 排序；
  - 响应编码：改单已做“Lane 完成前编码”（`CommandSlot.LaneResultTarget`），推广到下单/撤单/批量。
- **验证**：JFR `thread-cpu-load` 中 owner 线程 CPU 占比下降，`hot-methods` 中不再出现 Treasury/风险方法。

### L3. 热路径分配与间接开销（F5）

- `laneCommandScope.get()`：每次 `order()/position()` 都做 ThreadLocal 哈希查找；
- 捕获型 lambda（`onLane(userId, lane -> {...})`）：逃逸分析失败时每次分配一个闭包，且 `onLane` 调用点多态导致内联失败；
- `positionKeysForUserAndSymbol` 返回 `Collections.unmodifiableNavigableSet(new TreeSet<>(keys))`（boxed `Long` + TreeSet），若在风险热路径调用则逐次分配；
- 其他 ThreadLocal scratch（`RuntimeDerivativeFillCalculator`、`MatcherSettlementPlan.DIRECT_ORDER_KEYS`、`RiskLaneProcessor` 等）。
- **方案**：
  1. Lane 内方法直接接收 `AccountLaneState lane` 参数，删掉 `laneCommandScope`；
  2. `onLane` 捕获型 lambda 改为 Lane 上的具名方法（同时符合“主流程顺着读”的可读性要求）；
  3. scratch 对象改为 worker/owner 实例字段（每线程一个、生命周期明确），不用 ThreadLocal；
  4. 返回 boxed 集合的查询只保留在查询/快照边界，热路径用 primitive 迭代。
- **验证**：对受影响路径补 JMH（`-prof gc` 看 bytes/op 应≈0），整链路用 JFR `ObjectAllocationSample` + `ThreadAllocationStatistics` 计算 bytes/business op。

### L4. 线程模型与 CPU 超订（F8，Aeron Cluster 特性相关）

- 8 物理核上约 10 个自旋/让出型热线程，`SHARED_NETWORK` 下 sender/receiver 共用一个线程；clustered-service 用 YIELDING 几乎吃满一个核；
  Owner 与 clustered-service 是两个线程，日志消息从 clustered-service 线程交给 Owner 线程是一次额外的跨线程交接。
- **方案（逐项单因素验证）**：
  1. 先测 Lane 数 = 2 vs 4（在 Lane 改造完成后），确认 8 核机器上的最优 Lane 数，而不是默认 4；
  2. clustered-service 空闲策略 YIELDING → BackoffIdle（让出 CPU 给 Lane），对比吞吐与 p99；
  3. Owner 等待策略 ADAPTIVE vs BUSY_SPIN 单因素对照（历史 38–40 万成绩是 BUSY_SPIN，见 `PERFORMANCE_VALIDATION.md` 2026-10-10 节）；
  4. 评估 Owner 直接运行在 clustered-service 线程（`onSessionMessage` 内同步处理）以去掉一次交接——这是结构性改动，需单独设计评审。
- **验证**：每次只改一个因素；报告各线程 CPU、上下文切换、park 次数。

### L5. Lane 唤醒成本（F3 修复后的遗留）

- master 现在每次 `submit` 都 `LockSupport.unpark`（macOS 上是系统调用级开销），等待侧 `parkNanos(100µs)` 兜底。
- **方案**：Lane 维护 `volatile boolean parked`；生产者仅在 `parked == true` 时 `unpark`；消费者 `parked=true → 复查游标 → park → parked=false`，保证不丢唤醒且忙时零系统调用。
- **验证**：JFR `jdk.ThreadPark` 次数、Lane 线程上下文切换数；SettlementLaneWorkerTest 增加“park 窗口内发布”并发用例。

### L6. 出口（egress）与响应

- 首轮压测的失败形态是 `ResultUnknownException: admitted but result unknown`（客户端超时），说明结果迟到时客户端无法区分“慢”与“丢”。
- **方案**：在 L1–L5 之后复测；若仍出现，采集 egress publication back-pressure 次数与 `offer` 失败计数，再决定是否批量发布响应。当前**未定位**，不预先改动。

## 2. 流程问题（需要你确认）

- `AGENTS-performance.md` 规定“只在 master 开发、未经要求不建分支”。实验分支 `feature/decoupled-multilane-zeroalloc` 的成果已被 master `5a46d779` 吸收（且 master 对批量身份校验的修复更正确）。
  **建议**：停止在实验 worktree 上继续，后续全部在 master 上按 L1→L5 推进；worktree 是否删除由你决定。
- 实验 worktree 中最后一次压测仍失败（F2），**不存在有效的吞吐数据**；此前“任务完成”的表述不成立，以本文为准。

## 3. 建议执行顺序（每步独立提交、独立验证）

| 步骤 | 内容 | 门槛 |
|---|---|---|
| 1 | L1：Owner 侧 7 处全 Lane 扫描 + Owner 调用的 `onLane` 改为路由索引/Lane 命令；删除 F7 的锁；加源码边界测试 | 定向测试全绿；真实单节点 MIXED/256 窗口跑完无业务错误，资金守恒 |
| 2 | 拿到 master 的**第一份有效基线**（无 profiler 主轮 + gc 轮 + JFR 轮） | 按 `AGENTS-performance.md` 第 2–5 节完整记录 |
| 3 | L3：去 ThreadLocal / 捕获 lambda / 热路径 boxed 集合 | JMH bytes/op≈0；整链路 bytes/op 下降 |
| 4 | L5：条件唤醒 | park/上下文切换下降，吞吐不降 |
| 5 | L4：Lane 数、clustered-service 空闲策略、Owner 等待策略逐项对照 | 每项单因素报告 |
| 6 | L2：SystemLedgerLane 接线、强平/保险/ADL 迁出 Owner | Owner CPU 占比下降；资金/快照恢复核对通过 |

目标沿用 `PERFORMANCE_VALIDATION.md` 已锁定的口径：同配置两轮有效真实单节点吞吐恢复到历史 38–40 万业务项/s 或更高。
