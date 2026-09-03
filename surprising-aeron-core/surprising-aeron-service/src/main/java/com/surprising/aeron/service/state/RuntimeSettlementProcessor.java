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
            int accountLaneId = previousProgress == null ? 0 : previousProgress.accountLaneId();
            OrderPage page = selectOrders(runtime, identities, activeOrderIndex, instrument.symbol(),
                    accountLaneId, command.cursorOrderId(), command.maxOrders());
            selectedOrders = page.orders();
            moreOrders = !page.complete();
            cancelOrders(runtime, selectedOrders);
            if (moreOrders) {
                long nextCursor = page.nextCursorOrderId();
                runtime.treasury().setLifecycleProgress(symbolId,
                        new TreasuryRuntime.LifecycleProgressRuntime(command.settlementId(),
                                command.instrumentVersion(), command.settlementPriceTicks(),
                                command.optionCashUnitsPerContract(), false, page.accountLaneId(),
                                nextCursor, 0, chunkCommandId));
                runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(),
                        selectedOrders.isEmpty() ? 1 : 2));
                return new CoreSettlementProgressView(command.settlementId(), false, false, nextCursor, 0,
                        selectedOrders.size(), 0);
            }
            ordersComplete = true;
        }
        int accountLaneId = previousProgress == null || !previousProgress.ordersComplete()
                ? 0 : previousProgress.accountLaneId();
        UserPage userPage = selectUsers(indexedUserIds, runtime, accountLaneId,
                command.cursorUserId(), chunked ? command.maxUsers() : Integer.MAX_VALUE);
        ArrayList<Long> selectedUserIds = userPage.userIds();
        boolean moreUsers = chunked && !userPage.complete();
        int assetId = identities.assetId(instrument.settleAsset());
        Object[] laneResults = runtime.executeOwnerSettlements(selectedUserIds,
                ignored -> settleLane(runtime, instrument, kernel, command, selectedUserIds, symbolId, assetId));
        RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
        for (Object value : laneResults) {
            if (value instanceof RuntimeTreasuryDelta laneDelta) treasuryDelta.merge(laneDelta);
        }
        treasuryDelta.apply(runtime.treasury());
        boolean complete = !chunked || !moreUsers;
        long nextCursorUserId = complete ? 0 : selectedUserIds.getLast();
        if (complete) {
            runtime.treasury().setLifecycleSettlement(symbolId, command.settlementId());
        } else {
            runtime.treasury().setLifecycleProgress(symbolId,
                    new TreasuryRuntime.LifecycleProgressRuntime(command.settlementId(),
                            command.instrumentVersion(), command.settlementPriceTicks(),
                            command.optionCashUnitsPerContract(), true, userPage.accountLaneId(),
                            0, nextCursorUserId, chunkCommandId));
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
                        progress == null ? 0 : progress.accountLaneId(), nextCursorOrderId, 0, chunkCommandId));
        runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(),
                orders == null || orders.isEmpty() ? 1 : 2));
    }

    private static RuntimeTreasuryDelta settleLane(TradingRuntimeState runtime,
                                                   CoreInstrumentState instrument, SettlementKernel kernel,
                                                   SettleInstrumentCommand command,
                                                   Iterable<Long> selectedUserIds, int symbolId, int assetId) {
        RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
        for (Long userId : selectedUserIds) {
            if (userId != null && runtime.currentLaneOwns(userId)) {
                settleUser(runtime, instrument, kernel, command, userId,
                        symbolId, assetId, treasuryDelta);
            }
        }
        return treasuryDelta;
    }

    private static void settleUser(TradingRuntimeState runtime,
                                   CoreInstrumentState instrument,
                                   SettlementKernel kernel, SettleInstrumentCommand command, long userId,
                                   int symbolId, int assetId, RuntimeTreasuryDelta treasuryDelta) {
        if (runtime.user(userId) == null) return;
        java.util.NavigableSet<Long> positionKeys = runtime.positionKeysForUserAndSymbol(userId, symbolId);
        if (positionKeys.isEmpty()) return;
        BalanceRuntime balance = runtime.balance(userId, assetId);
        if (balance == null) throw new IllegalStateException("settlement balance is missing");
        long available = balance.availableUnits();
        long locked = balance.lockedUnits();
        long clearingPnlDelta = 0;
        boolean settled = false;
        for (long positionKey : positionKeys) {
            PositionRuntime position = runtime.position(positionKey);
            if (position == null || position.signedQuantitySteps() == 0) continue;
            settled = true;
            long cashDelta = kernel.lifecycleCashDeltaUnits(instrument, position.signedQuantitySteps(),
                    position.entryPriceTicks(), command.settlementPriceTicks());
            Cash cash = applyCash(available, locked, position.marginMode(),
                    position.positionMarginUnits(), cashDelta);
            available = cash.available();
            locked = cash.locked();
            clearingPnlDelta = Math.addExact(clearingPnlDelta, Math.negateExact(cash.appliedDelta()));
            runtime.replacePosition(positionKey, new PositionRuntime(userId, position.symbolId(),
                    assetId, position.marginMode(), position.positionSide(), 0, 0, 0, 0,
                    Math.addExact(position.realizedPnlUnits(), cashDelta),
                    0));
        }
        if (!settled) return;
        runtime.replaceBalance(new BalanceRuntime(userId, assetId, available, locked));
        treasuryDelta.addClearing(assetId, clearingPnlDelta);
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

    private static UserPage selectUsers(Iterable<Long> indexedUserIds, TradingRuntimeState runtime,
                                        int startLaneId, long startCursorUserId, int limit) {
        ArrayList<Long> selected = new ArrayList<>();
        int laneCount = runtime.topology().accountLaneCount();
        int laneId = startLaneId;
        long cursorUserId = startCursorUserId;
        int lastSelectedLaneId = startLaneId;
        while (laneId < laneCount) {
            for (Long userId : indexedUserIds) {
                if (userId == null || runtime.topology().accountLaneId(userId) != laneId
                        || userId <= cursorUserId) continue;
                if (selected.size() == limit) {
                    return new UserPage(selected, lastSelectedLaneId, selected.getLast(), false);
                }
                selected.add(userId);
                lastSelectedLaneId = laneId;
            }
            laneId++;
            cursorUserId = 0;
        }
        return new UserPage(selected, laneCount - 1, 0, true);
    }

    private static OrderPage selectOrders(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                          ActiveOrderIndex index, String symbol, int startLaneId,
                                          long startCursorOrderId, int limit) {
        ArrayList<CoreOrderState> selected = new ArrayList<>();
        int laneCount = runtime.topology().accountLaneCount();
        int laneId = startLaneId;
        long cursorOrderId = startCursorOrderId;
        int lastSelectedLaneId = startLaneId;
        while (laneId < laneCount) {
            for (long orderId : index.sortedIdsDescending(symbol)) {
                CoreOrderState order = runtime.order(orderId) == null ? null
                        : RuntimeStateMaterializer.orderSnapshot(runtime.order(orderId), identities);
                if (order == null || order.status() != CoreOrderStatus.OPEN
                        || runtime.topology().accountLaneId(order.userId()) != laneId
                        || orderId <= cursorOrderId) continue;
                if (selected.size() == limit) {
                    return new OrderPage(selected, lastSelectedLaneId, selected.getLast().orderId(), false);
                }
                selected.add(order);
                lastSelectedLaneId = laneId;
            }
            laneId++;
            cursorOrderId = 0;
        }
        return new OrderPage(selected, laneCount - 1, 0, true);
    }

    private static List<CoreOrderState> openOrders(TradingRuntimeState runtime,
                                                   RuntimeIdentityRegistry identities,
                                                   ActiveOrderIndex index, String symbol) {
        long[] orderIds = index.sortedIdsDescending(symbol);
        ArrayList<CoreOrderState> result = new ArrayList<>(orderIds.length);
        for (long orderId : orderIds) {
            OrderRuntime order = runtime.order(orderId);
            if (order != null && !order.canceled()) {
                result.add(RuntimeStateMaterializer.orderSnapshot(order, identities));
            }
        }
        return result.isEmpty() ? List.of() : List.copyOf(result);
    }

    private static void cancelOrders(TradingRuntimeState runtime, Collection<CoreOrderState> orders) {
        if (orders == null || orders.isEmpty()) return;
        runtime.executeOwnerSettlements(orders, CoreOrderState::userId, ignored -> {
            for (CoreOrderState order : orders) {
                if (!runtime.currentLaneOwns(order.userId())) continue;
                ReservationRuntime reservation = runtime.reservation(order.orderId());
                if (reservation == null) throw new IllegalStateException("settlement reservation is missing");
                runtime.cancelOrder(order.orderId(), order.userId(), reservation.reservedUnits());
            }
            return null;
        });
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

    private record UserPage(ArrayList<Long> userIds, int accountLaneId,
                            long nextCursorUserId, boolean complete) {
    }

    private record OrderPage(List<CoreOrderState> orders, int accountLaneId,
                             long nextCursorOrderId, boolean complete) {
    }
}
