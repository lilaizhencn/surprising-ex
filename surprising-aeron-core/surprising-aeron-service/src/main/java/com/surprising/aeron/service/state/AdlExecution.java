package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.instrument.CoreInstrumentState;

import com.surprising.aeron.protocol.ExecuteAdlCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.math.CoreContractMath;
import com.surprising.aeron.service.state.math.OptionContractMath;
import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreMarkPriceState;
import com.surprising.aeron.service.state.model.CorePositionState;
import com.surprising.aeron.service.state.model.CoreRiskState;

import java.util.Map;

import static com.surprising.aeron.service.state.ReducerSettlementSupport.positionKey;
import static com.surprising.aeron.service.state.ReducerSettlementSupport.proportional;
import static com.surprising.aeron.service.state.ReducerSettlementSupport.requireBalance;

/** Owns one atomic ADL target deleveraging and its liquidation state update. */
final class AdlExecution {

    private AdlExecution() {
    }

    static TradingCoreState execute(TradingCoreState state, ExecuteAdlCommand command) {
        CoreLiquidationState liquidation = state.riskState().liquidations().get(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        if (liquidation.status() != CoreLiquidationState.Status.ADL_REQUIRED) {
            throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT", "ADL requires ADL state");
        }
        if (!liquidation.symbol().equals(command.symbol()) || command.targetUserId() == liquidation.userId()
                || command.coveredUnits() > liquidation.deficitUnits()) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "ADL command does not match liquidation");
        }
        CoreInstrumentState instrument = requireInstrument(state, liquidation.symbol(),
                liquidation.instrumentChangeId());
        CoreMarkPriceState mark = state.riskState().markPrices().get(liquidation.symbol());
        if (mark == null || mark.priceSequence() != command.markPriceSequence()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "ADL mark price changed");
        }
        CoreUserState target = state.user(command.targetUserId());
        String positionKey = positionKey(command.symbol(), command.positionSide());
        CorePositionState position = target == null ? null : target.positions().get(positionKey);
        if (position == null || position.marginMode() != command.marginMode()
                || position.signedQuantitySteps() != command.expectedSignedQuantitySteps()
                || position.entryPriceTicks() != command.expectedEntryPriceTicks()
                || Long.signum(position.signedQuantitySteps()) == Long.signum(liquidation.signedQuantitySteps())) {
            throw new CoreStateRejectedException("ADL_POSITION_CONFLICT", "ADL target position changed");
        }
        long totalProfit = CoreContractMath.pnlUnits(instrument, position.signedQuantitySteps(),
                position.entryPriceTicks(), mark.markPriceTicks());
        long coverCapacity = totalProfit <= 0 ? 0 : proportional(totalProfit, command.closeQuantitySteps(),
                Math.absExact(position.signedQuantitySteps()));
        if (coverCapacity < command.coveredUnits()) {
            throw new CoreStateRejectedException("ADL_PROFIT_INSUFFICIENT", "ADL target profit is insufficient");
        }
        long currentAbs = Math.absExact(position.signedQuantitySteps());
        long remainingAbs = Math.subtractExact(currentAbs, command.closeQuantitySteps());
        long nextQuantity = remainingAbs == 0 ? 0
                : position.signedQuantitySteps() > 0 ? remainingAbs : Math.negateExact(remainingAbs);
        long releasedMargin = proportional(position.positionMarginUnits(), command.closeQuantitySteps(), currentAbs);
        AssetBalance balance = requireBalance(target, instrument.settleAsset());
        if (releasedMargin > 0) balance = balance.release(releasedMargin);
        long closeCashDelta = instrument.contractType().isOption()
                ? OptionContractMath.optionMarketValueUnits(instrument,
                position.signedQuantitySteps() > 0 ? command.closeQuantitySteps()
                        : Math.negateExact(command.closeQuantitySteps()), mark.markPriceTicks())
                : coverCapacity;
        long targetCashDelta = Math.subtractExact(closeCashDelta, command.coveredUnits());
        if (targetCashDelta != 0) balance = balance.adjustAvailable(targetCashDelta);
        CoreTreasuryState treasury = state.treasuryState()
                .adjustClearingPnl(instrument.settleAsset(), Math.negateExact(targetCashDelta))
                .adjustDeficit(instrument.settleAsset(), Math.negateExact(command.coveredUnits()))
                .adjustClearingPnl(instrument.settleAsset(), Math.negateExact(command.coveredUnits()));
        Map<String, AssetBalance> balances = StateMapSupport.delta(target.balances());
        balances.put(instrument.settleAsset(), balance);
        long nextEntryValue = remainingAbs == 0 ? 0
                : proportional(position.entryValueTicks(), remainingAbs, currentAbs);
        Map<String, CorePositionState> positions = StateMapSupport.delta(target.positions());
        positions.put(positionKey, new CorePositionState(position.symbol(), position.marginAsset(),
                position.marginMode(), position.positionSide(), remainingAbs == 0 ? 0 : position.instrumentChangeId(),
                nextQuantity, remainingAbs == 0 ? 0 : position.entryPriceTicks(), nextEntryValue,
                Math.addExact(position.realizedPnlUnits(), instrument.contractType().isOption() ? 0 : coverCapacity),
                Math.subtractExact(position.positionMarginUnits(), releasedMargin)));
        CoreUserState nextTarget = target.transition(Math.incrementExact(target.revision()),
                balances, target.reservations(), positions, target.positionMode());
        Map<Long, CoreUserState> users = StateMapSupport.delta(state.users());
        users.put(nextTarget.userId(), nextTarget);
        CoreLiquidationState.Status nextStatus = command.coveredUnits() == liquidation.deficitUnits()
                ? CoreLiquidationState.Status.COMPLETED : CoreLiquidationState.Status.ADL_REQUIRED;
        Map<Long, CoreLiquidationState> liquidations = StateMapSupport.delta(state.riskState().liquidations());
        liquidations.put(liquidation.liquidationId(), liquidation.covered(command.coveredUnits(), nextStatus));
        CoreRiskState risk = new CoreRiskState(state.riskState().markPrices(), state.riskState().snapshots(),
                liquidations, state.riskState().scans(), state.riskState().nextLiquidationId(),
                state.riskState().scanControl(), state.riskState().marketRevision());
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()), users,
                state.orders(), state.instruments(), risk, treasury,
                state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(), state.clientOrderIndex(),
                state.triggerOrders());
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
