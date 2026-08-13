package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ReplaceOrderCommand;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.service.matching.CoreMatch;
import com.surprising.instrument.api.math.PerpetualContractMath;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class TradingCoreReducer {

    public TradingCoreState adjustBalance(
            TradingCoreState state,
            long userId,
            BalanceAdjustmentCommand command) {
        requireUserId(userId);
        CoreUserState currentUser = state.users().getOrDefault(userId,
                CoreUserState.empty(state.productLine(), userId));
        String asset = AssetBalance.normalizeAsset(command.asset());
        AssetBalance currentBalance = currentUser.balances().getOrDefault(asset, new AssetBalance(asset, 0, 0));
        AssetBalance nextBalance = currentBalance.adjustAvailable(command.deltaUnits());

        Map<String, AssetBalance> balances = new TreeMap<>(currentUser.balances());
        balances.put(asset, nextBalance);
        CoreUserState nextUser = new CoreUserState(state.productLine(), userId,
                Math.incrementExact(currentUser.revision()), balances,
                currentUser.reservations(), currentUser.positions());
        return replaceUser(state, nextUser, state.orders(), state.bookState());
    }

    public TradingCoreState placeOrder(TradingCoreState state, long userId, PlaceOrderCommand command) {
        requireUserId(userId);
        if (state.orders().containsKey(command.orderId())) {
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "orderId already exists");
        }
        CoreInstrumentState instrument = requireInstrument(state, command.symbol(), command.instrumentVersion());
        if (state.treasuryState().lifecycleSettlements().containsKey(instrument.symbol())) {
            throw new CoreStateRejectedException("INSTRUMENT_SETTLED", "instrument is already settled");
        }
        validateReservationRule(state, command);
        validateInstrumentOrder(instrument, command);
        boolean versionConflict = state.bookState().openOrders().values().stream()
                .filter(order -> order.symbol().equals(OrderReservation.normalizeSymbol(command.symbol())))
                .map(order -> state.order(order.orderId()))
                .anyMatch(order -> order.instrumentVersion() != command.instrumentVersion());
        if (versionConflict) {
            throw new CoreStateRejectedException("INSTRUMENT_VERSION_OPEN_BOOK_MISMATCH",
                    "open book contains another instrument version");
        }
        CoreUserState currentUser = state.users().getOrDefault(userId,
                CoreUserState.empty(state.productLine(), userId));
        validateReduceOnlyCapacity(state, currentUser, command);
        long requiredReservation = requiredReservationUnits(instrument, currentUser, command);
        if (command.reservedUnits() < requiredReservation) {
            throw new CoreStateRejectedException("INSUFFICIENT_ORDER_RESERVATION",
                    "order reservation is below deterministic margin and fee requirement");
        }
        String asset = AssetBalance.normalizeAsset(command.reservationAsset());
        AssetBalance currentBalance = currentUser.balances().getOrDefault(asset, new AssetBalance(asset, 0, 0));
        AssetBalance nextBalance = currentBalance.reserve(command.reservedUnits());
        OrderReservation reservation = OrderReservation.create(command.orderId(), command.symbol(),
                command.instrumentVersion(),
                command.reservationKind(), asset, command.reservedUnits(), command.quantitySteps());
        CoreOrderState order = new CoreOrderState(command.orderId(), state.productLine(), userId,
                command.symbol(), command.instrumentVersion(), command.side(), command.priceTicks(),
                command.quantitySteps(), 0,
                command.quantitySteps(), command.reduceOnly(), CoreOrderStatus.OPEN, 1);

        Map<String, AssetBalance> balances = new TreeMap<>(currentUser.balances());
        balances.put(asset, nextBalance);
        Map<Long, OrderReservation> reservations = new TreeMap<>(currentUser.reservations());
        reservations.put(command.orderId(), reservation);
        CoreUserState nextUser = new CoreUserState(state.productLine(), userId,
                Math.incrementExact(currentUser.revision()), balances, reservations, currentUser.positions());
        Map<Long, CoreOrderState> orders = new TreeMap<>(state.orders());
        orders.put(order.orderId(), order);
        return replaceUser(state, nextUser, orders, state.bookState());
    }

    public TradingCoreState cancelOrder(TradingCoreState state, long userId, CancelOrderCommand command) {
        requireUserId(userId);
        CoreOrderState currentOrder = state.orders().get(command.orderId());
        if (currentOrder == null) {
            throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        }
        if (currentOrder.userId() != userId) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        if (currentOrder.status().terminal()) {
            return state;
        }
        CoreUserState currentUser = state.users().get(userId);
        OrderReservation currentReservation = currentUser.reservations().get(command.orderId());
        if (currentReservation == null) {
            throw new IllegalStateException("open order is missing reservation");
        }
        long releaseUnits = currentReservation.remainingUnits();
        AssetBalance currentBalance = currentUser.balances().get(currentReservation.asset());
        if (currentBalance == null) {
            throw new IllegalStateException("reservation balance is missing");
        }
        AssetBalance nextBalance = releaseUnits == 0
                ? currentBalance : currentBalance.release(releaseUnits);
        OrderReservation nextReservation = releaseUnits == 0
                ? currentReservation : currentReservation.releaseAll();

        Map<String, AssetBalance> balances = new TreeMap<>(currentUser.balances());
        balances.put(nextBalance.asset(), nextBalance);
        Map<Long, OrderReservation> reservations = new TreeMap<>(currentUser.reservations());
        reservations.put(command.orderId(), nextReservation);
        CoreUserState nextUser = new CoreUserState(state.productLine(), userId,
                Math.incrementExact(currentUser.revision()), balances, reservations, currentUser.positions());
        Map<Long, CoreOrderState> orders = new TreeMap<>(state.orders());
        orders.put(command.orderId(), currentOrder.cancel());
        Map<Long, CoreBookOrder> bookOrders = new TreeMap<>(state.bookState().openOrders());
        bookOrders.remove(command.orderId());
        CoreBookState bookState = new CoreBookState(state.bookState().nextPrioritySequence(), bookOrders);
        return replaceUser(state, nextUser, orders, bookState);
    }

    public TradingCoreState applyMatches(
            TradingCoreState state,
            long takerOrderId,
            String baseAsset,
            String quoteAsset,
            List<CoreMatch> matches) {
        if (matches == null) {
            throw new IllegalArgumentException("matches are required");
        }
        Map<Long, CoreUserState> users = new TreeMap<>(state.users());
        Map<Long, CoreOrderState> orders = new TreeMap<>(state.orders());
        Map<Long, CoreBookOrder> bookOrders = new TreeMap<>(state.bookState().openOrders());
        long nextPrioritySequence = state.bookState().nextPrioritySequence();
        CoreTreasuryState treasury = state.treasuryState();
        CoreOrderState taker = requireOpenOrder(orders, takerOrderId);
        CoreInstrumentState instrument = requireInstrument(state, taker.symbol(), taker.instrumentVersion());
        for (CoreMatch match : matches) {
            CoreOrderState maker = requireOpenOrder(orders, match.makerOrderId());
            if (!taker.symbol().equals(maker.symbol()) || taker.side() == maker.side()
                    || maker.userId() != match.makerUserId()) {
                throw new IllegalStateException("exchange-core match does not match authoritative orders");
            }
            if (taker.userId() == maker.userId()) {
                throw new CoreStateRejectedException("SELF_TRADE_PREVENTED", "self trade is not allowed");
            }
            if (state.productLine().isDerivative()) {
                DerivativeFillResult takerFill = applyDerivativeFill(users.get(taker.userId()), taker,
                        instrument, match.priceTicks(), match.quantitySteps(), true, treasury);
                users.put(taker.userId(), takerFill.user());
                treasury = takerFill.treasury();
                DerivativeFillResult makerFill = applyDerivativeFill(users.get(maker.userId()), maker,
                        instrument, match.priceTicks(), match.quantitySteps(), false, treasury);
                users.put(maker.userId(), makerFill.user());
                treasury = makerFill.treasury();
            } else {
                long quoteUnits = Math.multiplyExact(match.priceTicks(), match.quantitySteps());
                CoreOrderState buyerOrder = taker.side() == CoreOrderSide.BUY ? taker : maker;
                CoreOrderState sellerOrder = taker.side() == CoreOrderSide.SELL ? taker : maker;
                users.put(buyerOrder.userId(), applySpotFill(users.get(buyerOrder.userId()), buyerOrder,
                        AssetBalance.normalizeAsset(quoteAsset), quoteUnits,
                        AssetBalance.normalizeAsset(baseAsset), match.quantitySteps()));
                users.put(sellerOrder.userId(), applySpotFill(users.get(sellerOrder.userId()), sellerOrder,
                        AssetBalance.normalizeAsset(baseAsset), match.quantitySteps(),
                        AssetBalance.normalizeAsset(quoteAsset), quoteUnits));
            }
            taker = taker.fill(match.quantitySteps());
            maker = maker.fill(match.quantitySteps());
            orders.put(taker.orderId(), taker);
            orders.put(maker.orderId(), maker);
            if (maker.status() == CoreOrderStatus.OPEN) {
                CoreBookOrder previous = bookOrders.get(maker.orderId());
                if (previous == null) {
                    throw new IllegalStateException("maker order missing from book state");
                }
                bookOrders.put(maker.orderId(), new CoreBookOrder(maker.orderId(), maker.userId(), maker.symbol(),
                        maker.side(), maker.priceTicks(), maker.remainingQuantitySteps(), previous.prioritySequence()));
            } else {
                bookOrders.remove(maker.orderId());
                users.put(maker.userId(), releaseTerminalReservation(users.get(maker.userId()), maker.orderId()));
            }
        }
        if (taker.status() == CoreOrderStatus.OPEN) {
            bookOrders.put(taker.orderId(), new CoreBookOrder(taker.orderId(), taker.userId(), taker.symbol(),
                    taker.side(), taker.priceTicks(), taker.remainingQuantitySteps(), nextPrioritySequence));
            nextPrioritySequence = Math.incrementExact(nextPrioritySequence);
        } else {
            users.put(taker.userId(), releaseTerminalReservation(users.get(taker.userId()), taker.orderId()));
        }
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users, orders,
                new CoreBookState(nextPrioritySequence, bookOrders), state.instruments(), state.riskState(),
                treasury);
    }

    public TradingCoreState prepareReplace(
            TradingCoreState state,
            long userId,
            ReplaceOrderCommand command) {
        CoreOrderState order = requireOpenOrder(state.orders(), command.orderId());
        if (order.userId() != userId) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        CoreUserState user = state.user(userId);
        OrderReservation reservation = requireReservation(user, order.orderId());
        OrderReservation nextReservation = reservation.replaceReservedUnits(command.newReservedUnits());
        long delta = Math.subtractExact(nextReservation.remainingUnits(), reservation.remainingUnits());
        AssetBalance balance = requireBalance(user, reservation.asset());
        AssetBalance nextBalance = delta > 0 ? balance.reserve(delta)
                : delta < 0 ? balance.release(Math.negateExact(delta)) : balance;
        Map<String, AssetBalance> balances = new TreeMap<>(user.balances());
        balances.put(nextBalance.asset(), nextBalance);
        Map<Long, OrderReservation> reservations = new TreeMap<>(user.reservations());
        reservations.put(order.orderId(), nextReservation);
        CoreUserState nextUser = new CoreUserState(user.productLine(), user.userId(),
                Math.incrementExact(user.revision()), balances, reservations, user.positions());
        Map<Long, CoreUserState> users = new TreeMap<>(state.users());
        users.put(userId, nextUser);
        Map<Long, CoreOrderState> orders = new TreeMap<>(state.orders());
        CoreOrderState replaced = order.replacePrice(command.newPriceTicks());
        orders.put(order.orderId(), replaced);
        Map<Long, CoreBookOrder> bookOrders = new TreeMap<>(state.bookState().openOrders());
        bookOrders.remove(order.orderId());
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users, orders,
                new CoreBookState(state.bookState().nextPrioritySequence(), bookOrders),
                state.instruments(), state.riskState(), state.treasuryState());
    }

    public TradingCoreState upsertInstrument(TradingCoreState state, UpsertInstrumentCommand command) {
        CoreInstrumentState instrument = CoreInstrumentState.from(state.productLine(), command);
        CoreInstrumentState current = state.instruments().get(instrument.symbol());
        if (current != null && instrument.version() <= current.version()) {
            throw new CoreStateRejectedException("STALE_INSTRUMENT_VERSION", "instrument version must increase");
        }
        boolean openOrder = state.bookState().openOrders().values().stream()
                .anyMatch(order -> order.symbol().equals(instrument.symbol()));
        boolean openPosition = state.users().values().stream()
                .map(user -> user.positions().get(instrument.symbol()))
                .anyMatch(position -> position != null && position.signedQuantitySteps() != 0);
        if (current != null && (openOrder || openPosition)) {
            throw new CoreStateRejectedException("INSTRUMENT_VERSION_IN_USE",
                    "cannot replace instrument version with open state");
        }
        Map<String, CoreInstrumentState> instruments = new TreeMap<>(state.instruments());
        instruments.put(instrument.symbol(), instrument);
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                state.users(), state.orders(), state.bookState(), instruments, state.riskState(),
                state.treasuryState());
    }

    public TradingCoreState applyMarkPrice(TradingCoreState state, ApplyMarkPriceCommand command) {
        CoreInstrumentState instrument = requireInstrument(state, command.symbol(), command.instrumentVersion());
        CoreMarkPriceState current = state.riskState().markPrices().get(instrument.symbol());
        if (current != null && command.priceSequence() <= current.priceSequence()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "mark price sequence must increase");
        }
        Map<String, CoreMarkPriceState> marks = new TreeMap<>(state.riskState().markPrices());
        marks.put(instrument.symbol(), new CoreMarkPriceState(instrument.symbol(), instrument.version(),
                command.markPriceTicks(), command.priceSequence()));
        CoreRiskState risk = new CoreRiskState(marks, state.riskState().snapshots(),
                state.riskState().liquidations(),
                new CoreRiskState.RiskScan(instrument.symbol(), command.priceSequence(), 0, false),
                state.riskState().nextLiquidationId());
        TradingCoreState withMark = new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                state.users(), state.orders(), state.bookState(), state.instruments(), risk, state.treasuryState());
        return continueRiskScan(withMark, 256);
    }

    public TradingCoreState continueRiskScan(TradingCoreState state, int maxUsers) {
        if (maxUsers <= 0 || maxUsers > 4096) {
            throw new IllegalArgumentException("invalid risk scan batch size");
        }
        CoreRiskState.RiskScan scan = state.riskState().scan();
        if (scan.complete()) {
            return state;
        }
        CoreInstrumentState instrument = state.instruments().get(scan.symbol());
        CoreMarkPriceState mark = state.riskState().markPrices().get(scan.symbol());
        if (instrument == null || mark == null || mark.priceSequence() != scan.priceSequence()) {
            throw new IllegalStateException("risk scan input is missing");
        }
        Map<String, CoreRiskSnapshot> snapshots = new TreeMap<>(state.riskState().snapshots());
        Map<Long, CoreLiquidationState> liquidations = new TreeMap<>(state.riskState().liquidations());
        long nextLiquidationId = state.riskState().nextLiquidationId();
        long lastUserId = scan.lastUserId();
        int processed = 0;
        boolean complete = true;
        for (CoreUserState user : state.users().values()) {
            if (user.userId() <= scan.lastUserId()) {
                continue;
            }
            if (processed >= maxUsers) {
                complete = false;
                break;
            }
            processed++;
            lastUserId = user.userId();
            CorePositionState position = user.positions().get(scan.symbol());
            if (position == null || position.signedQuantitySteps() == 0) {
                continue;
            }
            long unrealized = PerpetualContractMath.unrealizedPnlUnits(instrument.contractType(),
                    position.signedQuantitySteps(), position.entryPriceTicks(), mark.markPriceTicks(),
                    instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits());
            AssetBalance balance = user.balances().get(instrument.settleAsset());
            long wallet = balance == null ? 0 : balance.totalUnits();
            long equity = Math.addExact(wallet, unrealized);
            long maintenance = CoreContractMath.maintenanceMarginUnits(instrument,
                    position.signedQuantitySteps(), mark.markPriceTicks());
            long ratio = maintenance <= 0 ? 0 : equity <= 0 ? Long.MAX_VALUE : safeRatio(maintenance, equity);
            CoreRiskStatus status = ratio >= 1_000_000 ? CoreRiskStatus.LIQUIDATION
                    : ratio >= 800_000 ? CoreRiskStatus.WARNING : CoreRiskStatus.NORMAL;
            CoreRiskSnapshot snapshot = new CoreRiskSnapshot(user.userId(), scan.symbol(), scan.priceSequence(),
                    equity, unrealized, maintenance, ratio, status);
            snapshots.put(snapshot.key(), snapshot);
            boolean activeLiquidation = liquidations.values().stream().anyMatch(value ->
                    value.userId() == user.userId() && value.symbol().equals(scan.symbol())
                            && value.status() != CoreLiquidationState.Status.COMPLETED);
            if (status == CoreRiskStatus.LIQUIDATION && !activeLiquidation) {
                CoreLiquidationState liquidation = new CoreLiquidationState(nextLiquidationId, user.userId(),
                        scan.symbol(), instrument.version(), scan.priceSequence(),
                        Math.absExact(position.signedQuantitySteps()), 0, CoreLiquidationState.Status.PLANNED);
                liquidations.put(nextLiquidationId, liquidation);
                nextLiquidationId = Math.incrementExact(nextLiquidationId);
            }
        }
        CoreRiskState nextRisk = new CoreRiskState(state.riskState().markPrices(), snapshots, liquidations,
                new CoreRiskState.RiskScan(scan.symbol(), scan.priceSequence(), lastUserId, complete),
                nextLiquidationId);
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), state.users(),
                state.orders(), state.bookState(), state.instruments(), nextRisk, state.treasuryState());
    }

    public TradingCoreState applyFunding(TradingCoreState state, ApplyFundingCommand command) {
        if (!state.productLine().isFundingProduct()) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED", "funding requires perpetual product");
        }
        CoreInstrumentState instrument = requireInstrument(state, command.symbol(), command.instrumentVersion());
        long previousSettlement = state.treasuryState().fundingSettlements()
                .getOrDefault(instrument.symbol(), 0L);
        if (command.settlementId() <= previousSettlement) {
            throw new CoreStateRejectedException("STALE_SETTLEMENT_ID", "funding settlement id must increase");
        }
        CoreMarkPriceState mark = state.riskState().markPrices().get(instrument.symbol());
        if (mark == null) {
            throw new CoreStateRejectedException("MARK_PRICE_NOT_FOUND", "funding requires mark price");
        }
        Map<Long, CoreUserState> users = new TreeMap<>(state.users());
        CoreTreasuryState treasury = state.treasuryState();
        for (CoreUserState user : state.users().values()) {
            CorePositionState position = user.positions().get(instrument.symbol());
            if (position == null || position.signedQuantitySteps() == 0) {
                continue;
            }
            long delta = CoreContractMath.fundingDeltaUnits(instrument, position.signedQuantitySteps(),
                    mark.markPriceTicks(), command.fundingRatePpm());
            CashResult result = applyCash(requireBalance(user, instrument.settleAsset()), delta);
            Map<String, AssetBalance> balances = new TreeMap<>(user.balances());
            balances.put(instrument.settleAsset(), result.balance());
            users.put(user.userId(), new CoreUserState(user.productLine(), user.userId(),
                    Math.incrementExact(user.revision()), balances, user.reservations(), user.positions()));
            treasury = treasury.adjustInsurance(instrument.settleAsset(), Math.negateExact(result.appliedDelta()));
        }
        treasury = treasury.recordFunding(instrument.symbol(), command.settlementId());
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users,
                state.orders(), state.bookState(), state.instruments(), state.riskState(), treasury);
    }

    public TradingCoreState settleInstrument(TradingCoreState state, SettleInstrumentCommand command) {
        CoreInstrumentState instrument = requireInstrument(state, command.symbol(), command.instrumentVersion());
        long previousSettlement = state.treasuryState().lifecycleSettlements()
                .getOrDefault(instrument.symbol(), 0L);
        if (command.settlementId() <= previousSettlement) {
            throw new CoreStateRejectedException("STALE_SETTLEMENT_ID", "lifecycle settlement id must increase");
        }
        if (!instrument.contractType().isDelivery() && !instrument.contractType().isOption()) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "instrument settlement requires delivery or option product");
        }
        if (instrument.contractType().isDelivery() && command.settlementPriceTicks() <= 0) {
            throw new CoreStateRejectedException("INVALID_SETTLEMENT_PRICE", "delivery price must be positive");
        }
        TradingCoreState canceled = cancelSymbolOrders(state, instrument.symbol());
        Map<Long, CoreUserState> users = new TreeMap<>(canceled.users());
        CoreTreasuryState treasury = canceled.treasuryState();
        for (CoreUserState user : canceled.users().values()) {
            CorePositionState position = user.positions().get(instrument.symbol());
            if (position == null || position.signedQuantitySteps() == 0) {
                continue;
            }
            AssetBalance balance = requireBalance(user, instrument.settleAsset());
            if (position.positionMarginUnits() > 0) {
                balance = balance.release(position.positionMarginUnits());
            }
            long cashDelta = instrument.contractType().isOption()
                    ? Math.multiplyExact(command.optionCashUnitsPerContract(), position.signedQuantitySteps())
                    : CoreContractMath.pnlUnits(instrument, position.signedQuantitySteps(),
                    position.entryPriceTicks(), command.settlementPriceTicks());
            CashResult result = applyCash(balance, cashDelta);
            treasury = treasury.adjustInsurance(instrument.settleAsset(), Math.negateExact(result.appliedDelta()));
            Map<String, AssetBalance> balances = new TreeMap<>(user.balances());
            balances.put(instrument.settleAsset(), result.balance());
            Map<String, CorePositionState> positions = new TreeMap<>(user.positions());
            positions.put(instrument.symbol(), new CorePositionState(instrument.symbol(), instrument.settleAsset(),
                    0, 0, 0, 0, Math.addExact(position.realizedPnlUnits(), cashDelta), 0));
            users.put(user.userId(), new CoreUserState(user.productLine(), user.userId(),
                    Math.incrementExact(user.revision()), balances, user.reservations(), positions));
        }
        treasury = treasury.recordLifecycle(instrument.symbol(), command.settlementId());
        return new TradingCoreState(canceled.productLine(), Math.incrementExact(canceled.revision()), users,
                canceled.orders(), canceled.bookState(), canceled.instruments(), canceled.riskState(), treasury);
    }

    public TradingCoreState executeLiquidation(TradingCoreState state, ExecuteLiquidationCommand command) {
        CoreLiquidationState liquidation = state.riskState().liquidations().get(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        if (liquidation.status() != CoreLiquidationState.Status.PLANNED) {
            throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT", "liquidation is not planned");
        }
        CoreInstrumentState instrument = requireInstrument(state, liquidation.symbol(),
                liquidation.instrumentVersion());
        CoreUserState user = state.user(liquidation.userId());
        CorePositionState position = user == null ? null : user.positions().get(liquidation.symbol());
        if (position == null || position.signedQuantitySteps() == 0) {
            throw new CoreStateRejectedException("POSITION_NOT_FOUND", "liquidation position does not exist");
        }
        TradingCoreState canceled = cancelUserSymbolOrders(state, user.userId(), liquidation.symbol());
        user = canceled.user(user.userId());
        position = user.positions().get(liquidation.symbol());
        AssetBalance balance = requireBalance(user, instrument.settleAsset());
        if (position.positionMarginUnits() > 0) {
            balance = balance.release(position.positionMarginUnits());
        }
        long pnl = instrument.contractType().isOption() ? 0
                : CoreContractMath.pnlUnits(instrument, position.signedQuantitySteps(),
                position.entryPriceTicks(), command.executionPriceTicks());
        CashResult cash = applyCash(balance, pnl);
        long uncovered = pnl < 0 ? Math.subtractExact(Math.negateExact(pnl),
                Math.negateExact(Math.min(0, cash.appliedDelta()))) : 0;
        CoreTreasuryState treasury = canceled.treasuryState()
                .adjustInsurance(instrument.settleAsset(), Math.negateExact(cash.appliedDelta()));
        Map<String, AssetBalance> balances = new TreeMap<>(user.balances());
        balances.put(instrument.settleAsset(), cash.balance());
        Map<String, CorePositionState> positions = new TreeMap<>(user.positions());
        positions.put(instrument.symbol(), new CorePositionState(instrument.symbol(), instrument.settleAsset(),
                0, 0, 0, 0, Math.addExact(position.realizedPnlUnits(), pnl), 0));
        CoreUserState nextUser = new CoreUserState(user.productLine(), user.userId(),
                Math.incrementExact(user.revision()), balances, user.reservations(), positions);
        Map<Long, CoreUserState> users = new TreeMap<>(canceled.users());
        users.put(nextUser.userId(), nextUser);
        Map<Long, CoreLiquidationState> liquidations = new TreeMap<>(canceled.riskState().liquidations());
        liquidations.put(liquidation.liquidationId(), liquidation.executed(uncovered));
        CoreRiskState risk = new CoreRiskState(canceled.riskState().markPrices(), canceled.riskState().snapshots(),
                liquidations, canceled.riskState().scan(), canceled.riskState().nextLiquidationId());
        return new TradingCoreState(canceled.productLine(), Math.incrementExact(canceled.revision()), users,
                canceled.orders(), canceled.bookState(), canceled.instruments(), risk, treasury);
    }

    public TradingCoreState resolveLiquidation(TradingCoreState state, ResolveLiquidationCommand command) {
        CoreLiquidationState liquidation = state.riskState().liquidations().get(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        CoreInstrumentState instrument = requireInstrument(state, liquidation.symbol(),
                liquidation.instrumentVersion());
        CoreLiquidationState.Status nextStatus;
        CoreTreasuryState treasury = state.treasuryState();
        switch (command.resolution()) {
            case INSURANCE -> {
                if (liquidation.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED) {
                    throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT",
                            "insurance resolution requires insurance state");
                }
                if (command.coveredUnits() != liquidation.deficitUnits()) {
                    throw new CoreStateRejectedException("INSURANCE_COVER_MISMATCH",
                            "insurance coverage must equal liquidation deficit");
                }
                if (command.coveredUnits() > 0) {
                    treasury = treasury.adjustInsurance(instrument.settleAsset(), command.coveredUnits());
                }
                nextStatus = CoreLiquidationState.Status.COMPLETED;
            }
            case ADL -> {
                if (liquidation.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED
                        && liquidation.status() != CoreLiquidationState.Status.ADL_REQUIRED) {
                    throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT",
                            "adl resolution requires deficit state");
                }
                if (command.coveredUnits() != liquidation.deficitUnits()) {
                    throw new CoreStateRejectedException("INSURANCE_COVER_MISMATCH",
                            "adl coverage must equal liquidation deficit");
                }
                if (command.coveredUnits() > 0) {
                    treasury = treasury.adjustInsurance(instrument.settleAsset(), command.coveredUnits());
                }
                nextStatus = CoreLiquidationState.Status.COMPLETED;
            }
            case COMPLETED -> {
                if (command.coveredUnits() != 0) {
                    throw new CoreStateRejectedException("INVALID_COMMAND", "completed resolution covers no units");
                }
                nextStatus = CoreLiquidationState.Status.COMPLETED;
            }
            default -> throw new IllegalStateException("unknown liquidation resolution");
        }
        Map<Long, CoreLiquidationState> liquidations = new TreeMap<>(state.riskState().liquidations());
        liquidations.put(liquidation.liquidationId(), liquidation.withStatus(nextStatus));
        CoreRiskState risk = new CoreRiskState(state.riskState().markPrices(), state.riskState().snapshots(),
                liquidations, state.riskState().scan(), state.riskState().nextLiquidationId());
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), state.users(),
                state.orders(), state.bookState(), state.instruments(), risk, treasury);
    }

    private TradingCoreState cancelUserSymbolOrders(TradingCoreState state, long userId, String symbol) {
        TradingCoreState current = state;
        List<Long> orderIds = state.orders().values().stream()
                .filter(order -> order.userId() == userId && order.status() == CoreOrderStatus.OPEN
                        && order.symbol().equals(symbol))
                .map(CoreOrderState::orderId).toList();
        for (long orderId : orderIds) {
            current = cancelOrder(current, userId, new CancelOrderCommand(orderId));
        }
        return current;
    }

    private TradingCoreState cancelSymbolOrders(TradingCoreState state, String symbol) {
        TradingCoreState current = state;
        List<CoreOrderState> openOrders = state.orders().values().stream()
                .filter(order -> order.status() == CoreOrderStatus.OPEN && order.symbol().equals(symbol))
                .toList();
        for (CoreOrderState order : openOrders) {
            current = cancelOrder(current, order.userId(), new CancelOrderCommand(order.orderId()));
        }
        return current;
    }

    private static long safeRatio(long maintenance, long equity) {
        try {
            return Math.multiplyExact(maintenance, 1_000_000L) / equity;
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static CoreInstrumentState requireInstrument(TradingCoreState state, String symbol, long version) {
        CoreInstrumentState instrument = state.instruments().get(OrderReservation.normalizeSymbol(symbol));
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        if (instrument.version() != version) {
            throw new CoreStateRejectedException("INSTRUMENT_VERSION_CONFLICT", "instrument version differs");
        }
        return instrument;
    }

    private static CoreOrderState requireOpenOrder(Map<Long, CoreOrderState> orders, long orderId) {
        CoreOrderState order = orders.get(orderId);
        if (order == null || order.status() != CoreOrderStatus.OPEN) {
            throw new IllegalStateException("matched order is not open orderId=" + orderId);
        }
        return order;
    }

    private static CoreUserState applySpotFill(
            CoreUserState user,
            CoreOrderState order,
            String debitAsset,
            long debitUnits,
            String creditAsset,
            long creditUnits) {
        OrderReservation reservation = requireReservation(user, order.orderId());
        if (!reservation.asset().equals(debitAsset)) {
            throw new IllegalStateException("spot fill debit asset does not match reservation");
        }
        Map<String, AssetBalance> balances = new TreeMap<>(user.balances());
        balances.put(debitAsset, requireBalance(user, debitAsset).consumeLocked(debitUnits));
        AssetBalance credit = balances.getOrDefault(creditAsset, new AssetBalance(creditAsset, 0, 0));
        balances.put(creditAsset, credit.credit(creditUnits));
        Map<Long, OrderReservation> reservations = new TreeMap<>(user.reservations());
        reservations.put(order.orderId(), reservation.consume(debitUnits));
        return new CoreUserState(user.productLine(), user.userId(), Math.incrementExact(user.revision()),
                balances, reservations, user.positions());
    }

    private static DerivativeFillResult applyDerivativeFill(
            CoreUserState user,
            CoreOrderState order,
            CoreInstrumentState instrument,
            long fillPriceTicks,
            long fillQuantitySteps,
            boolean taker,
            CoreTreasuryState treasury) {
        OrderReservation reservation = requireReservation(user, order.orderId());
        CorePositionState current = user.positions().get(order.symbol());
        long signedFill = order.side() == CoreOrderSide.BUY ? fillQuantitySteps : Math.negateExact(fillQuantitySteps);
        long currentQuantity = current == null ? 0 : current.signedQuantitySteps();
        long currentAbs = Math.absExact(currentQuantity);
        boolean opposite = currentQuantity != 0 && Long.signum(currentQuantity) != Long.signum(signedFill);
        long closeSteps = opposite ? Math.min(currentAbs, fillQuantitySteps) : 0;
        long openSteps = Math.subtractExact(fillQuantitySteps, closeSteps);
        if (order.reduceOnly() && openSteps != 0) {
            throw new CoreStateRejectedException("REDUCE_ONLY_CAPACITY_EXCEEDED",
                    "reduce-only fill would create reverse exposure");
        }
        long releasedMargin = current == null || closeSteps == 0 ? 0
                : proportional(current.positionMarginUnits(), closeSteps, currentAbs);
        long openingMargin = CoreContractMath.openingMarginUnits(instrument, order.side(), fillPriceTicks, openSteps);
        long premiumDelta = instrument.contractType().isOption()
                ? (order.side() == CoreOrderSide.BUY ? Math.negateExact(
                CoreContractMath.optionPremiumUnits(instrument, fillPriceTicks, fillQuantitySteps))
                : CoreContractMath.optionPremiumUnits(instrument, fillPriceTicks, fillQuantitySteps)) : 0;
        long feeDelta = CoreContractMath.feeDeltaUnits(instrument, fillPriceTicks, fillQuantitySteps, taker);
        long reservationDebit = Math.addExact(openingMargin,
                Math.addExact(Math.max(0, Math.negateExact(premiumDelta)), Math.max(0, Math.negateExact(feeDelta))));
        OrderReservation nextReservation = reservationDebit == 0 ? reservation : reservation.consume(reservationDebit);
        Map<String, AssetBalance> balances = new TreeMap<>(user.balances());
        AssetBalance balance = requireBalance(user, instrument.settleAsset());
        if (releasedMargin > 0) {
            balance = balance.release(releasedMargin);
        }
        long realizedPnl = 0;
        if (closeSteps > 0 && !instrument.contractType().isOption()) {
            long signedClose = currentQuantity > 0 ? closeSteps : Math.negateExact(closeSteps);
            realizedPnl = CoreContractMath.pnlUnits(instrument, signedClose,
                    current.entryPriceTicks(), fillPriceTicks);
        }
        CashResult pnlCash = applyCash(balance, realizedPnl);
        balance = pnlCash.balance();
        treasury = treasury.adjustInsurance(instrument.settleAsset(), Math.negateExact(pnlCash.appliedDelta()));
        if (premiumDelta < 0) {
            balance = balance.consumeLocked(Math.negateExact(premiumDelta));
        } else if (premiumDelta > 0) {
            balance = balance.credit(premiumDelta);
        }
        if (feeDelta < 0) {
            balance = balance.consumeLocked(Math.negateExact(feeDelta));
        } else if (feeDelta > 0) {
            balance = balance.credit(feeDelta);
        }
        treasury = treasury.adjustFee(instrument.settleAsset(), Math.negateExact(feeDelta));
        balances.put(instrument.settleAsset(), balance);
        long nextQuantity = Math.addExact(currentQuantity, signedFill);
        long nextEntryPrice;
        long nextEntryValue;
        if (nextQuantity == 0) {
            nextEntryPrice = 0;
            nextEntryValue = 0;
        } else if (currentQuantity == 0 || Long.signum(nextQuantity) != Long.signum(currentQuantity)) {
            nextEntryPrice = fillPriceTicks;
            nextEntryValue = Math.multiplyExact(Math.absExact(nextQuantity), fillPriceTicks);
        } else if (Long.signum(signedFill) == Long.signum(currentQuantity)) {
            nextEntryPrice = CoreContractMath.weightedEntryPrice(instrument, currentAbs,
                    current.entryPriceTicks(), fillQuantitySteps, fillPriceTicks);
            nextEntryValue = Math.multiplyExact(Math.absExact(nextQuantity), nextEntryPrice);
        } else {
            nextEntryPrice = current.entryPriceTicks();
            nextEntryValue = Math.multiplyExact(Math.absExact(nextQuantity), nextEntryPrice);
        }
        long nextMargin = Math.addExact(current == null ? 0 : current.positionMarginUnits(),
                Math.subtractExact(openingMargin, releasedMargin));
        CorePositionState position = new CorePositionState(order.symbol(), reservation.asset(),
                nextQuantity == 0 ? 0 : order.instrumentVersion(), nextQuantity, nextEntryPrice, nextEntryValue,
                Math.addExact(current == null ? 0 : current.realizedPnlUnits(), realizedPnl), nextMargin);
        Map<Long, OrderReservation> reservations = new TreeMap<>(user.reservations());
        reservations.put(order.orderId(), nextReservation);
        Map<String, CorePositionState> positions = new TreeMap<>(user.positions());
        positions.put(order.symbol(), position);
        return new DerivativeFillResult(new CoreUserState(user.productLine(), user.userId(),
                Math.incrementExact(user.revision()), balances, reservations, positions), treasury);
    }

    private static CashResult applyCash(AssetBalance balance, long delta) {
        if (delta >= 0) {
            return new CashResult(delta == 0 ? balance : balance.credit(delta), delta);
        }
        long debit = Math.min(balance.availableUnits(), Math.negateExact(delta));
        return new CashResult(debit == 0 ? balance : balance.adjustAvailable(Math.negateExact(debit)),
                Math.negateExact(debit));
    }

    private static long proportional(long units, long part, long total) {
        return part == total ? units : Math.multiplyExact(units, part) / total;
    }

    private record DerivativeFillResult(CoreUserState user, CoreTreasuryState treasury) {
    }

    private record CashResult(AssetBalance balance, long appliedDelta) {
    }

    private static CoreUserState releaseTerminalReservation(CoreUserState user, long orderId) {
        OrderReservation reservation = requireReservation(user, orderId);
        long releaseUnits = reservation.remainingUnits();
        if (releaseUnits == 0) {
            return user;
        }
        Map<String, AssetBalance> balances = new TreeMap<>(user.balances());
        balances.put(reservation.asset(), requireBalance(user, reservation.asset()).release(releaseUnits));
        Map<Long, OrderReservation> reservations = new TreeMap<>(user.reservations());
        reservations.put(orderId, reservation.releaseAll());
        return new CoreUserState(user.productLine(), user.userId(), Math.incrementExact(user.revision()),
                balances, reservations, user.positions());
    }

    private static OrderReservation requireReservation(CoreUserState user, long orderId) {
        OrderReservation reservation = user.reservations().get(orderId);
        if (reservation == null) {
            throw new IllegalStateException("open order reservation is missing orderId=" + orderId);
        }
        return reservation;
    }

    private static AssetBalance requireBalance(CoreUserState user, String asset) {
        AssetBalance balance = user.balances().get(asset);
        if (balance == null) {
            throw new IllegalStateException("reservation balance is missing asset=" + asset);
        }
        return balance;
    }

    private static TradingCoreState replaceUser(
            TradingCoreState state,
            CoreUserState user,
            Map<Long, CoreOrderState> orders,
            CoreBookState bookState) {
        Map<Long, CoreUserState> users = new TreeMap<>(state.users());
        users.put(user.userId(), user);
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users, orders,
                bookState, state.instruments(), state.riskState(), state.treasuryState());
    }

    private static void validateReservationRule(TradingCoreState state, PlaceOrderCommand command) {
        String reservationAsset = AssetBalance.normalizeAsset(command.reservationAsset());
        if (state.productLine().isDerivative()) {
            if (command.reservationKind() != ReservationKind.DERIVATIVE_MARGIN) {
                throw new CoreStateRejectedException("INVALID_RESERVATION_KIND",
                        "derivative orders require DERIVATIVE_MARGIN");
            }
            String settleAsset = AssetBalance.normalizeAsset(command.settleAsset());
            if (!reservationAsset.equals(settleAsset)) {
                throw new CoreStateRejectedException("INVALID_DERIVATIVE_RESERVATION_ASSET",
                        "derivative orders reserve the instrument settle asset");
            }
            return;
        }
        if (command.reservationKind() != ReservationKind.SPOT_ASSET) {
            throw new CoreStateRejectedException("INVALID_RESERVATION_KIND",
                    "spot orders require SPOT_ASSET");
        }
        String expectedAsset = AssetBalance.normalizeAsset(command.side() == CoreOrderSide.BUY
                ? command.quoteAsset() : command.baseAsset());
        if (!reservationAsset.equals(expectedAsset)) {
            throw new CoreStateRejectedException("INVALID_SPOT_RESERVATION_ASSET",
                    "spot buy reserves quote asset and spot sell reserves base asset");
        }
    }

    private static void validateInstrumentOrder(CoreInstrumentState instrument, PlaceOrderCommand command) {
        if (!instrument.baseAsset().equals(AssetBalance.normalizeAsset(command.baseAsset()))
                || !instrument.quoteAsset().equals(AssetBalance.normalizeAsset(command.quoteAsset()))
                || !instrument.settleAsset().equals(AssetBalance.normalizeAsset(command.settleAsset()))) {
            throw new CoreStateRejectedException("INSTRUMENT_ORDER_MISMATCH",
                    "order assets do not match instrument state");
        }
        if (command.priceTicks() <= 0) {
            throw new CoreStateRejectedException("INVALID_ORDER_PRICE", "order price must be positive");
        }
    }

    private static long requiredReservationUnits(
            CoreInstrumentState instrument,
            CoreUserState user,
            PlaceOrderCommand command) {
        if (instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT) {
            return command.side() == CoreOrderSide.BUY
                    ? Math.multiplyExact(command.priceTicks(), command.quantitySteps())
                    : command.quantitySteps();
        }
        CorePositionState position = user.positions().get(instrument.symbol());
        long currentQuantity = position == null ? 0 : position.signedQuantitySteps();
        long signedOrder = command.side() == CoreOrderSide.BUY
                ? command.quantitySteps() : Math.negateExact(command.quantitySteps());
        long closeSteps = currentQuantity != 0 && Long.signum(currentQuantity) != Long.signum(signedOrder)
                ? Math.min(Math.absExact(currentQuantity), command.quantitySteps()) : 0;
        long openSteps = command.reduceOnly() ? 0 : Math.subtractExact(command.quantitySteps(), closeSteps);
        long margin = CoreContractMath.openingMarginUnits(instrument, command.side(), command.priceTicks(),
                openSteps);
        long premium = instrument.contractType().isOption() && command.side() == CoreOrderSide.BUY
                ? CoreContractMath.optionPremiumUnits(instrument, command.priceTicks(), command.quantitySteps()) : 0;
        long fee = CoreContractMath.feeDeltaUnits(instrument, command.priceTicks(), command.quantitySteps(), true);
        return Math.max(1, Math.addExact(Math.addExact(margin, premium), Math.max(0, Math.negateExact(fee))));
    }

    private static void validateReduceOnlyCapacity(
            TradingCoreState state,
            CoreUserState user,
            PlaceOrderCommand command) {
        if (!command.reduceOnly()) {
            return;
        }
        if (!state.productLine().isDerivative()) {
            throw new CoreStateRejectedException("REDUCE_ONLY_UNSUPPORTED", "spot orders cannot be reduce-only");
        }
        CorePositionState position = user.positions().get(OrderReservation.normalizeSymbol(command.symbol()));
        if (position == null || position.signedQuantitySteps() == 0
                || (position.signedQuantitySteps() > 0) == (command.side() == CoreOrderSide.BUY)) {
            throw new CoreStateRejectedException("REDUCE_ONLY_REQUIRES_POSITION_STATE",
                    "reduce-only side must close an existing position");
        }
        long alreadyOpen = state.orders().values().stream()
                .filter(order -> order.userId() == user.userId() && order.reduceOnly()
                        && order.status() == CoreOrderStatus.OPEN && order.symbol().equals(position.symbol())
                        && order.side() == command.side())
                .mapToLong(CoreOrderState::remainingQuantitySteps)
                .reduce(0L, Math::addExact);
        long capacity = Math.subtractExact(Math.absExact(position.signedQuantitySteps()), alreadyOpen);
        if (command.quantitySteps() > capacity) {
            throw new CoreStateRejectedException("REDUCE_ONLY_CAPACITY_EXCEEDED",
                    "reduce-only open quantity exceeds position capacity");
        }
    }

    private static void requireUserId(long userId) {
        if (userId <= 0) {
            throw new CoreStateRejectedException("INVALID_USER_ID", "userId must be positive");
        }
    }
}
