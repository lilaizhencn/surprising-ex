package com.surprising.aeron.service.core;

import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.model.CoreFeePolicyState;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.AdjustInsuranceFundCommand;
import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.ContinueRiskScanCommand;
import com.surprising.aeron.protocol.CoreCommandResultCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.ExecuteAdlCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.service.account.TransferRuntime;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.logbuffer.Header;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32C;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实交易运行时的快照、恢复与相同命令重放测试，部分用例用进程内 Cluster/会话代理走 Owner 回调入口。覆盖账户、Matcher、账本、幂等、来源水位及派生索引的一致性；它不是三节点 Aeron 网络故障或性能验收。
 */
class RuntimeCommitRecoveryTest {

    /**
     * 按非排序插入顺序恢复四笔待完成划转，快照再恢复后检查业务哈希相同且导出 ID 按一、九、十七、二百五十七升序。
     */
    @Test
    void multiplePendingTransfersKeepCanonicalOrderAcrossRestart() {
        try (/* 未中断的原运行时，或提供初始恢复快照的原运行时 */ var original = new TradingCoreRuntime(ProductLine.SPOT)) {
            // 按非排序 ID 构造的原待完成划转对照表
            var transfers = new java.util.HashMap<Long, TransferRuntime>();
            for (long /* 当前遍历的原划转 ID 或快照段编号 */ id : new long[]{17, 1, 257, 9}) {
                transfers.put(id, new TransferRuntime(1001, new TransferFundsCommand(id, ProductLine.SPOT,
                        ProductLine.LINEAR_PERPETUAL, "FUNDING", "USDT_PERPETUAL", "USDT", 25,
                        "ordered-" + id, "recovery", 1001, 1001)));
            }
            original.runtimeState.restorePendingTransfers(transfers);
            // 当前对照业务哈希或按账户段累计的摘要
            long hash = original.stateHash();
            try (/* 当前恢复的 Owner 测试入口或交易运行时 */ var recovered = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, original.snapshot())) {
                assertThat(recovered.stateHash()).isEqualTo(hash);
                assertThat(recovered.pendingTransfers().keySet()).containsExactly(1L, 9L, 17L, 257L);
                assertThat(recovered.pendingTransfers()).isEqualTo(transfers);
            }
        }
    }

    /**
     * 把费用政策及待完成划转与原冷状态一起恢复，继续同一余额命令并比较响应、资金与投影序号；再次快照恢复后辅助状态仍和当前候选一致。
     */
    @Test
    void restorePublishesAuxiliaryStateWithTheCanonicalCandidate() {
        // 与主状态一同恢复的原费用政策
        CoreFeePolicyState feePolicy = new CoreFeePolicyState(
                91, 3, 1001, "1", -25, 75, 2, true, 900, 2_000);
        // 与主状态一同恢复的原待完成跨产品划转
        TransferRuntime transfer = new TransferRuntime(1001, new TransferFundsCommand(
                701, ProductLine.LINEAR_PERPETUAL, ProductLine.SPOT,
                "USDT_PERPETUAL", "SPOT", "USDT", 125, "recovery-701", "snapshot parity", 1001L, 1001L));
        try (/* 未中断的原运行时，或提供初始恢复快照的原运行时 */ TradingCoreRuntime original = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            original.runtimeState.restoreFeePolicies(Map.of(feePolicy.policyId(), feePolicy));
            original.runtimeState.restorePendingTransfers(Map.of(transfer.transferId(), transfer));
            // 保存快照前原运行时业务哈希
            long originalStateHash = original.stateHash();
            try (/* 由原完整快照恢复的独立运行时 */ TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(
                    ProductLine.LINEAR_PERPETUAL, original.snapshot(700))) {
                assertThat(restored.feePolicies()).containsExactlyEntriesOf(original.feePolicies());
                assertThat(restored.pendingTransfers()).containsExactlyEntriesOf(original.pendingTransfers());
                assertThat(restored.stateHash()).isEqualTo(originalStateHash);
                assertThat(restored.snapshotProjectionSequence()).isEqualTo(original.snapshotProjectionSequence());
                // 快照之后两份运行时都必须继续执行的同一原命令清单
                List<CoreMessage> postSnapshot = List.of(
                        command(1, 1001, CoreMessageType.ADJUST_BALANCE, balance(500)));
                // 原运行时执行快照后命令得到的响应对照
                ReplayResult originalReplay = replay(original, postSnapshot);
                // 恢复运行时执行同一清单得到的响应对照
                ReplayResult restoredReplay = replay(restored, postSnapshot);
                assertParity(restored, original, restoredReplay, originalReplay);
                assertThat(restoredReplay.responses().getFirst().appliedCommandCount())
                        .isEqualTo(restored.appliedCommandCount());
                assertThat(restored.tradingState().user(1001).totalUnits("USDT")).isEqualTo(500);
                assertThat(restored.feePolicies()).containsEntry(feePolicy.policyId(), feePolicy);
                assertThat(restored.pendingTransfers()).containsEntry(transfer.transferId(), transfer);
                try (/* 再次从恢复运行时快照构造的第三份运行时 */ TradingCoreRuntime recoveredAgain = TradingCoreRuntime.fromSnapshot(
                        ProductLine.LINEAR_PERPETUAL, restored.snapshot(704))) {
                    assertThat(recoveredAgain.stateHash()).isEqualTo(restored.stateHash());
                    assertThat(recoveredAgain.tradingState().user(1001).totalUnits("USDT")).isEqualTo(500);
                    assertThat(recoveredAgain.feePolicies()).containsEntry(feePolicy.policyId(), feePolicy);
                    assertThat(recoveredAgain.pendingTransfers()).containsEntry(transfer.transferId(), transfer);
                    assertThat(recoveredAgain.snapshotBusinessStateHash())
                            .isEqualTo(restored.snapshotBusinessStateHash());
                }
            }
        }
    }

    /**
     * 重复十轮比较未中断运行时与两个快照位置恢复的运行时：部分成交、补仓、撤单、资金费、全部平仓及后续强平响应和状态一致；重复原命令不推进投影，变载荷同 UUID 冲突。
     */
    @org.junit.jupiter.api.RepeatedTest(10)
    void replayAfterRestoreProducesIdenticalResponsesAndState() {
        // 使初始十份挂单先成交四份的原消息清单
        List<CoreMessage> partialFill = List.of(
                command(4, 22, CoreMessageType.PLACE_ORDER, place(202, CoreOrderSide.BUY, 100, 4, false)));
        // 继续成交余六、另挂撤单并结算资金费的原消息清单
        List<CoreMessage> middle = List.of(
                command(5, 33, CoreMessageType.ADJUST_BALANCE, balance(2_000)),
                command(6, 33, CoreMessageType.PLACE_ORDER, place(303, CoreOrderSide.BUY, 100, 6, false)),
                command(7, 11, CoreMessageType.PLACE_ORDER, place(102, CoreOrderSide.SELL, 110, 2, false)),
                command(8, 11, CoreMessageType.CANCEL_ORDER,
                        TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(102))),
                operationsCommand(2, CoreMessageType.APPLY_FUNDING,
                        TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(501, "1", 10_000))));
        // 让三用户各自原持仓全部平掉的原消息清单
        List<CoreMessage> tail = List.of(
                command(9, 11, CoreMessageType.PLACE_ORDER, place(401, CoreOrderSide.BUY, 100, 10, false)),
                command(10, 22, CoreMessageType.PLACE_ORDER, place(402, CoreOrderSide.SELL, 100, 4, true)),
                command(11, 33, CoreMessageType.PLACE_ORDER, place(403, CoreOrderSide.SELL, 100, 6, true)));
        try (/* 从头继续执行或作为所有恢复位置参考的原运行时/Owner 入口 */ TradingCoreRuntime uninterrupted = seededLinearPerpetual()) {
            // 本次用于再次恢复的快照字节；索引规范化中为字段对照映射
            byte[] snapshot = uninterrupted.snapshot(701);
            try (/* 初始挂单快照恢复的第一份运行时 */ TradingCoreRuntime firstRestore = TradingCoreRuntime.fromSnapshot(ProductLine.LINEAR_PERPETUAL, snapshot)) {
                // 初次快照围栏的投影序号，恢复后必须一致
                long fenceProjectionSequence = uninterrupted.snapshotProjectionSequence();
                assertThat(firstRestore.snapshotProjectionSequence()).isEqualTo(fenceProjectionSequence);
                // 原运行时部分成交阶段响应清单
                ReplayResult uninterruptedPartial = replay(uninterrupted, partialFill);
                // 第一恢复运行时相同部分成交阶段响应清单
                ReplayResult restoredPartial = replay(firstRestore, partialFill);
                assertParity(firstRestore, uninterrupted, restoredPartial, uninterruptedPartial);
                assertThat(uninterrupted.tradingState().order(101).status()).isEqualTo(CoreOrderStatus.OPEN);
                assertThat(uninterrupted.tradingState().order(101).executedQuantitySteps()).isEqualTo(4);
                assertThat(uninterrupted.tradingState().order(101).remainingQuantitySteps()).isEqualTo(6);
                assertThat(uninterrupted.tradingState().user(11).balances().get("USDT").lockedUnits())
                        .isPositive();
                // 原运行时中段成交、撤单及资金费响应
                ReplayResult uninterruptedMiddle = replay(uninterrupted, middle);
                // 第一恢复运行时相同中段原消息响应
                ReplayResult restoredMiddle = replay(firstRestore, middle);
                assertParity(firstRestore, uninterrupted, restoredMiddle, uninterruptedMiddle);
                assertThat(uninterrupted.tradingState().orders()).doesNotContainKeys(101L, 202L, 102L);
                assertThat(uninterrupted.terminalRetention().containsOrder(101, 11, "")).isTrue();
                // 原部分成交响应中复制并解码的 taker 已成交订单视图
                var partialOrder = CoreCommandResultCodec.decode(HexFormat.of().parseHex(
                        uninterruptedPartial.responses().getFirst().data())).orders().getFirst();
                assertThat(partialOrder.orderId()).isEqualTo(202);
                assertThat(partialOrder.status()).isEqualTo("FILLED");
                assertThat(partialOrder.executedQuantitySteps()).isEqualTo(4);
                // 中段撤单响应中复制并解码的原订单视图
                var canceledOrder = CoreCommandResultCodec.decode(HexFormat.of().parseHex(
                        uninterruptedMiddle.responses().get(3).data())).orders().getFirst();
                assertThat(canceledOrder.orderId()).isEqualTo(102);
                assertThat(canceledOrder.status()).isEqualTo("CANCELED");
                assertThat(uninterrupted.tradingState().treasuryState().fundingSettlements()).containsEntry("1", 501L);
                assertThat(economicUsdt(uninterrupted.tradingState())).isEqualTo(6_000);

                // 中段结束后原运行时的第二份恢复快照
                byte[] secondSnapshot = uninterrupted.snapshot(702);
                try (/* 从中段快照恢复、仅继续尾部的第三份运行时 */ TradingCoreRuntime secondRestore = TradingCoreRuntime.fromSnapshot(
                        ProductLine.LINEAR_PERPETUAL, secondSnapshot)) {
                    // 原运行时全部平仓阶段响应
                    ReplayResult uninterruptedTail = replay(uninterrupted, tail);
                    // 初始快照恢复运行时相同平仓阶段响应
                    ReplayResult firstRestoreTail = replay(firstRestore, tail);
                    // 中段快照恢复运行时相同平仓阶段响应
                    ReplayResult secondRestoreTail = replay(secondRestore, tail);
                    assertParity(firstRestore, uninterrupted, firstRestoreTail, uninterruptedTail);
                    assertParity(secondRestore, uninterrupted, secondRestoreTail, uninterruptedTail);
                    assertThat(uninterrupted.tradingState().user(11).positions().get("1")
                            .signedQuantitySteps()).isZero();
                    assertThat(uninterrupted.tradingState().user(22).positions().get("1")
                            .signedQuantitySteps()).isZero();
                    assertThat(uninterrupted.tradingState().user(33).positions().get("1")
                            .signedQuantitySteps()).isZero();
                    assertThat(uninterrupted.tradingState().orders()).isEmpty();
                    assertThat(uninterrupted.tradingState().users().values()).allSatisfy(/* 当前资金守恒或全部释放断言中的冷用户 */ user -> {
                        assertThat(user.reservations()).isEmpty();
                        assertThat(user.balances().get("USDT").lockedUnits()).isZero();
                    });
                    assertThat(economicUsdt(uninterrupted.tradingState())).isEqualTo(6_000);

                    // 原运行时后续强平阶段响应
                    ReplayResult uninterruptedLiquidation = liquidate(uninterrupted);
                    // 第一恢复运行时相同强平阶段响应
                    ReplayResult firstRestoreLiquidation = liquidate(firstRestore);
                    // 第二恢复运行时相同强平阶段响应
                    ReplayResult secondRestoreLiquidation = liquidate(secondRestore);
                    assertParity(firstRestore, uninterrupted, firstRestoreLiquidation, uninterruptedLiquidation);
                    assertParity(secondRestore, uninterrupted, secondRestoreLiquidation, uninterruptedLiquidation);
                    assertThat(uninterrupted.tradingState().riskState().liquidations().values())
                            .anyMatch(/* 当前待过滤或规范化的业务值，索引反射循环中为原字段 */ value -> value.status() == CoreLiquidationState.Status.INSURANCE_REQUIRED
                                    && value.deficitUnits() > 0);
                    assertThat(uninterrupted.tradingState().treasuryState().insuranceDeficits()
                            .getOrDefault("USDT", 0L)).isPositive();
                }

                // 已成功执行过的原部分成交消息，用于核对幂等
                CoreMessage duplicateCommand = partialFill.getFirst();
                // 重复或冲突原消息前保存的投影序号
                long projectionBeforeDuplicate = firstRestore.snapshotProjectionSequence();
                // 原消息重放得到的重复终态
                CoreResponse duplicate = firstRestore.apply(duplicateCommand);
                assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(duplicate.resultCode()).isEqualTo(restoredPartial.responses().getFirst().resultCode());
                assertThat(HexFormat.of().formatHex(duplicate.data()))
                        .isEqualTo(restoredPartial.responses().getFirst().data());
                assertThat(firstRestore.snapshotProjectionSequence()).isEqualTo(projectionBeforeDuplicate);
                // 同 UUID 但修改原下单数量的冲突重试
                CoreMessage changedRetry = new CoreMessage(duplicateCommand.header(),
                        place(202, CoreOrderSide.BUY, 100, 5, false));
                assertThat(firstRestore.apply(changedRetry).resultCode())
                        .isEqualTo(CoreResultCode.IDEMPOTENCY_CONFLICT);
                assertThat(firstRestore.snapshotProjectionSequence()).isEqualTo(projectionBeforeDuplicate);
            }
        }
    }

    /**
     * 分别破坏 Lane 用户清单、外层校验和、业务哈希、投影序号和 Matcher 内层载荷，检查解码拒绝且原发布引用、哈希和投影不变；只按旧 projector 线程名前缀检查数量未增加，不证明所有资源都无泄漏。
     */
    @Test
    void rejectsCorruptLaneAndHashSnapshotAtomically() {
        try (/* 未中断的原运行时，或提供初始恢复快照的原运行时 */ TradingCoreRuntime original = seededLinearPerpetual()) {
            // 本次用于再次恢复的快照字节；索引规范化中为字段对照映射
            byte[] snapshot = original.snapshot(703);
            // 破坏快照测试前原运行时业务哈希
            long stateHash = original.stateHash();
            // 破坏快照测试前原投影序号
            long projectionSequence = original.snapshotProjectionSequence();
            // 按指定旧线程名前缀统计的破坏测试前存活数
            long projectorThreads = projectorThreadCount();
            // 只有候选解码成功后才替换的原运行时发布引用
            AtomicReference<TradingCoreRuntime> published = new AtomicReference<>(original);
            // 用户清单已改且 Lane 摘要/外层 CRC 保持相符的坏快照
            byte[] corruptLaneManifest = mutateLaneUserPreservingDigest(snapshot, original.laneTopology());
            // 仅破坏载荷、不维护外层 CRC 的坏快照
            byte[] corruptChecksum = snapshot.clone();
            corruptChecksum[SectionedCoreSnapshotCodec.ENVELOPE_LENGTH
                    + SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH + 20] ^= 1;
            // 改全局业务哈希并重写外层 CRC 的坏快照
            byte[] corruptBusinessHash = mutateLongInSection(snapshot, 1, 101, 1);
            // 改投影序号并重写外层 CRC 的坏快照
            byte[] corruptProjectionSequence = mutateLongInSection(snapshot, 1, 61, 1);
            // 改 Matcher 内层载荷且只维护外层 CRC 的坏快照
            byte[] corruptMatcherPayload = mutateLongInSection(snapshot, 4, Long.BYTES, 1);

            assertThatThrownBy(() -> published.set(SectionedCoreSnapshotCodec.decode(
                    corruptLaneManifest, ProductLine.LINEAR_PERPETUAL)))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("user manifest differs from global state");
            assertThatThrownBy(() -> published.set(SectionedCoreSnapshotCodec.decode(
                    corruptChecksum, ProductLine.LINEAR_PERPETUAL)))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("checksum");
            assertThatThrownBy(() -> SectionedCoreSnapshotCodec.decode(
                    corruptBusinessHash, ProductLine.LINEAR_PERPETUAL))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("business state hash");
            assertThatThrownBy(() -> SectionedCoreSnapshotCodec.decode(
                    corruptProjectionSequence, ProductLine.LINEAR_PERPETUAL))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("projection sequence");
            assertThatThrownBy(() -> SectionedCoreSnapshotCodec.decode(
                    corruptMatcherPayload, ProductLine.LINEAR_PERPETUAL))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("matcher snapshot checksum mismatch");
            assertThat(original.stateHash()).isEqualTo(stateHash);
            assertThat(original.snapshotProjectionSequence()).isEqualTo(projectionSequence);
            assertThat(published.get()).isSameAs(original);
            assertThat(projectorThreadCount()).isEqualTo(projectorThreads);
        }
    }

    /**
     * 从同一配对订单簿快照创建两份 Owner 测试入口，执行两项成交批次，比较响应、持仓、预留释放、费用、索引与 Lane 围栏；原批次重复不改变投影。名称中的同步指测试线程推进辅助，并非禁用所有工作资源。
     */
    @Test
    void recoversPairedOrderBatchWithSynchronousOwnerMatching()
            throws Exception {
        // 本次两项配对成交的原批量消息
        CoreMessage batch = command(5, 1001, CoreMessageType.PLACE_ORDER_BATCH,
                TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                        batchPlace(82_001, "fatal-batch-first", CoreOrderSide.BUY, 100, 1),
                        batchPlace(82_002, "fatal-batch-second", CoreOrderSide.BUY, 100, 1)))));
        // 已包含两份 maker 挂单的原配对快照
        byte[] pairedSnapshot;
        try (/* 用于生成配对快照的原运行时，退出自动关闭 */ TradingCoreRuntime seed = seededPerpetualBatchState()) {
            pairedSnapshot = seed.snapshot(705);
        }

        // 从头继续执行或作为所有恢复位置参考的原运行时/Owner 入口
        TradingOwnerTestSupport uninterrupted = restoredService(pairedSnapshot);
        // 当前恢复的 Owner 测试入口或交易运行时
        TradingOwnerTestSupport recovered = restoredService(pairedSnapshot);
        try {
            // 同一配对快照恢复后首先执行的响应对照
            BatchReplay reference = completeBatch(uninterrupted, batch);
            // 同一快照另一份恢复入口执行的实际响应对照
            BatchReplay replayed = completeBatch(recovered, batch);

            assertThat(replayed.response()).isEqualTo(reference.response());
            assertBatchRecoveryParity(recovered.state(), uninterrupted.state());
            // 批量原响应解码出的逐项结果
            var items = TradingOrderBatchCodec.decodeResult(
                    HexFormat.of().parseHex(replayed.response().data())).items();
            assertThat(items).extracting(/* 当前批次子项，检查应用、恰一成交及无遗留订单正文 */ item -> item.orderId()).containsExactly(82_001L, 82_002L);
            assertThat(items).allSatisfy(/* 当前批次子项，检查应用、恰一成交及无遗留订单正文 */ item -> {
                assertThat(item.status()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(item.executions()).hasSize(1);
                assertThat(item.order()).isNull();
            });
            assertThat(recovered.state().tradingState().user(1001).balances().get("USDT").lockedUnits()).isPositive();
            assertThat(recovered.state().tradingState().user(1001).reservations()).isEmpty();
            assertThat(recovered.state().tradingState().user(2001).balances().get("USDT").lockedUnits()).isPositive();
            assertThat(recovered.state().tradingState().user(2001).reservations()).isEmpty();
            assertThat(recovered.state().tradingState().user(1001).positions().get("1").signedQuantitySteps())
                    .isEqualTo(2);
            assertThat(recovered.state().tradingState().user(2001).positions().get("1").signedQuantitySteps())
                    .isEqualTo(-2);
            assertThat(recovered.state().tradingState().orders()).isEmpty();
            assertThat(recovered.state().tradingState().clientOrderIndex()).isEmpty();
            assertThat(recovered.state().terminalRetention().containsOrder(82_001, 1001, "fatal-batch-first")).isTrue();
            assertThat(recovered.state().terminalRetention().containsOrder(82_002, 1001, "fatal-batch-second")).isTrue();
            assertThat(recovered.state().tradingState().riskState())
                    .isEqualTo(uninterrupted.state().tradingState().riskState());
            assertThat(recovered.state().tradingState().treasuryState())
                    .isEqualTo(uninterrupted.state().tradingState().treasuryState());
            assertThat(recovered.state().tradingState().treasuryState().feeBalances().get("USDT")).isPositive();
            assertThat(economicUsdt(recovered.state().tradingState())).isEqualTo(4_000);
            // 重复或冲突原消息前保存的投影序号
            long projectionBeforeDuplicate = recovered.state().snapshotProjectionSequence();
            // 原对照 Owner 入口收到的该批次重复终态响应
            CoreResponse referenceDuplicate = replayClusterCommand(uninterrupted, batch).response();
            // 恢复 Owner 入口收到的同批次重复终态响应
            CoreResponse recoveredDuplicate = replayClusterCommand(recovered, batch).response();
            assertThat(response(recoveredDuplicate)).isEqualTo(response(referenceDuplicate));
            assertThat(recoveredDuplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(recovered.state().snapshotProjectionSequence()).isEqualTo(projectionBeforeDuplicate);
            assertBatchRecoveryParity(recovered.state(), uninterrupted.state());
        } finally {
            uninterrupted.state().close();
            recovered.state().close();
        }

    }

    /**
     * 分别设置一或四个账户 Lane，以普通或批量强平执行同一保险和 ADL 快照场景，finally 恢复原 Lane 数系统属性。
     * @param lanes 本轮配置的账户 Lane 数，一或四
     * @param batch 是否采用批量强平执行；completeBatch 中为本条原批次消息
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1,false", "4,false", "1,true", "4,true"})
    void replaysInsuranceResolutionAndAdlAcrossPairedClusteredSnapshotCuts(int lanes, boolean batch) throws Exception {
        // 本轮开始前账户 Lane 数系统属性，finally 恢复
        String previous = System.getProperty("surprising.aeron.account-lanes");
        System.setProperty("surprising.aeron.account-lanes", Integer.toString(lanes));
        try { insuranceAndAdlSnapshotCuts(batch); }
        finally {
            if (previous == null) System.clearProperty("surprising.aeron.account-lanes");
            else System.setProperty("surprising.aeron.account-lanes", previous);
        }
    }

    /**
     * 准备资金费后低价风险场景，三页撤单强平形成亏空；在保险前后、ADL 后及全保险后分别恢复，核对部分保险二十五、剩余 ADL、错误目标回滚、重启续接与重复命令。资金变化只来自明确保险注资，ADL 不凭空改经济权益。
     * @param batch 是否采用批量强平执行；completeBatch 中为本条原批次消息
     */
    private void insuranceAndAdlSnapshotCuts(boolean batch) throws Exception {
        // 从头继续执行或作为所有恢复位置参考的原运行时/Owner 入口
        TradingOwnerTestSupport uninterrupted = new TradingOwnerTestSupport(ProductLine.LINEAR_PERPETUAL);
        uninterrupted.onStart(cluster(), null);
        try {
            // 按原顺序准备账户、资金费、挂单及低标记价的消息清单或其响应
            ReplayResult setup = replayClustered(uninterrupted, List.of(
                    operationsCommand(1, CoreMessageType.REGISTER_INSTRUMENT,
                            TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                                    "1", ContractType.LINEAR_PERPETUAL.ordinal(),
                                    "BTC", "USDT", "USDT", 1, 1, 1,
                                    100_000, 50_000, 0, 0, 0, -1, 0))),
                    kafkaCommand(1, CoreMessageType.APPLY_MARK_PRICE,
                            TradingCommandCodec.encodeApplyMarkPrice(
                                    new ApplyMarkPriceCommand("1", 100, 1, 1_000))),
                    command(1, 1, CoreMessageType.ADJUST_BALANCE, balance(110)),
                    command(2, 2, CoreMessageType.ADJUST_BALANCE, balance(1_000)),
                    command(3, 3, CoreMessageType.ADJUST_BALANCE, balance(100)),
                    command(4, 2, CoreMessageType.PLACE_ORDER,
                            place(91_001, CoreOrderSide.SELL, 100, 10, false)),
                    command(5, 1, CoreMessageType.PLACE_ORDER,
                            place(91_002, CoreOrderSide.BUY, 100, 10, false)),
                    command(6, 3, CoreMessageType.PLACE_ORDER,
                            place(91_003, CoreOrderSide.BUY, 1, 10, false)),
                    command(7, 1, CoreMessageType.PLACE_ORDER,
                            place(91_004, CoreOrderSide.SELL, 110, 1, true)),
                    command(8, 1, CoreMessageType.PLACE_ORDER,
                            place(91_005, CoreOrderSide.SELL, 110, 1, true)),
                    command(9, 1, CoreMessageType.PLACE_ORDER,
                            place(91_006, CoreOrderSide.SELL, 110, 1, true)),
                    operationsCommand(2, CoreMessageType.APPLY_FUNDING,
                            TradingCommandCodec.encodeApplyFunding(
                                    new ApplyFundingCommand(901, "1", 1_000))),
                    kafkaCommand(2, CoreMessageType.APPLY_MARK_PRICE,
                            TradingCommandCodec.encodeApplyMarkPrice(
                                    new ApplyMarkPriceCommand("1", 1, 2, 2_000)))));
            assertThat(uninterrupted.state().tradingState().treasuryState().fundingSettlements()).isNotEmpty();
            // 本测试运营来源当前继续使用的原序号
            long operationsSequence = 3;
            // 当前解码的原强平/风险扫描工作页
            CoreLiquidationWorkView work = liquidationWork(uninterrupted.state());
            while (work.riskScanPending()) {
                replayClustered(uninterrupted, List.of(operationsCommand(
                        operationsSequence++, CoreMessageType.CONTINUE_RISK_SCAN,
                        TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(1)))));
                work = liquidationWork(uninterrupted.state());
            }
            // 风险扫描为目标用户一分配的原计划强平
            CoreLiquidationState planned = uninterrupted.state().tradingState().riskState().liquidations()
                    .values().stream().filter(/* 当前待过滤或规范化的业务值，索引反射循环中为原字段 */ value -> value.userId() == 1).findFirst().orElseThrow();
            // 原强平分页执行次数，本场景恰三页
            int liquidationPages = 0;
            // 正在推进的原强平记录，用状态和撤单游标继续下一页
            CoreLiquidationState current = planned;
            while (current.status() == CoreLiquidationState.Status.PLANNED
                    || current.status() == CoreLiquidationState.Status.ORDERED) {
                assertThat(++liquidationPages).isLessThanOrEqualTo(3);
                // 本页待交 Owner 的原普通或批量强平消息
                CoreMessage executeLiquidation;
                if (batch) {
                    // 本页批量强平动作，或原工作页中选定用户的单项动作
                    var action = new com.surprising.aeron.protocol.ExecuteLiquidationBatchAction(
                            current.liquidationId(), current.userId(), current.instrumentId(), current.triggerPriceSequence(), 1, current.nextCancelOrderId());
                    executeLiquidation = operationsCommand(operationsSequence++, CoreMessageType.EXECUTE_LIQUIDATION_BATCH,
                            TradingCommandCodec.encodeExecuteLiquidationBatch(
                                    new com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand(List.of(action), 1, 0, null, 0)));
                } else {
                    executeLiquidation = operationsCommand(operationsSequence++, CoreMessageType.EXECUTE_LIQUIDATION,
                            TradingCommandCodec.encodeExecuteLiquidation(new ExecuteLiquidationCommand(
                                    current.liquidationId(), current.triggerPriceSequence(), 1, 0,
                                    current.nextCancelOrderId(), 1)));
                }
                // 原强平页执行前账户所有权交接代号
                long handoffBefore = uninterrupted.state().runtimeState.laneHandoffEpoch();
                replayClustered(uninterrupted, List.of(executeLiquidation));
                assertThat(uninterrupted.state().runtimeState.laneHandoffEpoch()).isEqualTo(handoffBefore);
                assertThat(uninterrupted.state().runtimeState.ownerLaneAccess()).isFalse();
                current = liquidation(uninterrupted.state(), planned.liquidationId());
            }
            assertThat(liquidationPages).isEqualTo(3);
            // 保险注资原消息在运营来源的固定序号
            long insuranceAdjustSequence = operationsSequence++;
            // 保险确认原消息在运营来源的固定序号
            long insuranceResolveSequence = operationsSequence++;
            // 本次原 ADL 消息在运营来源的固定序号
            long adlSequence = operationsSequence;
            // 执行完分页强平后需保险处理的原记录
            CoreLiquidationState insuranceRequired = liquidation(uninterrupted.state(), planned.liquidationId());
            // 保险前原亏空总额，预期大于二十五
            long deficit = insuranceRequired.deficitUnits();
            assertThat(deficit).isGreaterThan(25);
            assertThat(insuranceRequired.status()).isEqualTo(CoreLiquidationState.Status.INSURANCE_REQUIRED);
            assertThat(deficit(uninterrupted.state())).isEqualTo(deficit);
            // 按本夹具资金及未实现盈亏口径计算的保险前权益
            long economicBeforeInsurance = economicEquityUsdt(uninterrupted.state());
            // 原财库已有 USDT 保险余额，计算实际外部注资时扣除
            long initialInsurance = uninterrupted.state().tradingState().treasuryState()
                    .insuranceBalances().getOrDefault("USDT", 0L);

            // 原强平结束、尚未保险确认的恢复位置快照
            byte[] beforeInsurance = uninterrupted.state().snapshot(710);
            // 从保险前快照恢复的 Owner 测试入口
            TradingOwnerTestSupport restoredBeforeInsurance = restoredService(beforeInsurance);
            try {
                assertBatchRecoveryParity(restoredBeforeInsurance.state(), uninterrupted.state());
                // 把保险余额补到二十五、再确认只覆盖二十五的两条原消息
                List<CoreMessage> partialInsurance = List.of(
                        operationsCommand(insuranceAdjustSequence, CoreMessageType.ADJUST_INSURANCE_FUND,
                                TradingCommandCodec.encodeAdjustInsuranceFund(
                                        new AdjustInsuranceFundCommand("USDT", 25 - initialInsurance))),
                        operationsCommand(insuranceResolveSequence, CoreMessageType.RESOLVE_LIQUIDATION,
                                TradingCommandCodec.encodeResolveLiquidation(new ResolveLiquidationCommand(
                                        planned.liquidationId(), ResolveLiquidationCommand.Resolution.INSURANCE, 25))));
                replayClustered(uninterrupted, List.of(partialInsurance.getFirst()));
                replayClustered(restoredBeforeInsurance, List.of(partialInsurance.getFirst()));
                assertThat(com.surprising.aeron.service.treasury.InsuranceAllocationPolicy.isNext(
                        uninterrupted.state().tradingState(), planned.liquidationId())).isTrue();
                assertThat(com.surprising.aeron.service.treasury.InsuranceAllocationPolicy.expectedCoverage(
                        uninterrupted.state().tradingState(), planned.liquidationId())).isEqualTo(25);
                // 部分保险确认前的原账户交接代号，执行不得接管其他 Lane
                long insuranceHandoff = uninterrupted.state().runtimeState.laneHandoffEpoch();
                // 原入口执行部分保险确认的响应
                ReplayResult partialReference = replayClustered(uninterrupted, List.of(partialInsurance.getLast()));
                assertThat(uninterrupted.state().runtimeState.laneHandoffEpoch())
                        .as("insurance resolution must not take Lane ownership").isEqualTo(insuranceHandoff);
                // 保险前恢复入口执行相同确认的响应
                ReplayResult partialRecovered = replayClustered(restoredBeforeInsurance, List.of(partialInsurance.getLast()));
                assertThat(partialRecovered).isEqualTo(partialReference);
                assertBatchRecoveryParity(restoredBeforeInsurance.state(), uninterrupted.state());
                // 原亏空减二十五后的精确剩余亏空
                long residual = Math.subtractExact(deficit, 25);
                assertThat(liquidation(uninterrupted.state(), planned.liquidationId()).deficitUnits())
                        .isEqualTo(residual);
                assertThat(liquidation(uninterrupted.state(), planned.liquidationId()).status())
                        .isEqualTo(CoreLiquidationState.Status.ADL_REQUIRED);
                assertThat(deficit(uninterrupted.state())).isEqualTo(residual);
                assertThat(economicEquityUsdt(uninterrupted.state()))
                        .isEqualTo(Math.addExact(economicBeforeInsurance, 25 - initialInsurance));
                assertDuplicateClusterReplay(
                        uninterrupted, restoredBeforeInsurance, partialInsurance.getFirst());
                assertDuplicateClusterReplay(
                        uninterrupted, restoredBeforeInsurance, partialInsurance.getLast());

                // 部分保险后、等待 ADL 的原快照
                byte[] afterInsurance = uninterrupted.state().snapshot(711);
                // 从部分保险后快照恢复的 Owner 入口
                TradingOwnerTestSupport restoredAfterInsurance = restoredService(afterInsurance);
                try {
                    assertBatchRecoveryParity(restoredAfterInsurance.state(), uninterrupted.state());
                    // 按原目标持仓身份和价格构造的正确 ADL 消息
                    CoreMessage adl = operationsCommand(adlSequence, CoreMessageType.EXECUTE_ADL,
                            TradingCommandCodec.encodeExecuteAdl(new ExecuteAdlCommand(
                                    planned.liquidationId(), 2, "1", CoreMarginMode.CROSS,
                                    CorePositionSide.NET, -10, 100, 2, 10, residual)));
                    try (/* 从保险后快照单独恢复、用于错误 ADL 回滚验证的运行时 */ var rejectedState = TradingCoreRuntime.fromSnapshot(ProductLine.LINEAR_PERPETUAL, afterInsurance)) {
                        // 错误目标 ADL 前保存的完整冷状态
                        var beforeRejected = rejectedState.tradingState();
                        // 错误目标 ADL 前的原账户所有权交接代号
                        long handoff = rejectedState.runtimeState.laneHandoffEpoch();
                        // 将 ADL 预期价格改成一百零一的目标冲突消息
                        var invalid = operationsCommand(adlSequence, CoreMessageType.EXECUTE_ADL,
                                TradingCommandCodec.encodeExecuteAdl(new ExecuteAdlCommand(planned.liquidationId(),
                                        2, "1", CoreMarginMode.CROSS, CorePositionSide.NET, -10, 101, 2, 10, residual)));
                        // 错误目标 ADL 的原拒绝响应
                        var rejected = CoreTestCompletion.applyAsynchronously(rejectedState, invalid);
                        assertThat(rejected.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
                        assertThat(rejected.resultCode()).isEqualTo(CoreResultCode.ADL_POSITION_CONFLICT);
                        assertThat(rejectedState.runtimeState.laneHandoffEpoch()).isEqualTo(handoff);
                        assertThat(rejectedState.tradingState()).as("all participating Lanes roll back on target rejection")
                                .isEqualTo(beforeRejected);
                        try (/* 从拒绝后快照再次恢复的运行时，继续正确 ADL */ var again = TradingCoreRuntime.fromSnapshot(ProductLine.LINEAR_PERPETUAL, rejectedState.snapshot())) {
                            assertThat(again.tradingState()).isEqualTo(beforeRejected);
                            // 拒绝恢复后以新来源序号执行正确 ADL 的应用响应
                            var resumed = CoreTestCompletion.applyAsynchronously(again,
                                    operationsCommand(adlSequence + 1, CoreMessageType.EXECUTE_ADL, adl.payloadUnsafe()));
                            assertThat(resumed.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                            assertThat(liquidation(again, planned.liquidationId()).status())
                                    .isEqualTo(CoreLiquidationState.Status.COMPLETED);
                            assertThat(deficit(again)).isZero();
                            assertThat(economicEquityUsdt(again)).isEqualTo(economicEquityUsdt(rejectedState));
                        }
                    }
                    // 正确 ADL 前本夹具口径经济权益，减仓不应凭空改变它
                    long beforeAdlEconomic = economicEquityUsdt(uninterrupted.state());
                    // 正确 ADL 前原账户交接代号
                    long adlHandoff = uninterrupted.state().runtimeState.laneHandoffEpoch();
                    // 原入口执行正确 ADL 的响应清单
                    ReplayResult adlReference = replayClustered(uninterrupted, List.of(adl));
                    assertThat(uninterrupted.state().runtimeState.laneHandoffEpoch())
                            .as("ADL target and liquidation remain on their own Lanes").isEqualTo(adlHandoff);
                    // 保险前恢复并已继续保险的入口执行同一 ADL 响应
                    ReplayResult adlFromBeforeCut = replayClustered(restoredBeforeInsurance, List.of(adl));
                    // 从保险后恢复入口执行同一 ADL 响应
                    ReplayResult adlFromAfterCut = replayClustered(restoredAfterInsurance, List.of(adl));
                    assertThat(adlFromBeforeCut).isEqualTo(adlReference);
                    assertThat(adlFromAfterCut).isEqualTo(adlReference);
                    assertBatchRecoveryParity(restoredBeforeInsurance.state(), uninterrupted.state());
                    assertBatchRecoveryParity(restoredAfterInsurance.state(), uninterrupted.state());
                    assertThat(liquidation(uninterrupted.state(), planned.liquidationId()).deficitUnits()).isZero();
                    assertThat(deficit(uninterrupted.state())).isZero();
                    assertThat(liquidation(uninterrupted.state(), planned.liquidationId()).status())
                            .isEqualTo(CoreLiquidationState.Status.COMPLETED);
                    assertThat(uninterrupted.state().tradingState().user(2).positions().get("1")
                            .signedQuantitySteps()).isZero();
                    assertThat(economicEquityUsdt(uninterrupted.state())).isEqualTo(beforeAdlEconomic);

                    // ADL 完成后原快照
                    byte[] afterAdl = uninterrupted.state().snapshot(712);
                    // 从 ADL 完成后快照恢复的入口，用来检查重复与最终状态
                    TradingOwnerTestSupport restoredAfterAdl = restoredService(afterAdl);
                    try {
                        assertBatchRecoveryParity(restoredAfterAdl.state(), uninterrupted.state());
                        assertDuplicateClusterReplay(uninterrupted, restoredAfterAdl, adl);
                    } finally {
                        restoredAfterAdl.state().close();
                    }
                } finally {
                    restoredAfterInsurance.state().close();
                }
            } finally {
                restoredBeforeInsurance.state().close();
            }

            // 从保险前位置恢复、改走全保险覆盖的原对照入口
            TradingOwnerTestSupport fullReference = restoredService(beforeInsurance);
            // 从同一保险前位置恢复的另一全保险入口
            TradingOwnerTestSupport fullRecovered = restoredService(beforeInsurance);
            try {
                // 一次注足原亏空再全部保险确认的原消息清单
                List<CoreMessage> fullInsurance = List.of(
                        operationsCommand(insuranceAdjustSequence, CoreMessageType.ADJUST_INSURANCE_FUND,
                                TradingCommandCodec.encodeAdjustInsuranceFund(
                                        new AdjustInsuranceFundCommand("USDT", deficit - initialInsurance))),
                        operationsCommand(insuranceResolveSequence, CoreMessageType.RESOLVE_LIQUIDATION,
                                TradingCommandCodec.encodeResolveLiquidation(new ResolveLiquidationCommand(
                                        planned.liquidationId(), ResolveLiquidationCommand.Resolution.INSURANCE,
                                        deficit))));
                // 全保险原对照响应清单
                ReplayResult fullReferenceResult = replayClustered(fullReference, fullInsurance);
                // 另一恢复入口的全保险响应清单
                ReplayResult fullRecoveredResult = replayClustered(fullRecovered, fullInsurance);
                assertThat(fullRecoveredResult).isEqualTo(fullReferenceResult);
                assertBatchRecoveryParity(fullRecovered.state(), fullReference.state());
                assertThat(liquidation(fullReference.state(), planned.liquidationId()).deficitUnits()).isZero();
                assertThat(deficit(fullReference.state())).isZero();
                assertThat(liquidation(fullReference.state(), planned.liquidationId()).status())
                        .isEqualTo(CoreLiquidationState.Status.COMPLETED);
                assertThat(economicEquityUsdt(fullReference.state()))
                        .isEqualTo(Math.addExact(economicBeforeInsurance, deficit - initialInsurance));
                // 全保险覆盖后已完成强平的原快照
                byte[] afterFullInsurance = fullReference.state().snapshot(713);
                // 从全保险完成后快照恢复、核对幂等的入口
                TradingOwnerTestSupport restoredAfterFull = restoredService(afterFullInsurance);
                try {
                    assertBatchRecoveryParity(restoredAfterFull.state(), fullReference.state());
                    assertDuplicateClusterReplay(fullReference, restoredAfterFull, fullInsurance.getLast());
                } finally {
                    restoredAfterFull.state().close();
                }
            } finally {
                fullReference.state().close();
                fullRecovered.state().close();
            }
        } finally {
            uninterrupted.state().close();
        }
    }

    /**
     * 注册标的一 U 本位永续、标记价一百，给两用户各两千 USDT，挂出用户十一十份卖单，返回尚可继续成交的运行时。
     */
    private static TradingCoreRuntime seededLinearPerpetual() {
        // 本辅助构造并返回的原运行时
        TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL);
        apply(state, operationsCommand(1, CoreMessageType.REGISTER_INSTRUMENT,
                TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                        "1", ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT",
                        1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0))));
        apply(state, kafkaCommand(1, CoreMessageType.APPLY_MARK_PRICE,
                TradingCommandCodec.encodeApplyMarkPrice(
                        new ApplyMarkPriceCommand("1", 100, 1, 1_000))));
        apply(state, command(1, 11, CoreMessageType.ADJUST_BALANCE, balance(2_000)));
        apply(state, command(2, 22, CoreMessageType.ADJUST_BALANCE, balance(2_000)));
        apply(state, command(3, 11, CoreMessageType.PLACE_ORDER,
                place(101, CoreOrderSide.SELL, 100, 10, false)));
        return state;
    }

    /**
     * 建立带非零 maker/taker 费用的永续场景，两用户各两千，maker 挂两份独立一手卖单供配对批次恢复测试。
     */
    private static TradingCoreRuntime seededPerpetualBatchState() {
        // 本辅助构造并返回的原运行时
        TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL);
        apply(state, operationsCommand(1, CoreMessageType.REGISTER_INSTRUMENT,
                TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(
                        "1", ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT",
                        1, 1, 1, 100_000, 50_000, 10_000, 20_000, 0, -1, 0))));
        apply(state, kafkaCommand(1, CoreMessageType.APPLY_MARK_PRICE,
                TradingCommandCodec.encodeApplyMarkPrice(
                        new ApplyMarkPriceCommand("1", 100, 1, 1_000))));
        apply(state, command(1, 2001, CoreMessageType.ADJUST_BALANCE, balance(2_000)));
        apply(state, command(2, 1001, CoreMessageType.ADJUST_BALANCE, balance(2_000)));
        apply(state, command(3, 2001, CoreMessageType.PLACE_ORDER,
                TradingCommandCodec.encodePlaceOrder(
                        batchPlace(81_001, "paired-maker-first", CoreOrderSide.SELL, 100, 1))));
        apply(state, command(4, 2001, CoreMessageType.PLACE_ORDER,
                TradingCommandCodec.encodePlaceOrder(
                        batchPlace(81_002, "paired-maker-second", CoreOrderSide.SELL, 100, 1))));
        return state;
    }

    /**
     * 按本永续夹具口径精确汇总全部用户 USDT 可用加冻结，再加费用、保险、强平费及资金费/舍入残差并减保险亏空；不自动计入未实现盈亏或清算损益表。
     * @param state 本测试当前交易运行时或冷状态，沿其原所有权读取
     */
    private static long economicUsdt(com.surprising.aeron.model.TradingCoreState state) {
        // 按本夹具资金口径累计的 USDT 单位总额
        long total = 0;
        for (var /* 当前资金守恒或全部释放断言中的冷用户 */ user : state.users().values()) {
            // 当前用户的可空 USDT 冷余额
            var balance = user.balances().get("USDT");
            if (balance != null) total = Math.addExact(total,
                    Math.addExact(balance.availableUnits(), balance.lockedUnits()));
        }
        // 当前冷财库或原运行时财库，只按本辅助需要的分量读取
        var treasury = state.treasuryState();
        total = Math.addExact(total, treasury.feeBalances().getOrDefault("USDT", 0L));
        total = Math.addExact(total, treasury.insuranceBalances().getOrDefault("USDT", 0L));
        total = Math.subtractExact(total, treasury.insuranceDeficits().getOrDefault("USDT", 0L));
        total = Math.addExact(total, treasury.liquidationFeeBalances().getOrDefault("USDT", 0L));
        total = Math.addExact(total, treasury.fundingResidualBalances().getOrDefault("USDT", 0L));
        total = Math.addExact(total, treasury.roundingResidualBalances().getOrDefault("USDT", 0L));
        return total;
    }

    /**
     * 编码指定 USDT 增减，供本恢复夹具初始化资金。
     * @param amount 本次余额增减的原资产单位数
     */
    private static byte[] balance(long amount) {
        return balance("USDT", amount);
    }

    /**
     * 编码指定资产原单位数的余额调整。
     * @param asset 本次余额调整的原资产文本
     * @param amount 本次余额增减的原资产单位数
     */
    private static byte[] balance(String asset, long amount) {
        return TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, amount));
    }

    /**
     * 编码标的一跨仓净仓、限价 GTC 原订单，客户端单号由原订单号构造。
     * @param orderId 原订单业务号
     * @param side 原订单买卖方向
     * @param price 原限价价格 tick 数
     * @param quantity 原订单数量步数
     * @param reduceOnly 是否只允许减少既有持仓
     */
    private static byte[] place(long orderId, CoreOrderSide side, long price, long quantity,
                                boolean reduceOnly) {
        return TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(orderId, "1",
                side, price, quantity, reduceOnly, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "recovery-" + orderId));
    }

    /**
     * 构造带指定客户端单号的同类型批次原订单，不自行执行准入。
     * @param orderId 原订单业务号
     * @param clientOrderId 原客户端单号，保留用户输入文本
     * @param side 原订单买卖方向
     * @param price 原限价价格 tick 数
     * @param quantity 原订单数量步数
     */
    private static PlaceOrderCommand batchPlace(long orderId, String clientOrderId, CoreOrderSide side,
                                                long price, long quantity) {
        return new PlaceOrderCommand(orderId, "1", side, price, quantity, false,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, clientOrderId);
    }

    /**
     * 用网关来源七十七构造原用户命令。
     * @param sourceSequence 该来源内部原顺序号，不同来源各自独立
     * @param userId 原消息所属用户
     * @param type 原协议消息类型
     * @param payload 原业务载荷字节
     */
    private static CoreMessage command(long sourceSequence, long userId, CoreMessageType type, byte[] payload) {
        return sourcedCommand(CommandSource.GATEWAY, 77, sourceSequence, userId, type, payload);
    }

    /**
     * 用运营来源八十八、用户零构造原系统命令。
     * @param sourceSequence 该来源内部原顺序号，不同来源各自独立
     * @param type 原协议消息类型
     * @param payload 原业务载荷字节
     */
    private static CoreMessage operationsCommand(long sourceSequence, CoreMessageType type, byte[] payload) {
        return sourcedCommand(CommandSource.OPERATIONS, 88, sourceSequence, 0, type, payload);
    }

    /**
     * 用输入桥来源八十九、用户零构造原价格消息。
     * @param sourceSequence 该来源内部原顺序号，不同来源各自独立
     * @param type 原协议消息类型
     * @param payload 原业务载荷字节
     */
    private static CoreMessage kafkaCommand(long sourceSequence, CoreMessageType type, byte[] payload) {
        return sourcedCommand(CommandSource.KAFKA_INPUT_BRIDGE, 89, sourceSequence, 0, type, payload);
    }

    /**
     * 按来源名、来源序号、用户和消息类型生成可复现 UUID，使用固定永续产品线及随来源序号变化的请求时间；不同来源时钟可以倒退。
     * @param source 原命令来源枚举，各来源水位独立
     * @param sourceId 该原来源的实例 ID
     * @param sourceSequence 该来源内部原顺序号，不同来源各自独立
     * @param userId 原消息所属用户
     * @param type 原协议消息类型
     * @param payload 原业务载荷字节
     */
    private static CoreMessage sourcedCommand(CommandSource source, long sourceId, long sourceSequence,
                                               long userId, CoreMessageType type, byte[] payload) {
        // 由原来源、序号、用户及消息类型生成的可复现 UUID
        UUID commandId = UUID.nameUUIDFromBytes((source.name() + ':' + sourceSequence + ':' + userId + ':' + type)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new CoreMessage(CoreMessageHeader.command(type, commandId, ProductLine.LINEAR_PERPETUAL,
                source, sourceId, sourceSequence, userId, 1_000 + sourceSequence, sourceSequence), payload);
    }

    /**
     * 把原响应必要终态字段及数据十六进制复制为对照值，不把复用数据缓冲留在断言中。
     * @param response 需要复制或保存的原响应值
     */
    private static ResponseView response(CoreResponse response) {
        return new ResponseView(response.status(), response.commandStatus(), response.resultCode(),
                response.appliedCommandCount(),
                HexFormat.of().formatHex(response.data()));
    }

    /**
     * 先按原运行时执行，直接控制最多五秒续步；仍需撮合时分别以五秒预算等 Matcher 结果及结算完成。要求实际应用或幂等终态，不把 MATCHING_PENDING 当成功。
     * @param state 本测试当前交易运行时或冷状态，沿其原所有权读取
     * @param command 本次按原身份与载荷提交或重复重放的协议消息
     */
    private static CoreResponse apply(TradingCoreRuntime state, CoreMessage command) {
        // 本辅助当前取得、轮询或编码的原响应
        CoreResponse response = state.apply(command);
        // 本次直接控制最多五秒的单调时钟截止值
        long directDeadline = System.nanoTime() + 5_000_000_000L;
        while (response == null && state.directCommand.active() && System.nanoTime() < directDeadline) {
            response = state.directCommand.poll(state);
            if (response == null) Thread.onSpinWait();
        }
        if (response == null && state.directCommand.active()) {
            throw new AssertionError("Direct command timed out: " + command.header().messageType());
        }
        if (response != null && response.resultCode() != CoreResultCode.MATCHING_PENDING) {
            assertThat(response.status()).as(response.resultCode().name())
                    .isIn(ResponseStatus.APPLIED, ResponseStatus.DUPLICATE);
            return response;
        }
        // 本次原命令对应的 Core 撮合序号
        long sequence = state.matchingSequence(command.header().commandId());
        // 指定原序号的 Matcher 结果，尚未就绪时为空
        com.surprising.aeron.service.matcher.MatchingResult matching = null;
        // 当前撮合、结算或回调推进阶段的五秒截止时间，各阶段分别计算
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (matching == null && System.nanoTime() < deadline) {
            state.matchingProgress.drainMatchingCompletions();
            matching = state.takeMatchingResult(sequence);
            if (matching == null) Thread.onSpinWait();
        }
        assertThat(matching).as("matching completion for " + command.header().messageType()).isNotNull();
        // 本次原撮合命令已经满足结算条件后的终态响应
        CoreResponse completed = null;
        deadline = System.nanoTime() + 5_000_000_000L;
        while (completed == null && System.nanoTime() < deadline) {
            completed = state.completeMatching(sequence, matching,
                    command.header().submittedAtEpochMillis(), command.header().sourceSequence());
            if (completed == null) Thread.onSpinWait();
        }
        assertThat(completed).isNotNull();
        assertThat(completed.status()).isEqualTo(ResponseStatus.APPLIED);
        return completed;
    }

    /**
     * 在已恢复运行时继续补两用户、成交十份再压低标记价，续扫到有用户五十五强平动作后执行，按原顺序收集响应；续扫循环没有另设总页数上限。
     * @param state 本测试当前交易运行时或冷状态，沿其原所有权读取
     */
    private static ReplayResult liquidate(TradingCoreRuntime state) {
        // 本次按原顺序收集的独立响应对照或模拟会话响应字节清单
        ArrayList<ResponseView> responses = new ArrayList<>();
        // 按原顺序准备账户、资金费、挂单及低标记价的消息清单或其响应
        List<CoreMessage> setup = List.of(
                command(12, 44, CoreMessageType.ADJUST_BALANCE, balance(180)),
                command(13, 55, CoreMessageType.ADJUST_BALANCE, balance(180)),
                command(14, 44, CoreMessageType.PLACE_ORDER, place(501, CoreOrderSide.SELL, 100, 10, false)),
                command(15, 55, CoreMessageType.PLACE_ORDER, place(502, CoreOrderSide.BUY, 100, 10, false)),
                kafkaCommand(2, CoreMessageType.APPLY_MARK_PRICE,
                        TradingCommandCodec.encodeApplyMarkPrice(
                                new ApplyMarkPriceCommand("1", 80, 2, 2_000))));
        setup.forEach(/* 当前待提交的原协议消息 */ command -> {

            responses.add(response(apply(state, command)));
        });

        // 本测试运营来源当前继续使用的原序号
        long operationsSequence = 3;
        // 当前解码的原强平/风险扫描工作页
        CoreLiquidationWorkView work = liquidationWork(state);
        while (work.riskScanPending()) {
            // 继续风险扫描、每次预算一的原运营消息
            CoreMessage continuation = operationsCommand(operationsSequence++, CoreMessageType.CONTINUE_RISK_SCAN,
                    TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(1)));

            responses.add(response(apply(state, continuation)));
            work = liquidationWork(state);
        }
        // 本页批量强平动作，或原工作页中选定用户的单项动作
        var action = work.actions().stream().filter(/* 当前待过滤或规范化的业务值，索引反射循环中为原字段 */ value -> value.userId() == 55).findFirst().orElseThrow();
        // 原工作页中目标用户强平动作的执行消息
        CoreMessage execution = operationsCommand(operationsSequence, CoreMessageType.EXECUTE_LIQUIDATION,
                TradingCommandCodec.encodeExecuteLiquidation(new ExecuteLiquidationCommand(
                        action.liquidationId(), action.triggerPriceSequence(), action.markPriceTicks(), 100_000)));

        responses.add(response(apply(state, execution)));
        return new ReplayResult(List.copyOf(responses));
    }

    /**
     * 查询原强平执行工作，若内部挂起则最多五秒等待异步查询并检查健康，要求 OK 后解码。
     * @param state 本测试当前交易运行时或冷状态，沿其原所有权读取
     */
    private static CoreLiquidationWorkView liquidationWork(TradingCoreRuntime state) {
        // 查询当前强平执行页的原只读消息
        CoreMessage query = new CoreMessage(CoreMessageHeader.query(CoreMessageType.LIQUIDATION_WORK_QUERY,
                UUID.nameUUIDFromBytes("recovery-liquidation-work".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY, 77, 0, 0, 3_000, 0),
                CoreLiquidationWorkCodec.encodeQuery(ProductLine.LINEAR_PERPETUAL,
                        CoreLiquidationWorkView.Purpose.EXECUTION, 0, 100, 1_048_576));
        // 本辅助当前取得、轮询或编码的原响应
        CoreResponse response = state.apply(query);
        if (response.resultCode() == CoreResultCode.MATCHING_PENDING) {
            // 该原只读请求在异步查询队列中的序号
            long queryId = state.bookQueries.querySequence(query.header().commandId());
            // 当前撮合、结算或回调推进阶段的五秒截止时间，各阶段分别计算
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while ((response = state.bookQueries.takeQueryResult(queryId)) == null) {
                state.assertHealthy();
                if (System.nanoTime() >= deadline) throw new AssertionError("Liquidation query timeout");
                Thread.onSpinWait();
            }
        }
        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
        return CoreLiquidationWorkCodec.decodeWork(response.data());
    }

    /**
     * 比较本轮响应、空在途撮合、冷业务正文、业务及资金哈希、投影序号、幂等结果、来源水位、政策及划转，验证相同原命令在两份运行时得到相同结果。
     * @param actualState 待核对的恢复运行时
     * @param expectedState 作为对照的原运行时
     * @param actual 恢复运行时本轮响应对照
     * @param expected 未中断运行时本轮预期响应对照
     */
    private static void assertParity(TradingCoreRuntime actualState, TradingCoreRuntime expectedState,
                                     ReplayResult actual, ReplayResult expected) {
        assertThat(actual.responses()).isEqualTo(expected.responses());
        assertThat(actualState.pendingMatching().size()).isZero();
        assertThat(actualState.tradingState()).isEqualTo(expectedState.tradingState());
        assertThat(actualState.tradingState().users()).isEqualTo(expectedState.tradingState().users());
        assertThat(actualState.tradingState().orders()).isEqualTo(expectedState.tradingState().orders());
        assertThat(actualState.tradingState().riskState()).isEqualTo(expectedState.tradingState().riskState());
        assertThat(actualState.tradingState().treasuryState())
                .isEqualTo(expectedState.tradingState().treasuryState());
        assertThat(actualState.stateHash()).isEqualTo(expectedState.stateHash());
        assertThat(actualState.snapshotBusinessStateHash()).isEqualTo(expectedState.snapshotBusinessStateHash());
        assertThat(com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(actualState.snapshotTradingState()))
                .isEqualTo(com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(
                        expectedState.snapshotTradingState()));
        assertThat(actualState.snapshotProjectionSequence()).isEqualTo(expectedState.snapshotProjectionSequence());
        assertThat(actualState.commandResults()).isEqualTo(expectedState.commandResults());
        assertThat(actualState.lastSourceSequences()).isEqualTo(expectedState.lastSourceSequences());
        assertThat(actualState.feePolicies()).isEqualTo(expectedState.feePolicies());
        assertThat(actualState.pendingTransfers()).isEqualTo(expectedState.pendingTransfers());
    }

    /**
     * 通过 Owner 回调辅助推进一条批次到完成，要求只有一条响应、该 UUID 无残留撮合序号且 APPLIED，返回独立响应对照。
     * @param service 本次进程内 Owner 测试入口
     * @param batch 本条待通过原 Owner 回调执行的批量消息
     */
    private static BatchReplay completeBatch(TradingOwnerTestSupport service, CoreMessage batch) {
        // 本辅助构造并返回的原运行时
        TradingCoreRuntime state = service.state();
        // 当前进程内会话入口收集的响应清单
        ClusterReplay replay = replayClusterCommand(service, batch);
        assertThat(replay.responses()).hasSize(1);
        assertThat(state.matchingSequence(batch.header().commandId())).isZero();
        // 本辅助当前取得、轮询或编码的原响应
        CoreResponse response = replay.response();
        assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
        return new BatchReplay(response(response));
    }

    /**
     * 最多五秒持续收 Matcher 完成，要求得到指定原序号的结果；仅取得撮合结果，不在此代表账户账本已提交。
     * @param state 本测试当前交易运行时或冷状态，沿其原所有权读取
     * @param sequence 要等待的原 Core 撮合序号
     */
    private static com.surprising.aeron.service.matcher.MatchingResult awaitMatching(
            TradingCoreRuntime state, long sequence) {
        // 指定原序号的 Matcher 结果，尚未就绪时为空
        com.surprising.aeron.service.matcher.MatchingResult matching = null;
        // 当前撮合、结算或回调推进阶段的五秒截止时间，各阶段分别计算
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (matching == null && System.nanoTime() < deadline) {
            state.matchingProgress.drainMatchingCompletions();
            matching = state.takeMatchingResult(sequence);
            if (matching == null) Thread.onSpinWait();
        }
        assertThat(matching).isNotNull();
        return matching;
    }

    /**
     * 启动指定永续产品线的进程内 Owner 测试入口，再恢复给定分块快照；Cluster 为代理，不建立真实网络集群。
     * @param snapshot 用于恢复、摘要或校验和处理的原快照字节
     */
    private static TradingOwnerTestSupport restoredService(byte[] snapshot) {
        // 从原快照恢复并已启动的 Owner 测试入口
        TradingOwnerTestSupport service = new TradingOwnerTestSupport(ProductLine.LINEAR_PERPETUAL);
        service.onStart(cluster(), null);
        service.restoreSnapshot(snapshot);
        return service;
    }

    /**
     * 提供 Leader、固定日志位置、毫秒时间及无等待策略的 Cluster 代理，其余方法按返回类型给测试默认值。
     */
    private static Cluster cluster() {
        return (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (/* 当前动态代理实例，模拟方法不读取它 */ proxy, /* 本次被调用的 Cluster 或会话反射方法 */ method, /* 动态代理收到的原方法实参数组 */ arguments) -> switch (method.getName()) {
                    case "role" -> Cluster.Role.LEADER;
                    case "logPosition" -> 7L;
                    case "idleStrategy" -> NoOpIdleStrategy.INSTANCE;
                    case "timeUnit" -> TimeUnit.MILLISECONDS;
                    case "time" -> 1_000L;
                    case "scheduleTimer" -> true;
                    default -> defaultValue(method.getReturnType());
                });
    }

    /**
     * 为代理未列方法返回对应 primitive 零值或引用空值，不模拟真实 Cluster 业务。
     * @param type 代理方法声明的返回类型
     */
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }

    /**
     * 先经原会话回调入队，再最多五秒轮询 Owner 命令，直到无待处理条目；检查回调干净及响应存在后交回记录。
     * @param service 本次进程内 Owner 测试入口
     * @param command 本次按原身份与载荷提交或重复重放的协议消息
     */
    private static ClusterReplay replayClusterCommand(TradingOwnerTestSupport service, CoreMessage command) {
        // 当前进程内会话入口收集的响应清单
        ClusterReplay replay = replayClusterCommandRaw(service, command);
        // 当前撮合、结算或回调推进阶段的五秒截止时间，各阶段分别计算
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        do {
            service.pollCommands();
            if (service.pendingCommandCount() == 0) break;
            java.util.concurrent.locks.LockSupport.parkNanos(100_000);
        } while (System.nanoTime() < deadline);
        assertThat(service.pendingCommandCount()).isZero();
        service.state().assertClusterCallbackComplete();
        assertThat(replay.responses()).isNotEmpty();
        return replay;
    }

    /**
     * 编码原消息，使用不早于当前提交进度的模拟 Cluster 时间调用原会话入口；只入队并持有响应清单，完成推进由外层处理。
     * @param service 本次进程内 Owner 测试入口
     * @param command 本次按原身份与载荷提交或重复重放的协议消息
     */
    private static ClusterReplay replayClusterCommandRaw(
            TradingOwnerTestSupport service, CoreMessage command) {
        // 本次按原顺序收集的独立响应对照或模拟会话响应字节清单
        List<byte[]> responses = new ArrayList<>();
        // 本次交会话回调的完整原消息编码
        byte[] encoded = CoreMessageCodec.encode(command);
        // 各来源序号彼此独立，按它们合成的请求时间可能倒退。
        // 这里另给不早于已提交订单时间的模拟 Cluster 时钟，恢复后也沿用此约束。
        // 不早于原请求时间及当前已提交进度的模拟 Cluster 时间
        long timestamp = Math.max(command.header().submittedAtEpochMillis(), 1_000L + service.state().appliedCommandCount());
        service.onSessionMessage(clusterClient(responses), timestamp,
                new UnsafeBuffer(encoded), 0, encoded.length, aeronHeader());
        return new ClusterReplay(responses);
    }

    /**
     * 按原清单顺序通过会话入口推进每条命令，要求应用或重复后复制响应对照，不并发重排这些命令。
     * @param service 本次进程内 Owner 测试入口
     * @param commands 按原业务顺序推进的命令清单
     */
    private static ReplayResult replayClustered(
            TradingOwnerTestSupport service, List<CoreMessage> commands) {
        // 本次按原顺序收集的独立响应对照或模拟会话响应字节清单
        ArrayList<ResponseView> responses = new ArrayList<>();
        for (CoreMessage /* 当前待提交的原协议消息 */ command : commands) {
            // 当前进程内会话入口收集的响应清单
            ClusterReplay replay = replayClusterCommand(service, command);
            // 本次原撮合命令已经满足结算条件后的终态响应
            CoreResponse completed = replay.response();
            assertThat(completed.status()).as(command.header().messageType() + ": " + completed.resultCode().name())
                    .isIn(ResponseStatus.APPLIED, ResponseStatus.DUPLICATE);
            responses.add(response(completed));
        }
        return new ReplayResult(List.copyOf(responses));
    }

    /**
     * 从已提交冷状态查指定强平记录，缺失立即失败。
     * @param state 本测试当前交易运行时或冷状态，沿其原所有权读取
     * @param liquidationId 本次要读取或核对的强平任务 ID
     */
    private static CoreLiquidationState liquidation(TradingCoreRuntime state, long liquidationId) {
        return java.util.Objects.requireNonNull(
                state.tradingState().riskState().liquidations().get(liquidationId), "liquidation");
    }

    /**
     * 读取本永续测试当前 USDT 保险亏空，无值按零。
     * @param state 本测试当前交易运行时或冷状态，沿其原所有权读取
     */
    private static long deficit(TradingCoreRuntime state) {
        return state.tradingState().treasuryState().insuranceDeficits().getOrDefault("USDT", 0L);
    }

    /**
     * 在本夹具 USDT 资金汇总上，加 USDT 结算非零持仓按标记价减入场价计算的线性未实现盈亏；不是币本位或期权通用估值。
     * @param state 本测试当前交易运行时或冷状态，沿其原所有权读取
     */
    private static long economicEquityUsdt(TradingCoreRuntime state) {
        // 本次估值或重建索引读取的已提交冷状态
        var core = state.tradingState();
        // 按本夹具资金口径累计的 USDT 单位总额
        long total = economicUsdt(core);
        // 本线 USDT 结算持仓的线性未实现盈亏累计
        long unrealized = 0;
        for (var /* 当前资金守恒或全部释放断言中的冷用户 */ user : core.users().values()) {
            for (var /* 当前待按标记价计算未实现盈亏的冷持仓 */ position : user.positions().values()) {
                // 当前持仓所属原合约，用来检查结算资产
                var instrument = core.instruments().get(position.instrumentId());
                // 当前持仓所属原标记价，没有则不计算该项浮盈亏
                var mark = core.riskState().markPrices().get(position.instrumentId());
                if (position.signedQuantitySteps() != 0 && mark != null
                        && instrument.settleAsset().equals("USDT")) {
                    unrealized = Math.addExact(unrealized, Math.multiplyExact(
                            position.signedQuantitySteps(),
                            Math.subtractExact(mark.markPriceTicks(), position.entryPriceTicks())));
                }
            }
        }
        return Math.addExact(total, unrealized);
    }

    /**
     * 在两份进程内 Owner 入口重放同一原消息，检查重复响应相同、原投影不增长，再比较全部恢复状态。
     * @param reference 未中断执行的原对照运行时或 Owner 测试入口
     * @param restored 从同一快照恢复的对照 Owner 入口
     * @param command 本次按原身份与载荷提交或重复重放的协议消息
     */
    private static void assertDuplicateClusterReplay(
            TradingOwnerTestSupport reference, TradingOwnerTestSupport restored,
            CoreMessage command) throws Exception {
        // 重放原重复消息前保存的对照投影序号
        long projectionBefore = reference.state().snapshotProjectionSequence();
        // 原对照入口的重复响应清单
        ReplayResult referenceDuplicate = replayClustered(reference, List.of(command));
        // 恢复入口的同一原消息重复响应清单
        ReplayResult restoredDuplicate = replayClustered(restored, List.of(command));
        assertThat(restoredDuplicate).isEqualTo(referenceDuplicate);
        assertThat(referenceDuplicate.responses()).allMatch(/* 本次重复重放复制的原响应对照值 */ value -> value.status() == ResponseStatus.DUPLICATE);
        assertThat(reference.state().snapshotProjectionSequence()).isEqualTo(projectionBefore);
        assertBatchRecoveryParity(restored.state(), reference.state());
    }

    /** 模拟会话 ID 的原子递增来源，从一百开始，区分不同回放调用。 */
    private static final java.util.concurrent.atomic.AtomicLong nextSessionId = new java.util.concurrent.atomic.AtomicLong(100L);

    /**
     * 分配独立模拟会话号，用代理拷贝 offer 的响应字节到本次清单并返回成功；不执行真实网络传输。
     * @param responses 按原顺序保存的响应视图或原字节清单
     */
    private static ClientSession clusterClient(List<byte[]> responses) {
        // 为本次模拟会话分配的独立长整数号
        long sessionId = nextSessionId.getAndIncrement();
        return (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, (/* 当前动态代理实例，模拟方法不读取它 */ proxy, /* 本次被调用的 Cluster 或会话反射方法 */ method, /* 动态代理收到的原方法实参数组 */ arguments) -> switch (method.getName()) {
                    case "id" -> sessionId;
                    case "isClosing" -> false;
                    case "offer" -> {
                        // 会话 offer 收到的原响应 DirectBuffer
                        var buffer = (org.agrona.DirectBuffer) arguments[0];
                        // 目标载荷或原响应在输入缓冲中的字节起点
                        int offset = (int) arguments[1];
                        // 当前快照段、数组或响应有效字节长度
                        int length = (int) arguments[2];
                        // 本辅助当前取得、轮询或编码的原响应
                        byte[] response = new byte[length];
                        buffer.getBytes(offset, response);
                        responses.add(response);
                        yield 1L;
                    }
                    default -> primitiveDefault(method.getReturnType());
                });
    }

    /**
     * 为会话代理未列方法提供 primitive 零值，void 或引用返回空。
     * @param type 会话代理方法声明的返回类型
     */
    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }

    /**
     * 构造测试用固定 term/偏移的 Aeron Header，供会话回调识别模拟消息位置。
     */
    private static Header aeronHeader() {
        return new Header(0, 0).buffer(new UnsafeBuffer(new byte[64])).offset(0)
                .initialTermId(0).positionBitsToShift(16);
    }

    /**
     * 在调用方已建立账户读取边界时，读取两个固定用户余额和指定资产财库分量，组成本夹具资金视图；普通 long 求和不另加溢出防护。
     * @param runtime 调用方已取得读取权的原账户运行时
     * @param assetId 原内部资产编号
     */
    private static RuntimeFinancialView runtimeFinancialView(
            com.surprising.aeron.service.core.TradingAccountRuntime runtime, int assetId) {
        // 固定用户一千零一的原余额
        var buyer = runtime.balance(1001, assetId);
        // 固定用户二千零一的原余额
        var maker = runtime.balance(2001, assetId);
        // 当前冷财库或原运行时财库，只按本辅助需要的分量读取
        var treasury = runtime.treasury();
        // 按本辅助各财库分量普通求和得到的该资产总额
        long treasuryUnits = treasury.fee(assetId) + treasury.insurance(assetId)
                - treasury.insuranceDeficit(assetId) + treasury.liquidationFee(assetId)
                + treasury.fundingResidual(assetId) + treasury.roundingResidual(assetId)
                + treasury.clearingPnl(assetId);
        // 固定双方余额与该财库总额的普通求和
        long economicUnits = buyer.availableUnits() + buyer.lockedUnits()
                + maker.availableUnits() + maker.lockedUnits() + treasuryUnits;
        return new RuntimeFinancialView(
                new RuntimeBalanceView(buyer.availableUnits(), buyer.lockedUnits()),
                new RuntimeBalanceView(maker.availableUnits(), maker.lockedUnits()),
                treasury.fee(assetId), treasuryUnits, economicUnits);
    }

    /**
     * 比较两份运行时冷正文、身份、资金/业务哈希、幂等和来源水位，以及同命令围栏下各 Lane 快照；要求无在途命令、八索引等价且各自等于冷重建，再核对 Matcher 已应用序号。
     * @param recovered 待与原执行对照的恢复运行时
     * @param reference 未中断执行的原对照运行时或 Owner 测试入口
     */
    private static void assertBatchRecoveryParity(TradingCoreRuntime recovered, TradingCoreRuntime reference)
            throws Exception {
        assertThat(recovered.tradingState()).isEqualTo(reference.tradingState());
        assertThat(recovered.tradingState().users()).isEqualTo(reference.tradingState().users());
        assertThat(recovered.tradingState().orders()).isEqualTo(reference.tradingState().orders());
        assertThat(recovered.tradingState().clientOrderIndex())
                .isEqualTo(reference.tradingState().clientOrderIndex());
        // 反射取得的恢复运行时原身份表
        var recoveredIdentities = (com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry)
                field(recovered, "identities");
        // 反射取得的未中断对照原身份表
        var referenceIdentities = (com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry)
                field(reference, "identities");
        assertThat(recoveredIdentities.snapshot()).isEqualTo(referenceIdentities.snapshot());
        assertThat(recovered.tradingState().riskState()).isEqualTo(reference.tradingState().riskState());
        assertThat(recovered.tradingState().treasuryState()).isEqualTo(reference.tradingState().treasuryState());
        assertThat(recovered.snapshotBusinessStateHash()).isEqualTo(reference.snapshotBusinessStateHash());
        assertThat(com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(recovered.snapshotTradingState()))
                .isEqualTo(com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(
                        reference.snapshotTradingState()));
        assertThat(recovered.stateHash()).isEqualTo(reference.stateHash());
        assertThat(recovered.commandResults()).isEqualTo(reference.commandResults());
        assertThat(recovered.lastSourceSequences()).isEqualTo(reference.lastSourceSequences());
        // 本次 Lane 快照采用的已应用命令数围栏
        long fence = recovered.appliedCommandCount();
        // 恢复运行时在同一围栏捕获的各账户 Lane 快照
        var recoveredLanes = recovered.accountLaneSnapshots(fence, recovered.tradingState());
        assertThat(recoveredLanes).isEqualTo(reference.accountLaneSnapshots(fence, reference.tradingState()));
        assertThat(recoveredLanes).allSatisfy(/* 当前要核对 applied/committed 都等于围栏的 Lane 快照 */ lane -> {
            assertThat(lane.appliedSequence()).isEqualTo(lane.committedSequence());
            assertThat(lane.committedSequence()).isEqualTo(fence);
        });
        assertNoUnfinishedCommands(recovered);
        assertNoUnfinishedCommands(reference);
        assertThat(allIndexSnapshots(recovered)).isEqualTo(allIndexSnapshots(reference));
        assertIndexesEqualCanonicalRebuild(recovered);
        assertIndexesEqualCanonicalRebuild(reference);
        assertThat((long[]) field(recovered.commits, "appliedMatcherSequences"))
                .containsExactly((long[]) field(reference.commits, "appliedMatcherSequences"));
    }

    /**
     * 检查无事实作用域、无直接控制及撮合在途，并要求运行时满足原快照围栏。
     * @param state 本测试当前交易运行时或冷状态，沿其原所有权读取
     */
    private static void assertNoUnfinishedCommands(TradingCoreRuntime state) throws Exception {
        assertThat(state.factContextActive).isFalse();
        assertThat(state.directCommand.active()).isFalse();
        assertThat(state.pendingMatching().size()).isZero();
        assertThat(state.pendingMatching().inFlight()).isZero();
        state.runtimeState.requireSnapshotFenceReady();
    }

    /**
     * 仅供本测试访问指定声明字段，开启反射访问后返回原值；不存在或不可访问直接抛出。
     * @param target 包含目标声明字段的原对象
     * @param name 要读取的声明字段名
     */
    private static Object field(Object target, String name) throws Exception {
        // 仅供测试读取原内部状态的声明字段
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /**
     * 对运行时八个既有派生索引读取规范对照内容，不新增生产索引。
     * @param state 本测试当前交易运行时或冷状态，沿其原所有权读取
     */
    private static Map<String, Map<String, Object>> allIndexSnapshots(TradingCoreRuntime state) throws Exception {
        return Map.ofEntries(
                Map.entry("position-user", indexSnapshot(field(state, "positionUserIndex"))),
                Map.entry("open-interest", indexSnapshot(field(state, "openInterestIndex"))),
                Map.entry("trigger", indexSnapshot(field(state, "triggerOrderIndex"))),
                Map.entry("algo", indexSnapshot(field(state, "algoOrderIndex"))),
                Map.entry("liquidation", indexSnapshot(field(state, "liquidationIndex"))),
                Map.entry("timer", indexSnapshot(field(state, "cancelAllAfterIndex"))),
                Map.entry("active-order", indexSnapshot(field(state, "activeOrderIndex"))),
                Map.entry("adl-position", indexSnapshot(field(state, "adlPositionIndex"))));
    }

    /**
     * 从同一冷正文及身份表重新构造八个索引，按本测试规范化规则比较其内容与原运行时索引。
     * @param state 本测试当前交易运行时或冷状态，沿其原所有权读取
     */
    private static void assertIndexesEqualCanonicalRebuild(TradingCoreRuntime state) throws Exception {
        // 本次估值或重建索引读取的已提交冷状态
        var core = state.tradingState();
        // 当前冷重建或规范化取内容的原身份字典
        var identities = (com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry)
                field(state, "identities");
        // 八份从同一冷正文重建索引的字段对照结果
        Map<String, Map<String, Object>> rebuilt = Map.ofEntries(
                Map.entry("position-user", indexSnapshot(
                        new com.surprising.aeron.service.core.index.PositionUserIndex(core, identities))),
                Map.entry("open-interest", indexSnapshot(
                        new com.surprising.aeron.service.core.index.OpenInterestIndex(core))),
                Map.entry("trigger", indexSnapshot(
                        new com.surprising.aeron.service.core.index.TriggerOrderIndex(core))),
                Map.entry("algo", indexSnapshot(
                        new com.surprising.aeron.service.core.index.AlgoOrderIndex(core))),
                Map.entry("liquidation", indexSnapshot(
                        new com.surprising.aeron.service.core.index.LiquidationIndex(core))),
                Map.entry("timer", indexSnapshot(
                        new com.surprising.aeron.service.core.index.CancelAllAfterIndex(core))),
                Map.entry("active-order", indexSnapshot(
                        new com.surprising.aeron.service.core.index.ActiveOrderIndex(core, identities))),
                Map.entry("adl-position", indexSnapshot(
                        new com.surprising.aeron.service.core.index.AdlPositionIndex(core, identities))));
        assertThat(allIndexSnapshots(state)).isEqualTo(rebuilt);
    }

    /**
     * 反射收集非静态索引字段，排除身份引用、准入暂存、业务线程及活单查询暂存/回收池；字段值经测试规范化后按名字组成只读图。
     * @param index 待反射取内容的原派生索引对象
     */
    private static Map<String, Object> indexSnapshot(Object index) throws Exception {
        // 按字段名字排序、收集原索引内容的对照图
        java.util.TreeMap<String, Object> snapshot = new java.util.TreeMap<>();
        for (var /* 当前待反射读取的非静态索引声明字段 */ value : index.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(value.getModifiers()) || value.getName().equals("identities")
                    || value.getName().equals("admissionSummary")
                    || value.getName().equals("businessOwner")
                    || index instanceof com.surprising.aeron.service.core.index.ActiveOrderIndex
                    && (value.getName().equals("pageScratch")
                    || value.getName().equals("recycledOrders")
                    || value.getName().equals("recycledMemberships"))) continue; // 查询暂存和回收池可复用，不作为索引业务内容比较。
            value.setAccessible(true);
            snapshot.put(value.getName(), canonicalIndexValue(value.get(index)));
        }
        return Map.copyOf(snapshot);
    }

    /**
     * 把跨恢复对象转换为可比较测试值：身份取快照、订单取文本、特定索引单元递归字段；空桶按原规则省略，集合排序、序列保序。它不是生产序列化或完整任意对象等价算法。
     * @param value 待转换为测试对照值的对象，或待混合摘要的数字
     */
    private static Object canonicalIndexValue(Object value) {
        if (value instanceof com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry /* 当前冷重建或规范化取内容的原身份字典 */ identities)
            return identities.snapshot(); // 比较跨恢复对象的字典内容，而不是 Java 对象身份。
        if (value instanceof com.surprising.aeron.service.order.state.OrderRuntime /* 当前原订单，取文本比较跨恢复合约对象身份 */ order)
            return order.toString(); // 恢复后合约对象身份可不同，用本测试既有订单文本规则对照。
        if (value == null) return "<null>";
        if (value.getClass().getName().startsWith("com.surprising.aeron.service.core.index.OpenInterestIndex$")) {
            try {
                // 特定索引单元按字段递归转换的对照图
                Map<String, Object> snapshot = indexSnapshot(value);
                if (snapshot.get("longQuantity") instanceof Long /* OpenInterest 单元中已匹配为 Long 的多头数量 */ l && l == 0L
                        && snapshot.get("shortQuantity") instanceof Long /* OpenInterest 单元中已匹配为 Long 的空头数量 */ s && s == 0L) {
                    return Map.of();
                }
                return snapshot;
            } catch (/* 反射/递归索引规范化异常，转测试失败 */ Exception failure) {
                throw new AssertionError(failure);
            }
        }
        if (value.getClass().getName().startsWith("com.surprising.aeron.service.core.index.ActiveOrderIndex$")
                || value.getClass().getName().startsWith("com.surprising.aeron.service.core.index.AdlPositionIndex$")) {
            try {
                return indexSnapshot(value);
            } catch (/* 反射/递归索引规范化异常，转测试失败 */ Exception failure) {
                throw new AssertionError(failure);
            }
        }
        if (value instanceof Map<?, ?> /* 当前需要按文本键规范化的原映射 */ map) {
            // 本次递归构造的独立映射或序列副本
            java.util.TreeMap<String, Object> copy = new java.util.TreeMap<>();
            map.forEach((/* 当前 map 的原键，转文本后作为对照键 */ key, /* 当前映射值或序列元素，继续递归转换 */ nested) -> {
                // 递归转换后的本项索引字段对照值
                Object canonical = canonicalIndexValue(nested);
                if (canonical instanceof java.util.Collection<?> /* 已识别的集合对照值，空时省略其 map 项 */ col && col.isEmpty()) return;
                if (canonical instanceof Map<?, ?> /* 已规范化且为空时应省略的内层映射 */ nestedMap && nestedMap.isEmpty()) return;
                if ("[]".equals(canonical)) return;
                copy.put(String.valueOf(key), canonical);
            });
            return Map.copyOf(copy);
        }
        if (value instanceof java.util.Set<?> /* 待转换并按文本排序的原集合 */ set) {
            return set.stream().map(RuntimeCommitRecoveryTest::canonicalIndexValue)
                    .map(String::valueOf).sorted().toList();
        }
        if (value instanceof Iterable<?> /* 待按原顺序逐项转换的原可迭代序列 */ iterable) {
            // 本次递归构造的独立映射或序列副本
            java.util.ArrayList<Object> copy = new java.util.ArrayList<>();
            iterable.forEach(/* 当前映射值或序列元素，继续递归转换 */ nested -> copy.add(canonicalIndexValue(nested)));
            return List.copyOf(copy);
        }
        if (value.getClass().isArray()) {
            // 当前快照段、数组或响应有效字节长度
            int length = java.lang.reflect.Array.getLength(value);
            // 本次递归构造的独立映射或序列副本
            java.util.ArrayList<Object> copy = new java.util.ArrayList<>(length);
            for (/* 数组遍历位置或只反射不修改的原索引对象 */ int index = 0; index < length; index++) {
                copy.add(canonicalIndexValue(java.lang.reflect.Array.get(value, index)));
            }
            return List.copyOf(copy);
        }
        if (value.getClass().getName().startsWith("org.eclipse.collections")) return value.toString();
        return value;
    }

    /**
     * 按清单顺序用原运行时逐条执行并复制响应，交回不可变对照列表。
     * @param state 本测试当前交易运行时或冷状态，沿其原所有权读取
     * @param commands 按原业务顺序推进的命令清单
     */
    private static ReplayResult replay(TradingCoreRuntime state, List<CoreMessage> commands) {
        // 本次按原顺序收集的独立响应对照或模拟会话响应字节清单
        List<ResponseView> responses = commands.stream().map(/* 当前待提交的原协议消息 */ command -> response(apply(state, command))).toList();
        return new ReplayResult(responses);
    }

    /**
     * 原顺序回放得到的独立响应对照清单。
     * @param responses 按原顺序保存的响应视图或原字节清单
     */
    private record ReplayResult(List<ResponseView> responses) {
    }

    /**
     * 一条批量命令完成后的响应对照值。
     * @param response 需要复制或保存的原响应值
     */
    private record BatchReplay(ResponseView response) {
    }

    /**
     * 进程内会话代理接收的可追加原响应字节清单，外层推进后再读取。
     * @param responses 按原顺序保存的响应视图或原字节清单
     */
    private record ClusterReplay(List<byte[]> responses) {
        /**
         * 要求响应清单存在，但仍借用该清单，让后续 Owner 回包可继续追加。
         */
        ClusterReplay {
            responses = java.util.Objects.requireNonNull(responses, "responses");
        }

        /**
         * 要求已有响应，解码最后一份消息载荷为原 Core 响应，不要求此清单恰好只有一项。
         */
        CoreResponse response() {
            assertThat(responses).isNotEmpty();
            // 本次原协议消息，响应读取方法中为从最后响应字节解码的原消息
            CoreMessage message = CoreMessageCodec.decode(responses.getLast());
            return com.surprising.aeron.protocol.CoreProtocol.decodeResponse(message.payload());
        }
    }

    /**
     * 本恢复夹具复制的余额数字，不持有可变余额对象。
     * @param available 复制出的可用资产单位数
     * @param locked 复制出的冻结资产单位数
     */
    private record RuntimeBalanceView(long available, long locked) {
    }

    /**
     * 固定买方、maker 和财库的本夹具资金对照值，不是生产账本。
     * @param buyerBalance 固定买方的独立余额数字视图
     * @param makerBalance 固定 maker 的独立余额数字视图
     * @param treasuryFee 该资产已收费用数
     * @param treasuryUnits 按本夹具口径汇总的财库资产数
     * @param economicUnits 双方余额加财库分量的本夹具资金总额
     */
    private record RuntimeFinancialView(RuntimeBalanceView buyerBalance,
                                        RuntimeBalanceView makerBalance,
                                        long treasuryFee,
                                        long treasuryUnits,
                                        long economicUnits) {
        /**
         * 读取买方余额视图可用部分。
         */
        long buyerAvailable() {
            return buyerBalance.available();
        }

        /**
         * 读取买方余额视图冻结部分。
         */
        long buyerLocked() {
            return buyerBalance.locked();
        }

        /**
         * 按普通 long 加法返回买方可用加冻结。
         */
        long buyerTotal() {
            return buyerBalance.available() + buyerBalance.locked();
        }

        /**
         * 读取 maker 余额视图冻结部分。
         */
        long makerLocked() {
            return makerBalance.locked();
        }

        /**
         * 按普通 long 加法返回 maker 可用加冻结。
         */
        long makerTotal() {
            return makerBalance.available() + makerBalance.locked();
        }
    }

    /**
     * 复制原响应状态、结果码、命令计数及十六进制数据的跨恢复对照值。
     * @param status 原响应处理状态，可能为应用、重复等
     * @param commandStatus 原业务最终状态
     * @param resultCode 原协议业务结果码
     * @param appliedCommandCount 响应记录的已应用原命令数
     * @param data 原响应数据的十六进制副本
     */
    private record ResponseView(ResponseStatus status, ResponseStatus commandStatus, CoreResultCode resultCode,
                                long appliedCommandCount, String data) {
    }

    /**
     * 复制原快照，在指定段的固定载荷偏移精确加一项 long 差值，再重写外层校验和，供检查语义校验而不是仅 CRC 发现破坏。
     * @param source 待修改的原快照字节；来源构造辅助中为原来源枚举
     * @param sectionId 需要定位或处理的快照段编号
     * @param payloadOffset 目标 long 在指定段载荷中的字节偏移
     * @param delta 对目标 long 施加的精确加法差值
     */
    private static byte[] mutateLongInSection(byte[] source, int sectionId, int payloadOffset, long delta) {
        // 本测试复制并故意改坏的快照字节
        byte[] mutated = source.clone();
        // 当前快照的小端读取缓冲，模拟会话内为原响应 DirectBuffer
        ByteBuffer buffer = ByteBuffer.wrap(mutated).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(SectionedCoreSnapshotCodec.ENVELOPE_LENGTH);
        while (buffer.hasRemaining()) {
            // 当前遍历的原划转 ID 或快照段编号
            int id = buffer.getInt();
            // 当前快照段、数组或响应有效字节长度
            int length = buffer.getInt();
            // 目标载荷或原响应在输入缓冲中的字节起点
            int offset = buffer.position();
            if (id == sectionId) {
                buffer.putLong(offset + payloadOffset, Math.addExact(buffer.getLong(offset + payloadOffset), delta));
                rewriteChecksum(mutated);
                return mutated;
            }
            buffer.position(offset + length);
        }
        throw new AssertionError("snapshot section not found: " + sectionId);
    }

    /**
     * 复制快照，选有用户的账户段，把最后用户替成同 Lane 且不撞夹具用户的新 ID；按当前二进制结构重算 Lane 摘要及外层校验和，使恢复必须发现用户清单与全局正文不一致。
     * @param source 待修改的原快照字节；来源构造辅助中为原来源枚举
     * @param topology 原快照账户拓扑，用来选同 Lane 的替换用户
     */
    private static byte[] mutateLaneUserPreservingDigest(byte[] source, LaneTopology topology) {
        // 本测试复制并故意改坏的快照字节
        byte[] mutated = source.clone();
        // 当前快照的小端读取缓冲，模拟会话内为原响应 DirectBuffer
        ByteBuffer buffer = ByteBuffer.wrap(mutated).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(SectionedCoreSnapshotCodec.ENVELOPE_LENGTH);
        // 是否已经替换过一份账户用户清单
        boolean changed = false;
        while (buffer.position() < mutated.length
                - SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH - SectionedCoreSnapshotCodec.FOOTER_LENGTH) {
            // 当前遍历的快照段编号
            int sectionId = buffer.getInt();
            // 当前快照段、数组或响应有效字节长度
            int length = buffer.getInt();
            // 当前段载荷在完整快照中的字节起点
            int payloadOffset = buffer.position();
            if (!changed && sectionId >= 9) {
                // 当前账户段原 Lane 编号
                int laneId = buffer.getInt(payloadOffset);
                // 当前账户段原用户数
                int userCount = buffer.getInt(payloadOffset + Integer.BYTES + Long.BYTES * 5);
                if (userCount > 0) {
                    // 本段最后用户 ID 的绝对字节位置
                    int lastUserOffset = payloadOffset + Integer.BYTES * 2 + Long.BYTES * (5 + userCount - 1);
                    // 最后用户之前的 ID，单用户时按零
                    long previousUser = userCount == 1 ? 0 : buffer.getLong(lastUserOffset - Long.BYTES);
                    // 本段将要替换的原最后用户 ID
                    long originalUser = buffer.getLong(lastUserOffset);
                    // 仍属于原 Lane、按序且不等于夹具用户的新用户 ID
                    long replacement = Math.max(originalUser + 1, previousUser + 1);
                    while (topology.accountLaneId(replacement) != laneId || replacement == 11 || replacement == 22) {
                        replacement++;
                    }
                    buffer.putLong(lastUserOffset, replacement);
                    changed = true;
                }
            }
            buffer.position(payloadOffset + length);
        }
        if (!changed) throw new AssertionError("snapshot contains no account lane user");
        // 按已修改账户段重新计算的原格式 Lane 摘要
        long digest = rawAccountLaneDigest(mutated);
        buffer.position(SectionedCoreSnapshotCodec.ENVELOPE_LENGTH);
        while (buffer.hasRemaining()) {
            // 当前遍历的快照段编号
            int sectionId = buffer.getInt();
            // 当前快照段、数组或响应有效字节长度
            int length = buffer.getInt();
            // 当前段载荷在完整快照中的字节起点
            int payloadOffset = buffer.position();
            if (sectionId == 1) {
                buffer.putLong(payloadOffset + 77, digest);
                rewriteChecksum(mutated);
                return mutated;
            }
            buffer.position(payloadOffset + length);
        }
        throw new AssertionError("snapshot header section not found");
    }

    /**
     * 按当前快照账户段结构，混合 Lane 编号、五个进度字段及用户清单，供测试破坏数据后维护摘要，不解析完整业务对象。
     * @param snapshot 用于恢复、摘要或校验和处理的原快照字节
     */
    private static long rawAccountLaneDigest(byte[] snapshot) {
        // 当前快照的小端读取缓冲，模拟会话内为原响应 DirectBuffer
        ByteBuffer buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(SectionedCoreSnapshotCodec.ENVELOPE_LENGTH);
        // 当前对照业务哈希或按账户段累计的摘要
        long hash = 0xcbf29ce484222325L;
        while (buffer.position() < snapshot.length
                - SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH - SectionedCoreSnapshotCodec.FOOTER_LENGTH) {
            // 当前遍历的快照段编号
            int sectionId = buffer.getInt();
            // 当前快照段、数组或响应有效字节长度
            int length = buffer.getInt();
            // 当前段载荷在完整快照中的字节起点
            int payloadOffset = buffer.position();
            if (sectionId >= 9) {
                hash = mix(hash, buffer.getInt());
                for (/* 数组遍历位置或只反射不修改的原索引对象 */ int index = 0; index < 5; index++) hash = mix(hash, buffer.getLong());
                // 当前账户段原用户数
                int userCount = buffer.getInt();
                for (/* 数组遍历位置或只反射不修改的原索引对象 */ int index = 0; index < userCount; index++) hash = mix(hash, buffer.getLong());
            }
            buffer.position(payloadOffset + length);
        }
        return hash;
    }

    /**
     * 以固定 FNV 风格乘数混合数字，long 溢出是摘要算法原有环绕行为。
     * @param hash 此前累计的原始账户段摘要
     * @param value 待转换为测试对照值的对象，或待混合摘要的数字
     */
    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    /**
     * 只统计存活且名字以 core-commit-projector- 开头的线程，不能据此判断所有工作资源是否泄漏。
     */
    private static long projectorThreadCount() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(/* 当前按存活及旧 projector 名称前缀过滤的线程 */ thread -> thread.getName().startsWith("core-commit-projector-"))
                .count();
    }

    /**
     * 按当前分块快照格式计算外层 CRC32C 并写到末尾 long，排除末段头及 footer 范围，依赖测试输入符合现有格式。
     * @param snapshot 用于恢复、摘要或校验和处理的原快照字节
     */
    private static void rewriteChecksum(byte[] snapshot) {
        // 重新计算外层快照 CRC32C 的校验器
        CRC32C checksum = new CRC32C();
        checksum.update(snapshot, 0,
                snapshot.length - SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH
                        - SectionedCoreSnapshotCodec.FOOTER_LENGTH);
        ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(snapshot.length - Long.BYTES, checksum.getValue());
    }
}
