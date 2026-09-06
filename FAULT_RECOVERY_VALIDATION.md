# 本机 Cluster 故障恢复功能验证

本轮按用户要求仅用少量样本验证功能，不运行压测、JMH 或吞吐采样。此要求覆盖 AGENTS.md 中性能采样的通常要求；不提供性能验收结论。

## 测试环境与边界

- 当前 master；本机 HotSpot JDK25 编译，独立 Colima `aeron-fault-qa` Linux VM 内运行三个 Core JVM 与独立客户端 JVM。
- VM 内专属 network namespace/网桥与端口，使用 iptables 注入真实网络分区，SIGKILL/SIGSTOP/CONT 注入进程故障。
- 每成员独立持久目录，禁止对开发数据/共享磁盘执行故障注入。存储异常仅在本轮专用目录/挂载点操作。
- 每次一个 ProductLine，1 matcher、4 Account Lane，推送关闭，无 HTTP Provider、Kafka、数据库或钱包。
- 少量 maker/taker 样本、逐笔命令标识和预期值；无负载速率、无容量结论。

## 验证矩阵

| 场景 | 证据要求 | 状态 |
|---|---|---|
| follower 强杀及追赶 | 两节点交易继续、重加入后可接任 leader、业务对账 | 通过：afqa-final-availability-03 |
| leader 强杀 | 重新选举、相同命令 ID 重试、继续成交/撤单 | 通过：afqa-final-availability-03 |
| 失去多数派 | 新业务不能确认成功，恢复后结果明确 | 通过：afqa-final-availability-03 |
| leader/follower 暂停恢复 | 处理角色变化、旧 leader 不独立提交 | 通过：afqa-final-availability-03 |
| leader/follower 网络隔离 | 多数派可用、少数派不能提交、恢复一致 | 通过：afqa-final-availability-03 |
| 正常全集群重启 | 快照/日志恢复、已确认业务保留 | 通过：afqa-final-availability-03 |
| 全集群强杀 | 日志/快照恢复、结果不明请求幂等 | 通过：afqa-final-availability-03 |
| 落后节点重入 | 离线样本继续交易，重入后追赶并接任 | 通过：afqa-final-availability-03 |
| 快照过程中故障 | 中断快照不可成为有效恢复点 | 通过：suite-02/snapshot-cut |
| 磁盘满/写失败 | 显式失败，不静默丢业务或空状态恢复 | 通过：suite-02/read-only、storage-03 |
| 日志/快照损坏 | 检出损坏，拒绝错误恢复或按明确恢复策略处理 | 通过：suite-02/corrupt-log、storage-03；修复后三份新快照一致 |
| 已提交但响应丢失 | 相同命令重试不重复产生订单/资金影响 | 通过：suite-02/snapshot-cut |
| 撮合/跨 Lane 中断 | 未完成事件不得被复用/当完成，恢复业务一致 | 通过：CoreOrderedOrderBatchTest、OrderBatchSettlementWaitTest、RuntimeCommitRecoveryTest |
| 批量下单/改单/撤单中断 | 子项结果与冻结、订单终态一致 | 通过：CoreOrderedOrderBatchTest、ProductRecoveryLifecycleTest |
| 六产品线业务恢复 | 未成交/部分成交/完成/撤单、持仓、触发、资金费/强平/保险/ADL/交割/行权 | 通过：suite-02/product-* 六组 + 下述精确业务边界测试 |

检查重点：成功确认的业务不丢；未知结果最终可辨；重复命令不产生二次资金动作；用户+maker+Treasury 逐资产守恒；相同日志位置状态一致；恢复后继续完成新业务。

## 执行记录

约定矩阵已完成：11 个真实集群场景组全部有通过证据，六产品线资金对账无差额。最终采用 `afqa-final-suite-02` 中通过的 10 组，加 `afqa-final-availability-03` 可用性补验；存储组另用 `afqa-final-storage-03` 补强修复成员追赶后的三副本一致性。首次套件中失败的可用性记录原样保留，没有把原始 suite summary 改为通过。

最终可用性补验中，最初离线的 node1 重新追赶并实际成为 leader（见 node1.log），随后三份业务快照一致。

原始 VM 目录位于 `/tmp/`；宿主机完整证据归档：`/Users/atomex/Desktop/surprising/aeron-fault-evidence/2026-09-06/`，包含日志、vm-evidence.tgz（各轮 Archive/快照/MediaDriver 证据）、测试 classpath、被测 jar、汇总与校验清单。以下记录区分产品缺陷与夹具问题。

### 环境与构建

- 基线 commit：`49950e027262b43aacae0f4d418d5b6cffb8269d`，只验证当前 master。
- 宿主 HotSpot Oracle GraalVM 25.0.1；VM HotSpot Temurin 25.0.4.1+1，Linux 6.8、4 vCPU、6 GiB。
- Linux JDK 下载 SHA256：`dbb698396d478e7fa2b1e50f4103324b2a99b90569ee27c33f2261f9215cf41e`。
- 最终正确性构建 `/tmp/afqa-final-build-03.log`：636 个测试通过（product 12、protocol 88、client 43、instrument 14、service 431、trading-api 20、tools 28），0 失败、0 跳过。HTTP load、容量门禁、JMH、Kafka exporter 集成测试不在本轮范围。
- 被测产物 SHA256：service `347458fe72d5e5a699ce4512b5cf6572359aa8f3ba2ee126ff80506022192d47`；tools `68fe7e5a717f7465643acdd8db72764453c29d44902c474078db59074b9d373b`。真实集群的 Core 与客户端来自此 tools shaded jar；classpath 为 tools/test-classes:tools/classes:tools.jar。门禁随后按完整 Treasury 口径补编译到 tools/classes，故障入口来自 test-classes；这两份目录和原始 jar 一并归档，最终打包另记校验值。
- `CorePerpetualFinancialMatrixTest` 补充两条永续、全仓/逐仓：从保险赔付前、ADL 前的非空快照恢复 Runtime，再执行对应命令并与未中断结算完整对账；补验日志 `/tmp/afqa-insurance-adl-recovery.log` 通过。
- 故障夹具网络归属保护 2 个测试通过：已有 namespace 或 bridge 导致拒绝启动时，不清理其他测试的资源。
- AWS 配置 render 的 2 个测试通过，覆盖六产品线；增加 CRC 所需 JVM open 参数和 Core 受限自动重启。

### 发现并修复的产品问题

1. **Archive 未校验日志负载**：`afqa-corrupt-log-01` 把已确认金额 1,234,567 改为 1,234,566，原代码成功恢复了错误余额。Core Archive 现强制记录/回放 CRC32C。`afqa-corrupt-log-03` 同样破坏三份日志均拒绝回放；恢复原始文件后余额 1,234,567，继续入账 1 后为 1,234,568。
2. **致命 Driver 超时仅打印日志**：`afqa-availability-01` SIGSTOP 后恢复触发 DriverTimeoutException，Archive 清理失效 counter 映射导致 SIGSEGV。现 fatal Aeron、AgentTerminationException、JVM Error 立即失败退出，不继续使用失效资源；`ClusterFatalErrorTest` 和真实暂停/重启验证。崩溃原始证据：VM `/home/atomex.guest/hs_err_pid2129.log`。
3. **显式全局 hash 查询返回旧审计基准**：普通交易不更新完整 audit hash，查询原先直接返回缓存值。`STATE_HASH_QUERY` / `BUSINESS_STATE_HASH_QUERY` 现仅在查询边界建立全 Lane fence 并计算当前业务 hash；不把计算放回普通命令路径。`CoreAuditHashQueryTest` 验证余额变化、快照恢复、重复请求。
4. **独立客户端连接超时的资源关闭竞态**：`afqa-final-linear_delivery-02/client.log` 记录连接超时后 MediaDriver 已关闭，但连接线程仍存活，Aeron 默认 fatal handler 导致客户端进程退出。同步入口改用既有 AsyncConnect，在单一线程内轮询，超时先关闭客户端/连接资源，再关闭自有 driver；删除 FutureTask 和后台关闭线程。新增连续三次连接失败、目录资源清理回归。
5. **启动配置异常残留 shutdown barrier**：将 barrier 的创建移到配置构建之后，避免缺少 CRC JVM open 参数等前置失败留下等待线程。保留已有 ActiveDriver 启动失败回归。

### 夹具修正与失败轮次（不计为产品恢复通过）

- `afqa-smoke-01`：SIGKILL 后立即启动遇到 ActiveDriverException；脚本现在确认死亡后等待默认 10 秒心跳失效，留 1 秒余量。
- `afqa-availability-02`：supervisor 使用上一次死亡时间，致命退出后的重启过早；已清除旧时间。`afqa-availability-03` 全组可用性断言通过，后续最终组增加客户端只访问少数派的断言。
- 六产品线新样本初期缺少新鲜 mark、混淆 header 时间字段、使用了与持仓方向相同的衍生品触发方向；按真实 Core 合约修正测试输入，没有放宽生产校验。终态触发单可保留有界历史记录，断言其状态，不误认为整个内部 Map 必须为空。
- `ClusterProductLineGateMain` 补充衍生品入场前新鲜 mark，以及期权 index/forward；强平 mark 不再使用过期的固定时间。首次最终套件五条衍生品线发现旧 gate 输入不符合当前合约：风险扫描页大小 256 超过控制上限、交割早于到期、期权现金价值 25 与 120−100 不符。现读取当前扫描预算，有限循环等待多阶段扫描明确完成、真实等待短期到期和现金价值 20，没有放宽 Core 校验。
- `afqa-final-read-only-02` 的 bind mount 没有进入 JVM 私有挂载命名空间，未实际触发写失败；改为 nsenter 后，`afqa-final-read-only-03` 真实 EROFS、恢复后业务和三副本快照均通过。
- `afqa-storage-01` 修改的是 Aeron container 元数据；后续定位 Core envelope 再损坏。`afqa-storage-02` 和最终套件首次 storage 的冷启动等待窗口不足：缺失成员时默认 startup canvass 为 60 秒，旧夹具约 56 秒就失败。现允许 15 次有限连接尝试；磁盘修复后也先证明三份同位置快照一致，再注入快照损坏。`afqa-final-storage-02` 完整通过。
- `afqa-corrupt-log-02` 缺少 `java.util.zip` open 参数，未进入业务验证；后续 VM、AWS render、子 JVM 测试均同步。
- `afqa-snapshot-cut-01` 同步等待 snapshot control，无法及时强杀已暂停的节点；改为异步请求，等待 agent 的实际 offer 成功标记。
- `afqa-snapshot-cut-02` 中断快照、回放、丢响应与去重通过，最后复用 gate 初始化失败；最终使用独立 QA symbol 与新订单验证继续交易。
- `afqa-final-derivatives-02`：永续的单工作量扫描用 16 次循环仍不够（跨 Lane、用户、风险阶段），改为读取控制预算和明确完成断言；U 本位交割则暴露了上述真实客户端超时竞态。币本位交割、期权在本轮通过。
- `afqa-final-suite-02/availability`：网络/多数派恢复断言通过，进入全集群重启前，有 follower 尚未完成快照，固定 4 秒检查失败。夹具现有界等待三份同位置业务快照，必要时在追赶完成后再发起快照。此轮失败不计作全集群重启通过。
- 门禁对账审查：旧 `requireEconomicFunds` 把待处理清算工作当作账本科目，漏算清算盈亏等 Treasury 科目。现与 `SharedProductLineSnapshotContractTest` 的资金守恒口径一致：用户 available+locked，加 fee、insurance、liquidationFee、fundingResidual、roundingResidual、clearingPnl，减 insuranceDeficit。仅修正验证工具，没有改变交易结算。
- 一次构建测试筛选过宽，在进入 tools 测试前终止并重启正确筛选；不把中断构建计为通过。`afqa-final-build-02` 为完整通过记录。

### 最终打包

- 门禁修改后 tools 的 28 个相关测试通过，最终 package 成功（`/tmp/afqa-final-package.log`）。
- 逐字节核对最终 jar 中已测的 Core/客户端类与 build-03 相同；门禁类与实际集群使用的补编译 class 相同。
- 最终 service SHA256：`347458fe72d5e5a699ce4512b5cf6572359aa8f3ba2ee126ff80506022192d47`。
- 最终 tools SHA256：`5a6994f36546bae47d39379d4a8afa252771108714803e25f825b8db4982f958`。

### 最终验收层级

- 真实进程层：三 Core、选举、进程信号、网络隔离、Archive 磁盘/损坏、真实快照中断、丢回包、恢复后新交易。
- 精确业务边界层：`CoreOrderedOrderBatchTest`、`OrderBatchSettlementWaitTest`、`RuntimeCommitRecoveryTest` 使用真实 matcher/Lane 或 Cluster callback，确定性注入观察 matcher fact 后和 Lane 结算中间的失败；不能把它们描述成随机强杀时恰好命中某一条指令。
- 六产品线：`ProductRecoveryLifecycleTest` 每条线非空快照、部分成交、批量三种动作、触发待执行/执行后恢复与继续成交；`RuntimeCommitRecoveryTest.replaysInsuranceResolutionAndAdlAcrossPairedClusteredSnapshotCuts` 验证保险和 ADL 中间恢复；资金费、交割、期权价内/价外、主动平仓由对应 financial matrix 与真实 product gate 对账。
- 最终全集群停止信号已记录：SIGTERM 三节点返回 143，SIGKILL 返回 −9；两种停止后的余额和当前业务 hash 均通过恢复断言，不将 SIGTERM 表述为进程返回 0。
- 本机 SIGKILL 只模拟进程崩溃，不等价于物理断电、存储控制器掉电或跨可用区故障。此轮不承诺掉电持久性、容量、延迟或零性能成本。
