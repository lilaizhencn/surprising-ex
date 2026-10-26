package com.surprising.aeron.service.funding;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.CoreFundingPaymentView;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.service.account.BalanceRuntime;
import com.surprising.aeron.service.account.UserIndexPage;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPriceRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.product.ProductTradingRules;
import com.surprising.aeron.service.product.ProductTradingRulesRegistry;
import com.surprising.aeron.service.treasury.SystemLedgerCommand;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import com.surprising.aeron.support.collection.ImmutableLongArrayList;
import com.surprising.instrument.api.math.PerpetualContractMath;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

/** 永续合约资金费的账户与财库处理。固定本轮标记价后，按全局用户页分给账户 Lane；用户应付金额只扣可用余额，扣不到的部分从持仓收付明细中依次减免。账本记录实际收付的反向差额与分页进度，Owner 最后推进修订号。 构造约定：静态业务入口不创建实例；每页状态保存在 FundingWork。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimePerpetualFundingProcessor {

    /**
     * 在 Owner 上核对永续产品、币对维护状态、标记价、递增结算编号及分页参数。续页沿用进度中固定的标记价，并要求同一币对配置对象、费率和游标；可回收对象优先重置复用。与到期结算不同，已完成的同编号资金费也会按过期编号拒绝。
     * @param reuse 可选旧工作对象，满足继承的 reusable 条件时复用
     * @param command 资金费结算编号、费率及用户分页参数
     * @param indexedUserIds 非空、按全局用户编号递增且不重复的索引来源
     * @param runtime 产品线和账户数据的原权威运行时
     * @param identities 币对及结算资产内部编号映射
     */
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
        // 已经确认存在的永续币对配置。
        CoreInstrument instrument = runtime.instrument(command.instrumentId());
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        if (instrument.maintenance().mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT
                || instrument.maintenance().mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.CLOSED) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS", "fixed-price clearance has stopped funding");
        }
        // 本币对内部编号。
        int symbolId = identities.symbolId(instrument.instrumentIdValue());
        // 续页已有完整计算输入；只有首轮需要当前行情。
        TreasuryRuntime.FundingProgressRuntime previousProgress = runtime.treasury().fundingProgress(symbolId);
        long fundingMark;
        if (previousProgress == null) {
            MarkPriceRuntime mark = runtime.markPrice(symbolId);
            if (mark == null) {
                throw new CoreStateRejectedException("MARK_PRICE_NOT_FOUND", "funding requires mark price");
            }
            fundingMark = mark.markPriceTicks();
        } else {
            fundingMark = previousProgress.markPriceTicks();
        }

        // 本币对结算资产内部编号。
        int settleAssetId = identities.assetId(instrument.settleAsset());
        // 已完整结束的最近资金费结算编号。
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

    /**
     * 命令槽持有的一页资金费工作及回执缓冲。Owner 推进异步阶段，账本选择用户并合并财库，账户 Lane 应用余额；发布回执后才跨线程读取。重置复用容量，不新增账户或财库的权威副本。
     */
    public static final class FundingWork extends SystemLedgerCommand {
        /** 本页资金费编号、费率和用户分页输入。 */
        private ApplyFundingCommand command;
        /** 本轮固定的永续币对配置，续页校验要求与进度保存对象相同。 */
        private CoreInstrument instrument;
        /**
         * symbolId：币对内部编号。
         * settleAssetId：结算资产内部编号。
         */
        private int symbolId, settleAssetId;
        /** 本轮固定标记价；续页采用已保存进度中的价格。 */
        private long fundingMark;
        /** 账本选出的全局用户页与后续游标。 */
        private UserIndexPage userPage;
        /** 等待账本选页的持仓用户索引来源，完成后释放引用。 */
        private Iterable<Long> indexedUserIds;
        /** 本页含有用户的账户 Lane 位图。 */
        private long laneMask;
        /** 按实际账户归属划分的用户列表，重置保留容量。 */
        private LongArrayList[] usersByLane;
        /** 本页账户与账本交接的位置，代替三个顺序阶段标记的组合。 */
        private enum Phase { DISPATCH_ACCOUNTS, COLLECT_ACCOUNTS }
        private Phase phase;
        /** 异步完成时由 Owner 保存的本页结果，重置后为空。 */
        private FundingResult result;
        /** 各账户 Lane 的实际收付回执，交接完成后供账本读取。 */
        private LaneFundingResult[] laneResults;
        /** 账本按 Lane 汇总并最终按用户排序的明细缓冲；结果构造时复制，下一次重置清空。 */
        private final ArrayList<CoreFundingPaymentView> payments = new ArrayList<>();

        /**
         * 保存当前命令及固定价格输入，初始化该页可复用状态。
         * @param command 资金费结算编号、费率及用户分页参数
         * @param runtime 产品线和账户数据的原权威运行时
         * @param instrument 本轮固定使用的永续币对配置
         * @param symbolId 币对内部编号
         * @param settleAssetId 结算资产内部编号
         * @param fundingMark 本轮固定标记价
         * @param indexedUserIds 非空、按全局用户编号递增且不重复的索引来源
         */
        private FundingWork(ApplyFundingCommand command, TradingAccountRuntime runtime,
                CoreInstrument instrument, int symbolId, int settleAssetId, long fundingMark,
                Iterable<Long> indexedUserIds) {
            reset(command, runtime, instrument, symbolId, settleAssetId,
                    fundingMark, indexedUserIds);
        }

        /**
         * 重置继承的财务范围与本页标记，清空回执和明细并复用 Lane 分组容量，拓扑数量变化时重新创建分组及回执数组。
         * @param command 资金费结算编号、费率及用户分页参数
         * @param runtime 产品线和账户数据的原权威运行时
         * @param instrument 本轮固定使用的永续币对配置
         * @param symbolId 币对内部编号
         * @param settleAssetId 结算资产内部编号
         * @param fundingMark 本轮固定标记价
         * @param indexedUserIds 非空、按全局用户编号递增且不重复的索引来源
         */
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
                for (/* 当前用户所属账户 Lane 或回执数组的下标。 */ int lane = 0; lane < usersByLane.length; lane++) usersByLane[lane] = new LongArrayList();
            } else {
                for (LongArrayList /* 当前 Lane 可复用的用户列表，只清内容并保留容量。 */ users : usersByLane) users.clear();
            }
            laneMask = 0; phase = Phase.DISPATCH_ACCOUNTS; result = null;
            java.util.Arrays.fill(laneResults, null);
            payments.clear();
            selectAccountUsers();
            return this;
        }

        /**
         * 在账本上按全局游标选用户，再按实际账户 Lane 分组；未激活账本时内联执行。完成后释放索引来源引用，发布 usersReady。
         */
        private void selectAccountUsers() {
            userPage = UserIndexPage.select(indexedUserIds, command.cursorUserId(), command.maxUsers());
            ImmutableLongArrayList selectedUserIds = userPage.userIds();
            for (/* 本页原语用户列表的读取下标。 */ int index = 0; index < selectedUserIds.size(); index++) {
                // 当前选中的全局用户编号。
                long user = selectedUserIds.valueAt(index);
                // 当前用户所属账户 Lane 或回执数组的下标。
                int lane = runtime.topology().accountLaneId(user);
                usersByLane[lane].add(user); laneMask |= 1L << lane;
            }
            indexedUserIds = null;
        }

        /**
         * 使用本页固定币对、结算资产和标记价，由当前账户 Lane 结算并写入自己的回执槽，完成位发布后才供 Owner 与账本读取。
         * @param laneId 当前账户 Lane 编号
         */
        private Object applyLane(int laneId) {
            laneResults[laneId] = RuntimePerpetualFundingProcessor.applyLane(command, usersByLane[laneId], runtime,
                    instrument, symbolId, settleAssetId, fundingMark);
            return null;
        }

        /**
         * Owner 依次派发并收集账户收付、投递账本记账并等待完成，最后推进元数据并保存本页结果。每个阶段成功后置位，重复轮询不重复收付；true 表示本页有结果，是否全部结清仍看 progress.complete。
         */
        public boolean poll() {
            runtime.assertOwner();
            if (result != null) return true;
            if (phase == Phase.DISPATCH_ACCOUNTS) {
                phase = Phase.COLLECT_ACCOUNTS;
                if (laneMask != 0) runtime.dispatchControlLanes(laneMask, this::applyLane);
            }
            if (phase == Phase.COLLECT_ACCOUNTS) {
                if (laneMask != 0 && !runtime.pollControlLanes()) return false;
                for (/* 当前用户所属账户 Lane 或回执数组的下标。 */ int lane = 0; lane < usersByLane.length; lane++) {
                    if ((laneMask & (1L << lane)) == 0) continue;
                    LaneFundingResult receipt = laneResults[lane];
                    for (long userId : receipt.changedUserIds()) runtime.markBalanceChanged(userId);
                }
                runtime.requireRevisionCapacity();
                runtime.dispatchLedgerCommand(this);
                ledgerCommandCompleted();
                result = finishOwnerResult();
                return true;
            }
            return false;
        }

        /**
         * 读取 poll 已保存的页结果，尚未完成时拒绝。
         */
        public FundingResult result() {
            if (result == null) throw new IllegalStateException("funding has not completed");
            return result;
        }

        /**
         * 在账本阶段按 Lane 合并实际收付明细和资金费剩余差额，应用原财库后保存完成编号或固定价格的续页进度；最后按用户编号排序返回明细，不在账户热路径做此排序。
         * @param ledger 当前账本线程可写的原财库对象
         */
        @Override protected void applyLedger(TreasuryRuntime ledger) {
            long fundingResidual = 0;
            boolean balancesChanged = false;
            for (LaneFundingResult /* 某个账户 Lane 的实际收付与财库差额回执。 */ receipt : laneResults) {
                if (receipt == null) continue;
                payments.addAll(receipt.payments());
                fundingResidual = Math.addExact(fundingResidual, receipt.fundingResidualUnits());
                balancesChanged |= !receipt.changedUserIds().isEmpty();
            }
            // 实际收付可相互抵消为零；没有任何余额变化的页不产生账本写入。
            if (balancesChanged)
                ledger.setFundingResidual(settleAssetId,
                        Math.addExact(ledger.fundingResidual(settleAssetId), fundingResidual));
            if (userPage.complete()) ledger.setFundingSettlement(symbolId, command.settlementId());
            else ledger.setFundingProgress(symbolId, new TreasuryRuntime.FundingProgressRuntime(command.settlementId(),
                    instrument, command.fundingRatePpm(), userPage.nextCursorUserId(), fundingMark));
            payments.sort(java.util.Comparator.comparingLong(CoreFundingPaymentView::userId));
        }

        /**
         * 账户与财库完成后由 Owner 推进一次运行时修订号，返回只读明细副本及当前页进度；processedUsers 表示选中用户数，不保证每个用户都发生非零收付。
         */
        private FundingResult finishOwnerResult() {
            // 非分页或已读到用户末尾时，整轮资金费才可登记完成。
            boolean complete = userPage.complete();
            runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
            return new FundingResult(payments, new CoreFundingProgressView(command.settlementId(), complete,
                    complete ? 0 : userPage.nextCursorUserId(), userPage.userIds().size()));
        }
    }

    /**
     * 在所属 Lane 汇总用户本币对各仓应收应付，再把净应付款限制在可用余额内，冻结余额不变。实际非零收付才改余额和用户修订号；应付不足部分按持仓索引顺序抵减负向明细，财库只记录实际余额变化的反号，未抵完的减免视为内部不一致。
     * @param command 资金费结算编号、费率及用户分页参数
     * @param selectedUserIds 当前 Lane 本页选中的用户列表
     * @param runtime 产品线和账户数据的原权威运行时
     * @param instrument 本轮固定使用的永续币对配置
     * @param symbolId 币对内部编号
     * @param settleAssetId 结算资产内部编号
     * @param markPriceTicks 整轮固定的资金费计算标记价，不随本页执行过程变化
     */
    private static LaneFundingResult applyLane(ApplyFundingCommand command, LongArrayList selectedUserIds,
                                               TradingAccountRuntime runtime, CoreInstrument instrument,
                                               int symbolId, int settleAssetId, long markPriceTicks) {
        // 本永续产品的资金费计算规则，区分 U 本位与币本位。
        ProductTradingRules kernel = ProductTradingRulesRegistry.forInstrument(instrument);
        // 当前 Lane 生成的各持仓实际收付明细。
        ArrayList<CoreFundingPaymentView> payments = new ArrayList<>();
        // 实际发生非零净收付的用户编号构建器。
        ImmutableLongArrayList.Builder changedUserIds =
                new ImmutableLongArrayList.Builder(Math.min(16, selectedUserIds.size()));
        // 当前 Lane 实际账户收付对应的反向财库资金费差额。
        long fundingResidual = 0;
        for (/* 当前用户在本 Lane 选中列表里的下标。 */ int userIndex = 0; userIndex < selectedUserIds.size(); userIndex++) {
            // 当前正在计算或登记余额发布变化的用户编号。
            long userId = selectedUserIds.get(userIndex);
            // 该用户在本币对上的持仓键列表，决定明细生成及减免分配顺序。
            LongArrayList positionKeys = runtime.positionKeysForUserAndSymbolPrimitive(userId, symbolId);
            if (positionKeys.isEmpty()) continue;
            // 该用户各持仓原应收应付净额，正数应收，负数应付。
            long requestedDelta = 0;
            for (/* 当前持仓在该用户币对索引中的下标。 */ int positionIndex = 0; positionIndex < positionKeys.size(); positionIndex++) {
                // 当前持仓内部键。
                long positionKey = positionKeys.get(positionIndex);
                // 当前持仓权威值，用数量和方向计算资金费，不在此修改持仓。
                PositionRuntime position = runtime.position(positionKey);
                // 当前持仓按产品公式算出的原应收应付金额。
                long positionDelta = kernel.fundingDeltaUnits(instrument,
                        position.signedQuantitySteps(), markPriceTicks, command.fundingRatePpm());
                requestedDelta = Math.addExact(requestedDelta, positionDelta);
            }

            // 该用户结算资产的当前可用与冻结余额。
            BalanceRuntime balance = runtime.balance(userId, settleAssetId);
            if (balance == null) {
                throw new CoreStateRejectedException("BALANCE_NOT_FOUND", "required balance is missing");
            }
            // 最终实际净收付；应收全额入账，应付最多扣除可用余额。
            long appliedDelta = requestedDelta >= 0 ? requestedDelta
                    : Math.negateExact(Math.min(balance.availableUnits(), Math.negateExact(requestedDelta)));
            if (appliedDelta != 0) {
                runtime.replaceBalance(new BalanceRuntime(userId, settleAssetId,
                        Math.addExact(balance.availableUnits(), appliedDelta), balance.lockedUnits()));
                fundingResidual = Math.addExact(fundingResidual, Math.negateExact(appliedDelta));
                runtime.advanceUserRevision(userId);
                changedUserIds.add(userId);
            }

            // 因可用不足未实际扣到的应付款，随后从负向持仓明细中逐项抵减。
            long debitRelief = Math.subtractExact(appliedDelta, requestedDelta);
            for (/* 当前持仓在该用户币对索引中的下标。 */ int positionIndex = 0; positionIndex < positionKeys.size(); positionIndex++) {
                // 当前持仓内部键。
                long positionKey = positionKeys.get(positionIndex);
                // 当前持仓权威值，用数量和方向计算资金费，不在此修改持仓。
                PositionRuntime position = runtime.position(positionKey);
                // 当前持仓明细金额，负向应付可能被减免调至零。
                long amount = kernel.fundingDeltaUnits(instrument,
                        position.signedQuantitySteps(), markPriceTicks, command.fundingRatePpm());
                if (amount < 0 && debitRelief > 0) {
                    // 本持仓分到的应付减免，最多覆盖该持仓原应付额和剩余减免。
                    long relief = Math.min(Math.negateExact(amount), debitRelief);
                    amount = Math.addExact(amount, relief);
                    debitRelief = Math.subtractExact(debitRelief, relief);
                }
                if (amount != 0) {
                    // 按本产品合约类型及固定标记价计算的该持仓名义金额。
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

    /**
     * 本页资金费结果：只读明细副本及全局分页进度；账户与财库已在原运行时内应用。
     * @param payments 按用户和持仓记录的有符号实际收付明细，结果构造器会复制列表
     * @param progress 本页结束时是否整轮完成及下一用户游标
     */
    public record FundingResult(List<CoreFundingPaymentView> payments,
                                CoreFundingProgressView progress) {
        /**
         * 要求明细和进度非空，并复制明细列表形成只读结果。
         */
        public FundingResult {
            if (payments == null || progress == null) {
                throw new IllegalArgumentException("invalid runtime funding result");
            }
            payments = List.copyOf(payments);
        }
    }

    /**
     * 单个账户 Lane 的收付明细、实际余额变化用户和财库差额；Lane 完成后才交给 Owner 与账本消费。
     * @param payments 本 Lane 构建的实际收付明细，完成后不再修改
     * @param changedUserIds 实际发生非零余额变化并推进修订号的用户编号
     * @param fundingResidualUnits 本 Lane 实际账户资金变化的反向资金费残差
     */
    private record LaneFundingResult(List<CoreFundingPaymentView> payments, ImmutableLongArrayList changedUserIds,
                                     long fundingResidualUnits) {
    }

}
