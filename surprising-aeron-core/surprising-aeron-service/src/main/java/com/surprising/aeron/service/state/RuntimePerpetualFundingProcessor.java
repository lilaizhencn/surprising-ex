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
        return apply(before, command, indexedUserIds, chunkCommandId, runtime, identities);
    }

    public static FundingResult apply(TradingCoreState before, ApplyFundingCommand command,
                                      Iterable<Long> indexedUserIds, UUID chunkCommandId,
                                      TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        if (before == null || command == null || runtime == null || identities == null) {
            throw new IllegalArgumentException("invalid perpetual funding apply");
        }
        runtime.assertOwner();
        if (!before.productLine().isFundingProduct()) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED", "funding requires perpetual product");
        }
        CoreInstrumentState instrument = before.instruments().get(OrderReservation.normalizeSymbol(command.symbol()));
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        if (instrument.version() != command.instrumentVersion()) {
            throw new CoreStateRejectedException("INSTRUMENT_VERSION_CONFLICT", "instrument version differs");
        }
        SettlementKernel kernel = SettlementKernels.forInstrument(instrument);
        CoreMarkPriceState mark = before.riskState().markPrices().get(instrument.symbol());
        if (mark == null) {
            throw new CoreStateRejectedException("MARK_PRICE_NOT_FOUND", "funding requires mark price");
        }

        int symbolId = identities.symbolId(instrument.symbol());
        int settleAssetId = identities.assetId(instrument.settleAsset());
        long previousSettlement = runtime.treasury().fundingSettlement(symbolId);
        if (command.settlementId() <= previousSettlement) {
            throw new CoreStateRejectedException("STALE_SETTLEMENT_ID", "funding settlement id must increase");
        }

        TreasuryRuntime.FundingProgressRuntime previousProgress = runtime.treasury().fundingProgress(symbolId);
        boolean chunked = indexedUserIds != null && chunkCommandId != null;
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

        ArrayList<Long> selectedUserIds = new ArrayList<>();
        boolean moreUsers = false;
        if (!chunked) {
            before.users().keySet().forEach(selectedUserIds::add);
        } else {
            for (Long userId : indexedUserIds) {
                if (userId == null || userId <= command.cursorUserId()) continue;
                if (selectedUserIds.size() < command.maxUsers()) selectedUserIds.add(userId);
                else {
                    moreUsers = true;
                    break;
                }
            }
        }

        ArrayList<CoreFundingPaymentView> payments = new ArrayList<>();
        for (Long userId : selectedUserIds) {
            NavigableSet<Long> positionKeys = runtime.positionKeysForUserAndSymbol(userId, symbolId);
            if (positionKeys.isEmpty()) continue;
            long requestedDelta = 0;
            for (long positionKey : positionKeys) {
                PositionRuntime position = runtime.position(positionKey);
                long positionDelta = kernel.fundingDeltaUnits(instrument,
                        position.signedQuantitySteps(), mark.markPriceTicks(), command.fundingRatePpm());
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
                runtime.treasury().setFundingResidual(settleAssetId, Math.addExact(
                        runtime.treasury().fundingResidual(settleAssetId), Math.negateExact(appliedDelta)));
                runtime.advanceUserRevision(userId);
            }

            long debitRelief = Math.subtractExact(appliedDelta, requestedDelta);
            for (long positionKey : positionKeys) {
                PositionRuntime position = runtime.position(positionKey);
                long amount = kernel.fundingDeltaUnits(instrument,
                        position.signedQuantitySteps(), mark.markPriceTicks(), command.fundingRatePpm());
                if (amount < 0 && debitRelief > 0) {
                    long relief = Math.min(Math.negateExact(amount), debitRelief);
                    amount = Math.addExact(amount, relief);
                    debitRelief = Math.subtractExact(debitRelief, relief);
                }
                if (amount != 0) {
                    long notional = PerpetualContractMath.notionalUnits(instrument.contractType(),
                            position.signedQuantitySteps(), mark.markPriceTicks(),
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

        boolean complete = !chunked || !moreUsers;
        long nextCursorUserId = complete ? 0 : selectedUserIds.getLast();
        if (complete) {
            runtime.treasury().setFundingSettlement(symbolId, command.settlementId());
        } else {
            runtime.treasury().setFundingProgress(symbolId, new TreasuryRuntime.FundingProgressRuntime(
                    command.settlementId(), command.instrumentVersion(), command.fundingRatePpm(),
                    nextCursorUserId, chunkCommandId));
        }
        runtime.setMetadata(before.productLine(), Math.incrementExact(before.revision()));
        return new FundingResult(runtime, payments, new CoreFundingProgressView(command.settlementId(), complete,
                nextCursorUserId, selectedUserIds.size()));
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
}
