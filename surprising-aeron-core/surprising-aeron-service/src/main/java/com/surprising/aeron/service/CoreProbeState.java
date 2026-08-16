package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
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
import com.surprising.aeron.service.state.AlgoOrderIndex;
import com.surprising.aeron.service.state.LiquidationIndex;
import com.surprising.aeron.service.state.CancelAllAfterIndex;
import com.surprising.aeron.service.state.ActiveOrderIndex;
import com.surprising.aeron.service.state.AdlPositionIndex;
import com.surprising.aeron.service.state.PositionUserIndex;
import com.surprising.aeron.service.state.RiskSnapshotIndex;
import com.surprising.aeron.service.state.TriggerOrderIndex;
import com.surprising.aeron.service.state.TradingCoreReducer;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class CoreProbeState implements AutoCloseable {

    static final int MAX_IDEMPOTENCY_RESULTS = 128;
    static final int MAX_STORED_RESPONSE_BYTES = 1 * 1024 * 1024;
    static final int MAX_SOURCE_SEQUENCES = 65_536;
    private static final int DEFAULT_RISK_SCAN_BATCH_SIZE = 1_024;
    private static final System.Logger LOG = System.getLogger(CoreProbeState.class.getName());
    private static final long HASH_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long HASH_PRIME = 0x100000001b3L;
    private static final int MATCHING_PENDING_WIRE_CODE = 66;
    private final ProductLine productLine;
    private final TradingCoreRuntime runtime;
    private final LinkedHashMap<UUID, StoredResult> commandResults;
    private final LinkedHashMap<SourceKey, Long> lastSourceSequences;
    private final LinkedHashMap<Long, PendingMatching> pendingMatching;
    private final List<CoreMessage> queuedMatching = new ArrayList<>();
    private final Map<Long, com.surprising.aeron.service.matching.CoreMatchingResult> completedMatching
            = new ConcurrentHashMap<>();
    private final Map<Long, List<com.surprising.aeron.protocol.CoreBookLevelView>> completedBookQueries
            = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> failedQueries = new ConcurrentHashMap<>();
    private final Map<UUID, Long> queryIds = new ConcurrentHashMap<>();
    private final TradingCoreReducer tradingReducer;
    private final DeterministicExchangeCoreAdapter matchingAdapter;
    private final PositionUserIndex positionUserIndex;
    private final OpenInterestIndex openInterestIndex;
    private final TriggerOrderIndex triggerOrderIndex;
    private final AlgoOrderIndex algoOrderIndex;
    private final LiquidationIndex liquidationIndex;
    private final CancelAllAfterIndex cancelAllAfterIndex;
    private final ActiveOrderIndex activeOrderIndex;
    private final AdlPositionIndex adlPositionIndex;
    private final RiskSnapshotIndex riskSnapshotIndex;
    private final CoreExportState exportState;
    private final com.surprising.aeron.service.state.RollingBusinessStateHash rollingBusinessStateHash;
    private long appliedCommandCount;
    private long probeValue;
    private long cachedBusinessStateHash;
    private long lastSourceSequenceDigest;
    private long nextAsyncQueryId = Long.MIN_VALUE;
    private TradingCoreState tradingState;
    private List<CoreOrderStateView> commandOrderViews = List.of();
    private List<Long> commandChangedUserIds;
    private List<Long> commandChangedOrderIds;
    private List<Long> commandChangedLiquidationIds;
    private List<Long> commandChangedTriggerOrderIds;
    private List<String> commandChangedTreasuryAssets;
    private List<com.surprising.aeron.protocol.CoreExecutionView> commandExecutions = List.of();
    private List<com.surprising.aeron.protocol.CoreFundingPaymentView> commandFundingPayments = List.of();
    private CoreFundingProgressView commandFundingProgress;
    private CoreSettlementProgressView commandSettlementProgress;
    private CoreCommandDelta commandDelta = CoreCommandDelta.empty();

    public CoreProbeState(ProductLine productLine) {
        this(productLine, 0, 0, new LinkedHashMap<>(), new LinkedHashMap<>(),
                new LinkedHashMap<>(), TradingCoreState.empty(productLine), new CoreExportState());
    }

    private CoreProbeState(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            LinkedHashMap<UUID, StoredResult> commandResults,
            LinkedHashMap<SourceKey, Long> lastSourceSequences,
            LinkedHashMap<Long, PendingMatching> pendingMatching,
            TradingCoreState tradingState,
            CoreExportState exportState) {
        this.productLine = productLine;
        this.appliedCommandCount = appliedCommandCount;
        this.probeValue = probeValue;
        this.commandResults = commandResults;
        this.lastSourceSequences = lastSourceSequences;
        this.pendingMatching = pendingMatching;
        this.tradingState = tradingState;
        this.rollingBusinessStateHash = com.surprising.aeron.service.state.RollingBusinessStateHash.create(tradingState);
        this.cachedBusinessStateHash = rollingBusinessStateHash.value();
        this.lastSourceSequenceDigest = sourceSequenceDigest(lastSourceSequences);
        this.exportState = exportState;
        this.runtime = new TradingCoreRuntime(productLine, tradingState,
                pendingMatchingOrderIds(tradingState, pendingMatching));
        this.tradingReducer = runtime.reducerForConstruction();
        this.matchingAdapter = runtime.matcherForConstruction();
        this.positionUserIndex = runtime.positionUsersForConstruction();
        this.openInterestIndex = runtime.openInterestForConstruction();
        this.triggerOrderIndex = runtime.triggersForConstruction();
        this.algoOrderIndex = runtime.algosForConstruction();
        this.liquidationIndex = runtime.liquidationsForConstruction();
        this.cancelAllAfterIndex = runtime.timersForConstruction();
        this.activeOrderIndex = runtime.activeOrdersForConstruction();
        this.adlPositionIndex = runtime.adlPositionsForConstruction();
        this.riskSnapshotIndex = runtime.riskSnapshotsForConstruction();
    }

    private static java.util.Set<Long> pendingMatchingOrderIds(
            TradingCoreState state, Map<Long, PendingMatching> pendingMatching) {
        java.util.LinkedHashSet<Long> orderIds = new java.util.LinkedHashSet<>();
        for (PendingMatching pending : pendingMatching.values()) {
            switch (pending.operation()) {
                case PLACE -> orderIds.add(TradingCommandCodec.decodePlaceOrder(pending.command().payload()).orderId());
                case TRIGGER -> {
                    long[] execute = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(
                            pending.command().payload());
                    String clientOrderId = "TRIGGER:" + execute[0];
                    state.orders().values().stream()
                            .filter(order -> clientOrderId.equals(order.clientOrderId()))
                            .map(com.surprising.aeron.service.state.CoreOrderState::orderId)
                            .forEach(orderIds::add);
                }
                default -> {
                }
            }
        }
        return java.util.Set.copyOf(orderIds);
    }

    static CoreProbeState restore(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            Map<UUID, StoredResult> commandResults,
            Map<SourceKey, Long> lastSourceSequences,
            TradingCoreState tradingState,
            CoreExportState exportState) {
        return restore(productLine, appliedCommandCount, probeValue, commandResults, lastSourceSequences,
                Map.of(), tradingState, exportState);
    }

    static CoreProbeState restore(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            Map<UUID, StoredResult> commandResults,
            Map<SourceKey, Long> lastSourceSequences,
            Map<Long, PendingMatching> pendingMatching,
            TradingCoreState tradingState,
            CoreExportState exportState) {
        if (appliedCommandCount < 0 || commandResults.size() > MAX_IDEMPOTENCY_RESULTS
                || lastSourceSequences.size() > MAX_SOURCE_SEQUENCES
                || pendingMatching == null || tradingState == null || tradingState.productLine() != productLine
                || exportState == null) {
            throw new IllegalArgumentException("invalid restored probe state");
        }
        return new CoreProbeState(productLine, appliedCommandCount, probeValue,
                new LinkedHashMap<>(commandResults), new LinkedHashMap<>(lastSourceSequences),
                new LinkedHashMap<>(pendingMatching), tradingState, exportState);
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
                && message.header().messageType() == CoreMessageType.COMMAND_RESULT_QUERY) {
            try {
                UUID commandId = CoreStateQueryCodec.decodeCommandResultQuery(message.payload());
                StoredResult result = commandResults.get(commandId);
                if (result == null) {
                    return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                            CoreResultCode.ENTITY_NOT_FOUND, appliedCommandCount, cachedBusinessStateHash);
                }
                return new CoreResponse(ResponseStatus.OK, result.status(), result.resultCode(),
                        result.appliedCommandCount(), result.stateHash(), result.responseData());
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
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
                java.util.stream.Stream<com.surprising.aeron.service.state.CoreTriggerOrderState> source = query.expiresBeforeEpochMillis() > 0
                        ? triggerOrderIndex.expired(query.expiresBeforeEpochMillis(), query.limit()).stream()
                        .map(tradingState.triggerOrders()::get)
                        .filter(java.util.Objects::nonNull)
                        : query.symbol().isEmpty()
                        ? (query.status() != null
                        ? triggerOrderIndex.ids(query.status())
                        : message.header().userId() == 0 ? triggerOrderIndex.ids() : triggerOrderIndex.ids(message.header().userId())).stream()
                                .map(tradingState.triggerOrders()::get)
                                .filter(java.util.Objects::nonNull)
                        : (query.status() == null ? triggerOrderIndex.ids(query.symbol())
                        : triggerOrderIndex.ids(query.symbol(), query.status())).stream()
                                .map(tradingState.triggerOrders()::get)
                                .filter(java.util.Objects::nonNull);
                var values = source
                        .filter(order -> message.header().userId() == 0 || order.userId() == message.header().userId())
                        .filter(order -> query.triggerOrderId() == 0 || order.triggerOrderId() == query.triggerOrderId())
                        .filter(order -> query.symbol().isEmpty() || order.symbol().equals(query.symbol()))
                        .filter(order -> query.status() == null || order.status() == query.status())
                        .filter(order -> query.triggerOrderId() != 0 || order.triggerOrderId() < before)
                        .filter(order -> message.header().messageType() == CoreMessageType.TRIGGER_ORDER_QUERY
                                || order.status().open())
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
                return beginBookQuery(message);
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.EXPORT_BATCH_QUERY) {
            try {
                int maxEvents = CoreExportCodec.decodeBatchQuery(message.payload());
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, stateHash(),
                        CoreExportCodec.encodeBatchWithStatus(exportState.acknowledgedSequence(),
                                exportState.batch(maxEvents)));
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
                                tradingReducer.adlCandidates(tradingState, query.asset(), query.limit(), adlPositionIndex)));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.RISK_STATE_QUERY) {
            long requestedUserId = message.header().userId();
            var views = tradingReducer.riskSnapshots(tradingState, requestedUserId,
                    requestedUserId == 0 ? riskSnapshotIndex.keys() : riskSnapshotIndex.keys(requestedUserId));
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
                var algoIds = query.algoOrderId() != 0
                        ? List.of(query.algoOrderId())
                        : algoOrderIndex.query(query.userId(), query.symbol(), query.dueAtEpochMillis(),
                                query.limit(), tradingState.algoOrders());
                var values = algoIds.stream()
                        .map(tradingState.algoOrders()::get)
                        .filter(java.util.Objects::nonNull)
                        .filter(value -> query.userId() == 0 || value.userId() == query.userId())
                        .filter(value -> query.symbol().isEmpty() || value.symbol().equalsIgnoreCase(query.symbol()))
                        .filter(value -> query.dueAtEpochMillis() == 0 || value.nextSliceAtEpochMillis() > 0
                                && value.nextSliceAtEpochMillis() <= query.dueAtEpochMillis())
                        .filter(value -> query.algoOrderId() == 0 || value.algoOrderId() == query.algoOrderId())
                        .map(this::algoView).toList();
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
                var values = cancelAllAfterIndex.query(query.userId(), query.symbolScope(), query.dueAtEpochMillis(),
                                query.limit(), tradingState.cancelAllAfterTimers()).stream()
                        .map(tradingState.cancelAllAfterTimers()::get)
                        .filter(java.util.Objects::nonNull)
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
                var actions = liquidationIndex.activeIds().stream()
                        .map(tradingState.riskState().liquidations()::get)
                        .filter(java.util.Objects::nonNull)
                        .filter(value -> value.status()
                                == com.surprising.aeron.service.state.CoreLiquidationState.Status.PLANNED)
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
        if (exportCommand && !exportState.hasCapacityFor()) {
            return rejected(CoreResultCode.EXPORT_BACKLOG_FULL);
        }
        if (exportCommand && message.payload().length > CoreExportCodec.MAX_COMMAND_PAYLOAD) {
            return rejected(CoreResultCode.INVALID_MESSAGE);
        }
        if (isMatchingCommand(message.header().messageType())) {
            return beginMatching(message, clusterTimestamp, clusterPosition, sourceKey);
        }
        TradingCoreState beforeTradingState = tradingState;
        commandOrderViews = List.of();
        commandChangedUserIds = List.of();
        commandChangedOrderIds = List.of();
        commandChangedLiquidationIds = List.of();
        commandChangedTriggerOrderIds = List.of();
        commandChangedTreasuryAssets = List.of();
        commandExecutions = List.of();
        commandFundingPayments = List.of();
        commandFundingProgress = null;
        commandSettlementProgress = null;
        commandDelta = CoreCommandDelta.empty();
        queuedMatching.clear();
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
        if (status != ResponseStatus.APPLIED && tradingState != beforeTradingState) {
            restoreCommandState(beforeTradingState);
            runtime.restoreStateOnly(beforeTradingState);
        }
        if (status == null) {
            return rejected(CoreResultCode.INVALID_MESSAGE);
        }
        if (status == ResponseStatus.APPLIED) {
            cancelTriggersForClosedPositions(beforeTradingState);
            if (exportCommand && !exportState.hasCapacityFor(1 + queuedMatching.size())) {
                restoreCommandState(beforeTradingState);
                runtime.restoreStateOnly(beforeTradingState);
                queuedMatching.clear();
                return rejected(CoreResultCode.EXPORT_BACKLOG_FULL);
            }
        }
        boolean tradingStateChanged = status == ResponseStatus.APPLIED && tradingState != beforeTradingState;
        if (status == ResponseStatus.APPLIED) {
            List<Long> changedOrderIds = commandChangedOrderIds == null ? List.of() : commandChangedOrderIds;
            try {
                adoptState(tradingState.stampOrderChanges(beforeTradingState, clusterTimestamp, clusterPosition,
                        changedOrderIds));
            } catch (IllegalStateException exception) {
                restoreCommandState(beforeTradingState);
                runtime.restoreStateOnly(beforeTradingState);
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        try {
            commandDelta = commandDelta(beforeTradingState, tradingState, exportCommand);
        } catch (IllegalStateException exception) {
            if (tradingStateChanged) restoreCommandState(beforeTradingState);
            runtime.restoreStateOnly(beforeTradingState);
            return rejected(CoreResultCode.INVALID_COMMAND);
        }
        long businessStateHash = tradingStateChanged
                ? rollingBusinessStateHash.value() : cachedBusinessStateHash;
        if (exportCommand) {
            try {
                exportState.append(message, status, resultCode, Math.incrementExact(appliedCommandCount),
                        businessStateHash, commandDelta.changedUsers(), commandDelta.changedOrders(), commandDelta.executions(),
                        commandDelta.fundingPayments(),
                        commandDelta.changedLiquidations(), commandDelta.changedTreasuryAssets(),
                        commandDelta.changedTriggerOrders());
            } catch (CoreStateRejectedException exception) {
                if (!"EXPORT_BACKLOG_FULL".equals(exception.code())) throw exception;
                if (tradingStateChanged) restoreCommandState(beforeTradingState);
                runtime.restoreStateOnly(beforeTradingState);
                return rejected(CoreResultCode.EXPORT_BACKLOG_FULL);
            }
        }
        cachedBusinessStateHash = businessStateHash;
        appliedCommandCount = Math.incrementExact(appliedCommandCount);
        appendQueuedMatching(businessStateHash);
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

    private void appendQueuedMatching(long businessStateHash) {
        if (queuedMatching.isEmpty()) return;
        for (CoreMessage command : queuedMatching) {
            long sequence = Math.incrementExact(appliedCommandCount);
            exportState.append(command, ResponseStatus.APPLIED, matchingPendingCode(), sequence,
                    businessStateHash, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
            PendingMatching pending = new PendingMatching(sequence, PendingMatching.Operation.TRIGGER, command);
            pendingMatching.put(sequence, pending);
            appliedCommandCount = sequence;
            submitMatching(pending);
        }
        queuedMatching.clear();
    }

    private static boolean isMatchingCommand(CoreMessageType type) {
        return type == CoreMessageType.PLACE_ORDER || type == CoreMessageType.CANCEL_ORDER
                || type == CoreMessageType.REPLACE_ORDER || type == CoreMessageType.AMEND_ORDER
                || type == CoreMessageType.EXECUTE_LIQUIDATION || type == CoreMessageType.SETTLE_INSTRUMENT;
    }

    private CoreResponse beginBookQuery(CoreMessage message) {
        var query = message.payload().length == 0
                ? new com.surprising.aeron.protocol.CoreBookStateQuery("", 1_000)
                : CoreStateQueryCodec.decodeBookStateQuery(message.payload());
        long queryId = nextAsyncQueryId++;
        matchingAdapter.orderBookLevelsAsync(query.symbol(), query.depth()).whenComplete((levels, failure) -> {
            if (failure != null) {
                failedQueries.put(queryId, true);
                return;
            }
            completedBookQueries.put(queryId, levels);
        });
        queryIds.put(message.header().commandId(), queryId);
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, matchingPendingCode(),
                appliedCommandCount, cachedBusinessStateHash);
    }

    private CoreResponse beginMatching(CoreMessage message, long clusterTimestamp, long clusterPosition,
                                       SourceKey sourceKey) {
        if (!exportState.hasCapacityFor(2)) {
            return rejected(CoreResultCode.EXPORT_BACKLOG_FULL);
        }
        PendingMatching.Operation operation = switch (message.header().messageType()) {
            case PLACE_ORDER -> PendingMatching.Operation.PLACE;
            case CANCEL_ORDER -> PendingMatching.Operation.CANCEL;
            case REPLACE_ORDER -> PendingMatching.Operation.REPLACE;
            case AMEND_ORDER -> PendingMatching.Operation.AMEND;
            case EXECUTE_LIQUIDATION -> PendingMatching.Operation.LIQUIDATION;
            case SETTLE_INSTRUMENT -> PendingMatching.Operation.SETTLEMENT;
            default -> throw new IllegalArgumentException("not a matching command");
        };
        TradingCoreState before = tradingState;
        commandOrderViews = List.of();
        commandChangedUserIds = List.of();
        commandChangedOrderIds = List.of();
        commandChangedTreasuryAssets = List.of();
        commandExecutions = List.of();
        commandDelta = CoreCommandDelta.empty();
        try {
            switch (operation) {
                case PLACE -> {
                    var command = TradingCommandCodec.decodePlaceOrder(message.payload());
                    adoptState(tradingReducer.placeOrder(tradingState, message.header().userId(), command,
                            message.header().commandId(), openInterestIndex.openInterestSteps(command.symbol())));
                    commandChangedUserIds = List.of(message.header().userId());
                    commandChangedOrderIds = List.of(command.orderId());
                    commandOrderViews = List.of(orderView(tradingState.order(command.orderId())));
                }
                case CANCEL -> validatePendingCancel(message);
                case REPLACE -> validatePendingReplace(message, false);
                case AMEND -> validatePendingReplace(message, true);
                case LIQUIDATION -> validatePendingLiquidation(message);
                case SETTLEMENT -> validatePendingSettlement(message);
            }
        } catch (CoreStateRejectedException exception) {
            return recordRejectedMatching(message, sourceKey, CoreResultCode.fromRejectionCode(exception.code()));
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return recordRejectedMatching(message, sourceKey, exception instanceof ArithmeticException
                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND);
        }
        boolean tradingStateChanged = tradingState != before;
        if (tradingStateChanged) {
            try {
                adoptState(tradingState.stampOrderChanges(before, clusterTimestamp, clusterPosition,
                        commandChangedOrderIds));
            } catch (RuntimeException exception) {
                restoreCommandState(before);
                runtime.restoreStateOnly(before);
                throw exception;
            }
        }
        try {
            commandDelta = commandDelta(before, tradingState, true);
        } catch (RuntimeException exception) {
            if (tradingStateChanged) {
                restoreCommandState(before);
                runtime.restoreStateOnly(before);
            }
            return rejected(CoreResultCode.INVALID_COMMAND);
        }
        long businessStateHash = tradingStateChanged ? rollingBusinessStateHash.value() : cachedBusinessStateHash;
        long sequence = Math.incrementExact(appliedCommandCount);
        try {
            exportState.append(message, ResponseStatus.APPLIED, matchingPendingCode(), sequence,
                    businessStateHash, commandDelta.changedUsers(), commandDelta.changedOrders(),
                    commandDelta.executions(), commandDelta.fundingPayments(), commandDelta.changedLiquidations(),
                    commandDelta.changedTreasuryAssets(), commandDelta.changedTriggerOrders());
        } catch (CoreStateRejectedException exception) {
            if (tradingStateChanged) {
                restoreCommandState(before);
                runtime.restoreStateOnly(before);
            }
            return rejected(CoreResultCode.fromRejectionCode(exception.code()));
        }
        PendingMatching pending = new PendingMatching(sequence, operation, message);
        pendingMatching.put(sequence, pending);
        cachedBusinessStateHash = businessStateHash;
        appliedCommandCount = sequence;
        Long previousSourceSequence = lastSourceSequences.put(sourceKey, message.header().sourceSequence());
        if (previousSourceSequence != null) {
            lastSourceSequenceDigest ^= sourceSequenceDigest(sourceKey, previousSourceSequence);
        }
        lastSourceSequenceDigest ^= sourceSequenceDigest(sourceKey, message.header().sourceSequence());
        if (commandResults.size() >= MAX_IDEMPOTENCY_RESULTS) {
            commandResults.remove(commandResults.keySet().iterator().next());
        }
        long stateHash = stateHash(businessStateHash, message.header().commandId(), ResponseStatus.OK,
                matchingPendingCode(), appliedCommandCount);
        byte[] responseData = commandResultData();
        commandResults.put(message.header().commandId(), new StoredResult(ResponseStatus.OK,
                matchingPendingCode(), appliedCommandCount, stateHash, responseData));
        submitMatching(pending);
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, matchingPendingCode(),
                appliedCommandCount, stateHash, responseData);
    }

    private CoreResponse recordRejectedMatching(CoreMessage message, SourceKey sourceKey,
                                                CoreResultCode resultCode) {
        long sequence = Math.incrementExact(appliedCommandCount);
        exportState.append(message, ResponseStatus.REJECTED, resultCode, sequence,
                cachedBusinessStateHash, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        appliedCommandCount = sequence;
        Long previousSourceSequence = lastSourceSequences.put(sourceKey, message.header().sourceSequence());
        if (previousSourceSequence != null) {
            lastSourceSequenceDigest ^= sourceSequenceDigest(sourceKey, previousSourceSequence);
        }
        lastSourceSequenceDigest ^= sourceSequenceDigest(sourceKey, message.header().sourceSequence());
        if (commandResults.size() >= MAX_IDEMPOTENCY_RESULTS) {
            commandResults.remove(commandResults.keySet().iterator().next());
        }
        long stateHash = stateHash(cachedBusinessStateHash, message.header().commandId(),
                ResponseStatus.REJECTED, resultCode, appliedCommandCount);
        commandResults.put(message.header().commandId(), new StoredResult(ResponseStatus.REJECTED, resultCode,
                appliedCommandCount, stateHash, new byte[0]));
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, resultCode,
                appliedCommandCount, stateHash);
    }

    private void validatePendingCancel(CoreMessage message) {
        var command = TradingCommandCodec.decodeCancelOrder(message.payload());
        var order = tradingState.order(command.orderId());
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        if (order.userId() != message.header().userId()) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        commandChangedUserIds = List.of(message.header().userId());
        commandChangedOrderIds = List.of(command.orderId());
    }

    private void validatePendingReplace(CoreMessage message, boolean amend) {
        long originalOrderId;
        if (amend) {
            originalOrderId = TradingCommandCodec.decodeAmendOrder(message.payload()).originalOrderId();
        } else {
            originalOrderId = TradingCommandCodec.decodeReplaceOrder(message.payload()).originalOrderId();
        }
        var order = tradingState.order(originalOrderId);
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        if (order.userId() != message.header().userId()) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        if (order.status() != com.surprising.aeron.service.state.CoreOrderStatus.OPEN) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "order is not replaceable");
        }
        if (amend && order.orderType() != com.surprising.aeron.protocol.CoreOrderType.LIMIT) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "order is not amendable");
        }
        commandChangedUserIds = List.of(message.header().userId());
        commandChangedOrderIds = List.of(originalOrderId);
    }

    private void validatePendingLiquidation(CoreMessage message) {
        var command = TradingCommandCodec.decodeExecuteLiquidation(message.payload());
        var liquidation = tradingState.riskState().liquidations().get(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        commandChangedUserIds = List.of(liquidation.userId());
        commandChangedOrderIds = activeOrderIndex.ids(liquidation.userId(), liquidation.symbol()).stream()
                .mapToLong(Long::longValue).boxed().toList();
    }

    private void validatePendingSettlement(CoreMessage message) {
        var command = TradingCommandCodec.decodeSettleInstrument(message.payload());
        commandChangedUserIds = positionUserIndex.users(command.symbol()).stream().toList();
        commandChangedOrderIds = activeOrderIndex.ids(command.symbol()).stream().toList();
    }

    private void submitMatching(PendingMatching pending) {
        runtime.matcherReady().thenCompose(ignored -> submitMatchingNow(pending))
                .whenComplete((result, failure) -> completedMatching.put(pending.sequence(),
                        failure == null && result != null ? result
                                : new com.surprising.aeron.service.matching.CoreMatchingResult(false,
                                "EXCHANGE_CORE_FAILURE", List.of())));
    }

    private CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult> submitMatchingNow(
            PendingMatching pending) {
        CompletableFuture<com.surprising.aeron.service.matching.CoreMatchingResult> future;
        try {
            future = switch (pending.operation()) {
                case PLACE -> {
                    var command = TradingCommandCodec.decodePlaceOrder(pending.command().payload());
                    yield matchingAdapter.placeAsync(pending.command().header().userId(), command);
                }
                case CANCEL -> {
                    var command = TradingCommandCodec.decodeCancelOrder(pending.command().payload());
                    var order = tradingState.order(command.orderId());
                    yield matchingAdapter.cancelAsyncForContinuation(pending.command().header().userId(),
                            command.orderId(), order == null ? "" : order.symbol());
                }
                case REPLACE, AMEND -> {
                    var originalId = pending.operation() == PendingMatching.Operation.REPLACE
                            ? TradingCommandCodec.decodeReplaceOrder(pending.command().payload()).originalOrderId()
                            : TradingCommandCodec.decodeAmendOrder(pending.command().payload()).originalOrderId();
                    var order = tradingState.order(originalId);
                    var replacement = replacementFor(pending.command(), order);
                    yield matchingAdapter.replaceOrderAsync(pending.command().header().userId(), originalId,
                            order.symbol(), replacement);
                }
                case TRIGGER -> {
                    long[] execute = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(
                            pending.command().payload());
                    var trigger = tradingState.triggerOrders().get(execute[0]);
                    if (trigger == null) {
                        yield CompletableFuture.completedFuture(new com.surprising.aeron.service.matching.CoreMatchingResult(
                                false, "TRIGGER_ORDER_NOT_FOUND", List.of()));
                    }
                    yield matchingAdapter.placeAsync(trigger.userId(), triggerPlacement(trigger, execute[2]));
                }
                case LIQUIDATION -> {
                    var command = TradingCommandCodec.decodeExecuteLiquidation(pending.command().payload());
                    var liquidation = tradingState.riskState().liquidations().get(command.liquidationId());
                    if (liquidation == null || !tradingReducer.isLiquidationExecutable(tradingState, command)) {
                        yield CompletableFuture.completedFuture(new com.surprising.aeron.service.matching.CoreMatchingResult(
                                true, "SUCCESS", List.of()));
                    }
                    yield matchingAdapter.cancelBatchAsync(activeOrderIndex.ids(liquidation.userId(), liquidation.symbol()).stream()
                            .map(tradingState::order).filter(java.util.Objects::nonNull).toList());
                }
                case SETTLEMENT -> {
                    var command = TradingCommandCodec.decodeSettleInstrument(pending.command().payload());
                    if (tradingState.treasuryState().lifecycleProgress(command.symbol()) != null) {
                        yield CompletableFuture.completedFuture(new com.surprising.aeron.service.matching.CoreMatchingResult(
                                true, "SUCCESS", List.of()));
                    }
                    yield matchingAdapter.cancelBatchAsync(activeOrderIndex.ids(command.symbol()).stream()
                            .map(tradingState::order).filter(java.util.Objects::nonNull).toList());
                }
            };
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(new com.surprising.aeron.service.matching.CoreMatchingResult(false,
                    "EXCHANGE_CORE_FAILURE", List.of()));
        }
        return future;
    }

    private com.surprising.aeron.protocol.PlaceOrderCommand replacementFor(CoreMessage message,
                                                                            com.surprising.aeron.service.state.CoreOrderState order) {
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        var instrument = tradingState.instruments().get(order.symbol());
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument is missing");
        if (message.header().messageType() == CoreMessageType.REPLACE_ORDER) {
            return TradingCommandCodec.decodeReplaceOrder(message.payload()).replacement();
        }
        var command = TradingCommandCodec.decodeAmendOrder(message.payload());
        long priceTicks = command.priceTicks() == null ? order.priceTicks() : command.priceTicks();
        long quantitySteps = command.quantitySteps() == null ? order.remainingQuantitySteps() : command.quantitySteps();
        var timeInForce = command.timeInForce() == null ? order.timeInForce() : command.timeInForce();
        boolean postOnly = command.postOnly() == null ? order.postOnly() : command.postOnly();
        String clientOrderId = command.newClientOrderId() == null ? "" : command.newClientOrderId();
        return new com.surprising.aeron.protocol.PlaceOrderCommand(command.replacementOrderId(), order.symbol(),
                order.instrumentVersion(), instrument.baseAsset(), instrument.quoteAsset(), instrument.settleAsset(),
                order.side(), priceTicks, quantitySteps, order.reduceOnly(), order.marginMode(), order.positionSide(),
                instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT
                        ? com.surprising.aeron.protocol.ReservationKind.SPOT_ASSET
                        : com.surprising.aeron.protocol.ReservationKind.DERIVATIVE_MARGIN,
                instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT
                        ? order.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY
                                ? instrument.quoteAsset() : instrument.baseAsset()
                        : instrument.settleAsset(), 0, order.orderType(), timeInForce, priceTicks, postOnly,
                clientOrderId, order.makerFeeRatePpm(), order.takerFeeRatePpm());
    }

    private com.surprising.aeron.protocol.PlaceOrderCommand triggerPlacement(
            com.surprising.aeron.service.state.CoreTriggerOrderState trigger, long triggeredPriceTicks) {
        var instrument = tradingState.instruments().get(trigger.symbol());
        var order = tradingState.orders().values().stream()
                .filter(value -> value.userId() == trigger.userId())
                .filter(value -> value.clientOrderId().equals("TRIGGER:" + trigger.triggerOrderId()))
                .findFirst().orElseThrow(() -> new CoreStateRejectedException("ORDER_NOT_FOUND",
                        "trigger child order not found"));
        boolean spot = instrument != null
                && instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT;
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument is missing");
        return new com.surprising.aeron.protocol.PlaceOrderCommand(order.orderId(), trigger.symbol(),
                trigger.instrumentVersion(), instrument.baseAsset(), instrument.quoteAsset(), instrument.settleAsset(),
                trigger.side(), order.priceTicks(), order.quantitySteps(), !spot, trigger.marginMode(), trigger.positionSide(),
                spot ? com.surprising.aeron.protocol.ReservationKind.SPOT_ASSET
                        : com.surprising.aeron.protocol.ReservationKind.DERIVATIVE_MARGIN,
                spot && trigger.side() == com.surprising.aeron.protocol.CoreOrderSide.BUY
                        ? instrument.quoteAsset() : spot ? instrument.baseAsset() : instrument.settleAsset(), 0,
                trigger.orderType(), trigger.timeInForce(), order.priceTicks() > 0 ? order.priceTicks() : triggeredPriceTicks,
                false, order.clientOrderId(), trigger.makerFeeRatePpm(), trigger.takerFeeRatePpm());
    }

    com.surprising.aeron.service.matching.CoreMatchingResult takeMatchingResult(long sequence) {
        if (pendingMatching.isEmpty() || pendingMatching.keySet().iterator().next() != sequence) return null;
        return completedMatching.remove(sequence);
    }

    CompletableFuture<Integer> matchingStateHashAsync() {
        runtime.assertOwner();
        return runtime.matcherReady().thenCompose(ignored -> matchingAdapter.orderBooksStateHashAsync());
    }

    CoreResponse completeMatching(long sequence,
                                  com.surprising.aeron.service.matching.CoreMatchingResult matchingResult,
                                  long clusterTimestamp, long clusterPosition) {
        runtime.assertOwner();
        PendingMatching pending = pendingMatching.remove(sequence);
        if (pending == null || matchingResult == null) return null;
        TradingCoreState before = tradingState;
        commandOrderViews = List.of();
        commandChangedUserIds = List.of();
        commandChangedOrderIds = List.of();
        commandChangedLiquidationIds = List.of();
        commandChangedTriggerOrderIds = List.of();
        commandChangedTreasuryAssets = List.of();
        commandExecutions = List.of();
        ResponseStatus status = matchingResult.accepted() ? ResponseStatus.APPLIED : ResponseStatus.REJECTED;
        CoreResultCode resultCode = matchingResult.accepted() ? CoreResultCode.NONE : CoreResultCode.MATCHING_REJECTED;
        try {
            switch (pending.operation()) {
                case PLACE -> {
                    var command = TradingCommandCodec.decodePlaceOrder(pending.command().payload());
                    commandChangedUserIds = java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(pending.command().header().userId()),
                            matchingResult.matches().stream().map(com.surprising.aeron.service.matching.CoreMatch::makerUserId))
                            .distinct().toList();
                    commandChangedOrderIds = java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(command.orderId()),
                            matchingResult.matches().stream().map(com.surprising.aeron.service.matching.CoreMatch::makerOrderId))
                            .distinct().toList();
                    adoptState(matchingResult.accepted()
                            ? tradingReducer.applyMatches(tradingState, command.orderId(), command.baseAsset(),
                            command.quoteAsset(), matchingResult.matches())
                            : tradingReducer.rejectPlaceOrder(tradingState, pending.command().header().userId(),
                            command.orderId()));
                    commandExecutions = executionViews(command.orderId(), pending.command().header().userId(),
                            matchingResult.matches());
                }
                case CANCEL -> {
                    var command = TradingCommandCodec.decodeCancelOrder(pending.command().payload());
                    commandChangedUserIds = List.of(pending.command().header().userId());
                    commandChangedOrderIds = List.of(command.orderId());
                    if (matchingResult.accepted()) {
                        adoptState(tradingReducer.cancelOrder(tradingState,
                                pending.command().header().userId(), command));
                    }
                }
                case REPLACE, AMEND -> {
                    var command = replacementFor(pending.command(), tradingState.order(
                            pending.operation() == PendingMatching.Operation.REPLACE
                                    ? TradingCommandCodec.decodeReplaceOrder(pending.command().payload()).originalOrderId()
                                    : TradingCommandCodec.decodeAmendOrder(pending.command().payload()).originalOrderId()));
                    long originalOrderId = pending.operation() == PendingMatching.Operation.REPLACE
                            ? TradingCommandCodec.decodeReplaceOrder(pending.command().payload()).originalOrderId()
                            : TradingCommandCodec.decodeAmendOrder(pending.command().payload()).originalOrderId();
                    commandChangedUserIds = java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(pending.command().header().userId()),
                            matchingResult.matches().stream().map(com.surprising.aeron.service.matching.CoreMatch::makerUserId))
                            .distinct().toList();
                    commandChangedOrderIds = java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(originalOrderId, command.orderId()),
                            matchingResult.matches().stream().map(com.surprising.aeron.service.matching.CoreMatch::makerOrderId))
                            .distinct().toList();
                    if (matchingResult.accepted()) {
                        adoptState(tradingReducer.cancelOrder(tradingState,
                                pending.command().header().userId(), new com.surprising.aeron.protocol.CancelOrderCommand(originalOrderId)));
                        adoptState(tradingReducer.placeOrder(tradingState, pending.command().header().userId(), command,
                                pending.command().header().commandId(), openInterestIndex.openInterestSteps(command.symbol())));
                        adoptState(tradingReducer.applyMatches(tradingState, command.orderId(), command.baseAsset(),
                                command.quoteAsset(), matchingResult.matches()));
                        commandExecutions = executionViews(command.orderId(), pending.command().header().userId(),
                                matchingResult.matches());
                    }
                }
                case TRIGGER -> {
                    long[] execute = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(
                            pending.command().payload());
                    var trigger = tradingState.triggerOrders().get(execute[0]);
                    if (trigger == null) throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND",
                            "trigger order not found");
                    var command = triggerPlacement(trigger, execute[2]);
                    commandChangedUserIds = java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(trigger.userId()),
                            matchingResult.matches().stream().map(com.surprising.aeron.service.matching.CoreMatch::makerUserId))
                            .distinct().toList();
                    commandChangedOrderIds = java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(command.orderId()),
                            matchingResult.matches().stream().map(com.surprising.aeron.service.matching.CoreMatch::makerOrderId))
                            .distinct().toList();
                    if (matchingResult.accepted()) {
                        adoptState(tradingReducer.applyMatches(tradingState, command.orderId(), command.baseAsset(),
                                command.quoteAsset(), matchingResult.matches()));
                    } else {
                        adoptState(tradingReducer.rejectPlaceOrder(tradingState, trigger.userId(), command.orderId()));
                    }
                    adoptState(tradingReducer.completeTriggerOrder(tradingState, trigger.triggerOrderId(),
                            matchingResult.accepted(), matchingResult.accepted() ? command.orderId() : 0,
                            matchingResult.accepted() ? "" : matchingResult.resultCode(), execute[3]));
                    commandExecutions = executionViews(command.orderId(), trigger.userId(), matchingResult.matches());
                    commandOrderViews = commandChangedOrderIds.stream().map(tradingState::order)
                            .filter(java.util.Objects::nonNull).map(CoreProbeState::orderView).toList();
                }
                case LIQUIDATION -> {
                    var command = TradingCommandCodec.decodeExecuteLiquidation(pending.command().payload());
                    var liquidation = tradingState.riskState().liquidations().get(command.liquidationId());
                    if (matchingResult.accepted() && liquidation != null) {
                        adoptState(tradingReducer.executeLiquidation(tradingState, command));
                    }
                }
                case SETTLEMENT -> {
                    var command = TradingCommandCodec.decodeSettleInstrument(pending.command().payload());
                    if (matchingResult.accepted()) {
                        var settlement = tradingReducer.settleInstrumentWithProgress(tradingState, command,
                                positionUserIndex.users(command.symbol()), pending.command().header().commandId(), activeOrderIndex);
                        adoptState(settlement.state());
                        commandSettlementProgress = settlement.progress();
                    }
                }
            }
        } catch (CoreStateRejectedException exception) {
            status = ResponseStatus.REJECTED;
            resultCode = CoreResultCode.fromRejectionCode(exception.code());
            restoreCommandState(before);
            runtime.restoreStateOnly(before);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            status = ResponseStatus.REJECTED;
            resultCode = exception instanceof ArithmeticException
                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND;
            restoreCommandState(before);
            runtime.restoreStateOnly(before);
        }
        if (tradingState != before) {
            adoptState(tradingState.stampOrderChanges(before, clusterTimestamp, clusterPosition,
                    commandChangedOrderIds));
        }
        commandDelta = commandDelta(before, tradingState, true);
        long businessStateHash = tradingState == before ? cachedBusinessStateHash : rollingBusinessStateHash.value();
        long applied = Math.incrementExact(appliedCommandCount);
        exportState.append(pending.command(), status, resultCode, applied, businessStateHash,
                commandDelta.changedUsers(), commandDelta.changedOrders(), commandDelta.executions(),
                commandDelta.fundingPayments(), commandDelta.changedLiquidations(), commandDelta.changedTreasuryAssets(),
                commandDelta.changedTriggerOrders());
        cachedBusinessStateHash = businessStateHash;
        appliedCommandCount = applied;
        long stateHash = stateHash(businessStateHash, pending.command().header().commandId(), status, resultCode, applied);
        byte[] responseData = commandResultData();
        commandResults.put(pending.command().header().commandId(),
                new StoredResult(status, resultCode, applied, stateHash, responseData));
        return new CoreResponse(status, status, resultCode, applied, stateHash, responseData);
    }

    private void submitMatchingForRestore(PendingMatching pending) {
        submitMatching(pending);
    }

    void resumePendingMatching() {
        pendingMatching.values().forEach(this::submitMatchingForRestore);
    }

    boolean isMatchingPending(UUID commandId) {
        return pendingMatching.values().stream().anyMatch(value -> value.command().header().commandId().equals(commandId));
    }

    long matchingSequence(UUID commandId) {
        return pendingMatching.values().stream()
                .filter(value -> value.command().header().commandId().equals(commandId))
                .mapToLong(PendingMatching::sequence).findFirst().orElse(0);
    }

    long querySequence(UUID queryId) {
        return queryIds.getOrDefault(queryId, 0L);
    }

    CoreResponse takeQueryResult(long queryId) {
        if (failedQueries.remove(queryId) != null) {
            queryIds.values().removeIf(value -> value == queryId);
            return rejected(CoreResultCode.MATCHING_REJECTED);
        }
        List<com.surprising.aeron.protocol.CoreBookLevelView> levels = completedBookQueries.remove(queryId);
        if (levels == null) return null;
        var view = new com.surprising.aeron.protocol.CoreBookStateView(
                Math.decrementExact(exportState.nextSequence()), levels);
        queryIds.values().removeIf(value -> value == queryId);
        return new CoreResponse(ResponseStatus.OK, appliedCommandCount, cachedBusinessStateHash,
                CoreStateQueryCodec.encodeBookState(view));
    }


    Map<Long, PendingMatching> pendingMatching() {
        return Collections.unmodifiableMap(pendingMatching);
    }

    PendingMatching pendingMatching(long sequence) {
        return pendingMatching.get(sequence);
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
            case PROBE_INCREMENT -> probeValue = Math.addExact(
                    probeValue, CoreProtocol.decodeProbeDelta(message.payload()));
            case VERIFY_STATE_HASH -> {
            }
            case ADJUST_BALANCE -> {
                commandChangedUserIds = List.of(message.header().userId());
                adoptState(tradingReducer.adjustBalance(
                        tradingState, message.header().userId(),
                        TradingCommandCodec.decodeBalanceAdjustment(message.payload())));
            }
            case PLACE_ORDER, CANCEL_ORDER, REPLACE_ORDER, AMEND_ORDER,
                    EXECUTE_LIQUIDATION, SETTLE_INSTRUMENT ->
                    throw new IllegalStateException("matching command must use async continuation");
            case UPSERT_INSTRUMENT -> {
                var command = TradingCommandCodec.decodeUpsertInstrument(message.payload());
                TradingCoreState next = tradingReducer.upsertInstrument(tradingState, command);
                adoptState(next);
            }
            case APPLY_MARK_PRICE -> {
                var command = TradingCommandCodec.decodeApplyMarkPrice(message.payload());
                int pendingBefore = pendingRiskScanCount();
                long startedAt = System.nanoTime();
                adoptState(tradingReducer.applyMarkPrice(tradingState, command, positionUserIndex, liquidationIndex));
                logRiskScan("mark-price", command.symbol(), DEFAULT_RISK_SCAN_BATCH_SIZE,
                        pendingBefore, startedAt);
                evaluateMarkPriceTriggers(command, message.header().commandId(), message.header().submittedAtEpochMillis());
            }
            case APPLY_FUNDING -> {
                var command = TradingCommandCodec.decodeApplyFunding(message.payload());
                var result = tradingReducer.applyFundingWithFacts(tradingState,
                        command, positionUserIndex.users(command.symbol()), message.header().commandId());
                adoptState(result.state());
                commandFundingPayments = result.payments();
                commandFundingProgress = result.progress();
                commandChangedUserIds = commandFundingPayments.stream()
                        .map(com.surprising.aeron.protocol.CoreFundingPaymentView::userId).distinct().toList();
            }
            case EXECUTE_ADL -> {
                var command = TradingCommandCodec.decodeExecuteAdl(message.payload());
                commandChangedUserIds = List.of(command.targetUserId());
                adoptState(tradingReducer.executeAdl(tradingState, command));
            }
            case RESOLVE_LIQUIDATION -> adoptState(tradingReducer.resolveLiquidation(tradingState,
                    TradingCommandCodec.decodeResolveLiquidation(message.payload())));
            case CONTINUE_RISK_SCAN -> {
                var command = TradingCommandCodec.decodeContinueRiskScan(message.payload());
                String symbol = tradingState.riskState().scan().symbol();
                int pendingBefore = pendingRiskScanCount();
                long startedAt = System.nanoTime();
                adoptState(tradingReducer.continueRiskScan(tradingState, command.maxUsers(), positionUserIndex,
                        liquidationIndex));
                logRiskScan("continuation", symbol, command.maxUsers(), pendingBefore, startedAt);
            }
            case ACK_EXPORT -> {
                var acknowledgedTerminalOrderIds = exportState.acknowledge(
                        CoreExportCodec.decodeAck(message.payload()));
                commandChangedUserIds = acknowledgedTerminalOrderIds.stream()
                        .map(tradingState::order)
                        .filter(java.util.Objects::nonNull)
                        .map(com.surprising.aeron.service.state.CoreOrderState::userId)
                        .distinct().toList();
                adoptState(tradingReducer.pruneAcknowledgedTerminalReservations(
                        tradingState, acknowledgedTerminalOrderIds));
            }
            case UPDATE_POSITION_MODE -> {
                commandChangedUserIds = List.of(message.header().userId());
                adoptState(tradingReducer.updatePositionMode(
                        tradingState, message.header().userId(),
                        TradingCommandCodec.decodeUpdatePositionMode(message.payload())));
            }
            case ADJUST_POSITION_MARGIN -> {
                commandChangedUserIds = List.of(message.header().userId());
                adoptState(tradingReducer.adjustPositionMargin(
                        tradingState, message.header().userId(),
                        TradingCommandCodec.decodeAdjustPositionMargin(message.payload())));
            }
            case ADJUST_INSURANCE_FUND -> adoptState(tradingReducer.adjustInsuranceFund(
                    tradingState, TradingCommandCodec.decodeAdjustInsuranceFund(message.payload())));
            case UPDATE_LEVERAGE -> {
                commandChangedUserIds = List.of(message.header().userId());
                adoptState(tradingReducer.updateLeverage(
                        tradingState, message.header().userId(),
                        TradingCommandCodec.decodeUpdateLeverage(message.payload())));
            }
            case UPSERT_ALGO_ORDER -> adoptState(tradingReducer.upsertAlgoOrder(tradingState,
                    message.header().userId(), com.surprising.aeron.protocol.CoreAlgoOrderCodec.decode(message.payload()),
                    algoOrderIndex));
            case UPDATE_CANCEL_ALL_AFTER -> adoptState(tradingReducer.updateCancelAllAfter(tradingState,
                    message.header().userId(),
                    com.surprising.aeron.protocol.CoreCancelAllAfterCodec.decodeCommand(message.payload())));
            case PLACE_TRIGGER_ORDER -> adoptState(tradingReducer.upsertTriggerOrder(tradingState,
                    message.header().userId(), com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeState(message.payload()),
                    triggerOrderIndex));
            case CANCEL_TRIGGER_ORDER -> adoptState(tradingReducer.cancelTriggerOrder(tradingState,
                    message.header().userId(), com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeId(message.payload())));
            case CLAIM_TRIGGER_ORDER -> {
                long[] claim = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeClaim(message.payload());
                adoptState(tradingReducer.claimTriggerOrder(tradingState, claim[0], claim[1], claim[2], claim[3]));
            }
            case COMPLETE_TRIGGER_ORDER -> {
                long[] complete = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeComplete(message.payload());
                adoptState(tradingReducer.completeTriggerOrder(tradingState, complete[0], complete[1] == 1,
                        complete[2], "", complete[3]));
            }
            case UPDATE_TRIGGER_TRAILING -> {
                long[] trailing = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeTrailing(message.payload());
                adoptState(tradingReducer.updateTriggerTrailing(tradingState, trailing[0], trailing[1], trailing[2], trailing[3]));
            }
            case EXPIRE_TRIGGER_ORDER -> {
                long[] lifecycle = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeLifecycle(message.payload());
                adoptState(tradingReducer.expireTriggerOrder(tradingState, lifecycle[0], lifecycle[1]));
            }
            case RETRY_TRIGGER_ORDER -> {
                long[] lifecycle = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeLifecycle(message.payload());
                adoptState(tradingReducer.retryTriggerOrder(tradingState, lifecycle[0], lifecycle[1],
                        message.header().submittedAtEpochMillis()));
            }
            case EXECUTE_TRIGGER_ORDER -> executeTriggerOrder(message);
            default -> {
                return null;
            }
        }
        return ResponseStatus.APPLIED;
    }

    private java.util.stream.Stream<com.surprising.aeron.service.state.CoreOrderState> openOrders(long userId) {
        return (userId == 0 ? activeOrderIndex.ids() : activeOrderIndex.ids(userId)).stream()
                .map(tradingState::order)
                .filter(java.util.Objects::nonNull);
    }

    private void evaluateMarkPriceTriggers(com.surprising.aeron.protocol.ApplyMarkPriceCommand command,
                                           UUID commandId, long submittedAtEpochMillis) {
        long triggeredAt = command.generatedAtEpochMillis();
        for (long triggerOrderId : triggerOrderIndex.candidates(command.symbol(), command.markPriceTicks())) {
            var trigger = tradingState.triggerOrders().get(triggerOrderId);
            if (trigger == null || trigger.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
                continue;
            }
            if (trigger.expiresAtEpochMillis() > 0 && triggeredAt > 0
                    && trigger.expiresAtEpochMillis() <= triggeredAt) {
                adoptState(tradingReducer.expireTriggerOrder(tradingState, triggerOrderId, triggeredAt));
                markTriggerChanged(triggerOrderId);
                continue;
            }
            boolean triggered;
            if (trigger.triggerType() == com.surprising.aeron.protocol.CoreTriggerOrderType.TRAILING_STOP) {
                boolean sell = trigger.side() == com.surprising.aeron.protocol.CoreOrderSide.SELL;
                if (trigger.activationPriceTicks() > 0
                        && ((sell && command.markPriceTicks() < trigger.activationPriceTicks())
                        || (!sell && command.markPriceTicks() > trigger.activationPriceTicks()))) {
                    continue;
                }
                long highest = sell
                        ? Math.max(trigger.highestPriceTicks(), command.markPriceTicks())
                        : trigger.highestPriceTicks();
                long lowest = sell
                        ? trigger.lowestPriceTicks()
                        : trigger.lowestPriceTicks() == 0
                        ? command.markPriceTicks()
                        : Math.min(trigger.lowestPriceTicks(), command.markPriceTicks());
                long activatedAt = trigger.activatedAtEpochMillis() == 0 ? triggeredAt
                        : trigger.activatedAtEpochMillis();
                if (highest != trigger.highestPriceTicks() || lowest != trigger.lowestPriceTicks()
                        || activatedAt != trigger.activatedAtEpochMillis()) {
                    adoptState(tradingReducer.updateTriggerTrailing(tradingState, triggerOrderId,
                            highest, lowest, activatedAt));
                    markTriggerChanged(triggerOrderId);
                    trigger = tradingState.triggerOrders().get(triggerOrderId);
                }
                long base = sell ? trigger.highestPriceTicks() : trigger.lowestPriceTicks();
                long delta = trailingDelta(base, trigger.callbackRatePpm());
                long threshold = sell ? Math.subtractExact(base, delta) : Math.addExact(base, delta);
                triggered = trigger.activatedAtEpochMillis() > 0
                        && (sell ? command.markPriceTicks() <= threshold : command.markPriceTicks() >= threshold);
            } else {
                triggered = trigger.triggerCondition()
                        == com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL
                        ? command.markPriceTicks() >= trigger.triggerPriceTicks()
                        : command.markPriceTicks() <= trigger.triggerPriceTicks();
            }
            if (!triggered) continue;
            cancelOcoSiblings(trigger);
            executeTriggerOrder(triggerOrderId, command.priceSequence(), command.markPriceTicks(), triggeredAt, commandId);
        }
    }

    private void cancelTriggersForClosedPositions(TradingCoreState before) {
        if (commandChangedUserIds == null || commandChangedUserIds.isEmpty()) return;
        for (long userId : commandChangedUserIds) {
            var previousUser = before.user(userId);
            var currentUser = tradingState.user(userId);
            if (previousUser == null || currentUser == null) continue;
            java.util.LinkedHashSet<String> positionKeys = new java.util.LinkedHashSet<>();
            positionKeys.addAll(previousUser.positions().keySet());
            positionKeys.addAll(currentUser.positions().keySet());
            for (String positionKey : positionKeys) {
                var previous = previousUser.positions().get(positionKey);
                var current = currentUser.positions().get(positionKey);
                if (previous == null || previous.signedQuantitySteps() == 0
                        || (current != null && current.signedQuantitySteps() != 0)) continue;
                var position = current == null ? previous : current;
                for (long triggerOrderId : triggerOrderIndex.ids(userId, position.symbol(), position.marginMode(),
                        position.positionSide())) {
                    var trigger = tradingState.triggerOrders().get(triggerOrderId);
                    if (trigger != null && trigger.status()
                            == com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
                        adoptState(tradingReducer.cancelTriggerOrder(tradingState, userId, triggerOrderId));
                        markTriggerChanged(triggerOrderId);
                    }
                }
            }
        }
    }

    private static long trailingDelta(long base, long callbackRatePpm) {
        if (base <= 0 || callbackRatePpm <= 0) return 0;
        return Math.floorDiv(Math.multiplyExact(base, callbackRatePpm), 1_000_000L);
    }

    private static boolean isTriggerConditionSatisfied(
            com.surprising.aeron.service.state.CoreTriggerOrderState trigger, long priceTicks) {
        if (trigger.triggerType() == com.surprising.aeron.protocol.CoreTriggerOrderType.TRAILING_STOP) {
            boolean sell = trigger.side() == com.surprising.aeron.protocol.CoreOrderSide.SELL;
            long base = sell ? trigger.highestPriceTicks() : trigger.lowestPriceTicks();
            if (base <= 0 || trigger.activatedAtEpochMillis() <= 0) return false;
            long delta = trailingDelta(base, trigger.callbackRatePpm());
            long threshold = sell ? Math.subtractExact(base, delta) : Math.addExact(base, delta);
            return sell ? priceTicks <= threshold : priceTicks >= threshold;
        }
        return trigger.triggerCondition() == com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL
                ? priceTicks >= trigger.triggerPriceTicks() : priceTicks <= trigger.triggerPriceTicks();
    }

    private void cancelOcoSiblings(com.surprising.aeron.service.state.CoreTriggerOrderState trigger) {
        for (long siblingId : triggerOrderIndex.ocoSiblings(trigger)) {
            if (siblingId == trigger.triggerOrderId()) continue;
            var sibling = tradingState.triggerOrders().get(siblingId);
            if (sibling != null && sibling.status() == com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
                adoptState(tradingReducer.cancelTriggerOrder(tradingState, sibling.userId(), siblingId));
                markTriggerChanged(siblingId);
            }
        }
    }

    private void executeTriggerOrder(CoreMessage message) {
        long[] execute = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(message.payload());
        executeTriggerOrder(execute[0], execute[1], execute[2], execute[3], message.header().commandId());
    }

    private void executeTriggerOrder(long triggerOrderId, long triggerSequence, long triggeredPriceTicks,
                                     long triggeredAtEpochMillis, UUID commandId) {
        var trigger = tradingState.triggerOrders().get(triggerOrderId);
        if (trigger == null) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
        }
        if (trigger.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
            return;
        }
        var mark = tradingState.riskState().markPrices().get(trigger.symbol());
        if (mark != null && (mark.priceSequence() != triggerSequence
                || mark.markPriceTicks() != triggeredPriceTicks
                || !isTriggerConditionSatisfied(trigger, triggeredPriceTicks))) {
            throw new CoreStateRejectedException("TRIGGER_CONDITION_NOT_MET", "trigger price is not executable");
        }
        cancelOcoSiblings(trigger);
        TradingCoreState before = tradingState;
        TradingCoreState claimed = tradingReducer.claimTriggerOrder(before, triggerOrderId, triggerSequence,
                triggeredPriceTicks, triggeredAtEpochMillis);
        adoptState(claimed);
        markTriggerChanged(triggerOrderId);
        var instrument = claimed.instruments().get(trigger.symbol());
        if (instrument == null || instrument.version() <= 0 || trigger.instrumentVersion() <= 0
                || instrument.version() != trigger.instrumentVersion()) {
            adoptState(tradingReducer.completeTriggerOrder(claimed, triggerOrderId, false, 0,
                    instrument == null ? "INSTRUMENT_NOT_FOUND" : "STALE_INSTRUMENT_VERSION", triggeredAtEpochMillis));
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
                trigger.orderType(), trigger.timeInForce(), trigger.priceTicks() > 0 ? trigger.priceTicks() : triggeredPriceTicks, false,
                "TRIGGER:" + triggerOrderId, trigger.makerFeeRatePpm(), trigger.takerFeeRatePpm());
        TradingCoreState reserved;
        try {
            reserved = tradingReducer.placeOrder(claimed, trigger.userId(), place, commandId,
                    openInterestIndex.openInterestSteps(trigger.symbol()));
        } catch (CoreStateRejectedException exception) {
            adoptState(tradingReducer.completeTriggerOrder(claimed, triggerOrderId, false, 0,
                    exception.code(), triggeredAtEpochMillis));
            return;
        }
        adoptState(reserved);
        markUserChanged(trigger.userId());
        markOrderChanged(childOrderId);
        queueTriggerMatching(trigger, triggerSequence, triggeredPriceTicks, triggeredAtEpochMillis, commandId);
        commandOrderViews = appendDistinct(commandOrderViews, List.of(orderView(tradingState.order(childOrderId))));
    }

    private void adoptState(TradingCoreState next) {
        if (next == null || next.productLine() != productLine) {
            throw new IllegalArgumentException("invalid trading state");
        }
        if (next == tradingState) return;
        TradingCoreState previous = tradingState;
        runtime.transition(previous, next);
        rollingBusinessStateHash.update(previous, next);
        if (previous.users() != next.users()) {
            commandChangedUserIds = appendDistinct(commandChangedUserIds,
                    next.changedUserIds().stream().toList());
        }
        if (previous.orders() != next.orders()) {
            commandChangedOrderIds = appendDistinct(commandChangedOrderIds,
                    next.changedOrderIds().stream().toList());
        }
        if (previous.riskState().liquidations() != next.riskState().liquidations()) {
            commandChangedLiquidationIds = appendDistinct(commandChangedLiquidationIds,
                    next.changedLiquidationIds().stream().toList());
        }
        if (previous.triggerOrders() != next.triggerOrders()) {
            commandChangedTriggerOrderIds = appendDistinct(commandChangedTriggerOrderIds,
                    next.changedTriggerOrderIds().stream().toList());
        }
        if (previous.treasuryState() != next.treasuryState()) {
            commandChangedTreasuryAssets = appendDistinct(commandChangedTreasuryAssets,
                    next.changedTreasuryAssets().stream().toList());
        }
        tradingState = next;
    }

    private void restoreCommandState(TradingCoreState state) {
        tradingState = state;
        rollingBusinessStateHash.restore(state);
    }

    private static long triggerChildOrderId(long triggerOrderId, TradingCoreState state) {
        long candidate = Math.addExact(Math.multiplyExact(triggerOrderId, 2), 1);
        while (state.orders().containsKey(candidate)) {
            candidate = Math.addExact(candidate, 2);
        }
        return candidate;
    }

    private void queueTriggerMatching(com.surprising.aeron.service.state.CoreTriggerOrderState trigger,
                                      long triggerSequence, long triggeredPriceTicks,
                                      long triggeredAtEpochMillis, UUID parentCommandId) {
        UUID commandId = UUID.nameUUIDFromBytes((parentCommandId + ":trigger:" + trigger.triggerOrderId())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        CoreMessageHeader header = CoreMessageHeader.command(CoreMessageType.EXECUTE_TRIGGER_ORDER, commandId,
                productLine, com.surprising.aeron.protocol.CommandSource.OPERATIONS, trigger.userId(), 0,
                trigger.userId(), triggeredAtEpochMillis, 0);
        queuedMatching.add(new CoreMessage(header, com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeExecute(
                trigger.triggerOrderId(), triggerSequence, triggeredPriceTicks, triggeredAtEpochMillis)));
    }

    private void markTriggerChanged(long triggerOrderId) {
        commandChangedTriggerOrderIds = appendDistinct(commandChangedTriggerOrderIds, List.of(triggerOrderId));
    }

    private void markUserChanged(long userId) {
        commandChangedUserIds = appendDistinct(commandChangedUserIds, List.of(userId));
    }

    private void markOrderChanged(long orderId) {
        commandChangedOrderIds = appendDistinct(commandChangedOrderIds, List.of(orderId));
    }

    private static <T> List<T> appendDistinct(List<T> existing, List<T> additions) {
        java.util.LinkedHashSet<T> values = new java.util.LinkedHashSet<>();
        if (existing != null) values.addAll(existing);
        if (additions != null) values.addAll(additions);
        return List.copyOf(values);
    }

    private int pendingRiskScanCount() {
        return (int) tradingState.riskState().scans().values().stream()
                .filter(scan -> !scan.complete())
                .count();
    }

    private void logRiskScan(String operation, String symbol, int batchSize, int pendingBefore, long startedAt) {
        long elapsedMicros = (System.nanoTime() - startedAt) / 1_000L;
        int pendingAfter = pendingRiskScanCount();
        System.Logger.Level level = pendingAfter > 0 ? System.Logger.Level.INFO : System.Logger.Level.DEBUG;
        if (!LOG.isLoggable(level)) return;
        LOG.log(level, "risk scan operation={0} symbol={1} batchSize={2} elapsedMicros={3} "
                        + "pendingSymbolsBefore={4} pendingSymbolsAfter={5}",
                new Object[]{operation, symbol, batchSize, elapsedMicros, pendingBefore, pendingAfter});
    }

    @Override
    public void close() {
        completedMatching.clear();
        completedBookQueries.clear();
        failedQueries.clear();
        queryIds.clear();
        pendingMatching.clear();
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

    private CoreCommandDelta commandDelta(TradingCoreState before, TradingCoreState after, boolean includeViews) {
        List<Long> userIds = List.copyOf(commandChangedUserIds);
        List<Long> orderIds = List.copyOf(commandChangedOrderIds);
        List<Long> liquidationIds = List.copyOf(commandChangedLiquidationIds);
        List<Long> triggerOrderIds = List.copyOf(commandChangedTriggerOrderIds);
        List<CoreUserStateView> changedUsers = includeViews
                ? changedUsers(before, after, userIds)
                : List.of();
        List<CoreOrderStateView> changedOrders = includeViews
                ? changedOrders(before, after, orderIds)
                : List.of();
        List<com.surprising.aeron.protocol.CoreLiquidationView> changedLiquidations = includeViews
                ? changedLiquidations(before, after, liquidationIds)
                : List.of();
        List<com.surprising.aeron.protocol.CoreTreasuryAssetView> changedTreasuryAssets = includeViews
                ? changedTreasuryAssets(before, after, commandChangedTreasuryAssets)
                : List.of();
        List<com.surprising.aeron.protocol.CoreTriggerOrderStateView> changedTriggerOrders = includeViews
                ? changedTriggerOrders(before, after, triggerOrderIds)
                : List.of();
        return new CoreCommandDelta(
                userIds, orderIds, liquidationIds, triggerOrderIds,
                commandExecutions, commandFundingPayments, commandFundingProgress, commandSettlementProgress,
                changedUsers, changedOrders, changedLiquidations, changedTreasuryAssets, changedTriggerOrders);
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
        throw new IllegalStateException("changed user ids are required for export");
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
        throw new IllegalStateException("changed order ids are required for export");
    }

    private static List<com.surprising.aeron.protocol.CoreLiquidationView> changedLiquidations(
            TradingCoreState before, TradingCoreState after, List<Long> changedLiquidationIds) {
        if (changedLiquidationIds == null) {
            throw new IllegalStateException("changed liquidation ids are required for export");
        }
        java.util.stream.Stream<com.surprising.aeron.service.state.CoreLiquidationState> values =
                changedLiquidationIds.stream()
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
        if (changedTriggerOrderIds == null) {
            throw new IllegalStateException("changed trigger order ids are required for export");
        }
        java.util.stream.Stream<com.surprising.aeron.service.state.CoreTriggerOrderState> values =
                changedTriggerOrderIds.stream()
                        .map(id -> after.triggerOrders().get(id))
                        .filter(java.util.Objects::nonNull);
        return values.filter(value -> !value.equals(before.triggerOrders().get(value.triggerOrderId())))
                .map(com.surprising.aeron.service.state.CoreTriggerOrderState::view).toList();
    }

    private static List<com.surprising.aeron.protocol.CoreTreasuryAssetView> changedTreasuryAssets(
            TradingCoreState before, TradingCoreState after, List<String> changedAssetNames) {
        java.util.TreeSet<String> assets = new java.util.TreeSet<>();
        assets.addAll(changedAssetNames);
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

    private static CoreResultCode matchingPendingCode() {
        return CoreResultCode.fromWireCode(MATCHING_PENDING_WIRE_CODE);
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
