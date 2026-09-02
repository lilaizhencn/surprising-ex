package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SharedProductLineSnapshotContractTest {

    private static final long USER_ID = 701;
    private static final long BALANCE_UNITS = 20_000;
    private static final String SYMBOL = "BTC-USDT";

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void v17SnapshotRestoresEveryProductLineWithoutCrossLineOrFinancialDrift(ProductLine productLine) {
        CoreMessage funding = gateway(productLine, 1, USER_ID, CoreMessageType.ADJUST_BALANCE,
                TradingCommandCodec.encodeBalanceAdjustment(
                        new BalanceAdjustmentCommand(settleAsset(productLine), BALANCE_UNITS)));
        try (CoreProbeState original = new CoreProbeState(productLine)) {
            assertApplied(applyTerminal(original, operations(productLine, 1, CoreMessageType.UPSERT_INSTRUMENT,
                    TradingCommandCodec.encodeUpsertInstrument(instrument(productLine)))));
            if (productLine.isDerivative()) {
                assertApplied(applyTerminal(original, market(productLine, 1, CoreMessageType.APPLY_MARK_PRICE,
                        TradingCommandCodec.encodeApplyMarkPrice(
                                new ApplyMarkPriceCommand(SYMBOL, 1, 100, 1, 1_700_000_000_000L)))));
                assertThat(original.tradingState().riskState().markPrices().get(SYMBOL).markPriceTicks())
                        .isEqualTo(100);
            }

            assertApplied(applyTerminal(original, funding));
            long economicBeforeOrder = economicAssetUnits(original, settleAsset(productLine));
            long orderId = 10_000L + productLine.ordinal();
            assertApplied(applyTerminal(original, gateway(productLine, 2, USER_ID, CoreMessageType.PLACE_ORDER,
                    TradingCommandCodec.encodePlaceOrder(place(orderId)))));
            assertThat(original.tradingState().user(USER_ID).balances().get(settleAsset(productLine)).lockedUnits())
                    .as("%s reservation", productLine).isPositive();
            assertApplied(applyTerminal(original, gateway(productLine, 3, USER_ID, CoreMessageType.CANCEL_ORDER,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(orderId)))));
            assertThat(original.tradingState().user(USER_ID).balances().get(settleAsset(productLine)).lockedUnits())
                    .as("%s cancellation releases reservation", productLine).isZero();
            assertThat(economicAssetUnits(original, settleAsset(productLine))).isEqualTo(economicBeforeOrder);

            assertProductSpecificLifecycle(productLine, original);
            byte[] snapshot = original.snapshot(800 + productLine.ordinal());
            CoreSnapshotManifest manifest = CoreProbeState.inspectSnapshot(productLine, snapshot);
            long laneFence = original.appliedCommandCount();
            long stateHash = original.stateHash();
            long businessHash = original.snapshotBusinessStateHash();
            long fundsHash = original.snapshotFundsStateHash();
            long economicUnits = economicAssetUnits(original, settleAsset(productLine));

            assertThat(manifest.productLine()).isEqualTo(productLine);
            assertThat(manifest.schemaVersion()).isEqualTo(17);
            assertThatThrownBy(() -> CoreProbeState.fromSnapshot(other(productLine), snapshot))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("product line");

            try (CoreProbeState restored = CoreProbeState.fromSnapshot(productLine, snapshot)) {
                assertThat(restored.productLine()).isEqualTo(productLine);
                assertThat(restored.tradingState()).isEqualTo(original.tradingState());
                assertThat(restored.stateHash()).isEqualTo(stateHash);
                assertThat(restored.snapshotBusinessStateHash()).isEqualTo(businessHash);
                assertThat(restored.snapshotFundsStateHash()).isEqualTo(fundsHash);
                assertThat(economicAssetUnits(restored, settleAsset(productLine))).isEqualTo(economicUnits);
                assertThat(restored.accountLaneSnapshots(laneFence, restored.tradingState()))
                        .isEqualTo(original.accountLaneSnapshots(laneFence, original.tradingState()));
                assertThat(restored.commandResults()).isEqualTo(original.commandResults());
                assertThat(restored.lastSourceSequences()).isEqualTo(original.lastSourceSequences());

                long hashBeforeDuplicate = restored.stateHash();
                CoreResponse duplicate = restored.apply(funding);
                assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(restored.stateHash()).isEqualTo(hashBeforeDuplicate);
            }
        }
    }

    private static void assertProductSpecificLifecycle(ProductLine productLine, CoreProbeState state) {
        if (productLine.isFundingProduct()) {
            CoreResponse funding = applyTerminal(state, market(productLine, 2, CoreMessageType.APPLY_FUNDING,
                    TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(
                            90_000L + productLine.ordinal(), SYMBOL, 1, 10))));
            assertApplied(funding);
            assertThat(state.tradingState().treasuryState().fundingSettlements())
                    .containsEntry(SYMBOL, 90_000L + productLine.ordinal());
            return;
        }

        if (productLine == ProductLine.SPOT) return;

        CoreResponse funding = state.apply(market(productLine, 2, CoreMessageType.APPLY_FUNDING,
                TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(
                        90_000L + productLine.ordinal(), SYMBOL, 1, 10))));
        assertThat(funding.status()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(funding.resultCode()).isEqualTo(CoreResultCode.PRODUCT_LINE_UNSUPPORTED);
        if (!productLine.isDeliveryProduct()) return;

        long settlementId = 91_000L + productLine.ordinal();
        CoreResponse settlement = applyTerminal(state, operations(productLine, 2, CoreMessageType.SETTLE_INSTRUMENT,
                TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(
                        settlementId, SYMBOL, 1, 100, productLine.isOptionProduct() ? 10 : 0))));
        assertApplied(settlement);
        assertThat(state.tradingState().treasuryState().lifecycleSettlements()).containsEntry(SYMBOL, settlementId);
        CoreResponse rejectedAfterSettlement = state.apply(gateway(productLine, 4, USER_ID,
                CoreMessageType.PLACE_ORDER, TradingCommandCodec.encodePlaceOrder(place(
                        20_000L + productLine.ordinal()))));
        assertThat(rejectedAfterSettlement.status()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(rejectedAfterSettlement.resultCode()).isEqualTo(CoreResultCode.INSTRUMENT_SETTLED);
    }

    private static UpsertInstrumentCommand instrument(ProductLine productLine) {
        ContractType type = ContractType.valueOf(productLine.contractTypeCode());
        long expiry = type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0;
        return new UpsertInstrumentCommand(SYMBOL, 1, type.ordinal(), "BTC", "USDT", settleAsset(productLine),
                1, 1, type.isInverse() ? 1_000 : 1, 100_000, 50_000, 0, 0, expiry,
                type.isOption() ? 0 : -1, type.isOption() ? 100 : 0);
    }

    private static PlaceOrderCommand place(long orderId) {
        return new PlaceOrderCommand(orderId, SYMBOL, 1, CoreOrderSide.BUY, 100, 1, false,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                false, "v17-" + orderId);
    }

    private static CoreMessage gateway(ProductLine productLine, long sequence, long userId,
                                       CoreMessageType type, byte[] payload) {
        return message(productLine, CommandSource.GATEWAY, 701, sequence, userId, type, payload);
    }

    private static CoreMessage operations(ProductLine productLine, long sequence, CoreMessageType type,
                                          byte[] payload) {
        return message(productLine, CommandSource.OPERATIONS, 702, sequence, 0, type, payload);
    }

    private static CoreMessage market(ProductLine productLine, long sequence, CoreMessageType type, byte[] payload) {
        return message(productLine, CommandSource.KAFKA_INPUT_BRIDGE, 703, sequence, 0, type, payload);
    }

    private static CoreMessage message(ProductLine productLine, CommandSource source, long sourceId,
                                       long sourceSequence, long userId, CoreMessageType type, byte[] payload) {
        UUID commandId = UUID.nameUUIDFromBytes((productLine + ":" + source + ":" + sourceSequence + ":" + type)
                .getBytes(StandardCharsets.UTF_8));
        return new CoreMessage(CoreMessageHeader.command(type, commandId, productLine, source, sourceId,
                sourceSequence, userId, 1_700_000_000_000L + sourceSequence, sourceSequence), payload);
    }

    private static CoreResponse applyTerminal(CoreProbeState state, CoreMessage command) {
        CoreResponse response = state.apply(command);
        if (response.resultCode() != CoreResultCode.MATCHING_PENDING) return response;
        long matchingSequence = state.matchingSequence(command.header().commandId());
        com.surprising.aeron.service.matching.CoreMatchingResult matching = null;
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (matching == null && System.nanoTime() < deadline) {
            matching = state.takeMatchingResult(matchingSequence);
            if (matching == null) Thread.onSpinWait();
        }
        assertThat(matching).as("matching completion for %s", command.header().messageType()).isNotNull();
        CoreResponse completed = null;
        deadline = System.nanoTime() + 5_000_000_000L;
        while (completed == null && System.nanoTime() < deadline) {
            completed = state.completeMatching(matchingSequence, matching,
                    command.header().submittedAtEpochMillis(), command.header().sourceSequence());
            if (completed == null) Thread.onSpinWait();
        }
        return completed;
    }

    private static void assertApplied(CoreResponse response) {
        assertThat(response.status()).as("%s", response.resultCode()).isEqualTo(ResponseStatus.APPLIED);
    }

    private static ProductLine other(ProductLine productLine) {
        return ProductLine.values()[(productLine.ordinal() + 1) % ProductLine.values().length];
    }

    private static String settleAsset(ProductLine productLine) {
        return switch (productLine) {
            case INVERSE_PERPETUAL, INVERSE_DELIVERY -> "BTC";
            default -> "USDT";
        };
    }

    private static long economicAssetUnits(CoreProbeState state, String asset) {
        long total = state.tradingState().users().values().stream()
                .map(user -> user.balances().get(asset))
                .filter(java.util.Objects::nonNull)
                .mapToLong(balance -> Math.addExact(balance.availableUnits(), balance.lockedUnits()))
                .reduce(0L, Math::addExact);
        var treasury = state.tradingState().treasuryState();
        total = Math.addExact(total, treasury.feeBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.insuranceBalances().getOrDefault(asset, 0L));
        total = Math.subtractExact(total, treasury.insuranceDeficits().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.liquidationFeeBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.fundingResidualBalances().getOrDefault(asset, 0L));
        total = Math.addExact(total, treasury.roundingResidualBalances().getOrDefault(asset, 0L));
        return Math.addExact(total, treasury.clearingPnlBalances().getOrDefault(asset, 0L));
    }
}
