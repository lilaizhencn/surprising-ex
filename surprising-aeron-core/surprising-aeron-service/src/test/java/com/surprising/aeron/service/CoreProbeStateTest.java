package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreProbeStateTest {

    @Test
    void appliesCommandOnceAndReturnsOriginalDuplicateResult() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        CoreMessage command = command(UUID.randomUUID(), 1, 7);

        var applied = state.apply(command);
        var duplicate = state.apply(command);

        assertThat(applied.status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(duplicate.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(duplicate.appliedCommandCount()).isEqualTo(1);
        assertThat(duplicate.stateHash()).isEqualTo(applied.stateHash());
        assertThat(state.probeValue()).isEqualTo(7);
    }

    @Test
    void sourceHighWatermarkSurvivesIdempotencyWindowEviction() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        for (int sequence = 1; sequence <= CoreProbeState.MAX_IDEMPOTENCY_RESULTS + 1; sequence++) {
            assertThat(state.apply(command(UUID.randomUUID(), sequence, 1)).status())
                    .isEqualTo(ResponseStatus.APPLIED);
        }

        var staleRetry = state.apply(command(UUID.randomUUID(), 1, 100));

        assertThat(staleRetry.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(state.appliedCommandCount()).isEqualTo(CoreProbeState.MAX_IDEMPOTENCY_RESULTS + 1L);
        assertThat(state.probeValue()).isEqualTo(CoreProbeState.MAX_IDEMPOTENCY_RESULTS + 1L);
    }

    @Test
    void snapshotRoundTripPreservesStateHashAndDeduplication() {
        CoreProbeState original = new CoreProbeState(ProductLine.INVERSE_DELIVERY);
        CoreMessage first = command(ProductLine.INVERSE_DELIVERY, UUID.randomUUID(), 1, 11);
        CoreMessage second = command(ProductLine.INVERSE_DELIVERY, UUID.randomUUID(), 2, -3);
        original.apply(first);
        original.apply(second);

        CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.INVERSE_DELIVERY, original.snapshot());

        assertThat(restored.appliedCommandCount()).isEqualTo(original.appliedCommandCount());
        assertThat(restored.probeValue()).isEqualTo(8);
        assertThat(restored.stateHash()).isEqualTo(original.stateHash());
        assertThat(restored.apply(first).status()).isEqualTo(ResponseStatus.DUPLICATE);
    }

    @Test
    void rejectsAnotherProductLineWithoutMutatingState() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);

        var result = state.apply(command(ProductLine.OPTION, UUID.randomUUID(), 1, 10));

        assertThat(result.status()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(state.appliedCommandCount()).isZero();
        assertThat(state.probeValue()).isZero();
    }

    @Test
    void appliesTradingCommandsOnceAndSnapshotsAuthoritativeState() {
        CoreProbeState original = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(original);
        UUID adjustmentId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        CoreMessage adjustment = tradingCommand(CoreMessageType.ADJUST_BALANCE, adjustmentId, 1,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000)));
        CoreMessage place = tradingCommand(CoreMessageType.PLACE_ORDER, placeId, 2,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(91, "BTC-USDT", 1, "BTC", "USDT", "USDT",
                        CoreOrderSide.BUY, 1_000, 2, false,
                        ReservationKind.SPOT_ASSET, "USDT", 2_500)));

        assertThat(original.apply(adjustment).status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(original.apply(place).status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(original.apply(place).status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(original.tradingState().user(1001).balances().get("USDT").availableUnits()).isEqualTo(7_500);

        CoreMessage userQuery = query(CoreMessageType.USER_STATE_QUERY, 1001, new byte[0]);
        CoreMessage orderQuery = query(CoreMessageType.ORDER_STATE_QUERY, 1001,
                TradingCommandCodec.encodeOrderStateQuery(91));
        var userResult = original.apply(userQuery);
        var orderResult = original.apply(orderQuery);
        assertThat(userResult.status()).isEqualTo(ResponseStatus.OK);
        assertThat(CoreStateQueryCodec.decodeUserState(userResult.data()).balances().getFirst().lockedUnits())
                .isEqualTo(2_500);
        assertThat(orderResult.status()).isEqualTo(ResponseStatus.OK);
        assertThat(CoreStateQueryCodec.decodeOrderState(orderResult.data()).orderId()).isEqualTo(91);

        CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, original.snapshot());
        assertThat(restored.stateHash()).isEqualTo(original.stateHash());
        assertThat(restored.tradingState()).isEqualTo(original.tradingState());

        CoreMessage cancel = tradingCommand(CoreMessageType.CANCEL_ORDER, UUID.randomUUID(), 3,
                TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(91)));
        assertThat(restored.apply(cancel).status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(restored.tradingState().user(1001).totalUnits("USDT")).isEqualTo(10_000);
    }

    @Test
    void recordsRejectedTradingCommandWithoutChangingBusinessState() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        long businessHash = state.tradingState().businessStateHash();
        CoreMessage command = tradingCommand(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 1,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(1, "BTC-USDT", 1, "BTC", "USDT", "USDT",
                        CoreOrderSide.BUY, 600, 1, false,
                        ReservationKind.SPOT_ASSET, "USDT", 1_000)));

        var rejected = state.apply(command);
        var duplicate = state.apply(command);

        assertThat(rejected.status()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(rejected.resultCode()).isEqualTo(CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
        assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(duplicate.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(duplicate.resultCode()).isEqualTo(CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
        assertThat(state.appliedCommandCount()).isEqualTo(2);
        assertThat(state.tradingState().businessStateHash()).isEqualTo(businessHash);

        CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, state.snapshot());
        var restoredDuplicate = restored.apply(command);
        assertThat(restoredDuplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(restoredDuplicate.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(restoredDuplicate.resultCode()).isEqualTo(CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE);
    }

    private static CoreMessage command(UUID commandId, long sourceSequence, long delta) {
        return command(ProductLine.SPOT, commandId, sourceSequence, delta);
    }

    private static CoreMessage command(
            ProductLine productLine,
            UUID commandId,
            long sourceSequence,
            long delta) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT, commandId,
                productLine, CommandSource.GATEWAY, 7, sourceSequence, 1001, 1_000, sourceSequence),
                CoreProtocol.probePayload(delta));
    }

    private static CoreMessage tradingCommand(
            CoreMessageType messageType,
            UUID commandId,
            long sourceSequence,
            byte[] payload) {
        return new CoreMessage(CoreMessageHeader.command(messageType, commandId,
                ProductLine.SPOT, CommandSource.GATEWAY, 7, sourceSequence, 1001,
                1_000, sourceSequence), payload);
    }

    private static void applySpotInstrument(CoreProbeState state) {
        UpsertInstrumentCommand instrument = new UpsertInstrumentCommand("BTC-USDT", 1,
                ContractType.SPOT.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1,
                100_000, 50_000, 0, 0, 0, -1, 0);
        CoreMessage command = new CoreMessage(CoreMessageHeader.command(CoreMessageType.UPSERT_INSTRUMENT,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 9, 1, 1,
                1_000, 1), TradingCommandCodec.encodeUpsertInstrument(instrument));
        assertThat(state.apply(command).status()).isEqualTo(ResponseStatus.APPLIED);
    }

    private static CoreMessage query(CoreMessageType messageType, long userId, byte[] payload) {
        return new CoreMessage(CoreMessageHeader.query(messageType, UUID.randomUUID(),
                ProductLine.SPOT, CommandSource.GATEWAY, 7, 0, userId, 1_000, 100), payload);
    }
}
