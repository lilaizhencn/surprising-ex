package com.surprising.aeron.service.state;

import com.surprising.aeron.service.business.ProductTradingRules;
import com.surprising.aeron.service.business.ProductTradingRulesRegistry;
import com.surprising.aeron.service.command.ImmutableLongArrayList;

import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.CoreFundingPaymentView;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.instrument.api.math.PerpetualContractMath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NavigableSet;
import java.util.UUID;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

public final class RuntimePerpetualFundingProcessor {

    private RuntimePerpetualFundingProcessor() {
    }


    public static FundingResult apply(TradingCoreState before, ApplyFundingCommand command,
                                      Iterable<Long> indexedUserIds, UUID chunkCommandId,
                                      TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid perpetual funding apply");
        }
        Iterable<Long> users = indexedUserIds == null ? before.users().keySet() : indexedUserIds;
        return applyRuntime(command, users, chunkCommandId, runtime, identities);
    }

    public static FundingResult applyRuntime(ApplyFundingCommand command, Iterable<Long> indexedUserIds,
                                             UUID chunkCommandId, TradingRuntimeState runtime,
                                             RuntimeIdentityRegistry identities) {
        FundingWork work = prepare(command, indexedUserIds, chunkCommandId, runtime, identities);
        Object[] results = runtime.executeLifecycleSettlements(work.laneMask, work.selectedUserIds.size(),
                work::applyLane);
        return work.finish(results);
    }

    public static FundingWork prepare(ApplyFundingCommand command, Iterable<Long> indexedUserIds,
            UUID chunkCommandId, TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        if (command == null || indexedUserIds == null || runtime == null || identities == null) {
            throw new IllegalArgumentException("invalid perpetual funding apply");
        }
        runtime.assertOwner();
        if (!runtime.productLine().isFundingProduct()) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED", "funding requires perpetual product");
        }
        CoreInstrumentState instrument = runtime.instrument(command.symbol());
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        if (instrument.changeId() != command.instrumentChangeId()) {
            throw new CoreStateRejectedException("INSTRUMENT_CHANGE_ID_CONFLICT", "instrument version differs");
        }
        if (instrument.maintenance().mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT
                || instrument.maintenance().mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.CLOSED) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS", "fixed-price clearance has stopped funding");
        }
        int symbolId = identities.symbolId(instrument.symbol());
        MarkPriceRuntime mark = runtime.markPrice(symbolId);
        if (mark == null) {
            throw new CoreStateRejectedException("MARK_PRICE_NOT_FOUND", "funding requires mark price");
        }

        int settleAssetId = identities.assetId(instrument.settleAsset());
        long previousSettlement = runtime.treasury().fundingSettlement(symbolId);
        if (command.settlementId() <= previousSettlement) {
            throw new CoreStateRejectedException("STALE_SETTLEMENT_ID", "funding settlement id must increase");
        }

        TreasuryRuntime.FundingProgressRuntime previousProgress = runtime.treasury().fundingProgress(symbolId);
        long fundingMark = previousProgress == null ? mark.markPriceTicks() : previousProgress.markPriceTicks();
        long fundingPriceSequence = previousProgress == null ? mark.priceSequence() : previousProgress.priceSequence();
        boolean chunked = chunkCommandId != null;
        if (chunked) {
            if (previousProgress == null && command.cursorUserId() != 0) {
                throw new CoreStateRejectedException("INVALID_COMMAND", "funding cursor must start at zero");
            }
            if (previousProgress != null && (previousProgress.settlementId() != command.settlementId()
                    || previousProgress.instrumentChangeId() != command.instrumentChangeId()
                    || previousProgress.fundingRatePpm() != command.fundingRatePpm()
                    || previousProgress.nextCursorUserId() != command.cursorUserId())) {
                throw new CoreStateRejectedException("INVALID_COMMAND", "funding cursor does not match progress");
            }
        }

        UserPage userPage = selectUsers(indexedUserIds, command.cursorUserId(),
                chunked ? command.maxUsers() : Integer.MAX_VALUE);
        return new FundingWork(command, chunkCommandId, runtime, instrument, symbolId, settleAssetId,
                fundingMark, fundingPriceSequence, userPage);
    }

    /** 一页资金费的账户计算与 owner 财库提交；各 Lane 只处理自己持有的账户。 */
    public static final class FundingWork {
        /** 原始资金费参数及幂等分页标识，跨回调保持不变。 */
        private final ApplyFundingCommand command;
        private final UUID chunkCommandId;
        /** owner 负责财库和进度；Lane 通过作用域访问自己的账户。 */
        private final TradingRuntimeState runtime;
        /** 本页固定币对、结算资产和标记价，禁止中途切换价格。 */
        private final CoreInstrumentState instrument;
        private final int symbolId, settleAssetId;
        private final long fundingMark, fundingPriceSequence;
        /** 原有全局用户分页及其 Lane 参与范围。 */
        private final UserPage userPage;
        private final ImmutableLongArrayList selectedUserIds;
        private final boolean chunked;
        private final long laneMask;
        private final LongArrayList[] usersByLane;
        /** 一次派发一次终态，重复轮询不重复收付资金费。 */
        private boolean started;
        private FundingResult result;

        private FundingWork(ApplyFundingCommand command, UUID chunkCommandId, TradingRuntimeState runtime,
                CoreInstrumentState instrument, int symbolId, int settleAssetId, long fundingMark,
                long fundingPriceSequence, UserPage userPage) {
            this.command = command; this.chunkCommandId = chunkCommandId; this.runtime = runtime;
            this.instrument = instrument; this.symbolId = symbolId; this.settleAssetId = settleAssetId;
            this.fundingMark = fundingMark; this.fundingPriceSequence = fundingPriceSequence;
            this.userPage = userPage; this.selectedUserIds = userPage.userIds();
            this.chunked = chunkCommandId != null;
            this.usersByLane = groupUsers(selectedUserIds, runtime);
            long mask = 0;
            for (int lane = 0; lane < usersByLane.length; lane++) {
                if (!usersByLane[lane].isEmpty()) mask |= 1L << lane;
            }
            laneMask = mask;
        }

        private LaneFundingResult applyLane(int laneId) {
            return RuntimePerpetualFundingProcessor.applyLane(command, usersByLane[laneId], runtime,
                    instrument, symbolId, settleAssetId, fundingMark);
        }

        public boolean poll() {
            runtime.assertOwner();
            if (result != null) return true;
            if (!started) {
                started = true;
                if (laneMask != 0) runtime.dispatchControlLanes(laneMask, this::applyLane);
            }
            if (laneMask != 0 && !runtime.pollControlLanes()) return false;
            result = finish();
            return true;
        }

        public FundingResult result() {
            if (result == null) throw new IllegalStateException("funding has not completed");
            return result;
        }

        private FundingResult finish(Object[] laneResults) {
            ArrayList<CoreFundingPaymentView> payments = new ArrayList<>();
            RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
            for (Object value : laneResults) {
                if (!(value instanceof LaneFundingResult laneResult)) continue;
                payments.addAll(laneResult.payments());
                treasuryDelta.merge(laneResult.treasuryDelta());
                for (long userId : laneResult.changedUserIds()) {
                    runtime.markBalanceChanged(userId, settleAssetId);
                }
            }
            treasuryDelta.apply(runtime.treasury());

            boolean complete = !chunked || userPage.complete();
            long nextCursorUserId = complete ? 0 : userPage.nextCursorUserId();
            if (complete) {
                runtime.treasury().setFundingSettlement(symbolId, command.settlementId());
            } else {
                runtime.treasury().setFundingProgress(symbolId, new TreasuryRuntime.FundingProgressRuntime(
                        command.settlementId(), command.instrumentChangeId(), command.fundingRatePpm(),
                        userPage.accountLaneId(), nextCursorUserId, chunkCommandId, fundingMark, fundingPriceSequence));
            }
            runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
            payments.sort(java.util.Comparator.comparingLong(CoreFundingPaymentView::userId));
            return new FundingResult(runtime, payments, new CoreFundingProgressView(command.settlementId(), complete,
                    nextCursorUserId, selectedUserIds.size()));
        }

        private FundingResult finish() {
            ArrayList<CoreFundingPaymentView> payments = new ArrayList<>();
            RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
            for (int lane = 0; lane < usersByLane.length; lane++) {
                if ((laneMask & (1L << lane)) == 0) continue;
                Object value = runtime.controlLaneResult(lane);
                if (!(value instanceof LaneFundingResult laneResult)) continue;
                payments.addAll(laneResult.payments());
                treasuryDelta.merge(laneResult.treasuryDelta());
                for (long userId : laneResult.changedUserIds()) runtime.markBalanceChanged(userId, settleAssetId);
            }
            treasuryDelta.apply(runtime.treasury());
            boolean complete = !chunked || userPage.complete();
            long nextCursorUserId = complete ? 0 : userPage.nextCursorUserId();
            if (complete) runtime.treasury().setFundingSettlement(symbolId, command.settlementId());
            else runtime.treasury().setFundingProgress(symbolId, new TreasuryRuntime.FundingProgressRuntime(
                    command.settlementId(), command.instrumentChangeId(), command.fundingRatePpm(),
                    userPage.accountLaneId(), nextCursorUserId, chunkCommandId, fundingMark, fundingPriceSequence));
            runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
            payments.sort(java.util.Comparator.comparingLong(CoreFundingPaymentView::userId));
            return new FundingResult(runtime, payments, new CoreFundingProgressView(command.settlementId(), complete,
                    nextCursorUserId, selectedUserIds.size()));
        }
    }

    private static LaneFundingResult applyLane(ApplyFundingCommand command, LongArrayList selectedUserIds,
                                               TradingRuntimeState runtime, CoreInstrumentState instrument,
                                               int symbolId, int settleAssetId, long markPriceTicks) {
        ProductTradingRules kernel = ProductTradingRulesRegistry.forInstrument(instrument);
        ArrayList<CoreFundingPaymentView> payments = new ArrayList<>();
        LongArrayBuilder changedUserIds = new LongArrayBuilder(Math.min(16, selectedUserIds.size()));
        RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
        for (int userIndex = 0; userIndex < selectedUserIds.size(); userIndex++) {
            long userId = selectedUserIds.get(userIndex);
            LongArrayList positionKeys = runtime.positionKeysForUserAndSymbolPrimitive(userId, symbolId);
            if (positionKeys.isEmpty()) continue;
            long requestedDelta = 0;
            for (int positionIndex = 0; positionIndex < positionKeys.size(); positionIndex++) {
                long positionKey = positionKeys.get(positionIndex);
                PositionRuntime position = runtime.position(positionKey);
                long positionDelta = kernel.fundingDeltaUnits(instrument,
                        position.signedQuantitySteps(), markPriceTicks, command.fundingRatePpm());
                requestedDelta = Math.addExact(requestedDelta, positionDelta);
            }

            BalanceRuntime balance = runtime.balance(userId, settleAssetId);
            if (balance == null) {
                throw new CoreStateRejectedException("BALANCE_NOT_FOUND", "required balance is missing");
            }
            long appliedDelta = requestedDelta >= 0 ? requestedDelta
                    : Math.negateExact(Math.min(balance.availableUnits(), Math.negateExact(requestedDelta)));
            if (appliedDelta != 0) {
                runtime.replaceBalance(new BalanceRuntime(userId, settleAssetId,
                        Math.addExact(balance.availableUnits(), appliedDelta), balance.lockedUnits()));
                treasuryDelta.addFundingResidual(settleAssetId, Math.negateExact(appliedDelta));
                runtime.advanceUserRevision(userId);
                changedUserIds.add(userId);
            }

            long debitRelief = Math.subtractExact(appliedDelta, requestedDelta);
            for (int positionIndex = 0; positionIndex < positionKeys.size(); positionIndex++) {
                long positionKey = positionKeys.get(positionIndex);
                PositionRuntime position = runtime.position(positionKey);
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
                    payments.add(new CoreFundingPaymentView(command.settlementId(), userId, instrument.symbol(),
                            position.marginMode(), position.positionSide(), instrument.settleAsset(),
                            position.signedQuantitySteps(), notional, command.fundingRatePpm(), amount));
                }
            }
            if (debitRelief != 0) {
                throw new IllegalStateException("runtime funding debit relief was not fully allocated");
            }
        }
        return new LaneFundingResult(payments, changedUserIds.freeze(), treasuryDelta);
    }

    private static LongArrayList[] groupUsers(ImmutableLongArrayList userIds, TradingRuntimeState runtime) {
        LongArrayList[] groups = new LongArrayList[runtime.topology().accountLaneCount()];
        for (int lane = 0; lane < groups.length; lane++) groups[lane] = new LongArrayList();
        for (int index = 0; index < userIds.size(); index++) {
            long userId = userIds.valueAt(index);
            groups[runtime.topology().accountLaneId(userId)].add(userId);
        }
        return groups;
    }

    public record FundingResult(TradingRuntimeState state, List<CoreFundingPaymentView> payments,
                                CoreFundingProgressView progress) {
        public FundingResult {
            if (state == null || payments == null || progress == null) {
                throw new IllegalArgumentException("invalid runtime funding result");
            }
            payments = List.copyOf(payments);
        }
    }

    private record LaneFundingResult(List<CoreFundingPaymentView> payments, ImmutableLongArrayList changedUserIds,
                                     RuntimeTreasuryDelta treasuryDelta) {
    }

    static UserPage selectUsers(Iterable<Long> indexedUserIds, long startCursorUserId, int limit) {
        if (limit <= 0) return new UserPage(ImmutableLongArrayList.empty(), 0, 0, true);
        LongArrayBuilder selected = new LongArrayBuilder(Math.min(limit, 64));
        Iterable<Long> usersAfterCursor = indexedUserIds;
        if (indexedUserIds instanceof NavigableSet<?> indexedSet) {
            @SuppressWarnings("unchecked")
            NavigableSet<Long> userIds = (NavigableSet<Long>) indexedSet;
            usersAfterCursor = userIds.tailSet(startCursorUserId, false);
        }
        for (Long userId : usersAfterCursor) {
            if (userId == null || userId <= startCursorUserId) continue;
            if (selected.size() == limit) {
                return new UserPage(selected.freeze(), 0, selected.last(), false);
            }
            selected.add(userId.longValue());
        }
        return new UserPage(selected.freeze(), 0, 0, true);
    }

    record UserPage(ImmutableLongArrayList userIds, int accountLaneId,
                    long nextCursorUserId, boolean complete) {
    }

    /** Small append-only primitive builder used for one funding page/result. */
    private static final class LongArrayBuilder {
        private long[] values;
        private int size;

        private LongArrayBuilder(int initialCapacity) {
            values = new long[Math.max(1, initialCapacity)];
        }

        private void add(long value) {
            if (size == values.length) values = Arrays.copyOf(values, values.length << 1);
            values[size++] = value;
        }

        private int size() {
            return size;
        }

        private long last() {
            if (size == 0) throw new IllegalStateException("empty primitive page");
            return values[size - 1];
        }

        private ImmutableLongArrayList freeze() {
            if (size == 0) return ImmutableLongArrayList.empty();
            return ImmutableLongArrayList.takeOwnership(
                    size == values.length ? values : Arrays.copyOf(values, size));
        }
    }
}
