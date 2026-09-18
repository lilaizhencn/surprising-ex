package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.math.CoreContractMath;
import com.surprising.aeron.service.state.math.OptionContractMath;
import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreMarkPriceState;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CorePositionState;
import com.surprising.aeron.service.state.model.CoreRiskSnapshot;
import com.surprising.aeron.service.state.model.CoreRiskState;
import com.surprising.aeron.service.state.model.CoreRiskStatus;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.surprising.aeron.service.state.ReducerSettlementSupport.positionKey;
import static com.surprising.aeron.service.state.ReducerSettlementSupport.proportional;
import static com.surprising.aeron.service.state.ReducerSettlementSupport.requireBalance;
import static com.surprising.aeron.service.state.ReducerSettlementSupport.applyLiquidationCash;

/** Owns cancellation and cash/position application for one liquidation execution. */
final class LiquidationExecution {

    private LiquidationExecution() {
    }

    static TradingCoreState advanceCancellation(TradingCoreState state,
                                                ExecuteLiquidationCommand command,
                                                Collection<CoreOrderState> orders,
                                                long nextCursorOrderId) {
        if (nextCursorOrderId <= 0) throw new IllegalArgumentException("liquidation cursor must advance");
        CoreLiquidationState liquidation = state.riskState().liquidations().get(command.liquidationId());
        if (liquidation == null) throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND",
                "liquidation plan does not exist");
        if (liquidation.status() == CoreLiquidationState.Status.ORDERED
                && liquidation.nextCancelOrderId() != command.cursorOrderId()) {
            throw new CoreStateRejectedException("LIQUIDATION_CURSOR_CONFLICT",
                    "liquidation cancellation cursor does not match state");
        }
        TradingCoreState canceled = OrderStateTransitions.cancelOrders(state,
                orders == null ? List.of() : List.copyOf(orders));
        Map<Long, CoreLiquidationState> liquidations = StateMapSupport.delta(canceled.riskState().liquidations());
        liquidations.put(command.liquidationId(), liquidation.ordered(nextCursorOrderId));
        CoreRiskState risk = new CoreRiskState(canceled.riskState().markPrices(), canceled.riskState().snapshots(),
                liquidations, canceled.riskState().scans(), canceled.riskState().nextLiquidationId(),
                canceled.riskState().scanControl(), state.riskState().marketRevision());
        return new TradingCoreState(canceled.productLine(), Math.incrementExact(canceled.revision()), canceled.users(),
                canceled.orders(), canceled.instruments(), risk, canceled.treasuryState(),
                canceled.leverages(), canceled.algoOrders(), canceled.cancelAllAfterTimers(),
                canceled.clientOrderIndex(), canceled.triggerOrders());
    }

    static TradingCoreState execute(TradingCoreState state, ExecuteLiquidationCommand command,
                                    boolean cancelOpenOrders) {
        CoreLiquidationState liquidation = state.riskState().liquidations().get(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        if (liquidation.status() != CoreLiquidationState.Status.PLANNED
                && liquidation.status() != CoreLiquidationState.Status.ORDERED) {
            throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT", "liquidation is not planned");
        }
        validatePrice(state, liquidation, command);
        if (!isExecutable(state, liquidation)) {
            return cancel(state, liquidation);
        }
        CoreInstrumentState instrument = requireInstrument(state, liquidation.symbol(),
                liquidation.instrumentChangeId());
        CoreUserState user = state.user(liquidation.userId());
        String positionKey = positionKey(liquidation.symbol(), liquidation.positionSide());
        CorePositionState position = user.positions().get(positionKey);
        TradingCoreState canceled = cancelOpenOrders
                ? OrderStateTransitions.cancelUserSymbolOrders(state, user.userId(), liquidation.symbol()) : state;
        user = canceled.user(user.userId());
        position = user.positions().get(positionKey);
        AssetBalance balance = requireBalance(user, instrument.settleAsset());
        long currentAbs = Math.absExact(position.signedQuantitySteps());
        long closeQuantity = liquidation.closeQuantitySteps();
        long remainingAbs = Math.subtractExact(currentAbs, closeQuantity);
        long releasedMargin = position.positionMarginUnits() == 0 ? 0
                : proportional(position.positionMarginUnits(), closeQuantity, currentAbs);
        long signedCloseQuantity = position.signedQuantitySteps() > 0
                ? closeQuantity : Math.negateExact(closeQuantity);
        long pnl = instrument.contractType().isOption()
                ? OptionContractMath.optionMarketValueUnits(instrument, signedCloseQuantity,
                command.executionPriceTicks())
                : CoreContractMath.pnlUnits(instrument, signedCloseQuantity,
                position.entryPriceTicks(), command.executionPriceTicks());
        long feeDue = Math.negateExact(CoreContractMath.feeDeltaUnits(instrument,
                command.executionPriceTicks(), liquidation.closeQuantitySteps(), command.liquidationFeeRatePpm()));
        ReducerSettlementSupport.LiquidationCashResult cash = applyLiquidationCash(balance,
                liquidation.marginMode(), releasedMargin, pnl,
                feeDue);
        long uncovered = pnl < 0 ? Math.subtractExact(Math.negateExact(pnl),
                Math.negateExact(Math.min(0, cash.appliedDelta()))) : 0;
        long collectedFee = cash.collectedFeeUnits();
        CoreTreasuryState treasury = canceled.treasuryState()
                .adjustInsurance(instrument.settleAsset(),
                        Math.addExact(Math.negateExact(cash.appliedDelta()), collectedFee))
                .adjustDeficit(instrument.settleAsset(), uncovered)
                .adjustClearingPnl(instrument.settleAsset(), uncovered);
        Map<String, AssetBalance> balances = StateMapSupport.delta(user.balances());
        balances.put(instrument.settleAsset(), cash.balance());
        Map<String, CorePositionState> positions = StateMapSupport.delta(user.positions());
        long nextQuantity = remainingAbs == 0 ? 0
                : position.signedQuantitySteps() > 0 ? remainingAbs : Math.negateExact(remainingAbs);
        long nextEntryValue = remainingAbs == 0 ? 0
                : proportional(position.entryValueTicks(), remainingAbs, currentAbs);
        positions.put(positionKey, new CorePositionState(instrument.symbol(), instrument.settleAsset(),
                position.marginMode(), position.positionSide(), remainingAbs == 0 ? 0 : position.instrumentChangeId(),
                nextQuantity, remainingAbs == 0 ? 0 : position.entryPriceTicks(), nextEntryValue,
                Math.addExact(position.realizedPnlUnits(), instrument.contractType().isOption() ? 0 : pnl),
                Math.subtractExact(position.positionMarginUnits(), releasedMargin)));
        CoreUserState nextUser = user.transition(Math.incrementExact(user.revision()), balances,
                user.reservations(), positions, user.positionMode());
        Map<Long, CoreUserState> users = StateMapSupport.delta(canceled.users());
        users.put(nextUser.userId(), nextUser);
        Map<Long, CoreLiquidationState> liquidations = StateMapSupport.delta(canceled.riskState().liquidations());
        liquidations.put(liquidation.liquidationId(), liquidation.executed(uncovered,
                command.executionPriceTicks(), command.liquidationFeeRatePpm(), collectedFee));
        CoreRiskState risk = new CoreRiskState(canceled.riskState().markPrices(), canceled.riskState().snapshots(),
                liquidations, canceled.riskState().scans(), canceled.riskState().nextLiquidationId(),
                canceled.riskState().scanControl(), state.riskState().marketRevision());
        return new TradingCoreState(canceled.productLine(), Math.incrementExact(canceled.revision()), users,
                canceled.orders(), canceled.instruments(), risk, treasury,
                canceled.leverages(), canceled.algoOrders(), canceled.cancelAllAfterTimers(),
                canceled.clientOrderIndex(), canceled.triggerOrders());
    }

    static boolean isExecutable(TradingCoreState state, ExecuteLiquidationCommand command) {
        CoreLiquidationState liquidation = state.riskState().liquidations().get(command.liquidationId());
        if (liquidation == null || (liquidation.status() != CoreLiquidationState.Status.PLANNED
                && liquidation.status() != CoreLiquidationState.Status.ORDERED)) return false;
        validatePrice(state, liquidation, command);
        return isExecutable(state, liquidation);
    }

    private static boolean isExecutable(TradingCoreState state, CoreLiquidationState liquidation) {
        CoreInstrumentState instrument = state.instruments().get(liquidation.symbol());
        if (instrument == null || !CoreRiskPolicy.canLiquidate(
                instrument.contractType(), liquidation.signedQuantitySteps())) return false;
        CoreUserState user = state.user(liquidation.userId());
        CorePositionState position = user == null ? null
                : user.positions().get(positionKey(liquidation.symbol(), liquidation.positionSide()));
        CoreRiskSnapshot risk = state.riskState().snapshots().get(
                riskKey(liquidation.userId(), liquidation.symbol(), liquidation.positionSide()));
        return position != null && position.instrumentChangeId() == liquidation.instrumentChangeId()
                && position.marginMode() == liquidation.marginMode()
                && position.signedQuantitySteps() == liquidation.signedQuantitySteps()
                && risk != null && risk.priceSequence() == liquidation.triggerPriceSequence()
                && risk.status() == CoreRiskStatus.LIQUIDATION;
    }

    private static void validatePrice(TradingCoreState state, CoreLiquidationState liquidation,
                                      ExecuteLiquidationCommand command) {
        CoreMarkPriceState mark = state.riskState().markPrices().get(liquidation.symbol());
        if (mark == null || mark.priceSequence() != liquidation.triggerPriceSequence()
                || command.triggerPriceSequence() > 0
                && command.triggerPriceSequence() != liquidation.triggerPriceSequence()
                || command.executionPriceTicks() != mark.markPriceTicks()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "liquidation mark price changed");
        }
    }

    private static TradingCoreState cancel(TradingCoreState state, CoreLiquidationState liquidation) {
        Map<Long, CoreLiquidationState> liquidations = StateMapSupport.delta(state.riskState().liquidations());
        liquidations.put(liquidation.liquidationId(), liquidation.canceled());
        CoreRiskState risk = new CoreRiskState(state.riskState().markPrices(), state.riskState().snapshots(),
                liquidations, state.riskState().scans(), state.riskState().nextLiquidationId(),
                state.riskState().scanControl(), state.riskState().marketRevision());
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                state.users(), state.orders(), state.instruments(), risk, state.treasuryState(),
                state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(), state.clientOrderIndex(),
                state.triggerOrders());
    }

    private static String riskKey(long userId, String symbol, CorePositionSide positionSide) {
        return positionSide == CorePositionSide.NET
                ? userId + ":" + symbol : userId + ":" + symbol + ":" + positionSide.name();
    }

    private static CoreInstrumentState requireInstrument(TradingCoreState state, String symbol, long version) {
        CoreInstrumentState instrument = state.instruments().get(OrderReservation.normalizeSymbol(symbol));
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        if (instrument.changeId() != version) {
            throw new CoreStateRejectedException("INSTRUMENT_CHANGE_ID_CONFLICT", "instrument version differs");
        }
        return instrument;
    }
}
