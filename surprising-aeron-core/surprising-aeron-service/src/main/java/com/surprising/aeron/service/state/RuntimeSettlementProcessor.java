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
        ProductTradingRules kernel = ProductTradingRulesRegistry.forInstrument(instrument);
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
        Object[] prepared = runtime.executeLifecycleSettlements(selectedUserIds, Long::longValue,
                ignored -> prepareLane(runtime, instrument, kernel, command, selectedUserIds, symbolId, assetId));
        @SuppressWarnings("unchecked")
        List<UserSettlement>[] plans = new List[runtime.topology().accountLaneCount()];
        long requiredInsurance = 0;
        for (int lane = 0; lane < plans.length; lane++) {
            @SuppressWarnings("unchecked")
            List<UserSettlement> lanePlans = (List<UserSettlement>) prepared[lane];
            plans[lane] = lanePlans;
            if (lanePlans != null) for (var plan : lanePlans)
                requiredInsurance = Math.addExact(requiredInsurance, plan.insurance());
        }
        if (requiredInsurance > runtime.treasury().insurance(assetId)) {
            UUID progressId = chunkCommandId == null ? new UUID(0, command.settlementId()) : chunkCommandId;
            runtime.treasury().setLifecycleProgress(symbolId, new TreasuryRuntime.LifecycleProgressRuntime(
                    command.settlementId(), command.instrumentVersion(), command.settlementPriceTicks(),
                    command.optionCashUnitsPerContract(), true, 0, 0, command.cursorUserId(),
                    progressId, requiredInsurance));
            runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
            return new CoreSettlementProgressView(command.settlementId(), false, true, 0,
                    command.cursorUserId(), selectedOrders.size(), 0, requiredInsurance);
        }
        if (requiredInsurance != 0) runtime.treasury().setInsurance(assetId,
                Math.subtractExact(runtime.treasury().insurance(assetId), requiredInsurance),
                runtime.treasury().insuranceDeficit(assetId));
        Object[] laneResults = runtime.executeLifecycleSettlements(selectedUserIds, Long::longValue,
                lane -> applyLane(runtime, assetId, plans[lane]));
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
        validateSettlement(ProductTradingRulesRegistry.forInstrument(instrument), command);
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

    // These plans cross the Lane -> insurance reservation -> Lane boundary. No account is
    // mutated until every plan in the bounded page is affordable.
    private record UserSettlement(long userId, BalanceRuntime balance, long[] keys,
                                  PositionRuntime[] positions, long clearing, long insurance) { }

    private static List<UserSettlement> prepareLane(TradingRuntimeState runtime, CoreInstrumentState instrument,
                                                    ProductTradingRules kernel, SettleInstrumentCommand command,
                                                    Iterable<Long> users, int symbolId, int assetId) {
        ArrayList<UserSettlement> plans = new ArrayList<>();
        for (long userId : users) {
            if (!runtime.currentLaneOwns(userId) || runtime.user(userId) == null) continue;
            var indexedKeys = runtime.positionKeysForUserAndSymbol(userId, symbolId);
            if (indexedKeys.isEmpty()) continue;
            long[] keys = new long[indexedKeys.size()];
            PositionRuntime[] positions = new PositionRuntime[keys.length];
            BalanceRuntime balance = runtime.balance(userId, assetId);
            if (balance == null) throw new IllegalStateException("settlement balance is missing");
            long available = balance.availableUnits();
            long locked = balance.lockedUnits();
            long crossPnl = 0, crossMargin = 0, totalPnl = 0, insurance = 0;
            int index = 0;
            for (long key : indexedKeys) {
                PositionRuntime position = runtime.position(key);
                if (position == null || position.signedQuantitySteps() == 0) continue;
                long pnl = kernel.lifecycleCashDeltaUnits(instrument, position.signedQuantitySteps(),
                        position.entryPriceTicks(), command.settlementPriceTicks());
                totalPnl = Math.addExact(totalPnl, pnl);
                long margin = position.positionMarginUnits();
                locked = Math.subtractExact(locked, margin);
                if (position.marginMode() == CoreMarginMode.CROSS) {
                    crossPnl = Math.addExact(crossPnl, pnl);
                    crossMargin = Math.addExact(crossMargin, margin);
                } else {
                    long equity = Math.addExact(margin, pnl);
                    available = Math.addExact(available, Math.max(0, equity));
                    if (equity < 0) insurance = Math.addExact(insurance, Math.negateExact(equity));
                }
                keys[index] = key;
                positions[index++] = new PositionRuntime(userId, symbolId, assetId, position.marginMode(),
                        position.positionSide(), 0, 0, 0, 0,
                        Math.addExact(position.realizedPnlUnits(), pnl), 0);
            }
            if (index == 0) continue;
            long equity = Math.addExact(Math.addExact(available, crossMargin), crossPnl);
            if (equity < 0) insurance = Math.addExact(insurance, Math.negateExact(equity));
            Math.incrementExact(runtime.user(userId).revision());
            plans.add(new UserSettlement(userId,
                    new BalanceRuntime(userId, assetId, Math.max(0, equity), locked),
                    keys, positions, Math.negateExact(totalPnl), insurance));
        }
        return plans;
    }

    private static RuntimeTreasuryDelta applyLane(TradingRuntimeState runtime, int assetId,
                                                  List<UserSettlement> plans) {
        RuntimeTreasuryDelta delta = new RuntimeTreasuryDelta();
        for (UserSettlement plan : plans) {
            for (int index = 0; index < plan.keys().length; index++) {
                if (plan.positions()[index] != null)
                    runtime.replacePosition(plan.keys()[index], plan.positions()[index]);
            }
            runtime.replaceBalance(plan.balance());
            runtime.advanceUserRevision(plan.userId());
            delta.addClearing(assetId, plan.clearing());
        }
        return delta;
    }

    private static UserPage selectUsers(Iterable<Long> indexedUserIds, TradingRuntimeState runtime,
                                        int startLaneId, long startCursorUserId, int limit) {
        // The command cursor is global, not Lane-local. Core selects this same order.
        var page = RuntimePerpetualFundingProcessor.selectUsers(indexedUserIds, startCursorUserId, limit);
        return new UserPage(page.userIds(), 0, page.nextCursorUserId(), page.complete());
    }

    private static OrderPage selectOrders(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                          ActiveOrderIndex index, String symbol, int startLaneId,
                                          long startCursorOrderId, int limit) {
        var page = index.page(0, symbol, startCursorOrderId, limit);
        ArrayList<CoreOrderState> selected = new ArrayList<>(page.orderIds().size());
        for (long orderId : page.orderIds()) {
            OrderRuntime order = runtime.order(orderId);
            if (order == null || order.status() != CoreOrderStatus.OPEN) {
                throw new IllegalStateException("settlement active order index differs from runtime");
            }
            selected.add(RuntimeStateMaterializer.orderSnapshot(order, identities));
        }
        return new OrderPage(selected, 0, page.nextCursorOrderId(), page.nextCursorOrderId() == 0);
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

    private static void validateSettlement(ProductTradingRules kernel, SettleInstrumentCommand command) {
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
        if (chunked && progress == null && (command.cursorUserId() != 0 || command.cursorOrderId() != 0)) {
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

    private record UserPage(ArrayList<Long> userIds, int accountLaneId,
                            long nextCursorUserId, boolean complete) {
    }

    private record OrderPage(List<CoreOrderState> orders, int accountLaneId,
                             long nextCursorOrderId, boolean complete) {
    }
}
