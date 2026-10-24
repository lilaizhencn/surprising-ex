# 六产品线顺序运行验收（2026-10-24）

基于 `17c544f6340209f37cd94bd85db13034e871f418` 及本次响应头修复，按下表顺序启动并验证；完成并停止一条产品线后再启动下一条。使用 HotSpot Corretto JDK 27、Maven 3.9.16，独立 PostgreSQL 实例/数据库（15432/sixline_live）、Kafka 19092、Redis 16379 和本地确定性行情 REST 19195 / WebSocket 19194。数据库供本轮六条产品线共用，启动时只启用当前产品；Core 数据目录按产品及场景隔离。每组包含 Core、Gateway、Price、Realtime、Maker，衍生品加 Lifecycle；本轮未启动 wallet。

| 顺序 | 产品线 | 真实 HTTP/WS 用例 | 资金差额 | 恢复前后命令数 | 恢复前后 Core 状态哈希 |
|---|---|---|---|---|---|
| 1 | 现货 | 通过，0 失败/错误/跳过 | 0 | 47 | `3dd8b3c9a626b9f4`，一致 |
| 2 | 正向永续 | 通过，0 失败/错误/跳过 | 0 | 3101 | `25b34b2447a4192e`，一致 |
| 3 | 反向永续 | 通过，0 失败/错误/跳过 | 0 | 601 | `4c25e0942d04e4e0`，一致 |
| 4 | 正向交割 | 通过，0 失败/错误/跳过 | 0 | 780 | `bc69d29ec454591c`，一致 |
| 5 | 反向交割 | 通过，0 失败/错误/跳过 | 0 | 735 | `4cf2de0cb5b97b7f`，一致 |
| 6 | 期权 | 通过，0 失败/错误/跳过 | 0 | 1994 | `4c77c10da7c1cad2`，一致 |

每条产品线均验证配置热更新、版本冲突/非法参数拒绝、标的上架与暂停、实际做市报价、限价下撤单、市价成交、用户执行推送、盘口/公共成交的 WS 与 REST 身份一致性、终态查询及余额核对。业务变更期间进程 PID 保持一致。

现货另覆盖 FOK、私有订单历史与成交归属。永续覆盖开平仓、只减仓和风险查询；两个单独 Core 场景实际执行资金费和强平，检查清算完成、总资金守恒，并在保留持仓的情况下保存快照、冷启动后再次运行验证门禁。正向永续门禁快照前 14 条命令，哈希 `71cf86b3029d3dd7`；反向永续快照前同为 14 条，哈希 `862d6556d100c4a3`，两场景资金差额均为 0。

正向/反向交割覆盖到期转 SETTLING、撤销挂单及两条触发单、结算到 CLOSED、结算重试幂等、用户/做市相反方向的精确结算流水、持仓及冻结归零。精确测试损益分别为 200000000 USDT 最小单位、78431 BTC 最小单位，均核对资金守恒。

期权覆盖 OCO 两腿撤销后的用户终态、管理员时间线和提交回执查询；CALL/PUT × ITM/ATM/OTM 六个到期场景全部通过。实值行权现金均为 400000000，平值/虚值为 0；每对权利金 1000000000、taker 费 500000、maker 费 200000，单位均为 USDT 最小单位。六组持仓、冻结归零，结算历史和总资金差额核对通过。

8 次恢复均实际调用 `io.aeron.cluster.ClusterTool <cluster-dir> snapshot`，确认有效 service 与 consensus 快照记录各新增一条，保留原日志与数据目录，停止 JVM 后重新启动 Core。恢复阶段只启动 Core，在 HTTP/WS 验证完成后核对完整状态哈希，六条主业务场景同时核对已应用命令数；未以重建空状态或只运行快照单测替代恢复。重启前使用 `CommonContext.isDriverActive` 确认旧 MediaDriver 已失活。

本轮定位并修复 `CoreMessage.responseHeader`：可复用响应头的 `messageType` 尚未赋值时，`kind()` 会解引用它，首个真实 Core 查询即空指针并终止 Owner。初始化判断改为读取已有 `messageType`。新增回归先复现同一空指针，再验证首次初始化、同一命令复用、类型变更拒绝及释放后的下一命令复用。

同时校正两处旧测试数据：协议固定字节/指纹从过时 schema 9 对齐当前 schema 16（canonical-v2 SHA-256 另用 Python 独立重算为 `16485fe71c62ab2af309b348873c7ad0dab20ea1f5e894b6b2852c0c65284eb6`）；快照损坏注入改到当前 manifest 中产品码和命令计数后的实际 snapshotId，恢复拒绝时原运行时、哈希和计数均保持不变。扩展初测 83 项中仅该旧偏移用例失败，修正后按受影响范围复验 39 项，全部通过。

最终执行结果：18 模块运行包构建退出码 0；39 项协议/Owner/收发回归、15 项快照结构与编解码测试、12 项资金费分页及内存快照续接/已提交资金费回放/期权精确权利金与费用/ADL 索引测试全部 0 失败、0 错误、0 跳过；六条真实 HTTP/WS 测试各 1 项全部通过；两个真实 Core 资金费/强平门禁的 execute 与恢复后 verify 均退出码 0。每份运行包、日志和结果的 SHA-256、实际命令、退出码及场景输出见[机器证据](six-product-runtime-20261024.json)。打包命令使用 `-DskipTests`，测试成绩来自上述单独执行的命令。

```sh
mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=CoreMessageCodecTest,BorrowedResponseSlotTest,CoreMessageFlyweightDecoderTest,TradingCoreOwnerTest#pairedManifestMismatchFailsBeforeLiveStateReplacement,TradingOwnerInputReuseTest,BorrowedHeaderEgressTest,DeferredSessionResponsesTest,OwnerSessionBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=SnapshotFieldEvolutionTest,TradingStateSnapshotCodecTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl surprising-aeron-core/surprising-aeron-service -am -Dtest=AsyncFundingCommandTest,CommittedFundingReplayTest,CoreOptionPremiumFeeFundingTest,AdlPositionIndexTest -Dsurefire.failIfNoSpecifiedTests=false test
```

真实 HTTP/WS 测试逐产品设置 `SIX_LINE_LIVE_PRODUCT`、独立环境的 JDBC 用户/密码和 `SIX_LINE_LIVE_RUN_ID`，启用 `SIX_LINE_LIVE_TRADING=true`、`SIX_LINE_LIVE_EXPIRY=true`、`SIX_LINE_LIVE_OPTION_REFERENCE=true`；现货另启用 `SIX_LINE_LIVE_USER_HISTORY`、`SIX_LINE_LIVE_USER_FILLS`，期权另启用 `SIX_LINE_LIVE_TRIGGER_READ_VIEW`。启动入口为 `scripts/start-product-line-providers.sh`，使用本轮新构建的 8 个运行包；每组启动后运行：

```sh
mvn -pl surprising-gateway -am -Dtest=SixProductLineContractLiveTest -Dsurefire.failIfNoSpecifiedTests=false test
```

永续补充门禁显式运行 `com.surprising.aeron.benchmarks.workload.ClusterProductLineGateMain`，指定当前产品、单成员 `127.0.0.1`、seed 17001，先 `smoke-mode=execute`，快照恢复后 `smoke-mode=verify`；其为有限业务验证入口，未运行 JMH。完整构建模块列表见机器证据的 `build.command`。

保留了验证中遇到的失败记录：恢复脚本最初通过 macOS java_home 误选系统 JDK 25，改为已核验的 JDK 27 后通过；首轮正向永续因隔离数据库仍启用已停止的现货而激活超时，选定当前产品后重跑完整用例通过；首次正向永续立即重启遇到旧驱动心跳保护，确认驱动失活后用原快照/日志恢复通过，后续恢复统一先检查驱动。未修改业务约束或删除恢复数据来消除这些失败。

本轮没有覆盖浏览器/Flutter 点击、三节点切主或强杀、资金费/ADL/结算在途进程崩溃、资金费定时调度的完整周期、ADL 与保险调度的端到端流程、自动止盈止损执行、钱包及性能。资金费分页续接与 ADL 索引成绩属于单元范围，不扩展为上述端到端结论；全量简化审计仍有独立未完成事项。

本轮进程、依赖、IPC 及临时数据在摘要归档后清理，最终状态见机器证据 `cleanup`。
