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
        CoreInstrumentState instrument = requireInstrument(before, command);
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
            selectedOrders = openOrders(before, activeOrderIndex, instrument.symbol());
            cancelOrders(runtime, selectedOrders);
            ordersComplete = true;
        } else if (!ordersComplete) {
            ActiveOrderIndex.Page page = activeOrderIndex.page(
                    0, instrument.symbol(), command.cursorOrderId(), command.maxOrders());
            selectedOrders = page.orderIds().stream().map(before::order)
                    .filter(order -> order != null && order.status() == CoreOrderStatus.OPEN).toList();
            moreOrders = page.nextCursorOrderId() != 0;
            cancelOrders(runtime, selectedOrders);
            if (moreOrders) {
                long nextCursor = selectedOrders.getLast().orderId();
                runtime.treasury().setLifecycleProgress(symbolId,
                        new TreasuryRuntime.LifecycleProgressRuntime(command.settlementId(),
                                command.instrumentVersion(), command.settlementPriceTicks(),
                                command.optionCashUnitsPerContract(), false, nextCursor, 0, chunkCommandId));
                runtime.setMetadata(before.productLine(), Math.addExact(before.revision(),
                        selectedOrders.isEmpty() ? 1 : 2));
                return new CoreSettlementProgressView(command.settlementId(), false, false, nextCursor, 0,
                        selectedOrders.size(), 0);
            }
            ordersComplete = true;
        }
        ArrayList<Long> selectedUserIds = selectUsers(before, indexedUserIds, command, chunked);
        boolean moreUsers = chunked && hasMoreUsers(indexedUserIds, selectedUserIds, command.cursorUserId());
        for (long userId : selectedUserIds) {
            settleUser(before, runtime, identities, instrument, kernel, command, userId);
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
        runtime.setMetadata(before.productLine(), Math.addExact(before.revision(), selectedOrders.isEmpty() ? 1 : 2));
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
        CoreInstrumentState instrument = requireInstrument(before, command);
        validateSettlement(SettlementKernels.forInstrument(instrument), command);
        int symbolId = identities.symbolId(instrument.symbol());
        TreasuryRuntime.LifecycleProgressRuntime progress = runtime.treasury().lifecycleProgress(symbolId);
        validateProgress(progress, command, true);
        cancelOrders(runtime, orders);
        runtime.treasury().setLifecycleProgress(symbolId,
                new TreasuryRuntime.LifecycleProgressRuntime(command.settlementId(), command.instrumentVersion(),
                        command.settlementPriceTicks(), command.optionCashUnitsPerContract(), false,
                        nextCursorOrderId, 0, chunkCommandId));
        runtime.setMetadata(before.productLine(), Math.addExact(before.revision(),
                orders == null || orders.isEmpty() ? 1 : 2));
    }

    private static void settleUser(TradingCoreState before, TradingRuntimeState runtime,
                                   RuntimeIdentityRegistry identities, CoreInstrumentState instrument,
                                   SettlementKernel kernel, SettleInstrumentCommand command, long userId) {
        CoreUserState referenceUser = before.user(userId);
        if (referenceUser == null) return;
        List<CorePositionState> positions = referenceUser.positions().values().stream()
                .filter(position -> position.symbol().equals(instrument.symbol())
                        && position.signedQuantitySteps() != 0).toList();
        if (positions.isEmpty()) return;
        int assetId = identities.assetId(instrument.settleAsset());
        BalanceRuntime balance = runtime.balance(userId, assetId);
        if (balance == null) throw new IllegalStateException("settlement balance is missing");
        long available = balance.availableUnits();
        long locked = balance.lockedUnits();
        long clearingPnl = runtime.treasury().clearingPnl(assetId);
        long deficit = runtime.treasury().insuranceDeficit(assetId);
        for (CorePositionState position : positions) {
            long cashDelta = kernel.lifecycleCashDeltaUnits(instrument, position.signedQuantitySteps(),
                    position.entryPriceTicks(), command.settlementPriceTicks());
            Cash cash = applyCash(available, locked, position.marginMode(),
                    position.positionMarginUnits(), cashDelta);
            available = cash.available();
            locked = cash.locked();
            clearingPnl = Math.addExact(clearingPnl, Math.negateExact(cash.appliedDelta()));
            long positionKey = identities.positionKey(userId, position.key());
            PositionRuntime current = runtime.position(positionKey);
            runtime.replacePosition(positionKey, new PositionRuntime(userId, identities.symbolId(position.symbol()),
                    assetId, position.marginMode(), position.positionSide(), 0, 0, 0, 0,
                    Math.addExact(current == null ? position.realizedPnlUnits() : current.realizedPnlUnits(), cashDelta),
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

    private static ArrayList<Long> selectUsers(TradingCoreState before, Iterable<Long> indexedUserIds,
                                               SettleInstrumentCommand command, boolean chunked) {
        ArrayList<Long> selected = new ArrayList<>();
        Iterable<Long> source = indexedUserIds == null ? before.users().keySet() : indexedUserIds;
        for (Long userId : source) {
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

    private static List<CoreOrderState> openOrders(TradingCoreState before, ActiveOrderIndex index, String symbol) {
        return index == null
                ? before.orders().values().stream()
                .filter(order -> order.status() == CoreOrderStatus.OPEN && order.symbol().equals(symbol)).toList()
                : index.ids(symbol).stream().map(before::order).filter(java.util.Objects::nonNull).toList();
    }

    private static void cancelOrders(TradingRuntimeState runtime, Collection<CoreOrderState> orders) {
        if (orders == null) return;
        for (CoreOrderState order : orders) {
            ReservationRuntime reservation = runtime.reservation(order.orderId());
            if (reservation == null) throw new IllegalStateException("settlement reservation is missing");
            runtime.cancelOrder(order.orderId(), order.userId(), reservation.reservedUnits());
        }
    }

    private static CoreInstrumentState requireInstrument(TradingCoreState before, SettleInstrumentCommand command) {
        CoreInstrumentState instrument = before.instruments().get(OrderReservation.normalizeSymbol(command.symbol()));
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
