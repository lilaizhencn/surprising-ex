package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.AdjustInsuranceFundCommand;
import com.surprising.aeron.protocol.AdjustPositionMarginCommand;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.protocol.CoreCancelAllAfterCommand;
import com.surprising.aeron.protocol.CoreCancelAllAfterStatus;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.protocol.CoreTriggerOrderType;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.UpdateRiskScanControlCommand;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.protocol.UpdatePositionModeCommand;
import com.surprising.aeron.protocol.UpdateLeverageCommand;
import java.math.BigInteger;
import java.util.UUID;
import java.util.ArrayList;

public final class RuntimeCommandProcessor {

    private RuntimeCommandProcessor() {
    }

    public static void adjustBalance(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                     long userId, BalanceAdjustmentCommand command) {
        if (runtime == null || identities == null || command == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime balance adjustment");
        }
        runtime.assertOwner();
        String asset = AssetBalance.normalizeAsset(command.asset());
        int assetId = identities.assetId(asset);
        BalanceRuntime current = runtime.balance(userId, assetId);
        long currentAvailable = current == null ? 0 : current.availableUnits();
        long nextAvailable = Math.addExact(currentAvailable, command.deltaUnits());
        if (nextAvailable < 0) {
            throw new IllegalArgumentException("available balance cannot be negative");
        }

        UserRuntime user = runtime.user(userId);
        if (user == null) {
            runtime.putUser(new UserRuntime(runtime.productLine(), userId, 1,
                    com.surprising.aeron.protocol.CorePositionMode.ONE_WAY));
        } else {
            runtime.advanceUserRevision(userId);
        }
        if (current == null) {
            runtime.putBalance(new BalanceRuntime(userId, assetId, nextAvailable, 0));
        } else {
            runtime.replaceBalance(new BalanceRuntime(userId, assetId, nextAvailable, current.lockedUnits()));
        }
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
    }

    public static void updateRiskScanControl(TradingRuntimeState runtime, UpdateRiskScanControlCommand command,
                                             long updatedAtEpochMillis) {
        if (runtime == null || command == null) {
            throw new IllegalArgumentException("invalid runtime risk scan control update");
        }
        runtime.assertOwner();
        CoreRiskScanControlView current = runtime.riskScanControl();
        if (command.expectedVersion() != current.version()) {
            throw new CoreStateRejectedException("STALE_RISK_SCAN_CONTROL_VERSION",
                    "risk scan control version does not match");
        }
        runtime.setRiskScanControl(new CoreRiskScanControlView(
                Math.incrementExact(current.version()), command.ruleName(), command.enabled(),
                command.scanDelayMs(), command.scanBatchSize(), command.adminUserId(), command.reason(),
                Math.max(0, updatedAtEpochMillis)));
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
    }

    public static void upsertInstrument(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                        UpsertInstrumentCommand command) {
        if (runtime == null || identities == null || command == null) {
            throw new IllegalArgumentException("invalid runtime instrument update");
        }
        runtime.assertOwner();
        CoreInstrumentState instrument = CoreInstrumentState.from(runtime.productLine(), command);
        CoreInstrumentState current = runtime.instrument(instrument.symbol());
        if (current != null && instrument.version() <= current.version()) {
            throw new CoreStateRejectedException("STALE_INSTRUMENT_VERSION", "instrument version must increase");
        }
        int symbolId = identities.symbolId(instrument.symbol());
        boolean[] openState = {false};
        runtime.ordersForSnapshot().forEachValue(order -> {
            if (order.symbolId() == symbolId && order.status() == CoreOrderStatus.OPEN) openState[0] = true;
        });
        runtime.positionsForSnapshot().forEachValue(position -> {
            if (position.symbolId() == symbolId && position.signedQuantitySteps() != 0) openState[0] = true;
        });
        if (current != null && openState[0]) {
            throw new CoreStateRejectedException("INSTRUMENT_VERSION_IN_USE",
                    "cannot replace instrument version with open state");
        }
        runtime.putInstrument(instrument);
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
    }

    public static boolean updatePositionMode(TradingRuntimeState runtime, long userId,
                                             UpdatePositionModeCommand command) {
        if (runtime == null || command == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime position mode update");
        }
        runtime.assertOwner();
        if (!runtime.productLine().isDerivative()) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "position mode requires derivative product line");
        }
        UserRuntime current = runtime.user(userId);
        com.surprising.aeron.protocol.CorePositionMode currentMode = current == null
                ? com.surprising.aeron.protocol.CorePositionMode.ONE_WAY : current.positionMode();
        if (currentMode == command.positionMode()) return false;
        boolean[] blocked = {false};
        runtime.positionsForSnapshot().forEachValue(position -> {
            if (position.userId() == userId && position.signedQuantitySteps() != 0) blocked[0] = true;
        });
        runtime.ordersForSnapshot().forEachValue(order -> {
            if (order.userId() == userId && order.status() == CoreOrderStatus.OPEN) blocked[0] = true;
        });
        runtime.reservationsForSnapshot().forEachValue(reservation -> {
            if (reservation.userId() == userId && reservation.reservedUnits() != 0) blocked[0] = true;
        });
        if (blocked[0]) {
            throw new CoreStateRejectedException("POSITION_MODE_SWITCH_BLOCKED",
                    "open positions or orders block position mode update");
        }
        runtime.putUser(new UserRuntime(runtime.productLine(), userId,
                current == null ? 1 : Math.incrementExact(current.revision()), command.positionMode()));
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
        return true;
    }

    public static boolean updateLeverage(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                         long userId, UpdateLeverageCommand command) {
        if (runtime == null || identities == null || command == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime leverage update");
        }
        runtime.assertOwner();
        if (!runtime.productLine().isDerivative()) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "leverage requires derivative product line");
        }
        CoreInstrumentState instrument = runtime.instrument(command.symbol());
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument does not exist");
        }
        long minimumRate = Math.max(instrument.initialMarginRatePpm(),
                CoreContractMath.riskBracket(instrument, 0).initialMarginRatePpm());
        if (initialMarginRateFromLeverage(command.leveragePpm()) < minimumRate) {
            throw new CoreStateRejectedException("LEVERAGE_EXCEEDS_INSTRUMENT_LIMIT",
                    "leverage exceeds instrument maximum");
        }
        int symbolId = identities.symbolId(instrument.symbol());
        boolean[] openState = {false};
        runtime.ordersForSnapshot().forEachValue(order -> {
            if (order.userId() == userId && order.symbolId() == symbolId
                    && order.marginMode() == command.marginMode() && order.status() == CoreOrderStatus.OPEN) {
                openState[0] = true;
            }
        });
        runtime.positionsForSnapshot().forEachValue(position -> {
            if (position.userId() == userId && position.symbolId() == symbolId
                    && position.marginMode() == command.marginMode() && position.signedQuantitySteps() != 0) {
                openState[0] = true;
            }
        });
        CoreLeverageKey key = new CoreLeverageKey(userId, instrument.symbol(), command.marginMode());
        Long current = runtime.leverage(key);
        if (openState[0] && (current == null || current.longValue() != command.leveragePpm())) {
            throw new CoreStateRejectedException("LEVERAGE_UPDATE_BLOCKED", "open orders or positions exist");
        }
        if (current != null && current.longValue() == command.leveragePpm()) return false;
        runtime.putLeverage(key, command.leveragePpm());
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
        return true;
    }

    public static void adjustPositionMargin(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                            long userId, AdjustPositionMarginCommand command) {
        if (runtime == null || identities == null || command == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime position margin adjustment");
        }
        runtime.assertOwner();
        if (command.marginMode() != com.surprising.aeron.protocol.CoreMarginMode.ISOLATED
                || command.amountUnits() == 0) {
            throw new CoreStateRejectedException("POSITION_MARGIN_ADJUSTMENT_INVALID",
                    "only isolated position margin can be adjusted");
        }
        String symbol = OrderReservation.normalizeSymbol(command.symbol());
        String positionIdentity = command.positionSide().hedgeSide()
                ? symbol + ':' + command.positionSide().name() : symbol;
        long positionKey = identities.positionKey(userId, positionIdentity);
        PositionRuntime position = runtime.position(positionKey);
        if (position == null || position.signedQuantitySteps() == 0
                || position.marginMode() != command.marginMode()
                || position.positionSide() != command.positionSide()) {
            throw new CoreStateRejectedException("POSITION_NOT_FOUND", "isolated position does not exist");
        }
        BalanceRuntime balance = runtime.balance(userId, position.assetId());
        if (balance == null) {
            throw new IllegalStateException("position margin balance is missing");
        }
        long units = Math.absExact(command.amountUnits());
        long nextMargin;
        if (command.amountUnits() > 0) {
            balance.reserve(units);
            nextMargin = Math.addExact(position.positionMarginUnits(), units);
        } else {
            if (position.positionMarginUnits() < units) {
                throw new CoreStateRejectedException("POSITION_MARGIN_INSUFFICIENT",
                        "position margin is insufficient");
            }
            balance.release(units);
            nextMargin = Math.subtractExact(position.positionMarginUnits(), units);
        }
        runtime.markBalanceChanged(userId, position.assetId());
        runtime.replacePosition(positionKey, new PositionRuntime(position.userId(), position.symbolId(),
                position.assetId(), position.marginMode(), position.positionSide(), position.instrumentVersion(),
                position.signedQuantitySteps(), position.entryPriceTicks(), position.entryValueTicks(),
                position.realizedPnlUnits(), nextMargin));
        runtime.advanceUserRevision(userId);
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
    }

    public static void adjustInsuranceFund(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                           AdjustInsuranceFundCommand command) {
        if (runtime == null || identities == null || command == null) {
            throw new IllegalArgumentException("invalid runtime insurance adjustment");
        }
        runtime.assertOwner();
        int assetId = identities.assetId(command.asset());
        long current = runtime.treasury().insurance(assetId);
        if (command.deltaUnits() < 0 && Math.negateExact(command.deltaUnits()) > current) {
            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                    "insurance fund balance is insufficient");
        }
        runtime.treasury().adjustInsurance(assetId, command.deltaUnits());
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
    }

    public static void placeOrder(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                  long userId, ResolvedPlaceOrder command, UUID commandId,
                                  long requiredReservation) {
        if (runtime == null || identities == null || command == null || commandId == null || userId <= 0
                || requiredReservation <= 0) {
            throw new IllegalArgumentException("invalid runtime place order");
        }
        runtime.assertOwner();
        if (runtime.order(command.orderId()) != null) {
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "orderId already exists");
        }
        long clientKey = identities.clientKey(userId, command.clientOrderId());
        if (clientKey != 0 && runtime.orderIdByClient(userId, clientKey) != null) {
            throw new CoreStateRejectedException("DUPLICATE_CLIENT_ORDER_ID", "clientOrderId already exists");
        }
        int symbolId = identities.symbolId(command.symbol());
        int assetId = identities.assetId(command.reservationAsset());
        UserRuntime user = runtime.user(userId);
        BalanceRuntime balance = runtime.balance(userId, assetId);
        if (user == null || balance == null || balance.availableUnits() < requiredReservation) {
            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                    "available balance is insufficient");
        }
        OrderRuntime order = new OrderRuntime(command.orderId(), runtime.productLine(), userId, symbolId,
                command.instrumentVersion(), command.side(), command.limitPriceTicks(), command.matchingPriceTicks(),
                command.quantitySteps(), 0, command.quantitySteps(), command.reduceOnly(), command.marginMode(),
                command.positionSide(), command.orderType(), command.timeInForce(), command.postOnly(),
                command.clientOrderId(), commandId, command.makerFeeRatePpm(), command.takerFeeRatePpm(),
                0, 0, 0, CoreOrderStatus.OPEN, 1);
        ReservationRuntime reservation = new ReservationRuntime(command.orderId(), userId, symbolId,
                command.instrumentVersion(), command.reservationKind(), assetId, requiredReservation,
                0, 0, command.quantitySteps());
        runtime.reserveOrder(command.orderId(), userId, clientKey, symbolId,
                command.quantitySteps(), assetId, requiredReservation);
        runtime.replaceOrder(order);
        runtime.replaceReservation(reservation);
        runtime.putUser(new UserRuntime(runtime.productLine(), userId,
                Math.incrementExact(user.revision()), user.positionMode()));
        incrementRevision(runtime);
    }

    public static boolean cancelOrder(TradingRuntimeState runtime, long userId, long orderId) {
        if (runtime == null || userId <= 0 || orderId <= 0) {
            throw new IllegalArgumentException("invalid runtime cancel order");
        }
        runtime.assertOwner();
        OrderRuntime order = runtime.order(orderId);
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        if (order.userId() != userId) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        if (order.status().terminal()) return false;
        ReservationRuntime reservation = runtime.reservation(orderId);
        if (reservation == null) throw new IllegalStateException("open order is missing reservation");
        runtime.cancelOrder(orderId, userId, reservation.reservedUnits());
        incrementRevision(runtime);
        return true;
    }

    public static void rejectPlaceOrder(TradingRuntimeState runtime, long userId, long orderId) {
        if (runtime == null || userId <= 0 || orderId <= 0) {
            throw new IllegalArgumentException("invalid runtime rejected order");
        }
        runtime.assertOwner();
        OrderRuntime order = runtime.order(orderId);
        if (order == null || order.userId() != userId) {
            throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        }
        ReservationRuntime reservation = runtime.reservation(orderId);
        if (reservation == null) throw new IllegalStateException("rejected order reservation is missing");
        BalanceRuntime balance = runtime.balance(userId, reservation.assetId());
        if (balance == null) throw new IllegalStateException("rejected order balance is missing");
        long releaseUnits = reservation.reservedUnits();
        if (releaseUnits > 0) {
            balance.release(releaseUnits);
            runtime.markBalanceChanged(userId, reservation.assetId());
        }
        runtime.replaceOrder(order.withStatus(CoreOrderStatus.REJECTED, Math.incrementExact(order.revision())));
        runtime.removeReservation(orderId, userId);
        runtime.advanceUserRevision(userId);
        incrementRevision(runtime);
    }

    public static boolean stampOrderChanges(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                            TradingCoreState commandBefore,
                                            long timestamp, long clusterPosition,
                                            Iterable<Long> changedOrderIds) {
        if (runtime == null || identities == null || commandBefore == null
                || timestamp < 0 || clusterPosition < 0) {
            throw new IllegalArgumentException("invalid runtime order commit metadata");
        }
        runtime.assertOwner();
        Iterable<Long> candidates = changedOrderIds;
        if (candidates == null) {
            ArrayList<Long> all = new ArrayList<>();
            runtime.ordersForSnapshot().forEachKey(all::add);
            candidates = all;
        }
        boolean changed = false;
        for (Long orderId : candidates) {
            if (orderId == null) continue;
            OrderRuntime order = runtime.order(orderId);
            if (order == null) continue;
            CoreOrderState previous = commandBefore.order(orderId);
            OrderRuntime previousRuntime = previous == null
                    ? null : RuntimeStateProjector.toRuntimeOrder(previous, identities);
            if (!order.equals(previousRuntime)) {
                runtime.replaceOrder(order.withCommitMetadata(timestamp, clusterPosition));
                changed = true;
            }
        }
        return changed;
    }

    public static void pruneTerminalState(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                          TerminalPruneBatch batch) {
        if (runtime == null || identities == null || batch == null || batch.isEmpty()) {
            throw new IllegalArgumentException("invalid runtime terminal prune");
        }
        runtime.assertOwner();
        for (long orderId : batch.orderIds()) {
            OrderRuntime order = runtime.order(orderId);
            if (order == null || !order.status().terminal()) {
                throw new IllegalStateException("order is not terminal: " + orderId);
            }
            ReservationRuntime reservation = runtime.reservation(orderId);
            if (reservation != null) {
                if (reservation.reservedUnits() != 0) {
                    throw new IllegalStateException("terminal order retains funded reservation: " + orderId);
                }
                runtime.removeReservation(orderId, order.userId());
            }
            runtime.removeOrder(orderId);
            long clientKey = identities.clientKey(order.userId(), order.clientOrderId());
            if (clientKey != 0 && Long.valueOf(orderId).equals(
                    runtime.orderIdByClient(order.userId(), clientKey))) {
                runtime.removeClientOrder(order.userId(), clientKey);
            }
        }
        for (long algoOrderId : batch.algoOrderIds()) {
            CoreAlgoOrderState algo = runtime.algoOrder(algoOrderId);
            if (algo == null || !algo.terminal()) {
                throw new IllegalStateException("algo order is not terminal: " + algoOrderId);
            }
            runtime.removeAlgoOrder(algoOrderId);
        }
        for (long triggerOrderId : batch.triggerOrderIds()) {
            CoreTriggerOrderState trigger = runtime.triggerOrder(triggerOrderId);
            if (trigger == null || trigger.status().open()) {
                throw new IllegalStateException("trigger order is not terminal: " + triggerOrderId);
            }
            runtime.removeTriggerOrder(triggerOrderId);
        }
        for (long liquidationId : batch.liquidationIds()) {
            LiquidationRuntime liquidation = runtime.liquidation(liquidationId);
            if (liquidation == null || liquidation.status() != CoreLiquidationState.Status.CANCELED
                    && (liquidation.status() != CoreLiquidationState.Status.COMPLETED
                    || liquidation.deficitUnits() != 0)) {
                throw new IllegalStateException("liquidation is not terminal: " + liquidationId);
            }
            runtime.removeLiquidation(liquidationId);
        }
    }

    public static void replaceRiskScan(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                       CoreRiskState.RiskScan scan) {
        if (runtime == null || identities == null || scan == null) {
            throw new IllegalArgumentException("invalid runtime risk scan");
        }
        runtime.putRiskScan(new RiskScanRuntime(identities.symbolId(scan.symbol()), scan.priceSequence(),
                scan.scanStartPriceSequence(), scan.lastUserId(), scan.riskComplete(), scan.riskUserId(),
                scan.riskPhase(), scan.riskPositionCursor(), scan.riskReservationCursor(),
                scan.riskUnrealizedPnlUnits(), scan.riskMaintenanceMarginUnits(), scan.riskIsolatedMarginUnits(),
                scan.riskIsolatedReservationUnits(), scan.triggerComplete(), scan.triggerPhase(),
                scan.triggerPriceCursor(), scan.triggerOrderCursor(), scan.triggerUpperId(),
                scan.triggerMarkPriceTicks(), scan.triggerGeneratedAtEpochMillis(), scan.triggerOcoOrderId(),
                scan.triggerOcoCursor()));
        incrementRevision(runtime);
    }

    public static void updateCancelAllAfter(TradingRuntimeState runtime, long userId,
                                            CoreCancelAllAfterCommand command) {
        if (runtime == null || command == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime cancel-all-after update");
        }
        runtime.assertOwner();
        if (command.userId() != userId) {
            throw new CoreStateRejectedException("CANCEL_ALL_AFTER_OWNER_MISMATCH",
                    "cancel-all-after timer belongs to another user");
        }
        CoreCancelAllAfterKey key = new CoreCancelAllAfterKey(userId, command.symbolScope());
        CoreCancelAllAfterState current = runtime.cancelAllAfterTimer(key);
        CoreCancelAllAfterState next;
        switch (command.action()) {
            case SET -> {
                CoreCancelAllAfterStatus status = command.countdownMillis() == 0
                        ? CoreCancelAllAfterStatus.DISABLED : CoreCancelAllAfterStatus.ACTIVE;
                if (status == CoreCancelAllAfterStatus.ACTIVE
                        && command.triggerAtEpochMillis() <= command.updatedAtEpochMillis()) {
                    throw new CoreStateRejectedException("INVALID_CANCEL_ALL_AFTER_TRIGGER",
                            "active cancel-all-after timer must trigger in the future");
                }
                next = new CoreCancelAllAfterState(userId, command.symbolScope(), command.countdownMillis(), status,
                        status == CoreCancelAllAfterStatus.ACTIVE ? command.triggerAtEpochMillis() : 0,
                        command.updatedAtEpochMillis(), 0, 0,
                        current == null ? 1 : Math.incrementExact(current.revision()));
            }
            case CLAIM -> {
                requireTimerRevision(current, command);
                if (current.status() != CoreCancelAllAfterStatus.ACTIVE
                        || current.triggerAtEpochMillis() > command.updatedAtEpochMillis()) {
                    throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_DUE", "timer is not due");
                }
                next = new CoreCancelAllAfterState(userId, current.symbolScope(), current.countdownMillis(),
                        CoreCancelAllAfterStatus.TRIGGERING, current.triggerAtEpochMillis(),
                        command.updatedAtEpochMillis(), current.canceledOrders(), current.canceledTriggerOrders(),
                        Math.incrementExact(current.revision()));
            }
            case COMPLETE -> {
                requireTimerRevision(current, command);
                if (current.status() != CoreCancelAllAfterStatus.TRIGGERING) {
                    throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_CLAIMED", "timer is not claimed");
                }
                next = new CoreCancelAllAfterState(userId, current.symbolScope(), current.countdownMillis(),
                        CoreCancelAllAfterStatus.TRIGGERED, current.triggerAtEpochMillis(),
                        command.updatedAtEpochMillis(), command.canceledOrders(), command.canceledTriggerOrders(),
                        Math.incrementExact(current.revision()));
            }
            case RETRY -> {
                requireTimerRevision(current, command);
                if (current.status() != CoreCancelAllAfterStatus.TRIGGERING) {
                    throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_CLAIMED", "timer is not claimed");
                }
                next = new CoreCancelAllAfterState(userId, current.symbolScope(), current.countdownMillis(),
                        CoreCancelAllAfterStatus.ACTIVE, current.triggerAtEpochMillis(),
                        command.updatedAtEpochMillis(), current.canceledOrders(), current.canceledTriggerOrders(),
                        Math.incrementExact(current.revision()));
            }
            default -> throw new CoreStateRejectedException("INVALID_CANCEL_ALL_AFTER_ACTION", "unsupported action");
        }
        runtime.putCancelAllAfterTimer(key, next);
        incrementRevision(runtime);
    }

    public static void upsertAlgoOrder(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                       long userId, CoreAlgoOrderView view) {
        if (runtime == null || identities == null || view == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime algo order update");
        }
        runtime.assertOwner();
        if (view.userId() != userId) {
            throw new CoreStateRejectedException("ALGO_ORDER_OWNER_MISMATCH", "algo order belongs to another user");
        }
        if (view.clientAlgoOrderId().isBlank()) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "clientAlgoOrderId is required");
        }
        CoreAlgoOrderState next = CoreAlgoOrderState.from(view);
        CoreAlgoOrderState current = runtime.algoOrder(next.algoOrderId());
        if (current == null) {
            boolean duplicateClient = runtime.algoOrdersForRuntime().values().stream()
                    .anyMatch(value -> value.userId() == userId
                            && value.clientAlgoOrderId().equals(next.clientAlgoOrderId()));
            if (duplicateClient) {
                throw new CoreStateRejectedException("DUPLICATE_CLIENT_ALGO_ORDER_ID",
                        "clientAlgoOrderId already exists");
            }
            if (!next.childOrderIds().isEmpty() || next.revision() != 1) {
                throw new CoreStateRejectedException("INVALID_ALGO_ORDER_CREATE", "new algo order must start empty");
            }
        } else {
            requireSameAlgoIntent(current, next);
            if (next.revision() <= current.revision()) {
                throw new CoreStateRejectedException("STALE_ALGO_ORDER_REVISION", "algo order revision is stale");
            }
            if (next.revision() != Math.incrementExact(current.revision())
                    || next.childOrderIds().size() < current.childOrderIds().size()
                    || !next.childOrderIds().subList(0, current.childOrderIds().size()).equals(current.childOrderIds())
                    || next.childOrderIds().size() > current.childOrderIds().size() + 1) {
                throw new CoreStateRejectedException("INVALID_ALGO_ORDER_REVISION",
                        "algo order revision is not monotonic");
            }
            if (next.childOrderIds().size() > current.childOrderIds().size()) {
                OrderRuntime child = runtime.order(next.childOrderIds().getLast());
                if (child == null || child.userId() != userId
                        || child.symbolId() != identities.symbolId(next.symbol())) {
                    throw new CoreStateRejectedException("INVALID_ALGO_CHILD",
                            "algo child order is not authoritative");
                }
            }
        }
        runtime.putAlgoOrder(next);
        incrementRevision(runtime);
    }

    public static void upsertTriggerOrder(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                          long userId, CoreTriggerOrderStateView view) {
        if (runtime == null || identities == null || view == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime trigger order update");
        }
        runtime.assertOwner();
        if (view.userId() != userId || view.productLine() != runtime.productLine()) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_OWNER_MISMATCH", "trigger order owner mismatch");
        }
        if (view.clientTriggerOrderId().isBlank()) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "clientTriggerOrderId is required");
        }
        CoreInstrumentState instrument = runtime.instrument(view.symbol());
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "trigger order instrument does not exist");
        }
        int symbolId = identities.symbolId(instrument.symbol());
        if (runtime.treasury().lifecycleSettlement(symbolId) != 0) {
            throw new CoreStateRejectedException("INSTRUMENT_SETTLED", "instrument is already settled");
        }
        if (runtime.triggerOrder(view.triggerOrderId()) != null) {
            throw new CoreStateRejectedException("DUPLICATE_TRIGGER_ORDER_ID", "trigger order already exists");
        }
        boolean duplicateClient = runtime.triggerOrdersForRuntime().values().stream()
                .anyMatch(order -> order.userId() == userId
                        && order.clientTriggerOrderId().equals(view.clientTriggerOrderId()));
        if (duplicateClient) {
            throw new CoreStateRejectedException("DUPLICATE_CLIENT_TRIGGER_ORDER_ID",
                    "client trigger order id already exists");
        }
        validateTriggerPlacement(runtime, identities, userId, symbolId, view);
        CoreTriggerOrderState trigger = CoreTriggerOrderState.from(view);
        if (trigger.instrumentVersion() == 0) {
            trigger = trigger.withExecutionSnapshot(instrument.version(), instrument.makerFeeRatePpm(),
                    instrument.takerFeeRatePpm());
        } else if (trigger.instrumentVersion() != instrument.version()) {
            throw new CoreStateRejectedException("STALE_INSTRUMENT_VERSION",
                    "trigger order instrument version is stale");
        }
        runtime.putTriggerOrder(trigger);
        incrementRevision(runtime);
    }

    public static boolean cancelTriggerOrder(TradingRuntimeState runtime, long userId, long triggerOrderId) {
        requireTriggerInput(runtime, userId, triggerOrderId);
        CoreTriggerOrderState current = requireTrigger(runtime, triggerOrderId);
        if (current.userId() != userId) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_OWNER_MISMATCH", "trigger order owner mismatch");
        }
        if (!current.status().open()) return false;
        updateTrigger(runtime, current, CoreTriggerOrderStatus.CANCELED, 0, current.triggerSequence(),
                current.triggeredPriceTicks(), current.rejectReason(), current.updatedAtEpochMillis());
        return true;
    }

    public static boolean claimTriggerOrder(TradingRuntimeState runtime, long triggerOrderId, long triggerSequence,
                                            long triggeredPriceTicks, long triggeredAtEpochMillis) {
        CoreTriggerOrderState current = requireTrigger(runtime, triggerOrderId);
        if (current.status() != CoreTriggerOrderStatus.PENDING) return false;
        updateTrigger(runtime, current, CoreTriggerOrderStatus.TRIGGERING, 0, triggerSequence,
                triggeredPriceTicks, current.rejectReason(), triggeredAtEpochMillis);
        return true;
    }

    public static boolean completeTriggerOrder(TradingRuntimeState runtime, long triggerOrderId, boolean success,
                                               long placedOrderId, String rejectReason,
                                               long completedAtEpochMillis) {
        CoreTriggerOrderState current = requireTrigger(runtime, triggerOrderId);
        if (current.status() != CoreTriggerOrderStatus.TRIGGERING) return false;
        updateTrigger(runtime, current, success ? CoreTriggerOrderStatus.TRIGGERED
                        : CoreTriggerOrderStatus.TRIGGER_FAILED, placedOrderId, current.triggerSequence(),
                current.triggeredPriceTicks(), rejectReason, completedAtEpochMillis);
        return true;
    }

    public static boolean updateTriggerTrailing(TradingRuntimeState runtime, long triggerOrderId,
                                                long highestPriceTicks, long lowestPriceTicks,
                                                long activatedAtEpochMillis) {
        CoreTriggerOrderState current = requireTrigger(runtime, triggerOrderId);
        if (!current.status().open() || current.triggerType() != CoreTriggerOrderType.TRAILING_STOP) return false;
        runtime.putTriggerOrder(new CoreTriggerOrderState(current.triggerOrderId(), current.productLine(),
                current.userId(), current.clientTriggerOrderId(), current.ocoGroupId(), current.symbol(), current.side(),
                current.triggerType(), current.triggerCondition(), current.triggerPriceTicks(),
                current.activationPriceTicks(), current.callbackRatePpm(), highestPriceTicks, lowestPriceTicks,
                activatedAtEpochMillis, current.orderType(), current.timeInForce(), current.priceTicks(),
                current.quantitySteps(), current.marginMode(), current.positionSide(), current.status(),
                current.placedOrderId(), current.triggerSequence(), current.triggeredPriceTicks(), current.rejectReason(),
                current.traceId(), current.expiresAtEpochMillis(), current.triggeredAtEpochMillis(),
                current.createdAtEpochMillis(), Math.max(current.updatedAtEpochMillis(), activatedAtEpochMillis),
                Math.incrementExact(current.revision()), current.instrumentVersion(), current.makerFeeRatePpm(),
                current.takerFeeRatePpm()));
        incrementRevision(runtime);
        return true;
    }

    public static boolean expireTriggerOrder(TradingRuntimeState runtime, long triggerOrderId,
                                             long expiredAtEpochMillis) {
        CoreTriggerOrderState current = requireTrigger(runtime, triggerOrderId);
        if (current.status() != CoreTriggerOrderStatus.PENDING || current.expiresAtEpochMillis() == 0
                || current.expiresAtEpochMillis() > expiredAtEpochMillis) return false;
        updateTrigger(runtime, current, CoreTriggerOrderStatus.EXPIRED, 0, current.triggerSequence(),
                current.triggeredPriceTicks(), current.rejectReason(), expiredAtEpochMillis);
        return true;
    }

    public static boolean retryTriggerOrder(TradingRuntimeState runtime, long triggerOrderId,
                                            long staleBeforeEpochMillis, long retryAtEpochMillis) {
        CoreTriggerOrderState current = requireTrigger(runtime, triggerOrderId);
        if (current.status() != CoreTriggerOrderStatus.TRIGGERING
                || current.updatedAtEpochMillis() > staleBeforeEpochMillis) return false;
        updateTrigger(runtime, current, CoreTriggerOrderStatus.PENDING, 0, 0, 0,
                current.rejectReason(), retryAtEpochMillis);
        return true;
    }

    private static void validateTriggerPlacement(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                                 long userId, int symbolId, CoreTriggerOrderStateView view) {
        UserRuntime user = runtime.user(userId);
        if (user == null) throw new CoreStateRejectedException("USER_NOT_FOUND", "user does not exist");
        if (!runtime.productLine().isDerivative()) return;
        if (user.positionMode() == CorePositionMode.ONE_WAY && view.positionSide().hedgeSide()
                || user.positionMode() == CorePositionMode.HEDGE && !view.positionSide().hedgeSide()) {
            throw new CoreStateRejectedException("POSITION_MODE_MISMATCH",
                    "trigger position side does not match user position mode");
        }
        String positionIdentity = view.positionSide().hedgeSide()
                ? OrderReservation.normalizeSymbol(view.symbol()) + ':' + view.positionSide().name()
                : OrderReservation.normalizeSymbol(view.symbol());
        PositionRuntime position = runtime.position(identities.positionKey(userId, positionIdentity));
        if (position == null || position.signedQuantitySteps() == 0) {
            throw new CoreStateRejectedException("TRIGGER_POSITION_REQUIRED",
                    "trigger order requires an open position");
        }
        if (position.marginMode() != view.marginMode()) {
            throw new CoreStateRejectedException("POSITION_MARGIN_ADJUSTMENT_INVALID",
                    "trigger margin mode does not match position");
        }
        CoreOrderSide closeSide = position.signedQuantitySteps() > 0 ? CoreOrderSide.SELL : CoreOrderSide.BUY;
        if (view.side() != closeSide) {
            throw new CoreStateRejectedException("TRIGGER_SIDE_NOT_REDUCING",
                    "trigger order side must reduce the current position");
        }
        long openReduceOnly = 0;
        for (OrderRuntime order : runtime.ordersForSnapshot()) {
            if (order.userId() == userId && order.symbolId() == symbolId && order.status() == CoreOrderStatus.OPEN
                    && order.reduceOnly() && order.marginMode() == view.marginMode()
                    && order.positionSide() == view.positionSide() && order.side() == closeSide) {
                openReduceOnly = Math.addExact(openReduceOnly, order.remainingQuantitySteps());
            }
        }
        long triggerCapacity = 0;
        long sameOcoGroupMax = 0;
        for (CoreTriggerOrderState trigger : runtime.triggerOrdersForRuntime().values()) {
            if (!trigger.status().open() || trigger.userId() != userId || !trigger.symbol().equals(view.symbol())
                    || trigger.marginMode() != view.marginMode() || trigger.positionSide() != view.positionSide()
                    || trigger.side() != closeSide) continue;
            triggerCapacity = Math.addExact(triggerCapacity, trigger.quantitySteps());
            if (!view.ocoGroupId().isEmpty() && view.ocoGroupId().equals(trigger.ocoGroupId())) {
                sameOcoGroupMax = Math.max(sameOcoGroupMax, trigger.quantitySteps());
            }
        }
        long projectedTriggerCapacity = Math.addExact(Math.subtractExact(triggerCapacity, sameOcoGroupMax),
                Math.max(sameOcoGroupMax, view.quantitySteps()));
        if (Math.addExact(openReduceOnly, projectedTriggerCapacity)
                > Math.absExact(position.signedQuantitySteps())) {
            throw new CoreStateRejectedException("TRIGGER_CLOSE_CAPACITY_EXCEEDED",
                    "trigger order quantity exceeds available position");
        }
    }

    private static void updateTrigger(TradingRuntimeState runtime, CoreTriggerOrderState current,
                                      CoreTriggerOrderStatus status, long placedOrderId, long triggerSequence,
                                      long triggeredPriceTicks, String rejectReason, long updatedAt) {
        runtime.putTriggerOrder(new CoreTriggerOrderState(current.triggerOrderId(), current.productLine(),
                current.userId(), current.clientTriggerOrderId(), current.ocoGroupId(), current.symbol(), current.side(),
                current.triggerType(), current.triggerCondition(), current.triggerPriceTicks(),
                current.activationPriceTicks(), current.callbackRatePpm(), current.highestPriceTicks(),
                current.lowestPriceTicks(), current.activatedAtEpochMillis(), current.orderType(), current.timeInForce(),
                current.priceTicks(), current.quantitySteps(), current.marginMode(), current.positionSide(), status,
                placedOrderId, triggerSequence, triggeredPriceTicks, rejectReason, current.traceId(),
                current.expiresAtEpochMillis(), status == CoreTriggerOrderStatus.TRIGGERED
                        || status == CoreTriggerOrderStatus.TRIGGER_FAILED ? updatedAt : current.triggeredAtEpochMillis(),
                current.createdAtEpochMillis(), updatedAt, Math.incrementExact(current.revision()),
                current.instrumentVersion(), current.makerFeeRatePpm(), current.takerFeeRatePpm()));
        incrementRevision(runtime);
    }

    private static CoreTriggerOrderState requireTrigger(TradingRuntimeState runtime, long triggerOrderId) {
        if (runtime == null || triggerOrderId <= 0) throw new IllegalArgumentException("invalid runtime trigger order");
        runtime.assertOwner();
        CoreTriggerOrderState current = runtime.triggerOrder(triggerOrderId);
        if (current == null) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
        }
        return current;
    }

    private static void requireTriggerInput(TradingRuntimeState runtime, long userId, long triggerOrderId) {
        if (runtime == null || userId <= 0 || triggerOrderId <= 0) {
            throw new IllegalArgumentException("invalid runtime trigger order");
        }
    }

    private static void requireTimerRevision(CoreCancelAllAfterState current, CoreCancelAllAfterCommand command) {
        if (current == null) {
            throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_FOUND", "timer not found");
        }
        if (command.expectedRevision() != current.revision()) {
            throw new CoreStateRejectedException("STALE_CANCEL_ALL_AFTER_REVISION", "timer revision is stale");
        }
    }

    private static void requireSameAlgoIntent(CoreAlgoOrderState left, CoreAlgoOrderState right) {
        if (left.userId() != right.userId() || !left.clientAlgoOrderId().equals(right.clientAlgoOrderId())
                || !left.symbol().equals(right.symbol()) || left.algoTypeCode() != right.algoTypeCode()
                || left.side() != right.side() || left.priceTicks() != right.priceTicks()
                || left.quantitySteps() != right.quantitySteps()
                || left.childQuantitySteps() != right.childQuantitySteps()
                || left.intervalSeconds() != right.intervalSeconds() || left.durationSeconds() != right.durationSeconds()
                || left.marginMode() != right.marginMode() || left.positionSide() != right.positionSide()
                || left.reduceOnly() != right.reduceOnly() || left.postOnly() != right.postOnly()
                || left.timeInForce() != right.timeInForce() || left.startAtEpochMillis() != right.startAtEpochMillis()
                || left.createdAtEpochMillis() != right.createdAtEpochMillis()) {
            throw new CoreStateRejectedException("ALGO_ORDER_INTENT_MISMATCH", "algo order intent is immutable");
        }
    }

    private static void incrementRevision(TradingRuntimeState runtime) {
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
    }

    private static long initialMarginRateFromLeverage(long leveragePpm) {
        BigInteger numerator = BigInteger.valueOf(1_000_000L).multiply(BigInteger.valueOf(1_000_000L));
        BigInteger[] quotient = numerator.divideAndRemainder(BigInteger.valueOf(leveragePpm));
        return (quotient[1].signum() == 0 ? quotient[0] : quotient[0].add(BigInteger.ONE)).longValueExact();
    }
}
