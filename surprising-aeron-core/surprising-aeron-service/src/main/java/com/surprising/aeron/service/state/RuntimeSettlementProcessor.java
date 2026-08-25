package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class RuntimeSettlementProcessor {

    private RuntimeSettlementProcessor() {
    }

    public static CoreSettlementProgressView apply(TradingCoreState before, SettleInstrumentCommand command,
                                                   Iterable<Long> indexedUserIds, UUID chunkCommandId,
                                                   ActiveOrderIndex activeOrderIndex, TradingRuntimeState runtime,
                                                   RuntimeIdentityRegistry identities) {
        if (before == null || command == null || runtime == null || identities == null) {
            throw new IllegalArgumentException("invalid runtime settlement");
        }
        Iterable<Long> users = indexedUserIds == null ? before.users().keySet() : indexedUserIds;
        ActiveOrderIndex orders = activeOrderIndex == null ? new ActiveOrderIndex(before) : activeOrderIndex;
        return applyRuntime(command, users, chunkCommandId, orders, runtime, identities);
    }

    public static CoreSettlementProgressView applyRuntime(SettleInstrumentCommand command,
                                                          Iterable<Long> indexedUserIds, UUID chunkCommandId,
                                                          ActiveOrderIndex activeOrderIndex,
                                                          TradingRuntimeState runtime,
                                                          RuntimeIdentityRegistry identities) {
        if (command == null || indexedUserIds == null || activeOrderIndex == null
                || runtime == null || identities == null) {
            throw new IllegalArgumentException("invalid runtime settlement");
        }
        CoreInstrumentState instrument = requireInstrument(runtime, command);
        int symbolId = identities.symbolId(instrument.symbol());
        long previousSettlement = runtime.treasury().lifecycleSettlement(symbolId);
        if (command.settlementId() < previousSettlement) {
            throw new CoreStateRejectedException("STALE_SETTLEMENT_ID", "lifecycle settlement id must increase");
        }
        if (command.settlementId() == previousSettlement) {
            return new CoreSettlementProgressView(command.settlementId(), true, true, 0, 0, 0, 0);
        }
        SettlementKernel kernel = SettlementKernels.forInstrument(instrument);
        validateSettlement(kernel, command);
        TreasuryRuntime.LifecycleProgressRuntime previousProgress = runtime.treasury().lifecycleProgress(symbolId);
        boolean chunked = indexedUserIds != null && chunkCommandId != null;
        validateProgress(previousProgress, command, chunked);
        boolean ordersComplete = !chunked || previousProgress != null && previousProgress.ordersComplete();
        List<CoreOrderState> selectedOrders = List.of();
        boolean moreOrders = false;
        if (!chunked) {
            selectedOrders = openOrders(runtime, identities, activeOrderIndex, instrument.symbol());
            cancelOrders(runtime, selectedOrders);
            ordersComplete = true;
        } else if (!ordersComplete) {
            ActiveOrderIndex.Page page = activeOrderIndex.page(
                    0, instrument.symbol(), command.cursorOrderId(), command.maxOrders());
            selectedOrders = page.orderIds().stream().map(runtime::order)
                    .filter(order -> order != null && !order.canceled())
                    .map(order -> RuntimeStateMaterializer.orderSnapshot(order, identities)).toList();
            moreOrders = page.nextCursorOrderId() != 0;
            cancelOrders(runtime, selectedOrders);
            if (moreOrders) {
                long nextCursor = selectedOrders.getLast().orderId();
                runtime.treasury().setLifecycleProgress(symbolId,
                        new TreasuryRuntime.LifecycleProgressRuntime(command.settlementId(),
                                command.instrumentVersion(), command.settlementPriceTicks(),
                                command.optionCashUnitsPerContract(), false, nextCursor, 0, chunkCommandId));
                runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(),
                        selectedOrders.isEmpty() ? 1 : 2));
                return new CoreSettlementProgressView(command.settlementId(), false, false, nextCursor, 0,
                        selectedOrders.size(), 0);
            }
            ordersComplete = true;
        }
        ArrayList<Long> selectedUserIds = selectUsers(indexedUserIds, command, chunked);
        boolean moreUsers = chunked && hasMoreUsers(indexedUserIds, selectedUserIds, command.cursorUserId());
        for (long userId : selectedUserIds) {
            settleUser(runtime, identities, instrument, kernel, command, userId);
        }
        boolean complete = !chunked || !moreUsers;
        long nextCursorUserId = complete ? 0 : selectedUserIds.getLast();
        if (complete) {
            runtime.treasury().setLifecycleSettlement(symbolId, command.settlementId());
        } else {
            runtime.treasury().setLifecycleProgress(symbolId,
                    new TreasuryRuntime.LifecycleProgressRuntime(command.settlementId(),
                            command.instrumentVersion(), command.settlementPriceTicks(),
                            command.optionCashUnitsPerContract(), true, 0, nextCursorUserId, chunkCommandId));
        }
        runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(),
                selectedOrders.isEmpty() ? 1 : 2));
        return new CoreSettlementProgressView(command.settlementId(), complete, ordersComplete, 0,
                nextCursorUserId, selectedOrders.size(), selectedUserIds.size());
    }

    public static void advanceCancellation(TradingCoreState before, SettleInstrumentCommand command,
                                           Collection<CoreOrderState> orders, long nextCursorOrderId,
                                           UUID chunkCommandId, TradingRuntimeState runtime,
                                           RuntimeIdentityRegistry identities) {
        if (nextCursorOrderId <= 0 || chunkCommandId == null) {
            throw new IllegalArgumentException("settlement cursor must advance");
        }
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid runtime settlement cancellation");
        }
        advanceCancellationRuntime(command, orders, nextCursorOrderId, chunkCommandId, runtime, identities);
    }

    public static void advanceCancellationRuntime(SettleInstrumentCommand command,
                                                  Collection<CoreOrderState> orders, long nextCursorOrderId,
                                                  UUID chunkCommandId, TradingRuntimeState runtime,
                                                  RuntimeIdentityRegistry identities) {
        if (nextCursorOrderId <= 0 || chunkCommandId == null || runtime == null || identities == null) {
            throw new IllegalArgumentException("settlement cursor must advance");
        }
        CoreInstrumentState instrument = requireInstrument(runtime, command);
        validateSettlement(SettlementKernels.forInstrument(instrument), command);
        int symbolId = identities.symbolId(instrument.symbol());
        TreasuryRuntime.LifecycleProgressRuntime progress = runtime.treasury().lifecycleProgress(symbolId);
        validateProgress(progress, command, true);
        cancelOrders(runtime, orders);
        runtime.treasury().setLifecycleProgress(symbolId,
                new TreasuryRuntime.LifecycleProgressRuntime(command.settlementId(), command.instrumentVersion(),
                        command.settlementPriceTicks(), command.optionCashUnitsPerContract(), false,
                        nextCursorOrderId, 0, chunkCommandId));
        runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(),
                orders == null || orders.isEmpty() ? 1 : 2));
    }

    private static void settleUser(TradingRuntimeState runtime,
                                   RuntimeIdentityRegistry identities, CoreInstrumentState instrument,
                                   SettlementKernel kernel, SettleInstrumentCommand command, long userId) {
        if (runtime.user(userId) == null) return;
        int symbolId = identities.symbolId(instrument.symbol());
        ArrayList<PositionRuntime> positions = new ArrayList<>();
        for (long positionKey : runtime.positionKeysForUserAndSymbol(userId, symbolId)) {
            PositionRuntime position = runtime.position(positionKey);
            if (position != null && position.signedQuantitySteps() != 0) positions.add(position);
        }
        if (positions.isEmpty()) return;
        int assetId = identities.assetId(instrument.settleAsset());
        BalanceRuntime balance = runtime.balance(userId, assetId);
        if (balance == null) throw new IllegalStateException("settlement balance is missing");
        long available = balance.availableUnits();
        long locked = balance.lockedUnits();
        long clearingPnl = runtime.treasury().clearingPnl(assetId);
        long deficit = runtime.treasury().insuranceDeficit(assetId);
        for (PositionRuntime position : positions) {
            long cashDelta = kernel.lifecycleCashDeltaUnits(instrument, position.signedQuantitySteps(),
                    position.entryPriceTicks(), command.settlementPriceTicks());
            Cash cash = applyCash(available, locked, position.marginMode(),
                    position.positionMarginUnits(), cashDelta);
            available = cash.available();
            locked = cash.locked();
            clearingPnl = Math.addExact(clearingPnl, Math.negateExact(cash.appliedDelta()));
            String positionName = position.positionSide() == com.surprising.aeron.protocol.CorePositionSide.NET
                    ? instrument.symbol() : instrument.symbol() + ':' + position.positionSide().name();
            long positionKey = identities.positionKey(userId, positionName);
            runtime.replacePosition(positionKey, new PositionRuntime(userId, position.symbolId(),
                    assetId, position.marginMode(), position.positionSide(), 0, 0, 0, 0,
                    Math.addExact(position.realizedPnlUnits(), cashDelta),
                    0));
        }
        runtime.replaceBalance(new BalanceRuntime(userId, assetId, available, locked));
        runtime.treasury().setClearingPnl(assetId, clearingPnl);
        runtime.treasury().setDeficit(assetId, deficit);
        runtime.advanceUserRevision(userId);
    }

    private static Cash applyCash(long available, long locked, CoreMarginMode marginMode,
                                  long releasedMargin, long pnl) {
        long applied;
        if (marginMode == CoreMarginMode.ISOLATED) {
            if (pnl < 0) {
                long consumed = Math.min(releasedMargin, Math.negateExact(pnl));
                long remaining = Math.subtractExact(releasedMargin, consumed);
                locked = Math.subtractExact(locked, releasedMargin);
                available = Math.addExact(available, remaining);
                applied = Math.negateExact(consumed);
            } else {
                locked = Math.subtractExact(locked, releasedMargin);
                available = Math.addExact(available, Math.addExact(releasedMargin, pnl));
                applied = pnl;
            }
        } else {
            locked = Math.subtractExact(locked, releasedMargin);
            available = Math.addExact(available, releasedMargin);
            if (pnl >= 0) {
                available = Math.addExact(available, pnl);
                applied = pnl;
            } else {
                long debit = Math.min(available, Math.negateExact(pnl));
                available = Math.subtractExact(available, debit);
                applied = Math.negateExact(debit);
            }
        }
        return new Cash(available, locked, applied);
    }

    private static ArrayList<Long> selectUsers(Iterable<Long> indexedUserIds,
                                               SettleInstrumentCommand command, boolean chunked) {
        ArrayList<Long> selected = new ArrayList<>();
        for (Long userId : indexedUserIds) {
            if (userId == null || chunked && userId <= command.cursorUserId()) continue;
            if (!chunked || selected.size() < command.maxUsers()) selected.add(userId);
            else break;
        }
        return selected;
    }

    private static boolean hasMoreUsers(Iterable<Long> indexedUserIds, List<Long> selected, long cursor) {
        if (indexedUserIds == null) return false;
        long last = selected.isEmpty() ? cursor : selected.getLast();
        for (Long userId : indexedUserIds) if (userId != null && userId > last) return true;
        return false;
    }

    private static List<CoreOrderState> openOrders(TradingRuntimeState runtime,
                                                   RuntimeIdentityRegistry identities,
                                                   ActiveOrderIndex index, String symbol) {
        return index.ids(symbol).stream().map(runtime::order)
                .filter(order -> order != null && !order.canceled())
                .map(order -> RuntimeStateMaterializer.orderSnapshot(order, identities)).toList();
    }

    private static void cancelOrders(TradingRuntimeState runtime, Collection<CoreOrderState> orders) {
        if (orders == null) return;
        for (CoreOrderState order : orders) {
            ReservationRuntime reservation = runtime.reservation(order.orderId());
            if (reservation == null) throw new IllegalStateException("settlement reservation is missing");
            runtime.cancelOrder(order.orderId(), order.userId(), reservation.reservedUnits());
        }
    }

    private static CoreInstrumentState requireInstrument(TradingRuntimeState runtime,
                                                         SettleInstrumentCommand command) {
        CoreInstrumentState instrument = runtime.instrument(command.symbol());
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        if (command.instrumentVersion() < instrument.version()) {
            throw new CoreStateRejectedException("INSTRUMENT_VERSION_CONFLICT",
                    "instrument lifecycle version precedes execution version");
        }
        return instrument;
    }

    private static void validateSettlement(SettlementKernel kernel, SettleInstrumentCommand command) {
        switch (kernel.productLine()) {
            case LINEAR_DELIVERY, INVERSE_DELIVERY, OPTION -> { }
            case SPOT, LINEAR_PERPETUAL, INVERSE_PERPETUAL -> throw new CoreStateRejectedException(
                    "PRODUCT_LINE_UNSUPPORTED", "instrument settlement requires delivery or option product");
        }
        if (command.settlementPriceTicks() <= 0) {
            throw new CoreStateRejectedException("INVALID_SETTLEMENT_PRICE", "delivery price must be positive");
        }
    }

    private static void validateProgress(TreasuryRuntime.LifecycleProgressRuntime progress,
                                         SettleInstrumentCommand command, boolean chunked) {
        if (!chunked) return;
        if (progress == null && (command.cursorUserId() != 0 || command.cursorOrderId() != 0)) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor must start at zero");
        }
        if (progress != null && (progress.settlementId() != command.settlementId()
                || progress.instrumentVersion() != command.instrumentVersion()
                || progress.settlementPriceTicks() != command.settlementPriceTicks()
                || progress.optionCashUnitsPerContract() != command.optionCashUnitsPerContract()
                || progress.ordersComplete() != (command.cursorOrderId() == 0)
                || progress.nextCursorOrderId() != command.cursorOrderId()
                || progress.nextCursorUserId() != command.cursorUserId())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor does not match progress");
        }
    }

    private record Cash(long available, long locked, long appliedDelta) {
    }
}
