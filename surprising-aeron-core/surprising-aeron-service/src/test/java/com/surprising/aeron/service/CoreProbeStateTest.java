package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.protocol.AckExportCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.Map;
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
    void queriesCommittedCommandResultAfterResultUnknown() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        UUID commandId = UUID.randomUUID();
        CoreMessage command = command(commandId, 1, 7);

        var applied = state.apply(command);
        var result = state.apply(new CoreMessage(CoreMessageHeader.query(
                CoreMessageType.COMMAND_RESULT_QUERY, UUID.randomUUID(), ProductLine.SPOT,
                CommandSource.GATEWAY, 7, 0, 1001, 1_000, 2),
                CoreStateQueryCodec.encodeCommandResultQuery(commandId)));

        assertThat(result.status()).isEqualTo(ResponseStatus.OK);
        assertThat(result.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(result.resultCode()).isEqualTo(CoreResultCode.NONE);
        assertThat(result.appliedCommandCount()).isEqualTo(applied.appliedCommandCount());
        assertThat(result.stateHash()).isEqualTo(applied.stateHash());

        var unknown = state.apply(new CoreMessage(CoreMessageHeader.query(
                CoreMessageType.COMMAND_RESULT_QUERY, UUID.randomUUID(), ProductLine.SPOT,
                CommandSource.GATEWAY, 7, 0, 1001, 1_000, 3),
                CoreStateQueryCodec.encodeCommandResultQuery(UUID.randomUUID())));
        assertThat(unknown.status()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(unknown.resultCode()).isEqualTo(CoreResultCode.ENTITY_NOT_FOUND);
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
    void restoredStateRejectsUnboundedSourceSequenceRegistry() {
        Map<CoreProbeState.SourceKey, Long> sourceSequences = new java.util.LinkedHashMap<>();
        for (long sourceId = 0; sourceId <= CoreProbeState.MAX_SOURCE_SEQUENCES; sourceId++) {
            sourceSequences.put(new CoreProbeState.SourceKey(CommandSource.GATEWAY, sourceId), 1L);
        }

        assertThatThrownBy(() -> CoreProbeState.restore(ProductLine.SPOT, 0, 0, Map.of(), sourceSequences,
                com.surprising.aeron.service.state.TradingCoreState.empty(ProductLine.SPOT),
                new CoreExportState()))
                .isInstanceOf(IllegalArgumentException.class);
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
                        com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                        com.surprising.aeron.protocol.CorePositionSide.NET,
                        ReservationKind.SPOT_ASSET, "USDT", 2_500,
                        CoreOrderType.LIMIT, CoreTimeInForce.GTC, 1_000, false,
                        "client-91", -10, 20)));

        assertThat(original.apply(adjustment).status()).isEqualTo(ResponseStatus.APPLIED);
        var placeResponse = original.apply(place);
        assertThat(placeResponse.status()).isEqualTo(ResponseStatus.APPLIED);
        var placeResult = com.surprising.aeron.protocol.CoreCommandResultCodec.decode(placeResponse.data());
        assertThat(placeResult.orders()).extracting(value -> value.orderId()).containsExactly(91L);
        assertThat(placeResult.orders().getFirst().clusterPosition()).isPositive();
        assertThat(placeResult.executions()).isEmpty();
        var placeEvent = CoreExportCodec.decodeBatchResponse(original.apply(query(CoreMessageType.EXPORT_BATCH_QUERY, 0,
                        CoreExportCodec.encodeBatchQuery(10))).data()).events().stream()
                .map(message -> CoreExportCodec.decodeEvent(message.payload()))
                .filter(event -> event.commandId().equals(placeId))
                .findFirst().orElseThrow();
        assertThat(placeEvent.changedUsers()).extracting(value -> value.userId()).containsExactly(1001L);
        assertThat(placeEvent.changedOrders()).extracting(value -> value.orderId()).containsExactly(91L);
        var duplicatePlace = original.apply(place);
        assertThat(duplicatePlace.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(com.surprising.aeron.protocol.CoreCommandResultCodec.decode(duplicatePlace.data())
                .orders()).extracting(value -> value.orderId()).containsExactly(91L);
        assertThat(original.tradingState().user(1001).balances().get("USDT").availableUnits()).isEqualTo(7_999);

        var trigger = new com.surprising.aeron.protocol.CoreTriggerOrderStateView(501,
                ProductLine.SPOT, 1001, "tp-501", "", "BTC-USDT", CoreOrderSide.SELL,
                com.surprising.aeron.protocol.CoreTriggerOrderType.TAKE_PROFIT,
                com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL, 70_000,
                0, 0, 0, 0, 0, CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 1,
                CoreMarginMode.CROSS, CorePositionSide.NET,
                com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING, 0, 0, 0,
                "", "trace", 0, 0, 1_000, 1_000, 1);
        CoreMessage triggerPlace = tradingCommand(CoreMessageType.PLACE_TRIGGER_ORDER, UUID.randomUUID(), 3,
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeState(trigger));
        assertThat(original.apply(triggerPlace).status()).isEqualTo(ResponseStatus.APPLIED);
        var triggerEvent = CoreExportCodec.decodeBatchResponse(original.apply(query(CoreMessageType.EXPORT_BATCH_QUERY, 0,
                        CoreExportCodec.encodeBatchQuery(10))).data()).events().stream()
                .map(message -> CoreExportCodec.decodeEvent(message.payload()))
                .filter(event -> event.commandId().equals(triggerPlace.header().commandId()))
                .findFirst().orElseThrow();
        assertThat(triggerEvent.changedTriggerOrders()).extracting(value -> value.triggerOrderId())
                .containsExactly(501L);
        CoreMessage triggerQuery = query(CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY, 1001,
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeQuery(
                        new com.surprising.aeron.protocol.CoreTriggerOrderQuery(0, "BTC-USDT", 0, 10)));
        assertThat(com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeList(original.apply(triggerQuery).data()))
                .extracting(value -> value.triggerOrderId()).containsExactly(501L);

        CoreMessage userQuery = query(CoreMessageType.USER_STATE_QUERY, 1001, new byte[0]);
        CoreMessage orderQuery = query(CoreMessageType.ORDER_STATE_QUERY, 1001,
                TradingCommandCodec.encodeOrderStateQuery(91));
        CoreMessage clientOrderQuery = query(CoreMessageType.CLIENT_ORDER_STATE_QUERY, 1001,
                CoreStateQueryCodec.encodeClientOrderStateQuery("client-91"));
        var userResult = original.apply(userQuery);
        var orderResult = original.apply(orderQuery);
        assertThat(userResult.status()).isEqualTo(ResponseStatus.OK);
        assertThat(CoreStateQueryCodec.decodeUserState(userResult.data()).balances().getFirst().lockedUnits())
                .isEqualTo(2_001);
        assertThat(orderResult.status()).isEqualTo(ResponseStatus.OK);
        assertThat(CoreStateQueryCodec.decodeOrderState(orderResult.data()).orderId()).isEqualTo(91);
        var byClientId = CoreStateQueryCodec.decodeOrderState(original.apply(clientOrderQuery).data());
        assertThat(byClientId.orderId()).isEqualTo(91);
        assertThat(byClientId.commandId()).isEqualTo(placeId);
        assertThat(byClientId.clientOrderId()).isEqualTo("client-91");
        assertThat(byClientId.makerFeeRatePpm()).isEqualTo(-10);
        assertThat(byClientId.takerFeeRatePpm()).isEqualTo(20);
        assertThat(byClientId.createdAtEpochMillis()).isPositive();
        assertThat(byClientId.clusterPosition()).isPositive();
        CoreMessage openOrdersQuery = query(CoreMessageType.USER_OPEN_ORDERS_QUERY, 1001,
                CoreStateQueryCodec.encodeOpenOrdersQuery(
                        new com.surprising.aeron.protocol.CoreOpenOrdersQuery("BTC-USDT", 0, 10)));
        var openOrders = CoreStateQueryCodec.decodeOpenOrders(original.apply(openOrdersQuery).data());
        assertThat(openOrders.orders()).extracting(order -> order.orderId()).containsExactly(91L);
        var book = CoreStateQueryCodec.decodeBookState(
                original.apply(query(CoreMessageType.BOOK_STATE_QUERY, 0, new byte[0])).data());
        assertThat(book.exportSequence()).isEqualTo(4);
        assertThat(book.levels()).singleElement().satisfies(value -> {
            assertThat(value.priceTicks()).isEqualTo(1_000);
            assertThat(value.quantitySteps()).isEqualTo(2);
            assertThat(value.orderCount()).isEqualTo(1);
        });

        UUID duplicateId = UUID.randomUUID();
        CoreMessage duplicateClientOrder = tradingCommand(CoreMessageType.PLACE_ORDER, duplicateId, 4,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(92, "BTC-USDT", 1,
                        "BTC", "USDT", "USDT", CoreOrderSide.BUY, 900, 1, false,
                        com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                        com.surprising.aeron.protocol.CorePositionSide.NET,
                        ReservationKind.SPOT_ASSET, "USDT", 900,
                        CoreOrderType.LIMIT, CoreTimeInForce.GTC, 900, false,
                        "client-91", -10, 20)));
        long availableBeforeDuplicate = original.tradingState().user(1001).balances().get("USDT").availableUnits();
        assertThat(original.apply(duplicateClientOrder).resultCode()).isEqualTo(CoreResultCode.DUPLICATE_CLIENT_ORDER_ID);
        assertThat(original.tradingState().order(92)).isNull();
        assertThat(original.tradingState().user(1001).balances().get("USDT").availableUnits())
                .isEqualTo(availableBeforeDuplicate);

        CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, original.snapshot());
        assertThat(restored.stateHash()).isEqualTo(original.stateHash());
        assertThat(restored.tradingState()).isEqualTo(original.tradingState());
        assertThat(com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeList(restored.apply(triggerQuery).data()))
                .extracting(value -> value.triggerOrderId()).containsExactly(501L);
        assertThat(CoreStateQueryCodec.decodeOpenOrders(restored.apply(openOrdersQuery).data()).orders())
                .extracting(order -> order.orderId()).containsExactly(91L);

        CoreMessage cancel = tradingCommand(CoreMessageType.CANCEL_ORDER, UUID.randomUUID(), 5,
                TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(91)));
        var cancelResponse = restored.apply(cancel);
        assertThat(cancelResponse.status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(com.surprising.aeron.protocol.CoreCommandResultCodec.decode(cancelResponse.data()).orders())
                .extracting(value -> value.status()).containsExactly("CANCELED");
        assertThat(restored.tradingState().user(1001).totalUnits("USDT")).isEqualTo(10_000);
        assertThat(CoreStateQueryCodec.decodeOpenOrders(restored.apply(openOrdersQuery).data()).orders()).isEmpty();
        assertThat(com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeList(restored.apply(triggerQuery).data()))
                .extracting(value -> value.triggerOrderId()).containsExactly(501L);
    }

    @Test
    void orderPreflightUsesAuthoritativeRulesWithoutMutatingState() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 2,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))));
        long hash = state.tradingState().businessStateHash();
        PlaceOrderCommand command = new PlaceOrderCommand(99, "BTC-USDT", 1, "BTC", "USDT", "USDT",
                CoreOrderSide.BUY, 1_000, 2, false,
                com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                com.surprising.aeron.protocol.CorePositionSide.NET,
                ReservationKind.SPOT_ASSET, "USDT", 0,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, 1_000, false, "", 0, 0);
        CoreMessage query = query(CoreMessageType.ORDER_PREFLIGHT_QUERY, 1001,
                TradingCommandCodec.encodePlaceOrder(command));

        var response = state.apply(query);

        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
        assertThat(com.surprising.aeron.protocol.CoreOrderPreflightCodec.decode(response.data()))
                .isEqualTo(new com.surprising.aeron.protocol.CoreOrderPreflightView("USDT", 2_000));
        assertThat(state.tradingState().businessStateHash()).isEqualTo(hash);
        assertThat(state.tradingState().orders()).isEmpty();
    }

    @Test
    void executesTriggerInsideCoreAndDoesNotCreateASecondLifecycleRoundTrip() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 2))));
        var trigger = new com.surprising.aeron.protocol.CoreTriggerOrderStateView(503,
                ProductLine.SPOT, 1001, "tp-503", "", "BTC-USDT", CoreOrderSide.SELL,
                com.surprising.aeron.protocol.CoreTriggerOrderType.TAKE_PROFIT,
                com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL, 70_000,
                0, 0, 0, 0, 0, CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 1,
                CoreMarginMode.CROSS, CorePositionSide.NET,
                com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING, 0, 0, 0,
                "", "trace", 0, 0, 1_000, 1_000, 1);
        state.apply(tradingCommand(CoreMessageType.PLACE_TRIGGER_ORDER, UUID.randomUUID(), 2,
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeState(trigger)));

        CoreMessage execute = tradingCommand(CoreMessageType.EXECUTE_TRIGGER_ORDER, UUID.randomUUID(), 3,
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeExecute(503, 7, 70_000, 2_000));
        var response = state.apply(execute);

        assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(state.tradingState().triggerOrders().get(503L).status())
                .isEqualTo(com.surprising.aeron.protocol.CoreTriggerOrderStatus.TRIGGERED);
        long childOrderId = state.tradingState().triggerOrders().get(503L).placedOrderId();
        assertThat(childOrderId).isPositive();
        assertThat(state.tradingState().order(childOrderId)).isNotNull();
        assertThat(state.apply(execute).status()).isEqualTo(ResponseStatus.DUPLICATE);
    }

    @Test
    void markPriceCommandExecutesOnlyCrossingTriggerCandidatesInsideCore() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 2))));
        var trigger = new com.surprising.aeron.protocol.CoreTriggerOrderStateView(504,
                ProductLine.SPOT, 1001, "tp-504", "oco-504", "BTC-USDT", CoreOrderSide.SELL,
                com.surprising.aeron.protocol.CoreTriggerOrderType.TAKE_PROFIT,
                com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL, 70_000,
                0, 0, 0, 0, 0, CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 1,
                CoreMarginMode.CROSS, CorePositionSide.NET,
                com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING, 0, 0, 0,
                "", "trace", 0, 0, 1_000, 1_000, 1);
        state.apply(tradingCommand(CoreMessageType.PLACE_TRIGGER_ORDER, UUID.randomUUID(), 2,
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeState(trigger)));

        var mark = new ApplyMarkPriceCommand("BTC-USDT", 1, 70_000, 7, 2_000);
        var response = state.apply(tradingCommand(CoreMessageType.APPLY_MARK_PRICE, UUID.randomUUID(), 3,
                TradingCommandCodec.encodeApplyMarkPrice(mark)));

        assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(state.tradingState().triggerOrders().get(504L).status())
                .isEqualTo(com.surprising.aeron.protocol.CoreTriggerOrderStatus.TRIGGERED);
        assertThat(state.tradingState().triggerOrders().get(504L).triggerSequence()).isEqualTo(7);
    }

    @Test
    void expiryQueryUsesIncrementalIndexAndRemovesExpiredTrigger() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 2))));
        var trigger = new com.surprising.aeron.protocol.CoreTriggerOrderStateView(505,
                ProductLine.SPOT, 1001, "tp-505", "", "BTC-USDT", CoreOrderSide.SELL,
                com.surprising.aeron.protocol.CoreTriggerOrderType.TAKE_PROFIT,
                com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL, 70_000,
                0, 0, 0, 0, 0, CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 1,
                CoreMarginMode.CROSS, CorePositionSide.NET,
                com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING, 0, 0, 0,
                "", "trace", 1_500, 0, 1_000, 1_000, 1);
        state.apply(tradingCommand(CoreMessageType.PLACE_TRIGGER_ORDER, UUID.randomUUID(), 2,
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeState(trigger)));

        var expiryQuery = query(CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY, 0,
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeQuery(
                        new com.surprising.aeron.protocol.CoreTriggerOrderQuery(0, "", 0, 10,
                                com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING, 2_000)));
        assertThat(com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeList(state.apply(expiryQuery).data()))
                .extracting(value -> value.triggerOrderId()).containsExactly(505L);

        state.apply(tradingCommand(CoreMessageType.EXPIRE_TRIGGER_ORDER, UUID.randomUUID(), 3,
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeLifecycle(505, 2_000)));
        assertThat(com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeList(state.apply(expiryQuery).data()))
                .isEmpty();
    }

    @Test
    void openInterestQueryReturnsAuthoritativePositionTotals() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);

        var response = state.apply(query(CoreMessageType.OPEN_INTEREST_QUERY, 0, new byte[0]));

        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
        assertThat(com.surprising.aeron.protocol.CoreOpenInterestCodec.decode(response.data())).isEmpty();
    }

    @Test
    void liquidationWorkQueryReturnsOnlyCurrentBoundedPlansAndScanReadiness() {
        var current = new com.surprising.aeron.service.state.CoreLiquidationState(1, 1001, "BTC-USDT",
                CoreMarginMode.CROSS, CorePositionSide.NET, 1, 7, 10, 10, 0, 0, 0, 0,
                com.surprising.aeron.service.state.CoreLiquidationState.Status.PLANNED);
        var stale = new com.surprising.aeron.service.state.CoreLiquidationState(2, 1002, "ETH-USDT",
                CoreMarginMode.ISOLATED, CorePositionSide.LONG, 1, 8, 5, 5, 0, 0, 0, 0,
                com.surprising.aeron.service.state.CoreLiquidationState.Status.PLANNED);
        var risk = new com.surprising.aeron.service.state.CoreRiskState(
                Map.of("BTC-USDT", new com.surprising.aeron.service.state.CoreMarkPriceState(
                                "BTC-USDT", 1, 81, 7),
                        "ETH-USDT", new com.surprising.aeron.service.state.CoreMarkPriceState(
                                "ETH-USDT", 1, 120, 9)),
                Map.of(), Map.of(1L, current, 2L, stale),
                Map.of("BTC-USDT", new com.surprising.aeron.service.state.CoreRiskState.RiskScan(
                        "BTC-USDT", 7, 7, 1001, false)), 3);
        var trading = new com.surprising.aeron.service.state.TradingCoreState(ProductLine.SPOT, 1,
                Map.of(), Map.of(), com.surprising.aeron.service.state.CoreBookState.empty(), Map.of(), risk,
                com.surprising.aeron.service.state.CoreTreasuryState.empty());
        CoreProbeState state = CoreProbeState.restore(ProductLine.SPOT, 0, 0,
                Map.of(), Map.of(), trading, new CoreExportState());

        var response = state.apply(query(CoreMessageType.LIQUIDATION_WORK_QUERY, 0,
                com.surprising.aeron.protocol.CoreLiquidationWorkCodec.encodeQuery(1)));
        var work = com.surprising.aeron.protocol.CoreLiquidationWorkCodec.decodeWork(response.data());

        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
        assertThat(work.riskScanPending()).isTrue();
        assertThat(work.actions()).singleElement().satisfies(action -> {
            assertThat(action.liquidationId()).isEqualTo(1);
            assertThat(action.markPriceTicks()).isEqualTo(81);
            assertThat(action.triggerPriceSequence()).isEqualTo(7);
        });
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

    @Test
    void exportBacklogIsOrderedAckedAndRestoredWithSnapshot() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        CoreMessage first = command(UUID.randomUUID(), 1, 7);
        CoreMessage second = command(UUID.randomUUID(), 2, 3);
        state.apply(first);
        state.apply(second);

        var batchResult = state.apply(query(CoreMessageType.EXPORT_BATCH_QUERY, 0,
                CoreExportCodec.encodeBatchQuery(10)));
        var batch = CoreExportCodec.decodeBatchResponse(batchResult.data()).events();

        assertThat(batch).hasSize(2);
        assertThat(batch).extracting(message -> CoreExportCodec.decodeEvent(message.payload()).exportSequence())
                .containsExactly(1L, 2L);
        assertThat(CoreExportCodec.decodeEvent(batch.getFirst().payload()).commandId())
                .isEqualTo(first.header().commandId());

        CoreMessage ack = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ACK_EXPORT,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 81, 1, 0, 2_000, 81),
                CoreExportCodec.encodeAck(new AckExportCommand(1)));
        assertThat(state.apply(ack).status()).isEqualTo(ResponseStatus.APPLIED);

        CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, state.snapshot());
        var status = CoreExportCodec.decodeStatus(restored.apply(query(
                CoreMessageType.EXPORT_STATUS_QUERY, 0, new byte[0])).data());
        var remaining = CoreExportCodec.decodeBatchResponse(restored.apply(query(
                CoreMessageType.EXPORT_BATCH_QUERY, 0, CoreExportCodec.encodeBatchQuery(10))).data()).events();

        assertThat(status.acknowledgedSequence()).isEqualTo(1);
        assertThat(status.nextSequence()).isEqualTo(3);
        assertThat(status.pendingCount()).isEqualTo(1);
        assertThat(status.pendingBytes()).isPositive();
        assertThat(status.acceptingCommands()).isTrue();
        assertThat(remaining).hasSize(1);
        assertThat(CoreExportCodec.decodeEvent(remaining.getFirst().payload()).exportSequence()).isEqualTo(2);
    }

    @Test
    void snapshotChecksumRejectsCorruption() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        state.apply(command(UUID.randomUUID(), 1, 7));
        byte[] snapshot = state.snapshot();
        snapshot[snapshot.length - Long.BYTES - 1] ^= 1;

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> CoreProbeState.fromSnapshot(ProductLine.SPOT, snapshot))
                .isInstanceOf(com.surprising.aeron.protocol.ProtocolException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void rejectsUnsupportedSnapshotVersion() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        byte[] snapshot = state.snapshot();
        snapshot[4] = 2;
        snapshot[5] = 0;

        assertThatThrownBy(() -> CoreProbeState.fromSnapshot(ProductLine.SPOT, snapshot))
                .isInstanceOf(com.surprising.aeron.protocol.ProtocolException.class)
                .hasMessageContaining("unsupported snapshot version");
    }

    @Test
    void snapshotManifestReportsAuthoritativeMetadata() {
        CoreProbeState state = new CoreProbeState(ProductLine.OPTION);
        state.apply(command(ProductLine.OPTION, UUID.randomUUID(), 1, 3));

        CoreSnapshotManifest manifest = CoreProbeState.inspectSnapshot(ProductLine.OPTION, state.snapshot());

        assertThat(manifest.productLine()).isEqualTo(ProductLine.OPTION);
        assertThat(manifest.schemaVersion()).isEqualTo(3);
        assertThat(manifest.appliedCommandCount()).isEqualTo(1);
        assertThat(manifest.businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
        assertThat(manifest.exportStatus().pendingCount()).isEqualTo(1);
        assertThat(manifest.checksum()).isPositive();
    }

    @Test
    void rollingBusinessHashMatchesAuthoritativeStateAfterMutationsAndRestore() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        for (int sequence = 1; sequence <= 8; sequence++) {
            assertThat(state.apply(command(UUID.randomUUID(), sequence, sequence)).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.apply(query(CoreMessageType.BUSINESS_STATE_HASH_QUERY, 0, new byte[0])).stateHash())
                    .isEqualTo(state.tradingState().businessStateHash());
        }

        CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, state.snapshot());
        assertThat(restored.apply(query(CoreMessageType.BUSINESS_STATE_HASH_QUERY, 0, new byte[0])).stateHash())
                .isEqualTo(restored.tradingState().businessStateHash());
    }

    @Test
    void rejectsAckAheadWithExplicitResultAndAllowsRetry() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        state.apply(command(UUID.randomUUID(), 1, 7));
        UUID commandId = UUID.randomUUID();
        CoreMessage ackAhead = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ACK_EXPORT,
                commandId, ProductLine.SPOT, CommandSource.OPERATIONS, 81, 1, 0, 2_000, 81),
                CoreExportCodec.encodeAck(new AckExportCommand(2)));

        var rejected = state.apply(ackAhead);
        var duplicate = state.apply(ackAhead);

        assertThat(rejected.status()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(rejected.resultCode()).isEqualTo(CoreResultCode.EXPORT_ACK_AHEAD);
        assertThat(duplicate.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(duplicate.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
        assertThat(duplicate.resultCode()).isEqualTo(CoreResultCode.EXPORT_ACK_AHEAD);
    }

    @Test
    void exportAckCompactsTerminalReservationWithoutReleasingOrderIdentity() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        applySpotInstrument(state);
        assertThat(state.apply(tradingCommand(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 2,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000))))
                .status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(state.apply(tradingCommand(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 3,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(901, "BTC-USDT", 1,
                        "BTC", "USDT", "USDT", CoreOrderSide.BUY, 1_000, 2, false,
                        CoreMarginMode.CROSS, CorePositionSide.NET, ReservationKind.SPOT_ASSET, "USDT",
                        2_000, CoreOrderType.LIMIT, CoreTimeInForce.GTC, 1_000, false,
                        "client-901", 0, 0)))).status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(state.apply(tradingCommand(CoreMessageType.CANCEL_ORDER, UUID.randomUUID(), 4,
                TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(901)))).status())
                .isEqualTo(ResponseStatus.APPLIED);
        assertThat(state.tradingState().user(1001).reservations()).containsKey(901L);

        long throughSequence = state.exportState().nextSequence() - 1;
        CoreMessage ack = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ACK_EXPORT,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 9, 2, 0, 1_000, 5),
                CoreExportCodec.encodeAck(new AckExportCommand(throughSequence)));

        assertThat(state.apply(ack).status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(state.tradingState().user(1001).reservations()).doesNotContainKey(901L);
        assertThat(state.tradingState().order(901).status().name()).isEqualTo("CANCELED");
        assertThat(state.tradingState().order(1001, "client-901").orderId()).isEqualTo(901L);

        CoreMessage reusedOrderId = tradingCommand(CoreMessageType.PLACE_ORDER, UUID.randomUUID(), 5,
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(901, "BTC-USDT", 1,
                        "BTC", "USDT", "USDT", CoreOrderSide.BUY, 900, 1, false,
                        CoreMarginMode.CROSS, CorePositionSide.NET, ReservationKind.SPOT_ASSET, "USDT",
                        900, CoreOrderType.LIMIT, CoreTimeInForce.GTC, 900, false,
                        "client-902", 0, 0)));
        assertThat(state.apply(reusedOrderId).resultCode()).isEqualTo(CoreResultCode.DUPLICATE_ORDER_ID);
        assertThat(CoreProbeState.fromSnapshot(ProductLine.SPOT, state.snapshot()).tradingState().user(1001)
                .reservations()).doesNotContainKey(901L);
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
