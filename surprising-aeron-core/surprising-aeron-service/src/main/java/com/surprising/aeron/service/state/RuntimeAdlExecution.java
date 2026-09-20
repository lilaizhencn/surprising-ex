package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.account.BalanceRuntime;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.market.MarkPriceRuntime;

import com.surprising.aeron.protocol.ExecuteAdlCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.math.CoreContractMath;
import com.surprising.aeron.service.state.math.OptionContractMath;
import com.surprising.aeron.service.state.model.CoreLiquidationState;

/**
 * Owns ADL target validation and the two-account-lane ADL mutation.
 *
 * <p>The liquidation account is completed on its owning lane, while the target
 * account supplies the position and balance mutation. The owner only merges the
 * target lane treasury delta after both lanes have completed.</p>
 */
public final class RuntimeAdlExecution {

    private RuntimeAdlExecution() {
    }

    public static TradingRuntimeState apply(TradingCoreState before, ExecuteAdlCommand command,
                                            TradingRuntimeState runtime,
                                            RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid ADL apply");
        }
        return applyRuntime(command, runtime, identities);
    }

    public static TradingRuntimeState applyRuntime(ExecuteAdlCommand command,
                                                   TradingRuntimeState runtime,
                                                   RuntimeIdentityRegistry identities) {
        adl(null, command, runtime, identities, false);
        return runtime;
    }

    public static java.util.function.BooleanSupplier begin(ExecuteAdlCommand command,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        return begin(null, command, runtime, identities);
    }

    /** Re-arm a slot-owned ADL continuation while retaining its primitive delta buffer. */
    public static AdlWork begin(AdlWork reuse, ExecuteAdlCommand command,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        return adl(reuse, command, runtime, identities, true);
    }

    private static AdlWork adl(AdlWork reuse, ExecuteAdlCommand command,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, boolean asynchronous) {
        if (command == null || runtime == null || identities == null || !runtime.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid ADL apply");
        }
        runtime.assertOwner();
        LiquidationRuntime liquidation = runtime.liquidation(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        if (liquidation.status() != CoreLiquidationState.Status.ADL_REQUIRED) {
            throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT", "ADL requires ADL state");
        }
        if (runtime.treasury().fundingProgress(liquidation.symbolId()) != null) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS", "funding position cut is in progress");
        }
        if (!identities.symbol(liquidation.symbolId()).equals(command.symbol())
                || command.targetUserId() == liquidation.userId()
                || command.coveredUnits() > liquidation.deficitUnits()) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "ADL command does not match liquidation");
        }
        CoreInstrument instrument = requireInstrument(runtime, identities.symbol(liquidation.symbolId()));
        MarkPriceRuntime mark = runtime.markPrice(liquidation.symbolId());
        if (mark == null || mark.priceSequence() != command.markPriceSequence()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "ADL mark price changed");
        }

        long positionKey = identities.positionKey(command.targetUserId(),
                positionKey(command.symbol(), command.positionSide()));
        int settleAssetId = identities.assetId(instrument.settleAsset());
        int targetLane = runtime.topology().accountLaneId(command.targetUserId());
        long mask = (1L << targetLane) | runtime.topology().accountLaneMask(liquidation.userId());
        AdlWork work = reuse == null ? new AdlWork() : reuse;
        work.prepare(command, runtime, liquidation, instrument, mark, positionKey,
                settleAssetId, targetLane);
        if (asynchronous) {
            runtime.dispatchControlLanes(mask, work);
            return work;
        }
        runtime.releaseOwnerLaneAccess();
        Object[] results = runtime.executeLaneMutations(mask, Long.bitCount(mask), false, work);
        work.finish(results[targetLane]);
        return null;
    }

    /** Two-account-lane ADL mutation plus its single owner-side treasury delta. */
    public static final class AdlWork
            implements java.util.function.BooleanSupplier, java.util.function.IntFunction<Object> {
        private ExecuteAdlCommand command;
        private TradingRuntimeState runtime;
        private LiquidationRuntime liquidation;
        private CoreInstrument instrument;
        private MarkPriceRuntime mark;
        private long positionKey;
        private int settleAssetId;
        private int targetLane;
        private final RuntimeTreasuryDelta targetDelta = new RuntimeTreasuryDelta();
        private boolean done;

        private void prepare(ExecuteAdlCommand command, TradingRuntimeState runtime,
                LiquidationRuntime liquidation, CoreInstrument instrument, MarkPriceRuntime mark,
                long positionKey, int settleAssetId, int targetLane) {
            this.command = command;
            this.runtime = runtime;
            this.liquidation = liquidation;
            this.instrument = instrument;
            this.mark = mark;
            this.positionKey = positionKey;
            this.settleAssetId = settleAssetId;
            this.targetLane = targetLane;
            this.targetDelta.clear();
            this.done = false;
        }

        @Override
        public Object apply(int ignored) {
            RuntimeTreasuryDelta delta = null;
            if (runtime.currentLaneOwns(command.targetUserId())) {
                delta = applyAdlTarget(command, runtime, liquidation, instrument, mark,
                        positionKey, settleAssetId, targetDelta);
            }
            if (runtime.currentLaneOwns(liquidation.userId())) {
                completeAdlLiquidation(command, runtime);
            }
            return delta;
        }

        @Override
        public boolean getAsBoolean() {
            if (done) return true;
            if (!runtime.pollControlLanes()) return false;
            finish(runtime.controlLaneResult(targetLane));
            return true;
        }

        private void finish(Object result) {
            if (done) return;
            runtime.recordUserSettlementChanges(command.targetUserId(), settleAssetId, positionKey);
            if (result instanceof RuntimeTreasuryDelta delta) delta.apply(runtime.treasury());
            runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
            done = true;
        }
    }

    private static RuntimeTreasuryDelta applyAdlTarget(ExecuteAdlCommand command, TradingRuntimeState runtime,
            LiquidationRuntime liquidation, CoreInstrument instrument, MarkPriceRuntime mark,
            long positionKey, int settleAssetId, RuntimeTreasuryDelta reuse) {
        PositionRuntime position = runtime.position(positionKey);
        if (position == null || position.marginMode() != command.marginMode()
                || position.signedQuantitySteps() != command.expectedSignedQuantitySteps()
                || position.entryPriceTicks() != command.expectedEntryPriceTicks()
                || Long.signum(position.signedQuantitySteps()) == Long.signum(liquidation.signedQuantitySteps())) {
            throw new CoreStateRejectedException("ADL_POSITION_CONFLICT", "ADL target position changed");
        }
        long totalProfit = CoreContractMath.pnlUnits(instrument, position.signedQuantitySteps(),
                position.entryPriceTicks(), mark.markPriceTicks());
        long currentAbs = Math.absExact(position.signedQuantitySteps());
        long coverCapacity = totalProfit <= 0 ? 0
                : proportional(totalProfit, command.closeQuantitySteps(), currentAbs);
        if (coverCapacity < command.coveredUnits()) {
            throw new CoreStateRejectedException("ADL_PROFIT_INSUFFICIENT", "ADL target profit is insufficient");
        }
        long remainingAbs = Math.subtractExact(currentAbs, command.closeQuantitySteps());
        long nextQuantity = remainingAbs == 0 ? 0
                : position.signedQuantitySteps() > 0 ? remainingAbs : Math.negateExact(remainingAbs);
        long releasedMargin = proportional(position.positionMarginUnits(), command.closeQuantitySteps(), currentAbs);
        BalanceRuntime balance = runtime.balance(command.targetUserId(), settleAssetId);
        if (balance == null) {
            throw new CoreStateRejectedException("BALANCE_NOT_FOUND", "required balance is missing");
        }
        long closeCashDelta = instrument.contractType().isOption()
                ? OptionContractMath.optionMarketValueUnits(instrument,
                position.signedQuantitySteps() > 0 ? command.closeQuantitySteps()
                        : Math.negateExact(command.closeQuantitySteps()), mark.markPriceTicks())
                : coverCapacity;
        long targetCashDelta = Math.subtractExact(closeCashDelta, command.coveredUnits());
        RuntimeTreasuryDelta treasuryDelta = reuse == null ? new RuntimeTreasuryDelta() : reuse;
        treasuryDelta.clear();
        treasuryDelta.addClearing(settleAssetId, Math.negateExact(targetCashDelta));
        treasuryDelta.addDeficit(settleAssetId, Math.negateExact(command.coveredUnits()));
        treasuryDelta.addClearing(settleAssetId, Math.negateExact(command.coveredUnits()));
        BalanceRuntime nextBalance = new BalanceRuntime(balance.userId(), balance.assetId(),
                Math.addExact(balance.availableUnits(), Math.addExact(releasedMargin, targetCashDelta)),
                Math.subtractExact(balance.lockedUnits(), releasedMargin));
        long nextEntryValue = remainingAbs == 0 ? 0
                : proportional(position.entryValueTicks(), remainingAbs, currentAbs);
        PositionRuntime nextPosition = new PositionRuntime(position.userId(), position.symbolId(), position.assetId(),
                position.marginMode(), position.positionSide(), position.instrument(),
                nextQuantity, remainingAbs == 0 ? 0 : position.entryPriceTicks(), nextEntryValue,
                Math.addExact(position.realizedPnlUnits(),
                        instrument.contractType().isOption() ? 0 : coverCapacity),
                Math.subtractExact(position.positionMarginUnits(), releasedMargin));
        runtime.replaceBalance(nextBalance);
        runtime.replacePosition(positionKey, nextPosition);
        runtime.advanceUserRevision(command.targetUserId());
        return treasuryDelta;
    }

    private static void completeAdlLiquidation(ExecuteAdlCommand command, TradingRuntimeState runtime) {
        LiquidationRuntime current = runtime.liquidation(command.liquidationId());
        long nextDeficit = Math.subtractExact(current.deficitUnits(), command.coveredUnits());
        CoreLiquidationState.Status nextStatus = nextDeficit == 0
                ? CoreLiquidationState.Status.COMPLETED : CoreLiquidationState.Status.ADL_REQUIRED;

        LiquidationRuntime nextLiquidation = new LiquidationRuntime(current.liquidationId(), current.userId(),
                current.symbolId(), current.marginMode(), current.positionSide(), current.instrument(),
                current.triggerPriceSequence(), current.signedQuantitySteps(), current.closeQuantitySteps(),
                nextDeficit, current.executionPriceTicks(), current.liquidationFeeRatePpm(),
                current.liquidationFeeUnits(), nextStatus, 0);
        runtime.replaceLiquidation(nextLiquidation);
    }

    private static CoreInstrument requireInstrument(TradingRuntimeState runtime, String symbol) {
        CoreInstrument instrument = runtime.instrument(symbol);
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        return instrument;
    }

    private static String positionKey(String symbol, com.surprising.aeron.protocol.CorePositionSide side) {
        return side == com.surprising.aeron.protocol.CorePositionSide.NET ? symbol : symbol + ':' + side.name();
    }

    private static long proportional(long units, long part, long total) {
        return part == total ? units : Math.multiplyExact(units, part) / total;
    }
}
