package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreBalanceView;
import com.surprising.aeron.protocol.CoreCommandResultCodec;
import com.surprising.aeron.protocol.CoreCommandResultView;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CorePositionView;
import com.surprising.aeron.protocol.CoreReservationView;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreFundingProgressCodec;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.protocol.CoreSettlementProgressCodec;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.service.state.CoreStateRejectedException;
import com.surprising.aeron.service.state.OpenInterestIndex;
import com.surprising.aeron.service.state.PositionUserIndex;
import com.surprising.aeron.service.state.TriggerOrderIndex;
import com.surprising.aeron.service.state.TradingCoreReducer;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import com.surprising.product.api.ProductLine;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CoreProbeState implements AutoCloseable {

    static final int MAX_IDEMPOTENCY_RESULTS = 128;
    static final int MAX_STORED_RESPONSE_BYTES = 1 * 1024 * 1024;
    static final int MAX_SOURCE_SEQUENCES = 65_536;
    private static final long HASH_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long HASH_PRIME = 0x100000001b3L;

    private final ProductLine productLine;
    private final TradingCoreRuntime runtime;
    private final LinkedHashMap<UUID, StoredResult> commandResults;
    private final LinkedHashMap<SourceKey, Long> lastSourceSequences;
    private final TradingCoreReducer tradingReducer;
    private final DeterministicExchangeCoreAdapter matchingAdapter;
    private final PositionUserIndex positionUserIndex;
    private final OpenInterestIndex openInterestIndex;
    private final TriggerOrderIndex triggerOrderIndex;
    private final CoreExportState exportState;
    private final com.surprising.aeron.service.state.RollingBusinessStateHash rollingBusinessStateHash;
    private long appliedCommandCount;
    private long probeValue;
    private long cachedBusinessStateHash;
    private long lastSourceSequenceDigest;
    private TradingCoreState tradingState;
    private List<CoreOrderStateView> commandOrderViews = List.of();
    private List<Long> commandChangedUserIds;
    private List<Long> commandChangedOrderIds;
    private List<Long> commandChangedLiquidationIds;
    private List<Long> commandChangedTriggerOrderIds;
    private List<com.surprising.aeron.protocol.CoreExecutionView> commandExecutions = List.of();
    private List<com.surprising.aeron.protocol.CoreFundingPaymentView> commandFundingPayments = List.of();
    private CoreFundingProgressView commandFundingProgress;
    private CoreSettlementProgressView commandSettlementProgress;
    private CoreCommandDelta commandDelta = CoreCommandDelta.empty();

    public CoreProbeState(ProductLine productLine) {
        this(productLine, 0, 0, new LinkedHashMap<>(), new LinkedHashMap<>(),
                TradingCoreState.empty(productLine), new CoreExportState());
    }

    private CoreProbeState(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            LinkedHashMap<UUID, StoredResult> commandResults,
            LinkedHashMap<SourceKey, Long> lastSourceSequences,
            TradingCoreState tradingState,
            CoreExportState exportState) {
        this.productLine = productLine;
        this.appliedCommandCount = appliedCommandCount;
        this.probeValue = probeValue;
        this.commandResults = commandResults;
        this.lastSourceSequences = lastSourceSequences;
        this.tradingState = tradingState;
        this.rollingBusinessStateHash = com.surprising.aeron.service.state.RollingBusinessStateHash.create(tradingState);
        this.cachedBusinessStateHash = rollingBusinessStateHash.value();
        this.lastSourceSequenceDigest = sourceSequenceDigest(lastSourceSequences);
        this.exportState = exportState;
        this.runtime = new TradingCoreRuntime(productLine, tradingState);
        this.tradingReducer = runtime.reducer();
        this.matchingAdapter = runtime.matcher();
        this.positionUserIndex = runtime.positionUsers();
        this.openInterestIndex = runtime.openInterest();
        this.triggerOrderIndex = runtime.triggers();
    }

    static CoreProbeState restore(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            Map<UUID, StoredResult> commandResults,
            Map<SourceKey, Long> lastSourceSequences,
            TradingCoreState tradingState,
            CoreExportState exportState) {
        if (appliedCommandCount < 0 || commandResults.size() > MAX_IDEMPOTENCY_RESULTS
                || lastSourceSequences.size() > MAX_SOURCE_SEQUENCES
                || tradingState == null || tradingState.productLine() != productLine || exportState == null) {
            throw new IllegalArgumentException("invalid restored probe state");
        }
        return new CoreProbeState(productLine, appliedCommandCount, probeValue,
                new LinkedHashMap<>(commandResults), new LinkedHashMap<>(lastSourceSequences), tradingState,
                exportState);
    }

    public CoreResponse apply(CoreMessage message) {
        return apply(message, message.header().submittedAtEpochMillis(), Math.addExact(appliedCommandCount, 1));
    }

    public CoreResponse apply(CoreMessage message, long clusterTimestamp, long clusterPosition) {
        runtime.assertOwner();
        if (message.header().productLine() != productLine) {
            return rejected(CoreResultCode.PRODUCT_LINE_MISMATCH);
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.STATE_HASH_QUERY) {
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, stateHash());
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.BUSINESS_STATE_HASH_QUERY) {
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash);
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.USER_STATE_HASH_QUERY) {
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount,
                    tradingState.userStateHash(message.header().userId()));
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.ORDER_STATE_HASH_QUERY) {
            try {
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount,
                        tradingState.orderStateHash(TradingCommandCodec.decodeOrderStateQuery(message.payload())));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.USER_STATE_QUERY) {
            return userStateResponse(message.header().userId());
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.ORDER_STATE_QUERY) {
            try {
                return orderStateResponse(TradingCommandCodec.decodeOrderStateQuery(message.payload()));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.CLIENT_ORDER_STATE_QUERY) {
            try {
                return orderStateResponse(tradingState.order(message.header().userId(),
                        CoreStateQueryCodec.decodeClientOrderStateQuery(message.payload())));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.USER_OPEN_ORDERS_QUERY) {
            try {
                var query = CoreStateQueryCodec.decodeOpenOrdersQuery(message.payload());
                long beforeOrderId = query.beforeOrderId() == 0 ? Long.MAX_VALUE : query.beforeOrderId();
                long requestedUserId = message.header().userId();
                var orders = openOrders(requestedUserId)
                        .filter(order -> order.status().name().equals("OPEN"))
                        .filter(order -> query.symbol().isEmpty() || order.symbol().equals(query.symbol()))
                        .filter(order -> order.orderId() < beforeOrderId)
                        .limit(query.limit())
                        .map(CoreProbeState::orderView)
                        .toList();
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        CoreStateQueryCodec.encodeOpenOrders(
                                new com.surprising.aeron.protocol.CoreOpenOrdersView(orders)));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && (message.header().messageType() == CoreMessageType.TRIGGER_ORDER_QUERY
                || message.header().messageType() == CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY)) {
            try {
                var query = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeQuery(message.payload());
                long before = query.beforeTriggerOrderId() == 0 ? Long.MAX_VALUE : query.beforeTriggerOrderId();
                java.util.stream.Stream<com.surprising.aeron.service.state.CoreTriggerOrderState> source = query.symbol().isEmpty()
                        ? (message.header().userId() == 0 ? triggerOrderIndex.ids() : triggerOrderIndex.ids(message.header().userId())).stream()
                                .map(tradingState.triggerOrders()::get)
                                .filter(java.util.Objects::nonNull)
                        : triggerOrderIndex.ids(query.symbol()).stream()
                                .map(tradingState.triggerOrders()::get)
                                .filter(java.util.Objects::nonNull);
                var values = source
                        .filter(order -> message.header().userId() == 0 || order.userId() == message.header().userId())
                        .filter(order -> query.triggerOrderId() == 0 || order.triggerOrderId() == query.triggerOrderId())
                        .filter(order -> query.symbol().isEmpty() || order.symbol().equals(query.symbol()))
                        .filter(order -> query.triggerOrderId() != 0 || order.triggerOrderId() < before)
                        .filter(order -> message.header().messageType() == CoreMessageType.TRIGGER_ORDER_QUERY
                                || order.status().open())
                        .sorted(java.util.Comparator.comparingLong(
                                com.surprising.aeron.service.state.CoreTriggerOrderState::triggerOrderId).reversed())
                        .limit(query.limit())
                        .map(com.surprising.aeron.service.state.CoreTriggerOrderState::view).toList();
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeList(values));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.BOOK_STATE_QUERY) {
            try {
                var query = message.payload().length == 0
                        ? new com.surprising.aeron.protocol.CoreBookStateQuery("", 1_000)
                        : CoreStateQueryCodec.decodeBookStateQuery(message.payload());
                var view = new com.surprising.aeron.protocol.CoreBookStateView(
                        Math.decrementExact(exportState.nextSequence()),
                        matchingAdapter.orderBookLevels(query.symbol(), query.depth()));
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        CoreStateQueryCodec.encodeBookState(view));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.EXPORT_BATCH_QUERY) {
            try {
                int maxEvents = CoreExportCodec.decodeBatchQuery(message.payload());
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, stateHash(),
                        CoreExportCodec.encodeBatch(exportState.batch(maxEvents)));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.EXPORT_STATUS_QUERY) {
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, stateHash(),
                    CoreExportCodec.encodeStatus(exportState.status()));
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.TREASURY_STATE_QUERY) {
            java.util.TreeSet<String> assets = new java.util.TreeSet<>();
            assets.addAll(tradingState.treasuryState().feeBalances().keySet());
            assets.addAll(tradingState.treasuryState().insuranceBalances().keySet());
            assets.addAll(tradingState.treasuryState().insuranceDeficits().keySet());
            var views = assets.stream().map(asset -> new com.surprising.aeron.protocol.CoreTreasuryAssetView(asset,
                    tradingState.treasuryState().feeBalances().getOrDefault(asset, 0L),
                    tradingState.treasuryState().insuranceBalances().getOrDefault(asset, 0L),
                    tradingState.treasuryState().insuranceDeficits().getOrDefault(asset, 0L))).toList();
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                    CoreStateQueryCodec.encodeTreasuryState(views));
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.FUNDING_PROGRESS_QUERY) {
            try {
                String symbol = CoreStateQueryCodec.decodeFundingProgressQuery(message.payload());
                var treasury = tradingState.treasuryState();
                var progress = treasury.fundingProgress(symbol);
                long settledSettlementId = treasury.fundingSettlement(symbol);
                CoreFundingProgressView view = progress == null
                        ? new CoreFundingProgressView(settledSettlementId, true, 0, 0)
                        : new CoreFundingProgressView(progress.settlementId(), false,
                                progress.nextCursorUserId(), 0);
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        CoreFundingProgressCodec.encode(view));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.SETTLEMENT_PROGRESS_QUERY) {
            try {
                String symbol = CoreStateQueryCodec.decodeSettlementProgressQuery(message.payload());
                var treasury = tradingState.treasuryState();
                var progress = treasury.lifecycleProgress(symbol);
                long settledSettlementId = treasury.lifecycleSettlement(symbol);
                CoreSettlementProgressView view = progress == null
                        ? new CoreSettlementProgressView(settledSettlementId, true, 0, 0)
                        : new CoreSettlementProgressView(progress.settlementId(), false,
                                progress.nextCursorUserId(), 0);
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        CoreSettlementProgressCodec.encode(view));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.ADL_CANDIDATE_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreAdlQueryCodec.decodeQuery(message.payload());
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreAdlQueryCodec.encodeCandidates(
                                tradingReducer.adlCandidates(tradingState, query.asset(), query.limit())));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.RISK_STATE_QUERY) {
            var views = tradingReducer.riskSnapshots(tradingState, message.header().userId());
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                    com.surprising.aeron.protocol.CoreRiskQueryCodec.encode(views));
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.OPEN_INTEREST_QUERY) {
            var views = openInterestIndex.totals().entrySet().stream()
                    .map(entry -> new com.surprising.aeron.protocol.CoreOpenInterestView(
                            entry.getKey(), entry.getValue().longQuantity(), entry.getValue().shortQuantity()))
                    .toList();
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                    com.surprising.aeron.protocol.CoreOpenInterestCodec.encode(views));
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.ALGO_ORDER_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreAlgoOrderCodec.decodeQuery(message.payload());
                var values = tradingState.algoOrders().values().stream()
                        .filter(value -> query.userId() == 0 || value.userId() == query.userId())
                        .filter(value -> query.algoOrderId() == 0 || value.algoOrderId() == query.algoOrderId())
                        .filter(value -> query.symbol().isEmpty() || value.symbol().equalsIgnoreCase(query.symbol()))
                        .filter(value -> query.dueAtEpochMillis() == 0 || value.nextSliceAtEpochMillis() > 0
                                && value.nextSliceAtEpochMillis() <= query.dueAtEpochMillis())
                        .sorted(java.util.Comparator.comparingLong(com.surprising.aeron.service.state.CoreAlgoOrderState::nextSliceAtEpochMillis)
                                .thenComparingLong(com.surprising.aeron.service.state.CoreAlgoOrderState::algoOrderId))
                        .limit(query.limit()).map(this::algoView).toList();
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreAlgoOrderCodec.encodeList(values));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.CANCEL_ALL_AFTER_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreCancelAllAfterCodec.decodeQuery(message.payload());
                var values = tradingState.cancelAllAfterTimers().values().stream()
                        .filter(value -> query.userId() == 0 || value.userId() == query.userId())
                        .filter(value -> query.symbolScope().isEmpty()
                                || value.symbolScope().equals(query.symbolScope()))
                        .filter(value -> query.dueAtEpochMillis() == 0
                                || value.status() == com.surprising.aeron.protocol.CoreCancelAllAfterStatus.ACTIVE
                                && value.triggerAtEpochMillis() <= query.dueAtEpochMillis())
                        .sorted(java.util.Comparator
                                .comparingLong(com.surprising.aeron.service.state.CoreCancelAllAfterState::triggerAtEpochMillis)
                                .thenComparingLong(com.surprising.aeron.service.state.CoreCancelAllAfterState::userId)
                                .thenComparing(com.surprising.aeron.service.state.CoreCancelAllAfterState::symbolScope))
                        .limit(query.limit())
                        .map(com.surprising.aeron.service.state.CoreCancelAllAfterState::view)
                        .toList();
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreCancelAllAfterCodec.encodeList(values));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.LIQUIDATION_WORK_QUERY) {
            try {
                int limit = com.surprising.aeron.protocol.CoreLiquidationWorkCodec.decodeQuery(message.payload());
                var actions = tradingState.riskState().liquidations().values().stream()
                        .filter(value -> value.status()
                                == com.surprising.aeron.service.state.CoreLiquidationState.Status.PLANNED)
                        .sorted(java.util.Comparator.comparingLong(
                                com.surprising.aeron.service.state.CoreLiquidationState::liquidationId))
                        .map(value -> {
                            var mark = tradingState.riskState().markPrices().get(value.symbol());
                            if (mark == null || mark.priceSequence() != value.triggerPriceSequence()) return null;
                            return new com.surprising.aeron.protocol.CoreLiquidationActionView(
                                    value.liquidationId(), value.userId(), value.symbol(), value.marginMode(),
                                    value.positionSide(), value.instrumentVersion(), value.triggerPriceSequence(),
                                    value.signedQuantitySteps(), value.closeQuantitySteps(), mark.markPriceTicks());
                        })
                        .filter(java.util.Objects::nonNull)
                        .limit(limit)
                        .toList();
                var work = new com.surprising.aeron.protocol.CoreLiquidationWorkView(
                        tradingState.riskState().hasPendingScans(), actions);
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreLiquidationWorkCodec.encodeWork(work));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.ORDER_PREFLIGHT_QUERY) {
            try {
                var command = TradingCommandCodec.decodePlaceOrder(message.payload());
                TradingCoreState preview = tradingReducer.placeOrder(tradingState, message.header().userId(), command,
                        message.header().commandId(), openInterestIndex.openInterestSteps(command.symbol()));
                var reservation = preview.user(message.header().userId()).reservations().get(command.orderId());
                if (reservation == null) throw new IllegalStateException("preflight reservation is missing");
                var view = new com.surprising.aeron.protocol.CoreOrderPreflightView(
                        reservation.asset(), reservation.reservedUnits());
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreOrderPreflightCodec.encode(view));
            } catch (CoreStateRejectedException exception) {
                return rejected(CoreResultCode.fromRejectionCode(exception.code()));
            } catch (ArithmeticException exception) {
                return rejected(CoreResultCode.ARITHMETIC_OVERFLOW);
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        StoredResult duplicate = commandResults.get(message.header().commandId());
        if (duplicate != null) {
            return new CoreResponse(ResponseStatus.DUPLICATE,
                    duplicate.status(),
                    duplicate.resultCode(),
                    duplicate.appliedCommandCount(), duplicate.stateHash(), duplicate.responseData());
        }
        if (message.header().kind() != WireMessageKind.COMMAND) {
            return rejected(CoreResultCode.INVALID_MESSAGE);
        }
        SourceKey sourceKey = new SourceKey(message.header().source(), message.header().sourceId());
        Long lastSourceSequence = lastSourceSequences.get(sourceKey);
        if (lastSourceSequence != null && message.header().sourceSequence() <= lastSourceSequence) {
            return new CoreResponse(ResponseStatus.DUPLICATE, ResponseStatus.DUPLICATE,
                    CoreResultCode.STALE_SOURCE_SEQUENCE, appliedCommandCount, stateHash());
        }
        if (lastSourceSequence == null && lastSourceSequences.size() >= MAX_SOURCE_SEQUENCES) {
            return rejected(CoreResultCode.SOURCE_SEQUENCE_TRACKING_FULL);
        }
        ResponseStatus status;
        CoreResultCode resultCode = CoreResultCode.NONE;
        boolean exportCommand = message.header().messageType() != CoreMessageType.ACK_EXPORT;
        if (exportCommand && !exportState.hasCapacityFor(message)) {
            return rejected(CoreResultCode.EXPORT_BACKLOG_FULL);
        }
        if (exportCommand && message.payload().length > CoreExportCodec.MAX_COMMAND_PAYLOAD) {
            return rejected(CoreResultCode.INVALID_MESSAGE);
        }
        TradingCoreState beforeTradingState = tradingState;
        commandOrderViews = List.of();
        commandChangedUserIds = null;
        commandChangedOrderIds = null;
        commandChangedLiquidationIds = List.of();
        commandChangedTriggerOrderIds = List.of();
        commandExecutions = List.of();
        commandFundingPayments = List.of();
        commandFundingProgress = null;
        commandSettlementProgress = null;
        commandDelta = CoreCommandDelta.empty();
        try {
            status = applyCommand(message);
        } catch (CoreStateRejectedException exception) {
            status = ResponseStatus.REJECTED;
            resultCode = CoreResultCode.fromRejectionCode(exception.code());
        } catch (ArithmeticException exception) {
            status = ResponseStatus.REJECTED;
            resultCode = CoreResultCode.ARITHMETIC_OVERFLOW;
        } catch (IllegalArgumentException exception) {
            status = ResponseStatus.REJECTED;
            resultCode = CoreResultCode.INVALID_COMMAND;
        }
        if (status == null) {
            return rejected(CoreResultCode.INVALID_MESSAGE);
        }
        boolean tradingStateChanged = status == ResponseStatus.APPLIED && tradingState != beforeTradingState;
        if (status == ResponseStatus.APPLIED) {
            List<Long> changedOrderIds = commandChangedOrderIds == null ? List.of() : commandChangedOrderIds;
            tradingState = tradingState.stampOrderChanges(beforeTradingState, clusterTimestamp, clusterPosition,
                    changedOrderIds);
            runtime.transition(beforeTradingState, tradingState);
        }
        if (tradingStateChanged) {
            rollingBusinessStateHash.update(beforeTradingState, tradingState);
        }
        commandDelta = commandDelta(beforeTradingState, tradingState);
        long businessStateHash = tradingStateChanged
                ? rollingBusinessStateHash.value() : cachedBusinessStateHash;
        if (exportCommand) {
            try {
                exportState.append(message, status, resultCode, Math.incrementExact(appliedCommandCount),
                        businessStateHash, changedUsers(beforeTradingState, tradingState, commandDelta.userIds()),
                        changedOrders(beforeTradingState, tradingState, commandDelta.orderIds()), commandDelta.executions(),
                        commandDelta.fundingPayments(),
                        changedLiquidations(beforeTradingState, tradingState, commandDelta.liquidationIds()),
                        changedTreasuryAssets(beforeTradingState, tradingState),
                        changedTriggerOrders(beforeTradingState, tradingState, commandDelta.triggerOrderIds()));
            } catch (CoreStateRejectedException exception) {
                if (!"EXPORT_BACKLOG_FULL".equals(exception.code())) throw exception;
                if (tradingStateChanged) rollingBusinessStateHash.update(tradingState, beforeTradingState);
                tradingState = beforeTradingState;
                runtime.restore(beforeTradingState);
                if (matchingCommand(message.header().messageType())) matchingAdapter.rebuild(beforeTradingState);
                return rejected(CoreResultCode.EXPORT_BACKLOG_FULL);
            }
        }
        cachedBusinessStateHash = businessStateHash;
        appliedCommandCount = Math.incrementExact(appliedCommandCount);
        Long previousSourceSequence = lastSourceSequences.put(sourceKey, message.header().sourceSequence());
        if (previousSourceSequence != null) {
            lastSourceSequenceDigest ^= sourceSequenceDigest(sourceKey, previousSourceSequence);
        }
        lastSourceSequenceDigest ^= sourceSequenceDigest(sourceKey, message.header().sourceSequence());
        if (commandResults.size() >= MAX_IDEMPOTENCY_RESULTS) {
            UUID oldest = commandResults.keySet().iterator().next();
            commandResults.remove(oldest);
        }
        long stateHash = stateHash(businessStateHash, message.header().commandId(), status, resultCode,
                appliedCommandCount);
        byte[] responseData = message.header().messageType() == CoreMessageType.ACK_EXPORT
                && status == ResponseStatus.APPLIED
                ? CoreExportCodec.encodeStatus(exportState.status()) : commandResultData();
        byte[] storedResponseData = responseData.length <= MAX_STORED_RESPONSE_BYTES
                ? responseData : new byte[0];
        commandResults.put(message.header().commandId(),
                new StoredResult(status, resultCode, appliedCommandCount, stateHash, storedResponseData));
        return new CoreResponse(status, status, resultCode, appliedCommandCount, stateHash, responseData);
    }

    public long stateHash() {
        return stateHash(cachedBusinessStateHash);
    }

    private long stateHash(long businessStateHash) {
        return stateHash(businessStateHash, null, null, null, 0);
    }

    private long stateHash(long businessStateHash, UUID commandId, ResponseStatus commandStatus,
                           CoreResultCode commandResultCode, long commandAppliedCommandCount) {
        long hash = HASH_OFFSET_BASIS;
        hash = mix(hash, productLine.ordinal());
        hash = mix(hash, appliedCommandCount);
        hash = mix(hash, probeValue);
        hash = mix(hash, businessStateHash);
        hash = mix(hash, exportState.acknowledgedSequence());
        hash = mix(hash, exportState.nextSequence());
        hash = mix(hash, exportState.pendingCount());
        hash = mix(hash, exportState.pendingDigest());
        hash = mix(hash, lastSourceSequenceDigest);
        for (Map.Entry<UUID, StoredResult> entry : commandResults.entrySet()) {
            hash = mix(hash, entry.getKey().getMostSignificantBits());
            hash = mix(hash, entry.getKey().getLeastSignificantBits());
            hash = mix(hash, entry.getValue().status().wireCode());
            hash = mix(hash, entry.getValue().resultCode().wireCode());
            hash = mix(hash, entry.getValue().appliedCommandCount());
        }
        if (commandId != null) {
            hash = mix(hash, commandId.getMostSignificantBits());
            hash = mix(hash, commandId.getLeastSignificantBits());
            hash = mix(hash, commandStatus.wireCode());
            hash = mix(hash, commandResultCode.wireCode());
            hash = mix(hash, commandAppliedCommandCount);
        }
        return hash;
    }

    public byte[] snapshot() {
        return CoreStateSnapshotCodec.encode(this);
    }

    public static CoreProbeState fromSnapshot(ProductLine productLine, byte[] snapshot) {
        return CoreStateSnapshotCodec.decode(snapshot, productLine);
    }

    public static CoreSnapshotManifest inspectSnapshot(ProductLine productLine, byte[] snapshot) {
        return CoreStateSnapshotCodec.manifest(snapshot, productLine);
    }

    public ProductLine productLine() {
        return productLine;
    }

    public long appliedCommandCount() {
        return appliedCommandCount;
    }

    public long probeValue() {
        return probeValue;
    }

    public TradingCoreState tradingState() {
        return tradingState;
    }

    CoreExportState exportState() {
        return exportState;
    }

    Map<UUID, StoredResult> commandResults() {
        return Collections.unmodifiableMap(commandResults);
    }

    Map<SourceKey, Long> lastSourceSequences() {
        return Collections.unmodifiableMap(lastSourceSequences);
    }

    private ResponseStatus applyCommand(CoreMessage message) {
        switch (message.header().messageType()) {
            case SETTLE_INSTRUMENT, EXECUTE_LIQUIDATION, EXECUTE_ADL, RESOLVE_LIQUIDATION,
                    CONTINUE_RISK_SCAN -> commandChangedLiquidationIds = null;
            case PLACE_TRIGGER_ORDER, CANCEL_TRIGGER_ORDER, CLAIM_TRIGGER_ORDER, COMPLETE_TRIGGER_ORDER,
                    UPDATE_TRIGGER_TRAILING, EXPIRE_TRIGGER_ORDER, RETRY_TRIGGER_ORDER,
                    EXECUTE_TRIGGER_ORDER -> commandChangedTriggerOrderIds = null;
            default -> {
            }
        }
        switch (message.header().messageType()) {
            case PROBE_INCREMENT -> probeValue = Math.addExact(
                    probeValue, CoreProtocol.decodeProbeDelta(message.payload()));
            case VERIFY_STATE_HASH -> {
            }
            case ADJUST_BALANCE -> {
                commandChangedUserIds = List.of(message.header().userId());
                tradingState = tradingReducer.adjustBalance(
                        tradingState, message.header().userId(),
                        TradingCommandCodec.decodeBalanceAdjustment(message.payload()));
            }
            case PLACE_ORDER -> placeOrder(message);
            case CANCEL_ORDER -> cancelOrder(message);
            case REPLACE_ORDER -> replaceOrder(message);
            case AMEND_ORDER -> amendOrder(message);
            case UPSERT_INSTRUMENT -> tradingState = tradingReducer.upsertInstrument(tradingState,
                    TradingCommandCodec.decodeUpsertInstrument(message.payload()));
            case APPLY_MARK_PRICE -> tradingState = tradingReducer.applyMarkPrice(tradingState,
                    TradingCommandCodec.decodeApplyMarkPrice(message.payload()));
            case APPLY_FUNDING -> {
                var command = TradingCommandCodec.decodeApplyFunding(message.payload());
                var result = tradingReducer.applyFundingWithFacts(tradingState,
                        command, positionUserIndex.users(command.symbol()), message.header().commandId());
                tradingState = result.state();
                commandFundingPayments = result.payments();
                commandFundingProgress = result.progress();
                commandChangedUserIds = commandFundingPayments.stream()
                        .map(com.surprising.aeron.protocol.CoreFundingPaymentView::userId).distinct().toList();
            }
            case SETTLE_INSTRUMENT -> settleInstrument(message);
            case EXECUTE_LIQUIDATION -> executeLiquidation(message);
            case EXECUTE_ADL -> {
                var command = TradingCommandCodec.decodeExecuteAdl(message.payload());
                commandChangedUserIds = List.of(command.targetUserId());
                tradingState = tradingReducer.executeAdl(tradingState, command);
            }
            case RESOLVE_LIQUIDATION -> tradingState = tradingReducer.resolveLiquidation(tradingState,
                    TradingCommandCodec.decodeResolveLiquidation(message.payload()));
            case CONTINUE_RISK_SCAN -> tradingState = tradingReducer.continueRiskScan(tradingState,
                    TradingCommandCodec.decodeContinueRiskScan(message.payload()).maxUsers());
            case ACK_EXPORT -> {
                var acknowledgedTerminalOrderIds = exportState.acknowledge(
                        CoreExportCodec.decodeAck(message.payload()));
                commandChangedUserIds = acknowledgedTerminalOrderIds.stream()
                        .map(tradingState::order)
                        .filter(java.util.Objects::nonNull)
                        .map(com.surprising.aeron.service.state.CoreOrderState::userId)
                        .distinct().toList();
                tradingState = tradingReducer.pruneAcknowledgedTerminalReservations(
                        tradingState, acknowledgedTerminalOrderIds);
            }
            case UPDATE_POSITION_MODE -> {
                commandChangedUserIds = List.of(message.header().userId());
                tradingState = tradingReducer.updatePositionMode(
                        tradingState, message.header().userId(),
                        TradingCommandCodec.decodeUpdatePositionMode(message.payload()));
            }
            case ADJUST_POSITION_MARGIN -> {
                commandChangedUserIds = List.of(message.header().userId());
                tradingState = tradingReducer.adjustPositionMargin(
                        tradingState, message.header().userId(),
                        TradingCommandCodec.decodeAdjustPositionMargin(message.payload()));
            }
            case ADJUST_INSURANCE_FUND -> tradingState = tradingReducer.adjustInsuranceFund(
                    tradingState, TradingCommandCodec.decodeAdjustInsuranceFund(message.payload()));
            case UPDATE_LEVERAGE -> {
                commandChangedUserIds = List.of(message.header().userId());
                tradingState = tradingReducer.updateLeverage(
                        tradingState, message.header().userId(),
                        TradingCommandCodec.decodeUpdateLeverage(message.payload()));
            }
            case UPSERT_ALGO_ORDER -> tradingState = tradingReducer.upsertAlgoOrder(tradingState,
                    message.header().userId(), com.surprising.aeron.protocol.CoreAlgoOrderCodec.decode(message.payload()));
            case UPDATE_CANCEL_ALL_AFTER -> tradingState = tradingReducer.updateCancelAllAfter(tradingState,
                    message.header().userId(),
                    com.surprising.aeron.protocol.CoreCancelAllAfterCodec.decodeCommand(message.payload()));
            case PLACE_TRIGGER_ORDER -> tradingState = tradingReducer.upsertTriggerOrder(tradingState,
                    message.header().userId(), com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeState(message.payload()),
                    triggerOrderIndex);
            case CANCEL_TRIGGER_ORDER -> tradingState = tradingReducer.cancelTriggerOrder(tradingState,
                    message.header().userId(), com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeId(message.payload()));
            case CLAIM_TRIGGER_ORDER -> {
                long[] claim = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeClaim(message.payload());
                tradingState = tradingReducer.claimTriggerOrder(tradingState, claim[0], claim[1], claim[2], claim[3]);
            }
            case COMPLETE_TRIGGER_ORDER -> {
                long[] complete = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeComplete(message.payload());
                tradingState = tradingReducer.completeTriggerOrder(tradingState, complete[0], complete[1] == 1,
                        complete[2], "", complete[3]);
            }
            case UPDATE_TRIGGER_TRAILING -> {
                long[] trailing = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeTrailing(message.payload());
                tradingState = tradingReducer.updateTriggerTrailing(tradingState, trailing[0], trailing[1], trailing[2], trailing[3]);
            }
            case EXPIRE_TRIGGER_ORDER -> {
                long[] lifecycle = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeLifecycle(message.payload());
                tradingState = tradingReducer.expireTriggerOrder(tradingState, lifecycle[0], lifecycle[1]);
            }
            case RETRY_TRIGGER_ORDER -> {
                long[] lifecycle = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeLifecycle(message.payload());
                tradingState = tradingReducer.retryTriggerOrder(tradingState, lifecycle[0], lifecycle[1],
                        message.header().submittedAtEpochMillis());
            }
            case EXECUTE_TRIGGER_ORDER -> executeTriggerOrder(message);
            default -> {
                return null;
            }
        }
        return ResponseStatus.APPLIED;
    }

    private static boolean matchingCommand(CoreMessageType messageType) {
        return switch (messageType) {
            case PLACE_ORDER, CANCEL_ORDER, REPLACE_ORDER, AMEND_ORDER, SETTLE_INSTRUMENT -> true;
            case EXECUTE_TRIGGER_ORDER -> true;
            default -> false;
        };
    }

    private java.util.stream.Stream<com.surprising.aeron.service.state.CoreOrderState> openOrders(long userId) {
        if (userId == 0) {
            return ((java.util.NavigableMap<Long, com.surprising.aeron.service.state.CoreOrderState>) tradingState.orders())
                    .descendingMap().values().stream();
        }
        var user = tradingState.user(userId);
        if (user == null) return java.util.stream.Stream.empty();
        return user.reservations().keySet().stream()
                .map(tradingState::order)
                .filter(java.util.Objects::nonNull)
                .sorted(java.util.Comparator.comparingLong(
                        com.surprising.aeron.service.state.CoreOrderState::orderId).reversed());
    }

    private void placeOrder(CoreMessage message) {
        var command = TradingCommandCodec.decodePlaceOrder(message.payload());
        TradingCoreState before = tradingState;
        TradingCoreState reserved = tradingReducer.placeOrder(before, message.header().userId(), command,
                message.header().commandId(), openInterestIndex.openInterestSteps(command.symbol()));
        var matchingResult = placeInMatching(before, message.header().userId(), command);
        if (!matchingResult.accepted()) {
            throw new CoreStateRejectedException("MATCHING_REJECTED", matchingResult.resultCode());
        }
        try {
            tradingState = tradingReducer.applyMatches(reserved, command.orderId(),
                    command.baseAsset(), command.quoteAsset(), matchingResult.matches());
            commandExecutions = executionViews(command.orderId(), message.header().userId(), matchingResult.matches());
            commandChangedUserIds = java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(message.header().userId()),
                            matchingResult.matches().stream()
                                    .map(com.surprising.aeron.service.matching.CoreMatch::makerUserId))
                    .distinct().toList();
            commandChangedOrderIds = java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(command.orderId()),
                            matchingResult.matches().stream()
                                    .map(com.surprising.aeron.service.matching.CoreMatch::makerOrderId))
                    .distinct().toList();
            commandOrderViews = java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(command.orderId()),
                            matchingResult.matches().stream()
                                    .map(com.surprising.aeron.service.matching.CoreMatch::makerOrderId))
                    .distinct().map(orderId -> orderView(tradingState.order(orderId))).toList();
        } catch (RuntimeException exception) {
            matchingAdapter.rebuild(before);
            throw exception;
        }
    }

    private com.surprising.aeron.service.matching.CoreMatchingResult placeInMatching(
            TradingCoreState before, long userId, com.surprising.aeron.protocol.PlaceOrderCommand command) {
        try {
            matchingAdapter.ensureInstrument(before.instruments().get(command.symbol()));
            return matchingAdapter.place(userId, command);
        } catch (RuntimeException exception) {
            matchingAdapter.rebuild(before);
            throw exception;
        }
    }

    private void cancelOrder(CoreMessage message) {
        var command = TradingCommandCodec.decodeCancelOrder(message.payload());
        TradingCoreState before = tradingState;
        TradingCoreState canceled = tradingReducer.cancelOrder(before, message.header().userId(), command);
        if (canceled == before) {
            commandChangedUserIds = List.of(message.header().userId());
            commandChangedOrderIds = List.of(command.orderId());
            commandOrderViews = List.of(orderView(before.order(command.orderId())));
            return;
        }
        var order = before.order(command.orderId());
        commandChangedUserIds = List.of(message.header().userId());
        commandChangedOrderIds = List.of(command.orderId());
        var matchingResult = cancelInMatching(before, message.header().userId(), command.orderId(), order.symbol());
        if (!matchingResult.accepted()) {
            throw new CoreStateRejectedException("MATCHING_REJECTED", matchingResult.resultCode());
        }
        tradingState = canceled;
        commandOrderViews = List.of(orderView(tradingState.order(command.orderId())));
    }

    private com.surprising.aeron.service.matching.CoreMatchingResult cancelInMatching(
            TradingCoreState before, long userId, long orderId, String symbol) {
        try {
            return matchingAdapter.cancel(userId, orderId, symbol);
        } catch (RuntimeException exception) {
            matchingAdapter.rebuild(before);
            throw exception;
        }
    }

    private void replaceOrder(CoreMessage message) {
        var command = TradingCommandCodec.decodeReplaceOrder(message.payload());
        TradingCoreState before = tradingState;
        var order = before.order(command.originalOrderId());
        if (order == null) {
            throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        }
        replaceOrder(message, before, order, command.replacement());
    }

    private void amendOrder(CoreMessage message) {
        var command = TradingCommandCodec.decodeAmendOrder(message.payload());
        TradingCoreState before = tradingState;
        var order = before.order(command.originalOrderId());
        if (order == null) {
            throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        }
        if (order.userId() != message.header().userId()) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        if (order.orderType() != com.surprising.aeron.protocol.CoreOrderType.LIMIT
                || order.status() != com.surprising.aeron.service.state.CoreOrderStatus.OPEN) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "order is not amendable");
        }
        var instrument = before.instruments().get(order.symbol());
        if (instrument == null || instrument.version() != order.instrumentVersion()) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "order instrument is missing");
        }
        long priceTicks = command.priceTicks() == null ? order.priceTicks() : command.priceTicks();
        long quantitySteps = command.quantitySteps() == null
                ? order.remainingQuantitySteps() : command.quantitySteps();
        var timeInForce = command.timeInForce() == null ? order.timeInForce() : command.timeInForce();
        boolean postOnly = command.postOnly() == null ? order.postOnly() : command.postOnly();
        String clientOrderId = command.newClientOrderId() == null ? "" : command.newClientOrderId();
        var replacement = new com.surprising.aeron.protocol.PlaceOrderCommand(
                command.replacementOrderId(), order.symbol(), order.instrumentVersion(), instrument.baseAsset(),
                instrument.quoteAsset(), instrument.settleAsset(), order.side(), priceTicks, quantitySteps,
                order.reduceOnly(), order.marginMode(), order.positionSide(),
                instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT
                        ? com.surprising.aeron.protocol.ReservationKind.SPOT_ASSET
                        : com.surprising.aeron.protocol.ReservationKind.DERIVATIVE_MARGIN,
                instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT
                        ? order.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY
                                ? instrument.quoteAsset() : instrument.baseAsset()
                        : instrument.settleAsset(), 0, order.orderType(), timeInForce, priceTicks, postOnly,
                clientOrderId, order.makerFeeRatePpm(), order.takerFeeRatePpm());
        replaceOrder(message, before, order, replacement);
    }

    private void replaceOrder(CoreMessage message, TradingCoreState before,
                               com.surprising.aeron.service.state.CoreOrderState order,
                               com.surprising.aeron.protocol.PlaceOrderCommand replacement) {
        commandChangedUserIds = List.of(message.header().userId());
        commandChangedOrderIds = List.of(order.orderId(), replacement.orderId());
        TradingCoreState canceled = tradingReducer.cancelOrder(before, message.header().userId(),
                new com.surprising.aeron.protocol.CancelOrderCommand(order.orderId()));
        TradingCoreState prepared = tradingReducer.placeOrder(canceled, message.header().userId(),
                replacement, message.header().commandId(), openInterestIndex.openInterestSteps(replacement.symbol()));
        try {
            var cancelResult = matchingAdapter.cancel(message.header().userId(), order.orderId(),
                    order.symbol());
            if (!cancelResult.accepted()) {
                throw new CoreStateRejectedException("MATCHING_REJECTED", cancelResult.resultCode());
            }
            matchingAdapter.ensureInstrument(before.instruments().get(replacement.symbol()));
            var matchingResult = matchingAdapter.place(message.header().userId(), replacement);
            if (!matchingResult.accepted()) {
                throw new CoreStateRejectedException("MATCHING_REJECTED", matchingResult.resultCode());
            }
            tradingState = tradingReducer.applyMatches(prepared, replacement.orderId(),
                    replacement.baseAsset(), replacement.quoteAsset(), matchingResult.matches());
            commandExecutions = executionViews(replacement.orderId(), message.header().userId(),
                    matchingResult.matches());
            commandChangedUserIds = java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(message.header().userId()),
                            matchingResult.matches().stream()
                                    .map(com.surprising.aeron.service.matching.CoreMatch::makerUserId))
                    .distinct().toList();
            commandChangedOrderIds = java.util.stream.Stream.concat(
                            commandChangedOrderIds.stream(),
                            matchingResult.matches().stream()
                                    .map(com.surprising.aeron.service.matching.CoreMatch::makerOrderId))
                    .distinct().toList();
            commandOrderViews = java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(order.orderId(), replacement.orderId()),
                            matchingResult.matches().stream()
                                    .map(com.surprising.aeron.service.matching.CoreMatch::makerOrderId))
                    .distinct().map(orderId -> orderView(tradingState.order(orderId))).toList();
        } catch (RuntimeException exception) {
            matchingAdapter.rebuild(before);
            throw exception;
        }
    }

    private void executeTriggerOrder(CoreMessage message) {
        long[] execute = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(message.payload());
        long triggerOrderId = execute[0];
        var trigger = tradingState.triggerOrders().get(triggerOrderId);
        if (trigger == null) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
        }
        if (trigger.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
            return;
        }
        TradingCoreState before = tradingState;
        TradingCoreState claimed = tradingReducer.claimTriggerOrder(before, triggerOrderId, execute[1], execute[2], execute[3]);
        commandChangedTriggerOrderIds = List.of(triggerOrderId);
        var instrument = claimed.instruments().get(trigger.symbol());
        if (instrument == null || instrument.version() <= 0 || trigger.instrumentVersion() <= 0
                || instrument.version() != trigger.instrumentVersion()) {
            tradingState = tradingReducer.completeTriggerOrder(claimed, triggerOrderId, false, 0,
                    instrument == null ? "INSTRUMENT_NOT_FOUND" : "STALE_INSTRUMENT_VERSION", execute[3]);
            return;
        }
        long childOrderId = triggerChildOrderId(triggerOrderId, claimed);
        boolean spot = instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT;
        var place = new com.surprising.aeron.protocol.PlaceOrderCommand(
                childOrderId, trigger.symbol(), trigger.instrumentVersion(), instrument.baseAsset(), instrument.quoteAsset(),
                instrument.settleAsset(), trigger.side(), trigger.orderType() == com.surprising.aeron.protocol.CoreOrderType.MARKET
                        ? 0 : trigger.priceTicks(), trigger.quantitySteps(), !spot, trigger.marginMode(), trigger.positionSide(),
                spot ? com.surprising.aeron.protocol.ReservationKind.SPOT_ASSET
                        : com.surprising.aeron.protocol.ReservationKind.DERIVATIVE_MARGIN,
                spot && trigger.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY
                        ? instrument.quoteAsset() : spot ? instrument.baseAsset() : instrument.settleAsset(), 0,
                trigger.orderType(), trigger.timeInForce(), trigger.priceTicks() > 0 ? trigger.priceTicks() : execute[2], false,
                "TRIGGER:" + triggerOrderId, trigger.makerFeeRatePpm(), trigger.takerFeeRatePpm());
        TradingCoreState reserved;
        try {
            reserved = tradingReducer.placeOrder(claimed, trigger.userId(), place, message.header().commandId(),
                    openInterestIndex.openInterestSteps(trigger.symbol()));
        } catch (CoreStateRejectedException exception) {
            tradingState = tradingReducer.completeTriggerOrder(claimed, triggerOrderId, false, 0,
                    exception.code(), execute[3]);
            return;
        }
        try {
            var matching = placeInMatching(claimed, trigger.userId(), place);
            if (!matching.accepted()) {
                tradingState = tradingReducer.completeTriggerOrder(claimed, triggerOrderId, false, 0,
                        matching.resultCode(), execute[3]);
                return;
            }
            TradingCoreState matched = tradingReducer.applyMatches(reserved, childOrderId,
                    instrument.baseAsset(), instrument.quoteAsset(), matching.matches());
            tradingState = tradingReducer.completeTriggerOrder(matched, triggerOrderId, true, childOrderId,
                    "", execute[3]);
            commandExecutions = executionViews(childOrderId, trigger.userId(), matching.matches());
            commandChangedUserIds = java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(trigger.userId()),
                            matching.matches().stream()
                                    .map(com.surprising.aeron.service.matching.CoreMatch::makerUserId))
                    .distinct().toList();
            commandChangedOrderIds = java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(childOrderId),
                            matching.matches().stream()
                                    .map(com.surprising.aeron.service.matching.CoreMatch::makerOrderId))
                    .distinct().toList();
            commandOrderViews = commandChangedOrderIds.stream().map(tradingState::order)
                    .filter(java.util.Objects::nonNull).map(CoreProbeState::orderView).toList();
        } catch (RuntimeException exception) {
            matchingAdapter.rebuild(claimed);
            throw exception;
        }
    }

    private static long triggerChildOrderId(long triggerOrderId, TradingCoreState state) {
        long candidate = Math.addExact(Math.multiplyExact(triggerOrderId, 2), 1);
        while (state.orders().containsKey(candidate)) {
            candidate = Math.addExact(candidate, 2);
        }
        return candidate;
    }

    private void executeLiquidation(CoreMessage message) {
        var command = TradingCommandCodec.decodeExecuteLiquidation(message.payload());
        TradingCoreState before = tradingState;
        var liquidation = before.riskState().liquidations().get(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        if (!tradingReducer.isLiquidationExecutable(before, command)) {
            tradingState = tradingReducer.executeLiquidation(before, command);
            return;
        }
        var user = before.user(liquidation.userId());
        var openOrders = user == null ? List.<com.surprising.aeron.service.state.CoreOrderState>of()
                : user.reservations().keySet().stream().map(before::order)
                .filter(java.util.Objects::nonNull)
                .filter(order -> order.userId() == liquidation.userId()
                        && order.symbol().equals(liquidation.symbol())
                        && order.status() == com.surprising.aeron.service.state.CoreOrderStatus.OPEN)
                .toList();
        commandChangedUserIds = List.of(liquidation.userId());
        commandChangedOrderIds = openOrders.stream()
                .map(com.surprising.aeron.service.state.CoreOrderState::orderId).toList();
        try {
            for (var result : matchingAdapter.cancelBatch(openOrders)) {
                if (!result.accepted()) {
                    throw new CoreStateRejectedException("MATCHING_REJECTED", result.resultCode());
                }
            }
            tradingState = tradingReducer.executeLiquidation(before, command);
        } catch (RuntimeException exception) {
            matchingAdapter.rebuild(before);
            throw exception;
        }
    }

    private void settleInstrument(CoreMessage message) {
        var command = TradingCommandCodec.decodeSettleInstrument(message.payload());
        TradingCoreState before = tradingState;
        boolean continuation = before.treasuryState().lifecycleProgress(command.symbol()) != null;
        var openOrders = before.bookState().openOrders().keySet().stream()
                .map(before::order)
                .filter(java.util.Objects::nonNull)
                .filter(order -> order.symbol().equalsIgnoreCase(command.symbol())
                        && order.status() == com.surprising.aeron.service.state.CoreOrderStatus.OPEN)
                .toList();
        commandChangedUserIds = positionUserIndex.users(command.symbol()).stream().toList();
        commandChangedOrderIds = openOrders.stream()
                .map(com.surprising.aeron.service.state.CoreOrderState::orderId).toList();
        try {
            if (!continuation) {
                for (var result : matchingAdapter.cancelBatch(openOrders)) {
                    if (!result.accepted()) {
                        throw new CoreStateRejectedException("MATCHING_REJECTED", result.resultCode());
                    }
                }
            }
            var result = tradingReducer.settleInstrumentWithProgress(before, command,
                    positionUserIndex.users(command.symbol()), message.header().commandId());
            tradingState = result.state();
            commandSettlementProgress = result.progress();
        } catch (RuntimeException exception) {
            matchingAdapter.rebuild(before);
            throw exception;
        }
    }

    int matchingStateHash() {
        return matchingAdapter.orderBooksStateHash();
    }

    @Override
    public void close() {
        runtime.close();
    }

    private CoreResponse userStateResponse(long userId) {
        var user = tradingState.user(userId);
        if (user == null) {
            return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                    CoreResultCode.ENTITY_NOT_FOUND, appliedCommandCount, cachedBusinessStateHash);
        }
        var view = new CoreUserStateView(user.productLine(), user.userId(), user.revision(), user.positionMode(),
                user.balances().values().stream().map(value -> new CoreBalanceView(
                        value.asset(), value.availableUnits(), value.lockedUnits())).toList(),
                user.reservations().values().stream().map(value -> new CoreReservationView(
                        value.orderId(), value.symbol(), value.instrumentVersion(), value.kind(), value.asset(),
                        value.reservedUnits(),
                        value.releasedUnits(), value.consumedUnits(), value.orderQuantitySteps())).toList(),
                user.positions().values().stream().map(value -> new CorePositionView(
                        value.symbol(), value.marginAsset(), value.marginMode(), value.positionSide(),
                        value.instrumentVersion(), value.signedQuantitySteps(),
                        value.entryPriceTicks(), value.entryValueTicks(), value.realizedPnlUnits(),
                        value.positionMarginUnits())).toList(),
                tradingState.leverages().entrySet().stream()
                        .filter(entry -> entry.getKey().userId() == userId)
                        .map(entry -> new com.surprising.aeron.protocol.CoreLeverageView(entry.getKey().symbol(),
                                entry.getKey().marginMode(), entry.getValue())).toList());
        return new CoreResponse(ResponseStatus.OK, appliedCommandCount, tradingState.userStateHash(userId),
                CoreStateQueryCodec.encodeUserState(view));
    }

    private com.surprising.aeron.protocol.CoreAlgoOrderView algoView(
            com.surprising.aeron.service.state.CoreAlgoOrderState state) {
        long executed = 0, active = 0; int activeCount = 0;
        for (long childOrderId : state.childOrderIds()) {
            var child = tradingState.order(childOrderId);
            if (child == null) throw new IllegalStateException("algo child order missing");
            executed = Math.addExact(executed, child.executedQuantitySteps());
            if (child.status() == com.surprising.aeron.service.state.CoreOrderStatus.OPEN) {
                active = Math.addExact(active, child.remainingQuantitySteps()); activeCount++;
            }
        }
        return new com.surprising.aeron.protocol.CoreAlgoOrderView(state.algoOrderId(), state.userId(),
                state.clientAlgoOrderId(), state.symbol(), state.algoTypeCode(), state.side(), state.priceTicks(),
                state.quantitySteps(), state.childQuantitySteps(), state.intervalSeconds(), state.durationSeconds(),
                state.marginMode(), state.positionSide(), state.reduceOnly(), state.postOnly(), state.timeInForce(),
                state.statusCode(), state.currentOrderId(), state.rejectReason(), state.traceId(), state.startAtEpochMillis(),
                state.nextSliceAtEpochMillis(), state.completedAtEpochMillis(), state.createdAtEpochMillis(),
                state.updatedAtEpochMillis(), state.revision(), state.childOrderIds(), executed, active, activeCount);
    }

    private static List<com.surprising.aeron.protocol.CoreExecutionView> executionViews(
            long takerOrderId, long takerUserId, List<com.surprising.aeron.service.matching.CoreMatch> matches) {
        return matches.stream().map(match -> new com.surprising.aeron.protocol.CoreExecutionView(
                takerOrderId, match.makerOrderId(), takerUserId, match.makerUserId(),
                match.priceTicks(), match.quantitySteps())).toList();
    }

    private CoreCommandDelta commandDelta(TradingCoreState before, TradingCoreState after) {
        return new CoreCommandDelta(
                explicitOrDerived(commandChangedUserIds, after.changedUserIdsSince(before)),
                explicitOrDerived(commandChangedOrderIds, after.changedOrderIdsSince(before)),
                explicitOrDerived(commandChangedLiquidationIds, after.changedLiquidationIdsSince(before)),
                explicitOrDerived(commandChangedTriggerOrderIds, after.changedTriggerOrderIdsSince(before)),
                commandExecutions, commandFundingPayments, commandFundingProgress, commandSettlementProgress);
    }

    private static <T> List<T> explicitOrDerived(List<T> explicit, java.util.Set<T> derived) {
        return explicit != null ? List.copyOf(explicit) : derived == null ? null : List.copyOf(derived);
    }

    private static List<CoreUserStateView> changedUsers(
            TradingCoreState before, TradingCoreState after, List<Long> changedUserIds) {
        if (changedUserIds != null) {
            java.util.ArrayList<CoreUserStateView> result = new java.util.ArrayList<>(changedUserIds.size());
            for (Long userId : changedUserIds) {
                var user = after.user(userId);
                if (user != null && !user.equals(before.user(userId))) {
                    result.add(userDelta(before.user(userId), user));
                }
            }
            return List.copyOf(result);
        }
        return after.users().values().stream()
                .filter(user -> !user.equals(before.users().get(user.userId())))
                .map(user -> userDelta(before.users().get(user.userId()), user)).toList();
    }

    private static List<CoreOrderStateView> changedOrders(
            TradingCoreState before, TradingCoreState after, List<Long> changedOrderIds) {
        if (changedOrderIds != null) {
            java.util.ArrayList<CoreOrderStateView> result = new java.util.ArrayList<>(changedOrderIds.size());
            for (Long orderId : changedOrderIds) {
                var order = after.order(orderId);
                if (order != null && !order.equals(before.order(orderId))) {
                    result.add(orderView(order));
                }
            }
            return List.copyOf(result);
        }
        return after.orders().values().stream()
                .filter(order -> !order.equals(before.orders().get(order.orderId())))
                .map(CoreProbeState::orderView).toList();
    }

    private static List<com.surprising.aeron.protocol.CoreLiquidationView> changedLiquidations(
            TradingCoreState before, TradingCoreState after, List<Long> changedLiquidationIds) {
        java.util.stream.Stream<com.surprising.aeron.service.state.CoreLiquidationState> values =
                changedLiquidationIds == null
                        ? after.riskState().liquidations().values().stream()
                        : changedLiquidationIds.stream()
                                .map(id -> after.riskState().liquidations().get(id))
                                .filter(java.util.Objects::nonNull);
        return values.filter(value -> !value.equals(before.riskState().liquidations().get(value.liquidationId())))
                .map(value -> {
                    var instrument = after.instruments().get(value.symbol());
                    if (instrument == null) throw new IllegalStateException("liquidation instrument is missing");
                    return new com.surprising.aeron.protocol.CoreLiquidationView(value.liquidationId(),
                            value.userId(), value.symbol(), instrument.settleAsset(), value.marginMode(),
                            value.positionSide(), value.instrumentVersion(), value.triggerPriceSequence(),
                            value.signedQuantitySteps(), value.closeQuantitySteps(), value.deficitUnits(),
                            value.executionPriceTicks(), value.liquidationFeeRatePpm(),
                            value.liquidationFeeUnits(), value.status().name());
                }).toList();
    }

    private static List<com.surprising.aeron.protocol.CoreTriggerOrderStateView> changedTriggerOrders(
            TradingCoreState before, TradingCoreState after, List<Long> changedTriggerOrderIds) {
        java.util.stream.Stream<com.surprising.aeron.service.state.CoreTriggerOrderState> values =
                changedTriggerOrderIds == null
                        ? after.triggerOrders().values().stream()
                        : changedTriggerOrderIds.stream()
                                .map(id -> after.triggerOrders().get(id))
                                .filter(java.util.Objects::nonNull);
        return values.filter(value -> !value.equals(before.triggerOrders().get(value.triggerOrderId())))
                .map(com.surprising.aeron.service.state.CoreTriggerOrderState::view).toList();
    }

    private static List<com.surprising.aeron.protocol.CoreTreasuryAssetView> changedTreasuryAssets(
            TradingCoreState before, TradingCoreState after) {
        java.util.TreeSet<String> assets = new java.util.TreeSet<>();
        var changedAssets = after.changedTreasuryAssetsSince(before);
        if (changedAssets == null) {
            assets.addAll(before.treasuryState().feeBalances().keySet());
            assets.addAll(before.treasuryState().insuranceBalances().keySet());
            assets.addAll(before.treasuryState().insuranceDeficits().keySet());
            assets.addAll(after.treasuryState().feeBalances().keySet());
            assets.addAll(after.treasuryState().insuranceBalances().keySet());
            assets.addAll(after.treasuryState().insuranceDeficits().keySet());
        } else {
            assets.addAll(changedAssets);
        }
        return assets.stream().filter(asset -> treasuryChanged(before, after, asset))
                .map(asset -> new com.surprising.aeron.protocol.CoreTreasuryAssetView(asset,
                        after.treasuryState().feeBalances().getOrDefault(asset, 0L),
                        after.treasuryState().insuranceBalances().getOrDefault(asset, 0L),
                        after.treasuryState().insuranceDeficits().getOrDefault(asset, 0L))).toList();
    }

    private static boolean treasuryChanged(TradingCoreState before, TradingCoreState after, String asset) {
        return !java.util.Objects.equals(before.treasuryState().feeBalances().get(asset),
                after.treasuryState().feeBalances().get(asset))
                || !java.util.Objects.equals(before.treasuryState().insuranceBalances().get(asset),
                after.treasuryState().insuranceBalances().get(asset))
                || !java.util.Objects.equals(before.treasuryState().insuranceDeficits().get(asset),
                after.treasuryState().insuranceDeficits().get(asset));
    }

    private static CoreUserStateView userDelta(com.surprising.aeron.service.state.CoreUserState before,
                                                com.surprising.aeron.service.state.CoreUserState after) {
        return new CoreUserStateView(after.productLine(), after.userId(), after.revision(), after.positionMode(),
                after.balances().values().stream()
                        .filter(value -> before == null || !value.equals(before.balances().get(value.asset())))
                        .map(value -> new CoreBalanceView(value.asset(), value.availableUnits(), value.lockedUnits()))
                        .toList(),
                after.reservations().values().stream()
                        .filter(value -> before == null || !value.equals(before.reservations().get(value.orderId())))
                        .map(value -> new CoreReservationView(value.orderId(), value.symbol(), value.instrumentVersion(),
                                value.kind(), value.asset(), value.reservedUnits(), value.releasedUnits(),
                                value.consumedUnits(), value.orderQuantitySteps())).toList(),
                after.positions().values().stream()
                        .filter(value -> before == null || !value.equals(before.positions().get(value.key())))
                        .map(value -> new CorePositionView(value.symbol(), value.marginAsset(), value.marginMode(),
                                value.positionSide(), value.instrumentVersion(), value.signedQuantitySteps(),
                                value.entryPriceTicks(), value.entryValueTicks(), value.realizedPnlUnits(),
                                value.positionMarginUnits())).toList());
    }

    private static CoreOrderStateView orderView(com.surprising.aeron.service.state.CoreOrderState order) {
        return new CoreOrderStateView(order.orderId(), order.productLine(), order.userId(), order.symbol(),
                order.instrumentVersion(), order.side(), order.priceTicks(), order.quantitySteps(),
                order.executedQuantitySteps(), order.remainingQuantitySteps(), order.reduceOnly(),
                order.marginMode(), order.positionSide(), order.orderType(), order.timeInForce(), order.postOnly(),
                order.clientOrderId(), order.commandId(), order.makerFeeRatePpm(), order.takerFeeRatePpm(),
                order.createdAtEpochMillis(), order.updatedAtEpochMillis(), order.clusterPosition(),
                order.status().name(), order.revision());
    }

    private byte[] commandResultData() {
        if (commandFundingProgress != null) {
            return CoreFundingProgressCodec.encode(commandFundingProgress);
        }
        if (commandSettlementProgress != null) {
            return CoreSettlementProgressCodec.encode(commandSettlementProgress);
        }
        if (commandOrderViews.isEmpty() && commandExecutions.isEmpty()) {
            return new byte[0];
        }
        List<CoreOrderStateView> finalOrders = commandOrderViews.stream()
                .map(value -> {
                    var order = tradingState.order(value.orderId());
                    return order == null ? value : orderView(order);
                })
                .toList();
        try {
            return CoreCommandResultCodec.encode(new CoreCommandResultView(finalOrders, commandExecutions));
        } catch (IllegalArgumentException exception) {
            return new byte[0];
        }
    }

    private CoreResponse orderStateResponse(long orderId) {
        return orderStateResponse(tradingState.order(orderId));
    }

    private CoreResponse orderStateResponse(com.surprising.aeron.service.state.CoreOrderState order) {
        if (order == null) {
            return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                    CoreResultCode.ENTITY_NOT_FOUND, appliedCommandCount, cachedBusinessStateHash);
        }
        var view = new CoreOrderStateView(order.orderId(), order.productLine(), order.userId(), order.symbol(),
                order.instrumentVersion(), order.side(), order.priceTicks(), order.quantitySteps(),
                order.executedQuantitySteps(), order.remainingQuantitySteps(), order.reduceOnly(),
                order.marginMode(), order.positionSide(), order.orderType(), order.timeInForce(), order.postOnly(),
                order.clientOrderId(), order.commandId(), order.makerFeeRatePpm(), order.takerFeeRatePpm(),
                order.createdAtEpochMillis(), order.updatedAtEpochMillis(), order.clusterPosition(),
                order.status().name(), order.revision());
        return new CoreResponse(ResponseStatus.OK, appliedCommandCount, tradingState.orderStateHash(order.orderId()),
                CoreStateQueryCodec.encodeOrderState(view));
    }

    private static long mix(long hash, long value) {
        long result = hash;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            result ^= (value >>> shift) & 0xff;
            result *= HASH_PRIME;
        }
        return result;
    }

    private static long sourceSequenceDigest(Map<SourceKey, Long> sequences) {
        long digest = 0;
        for (Map.Entry<SourceKey, Long> entry : sequences.entrySet()) {
            digest ^= sourceSequenceDigest(entry.getKey(), entry.getValue());
        }
        return digest;
    }

    private static long sourceSequenceDigest(SourceKey key, long sequence) {
        long digest = HASH_OFFSET_BASIS;
        digest = mix(digest, key.source().wireCode());
        digest = mix(digest, key.sourceId());
        return mix(digest, sequence);
    }

    private CoreResponse rejected(CoreResultCode resultCode) {
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                resultCode, appliedCommandCount, stateHash());
    }

    record StoredResult(
            ResponseStatus status,
            CoreResultCode resultCode,
            long appliedCommandCount,
            long stateHash,
            byte[] responseData) {

        StoredResult(ResponseStatus status, CoreResultCode resultCode, long appliedCommandCount, long stateHash) {
            this(status, resultCode, appliedCommandCount, stateHash, new byte[0]);
        }

        StoredResult {
            responseData = responseData == null ? new byte[0] : responseData.clone();
        }

        @Override
        public byte[] responseData() {
            return responseData.clone();
        }
    }

    record SourceKey(CommandSource source, long sourceId) {
    }
}
