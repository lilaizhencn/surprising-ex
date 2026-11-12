package com.surprising.aeron.service.adl;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.protocol.ExecuteAdlCommand;
import com.surprising.aeron.service.account.AccountBalance;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPrice;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.service.treasury.SystemLedgerCommand;
import com.surprising.aeron.service.treasury.SystemFunds;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.aeron.support.math.OptionContractMath;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** ADL 步骤 1–4：源强平校验、目标账户减仓、源强平补亏、账本记账。Owner 只安排顺序并收集完成结果。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AdlService {

    /** 优先复用已经可回收的命令续步，初始化新 ADL 输入并返回给 Owner 轮询。 */
    public static AdlWork begin(AdlWork reuse, ExecuteAdlCommand command,
            AccountBook accountBook, IdIndex identities) {
        if (command == null || accountBook == null || identities == null || !accountBook.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid ADL apply");
        }
        accountBook.assertOwner();
        var route = accountBook.liquidationRoute(command.liquidationId());
        if (route == null) throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation route is missing");
        CoreInstrument instrument = accountBook.requireInstrument(route.instrumentId());
        int symbolId = identities.symbolId(route.instrumentId());
        MarkPrice mark = accountBook.markPrice(symbolId);
        if (mark == null || mark.priceSequence() != command.markPriceSequence())
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "ADL mark price changed");
        long positionKey = identities.positionKey(command.targetUserId(), instrument, command.positionSide());
        AdlWork work = reuse == null || !reuse.reusable() ? new AdlWork() : reuse;
        work.prepare(command, accountBook, instrument, mark, positionKey,
                identities.assetId(instrument.settleAsset()));
        work.fundingInProgress = accountBook.systemFunds().fundingProgress(symbolId) != null;
        accountBook.dispatchControlLanes(accountBook.topology().accountLaneMask(route.userId()), work);
        return work;
    }

    /** 命令槽可复用的 ADL 续步，按源账户校验、目标账户减仓、源强平补亏、账本记账的顺序推进。 */
    public static final class AdlWork extends SystemLedgerCommand
            implements java.util.function.BooleanSupplier, java.util.function.IntFunction<Object> {
        private ExecuteAdlCommand command;
        private RiskScanner.LiquidationOrder liquidation;
        private CoreInstrument instrument;
        private MarkPrice mark;
        private long positionKey;
        private int settleAssetId;
        private long clearingUnits;
        /** 原有源校验、目标减仓、源补亏、账户发布和账本交接的互斥阶段。 */
        private enum Phase { CAPTURE, TARGET, SOURCE, ACCOUNTS, DONE }
        /** Owner 推进当前阶段。 */
        private Phase phase;
        /** Owner 准备时捕获的该币对资金费切仓是否进行中，源校验据此拒绝冲突。 */
        private boolean fundingInProgress;

        private void prepare(ExecuteAdlCommand command, AccountBook accountBook,
                CoreInstrument instrument, MarkPrice mark, long positionKey, int settleAssetId) {
            initializeLedgerCommand(accountBook);
            this.command = command;

            this.liquidation = null;
            this.instrument = instrument;
            this.mark = mark;
            this.positionKey = positionKey;
            this.settleAssetId = settleAssetId;
            this.clearingUnits = 0;
            this.phase = Phase.CAPTURE;
        }

        /** 在当前账户 Lane 执行对应阶段：先检查源强平状态、资金费边界和覆盖范围。 */
        @Override
        public Object apply(int ignored) {
            switch (phase) {
                case CAPTURE -> {
                    liquidation = accountBook.liquidation(command.liquidationId());
                    if (liquidation == null) throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
                    if (liquidation.status() != CoreLiquidationState.Status.ADL_REQUIRED)
                        throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT", "ADL requires ADL state");
                    if (fundingInProgress)
                        throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS", "funding position cut is in progress");
                    if (!instrument.instrumentId().equals(command.instrumentId()) || command.targetUserId() == liquidation.userId()
                            || command.coveredUnits() > liquidation.deficitUnits())
                        throw new CoreStateRejectedException("INVALID_COMMAND", "ADL command does not match liquidation");
                }
                case TARGET -> {
                    if (accountBook.currentLaneOwns(command.targetUserId()))
                        clearingUnits = applyAdlTarget(command, accountBook, liquidation, instrument, mark,
                                positionKey, settleAssetId);
                    if (accountBook.currentLaneOwns(liquidation.userId()) && accountBook.currentLaneOwns(command.targetUserId()))
                        completeAdlLiquidation(command, accountBook);
                }
                case SOURCE -> {
                    if (accountBook.currentLaneOwns(liquidation.userId())) completeAdlLiquidation(command, accountBook);
                }
                default -> throw new IllegalStateException("ADL account operation outside dispatch phase");
            }
            return null;
        }

        /** Owner 非阻塞等待源校验、目标减仓和必要的源强平更新，登记目标账户变化后派发账本。 */
        @Override
        public boolean getAsBoolean() {
            if (phase == Phase.DONE) return true;
            if (phase == Phase.CAPTURE) {
                if (!accountBook.pollControlLanes()) return false;
                phase = Phase.TARGET;
                accountBook.dispatchControlLanes(accountBook.topology().accountLaneMask(command.targetUserId()), this);
                return false;
            }
            if (phase == Phase.TARGET) {
                if (!accountBook.pollControlLanes()) return false;
                long sourceLaneMask = accountBook.topology().accountLaneMask(liquidation.userId());
                if (accountBook.topology().accountLaneMask(command.targetUserId()) != sourceLaneMask) {
                    phase = Phase.SOURCE;
                    accountBook.dispatchControlLanes(sourceLaneMask, this);
                    return false;
                }
                phase = Phase.ACCOUNTS;
            }
            if (phase == Phase.SOURCE) {
                if (!accountBook.pollControlLanes()) return false;
                phase = Phase.ACCOUNTS;
            }
            if (phase == Phase.ACCOUNTS) {
                accountBook.recordUserSettlementChanges(command.targetUserId(), settleAssetId, positionKey);
                accountBook.requireRevisionCapacity();
                accountBook.dispatchLedgerCommand(this);
                requireLedgerAccepted();
                accountBook.setMetadata(accountBook.productLine(), Math.incrementExact(accountBook.revision()));
                phase = Phase.DONE;
                liquidation = null;
                command = null;
                instrument = null;
                mark = null;
                return true;
            }
            return false;
        }

        /** 账本消费目标账户的资金差额，完成清算对手项和亏空抵减。 */
        @Override protected void applyLedger(SystemFunds ledger) {
            long nextDeficit = Math.subtractExact(ledger.insuranceDeficit(settleAssetId), command.coveredUnits());
            long nextClearing = Math.addExact(ledger.clearingPnl(settleAssetId), clearingUnits);
            ledger.setInsurance(settleAssetId, ledger.insurance(settleAssetId), nextDeficit);
            ledger.setClearingPnl(settleAssetId, nextClearing);
        }

    }

    private static long applyAdlTarget(ExecuteAdlCommand command, AccountBook accountBook,
            RiskScanner.LiquidationOrder liquidation, CoreInstrument instrument, MarkPrice mark,
            long positionKey, int settleAssetId) {
        Position position = accountBook.position(positionKey);
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
                : com.surprising.instrument.api.math.PerpetualContractMath.proportionalUnits(totalProfit, command.closeQuantitySteps(), currentAbs);
        if (coverCapacity < command.coveredUnits()) {
            throw new CoreStateRejectedException("ADL_PROFIT_INSUFFICIENT", "ADL target profit is insufficient");
        }
        long remainingAbs = Math.subtractExact(currentAbs, command.closeQuantitySteps());
        long nextQuantity = remainingAbs == 0 ? 0
                : position.signedQuantitySteps() > 0 ? remainingAbs : Math.negateExact(remainingAbs);
        long releasedMargin = com.surprising.instrument.api.math.PerpetualContractMath.proportionalUnits(position.positionMarginUnits(), command.closeQuantitySteps(), currentAbs);
        AccountBalance balance = accountBook.balance(command.targetUserId(), settleAssetId);
        if (balance == null) {
            throw new CoreStateRejectedException("BALANCE_NOT_FOUND", "required balance is missing");
        }
        long closeCashDelta = instrument.contractType().isOption()
                ? OptionContractMath.optionMarketValueUnits(instrument,
                position.signedQuantitySteps() > 0 ? command.closeQuantitySteps()
                        : Math.negateExact(command.closeQuantitySteps()), mark.markPriceTicks())
                : coverCapacity;
        long targetCashDelta = Math.subtractExact(closeCashDelta, command.coveredUnits());
        long clearingUnits = Math.addExact(Math.negateExact(targetCashDelta), Math.negateExact(command.coveredUnits()));
        AccountBalance nextBalance = new AccountBalance(balance.userId(), balance.assetId(),
                Math.addExact(balance.availableUnits(), Math.addExact(releasedMargin, targetCashDelta)),
                Math.subtractExact(balance.lockedUnits(), releasedMargin));
        long nextEntryValue = remainingAbs == 0 ? 0
                : com.surprising.instrument.api.math.PerpetualContractMath.proportionalUnits(position.entryValueTicks(), remainingAbs, currentAbs);
        Position nextPosition = new Position(position.userId(), position.symbolId(), position.assetId(),
                position.marginMode(), position.positionSide(), position.instrument(),
                nextQuantity, remainingAbs == 0 ? 0 : position.entryPriceTicks(), nextEntryValue,
                Math.addExact(position.realizedPnlUnits(),
                        instrument.contractType().isOption() ? 0 : coverCapacity),
                Math.subtractExact(position.positionMarginUnits(), releasedMargin));
        accountBook.replaceBalance(nextBalance);
        accountBook.replacePosition(positionKey, nextPosition);
        accountBook.advanceUserRevision(command.targetUserId());
        return clearingUnits;
    }

    /** 归零则完成，否则继续保留 ADL_REQUIRED，执行价格和已收费用保持原记录。 */
    private static void completeAdlLiquidation(ExecuteAdlCommand command, AccountBook accountBook) {
        RiskScanner.LiquidationOrder current = accountBook.liquidation(command.liquidationId());
        long nextDeficit = Math.subtractExact(current.deficitUnits(), command.coveredUnits());
        CoreLiquidationState.Status nextStatus = nextDeficit == 0
                ? CoreLiquidationState.Status.COMPLETED : CoreLiquidationState.Status.ADL_REQUIRED;

        RiskScanner.LiquidationOrder nextLiquidation = new RiskScanner.LiquidationOrder(current.liquidationId(), current.userId(),
                current.symbolId(), current.marginMode(), current.positionSide(), current.instrument(),
                current.triggerPriceSequence(), current.signedQuantitySteps(), current.closeQuantitySteps(),
                nextDeficit, current.executionPriceTicks(), current.liquidationFeeRatePpm(),
                current.liquidationFeeUnits(), nextStatus, 0);
        accountBook.replaceLiquidation(nextLiquidation);
    }

}
