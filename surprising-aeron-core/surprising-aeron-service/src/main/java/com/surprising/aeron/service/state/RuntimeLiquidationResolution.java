package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.instrument.CoreInstrument;

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
        if (runtime.systemLedgerStarted()) throw new IllegalStateException("active resolution requires its asynchronous continuation");
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
        if (asynchronous) {
            var route = runtime.liquidationRoute(command.liquidationId());
            if (route == null) throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation route is missing");
            CoreInstrument instrument = requireInstrument(runtime, route.instrumentId());
            int assetId = identities.assetId(instrument.settleAsset());
            ResolutionWork work = reuse == null || !reuse.reusable() ? new ResolutionWork() : reuse;
            work.sourceUserId = route.userId();
            work.prepare(runtime, command, null, assetId, null);
            work.prepareClaimReads(candidateIds, identities);
            return work;
        }
        LiquidationRuntime liquidation = runtime.liquidation(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        CoreInstrument instrument = requireInstrument(runtime, identities.instrumentId(liquidation.symbolId()));
        int assetId = identities.assetId(instrument.settleAsset());
        InsuranceAllocationPolicy.ResolutionInputs allocation = command.resolution() == ResolveLiquidationCommand.Resolution.INSURANCE
                ? InsuranceAllocationPolicy.prepareResolution(runtime, identities, candidateIds, liquidation, assetId) : null;
        ResolutionWork work = reuse == null || !reuse.reusable() ? new ResolutionWork() : reuse;
        work.prepare(runtime, command, liquidation, assetId, allocation);
        if (asynchronous) return work;
        work.applyLedger(runtime.treasury());
        runtime.releaseOwnerLaneAccess();
        runtime.executeLaneMutations(work.laneMask, 1, work);
        work.finish();
        return null;
    }

    /** 账本先判定预算并记账；账户Lane随后发布自己的强平终态。 */
    public static final class ResolutionWork extends SystemLedgerCommand
            implements java.util.function.BooleanSupplier, java.util.function.IntFunction<Object> {
        private final RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
        private TradingRuntimeState runtime;
        private ResolveLiquidationCommand command;
        private LiquidationRuntime liquidation, nextLiquidation;
        private InsuranceAllocationPolicy.ResolutionInputs allocation;
        private int assetId;
        private long laneMask;
        private boolean done, ledgerDispatched, accountsDispatched;
        private long sourceUserId;
        private boolean capturingClaims;
        private long[] claimIds = new long[4];
        private int[] claimLanes = new int[4];
        private LiquidationRuntime[] claimReceipts = new LiquidationRuntime[4];
        private int claimCount;

        private void prepare(TradingRuntimeState runtime, ResolveLiquidationCommand command,
                LiquidationRuntime liquidation, int assetId, InsuranceAllocationPolicy.ResolutionInputs allocation) {
            initializeLedgerCommand(runtime);
            runtime.requireRevisionCapacity();
            this.runtime = runtime; this.command = command; this.liquidation = liquidation;
            this.assetId = assetId; this.allocation = allocation;
            laneMask = runtime.topology().accountLaneMask(liquidation == null ? sourceUserId : liquidation.userId());
            nextLiquidation = null;
            treasuryDelta.clear();
            done = ledgerDispatched = accountsDispatched = capturingClaims = false;
        }

        private void prepareClaimReads(Iterable<Long> candidateIds, RuntimeIdentityRegistry identities) {
            claimCount = 0;
            long readMask = laneMask;
            if (command.resolution() == ResolveLiquidationCommand.Resolution.INSURANCE) {
                for (long id : candidateIds) {
                    var route = runtime.liquidationRoute(id);
                    if (route == null) continue;
                    var instrument = runtime.instrument(route.instrumentId());
                    if (instrument == null || identities.assetId(instrument.settleAsset()) != assetId) continue;
                    if (claimCount == claimIds.length) {
                        int capacity = Math.multiplyExact(claimCount, 2);
                        claimIds = java.util.Arrays.copyOf(claimIds, capacity);
                        claimLanes = java.util.Arrays.copyOf(claimLanes, capacity);
                        claimReceipts = java.util.Arrays.copyOf(claimReceipts, capacity);
                    }
                    int laneId = runtime.topology().accountLaneId(route.userId());
                    claimIds[claimCount] = id; claimLanes[claimCount] = laneId; claimReceipts[claimCount++] = null;
                    readMask |= 1L << laneId;
                }
            }
            capturingClaims = true;
            runtime.dispatchControlLanes(readMask, this);
        }

        @Override protected void applyLedger(TreasuryRuntime ledger) {
            if (command.resolution() == ResolveLiquidationCommand.Resolution.INSURANCE && allocation == null)
                allocation = InsuranceAllocationPolicy.prepareResolution(claimReceipts, claimCount, liquidation, assetId);
            long nextDeficit = liquidation.deficitUnits();
            CoreLiquidationState.Status nextStatus;
            switch (command.resolution()) {
                case INSURANCE -> {
                    if (liquidation.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED)
                        throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT", "insurance resolution requires insurance state");
                    if (command.coveredUnits() > liquidation.deficitUnits())
                        throw new CoreStateRejectedException("INSURANCE_COVER_EXCEEDS_DEFICIT", "insurance coverage must be within liquidation deficit");
                    InsuranceAllocationPolicy.Resolution result = InsuranceAllocationPolicy.resolve(allocation, ledger);
                    if (!result.next()) throw new CoreStateRejectedException("INSURANCE_RESOLUTION_ORDER_MISMATCH",
                            "insurance claims must resolve in deterministic priority order");
                    if (command.coveredUnits() != result.expectedCoverage())
                        throw new CoreStateRejectedException("INSURANCE_ALLOCATION_MISMATCH", "insurance coverage does not match deterministic allocation");
                    if (command.coveredUnits() > ledger.insurance(assetId))
                        throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE", "insurance fund balance is insufficient");
                    if (command.coveredUnits() != 0) {
                        treasuryDelta.addInsurance(assetId, Math.negateExact(command.coveredUnits()));
                        treasuryDelta.addDeficit(assetId, Math.negateExact(command.coveredUnits()));
                    }
                    nextDeficit = Math.subtractExact(nextDeficit, command.coveredUnits());
                    nextStatus = nextDeficit == 0 ? CoreLiquidationState.Status.COMPLETED : CoreLiquidationState.Status.ADL_REQUIRED;
                }
                case ADL -> throw new CoreStateRejectedException("INVALID_COMMAND", "ADL resolution requires atomic target deleveraging");
                case COMPLETED -> {
                    if (command.coveredUnits() != 0) throw new CoreStateRejectedException("INVALID_COMMAND", "completed resolution covers no units");
                    if (liquidation.deficitUnits() != 0) throw new CoreStateRejectedException("LIQUIDATION_DEFICIT_REMAINS",
                            "liquidation deficit must be fully covered before completion");
                    nextStatus = CoreLiquidationState.Status.COMPLETED;
                }
                default -> throw new IllegalStateException("unknown liquidation resolution");
            }
            treasuryDelta.apply(ledger);
            LiquidationRuntime current = liquidation;
            nextLiquidation = new LiquidationRuntime(current.liquidationId(), current.userId(), current.symbolId(),
                    current.marginMode(), current.positionSide(), current.instrument(), current.triggerPriceSequence(),
                    current.signedQuantitySteps(), current.closeQuantitySteps(), nextDeficit, current.executionPriceTicks(),
                    current.liquidationFeeRatePpm(), current.liquidationFeeUnits(), nextStatus, 0);
        }

        @Override public Object apply(int laneId) {
            if (capturingClaims) {
                if (runtime.currentLaneOwns(sourceUserId)) {
                    liquidation = runtime.liquidation(command.liquidationId());
                    if (liquidation == null) throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
                }
                for (int index = 0; index < claimCount; index++) {
                    if (claimLanes[index] != laneId) continue;
                    var value = runtime.liquidation(claimIds[index]);
                    if (value != null && value.status() == CoreLiquidationState.Status.INSURANCE_REQUIRED)
                        claimReceipts[index] = value;
                }
                return null;
            }
            runtime.replaceLiquidation(nextLiquidation);
            return null;
        }

        @Override public boolean getAsBoolean() {
            if (done) return true;
            if (capturingClaims) {
                if (!runtime.pollControlLanes()) return false;
                capturingClaims = false;
            }
            if (!ledgerDispatched) {
                if (!runtime.dispatchLedgerCommand(this)) return false;
                ledgerDispatched = true;
            }
            if (!ledgerCommandCompleted()) return false;
            if (!accountsDispatched) {
                runtime.dispatchControlLanes(laneMask, this);
                accountsDispatched = true;
            }
            if (!runtime.pollControlLanes()) return false;
            finish();
            return true;
        }

        private void finish() {
            if (done) return;
            runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
            java.util.Arrays.fill(claimReceipts, 0, claimCount, null);
            allocation = null;
            liquidation = nextLiquidation = null;
            command = null;
            done = true;
        }
    }

    private static CoreInstrument requireInstrument(TradingRuntimeState runtime, String instrumentId) {
        CoreInstrument instrument = runtime.instrument(instrumentId);
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        return instrument;
    }
}
