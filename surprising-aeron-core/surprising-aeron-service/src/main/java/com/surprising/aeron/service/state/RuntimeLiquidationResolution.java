package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.instrument.CoreInstrumentState;

import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.model.CoreLiquidationState;

/**
 * Owns insurance coverage and completion decisions after liquidation execution.
 *
 * <p>This stage validates deterministic insurance allocation and changes only the
 * liquidation status plus treasury deficit/insurance balances. Account Lane
 * ordering remains explicit in the reusable resolution continuation.</p>
 */
public final class RuntimeLiquidationResolution {

    private RuntimeLiquidationResolution() {
    }

    public static TradingRuntimeState apply(TradingCoreState before,
                                            ResolveLiquidationCommand command,
                                            TradingRuntimeState runtime,
                                            RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid liquidation resolution apply");
        }
        return applyRuntime(command, runtime, identities, before.riskState().liquidations().keySet());
    }

    public static TradingRuntimeState applyRuntime(ResolveLiquidationCommand command,
                                                   TradingRuntimeState runtime,
                                                   RuntimeIdentityRegistry identities,
                                                   Iterable<Long> candidateIds) {
        resolve(null, command, runtime, identities, candidateIds, false);
        return runtime;
    }

    public static java.util.function.BooleanSupplier begin(ResolveLiquidationCommand command,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, Iterable<Long> candidateIds) {
        return begin(null, command, runtime, identities, candidateIds);
    }

    /** Re-arm a slot-owned resolution continuation while retaining its treasury delta storage. */
    public static ResolutionWork begin(ResolutionWork reuse, ResolveLiquidationCommand command,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, Iterable<Long> candidateIds) {
        return resolve(reuse, command, runtime, identities, candidateIds, true);
    }

    private static ResolutionWork resolve(ResolutionWork reuse, ResolveLiquidationCommand command,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, Iterable<Long> candidateIds,
            boolean asynchronous) {
        if (command == null || runtime == null || identities == null || !runtime.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid liquidation resolution apply");
        }
        runtime.assertOwner();
        LiquidationRuntime liquidation = runtime.liquidation(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        CoreInstrumentState instrument = requireInstrument(runtime, identities.symbol(liquidation.symbolId()),
                liquidation.instrumentChangeId());
        LiquidationRuntime current = liquidation;
        ResolutionWork work = reuse == null ? new ResolutionWork() : reuse;
        CoreLiquidationState.Status nextStatus;
        long nextDeficit = liquidation.deficitUnits();
        RuntimeTreasuryDelta treasuryDelta = work.delta();
        treasuryDelta.clear();
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
                int assetId = identities.assetId(instrument.settleAsset());
                InsuranceAllocationPolicy.Resolution allocation = InsuranceAllocationPolicy.resolve(
                        runtime, identities, candidateIds, liquidation.liquidationId());
                if (!allocation.next()) {
                    throw new CoreStateRejectedException("INSURANCE_RESOLUTION_ORDER_MISMATCH",
                            "insurance claims must resolve in deterministic priority order");
                }
                long expectedCoverage = allocation.expectedCoverage();
                if (command.coveredUnits() != expectedCoverage) {
                    throw new CoreStateRejectedException("INSURANCE_ALLOCATION_MISMATCH",
                            "insurance coverage does not match deterministic allocation");
                }
                if (command.coveredUnits() > runtime.treasury().insurance(assetId)) {
                    throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                            "insurance fund balance is insufficient");
                }
                if (command.coveredUnits() != 0) {
                    treasuryDelta.addInsurance(assetId, Math.negateExact(command.coveredUnits()));
                    treasuryDelta.addDeficit(assetId, Math.negateExact(command.coveredUnits()));
                }
                nextDeficit = Math.subtractExact(nextDeficit, command.coveredUnits());
                nextStatus = nextDeficit == 0 ? CoreLiquidationState.Status.COMPLETED
                        : CoreLiquidationState.Status.ADL_REQUIRED;
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
        LiquidationRuntime nextLiquidation = new LiquidationRuntime(current.liquidationId(), current.userId(),
                current.symbolId(), current.marginMode(), current.positionSide(), current.instrumentChangeId(),
                current.triggerPriceSequence(), current.signedQuantitySteps(), current.closeQuantitySteps(),
                nextDeficit, current.executionPriceTicks(), current.liquidationFeeRatePpm(),
                current.liquidationFeeUnits(), nextStatus, 0);
        long laneMask = runtime.topology().accountLaneMask(current.userId());
        work.prepare(runtime, nextLiquidation, laneMask);
        if (asynchronous) {
            runtime.dispatchControlLanes(laneMask, work);
            return work;
        }
        runtime.releaseOwnerLaneAccess();
        runtime.executeLaneMutations(laneMask, 1, false, work);
        work.finish();
        return null;
    }

    /** One account-lane mutation plus the owner-side insurance delta for a resolution. */
    public static final class ResolutionWork
            implements java.util.function.BooleanSupplier, java.util.function.IntFunction<Object> {
        private final RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
        private TradingRuntimeState runtime;
        private LiquidationRuntime nextLiquidation;
        private boolean done;

        private RuntimeTreasuryDelta delta() { return treasuryDelta; }

        private void prepare(TradingRuntimeState runtime, LiquidationRuntime nextLiquidation, long laneMask) {
            this.runtime = runtime;
            this.nextLiquidation = nextLiquidation;
            this.done = false;
        }

        @Override
        public Object apply(int ignored) {
            runtime.replaceLiquidation(nextLiquidation);
            return null;
        }

        @Override
        public boolean getAsBoolean() {
            if (done) return true;
            if (!runtime.pollControlLanes()) return false;
            finish();
            return true;
        }

        private void finish() {
            if (done) return;
            treasuryDelta.apply(runtime.treasury());
            runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
            done = true;
        }
    }

    private static CoreInstrumentState requireInstrument(TradingRuntimeState runtime, String symbol, long version) {
        CoreInstrumentState instrument = runtime.instrument(symbol);
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        if (instrument.changeId() != version) {
            throw new CoreStateRejectedException("INSTRUMENT_CHANGE_ID_CONFLICT", "instrument version differs");
        }
        return instrument;
    }
}
