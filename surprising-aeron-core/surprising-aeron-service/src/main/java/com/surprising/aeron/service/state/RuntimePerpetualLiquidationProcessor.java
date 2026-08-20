package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.protocol.ExecuteAdlCommand;

import java.util.Collection;
import java.util.List;

/** Runs perpetual liquidation settlement and insurance resolution on discardable Runtime projections. */
public final class RuntimePerpetualLiquidationProcessor {

    private RuntimePerpetualLiquidationProcessor() {
    }

    public static TradingRuntimeState simulateExecution(TradingCoreState before,
                                                        ExecuteLiquidationCommand command,
                                                        RuntimeIdentityRegistry identities) {
        return simulateExecution(before, command, List.of(), identities);
    }

    public static TradingRuntimeState simulateExecution(TradingCoreState before,
                                                        ExecuteLiquidationCommand command,
                                                        Collection<CoreOrderState> canceledOrders,
                                                        RuntimeIdentityRegistry identities) {
        if (before == null || command == null || identities == null || !before.productLine().isFundingProduct()) {
            throw new IllegalArgumentException("invalid perpetual liquidation simulation");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        return applyExecution(before, command, canceledOrders, runtime, identities);
    }

    public static TradingRuntimeState applyExecution(TradingCoreState before,
                                                     ExecuteLiquidationCommand command,
                                                     Collection<CoreOrderState> canceledOrders,
                                                     TradingRuntimeState runtime,
                                                     RuntimeIdentityRegistry identities) {
        if (before == null || command == null || runtime == null || identities == null
                || !before.productLine().isFundingProduct()) {
            throw new IllegalArgumentException("invalid perpetual liquidation apply");
        }
        runtime.assertOwner();
        CoreLiquidationState liquidation = before.riskState().liquidations().get(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        if (liquidation.status() != CoreLiquidationState.Status.PLANNED
                && liquidation.status() != CoreLiquidationState.Status.ORDERED) {
            throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT", "liquidation is not planned");
        }
        validatePrice(before, liquidation, command);
        LiquidationRuntime runtimeLiquidation = runtime.liquidation(command.liquidationId());
        if (!executable(before, liquidation)) {
            runtime.replaceLiquidation(copy(runtimeLiquidation, 0, 0, 0,
                    CoreLiquidationState.Status.CANCELED, 0));
            runtime.advanceUserRevision(liquidation.userId());
            runtime.setMetadata(before.productLine(), Math.incrementExact(before.revision()));
            return runtime;
        }
        cancelOrders(runtime, canceledOrders == null ? List.of() : canceledOrders);

        CoreInstrumentState instrument = requireInstrument(before, liquidation.symbol(),
                liquidation.instrumentVersion());
        int settleAssetId = identities.assetId(instrument.settleAsset());
        long positionKey = identities.positionKey(liquidation.userId(),
                positionKey(liquidation.symbol(), liquidation.positionSide()));
        PositionRuntime position = runtime.position(positionKey);
        BalanceRuntime balance = runtime.balance(liquidation.userId(), settleAssetId);
        if (position == null || balance == null) {
            throw new IllegalStateException("runtime liquidation entities are missing");
        }

        long currentAbs = Math.absExact(position.signedQuantitySteps());
        long closeQuantity = liquidation.closeQuantitySteps();
        long remainingAbs = Math.subtractExact(currentAbs, closeQuantity);
        long releasedMargin = position.positionMarginUnits() == 0 ? 0
                : proportional(position.positionMarginUnits(), closeQuantity, currentAbs);
        long pnl = instrument.contractType().isOption() ? 0
                : CoreContractMath.pnlUnits(instrument,
                position.signedQuantitySteps() > 0 ? closeQuantity : Math.negateExact(closeQuantity),
                position.entryPriceTicks(), command.executionPriceTicks());
        long feeDue = Math.negateExact(CoreContractMath.feeDeltaUnits(instrument,
                command.executionPriceTicks(), closeQuantity, command.liquidationFeeRatePpm()));
        LiquidationCash cash = applyCash(balance, liquidation.marginMode(), releasedMargin, pnl, feeDue);
        long uncovered = pnl < 0 ? Math.subtractExact(Math.negateExact(pnl),
                Math.negateExact(Math.min(0, cash.appliedPnl()))) : 0;

        long nextQuantity = remainingAbs == 0 ? 0
                : position.signedQuantitySteps() > 0 ? remainingAbs : Math.negateExact(remainingAbs);
        long nextEntryValue = remainingAbs == 0 ? 0
                : proportional(position.entryValueTicks(), remainingAbs, currentAbs);
        PositionRuntime nextPosition = new PositionRuntime(position.userId(), position.symbolId(), position.assetId(),
                position.marginMode(), position.positionSide(), remainingAbs == 0 ? 0 : position.instrumentVersion(),
                nextQuantity, remainingAbs == 0 ? 0 : position.entryPriceTicks(), nextEntryValue,
                Math.addExact(position.realizedPnlUnits(), pnl),
                Math.subtractExact(position.positionMarginUnits(), releasedMargin));
        long insuranceDelta = Math.addExact(Math.negateExact(cash.appliedPnl()), cash.collectedFee());
        CoreLiquidationState.Status nextStatus = uncovered > 0
                ? CoreLiquidationState.Status.INSURANCE_REQUIRED : CoreLiquidationState.Status.COMPLETED;

        runtime.replaceBalance(cash.balance());
        runtime.replacePosition(positionKey, nextPosition);
        runtime.treasury().adjustInsurance(settleAssetId, insuranceDelta);
        runtime.replaceLiquidation(copy(runtimeLiquidation, uncovered, command.executionPriceTicks(),
                command.liquidationFeeRatePpm(), nextStatus, cash.collectedFee()));
        runtime.advanceUserRevision(liquidation.userId());
        runtime.setMetadata(before.productLine(), revisionAfterCancellation(before, canceledOrders));
        return runtime;
    }

    public static TradingRuntimeState simulateCancellationAdvance(TradingCoreState before,
                                                                  ExecuteLiquidationCommand command,
                                                                  Collection<CoreOrderState> canceledOrders,
                                                                  long nextCursorOrderId,
                                                                  RuntimeIdentityRegistry identities) {
        if (before == null || command == null || identities == null || nextCursorOrderId <= 0
                || !before.productLine().isFundingProduct()) {
            throw new IllegalArgumentException("invalid liquidation cancellation simulation");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        return applyCancellationAdvance(before, command, canceledOrders, nextCursorOrderId, runtime, identities);
    }

    public static TradingRuntimeState applyCancellationAdvance(TradingCoreState before,
                                                               ExecuteLiquidationCommand command,
                                                               Collection<CoreOrderState> canceledOrders,
                                                               long nextCursorOrderId,
                                                               TradingRuntimeState runtime,
                                                               RuntimeIdentityRegistry identities) {
        if (before == null || command == null || runtime == null || identities == null || nextCursorOrderId <= 0
                || !before.productLine().isFundingProduct()) {
            throw new IllegalArgumentException("invalid liquidation cancellation apply");
        }
        runtime.assertOwner();
        CoreLiquidationState liquidation = before.riskState().liquidations().get(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        if (liquidation.status() == CoreLiquidationState.Status.ORDERED
                && liquidation.nextCancelOrderId() != command.cursorOrderId()) {
            throw new CoreStateRejectedException("LIQUIDATION_CURSOR_CONFLICT",
                    "liquidation cancellation cursor does not match state");
        }
        cancelOrders(runtime, canceledOrders == null ? List.of() : canceledOrders);
        LiquidationRuntime current = runtime.liquidation(command.liquidationId());
        runtime.replaceLiquidation(new LiquidationRuntime(current.liquidationId(), current.userId(),
                current.symbolId(), current.marginMode(), current.positionSide(), current.instrumentVersion(),
                current.triggerPriceSequence(), current.signedQuantitySteps(), current.closeQuantitySteps(),
                current.deficitUnits(), current.executionPriceTicks(), current.liquidationFeeRatePpm(),
                current.liquidationFeeUnits(), CoreLiquidationState.Status.ORDERED, nextCursorOrderId));
        runtime.setMetadata(before.productLine(), revisionAfterCancellation(before, canceledOrders));
        return runtime;
    }

    public static TradingRuntimeState simulateResolution(TradingCoreState before,
                                                         ResolveLiquidationCommand command,
                                                         RuntimeIdentityRegistry identities) {
        if (before == null || command == null || identities == null || !before.productLine().isFundingProduct()) {
            throw new IllegalArgumentException("invalid liquidation resolution simulation");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        return applyResolution(before, command, runtime, identities);
    }

    public static TradingRuntimeState applyResolution(TradingCoreState before,
                                                      ResolveLiquidationCommand command,
                                                      TradingRuntimeState runtime,
                                                      RuntimeIdentityRegistry identities) {
        if (before == null || command == null || runtime == null || identities == null
                || !before.productLine().isFundingProduct()) {
            throw new IllegalArgumentException("invalid liquidation resolution apply");
        }
        runtime.assertOwner();
        CoreLiquidationState liquidation = before.riskState().liquidations().get(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        CoreInstrumentState instrument = requireInstrument(before, liquidation.symbol(),
                liquidation.instrumentVersion());
        LiquidationRuntime current = runtime.liquidation(command.liquidationId());
        CoreLiquidationState.Status nextStatus;
        long nextDeficit = liquidation.deficitUnits();
        switch (command.resolution()) {
            case INSURANCE -> {
                if (liquidation.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED) {
                    throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT",
                            "insurance resolution requires insurance state");
                }
                if (command.coveredUnits() <= 0 || command.coveredUnits() > liquidation.deficitUnits()) {
                    throw new CoreStateRejectedException("INSURANCE_COVER_EXCEEDS_DEFICIT",
                            "insurance coverage must be within liquidation deficit");
                }
                int assetId = identities.assetId(instrument.settleAsset());
                if (command.coveredUnits() > runtime.treasury().insurance(assetId)) {
                    throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                            "insurance fund balance is insufficient");
                }
                runtime.treasury().adjustInsurance(assetId, Math.negateExact(command.coveredUnits()));
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
                nextStatus = CoreLiquidationState.Status.COMPLETED;
            }
            default -> throw new IllegalStateException("unknown liquidation resolution");
        }
        runtime.replaceLiquidation(new LiquidationRuntime(current.liquidationId(), current.userId(),
                current.symbolId(), current.marginMode(), current.positionSide(), current.instrumentVersion(),
                current.triggerPriceSequence(), current.signedQuantitySteps(), current.closeQuantitySteps(),
                nextDeficit, current.executionPriceTicks(), current.liquidationFeeRatePpm(),
                current.liquidationFeeUnits(), nextStatus, 0));
        runtime.setMetadata(before.productLine(), Math.incrementExact(before.revision()));
        return runtime;
    }

    public static TradingRuntimeState simulateAdl(TradingCoreState before, ExecuteAdlCommand command,
                                                  RuntimeIdentityRegistry identities) {
        if (before == null || command == null || identities == null || !before.productLine().isFundingProduct()) {
            throw new IllegalArgumentException("invalid ADL simulation");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        return applyAdl(before, command, runtime, identities);
    }

    public static TradingRuntimeState applyAdl(TradingCoreState before, ExecuteAdlCommand command,
                                               TradingRuntimeState runtime,
                                               RuntimeIdentityRegistry identities) {
        if (before == null || command == null || runtime == null || identities == null
                || !before.productLine().isFundingProduct()) {
            throw new IllegalArgumentException("invalid ADL apply");
        }
        runtime.assertOwner();
        CoreLiquidationState liquidation = before.riskState().liquidations().get(command.liquidationId());
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
        CoreInstrumentState instrument = requireInstrument(before, liquidation.symbol(),
                liquidation.instrumentVersion());
        CoreMarkPriceState mark = before.riskState().markPrices().get(liquidation.symbol());
        if (mark == null || mark.priceSequence() != command.markPriceSequence()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "ADL mark price changed");
        }

        long positionKey = identities.positionKey(command.targetUserId(),
                positionKey(command.symbol(), command.positionSide()));
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
        int settleAssetId = identities.assetId(instrument.settleAsset());
        BalanceRuntime balance = runtime.balance(command.targetUserId(), settleAssetId);
        if (balance == null) {
            throw new CoreStateRejectedException("BALANCE_NOT_FOUND", "required balance is missing");
        }
        long targetCashDelta = Math.subtractExact(coverCapacity, command.coveredUnits());
        BalanceRuntime nextBalance = new BalanceRuntime(balance.userId(), balance.assetId(),
                Math.addExact(balance.availableUnits(), Math.addExact(releasedMargin, targetCashDelta)),
                Math.subtractExact(balance.lockedUnits(), releasedMargin));
        long nextEntryValue = remainingAbs == 0 ? 0
                : proportional(position.entryValueTicks(), remainingAbs, currentAbs);
        PositionRuntime nextPosition = new PositionRuntime(position.userId(), position.symbolId(), position.assetId(),
                position.marginMode(), position.positionSide(), remainingAbs == 0 ? 0 : position.instrumentVersion(),
                nextQuantity, remainingAbs == 0 ? 0 : position.entryPriceTicks(), nextEntryValue,
                Math.addExact(position.realizedPnlUnits(), coverCapacity),
                Math.subtractExact(position.positionMarginUnits(), releasedMargin));
        LiquidationRuntime current = runtime.liquidation(command.liquidationId());
        long nextDeficit = Math.subtractExact(current.deficitUnits(), command.coveredUnits());
        CoreLiquidationState.Status nextStatus = nextDeficit == 0
                ? CoreLiquidationState.Status.COMPLETED : CoreLiquidationState.Status.ADL_REQUIRED;

        runtime.replaceBalance(nextBalance);
        runtime.replacePosition(positionKey, nextPosition);
        runtime.replaceLiquidation(new LiquidationRuntime(current.liquidationId(), current.userId(),
                current.symbolId(), current.marginMode(), current.positionSide(), current.instrumentVersion(),
                current.triggerPriceSequence(), current.signedQuantitySteps(), current.closeQuantitySteps(),
                nextDeficit, current.executionPriceTicks(), current.liquidationFeeRatePpm(),
                current.liquidationFeeUnits(), nextStatus, 0));
        runtime.advanceUserRevision(command.targetUserId());
        runtime.setMetadata(before.productLine(), Math.incrementExact(before.revision()));
        return runtime;
    }

    private static LiquidationCash applyCash(BalanceRuntime balance, CoreMarginMode marginMode,
                                             long releasedMargin, long pnl, long feeDue) {
        long available = balance.availableUnits();
        long locked = balance.lockedUnits();
        long appliedPnl;
        long collectedFee;
        if (marginMode == CoreMarginMode.ISOLATED) {
            if (pnl < 0) {
                long consumedMargin = Math.min(releasedMargin, Math.negateExact(pnl));
                long remainingMargin = Math.subtractExact(releasedMargin, consumedMargin);
                locked = Math.subtractExact(locked, releasedMargin);
                available = Math.addExact(available, remainingMargin);
                appliedPnl = Math.negateExact(consumedMargin);
                collectedFee = Math.min(feeDue, remainingMargin);
            } else {
                locked = Math.subtractExact(locked, releasedMargin);
                available = Math.addExact(available, Math.addExact(releasedMargin, pnl));
                appliedPnl = pnl;
                collectedFee = Math.min(feeDue, Math.addExact(releasedMargin, pnl));
            }
            available = Math.subtractExact(available, collectedFee);
        } else {
            locked = Math.subtractExact(locked, releasedMargin);
            available = Math.addExact(available, releasedMargin);
            if (pnl >= 0) {
                available = Math.addExact(available, pnl);
                appliedPnl = pnl;
            } else {
                long debit = Math.min(available, Math.negateExact(pnl));
                available = Math.subtractExact(available, debit);
                appliedPnl = Math.negateExact(debit);
            }
            collectedFee = Math.min(available, feeDue);
            available = Math.subtractExact(available, collectedFee);
        }
        return new LiquidationCash(new BalanceRuntime(balance.userId(), balance.assetId(), available, locked),
                appliedPnl, collectedFee);
    }

    private static void cancelOrders(TradingRuntimeState runtime, Collection<CoreOrderState> orders) {
        for (CoreOrderState order : orders) {
            if (order == null || order.status() != CoreOrderStatus.OPEN) {
                throw new IllegalArgumentException("runtime liquidation cancellation requires open orders");
            }
            ReservationRuntime reservation = runtime.reservation(order.orderId());
            if (reservation == null) {
                throw new IllegalStateException("runtime liquidation reservation is missing: " + order.orderId());
            }
            runtime.cancelOrder(order.orderId(), order.userId(), reservation.reservedUnits());
        }
    }

    private static boolean executable(TradingCoreState state, CoreLiquidationState liquidation) {
        CoreUserState user = state.user(liquidation.userId());
        CorePositionState position = user == null ? null
                : user.positions().get(positionKey(liquidation.symbol(), liquidation.positionSide()));
        CoreRiskSnapshot risk = state.riskState().snapshots().get(
                riskKey(liquidation.userId(), liquidation.symbol(), liquidation.positionSide()));
        return position != null && position.instrumentVersion() == liquidation.instrumentVersion()
                && position.marginMode() == liquidation.marginMode()
                && position.signedQuantitySteps() == liquidation.signedQuantitySteps()
                && risk != null && risk.priceSequence() == liquidation.triggerPriceSequence()
                && risk.status() == CoreRiskStatus.LIQUIDATION;
    }

    private static long revisionAfterCancellation(TradingCoreState before,
                                                  Collection<CoreOrderState> canceledOrders) {
        return Math.addExact(before.revision(), canceledOrders == null || canceledOrders.isEmpty() ? 1 : 2);
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

    private static LiquidationRuntime copy(LiquidationRuntime current, long deficit, long priceTicks,
                                           long feeRatePpm, CoreLiquidationState.Status status, long feeUnits) {
        return new LiquidationRuntime(current.liquidationId(), current.userId(), current.symbolId(),
                current.marginMode(), current.positionSide(), current.instrumentVersion(),
                current.triggerPriceSequence(), current.signedQuantitySteps(), current.closeQuantitySteps(),
                deficit, priceTicks, feeRatePpm, feeUnits, status, 0);
    }

    private static String positionKey(String symbol, CorePositionSide side) {
        return side == CorePositionSide.NET ? symbol : symbol + ':' + side.name();
    }

    private static String riskKey(long userId, String symbol, CorePositionSide side) {
        return side == CorePositionSide.NET ? userId + ":" + symbol : userId + ":" + symbol + ':' + side.name();
    }

    private static long proportional(long units, long part, long total) {
        return part == total ? units : Math.multiplyExact(units, part) / total;
    }

    private record LiquidationCash(BalanceRuntime balance, long appliedPnl, long collectedFee) {
    }
}
