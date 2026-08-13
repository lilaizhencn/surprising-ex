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
import com.surprising.aeron.service.state.CoreStateRejectedException;
import com.surprising.aeron.service.state.TradingCoreReducer;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import com.surprising.product.api.ProductLine;
import java.util.Collections;
import java.util.LinkedHashMap;
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
    private long appliedCommandCount;
    private long probeValue;
    private TradingCoreState tradingState;

    public CoreProbeState(ProductLine productLine) {
        this(productLine, 0, 0, new LinkedHashMap<>(), new LinkedHashMap<>(),
                TradingCoreState.empty(productLine));
    }

    private CoreProbeState(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            LinkedHashMap<UUID, StoredResult> commandResults,
            LinkedHashMap<SourceKey, Long> lastSourceSequences,
            TradingCoreState tradingState) {
        this.productLine = productLine;
        this.appliedCommandCount = appliedCommandCount;
        this.probeValue = probeValue;
        this.commandResults = commandResults;
        this.lastSourceSequences = lastSourceSequences;
        this.tradingState = tradingState;
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
            TradingCoreState tradingState) {
        if (appliedCommandCount < 0 || commandResults.size() > MAX_IDEMPOTENCY_RESULTS
                || tradingState == null || tradingState.productLine() != productLine) {
            throw new IllegalArgumentException("invalid restored probe state");
        }
        return new CoreProbeState(productLine, appliedCommandCount, probeValue,
                new LinkedHashMap<>(commandResults), new LinkedHashMap<>(lastSourceSequences), tradingState);
    }

    public CoreResponse apply(CoreMessage message) {
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

    public long stateHash() {
        long hash = HASH_OFFSET_BASIS;
        hash = mix(hash, productLine.ordinal());
        hash = mix(hash, appliedCommandCount);
        hash = mix(hash, probeValue);
        hash = mix(hash, tradingState.businessStateHash());
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
            default -> {
                return null;
            }
        }
        return ResponseStatus.APPLIED;
    }

    private void placeOrder(CoreMessage message) {
        var command = TradingCommandCodec.decodePlaceOrder(message.payload());
        TradingCoreState before = tradingState;
        TradingCoreState reserved = tradingReducer.placeOrder(before, message.header().userId(), command);
        try {
            var matchingResult = matchingAdapter.place(message.header().userId(), command);
            if (!matchingResult.accepted()) {
                throw new CoreStateRejectedException("MATCHING_REJECTED", matchingResult.resultCode());
            }
            tradingState = tradingReducer.applyMatches(reserved, command.orderId(),
                    command.baseAsset(), command.quoteAsset(), matchingResult.matches());
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
        var order = before.order(command.orderId());
        if (order == null) {
            throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        }
        TradingCoreState prepared = tradingReducer.prepareReplace(before, message.header().userId(), command);
        try {
            var matchingResult = matchingAdapter.replace(message.header().userId(), command.orderId(),
                    order.symbol(), command.newPriceTicks());
            if (!matchingResult.accepted()) {
                throw new CoreStateRejectedException("MATCHING_REJECTED", matchingResult.resultCode());
            }
            tradingState = tradingReducer.applyMatches(prepared, command.orderId(),
                    command.baseAsset(), command.quoteAsset(), matchingResult.matches());
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
        var view = new CoreUserStateView(user.productLine(), user.userId(), user.revision(),
                user.balances().values().stream().map(value -> new CoreBalanceView(
                        value.asset(), value.availableUnits(), value.lockedUnits())).toList(),
                user.reservations().values().stream().map(value -> new CoreReservationView(
                        value.orderId(), value.symbol(), value.instrumentVersion(), value.kind(), value.asset(),
                        value.reservedUnits(),
                        value.releasedUnits(), value.consumedUnits(), value.orderQuantitySteps())).toList(),
                user.positions().values().stream().map(value -> new CorePositionView(
                        value.symbol(), value.marginAsset(), value.instrumentVersion(), value.signedQuantitySteps(),
                        value.entryPriceTicks(), value.entryValueTicks(), value.realizedPnlUnits(),
                        value.positionMarginUnits())).toList());
        return new CoreResponse(ResponseStatus.OK, appliedCommandCount, tradingState.userStateHash(userId),
                CoreStateQueryCodec.encodeUserState(view));
    }

    private CoreResponse orderStateResponse(long orderId) {
        var order = tradingState.order(orderId);
        if (order == null) {
            return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                    CoreResultCode.ENTITY_NOT_FOUND, appliedCommandCount, tradingState.businessStateHash());
        }
        var view = new CoreOrderStateView(order.orderId(), order.productLine(), order.userId(), order.symbol(),
                order.instrumentVersion(), order.side(), order.priceTicks(), order.quantitySteps(),
                order.executedQuantitySteps(),
                order.remainingQuantitySteps(), order.reduceOnly(), order.status().name(), order.revision());
        return new CoreResponse(ResponseStatus.OK, appliedCommandCount, tradingState.orderStateHash(orderId),
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
