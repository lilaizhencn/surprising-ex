package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.CoreFundingPaymentView;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.instrument.api.math.PerpetualContractMath;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.UUID;

public final class RuntimePerpetualFundingProcessor {

    private RuntimePerpetualFundingProcessor() {
    }

    public static FundingResult simulate(TradingCoreState before, ApplyFundingCommand command,
                                         Iterable<Long> indexedUserIds, UUID chunkCommandId,
                                         RuntimeIdentityRegistry identities) {
        if (before == null || command == null || identities == null) {
            throw new IllegalArgumentException("invalid perpetual funding simulation");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        Iterable<Long> users = indexedUserIds == null ? before.users().keySet() : indexedUserIds;
        return applyRuntime(command, users, chunkCommandId, runtime, identities);
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
        if (instrument.version() != command.instrumentVersion()) {
            throw new CoreStateRejectedException("INSTRUMENT_VERSION_CONFLICT", "instrument version differs");
        }
        SettlementKernel kernel = SettlementKernels.forInstrument(instrument);
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
        boolean chunked = chunkCommandId != null;
        if (chunked) {
            if (previousProgress == null && command.cursorUserId() != 0) {
                throw new CoreStateRejectedException("INVALID_COMMAND", "funding cursor must start at zero");
            }
            if (previousProgress != null && (previousProgress.settlementId() != command.settlementId()
                    || previousProgress.instrumentVersion() != command.instrumentVersion()
                    || previousProgress.fundingRatePpm() != command.fundingRatePpm()
                    || previousProgress.nextCursorUserId() != command.cursorUserId())) {
                throw new CoreStateRejectedException("INVALID_COMMAND", "funding cursor does not match progress");
            }
        }

        UserPage userPage = selectUsers(indexedUserIds, command.cursorUserId(),
                chunked ? command.maxUsers() : Integer.MAX_VALUE);
        ArrayList<Long> selectedUserIds = userPage.userIds();

        Object[] laneResults = runtime.executeLifecycleSettlements(selectedUserIds, Long::longValue,
                ignored -> applyLane(command, selectedUserIds, runtime, instrument, symbolId,
                        settleAssetId, mark.markPriceTicks()));
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
                    command.settlementId(), command.instrumentVersion(), command.fundingRatePpm(),
                    userPage.accountLaneId(), nextCursorUserId, chunkCommandId));
        }
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
        payments.sort(java.util.Comparator.comparingLong(CoreFundingPaymentView::userId));
        return new FundingResult(runtime, payments, new CoreFundingProgressView(command.settlementId(), complete,
                nextCursorUserId, selectedUserIds.size()));
    }

    private static LaneFundingResult applyLane(ApplyFundingCommand command, Iterable<Long> selectedUserIds,
                                               TradingRuntimeState runtime, CoreInstrumentState instrument,
                                               int symbolId, int settleAssetId, long markPriceTicks) {
        SettlementKernel kernel = SettlementKernels.forInstrument(instrument);
        ArrayList<CoreFundingPaymentView> payments = new ArrayList<>();
        ArrayList<Long> changedUserIds = new ArrayList<>();
        RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
        for (Long userId : selectedUserIds) {
            if (userId == null || !runtime.currentLaneOwns(userId)) continue;
            NavigableSet<Long> positionKeys = runtime.positionKeysForUserAndSymbol(userId, symbolId);
            if (positionKeys.isEmpty()) continue;
            long requestedDelta = 0;
            for (long positionKey : positionKeys) {
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
            for (long positionKey : positionKeys) {
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
        return new LaneFundingResult(payments, changedUserIds, treasuryDelta);
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

    private record LaneFundingResult(List<CoreFundingPaymentView> payments, List<Long> changedUserIds,
                                     RuntimeTreasuryDelta treasuryDelta) {
    }

    static UserPage selectUsers(Iterable<Long> indexedUserIds, long startCursorUserId, int limit) {
        ArrayList<Long> selected = new ArrayList<>();
        Iterable<Long> usersAfterCursor = indexedUserIds;
        if (indexedUserIds instanceof NavigableSet<?> indexedSet) {
            @SuppressWarnings("unchecked")
            NavigableSet<Long> userIds = (NavigableSet<Long>) indexedSet;
            usersAfterCursor = userIds.tailSet(startCursorUserId, false);
        }
        for (Long userId : usersAfterCursor) {
            if (userId == null || userId <= startCursorUserId) continue;
            if (selected.size() == limit) {
                return new UserPage(selected, 0, selected.getLast(), false);
            }
            selected.add(userId);
        }
        return new UserPage(selected, 0, 0, true);
    }

    record UserPage(ArrayList<Long> userIds, int accountLaneId,
                    long nextCursorUserId, boolean complete) {
    }
}
