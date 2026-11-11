package com.surprising.aeron.service.funding;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.CoreFundingPaymentView;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.service.account.AccountBalance;
import com.surprising.aeron.service.account.AccountExposureIndex;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPrice;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.product.ProductTradingRules;
import com.surprising.aeron.service.treasury.SystemLedgerCommand;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import com.surprising.aeron.support.collection.ImmutableLongArrayList;
import com.surprising.instrument.api.math.PerpetualContractMath;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

/** 永续合约资金费的账户与财库处理。账本记录实际收付的反向差额与分页进度，Owner 最后推进修订号。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class FundingSettlementService {

    /** 在 Owner 上核对永续产品、币对维护状态、标记价、递增结算编号及分页参数。 */
    public static FundingWork prepare(FundingWork reuse, ApplyFundingCommand command,
            Iterable<Long> indexedUserIds, TradingAccountRuntime runtime,
            RuntimeIdentityRegistry identities) {
        if (command == null || indexedUserIds == null || runtime == null || identities == null) {
            throw new IllegalArgumentException("invalid perpetual funding apply");
        }
        runtime.assertOwner();
        if (!runtime.productLine().isFundingProduct()) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED", "funding requires perpetual product");
        }
        CoreInstrument instrument = runtime.instrument(command.instrumentId());
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        if (instrument.maintenance().mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT
                || instrument.maintenance().mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.CLOSED) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS", "fixed-price clearance has stopped funding");
        }
        int symbolId = identities.symbolId(instrument.instrumentIdValue());
        TreasuryRuntime.FundingProgressRuntime previousProgress = runtime.treasury().fundingProgress(symbolId);
        long fundingMark;
        if (previousProgress == null) {
            MarkPrice mark = runtime.markPrice(symbolId);
            if (mark == null) {
                throw new CoreStateRejectedException("MARK_PRICE_NOT_FOUND", "funding requires mark price");
            }
            fundingMark = mark.markPriceTicks();
        } else {
            fundingMark = previousProgress.markPriceTicks();
        }

        int settleAssetId = identities.assetId(instrument.settleAsset());
        long previousSettlement = runtime.treasury().fundingSettlement(symbolId);
        if (command.settlementId() <= previousSettlement) {
            throw new CoreStateRejectedException("STALE_SETTLEMENT_ID", "funding settlement id must increase");
        }

        if (previousProgress == null && command.cursorUserId() != 0) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "funding cursor must start at zero");
        }
        if (previousProgress != null && (previousProgress.settlementId() != command.settlementId()
                || previousProgress.instrument() != instrument
                || previousProgress.fundingRatePpm() != command.fundingRatePpm()
                || previousProgress.nextCursorUserId() != command.cursorUserId())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "funding cursor does not match progress");
        }

        if (reuse == null || !reuse.reusable()) return new FundingWork(command, runtime, instrument, symbolId,
                settleAssetId, fundingMark, indexedUserIds);
        return reuse.reset(command, runtime, instrument, symbolId, settleAssetId,
                fundingMark, indexedUserIds);
    }

    /** 命令槽持有的一页资金费工作及回执缓冲。Owner 推进异步阶段，账本选择用户并合并财库，账户 Lane 应用余额。发布回执后才跨线程读取。 */
    public static final class FundingWork extends SystemLedgerCommand {
        private ApplyFundingCommand command;
        private CoreInstrument instrument;
        private int symbolId, settleAssetId;
        private long fundingMark;
        private AccountExposureIndex.Page userPage;
        private Iterable<Long> indexedUserIds;
        private long laneMask;
        private LongArrayList[] usersByLane;
        /** 本页账户与账本交接的位置，代替三个顺序阶段标记的组合。 */
        private enum Phase { DISPATCH_ACCOUNTS, COLLECT_ACCOUNTS }
        private Phase phase;
        /** 异步完成时由 Owner 保存的本页结果，重置后为空。 */
        private FundingResult result;
        private LaneFundingResult[] laneResults;
        private final ArrayList<CoreFundingPaymentView> payments = new ArrayList<>();

        private FundingWork(ApplyFundingCommand command, TradingAccountRuntime runtime,
                CoreInstrument instrument, int symbolId, int settleAssetId, long fundingMark,
                Iterable<Long> indexedUserIds) {
            reset(command, runtime, instrument, symbolId, settleAssetId,
                    fundingMark, indexedUserIds);
        }

        private FundingWork reset(ApplyFundingCommand command,
                TradingAccountRuntime runtime, CoreInstrument instrument, int symbolId,
                int settleAssetId, long fundingMark, Iterable<Long> indexedUserIds) {
            initializeLedgerCommand(runtime);
            this.command = command;
            this.instrument = instrument; this.symbolId = symbolId; this.settleAssetId = settleAssetId;
            this.fundingMark = fundingMark;
            this.indexedUserIds = indexedUserIds; userPage = null;
            if (usersByLane == null || usersByLane.length != runtime.topology().accountLaneCount()) {
                usersByLane = new LongArrayList[runtime.topology().accountLaneCount()];
                laneResults = new LaneFundingResult[usersByLane.length];
                for (int lane = 0; lane < usersByLane.length; lane++) usersByLane[lane] = new LongArrayList();
            } else {
                for (LongArrayList users : usersByLane) users.clear();
            }
            laneMask = 0; phase = Phase.DISPATCH_ACCOUNTS; result = null;
            java.util.Arrays.fill(laneResults, null);
            payments.clear();
            selectAccountUsers();
            return this;
        }

        private void selectAccountUsers() {
            userPage = AccountExposureIndex.Page.select(indexedUserIds, command.cursorUserId(), command.maxUsers());
            ImmutableLongArrayList selectedUserIds = userPage.userIds();
            for (int index = 0; index < selectedUserIds.size(); index++) {
                long user = selectedUserIds.valueAt(index);
                int lane = runtime.topology().accountLaneId(user);
                usersByLane[lane].add(user); laneMask |= 1L << lane;
            }
            indexedUserIds = null;
        }

        /** 使用本页固定币对、结算资产和标记价，由当前账户 Lane 结算并写入自己的回执槽，完成位发布后才供 Owner 与账本读取。 */
        private Object applyLane(int laneId) {
            laneResults[laneId] = FundingSettlementService.applyLane(command, usersByLane[laneId], runtime,
                    instrument, symbolId, settleAssetId, fundingMark);
            return null;
        }

        /** Owner 依次派发并收集账户收付、投递账本记账并等待完成，最后推进元数据并保存本页结果。 */
        public boolean poll() {
            runtime.assertOwner();
            if (result != null) return true;
            if (phase == Phase.DISPATCH_ACCOUNTS) {
                phase = Phase.COLLECT_ACCOUNTS;
                if (laneMask != 0) runtime.dispatchControlLanes(laneMask, this::applyLane);
            }
            if (phase == Phase.COLLECT_ACCOUNTS) {
                if (laneMask != 0 && !runtime.pollControlLanes()) return false;
                for (int lane = 0; lane < usersByLane.length; lane++) {
                    if ((laneMask & (1L << lane)) == 0) continue;
                    LaneFundingResult receipt = laneResults[lane];
                    for (long userId : receipt.changedUserIds()) runtime.markBalanceChanged(userId);
                }
                runtime.requireRevisionCapacity();
                runtime.dispatchLedgerCommand(this);
                requireLedgerAccepted();
                result = finishOwnerResult();
                return true;
            }
            return false;
        }

        /** 读取 poll 已保存的页结果，尚未完成时拒绝。 */
        public FundingResult result() {
            if (result == null) throw new IllegalStateException("funding has not completed");
            return result;
        }

        /** 在账本阶段按 Lane 合并实际收付明细和资金费剩余差额，应用原财库后保存完成编号或固定价格的续页进度。 */
        @Override protected void applyLedger(TreasuryRuntime ledger) {
            long fundingResidual = 0;
            boolean balancesChanged = false;
            for (LaneFundingResult receipt : laneResults) {
                if (receipt == null) continue;
                payments.addAll(receipt.payments());
                fundingResidual = Math.addExact(fundingResidual, receipt.fundingResidualUnits());
                balancesChanged |= !receipt.changedUserIds().isEmpty();
            }
            if (balancesChanged)
                ledger.setFundingResidual(settleAssetId,
                        Math.addExact(ledger.fundingResidual(settleAssetId), fundingResidual));
            if (userPage.complete()) ledger.setFundingSettlement(symbolId, command.settlementId());
            else ledger.setFundingProgress(symbolId, new TreasuryRuntime.FundingProgressRuntime(command.settlementId(),
                    instrument, command.fundingRatePpm(), userPage.nextCursorUserId(), fundingMark));
            payments.sort(java.util.Comparator.comparingLong(CoreFundingPaymentView::userId));
        }

        /** 账户与财库完成后由 Owner 推进一次运行时修订号，返回只读明细副本及当前页进度。 */
        private FundingResult finishOwnerResult() {
            boolean complete = userPage.complete();
            runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
            return new FundingResult(payments, new CoreFundingProgressView(command.settlementId(), complete,
                    complete ? 0 : userPage.nextCursorUserId(), userPage.userIds().size()));
        }
    }

    /** 应付不足部分按持仓索引顺序抵减负向明细，财库只记录实际余额变化的反号，未抵完的减免视为内部不一致。 */
    private static LaneFundingResult applyLane(ApplyFundingCommand command, LongArrayList selectedUserIds,
                                               TradingAccountRuntime runtime, CoreInstrument instrument,
                                               int symbolId, int settleAssetId, long markPriceTicks) {
        ProductTradingRules kernel = ProductTradingRules.forInstrument(instrument);
        ArrayList<CoreFundingPaymentView> payments = new ArrayList<>();
        ImmutableLongArrayList.Builder changedUserIds =
                new ImmutableLongArrayList.Builder(Math.min(16, selectedUserIds.size()));
        long fundingResidual = 0;
        for (int userIndex = 0; userIndex < selectedUserIds.size(); userIndex++) {
            long userId = selectedUserIds.get(userIndex);
            LongArrayList positionKeys = runtime.positionKeysForUserAndSymbolPrimitive(userId, symbolId);
            if (positionKeys.isEmpty()) continue;
            long requestedDelta = 0;
            for (int positionIndex = 0; positionIndex < positionKeys.size(); positionIndex++) {
                long positionKey = positionKeys.get(positionIndex);
                Position position = runtime.position(positionKey);
                long positionDelta = kernel.fundingDeltaUnits(instrument,
                        position.signedQuantitySteps(), markPriceTicks, command.fundingRatePpm());
                requestedDelta = Math.addExact(requestedDelta, positionDelta);
            }

            AccountBalance balance = runtime.balance(userId, settleAssetId);
            if (balance == null) {
                throw new CoreStateRejectedException("BALANCE_NOT_FOUND", "required balance is missing");
            }
            long appliedDelta = requestedDelta >= 0 ? requestedDelta
                    : Math.negateExact(Math.min(balance.availableUnits(), Math.negateExact(requestedDelta)));
            if (appliedDelta != 0) {
                runtime.replaceBalance(new AccountBalance(userId, settleAssetId,
                        Math.addExact(balance.availableUnits(), appliedDelta), balance.lockedUnits()));
                fundingResidual = Math.addExact(fundingResidual, Math.negateExact(appliedDelta));
                runtime.advanceUserRevision(userId);
                changedUserIds.add(userId);
            }

            long debitRelief = Math.subtractExact(appliedDelta, requestedDelta);
            for (int positionIndex = 0; positionIndex < positionKeys.size(); positionIndex++) {
                long positionKey = positionKeys.get(positionIndex);
                Position position = runtime.position(positionKey);
                long amount = kernel.fundingDeltaUnits(instrument,
                        position.signedQuantitySteps(), markPriceTicks, command.fundingRatePpm());
                if (amount < 0 && debitRelief > 0) {
                    long relief = Math.min(Math.negateExact(amount), debitRelief);
                    amount = Math.addExact(amount, relief);
                    debitRelief = Math.subtractExact(debitRelief, relief);
                }
                if (amount != 0) {
                    long notional = PerpetualContractMath.notionalUnits(instrument.contractType(),
                            position.signedQuantitySteps(), markPriceTicks,
                            instrument.notionalMultiplierUnits(), instrument.priceTickUnits(),
                            instrument.settleScaleUnits());
                    payments.add(new CoreFundingPaymentView(command.settlementId(), userId, instrument.instrumentId(),
                            position.marginMode(), position.positionSide(), instrument.settleAsset(),
                            position.signedQuantitySteps(), notional, command.fundingRatePpm(), amount));
                }
            }
            if (debitRelief != 0) {
                throw new IllegalStateException("runtime funding debit relief was not fully allocated");
            }
        }
        return new LaneFundingResult(payments, changedUserIds.freeze(), fundingResidual);
    }

    /** 本页资金费结果：只读明细副本及全局分页进度。 */
    public record FundingResult(List<CoreFundingPaymentView> payments,
                                CoreFundingProgressView progress) {
        public FundingResult {
            if (payments == null || progress == null) {
                throw new IllegalArgumentException("invalid runtime funding result");
            }
            payments = List.copyOf(payments);
        }
    }

    /** 单个账户 Lane 的收付明细、实际余额变化用户和财库差额。Lane 完成后才交给 Owner 与账本消费。 */
    private record LaneFundingResult(List<CoreFundingPaymentView> payments, ImmutableLongArrayList changedUserIds,
                                     long fundingResidualUnits) {
    }

}
