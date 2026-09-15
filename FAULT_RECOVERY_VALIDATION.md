# 本机 Cluster 故障恢复功能验证


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

## 2026-09-15 类清理编译验证

- 变更：删除 12 个无 Java、配置或脚本引用的内部死代码类；公共 API DTO、事件和 Feign 接口未删除。
- 环境：Oracle GraalVM 25.0.1（HotSpot 25.0.1+8，Java 25）、Maven 3.9.16；测试前后磁盘可用空间约 505 GiB。
- 编译命令：`mvn clean compile`；结果：36 个 reactor 模块全部 `BUILD SUCCESS`，无编译错误。仅有既有 deprecated/unchecked 编译提示。
- 测试命令：`mvn -pl surprising-aeron-core/surprising-aeron-protocol,surprising-trading/surprising-trading-provider,surprising-account/surprising-account-provider,surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,surprising-price/surprising-price-provider,surprising-gateway -am test`；结果：1744 tests，0 failures，0 errors，32 skipped。
- 跳过范围：`MaintenanceIntegrationTest` 的 10 项因缺少 `MAINTENANCE_TEST_JDBC_URL` 跳过；`CustodyWithdrawalReconciliationPostgresTest` 的 22 项因缺少 `SURPRISING_WITHDRAWAL_IT_DATABASE_URL` 跳过。未启动 wallet、集群、JMH 或 JFR；本轮仅做编译与受影响模块测试。
- 异常摘要：测试中打印的 topic/key mismatch 和 Mockito 动态 agent 警告均为既有测试行为/运行时警告，不构成失败。
- 产物：本轮 Maven `target`、Surefire 报告及临时测试产物已在记录后清理；原始产物不保留，不能将已删除路径作为后续证据。

## 2026-09-15 Aeron service 命令职责拆分验证

- 变更：将余额、持仓、杠杆、风险、强平、ADL、保险基金、资金费、结算、费率、币对维护和触发单命令入口按业务职责归入 `service.command.*`；新增窄 `*CommandContext`、直接命令路由和触发单路由；将下单/批量/撮合命令值类型归入 `command.order`。账户 Lane 工作类继续保留在 `service.state`，避免扩大状态层内部 API。
- 环境：Oracle GraalVM 25.0.1（HotSpot 25.0.1+8，Java 25）、Maven 3.9.16；测试前可用磁盘约 507 GiB。
- 编译命令：`mvn -pl surprising-aeron-core/surprising-aeron-service -am -DskipTests compile`；结果：7 个 reactor 模块全部 `BUILD SUCCESS`，无编译错误，仅有既有 deprecated API 提示。
- 测试命令：`mvn -pl surprising-aeron-core/surprising-aeron-service -am test`；结果：1112 tests，0 failures，0 errors，0 skipped（product-api 12、aeron-protocol 107、aeron-client 49、instrument-api 12、aeron-service 932）。
- 异常摘要：Aeron client close interrupted、SLF4J 无 provider、Unsafe deprecated 和 ClusterFatalErrorTest 的故障注入日志均为测试/运行时警告；相关测试均通过。
- 未验证范围：未启动真实 Aeron Cluster、未执行 JMH/JFR、未做三节点吞吐/尾延迟/资金压测；本轮是类边界与功能回归验证，不形成交易主链路性能验收结论。
- 产物：已先记录本轮结果；随后清理本轮 Maven `target`、Surefire 报告、测试 fault-agent jar 及临时日志/报告。原始产物不保留，不能将已删除路径作为后续证据。

## 2026-09-15 command.support 工具迁移复测

- 变更：将 `PrimitiveLongChangeSet` 从 `orchestration` 移入 `command.support`，解除触发单命令包对 owner 编排包的反向依赖；仅将跨包读取/转换方法提升为公开只读 API。
- 环境：Oracle GraalVM 25.0.1（HotSpot 25.0.1+8，Java 25）、Maven 3.9.16；测试前可用磁盘约 506 GiB。
- 首次测试命令：`mvn -pl surprising-aeron-core/surprising-aeron-service -am test`；编译阶段失败，9 个访问级别错误，原因是迁移后的 `toImmutableList`、`valueAt`、`toPrimitiveArray` 仍为包私有方法；未进入测试执行。
- 修复：将上述工具类只读读取/转换方法提升为 `public`，未改变业务语义或状态所有权。
- 复测命令：`mvn -pl surprising-aeron-core/surprising-aeron-service -am test`；结果：7 个 reactor 模块全部 `BUILD SUCCESS`，1112 tests，0 failures，0 errors，0 skipped（product-api 12、aeron-protocol 107、aeron-client 49、instrument-api 12、aeron-service 932）。
- 异常摘要：Aeron client close interrupted、SLF4J 无 provider、Unsafe deprecated 和故障注入日志均为测试/运行时警告；相关测试均通过。
- 未验证范围：未启动真实 Aeron Cluster、未执行 JMH/JFR、未做三节点吞吐/尾延迟/资金压测；本轮不形成交易主链路性能验收结论。
- 产物：记录完成后清理本轮 Maven `target`、Surefire 报告、测试 fault-agent jar 及临时日志/报告；原始产物不保留，不能将已删除路径作为后续证据。

## 2026-09-15 state 职责分包验证

- 变更：将查询服务迁入 `service.state.query`，将快照值/编解码器迁入 `service.state.snapshot`，将纯合约与舍入计算迁入 `service.state.math`；保留 `TradingRuntimeState`、Account Lane、Reducer、撮合结算和风险处理的包级所有权，未复制运行时状态或增加交易线程/事件阶段。同步更新 service 调用方、测试导入和 Aeron Core 中文 README。
- 环境：Oracle GraalVM 25.0.1（HotSpot 25.0.1+8，Java 25）、Maven 3.9.16；测试前可用磁盘约 505 GiB。
- 编译命令：`mvn -pl surprising-aeron-core/surprising-aeron-service -am -DskipTests compile`；结果：7 个 reactor 模块 `BUILD SUCCESS`。迁移中首次编译发现旧包导入及包级可见性错误，已修复后重新编译通过。
- 定向测试：查询、快照编解码和数学类共 16 项通过。
- 回归命令：`mvn -pl surprising-aeron-core/surprising-aeron-service -am test`；结果：product-api 12、aeron-protocol 107、aeron-client 49、instrument-api 12、aeron-service 932，共 1112 tests，0 failures，0 errors，0 skipped。
- 异常摘要：Aeron 故障注入测试输出的 heartbeat/driver timeout、SLF4J provider、Unsafe deprecated、client close interrupted 等均为既有测试警告，相关测试通过；未发现由本次包迁移引起的失败。
- 未验证范围：未启动 wallet 或真实三节点 Aeron Cluster，未执行 JMH/JFR、吞吐/尾延迟、资金压测；本轮仅验证包边界、编译、快照/查询/数学行为和 service 回归，不形成性能验收结论。
- 产物：已先记录本轮结果；随后清理本轮 Maven `target`、Surefire 报告、测试 fault-agent jar 及临时日志/报告。原始产物不保留，不能将已删除路径作为后续证据。
