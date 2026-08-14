package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreBalanceView;
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
import com.surprising.aeron.service.state.CoreStateRejectedException;
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
    private static final long HASH_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long HASH_PRIME = 0x100000001b3L;

    private final ProductLine productLine;
    private final LinkedHashMap<UUID, StoredResult> commandResults;
    private final LinkedHashMap<SourceKey, Long> lastSourceSequences;
    private final TradingCoreReducer tradingReducer;
    private final DeterministicExchangeCoreAdapter matchingAdapter;
    private final CoreExportState exportState;
    private long appliedCommandCount;
    private long probeValue;
    private TradingCoreState tradingState;
    private List<com.surprising.aeron.protocol.CoreExecutionView> commandExecutions = List.of();
    private List<com.surprising.aeron.protocol.CoreFundingPaymentView> commandFundingPayments = List.of();

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
        this.exportState = exportState;
        this.tradingReducer = new TradingCoreReducer();
        this.matchingAdapter = new DeterministicExchangeCoreAdapter();
        this.matchingAdapter.rebuild(tradingState.bookState());
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
        if (message.header().productLine() != productLine) {
            return rejected(CoreResultCode.PRODUCT_LINE_MISMATCH);
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.STATE_HASH_QUERY) {
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, stateHash());
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.BUSINESS_STATE_HASH_QUERY) {
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, tradingState.businessStateHash());
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
                var orders = tradingState.orders().values().stream()
                        .filter(order -> requestedUserId == 0 || order.userId() == requestedUserId)
                        .filter(order -> order.status().name().equals("OPEN"))
                        .filter(order -> query.symbol().isEmpty() || order.symbol().equals(query.symbol()))
                        .filter(order -> order.orderId() < beforeOrderId)
                        .sorted(java.util.Comparator.comparingLong(
                                com.surprising.aeron.service.state.CoreOrderState::orderId).reversed())
                        .limit(query.limit())
                        .map(CoreProbeState::orderView)
                        .toList();
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, tradingState.businessStateHash(),
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
                var values = tradingState.triggerOrders().values().stream()
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
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, tradingState.businessStateHash(),
                        com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeList(values));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.BOOK_STATE_QUERY) {
            java.util.Map<BookLevelKey, long[]> aggregated = new java.util.HashMap<>();
            tradingState.bookState().openOrders().values().forEach(order -> {
                BookLevelKey key = new BookLevelKey(order.symbol(), order.side(), order.priceTicks());
                aggregated.compute(key, (ignored, current) -> current == null
                        ? new long[] {order.remainingQuantitySteps(), 1}
                        : new long[] {Math.addExact(current[0], order.remainingQuantitySteps()),
                                Math.incrementExact(current[1])});
            });
            var levels = aggregated.entrySet().stream()
                    .sorted(java.util.Comparator.comparing((java.util.Map.Entry<BookLevelKey, long[]> entry)
                                    -> entry.getKey().symbol())
                            .thenComparing(entry -> entry.getKey().side())
                            .thenComparingLong(entry -> entry.getKey().priceTicks()))
                    .map(entry -> new com.surprising.aeron.protocol.CoreBookLevelView(entry.getKey().symbol(),
                            entry.getKey().side(), entry.getKey().priceTicks(), entry.getValue()[0],
                            entry.getValue()[1])).toList();
            var view = new com.surprising.aeron.protocol.CoreBookStateView(
                    Math.decrementExact(exportState.nextSequence()), levels);
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, tradingState.businessStateHash(),
                    CoreStateQueryCodec.encodeBookState(view));
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
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, tradingState.businessStateHash(),
                    CoreStateQueryCodec.encodeTreasuryState(views));
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.ADL_CANDIDATE_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreAdlQueryCodec.decodeQuery(message.payload());
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, tradingState.businessStateHash(),
                        com.surprising.aeron.protocol.CoreAdlQueryCodec.encodeCandidates(
                                tradingReducer.adlCandidates(tradingState, query.asset(), query.limit())));
            } catch (IllegalArgumentException exception) {
                return rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.RISK_STATE_QUERY) {
            var views = tradingReducer.riskSnapshots(tradingState, message.header().userId());
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, tradingState.businessStateHash(),
                    com.surprising.aeron.protocol.CoreRiskQueryCodec.encode(views));
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.OPEN_INTEREST_QUERY) {
            java.util.Map<String, long[]> totals = new java.util.TreeMap<>();
            for (var user : tradingState.users().values()) {
                for (var position : user.positions().values()) {
                    if (position.signedQuantitySteps() == 0L) continue;
                    long[] value = totals.computeIfAbsent(position.symbol(), ignored -> new long[2]);
                    if (position.signedQuantitySteps() > 0L) {
                        value[0] = Math.addExact(value[0], position.signedQuantitySteps());
                    } else {
                        value[1] = Math.addExact(value[1], Math.absExact(position.signedQuantitySteps()));
                    }
                }
            }
            var views = totals.entrySet().stream()
                    .map(entry -> new com.surprising.aeron.protocol.CoreOpenInterestView(
                            entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                    .toList();
            return new CoreResponse(ResponseStatus.OK, appliedCommandCount, tradingState.businessStateHash(),
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
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, tradingState.businessStateHash(),
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
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, tradingState.businessStateHash(),
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
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, tradingState.businessStateHash(),
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
                        message.header().commandId());
                var reservation = preview.user(message.header().userId()).reservations().get(command.orderId());
                if (reservation == null) throw new IllegalStateException("preflight reservation is missing");
                var view = new com.surprising.aeron.protocol.CoreOrderPreflightView(
                        reservation.asset(), reservation.reservedUnits());
                return new CoreResponse(ResponseStatus.OK, appliedCommandCount, tradingState.businessStateHash(),
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
                    duplicate.appliedCommandCount(), duplicate.stateHash());
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
        commandExecutions = List.of();
        commandFundingPayments = List.of();
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
        if (status == ResponseStatus.APPLIED) {
            tradingState = tradingState.stampOrderChanges(beforeTradingState, clusterTimestamp, clusterPosition);
        }
        if (exportCommand) {
            try {
                exportState.append(message, status, resultCode, Math.incrementExact(appliedCommandCount),
                        tradingState.businessStateHash(), changedUsers(beforeTradingState, tradingState),
                        changedOrders(beforeTradingState, tradingState), commandExecutions, commandFundingPayments,
                        changedLiquidations(beforeTradingState, tradingState),
                        changedTreasuryAssets(beforeTradingState, tradingState),
                        changedTriggerOrders(beforeTradingState, tradingState));
            } catch (CoreStateRejectedException exception) {
                tradingState = beforeTradingState;
                matchingAdapter.rebuild(beforeTradingState.bookState());
                return rejected(CoreResultCode.fromRejectionCode(exception.code()));
            }
        }
        appliedCommandCount = Math.incrementExact(appliedCommandCount);
        lastSourceSequences.put(sourceKey, message.header().sourceSequence());
        commandResults.put(message.header().commandId(),
                new StoredResult(status, resultCode, appliedCommandCount, 0));
        trimIdempotencyWindow();
        long stateHash = stateHash();
        commandResults.put(message.header().commandId(),
                new StoredResult(status, resultCode, appliedCommandCount, stateHash));
        return new CoreResponse(status, status, resultCode, appliedCommandCount, stateHash);
    }

    private record BookLevelKey(String symbol, com.surprising.aeron.protocol.CoreOrderSide side, long priceTicks) {
    }

    public long stateHash() {
        long hash = HASH_OFFSET_BASIS;
        hash = mix(hash, productLine.ordinal());
        hash = mix(hash, appliedCommandCount);
        hash = mix(hash, probeValue);
        hash = mix(hash, tradingState.businessStateHash());
        hash = mix(hash, exportState.acknowledgedSequence());
        hash = mix(hash, exportState.nextSequence());
        hash = mix(hash, exportState.pendingCount());
        hash = mix(hash, exportState.pendingDigest());
        for (Map.Entry<SourceKey, Long> entry : lastSourceSequences.entrySet()) {
            hash = mix(hash, entry.getKey().source().wireCode());
            hash = mix(hash, entry.getKey().sourceId());
            hash = mix(hash, entry.getValue());
        }
        for (Map.Entry<UUID, StoredResult> entry : commandResults.entrySet()) {
            hash = mix(hash, entry.getKey().getMostSignificantBits());
            hash = mix(hash, entry.getKey().getLeastSignificantBits());
            hash = mix(hash, entry.getValue().status().wireCode());
            hash = mix(hash, entry.getValue().resultCode().wireCode());
            hash = mix(hash, entry.getValue().appliedCommandCount());
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

    private void trimIdempotencyWindow() {
        while (commandResults.size() > MAX_IDEMPOTENCY_RESULTS) {
            UUID oldest = commandResults.keySet().iterator().next();
            commandResults.remove(oldest);
        }
    }

    private ResponseStatus applyCommand(CoreMessage message) {
        switch (message.header().messageType()) {
            case PROBE_INCREMENT -> probeValue = Math.addExact(
                    probeValue, CoreProtocol.decodeProbeDelta(message.payload()));
            case VERIFY_STATE_HASH -> {
            }
            case ADJUST_BALANCE -> tradingState = tradingReducer.adjustBalance(
                    tradingState, message.header().userId(),
                    TradingCommandCodec.decodeBalanceAdjustment(message.payload()));
            case PLACE_ORDER -> placeOrder(message);
            case CANCEL_ORDER -> cancelOrder(message);
            case REPLACE_ORDER -> replaceOrder(message);
            case UPSERT_INSTRUMENT -> tradingState = tradingReducer.upsertInstrument(tradingState,
                    TradingCommandCodec.decodeUpsertInstrument(message.payload()));
            case APPLY_MARK_PRICE -> tradingState = tradingReducer.applyMarkPrice(tradingState,
                    TradingCommandCodec.decodeApplyMarkPrice(message.payload()));
            case APPLY_FUNDING -> {
                var result = tradingReducer.applyFundingWithFacts(tradingState,
                        TradingCommandCodec.decodeApplyFunding(message.payload()));
                tradingState = result.state();
                commandFundingPayments = result.payments();
            }
            case SETTLE_INSTRUMENT -> settleInstrument(message);
            case EXECUTE_LIQUIDATION -> executeLiquidation(message);
            case EXECUTE_ADL -> tradingState = tradingReducer.executeAdl(tradingState,
                    TradingCommandCodec.decodeExecuteAdl(message.payload()));
            case RESOLVE_LIQUIDATION -> tradingState = tradingReducer.resolveLiquidation(tradingState,
                    TradingCommandCodec.decodeResolveLiquidation(message.payload()));
            case CONTINUE_RISK_SCAN -> tradingState = tradingReducer.continueRiskScan(tradingState,
                    TradingCommandCodec.decodeContinueRiskScan(message.payload()).maxUsers());
            case ACK_EXPORT -> exportState.acknowledge(CoreExportCodec.decodeAck(message.payload()));
            case UPDATE_POSITION_MODE -> tradingState = tradingReducer.updatePositionMode(
                    tradingState, message.header().userId(),
                    TradingCommandCodec.decodeUpdatePositionMode(message.payload()));
            case ADJUST_POSITION_MARGIN -> tradingState = tradingReducer.adjustPositionMargin(
                    tradingState, message.header().userId(),
                    TradingCommandCodec.decodeAdjustPositionMargin(message.payload()));
            case ADJUST_INSURANCE_FUND -> tradingState = tradingReducer.adjustInsuranceFund(
                    tradingState, TradingCommandCodec.decodeAdjustInsuranceFund(message.payload()));
            case UPDATE_LEVERAGE -> tradingState = tradingReducer.updateLeverage(
                    tradingState, message.header().userId(),
                    TradingCommandCodec.decodeUpdateLeverage(message.payload()));
            case UPSERT_ALGO_ORDER -> tradingState = tradingReducer.upsertAlgoOrder(tradingState,
                    message.header().userId(), com.surprising.aeron.protocol.CoreAlgoOrderCodec.decode(message.payload()));
            case UPDATE_CANCEL_ALL_AFTER -> tradingState = tradingReducer.updateCancelAllAfter(tradingState,
                    message.header().userId(),
                    com.surprising.aeron.protocol.CoreCancelAllAfterCodec.decodeCommand(message.payload()));
            case PLACE_TRIGGER_ORDER -> tradingState = tradingReducer.upsertTriggerOrder(tradingState,
                    message.header().userId(), com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeState(message.payload()));
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
            default -> {
                return null;
            }
        }
        return ResponseStatus.APPLIED;
    }

    private void placeOrder(CoreMessage message) {
        var command = TradingCommandCodec.decodePlaceOrder(message.payload());
        TradingCoreState before = tradingState;
        TradingCoreState reserved = tradingReducer.placeOrder(before, message.header().userId(), command,
                message.header().commandId());
        try {
            var matchingResult = matchingAdapter.place(message.header().userId(), command);
            if (!matchingResult.accepted()) {
                throw new CoreStateRejectedException("MATCHING_REJECTED", matchingResult.resultCode());
            }
            tradingState = tradingReducer.applyMatches(reserved, command.orderId(),
                    command.baseAsset(), command.quoteAsset(), matchingResult.matches());
            commandExecutions = executionViews(command.orderId(), message.header().userId(), matchingResult.matches());
        } catch (RuntimeException exception) {
            matchingAdapter.rebuild(before.bookState());
            throw exception;
        }
    }

    private void cancelOrder(CoreMessage message) {
        var command = TradingCommandCodec.decodeCancelOrder(message.payload());
        TradingCoreState before = tradingState;
        TradingCoreState canceled = tradingReducer.cancelOrder(before, message.header().userId(), command);
        if (canceled == before) {
            return;
        }
        var order = before.order(command.orderId());
        try {
            var matchingResult = matchingAdapter.cancel(message.header().userId(), command.orderId(), order.symbol());
            if (!matchingResult.accepted()) {
                throw new CoreStateRejectedException("MATCHING_REJECTED", matchingResult.resultCode());
            }
            tradingState = canceled;
        } catch (RuntimeException exception) {
            matchingAdapter.rebuild(before.bookState());
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
        TradingCoreState canceled = tradingReducer.cancelOrder(before, message.header().userId(),
                new com.surprising.aeron.protocol.CancelOrderCommand(command.originalOrderId()));
        TradingCoreState prepared = tradingReducer.placeOrder(canceled, message.header().userId(),
                command.replacement(), message.header().commandId());
        try {
            var cancelResult = matchingAdapter.cancel(message.header().userId(), command.originalOrderId(),
                    order.symbol());
            if (!cancelResult.accepted()) {
                throw new CoreStateRejectedException("MATCHING_REJECTED", cancelResult.resultCode());
            }
            var matchingResult = matchingAdapter.place(message.header().userId(), command.replacement());
            if (!matchingResult.accepted()) {
                throw new CoreStateRejectedException("MATCHING_REJECTED", matchingResult.resultCode());
            }
            tradingState = tradingReducer.applyMatches(prepared, command.replacement().orderId(),
                    command.replacement().baseAsset(), command.replacement().quoteAsset(), matchingResult.matches());
            commandExecutions = executionViews(command.replacement().orderId(), message.header().userId(),
                    matchingResult.matches());
        } catch (RuntimeException exception) {
            matchingAdapter.rebuild(before.bookState());
            throw exception;
        }
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
        var openOrders = before.orders().values().stream()
                .filter(order -> order.userId() == liquidation.userId()
                        && order.symbol().equals(liquidation.symbol())
                        && order.status() == com.surprising.aeron.service.state.CoreOrderStatus.OPEN)
                .toList();
        try {
            for (var order : openOrders) {
                var result = matchingAdapter.cancel(order.userId(), order.orderId(), order.symbol());
                if (!result.accepted()) {
                    throw new CoreStateRejectedException("MATCHING_REJECTED", result.resultCode());
                }
            }
            tradingState = tradingReducer.executeLiquidation(before, command);
        } catch (RuntimeException exception) {
            matchingAdapter.rebuild(before.bookState());
            throw exception;
        }
    }

    private void settleInstrument(CoreMessage message) {
        var command = TradingCommandCodec.decodeSettleInstrument(message.payload());
        TradingCoreState before = tradingState;
        var openOrders = before.orders().values().stream()
                .filter(order -> order.symbol().equalsIgnoreCase(command.symbol())
                        && order.status() == com.surprising.aeron.service.state.CoreOrderStatus.OPEN)
                .toList();
        try {
            for (var order : openOrders) {
                var result = matchingAdapter.cancel(order.userId(), order.orderId(), order.symbol());
                if (!result.accepted()) {
                    throw new CoreStateRejectedException("MATCHING_REJECTED", result.resultCode());
                }
            }
            tradingState = tradingReducer.settleInstrument(before, command);
        } catch (RuntimeException exception) {
            matchingAdapter.rebuild(before.bookState());
            throw exception;
        }
    }

    int matchingStateHash() {
        return matchingAdapter.orderBooksStateHash();
    }

    @Override
    public void close() {
        matchingAdapter.close();
    }

    private CoreResponse userStateResponse(long userId) {
        var user = tradingState.user(userId);
        if (user == null) {
            return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                    CoreResultCode.ENTITY_NOT_FOUND, appliedCommandCount, tradingState.businessStateHash());
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

    private static List<CoreUserStateView> changedUsers(TradingCoreState before, TradingCoreState after) {
        return after.users().values().stream()
                .filter(user -> !user.equals(before.users().get(user.userId())))
                .map(user -> userDelta(before.users().get(user.userId()), user)).toList();
    }

    private static List<CoreOrderStateView> changedOrders(TradingCoreState before, TradingCoreState after) {
        return after.orders().values().stream()
                .filter(order -> !order.equals(before.orders().get(order.orderId())))
                .map(CoreProbeState::orderView).toList();
    }

    private static List<com.surprising.aeron.protocol.CoreLiquidationView> changedLiquidations(
            TradingCoreState before, TradingCoreState after) {
        return after.riskState().liquidations().values().stream()
                .filter(value -> !value.equals(before.riskState().liquidations().get(value.liquidationId())))
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
            TradingCoreState before, TradingCoreState after) {
        return after.triggerOrders().values().stream()
                .filter(value -> !value.equals(before.triggerOrders().get(value.triggerOrderId())))
                .map(com.surprising.aeron.service.state.CoreTriggerOrderState::view).toList();
    }

    private static List<com.surprising.aeron.protocol.CoreTreasuryAssetView> changedTreasuryAssets(
            TradingCoreState before, TradingCoreState after) {
        java.util.TreeSet<String> assets = new java.util.TreeSet<>();
        assets.addAll(before.treasuryState().feeBalances().keySet());
        assets.addAll(before.treasuryState().insuranceBalances().keySet());
        assets.addAll(before.treasuryState().insuranceDeficits().keySet());
        assets.addAll(after.treasuryState().feeBalances().keySet());
        assets.addAll(after.treasuryState().insuranceBalances().keySet());
        assets.addAll(after.treasuryState().insuranceDeficits().keySet());
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

    private static CoreUserStateView userView(com.surprising.aeron.service.state.CoreUserState user) {
        return new CoreUserStateView(user.productLine(), user.userId(), user.revision(), user.positionMode(),
                user.balances().values().stream().map(value -> new CoreBalanceView(
                        value.asset(), value.availableUnits(), value.lockedUnits())).toList(),
                user.reservations().values().stream().map(value -> new CoreReservationView(
                        value.orderId(), value.symbol(), value.instrumentVersion(), value.kind(), value.asset(),
                        value.reservedUnits(), value.releasedUnits(), value.consumedUnits(),
                        value.orderQuantitySteps())).toList(),
                user.positions().values().stream().map(value -> new CorePositionView(
                        value.symbol(), value.marginAsset(), value.marginMode(), value.positionSide(),
                        value.instrumentVersion(), value.signedQuantitySteps(), value.entryPriceTicks(),
                        value.entryValueTicks(), value.realizedPnlUnits(), value.positionMarginUnits())).toList());
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

    private CoreResponse orderStateResponse(long orderId) {
        return orderStateResponse(tradingState.order(orderId));
    }

    private CoreResponse orderStateResponse(com.surprising.aeron.service.state.CoreOrderState order) {
        if (order == null) {
            return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                    CoreResultCode.ENTITY_NOT_FOUND, appliedCommandCount, tradingState.businessStateHash());
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

    private CoreResponse rejected(CoreResultCode resultCode) {
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                resultCode, appliedCommandCount, stateHash());
    }

    record StoredResult(
            ResponseStatus status,
            CoreResultCode resultCode,
            long appliedCommandCount,
            long stateHash) {
    }

    record SourceKey(CommandSource source, long sourceId) {
    }
}
