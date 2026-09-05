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
        if (before == null || command == null || identities == null || !before.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid perpetual liquidation simulation");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        return applyExecutionRuntime(command, canceledOrders, runtime, identities);
    }

    public static TradingRuntimeState applyExecution(TradingCoreState before,
                                                     ExecuteLiquidationCommand command,
                                                     Collection<CoreOrderState> canceledOrders,
                                                     TradingRuntimeState runtime,
                                                     RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid perpetual liquidation apply");
        }
        return applyExecutionRuntime(command, canceledOrders, runtime, identities);
    }

    public static TradingRuntimeState applyExecutionRuntime(ExecuteLiquidationCommand command,
                                                            Collection<CoreOrderState> canceledOrders,
                                                            TradingRuntimeState runtime,
                                                            RuntimeIdentityRegistry identities) {
        if (command == null || runtime == null || identities == null || !runtime.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid perpetual liquidation apply");
        }
        runtime.assertOwner();
        LiquidationRuntime liquidation = runtime.liquidation(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        if (liquidation.status() != CoreLiquidationState.Status.PLANNED
                && liquidation.status() != CoreLiquidationState.Status.ORDERED) {
            throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT", "liquidation is not planned");
        }
        validatePrice(runtime, liquidation, command);
        if (!executable(runtime, liquidation, identities)) {
            runtime.executeUserSettlement(liquidation.userId(), () -> {
                runtime.replaceLiquidation(copy(liquidation, 0, 0, 0,
                        CoreLiquidationState.Status.CANCELED, 0));
                runtime.advanceUserRevision(liquidation.userId());
                return null;
            });
            runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
            return runtime;
        }
        cancelOrders(runtime, canceledOrders == null ? List.of() : canceledOrders);

        CoreInstrumentState instrument = requireInstrument(runtime, identities.symbol(liquidation.symbolId()),
                liquidation.instrumentVersion());
        int settleAssetId = identities.assetId(instrument.settleAsset());
        long positionKey = identities.positionKey(liquidation.userId(),
                positionKey(identities.symbol(liquidation.symbolId()), liquidation.positionSide()));
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

        runtime.executeUserSettlement(liquidation.userId(), () -> {
            runtime.replaceBalance(cash.balance());
            runtime.replacePosition(positionKey, nextPosition);
            runtime.replaceLiquidation(copy(liquidation, uncovered, command.executionPriceTicks(),
                    command.liquidationFeeRatePpm(), nextStatus, cash.collectedFee()));
            runtime.advanceUserRevision(liquidation.userId());
            return null;
        });
        runtime.recordUserSettlementChanges(liquidation.userId(), settleAssetId, positionKey);
        RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
        treasuryDelta.addInsurance(settleAssetId, insuranceDelta);
        treasuryDelta.addDeficit(settleAssetId, uncovered);
        treasuryDelta.addClearing(settleAssetId, uncovered);
        treasuryDelta.apply(runtime.treasury());
        runtime.setMetadata(runtime.productLine(), revisionAfterCancellation(runtime.revision(), canceledOrders));
        return runtime;
    }

    public static TradingRuntimeState simulateCancellationAdvance(TradingCoreState before,
                                                                  ExecuteLiquidationCommand command,
                                                                  Collection<CoreOrderState> canceledOrders,
                                                                  long nextCursorOrderId,
                                                                  RuntimeIdentityRegistry identities) {
        if (before == null || command == null || identities == null || nextCursorOrderId <= 0
                || !before.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid liquidation cancellation simulation");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        return applyCancellationAdvanceRuntime(command, canceledOrders, nextCursorOrderId, runtime, identities);
    }

    public static TradingRuntimeState applyCancellationAdvance(TradingCoreState before,
                                                               ExecuteLiquidationCommand command,
                                                               Collection<CoreOrderState> canceledOrders,
                                                               long nextCursorOrderId,
                                                               TradingRuntimeState runtime,
                                                               RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid liquidation cancellation apply");
        }
        return applyCancellationAdvanceRuntime(command, canceledOrders, nextCursorOrderId, runtime, identities);
    }

    public static TradingRuntimeState applyCancellationAdvanceRuntime(ExecuteLiquidationCommand command,
                                                                      Collection<CoreOrderState> canceledOrders,
                                                                      long nextCursorOrderId,
                                                                      TradingRuntimeState runtime,
                                                                      RuntimeIdentityRegistry identities) {
        if (command == null || runtime == null || identities == null || nextCursorOrderId <= 0
                || !runtime.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid liquidation cancellation apply");
        }
        runtime.assertOwner();
        LiquidationRuntime liquidation = runtime.liquidation(command.liquidationId());
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
        LiquidationRuntime next = new LiquidationRuntime(current.liquidationId(), current.userId(),
                current.symbolId(), current.marginMode(), current.positionSide(), current.instrumentVersion(),
                current.triggerPriceSequence(), current.signedQuantitySteps(), current.closeQuantitySteps(),
                current.deficitUnits(), current.executionPriceTicks(), current.liquidationFeeRatePpm(),
                current.liquidationFeeUnits(), CoreLiquidationState.Status.ORDERED, nextCursorOrderId);
        runtime.executeUserSettlement(current.userId(), () -> {
            runtime.replaceLiquidation(next);
            return null;
        });
        runtime.setMetadata(runtime.productLine(), revisionAfterCancellation(runtime.revision(), canceledOrders));
        return runtime;
    }

    public static TradingRuntimeState simulateResolution(TradingCoreState before,
                                                         ResolveLiquidationCommand command,
                                                         RuntimeIdentityRegistry identities) {
        if (before == null || command == null || identities == null || !before.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid liquidation resolution simulation");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        return applyResolutionRuntime(command, runtime, identities, before.riskState().liquidations().keySet());
    }

    public static TradingRuntimeState applyResolution(TradingCoreState before,
                                                      ResolveLiquidationCommand command,
                                                      TradingRuntimeState runtime,
                                                      RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid liquidation resolution apply");
        }
        return applyResolutionRuntime(command, runtime, identities, before.riskState().liquidations().keySet());
    }

    public static TradingRuntimeState applyResolutionRuntime(ResolveLiquidationCommand command,
                                                             TradingRuntimeState runtime,
                                                             RuntimeIdentityRegistry identities,
                                                             Iterable<Long> candidateIds) {
        if (command == null || runtime == null || identities == null || !runtime.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid liquidation resolution apply");
        }
        runtime.assertOwner();
        LiquidationRuntime liquidation = runtime.liquidation(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        CoreInstrumentState instrument = requireInstrument(runtime, identities.symbol(liquidation.symbolId()),
                liquidation.instrumentVersion());
        LiquidationRuntime current = liquidation;
        CoreLiquidationState.Status nextStatus;
        long nextDeficit = liquidation.deficitUnits();
        BalanceRuntime nextBalance = null;
        int changedAssetId;
        RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
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
                if (!InsuranceAllocationPolicy.isNext(runtime, identities, candidateIds,
                        liquidation.liquidationId())) {
                    throw new CoreStateRejectedException("INSURANCE_RESOLUTION_ORDER_MISMATCH",
                            "insurance claims must resolve in deterministic priority order");
                }
                long expectedCoverage = InsuranceAllocationPolicy.expectedCoverage(
                        runtime, identities, candidateIds, liquidation.liquidationId());
                if (command.coveredUnits() != expectedCoverage) {
                    throw new CoreStateRejectedException("INSURANCE_ALLOCATION_MISMATCH",
                            "insurance coverage does not match deterministic allocation");
                }
                if (command.coveredUnits() > runtime.treasury().insurance(assetId)) {
                    throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                            "insurance fund balance is insufficient");
                }
                changedAssetId = assetId;
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
                changedAssetId = -1;
                nextStatus = CoreLiquidationState.Status.COMPLETED;
            }
            default -> throw new IllegalStateException("unknown liquidation resolution");
        }
        LiquidationRuntime nextLiquidation = new LiquidationRuntime(current.liquidationId(), current.userId(),
                current.symbolId(), current.marginMode(), current.positionSide(), current.instrumentVersion(),
                current.triggerPriceSequence(), current.signedQuantitySteps(), current.closeQuantitySteps(),
                nextDeficit, current.executionPriceTicks(), current.liquidationFeeRatePpm(),
                current.liquidationFeeUnits(), nextStatus, 0);
        BalanceRuntime preparedBalance = nextBalance;
        runtime.executeUserSettlement(current.userId(), () -> {
            if (preparedBalance != null) {
                runtime.replaceBalance(preparedBalance);
                runtime.advanceUserRevision(current.userId());
            }
            runtime.replaceLiquidation(nextLiquidation);
            return null;
        });
        if (preparedBalance != null) {
            runtime.markBalanceChanged(current.userId(), changedAssetId);
        }
        treasuryDelta.apply(runtime.treasury());
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
        return runtime;
    }

    public static TradingRuntimeState simulateAdl(TradingCoreState before, ExecuteAdlCommand command,
                                                  RuntimeIdentityRegistry identities) {
        if (before == null || command == null || identities == null || !before.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid ADL simulation");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        return applyAdlRuntime(command, runtime, identities);
    }

    public static TradingRuntimeState applyAdl(TradingCoreState before, ExecuteAdlCommand command,
                                               TradingRuntimeState runtime,
                                               RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid ADL apply");
        }
        return applyAdlRuntime(command, runtime, identities);
    }

    public static TradingRuntimeState applyAdlRuntime(ExecuteAdlCommand command, TradingRuntimeState runtime,
                                                      RuntimeIdentityRegistry identities) {
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
        if (!identities.symbol(liquidation.symbolId()).equals(command.symbol())
                || command.targetUserId() == liquidation.userId()
                || command.coveredUnits() > liquidation.deficitUnits()) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "ADL command does not match liquidation");
        }
        CoreInstrumentState instrument = requireInstrument(runtime, identities.symbol(liquidation.symbolId()),
                liquidation.instrumentVersion());
        MarkPriceRuntime mark = runtime.markPrice(liquidation.symbolId());
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
        RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
        treasuryDelta.addClearing(settleAssetId, Math.negateExact(targetCashDelta));
        treasuryDelta.addDeficit(settleAssetId, Math.negateExact(command.coveredUnits()));
        treasuryDelta.addClearing(settleAssetId, Math.negateExact(command.coveredUnits()));
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

        LiquidationRuntime nextLiquidation = new LiquidationRuntime(current.liquidationId(), current.userId(),
                current.symbolId(), current.marginMode(), current.positionSide(), current.instrumentVersion(),
                current.triggerPriceSequence(), current.signedQuantitySteps(), current.closeQuantitySteps(),
                nextDeficit, current.executionPriceTicks(), current.liquidationFeeRatePpm(),
                current.liquidationFeeUnits(), nextStatus, 0);
        runtime.executeOwnerSettlements(List.of(command.targetUserId(), current.userId()), ignored -> {
            if (runtime.currentLaneOwns(command.targetUserId())) {
                runtime.replaceBalance(nextBalance);
                runtime.replacePosition(positionKey, nextPosition);
                runtime.advanceUserRevision(command.targetUserId());
            }
            if (runtime.currentLaneOwns(current.userId())) runtime.replaceLiquidation(nextLiquidation);
            return null;
        });
        runtime.recordUserSettlementChanges(command.targetUserId(), settleAssetId, positionKey);
        treasuryDelta.apply(runtime.treasury());
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
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
        if (orders == null || orders.isEmpty()) return;
        runtime.executeOwnerSettlements(orders, CoreOrderState::userId, ignored -> {
            for (CoreOrderState order : orders) {
                if (order == null || !runtime.currentLaneOwns(order.userId())) continue;
                OrderRuntime current = runtime.order(order.orderId());
                if (current == null || current.canceled() || current.remainingQuantitySteps() == 0
                        || current.userId() != order.userId()) {
                    throw new IllegalArgumentException("runtime liquidation cancellation requires open orders");
                }
                ReservationRuntime reservation = runtime.reservation(order.orderId());
                if (reservation == null) {
                    throw new IllegalStateException("runtime liquidation reservation is missing: " + order.orderId());
                }
                runtime.cancelOrder(order.orderId(), order.userId(), reservation.reservedUnits());
            }
            return null;
        });
    }

    private static boolean executable(TradingRuntimeState runtime, LiquidationRuntime liquidation,
                                      RuntimeIdentityRegistry identities) {
        String symbol = identities.symbol(liquidation.symbolId());
        CoreInstrumentState instrument = runtime.instrument(symbol);
        if (instrument == null || !CoreRiskPolicy.canLiquidate(
                instrument.contractType(), liquidation.signedQuantitySteps())) return false;
        long positionKey = identities.positionKey(liquidation.userId(),
                positionKey(symbol, liquidation.positionSide()));
        PositionRuntime position = runtime.position(positionKey);
        RiskSnapshotRuntime risk = runtime.riskSnapshot(positionKey);
        return position != null && position.instrumentVersion() == liquidation.instrumentVersion()
                && position.marginMode() == liquidation.marginMode()
                && position.signedQuantitySteps() == liquidation.signedQuantitySteps()
                && risk != null && risk.priceSequence() == liquidation.triggerPriceSequence()
                && risk.status() == CoreRiskStatus.LIQUIDATION;
    }

    private static long revisionAfterCancellation(long revision,
                                                  Collection<CoreOrderState> canceledOrders) {
        return Math.addExact(revision, canceledOrders == null || canceledOrders.isEmpty() ? 1 : 2);
    }

    private static void validatePrice(TradingRuntimeState runtime, LiquidationRuntime liquidation,
                                      ExecuteLiquidationCommand command) {
        MarkPriceRuntime mark = runtime.markPrice(liquidation.symbolId());
        if (mark == null || mark.priceSequence() != liquidation.triggerPriceSequence()
                || command.triggerPriceSequence() > 0
                && command.triggerPriceSequence() != liquidation.triggerPriceSequence()
                || command.executionPriceTicks() != mark.markPriceTicks()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "liquidation mark price changed");
        }
    }

    private static CoreInstrumentState requireInstrument(TradingRuntimeState runtime, String symbol, long version) {
        CoreInstrumentState instrument = runtime.instrument(symbol);
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

    private static long proportional(long units, long part, long total) {
        return part == total ? units : Math.multiplyExact(units, part) / total;
    }

    private record LiquidationCash(BalanceRuntime balance, long appliedPnl, long collectedFee) {
    }
}
