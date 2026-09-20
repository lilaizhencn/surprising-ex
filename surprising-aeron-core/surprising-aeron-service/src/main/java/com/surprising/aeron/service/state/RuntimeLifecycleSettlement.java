package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.account.BalanceRuntime;
import com.surprising.aeron.service.state.instrument.CoreInstrument;

import com.surprising.aeron.service.business.ProductTradingRules;
import com.surprising.aeron.service.business.ProductTradingRulesRegistry;

import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.index.ActiveOrderIndex;

import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CoreOrderStatus;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

public final class RuntimeLifecycleSettlement {

    private RuntimeLifecycleSettlement() {
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
        CoreInstrument instrument = requireInstrument(runtime, command);
        int symbolId = identities.symbolId(instrument.symbol());
        long previousSettlement = runtime.treasury().lifecycleSettlement(symbolId);
        if (command.settlementId() < previousSettlement) {
            throw new CoreStateRejectedException("STALE_SETTLEMENT_ID", "lifecycle settlement id must increase");
        }
        if (command.settlementId() == previousSettlement) {
            return new CoreSettlementProgressView(command.settlementId(), true, true, 0, 0, 0, 0);
        }
        ProductTradingRules kernel = ProductTradingRulesRegistry.forInstrument(instrument);
        kernel.validateLifecycleSettlement(instrument, command);
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
                                instrument, command.settlementPriceTicks(),
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
        List<Long> selectedUserIds = userPage.userIds();
        boolean moreUsers = chunked && !userPage.complete();
        int assetId = identities.assetId(instrument.settleAsset());
        LongArrayList[] usersByLane = groupUsers(selectedUserIds, runtime);
        long userLaneMask = laneMask(usersByLane);
        Object[] prepared = runtime.executeLifecycleSettlements(userLaneMask, selectedUserIds.size(),
                lane -> prepareLane(runtime, instrument, kernel, command, usersByLane[lane], symbolId, assetId));
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
                    command.settlementId(), instrument, command.settlementPriceTicks(),
                    command.optionCashUnitsPerContract(), true, 0, 0, command.cursorUserId(),
                    progressId, requiredInsurance));
            runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
            return new CoreSettlementProgressView(command.settlementId(), false, true, 0,
                    command.cursorUserId(), selectedOrders.size(), 0, requiredInsurance);
        }
        if (requiredInsurance != 0) runtime.treasury().setInsurance(assetId,
                Math.subtractExact(runtime.treasury().insurance(assetId), requiredInsurance),
                runtime.treasury().insuranceDeficit(assetId));
        Object[] laneResults = runtime.executeLifecycleSettlements(userLaneMask, selectedUserIds.size(),
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
                            instrument, command.settlementPriceTicks(),
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
        CoreInstrument instrument = requireInstrument(runtime, command);
        ProductTradingRulesRegistry.forInstrument(instrument)
                .validateLifecycleSettlement(instrument, command);
        int symbolId = identities.symbolId(instrument.symbol());
        TreasuryRuntime.LifecycleProgressRuntime progress = runtime.treasury().lifecycleProgress(symbolId);
        validateProgress(progress, command, true);
        cancelOrders(runtime, orders);
        runtime.treasury().setLifecycleProgress(symbolId,
                new TreasuryRuntime.LifecycleProgressRuntime(command.settlementId(), instrument,
                        command.settlementPriceTicks(), command.optionCashUnitsPerContract(), false,
                        progress == null ? 0 : progress.accountLaneId(), nextCursorOrderId, 0, chunkCommandId));
        runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(),
                orders == null || orders.isEmpty() ? 1 : 2));
    }

    // These plans cross the Lane -> insurance reservation -> Lane boundary. No account is
    // mutated until every plan in the bounded page is affordable.
    record UserSettlement(long userId, BalanceRuntime balance, long[] keys,
                          PositionRuntime[] positions, long clearing, long insurance) { }

    static List<UserSettlement> prepareLane(TradingRuntimeState runtime, CoreInstrument instrument,
                                            ProductTradingRules kernel, SettleInstrumentCommand command,
                                            LongArrayList users, int symbolId, int assetId) {
        ArrayList<UserSettlement> plans = new ArrayList<>();
        for (int userIndex = 0; userIndex < users.size(); userIndex++) {
            long userId = users.get(userIndex);
            if (runtime.user(userId) == null) continue;
            LongArrayList indexedKeys = runtime.positionKeysForUserAndSymbolPrimitive(userId, symbolId);
            if (indexedKeys.isEmpty()) continue;
            long[] keys = new long[indexedKeys.size()];
            PositionRuntime[] positions = new PositionRuntime[keys.length];
            BalanceRuntime balance = runtime.balance(userId, assetId);
            if (balance == null) throw new IllegalStateException("settlement balance is missing");
            long available = balance.availableUnits();
            long locked = balance.lockedUnits();
            long crossPnl = 0, crossMargin = 0, totalPnl = 0, insurance = 0;
            int index = 0;
            for (int positionIndex = 0; positionIndex < indexedKeys.size(); positionIndex++) {
                long key = indexedKeys.get(positionIndex);
                PositionRuntime position = runtime.position(key);
                if (position == null || position.signedQuantitySteps() == 0) continue;
                long pnl = kernel.lifecycleSettlementCashDeltaUnits(
                        instrument, position.signedQuantitySteps(), position.entryPriceTicks(),
                        command.settlementPriceTicks());
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
                        position.positionSide(), position.instrument(), 0, 0, 0,
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

    static RuntimeTreasuryDelta applyLane(TradingRuntimeState runtime, int assetId,
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

    static UserPage selectUsers(Iterable<Long> indexedUserIds, TradingRuntimeState runtime,
                                int startLaneId, long startCursorUserId, int limit) {
        // The command cursor is global, not Lane-local. Core selects this same order.
        var page = RuntimePerpetualFundingProcessor.selectUsers(indexedUserIds, startCursorUserId, limit);
        return new UserPage(page.userIds(), 0, page.nextCursorUserId(), page.complete());
    }

    static OrderPage selectOrders(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
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
        @SuppressWarnings("unchecked") ArrayList<CoreOrderState>[] ordersByLane =
                groupOrders(orders, runtime);
        long laneMask = laneMask(ordersByLane);
        runtime.executeOwnerSettlements(laneMask, orders.size(),
                lane -> { cancelOrdersOnLane(runtime, ordersByLane[lane]); return null; });
    }

    static void cancelOrdersOnLane(TradingRuntimeState runtime, Collection<CoreOrderState> orders) {
        if (orders == null || orders.isEmpty()) return;
        for (CoreOrderState order : orders) {
            if (!runtime.currentLaneOwns(order.userId())) continue;
            ReservationRuntime reservation = runtime.reservation(order.orderId());
            if (reservation == null) throw new IllegalStateException("settlement reservation is missing");
            runtime.cancelOrder(order.orderId(), order.userId(), reservation.reservedUnits());
        }
    }

    private static LongArrayList[] groupUsers(List<Long> userIds, TradingRuntimeState runtime) {
        LongArrayList[] groups = new LongArrayList[runtime.topology().accountLaneCount()];
        for (int lane = 0; lane < groups.length; lane++) groups[lane] = new LongArrayList();
        for (long userId : userIds) groups[runtime.topology().accountLaneId(userId)].add(userId);
        return groups;
    }

    static long laneMask(LongArrayList[] groups) {
        long mask = 0;
        for (int lane = 0; lane < groups.length; lane++) if (!groups[lane].isEmpty()) mask |= 1L << lane;
        return mask;
    }

    static long laneMask(ArrayList<CoreOrderState>[] groups) {
        long mask = 0;
        for (int lane = 0; lane < groups.length; lane++) {
            if (groups[lane] != null && !groups[lane].isEmpty()) mask |= 1L << lane;
        }
        return mask;
    }

    private static ArrayList<CoreOrderState>[] groupOrders(Collection<CoreOrderState> orders,
                                                            TradingRuntimeState runtime) {
        @SuppressWarnings("unchecked") ArrayList<CoreOrderState>[] groups =
                (ArrayList<CoreOrderState>[]) new ArrayList<?>[runtime.topology().accountLaneCount()];
        for (CoreOrderState order : orders) {
            if (order == null) continue;
            int lane = runtime.topology().accountLaneId(order.userId());
            ArrayList<CoreOrderState> group = groups[lane];
            if (group == null) groups[lane] = group = new ArrayList<>();
            group.add(order);
        }
        return groups;
    }

    static CoreInstrument requireInstrument(TradingRuntimeState runtime,
                                                 SettleInstrumentCommand command) {
        CoreInstrument instrument = runtime.instrument(command.symbol());
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        return instrument;
    }

    static void validateProgress(TreasuryRuntime.LifecycleProgressRuntime progress,
                                 SettleInstrumentCommand command, boolean chunked) {
        if (chunked && progress == null && (command.cursorUserId() != 0 || command.cursorOrderId() != 0)) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor must start at zero");
        }
        if (progress != null && (progress.settlementId() != command.settlementId()
                || progress.settlementPriceTicks() != command.settlementPriceTicks()
                || progress.optionCashUnitsPerContract() != command.optionCashUnitsPerContract()
                || progress.ordersComplete() != (command.cursorOrderId() == 0)
                || progress.nextCursorOrderId() != command.cursorOrderId()
                || progress.nextCursorUserId() != command.cursorUserId())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "settlement cursor does not match progress");
        }
    }

    record UserPage(List<Long> userIds, int accountLaneId,
                    long nextCursorUserId, boolean complete) {
    }

    record OrderPage(List<CoreOrderState> orders, int accountLaneId,
                     long nextCursorOrderId, boolean complete) {
    }
}
