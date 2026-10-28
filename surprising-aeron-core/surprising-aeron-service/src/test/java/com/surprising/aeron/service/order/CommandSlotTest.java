package com.surprising.aeron.service.order;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.order.command.DecodedMatchingCommand;
import com.surprising.aeron.service.result.CommandResultBuilder;
import com.surprising.aeron.service.result.ResponseArena;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证命令槽的 Lane 结果、primitive 提交缓冲交换、直接命令清理及批次关联复用。多数断言针对状态容器，部分用真实运行时提供 Owner 协作入口，不等于完整撮合与资金结算验收。
 */
class CommandSlotTest {

    /**
     * Lane 已明确捕获空订单并准备空响应时，要求 Owner 结果构建器直接复用该结果；构建器不带 Owner，若回查订单就会失败。
     */
    @Test
    void laneEmptyResultIsFinalAndNeverQueriesOwnerState() {
        // 当前测试的可复用命令槽，批次测试中为三个槽的数组。
        CoreMessage command = command(1000);
        var pending = new CommandSlot().initialize(1000, CommandSlot.Operation.PLACE, command,
                CommandFingerprint.of(command), List.of(), DecodedMatchingCommand.decode(command), null);
        pending.prepareLaneResultTarget(new ResponseArena(1, 16));
        // 普通命令槽提供的 Lane 结果写入接口。
        var target = pending.laneResultTarget();
        target.resultOrder(0, null, null);
        target.prepareResponse();
        assertThat(pending.laneResultPrepared()).isTrue();
        assertThat(pending.lanePreparedResponse()).isEmpty();
        assertThat(pending.lanePreparedResponseLength()).isZero();
        // 构建器没有 Owner；若收尾仍回查订单表会失败，必须使用 Lane 已给出的空结果。
        // Owner 结果与变更集合构建器，空 Owner 场景用于禁止回读。
        var builder = new CommandResultBuilder(null);
        builder.materializeCommandOrderViews(pending);
        assertThat(builder.commandResultData(pending, null)).isSameAs(pending.lanePreparedResponse());
    }

    /**
     * 挂起用户七/订单一百零一的变更缓冲，构建器另写八/一百零二，再恢复原缓冲；同一个 PrimitiveLongView 对象必须先看到新值再看到恢复值，避免每次复制视图。
     */
    @Test void changedIdViewsFollowSuspendedPrimitiveBuffersWithoutPerCommitCopies() {
        try (/* 提供结果构建器的真实线性永续运行时，退出时关闭。 */ var runtime = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            // Owner 结果与变更集合构建器，空 Owner 场景用于禁止回读。
            var builder = runtime.resultBuilder;
            builder.beginCommand();
            builder.changedUserIds().add(7);
            builder.changedOrderIds().add(101);
            builder.materializeChangeAccumulators();
            // 构建器长期复用的 primitive 用户变更视图，缓冲交换后仍是同一个对象。
            var userView = builder.commandChangedUserIds;
            assertThat(userView).isInstanceOf(com.surprising.aeron.support.collection.PrimitiveLongView.class);
            // 当前测试的可复用命令槽，批次测试中为三个槽的数组。
            CoreMessage command = command(1);
            var pending = new CommandSlot().initialize(1, CommandSlot.Operation.PLACE, command,
                    CommandFingerprint.of(command), List.of(), DecodedMatchingCommand.decode(command), null);
            pending.suspendCommitContext(builder, false);
            builder.commandChangedUserIds = List.of();
            builder.commandChangedOrderIds = List.of();
            builder.resetChangeAccumulators();
            builder.changedUserIds().add(8);
            builder.changedOrderIds().add(102);
            builder.materializeChangeAccumulators();
            assertThat(builder.commandChangedUserIds).isSameAs(userView).containsExactly(8L);
            pending.restoreCommitContext(builder);
            builder.commandChangedUserIds = List.of();
            builder.commandChangedOrderIds = List.of();
            builder.materializeChangeAccumulators();
            assertThat(builder.commandChangedUserIds).isSameAs(userView).containsExactly(7L);
            assertThat(builder.commandChangedOrderIds).containsExactly(101L);
            pending.clearCommitContext();
        }
    }

    /**
     * 实际验证 DirectCommandSlot：先写控制任务、拒绝结果和终结标记，清理后复用，要求旧任务和结果为空、边界及检查点换为新命令值。
     */
    @Test
    void clearsControlResultAndWorkBeforeReusingTheSameSlot() {
        try (var runtime = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            // 本方法实际测试的直接命令槽。
            DirectCommandSlot slot = runtime.directCommand;
            // 首条直接命令，批次代际测试中则为当前代首个 Core 序号。
            CoreMessage first = command(1000);
            slot.initialize(first, CommandFingerprint.of(first), new TradingCoreRuntime.SourceKey(
                    CommandSource.OPERATIONS, 1), 10, 20, 1, 2, 3);
            slot.deferControl(() -> true);
            slot.result(com.surprising.aeron.protocol.ResponseStatus.REJECTED,
                    com.surprising.aeron.protocol.CoreResultCode.INVALID_COMMAND);
            slot.markFinalizationPrepared();
            slot.clear();

            // 复用同一直接槽的新命令。
            CoreMessage second = command(1001);
            slot.initialize(second, CommandFingerprint.of(second), new TradingCoreRuntime.SourceKey(
                    CommandSource.OPERATIONS, 1), 30, 40, 4, 5, 6);
            assertThat(slot.command()).isSameAs(second);
            assertThat(slot.hasControlWork()).isFalse();
            assertThat(slot.status()).isNull();
            assertThat(slot.resultCode()).isNull();
            assertThat(slot.finalizationPrepared()).isFalse();
            assertThat(slot.commitFenceTimestamp()).isEqualTo(30);
            assertThat(slot.checkpoint()).isEqualTo(5);
            slot.clear();
        }
    }

    /**
     * 给槽绑定批次后直接以新序号 initialize，要求旧批次引用清空；本项只断言新代际清理，未单独断言名称中的中间重写保留过程。
     */
    @Test
    void batchContextSurvivesCommandRewriteAndIsClearedForNextSlotGeneration() {
        // 当前下单输入消息。
        CoreMessage command = command(1000);
        // 当前测试的可复用命令槽，批次测试中为三个槽的数组。
        var pending = new CommandSlot().initialize(1000, CommandSlot.Operation.PLACE, command,
                CommandFingerprint.of(command), List.of(), DecodedMatchingCommand.decode(command), null);
        // 人工绑定到槽上的批次上下文。
        var batch = new OrderBatchPending(1, new ResponseArena(1, 4096));
        pending.orderBatch = batch;
        pending.initialize(2000, CommandSlot.Operation.PLACE, command,
                CommandFingerprint.of(command), List.of(),
                DecodedMatchingCommand.decode(command), null);
        assertThat(pending.orderBatch).isNull();
    }

    /**
     * 重复三代创建三个批次，验证重复注册拒绝、中项和首项摘除修补链接，整体清理后槽与批次双方都断开，再移除各待命令并复用相同物理位置。
     */
    @Test
    void batchOrderingDetachesMiddleHeadAndTailAcrossRingReuse() {
        try (/* 提供待队列和批次链入口的真实运行时。 */ var owner = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            for (/* 当前槽位复用代际，循环共三次。 */ int generation = 0; generation < 3; generation++) {
                // 首条直接命令，批次代际测试中则为当前代首个 Core 序号。
                long first = 1000L + (long) generation * owner.pendingMatching().capacity();
                // 当前测试的可复用命令槽，批次测试中为三个槽的数组。
                var pending = new CommandSlot[3];
                // 本代三个批次上下文数组。
                var batches = new OrderBatchPending[3];
                for (/* 当前创建或清理的三个批次/命令下标。 */ int i = 0; i < 3; i++) {
                    pending[i] = owner.admissions().newPendingMatching(first + i,
                            CommandSlot.Operation.PLACE, command(first + i));
                    owner.pendingMatching().put(pending[i]);
                    batches[i] = new OrderBatchPending(1, owner.responseArena());
                    batches[i].sequence = first + i;
                    owner.batches().registerBatch(pending[i], batches[i]);
                    assertThat(owner.batches().batch(first + i)).isSameAs(pending[i].orderBatch).isSameAs(batches[i]);
                }
                assertThat(owner.batches().pendingBatchCount()).isEqualTo(3);
                assertThat(owner.batches().firstBatch()).isSameAs(batches[0]);
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> owner.batches().registerBatch(pending[0], batches[0]))
                        .isInstanceOf(IllegalStateException.class);
                owner.batches().unregisterBatch(pending[1], batches[1]);
                assertThat(batches[0].nextBatch).isSameAs(batches[2]);
                assertThat(batches[2].previousBatch).isSameAs(batches[0]);
                assertThat(pending[1].orderBatch).isNull();
                owner.batches().unregisterBatch(pending[0], batches[0]);
                assertThat(owner.batches().firstBatch()).isSameAs(batches[2]);
                owner.batches().clearPendingBatches();
                assertThat(owner.batches().hasPendingBatches()).isFalse();
                assertThat(owner.batches().pendingBatchCount()).isZero();
                for (/* 当前创建或清理的三个批次/命令下标。 */ int i = 0; i < 3; i++) {
                    assertThat(pending[i].orderBatch).isNull();
                    assertThat(batches[i].previousBatch).isNull();
                    assertThat(batches[i].nextBatch).isNull();
                    owner.pendingMatching().remove(first + i);
                }
            }
        }
    }

    /**
     * 只更新先撤订单号列表，要求原命令、指纹、解码引用及空资金增量保持原身份；本方法未实际调用 deferMatching 阶段切换。
     */
    @Test
    void preservesCommandStateAcrossDeferredMatchingUpdates() {
        // 当前下单输入消息。
        CoreMessage command = command(11);
        // 原命令已计算的指纹，更新列表后要求保持同一引用。
        CommandFingerprint fingerprint = CommandFingerprint.of(command);
        // 当前测试的可复用命令槽，批次测试中为三个槽的数组。
        CommandSlot pending = new CommandSlot().initialize(7, CommandSlot.Operation.PLACE, command, fingerprint,
                List.of(17L), DecodedMatchingCommand.decode(command), null);

        // 更新前保留的解码引用。
        var decoded = pending.decodedCommand();
        // 更新先撤列表后返回的原命令槽引用。
        CommandSlot updatedCancellations = pending.withPreMatchingCancellations(List.of(18L, 19L));

        assertThat(updatedCancellations.preMatchingCancellationOrderIds()).containsExactly(18L, 19L);
        assertThat(updatedCancellations.command()).isSameAs(command);
        assertThat(updatedCancellations.decodedCommand()).isSameAs(decoded);
        assertThat(updatedCancellations.fingerprint()).isSameAs(fingerprint);
    }

    /**
     * 构造线性永续 GTC 买单消息，随机 UUID，订单号及客户端键按来源序号生成，不实际执行账户准入。
     * @param sourceSequence 测试命令源序号，同时用于订单身份与关联位置
     */
    private static CoreMessage command(long sourceSequence) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER, UUID.randomUUID(),
                ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY, 7, sourceSequence,
                101, 1_700_000_000_000L, sourceSequence),
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(
                        1_000 + sourceSequence, "1", CoreOrderSide.BUY, 100, 1,
                        false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                        CoreTimeInForce.GTC, false, "pending-" + sourceSequence)));
    }
}
