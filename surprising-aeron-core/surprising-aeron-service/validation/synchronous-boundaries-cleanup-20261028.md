# Aeron 同步边界与测试专用入口清理验证（2026-10-28）

范围：本轮确认的七组冗余逻辑，涉及 18 个 service 生产 Java 文件，生产源码净减少 512 行；未新增生产类、线程或兼容层。实际改动说明见 [service README](../README.md)。删除 `ScopeClose`、`StateHashes`、`SnapshotEncoder`、`MatcherSnapshotCapture` 四个内部类型。

账本同步记账及资金核对直接完成或抛错；保险预留跨账户阶段的财务作用域、资金守恒、持仓索引撤销和提交后复用条件保留。快照仍等待 Matcher 真实捕获，Owner 直接审计和编码；释放等待屏障不会提前结束 Matcher 捕获。引擎哈希算法、协议和快照格式不变。

## 环境与结果

- HotSpot Corretto JDK 27（27+33-FR），Maven 3.9.16；执行前磁盘可用约 195 GiB。
- 最终 14 个相关模块干净构建成功，最终命令退出码 0。
- 合并最后一批定向测试和单项复验：30 个测试类的 326 个不同用例全部通过，0 个未解决失败、0 个跳过，不重复累计复验。
- 覆盖六产品线成交、余额冻结/释放、手续费、保险、强平、ADL、资金费、交割/期权结算、持仓索引、拒绝回滚、身份引用隔离、快照预算/故障/恢复及日志续接。
- 首轮遇到两个测试整数用户 ID 误选 Lane 下标重载，以及故障测试清理异常。已改为明确的 long 用户 ID。故障用例最初误用每次通知查询都会立即检查的账户故障；改为现有提交发布故障后，验证空轮询健康检查间隔和实际命令立即拒绝。最终生产逻辑的 326 项批次中 325 项通过，最后一项修正测试故障源后单独通过；此后生产逻辑未改，只修正健康检查注释。
- 删除类型的旧 class 经干净构建清除；`git diff --check` 通过。
- CodeGraph 工具不可用，本轮根据源码与实际调用点核对，未声称使用图索引。

## 实际验证命令

编译迁移阶段使用 `mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am test-compile -DskipTests`，14 个模块通过。最终生产代码的定向批次如下；该次退出码 1，仅剩上述故障源迁移测试失败：

```bash
mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am clean test \
  '-Dtest=SystemLedgerControlTest,SystemLedgerFundsValidationTest,RuntimeTreasuryDeltaTest,LedgerPositionPublicationTest,RuntimeChangedIndexCommitTest,CommittedIndexVisitTest,RuntimeIdentityRegistryTest,LaneClientIdentitiesTest,SectionedCoreSnapshotCodecTest,SectionedSnapshotBudgetTest,CoreShutdownFailureTest,CoreNativeSnapshotProductLineTest,ProductRecoveryLifecycleTest,DeterministicExchangeCoreAdapterTest,TradingStateSnapshotCodecTest,CoreRuntimeConfigurationTest,CoreMatchingStateTest,CorePerpetualFinancialMatrixTest,CoreDeliveryOptionFinancialMatrixTest,AsyncFundingCommandTest,CoreLedgerQueryTest,CoreAccountLaneQueryTest,CoreRiskLaneQueryTest,CoreAlgoLaneQueryTest,CorePreflightLaneQueryTest,ActiveOrderIndexTest,RuntimeCommitRecoveryTest,MatcherTreasurySettlementTest,TradingCoreOwnerTest#emptyPollingChecksSilentFailureWithinOneMillisecondAndCommandsNeverSkipHealth+terminalPreparationFailureRollsBackOnLaneAndRetainsRejection+stageProgressAndReentrantBackgroundDeferSnapshotUntilSettlementCompletes+loadsOneByteSnapshotFragmentsThroughBoundedSectionRecovery+emptyAndIncompleteFragmentSourcesFailBeforeStateReplacement+rejectsSnapshotFragmentsBeyondBoundedRecoveryBuffer+doesNotReplaceStateAfterCorruptSnapshot+pairedManifestMismatchFailsBeforeLiveStateReplacement+propagatesFatalMatcherDivergenceFromSnapshotCallback+snapshotWaitsForAsynchronousMatcherCaptureAndReleasesCommandAdmission+snapshotCaptureTimeoutIsFailClosed+backgroundAndSnapshotCannotCommitWorkOutsideALogCallback+restoresSuccessfulSnapshotRoundTripWithoutTimingPoll,CoreOrderedOrderBatchTest#sequentialPlaceUsesEarlierFillProceedsOnPermanentLanes+sequentialPlaceBatchAdmitsAndRejectsFromLanesWithoutOwnerAccountReads' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

修正测试故障源后，用下面命令干净重建全部 14 个相关模块，补验该项；退出码 0：

```bash
mvn -pl surprising-aeron-core/surprising-aeron-benchmarks -am clean test \
  '-Dtest=TradingCoreOwnerTest#emptyPollingChecksSilentFailureWithinOneMillisecondAndCommandsNeverSkipHealth' \
  -Dsurefire.failIfNoSpecifiedTests=false

git diff --check
```

## 合并后的定向用例

| 测试类 | 通过用例 |
| --- | ---: |
| `ActiveOrderIndexTest` | 11 |
| `AsyncFundingCommandTest` | 4 |
| `CommittedIndexVisitTest` | 1 |
| `CoreAccountLaneQueryTest` | 8 |
| `CoreAlgoLaneQueryTest` | 5 |
| `CoreDeliveryOptionFinancialMatrixTest` | 29 |
| `CoreLedgerQueryTest` | 8 |
| `CoreMatchingStateTest` | 51 |
| `CoreNativeSnapshotProductLineTest` | 6 |
| `CoreOrderedOrderBatchTest` | 2 |
| `CorePerpetualFinancialMatrixTest` | 42 |
| `CorePreflightLaneQueryTest` | 9 |
| `CoreRiskLaneQueryTest` | 10 |
| `CoreRuntimeConfigurationTest` | 5 |
| `CoreShutdownFailureTest` | 2 |
| `DeterministicExchangeCoreAdapterTest` | 27 |
| `LaneClientIdentitiesTest` | 6 |
| `LedgerPositionPublicationTest` | 4 |
| `MatcherTreasurySettlementTest` | 2 |
| `ProductRecoveryLifecycleTest` | 6 |
| `RuntimeChangedIndexCommitTest` | 4 |
| `RuntimeCommitRecoveryTest` | 18 |
| `RuntimeIdentityRegistryTest` | 12 |
| `RuntimeTreasuryDeltaTest` | 13 |
| `SectionedCoreSnapshotCodecTest` | 15 |
| `SectionedSnapshotBudgetTest` | 1 |
| `SystemLedgerControlTest` | 5 |
| `SystemLedgerFundsValidationTest` | 1 |
| `TradingCoreOwnerTest` | 13 |
| `TradingStateSnapshotCodecTest` | 6 |

## 未执行范围与清理

按要求未执行全量测试、压测、JMH/JFR；benchmarks 模块仅编译。未启动真实集群、wallet 或进行 HTTP/WebSocket 前后端联调，公开协议和前端接口未改。不据功能测试结果声明性能提升。

验证摘要归档后清理本轮临时脚本、日志和一次故障诊断线程转储；保留 Maven 的最终复验报告。本轮未改变 Git 全局或仓库代理配置。
