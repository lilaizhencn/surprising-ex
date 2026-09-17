package com.surprising.aeron.service.orchestration;
import com.surprising.aeron.service.command.order.DecodedMatchingCommand;
import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.state.RuntimeCommitJournal;
import com.surprising.aeron.service.state.RuntimeFundsDelta;
import com.surprising.aeron.service.state.RuntimeProjectionPoint;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommandSlotTest {

    @Test
    void clearsControlResultAndWorkBeforeReusingTheSameSlot() {
        DirectCommandSlot slot = new DirectCommandSlot();
        CoreMessage first = command(1000);
        var point = new RuntimeProjectionPoint(0, TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL));
        slot.initialize(first, CommandFingerprint.of(first), new TradingCoreRuntime.SourceKey(
                CommandSource.OPERATIONS, 1), 10, 20, point, 1, 2, 3);
        slot.deferControl(() -> true);
        slot.result(com.surprising.aeron.protocol.ResponseStatus.REJECTED,
                com.surprising.aeron.protocol.CoreResultCode.INVALID_COMMAND);
        slot.markFinalizationPrepared();
        slot.clear();

        CoreMessage second = command(1001);
        slot.initialize(second, CommandFingerprint.of(second), new TradingCoreRuntime.SourceKey(
                CommandSource.OPERATIONS, 1), 30, 40, point, 4, 5, 6);
        assertThat(slot.command()).isSameAs(second);
        assertThat(slot.controlWork()).isNull();
        assertThat(slot.status()).isNull();
        assertThat(slot.resultCode()).isNull();
        assertThat(slot.finalizationPrepared()).isFalse();
        assertThat(slot.commitFenceTimestamp()).isEqualTo(30);
        assertThat(slot.checkpoint()).isEqualTo(5);
        slot.clear();
    }

    @Test
    void batchContextSurvivesCommandRewriteAndIsClearedForNextSlotGeneration() {
        CoreMessage command = command(1000);
        var projection = new RuntimeProjectionPoint(0, TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL));
        var pending = new CommandSlot(1000, CommandSlot.Operation.PLACE, command,
                projection, 0, 0, RuntimeFundsDelta.empty());
        var batch = new OrderBatchPending(1);
        pending.orderBatch = batch;
        pending.initialize(2000, CommandSlot.Operation.PLACE, command,
                CommandFingerprint.of(command), List.of(), projection, 0, 0,
                RuntimeFundsDelta.empty(), DecodedMatchingCommand.decode(command), null);
        assertThat(pending.orderBatch).isNull();
    }

    @Test
    void batchOrderingDetachesMiddleHeadAndTailAcrossRingReuse() {
        try (var owner = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            var projection = new RuntimeProjectionPoint(0, TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL));
            for (int generation = 0; generation < 3; generation++) {
                long first = 1000L + (long) generation * owner.pendingMatching.capacity();
                var pending = new CommandSlot[3];
                var batches = new OrderBatchPending[3];
                for (int i = 0; i < 3; i++) {
                    pending[i] = owner.admissions.newPendingMatching(first + i,
                            CommandSlot.Operation.PLACE, command(first + i));
                    owner.pendingMatching.put(pending[i]);
                    batches[i] = new OrderBatchPending(1);
                    batches[i].sequence = first + i;
                    owner.batches.registerBatch(pending[i], batches[i]);
                    assertThat(owner.batches.batch(first + i)).isSameAs(pending[i].orderBatch).isSameAs(batches[i]);
                }
                assertThat(owner.batches.pendingBatchCount()).isEqualTo(3);
                assertThat(owner.batches.firstBatch()).isSameAs(batches[0]);
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> owner.batches.registerBatch(pending[0], batches[0]))
                        .isInstanceOf(IllegalStateException.class);
                owner.batches.unregisterBatch(pending[1], batches[1]);
                assertThat(batches[0].nextBatch).isSameAs(batches[2]);
                assertThat(batches[2].previousBatch).isSameAs(batches[0]);
                assertThat(pending[1].orderBatch).isNull();
                owner.batches.unregisterBatch(pending[0], batches[0]);
                assertThat(owner.batches.firstBatch()).isSameAs(batches[2]);
                owner.batches.clearPendingBatches();
                assertThat(owner.batches.hasPendingBatches()).isFalse();
                assertThat(owner.batches.pendingBatchCount()).isZero();
                for (int i = 0; i < 3; i++) {
                    assertThat(pending[i].orderBatch).isNull();
                    assertThat(batches[i].previousBatch).isNull();
                    assertThat(batches[i].nextBatch).isNull();
                    owner.pendingMatching.remove(first + i);
                }
            }
        }
    }

    @Test
    void preservesCapturedPreCommandHashesAcrossDeferredMatchingUpdates() {
        CoreMessage command = command(11);
        CommandFingerprint fingerprint = CommandFingerprint.of(command);
        TradingCoreState beforeState = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        RuntimeProjectionPoint beforeProjection = new RuntimeProjectionPoint(0, beforeState);
        RuntimeFundsDelta fundsDelta = RuntimeFundsDelta.empty();
        CommandSlot pending = new CommandSlot(7, CommandSlot.Operation.PLACE, command, fingerprint,
                List.of(17L), beforeProjection, 101L, 202L, fundsDelta);

        var decoded = pending.decodedCommand();
        CommandSlot updatedCancellations = pending.withPreMatchingCancellations(List.of(18L, 19L));

        assertThat(updatedCancellations.beforeBusinessStateHash()).isEqualTo(101L);
        assertThat(updatedCancellations.beforeFundsStateHash()).isEqualTo(202L);
        assertThat(updatedCancellations.beforeProjection()).isSameAs(beforeProjection);
        assertThat(updatedCancellations.fundsDelta()).isSameAs(fundsDelta);
        assertThat(updatedCancellations.preMatchingCancellationOrderIds()).containsExactly(18L, 19L);
        assertThat(updatedCancellations.command()).isSameAs(command);
        assertThat(updatedCancellations.decodedCommand()).isSameAs(decoded);
        assertThat(updatedCancellations.fingerprint()).isSameAs(fingerprint);
    }

    private static CoreMessage command(long sourceSequence) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER, UUID.randomUUID(),
                ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY, 7, sourceSequence,
                101, 1_700_000_000_000L, sourceSequence),
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(
                        1_000 + sourceSequence, "BTC-USDT", 1, CoreOrderSide.BUY, 100, 1,
                        false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                        CoreTimeInForce.GTC, false, "pending-" + sourceSequence)));
    }
}
