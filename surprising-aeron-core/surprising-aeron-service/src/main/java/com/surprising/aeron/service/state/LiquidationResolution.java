package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreRiskState;

import java.util.Map;

/** Owns insurance coverage and terminal resolution of a liquidation deficit. */
final class LiquidationResolution {

    private LiquidationResolution() {
    }

    static TradingCoreState resolve(TradingCoreState state, ResolveLiquidationCommand command) {
        CoreLiquidationState liquidation = state.riskState().liquidations().get(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        CoreInstrumentState instrument = requireInstrument(state, liquidation.symbol(),
                liquidation.instrumentChangeId());
        CoreLiquidationState.Status nextStatus;
        CoreTreasuryState treasury = state.treasuryState();
        switch (command.resolution()) {
            case INSURANCE -> {
                if (liquidation.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED) {
                    throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT",
                            "insurance resolution requires insurance state");
                }
                if (command.coveredUnits() > liquidation.deficitUnits()) {
                    throw new CoreStateRejectedException("INSURANCE_COVER_EXCEEDS_DEFICIT",
                            "insurance coverage must be within liquidation deficit");
                }
                long available = treasury.insuranceBalances().getOrDefault(instrument.settleAsset(), 0L);
                if (!InsuranceAllocationPolicy.isNext(state, liquidation.liquidationId())) {
                    throw new CoreStateRejectedException("INSURANCE_RESOLUTION_ORDER_MISMATCH",
                            "insurance claims must resolve in deterministic priority order");
                }
                long expectedCoverage = InsuranceAllocationPolicy.expectedCoverage(state, liquidation.liquidationId());
                if (command.coveredUnits() != expectedCoverage) {
                    throw new CoreStateRejectedException("INSURANCE_ALLOCATION_MISMATCH",
                            "insurance coverage does not match deterministic allocation");
                }
                if (command.coveredUnits() > available) {
                    throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                            "insurance fund balance is insufficient");
                }
                if (command.coveredUnits() != 0) {
                    treasury = treasury.adjustInsurance(instrument.settleAsset(),
                            Math.negateExact(command.coveredUnits())).adjustDeficit(
                            instrument.settleAsset(), Math.negateExact(command.coveredUnits()));
                }
                nextStatus = command.coveredUnits() == liquidation.deficitUnits()
                        ? CoreLiquidationState.Status.COMPLETED : CoreLiquidationState.Status.ADL_REQUIRED;
            }
            case ADL -> throw new CoreStateRejectedException("INVALID_COMMAND",
                    "ADL resolution requires atomic target deleveraging");
            case COMPLETED -> {
                if (command.coveredUnits() != 0) {
                    throw new CoreStateRejectedException("INVALID_COMMAND", "completed resolution covers no units");
                }
                if (liquidation.deficitUnits() != 0) {
                    throw new CoreStateRejectedException("LIQUIDATION_DEFICIT_REMAINS",
                            "liquidation deficit must be fully covered before completion");
                }
                nextStatus = CoreLiquidationState.Status.COMPLETED;
            }
            default -> throw new IllegalStateException("unknown liquidation resolution");
        }
        Map<Long, CoreLiquidationState> liquidations = StateMapSupport.delta(state.riskState().liquidations());
        CoreLiquidationState nextLiquidation = command.resolution() == ResolveLiquidationCommand.Resolution.COMPLETED
                ? liquidation.withStatus(nextStatus) : liquidation.covered(command.coveredUnits(), nextStatus);
        liquidations.put(liquidation.liquidationId(), nextLiquidation);
        CoreRiskState risk = new CoreRiskState(state.riskState().markPrices(), state.riskState().snapshots(),
                liquidations, state.riskState().scans(), state.riskState().nextLiquidationId(),
                state.riskState().scanControl(), state.riskState().marketRevision());
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                state.users(), state.orders(), state.instruments(), risk, treasury,
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
