package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.instrument.CoreInstrumentState;

import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.CoreFundingPaymentView;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.service.business.ProductTradingRules;
import com.surprising.aeron.service.business.ProductTradingRulesRegistry;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.model.CoreMarkPriceState;
import com.surprising.aeron.service.state.model.CorePositionState;
import com.surprising.aeron.service.state.ReducerSettlementSupport.CashResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.surprising.aeron.service.state.ReducerSettlementSupport.applyCash;
import static com.surprising.aeron.service.state.ReducerSettlementSupport.requireBalance;

/** Owns the authoritative TradingCoreState funding settlement and its payment facts. */
final class FundingStateTransitions {

    private FundingStateTransitions() {
    }

    static TradingCoreReducer.FundingApplication apply(
            TradingCoreState state, ApplyFundingCommand command,
            Iterable<Long> indexedUserIds, UUID chunkCommandId) {
        if (!state.productLine().isFundingProduct()) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED", "funding requires perpetual product");
        }
        CoreInstrumentState instrument = requireInstrument(state, command.symbol(), command.instrumentChangeId());
        ProductTradingRules kernel = ProductTradingRulesRegistry.forInstrument(instrument);
        long previousSettlement = state.treasuryState().fundingSettlements()
                .getOrDefault(instrument.symbol(), 0L);
        if (command.settlementId() <= previousSettlement) {
            throw new CoreStateRejectedException("STALE_SETTLEMENT_ID", "funding settlement id must increase");
        }
        CoreMarkPriceState mark = state.riskState().markPrices().get(instrument.symbol());
        if (mark == null) {
            throw new CoreStateRejectedException("MARK_PRICE_NOT_FOUND", "funding requires mark price");
        }
        CoreTreasuryState.FundingProgress previousProgress = state.treasuryState()
                .fundingProgress(instrument.symbol());
        long fundingMark = previousProgress == null ? mark.markPriceTicks() : previousProgress.markPriceTicks();
        long fundingPriceSequence = previousProgress == null ? mark.priceSequence() : previousProgress.priceSequence();
        boolean chunked = indexedUserIds != null && chunkCommandId != null;
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
        Map<Long, CoreUserState> users = StateMapSupport.delta(state.users());
        CoreTreasuryState treasury = state.treasuryState();
        ArrayList<CoreFundingPaymentView> payments = new ArrayList<>();
        ArrayList<Long> selectedUserIds = new ArrayList<>();
        boolean moreUsers = false;
        if (!chunked) {
            state.users().keySet().forEach(selectedUserIds::add);
        } else {
            for (Long userId : indexedUserIds) {
                if (userId == null || userId <= command.cursorUserId()) continue;
                if (selectedUserIds.size() < command.maxUsers()) {
                    selectedUserIds.add(userId);
                } else {
                    moreUsers = true;
                    break;
                }
            }
        }
        for (Long userId : selectedUserIds) {
            CoreUserState user = state.user(userId);
            if (user == null) continue;
            long delta = 0;
            List<CorePositionState> positions = positionsForSymbol(user, instrument.symbol());
            ArrayList<Long> positionDeltas = new ArrayList<>(positions.size());
            for (CorePositionState position : positions) {
                long positionDelta = kernel.fundingDeltaUnits(instrument,
                        position.signedQuantitySteps(), fundingMark, command.fundingRatePpm());
                positionDeltas.add(positionDelta);
                delta = Math.addExact(delta, positionDelta);
            }
            if (positions.isEmpty()) continue;
            CashResult result = applyCash(requireBalance(user, instrument.settleAsset()), delta);
            if (result.appliedDelta() != 0) {
                Map<String, AssetBalance> balances = StateMapSupport.delta(user.balances());
                balances.put(instrument.settleAsset(), result.balance());
                users.put(user.userId(), user.transition(Math.incrementExact(user.revision()),
                        balances, user.reservations(), user.positions(), user.positionMode()));
                treasury = treasury.adjustFundingResidual(
                        instrument.settleAsset(), Math.negateExact(result.appliedDelta()));
            }
            long debitRelief = Math.subtractExact(result.appliedDelta(), delta);
            for (int index = 0; index < positions.size(); index++) {
                CorePositionState position = positions.get(index);
                long amount = positionDeltas.get(index);
                if (amount < 0 && debitRelief > 0) {
                    long relief = Math.min(Math.negateExact(amount), debitRelief);
                    amount = Math.addExact(amount, relief);
                    debitRelief = Math.subtractExact(debitRelief, relief);
                }
                if (amount != 0) {
                    long notional = com.surprising.instrument.api.math.PerpetualContractMath.notionalUnits(
                            instrument.contractType(), position.signedQuantitySteps(), fundingMark,
                            instrument.notionalMultiplierUnits(), instrument.priceTickUnits(),
                            instrument.settleScaleUnits());
                    payments.add(new CoreFundingPaymentView(
                            command.settlementId(), user.userId(), instrument.symbol(), position.marginMode(),
                            position.positionSide(), instrument.settleAsset(), position.signedQuantitySteps(),
                            notional, command.fundingRatePpm(), amount));
                }
            }
            if (debitRelief != 0) throw new IllegalStateException("funding debit relief was not fully allocated");
        }
        boolean complete = !chunked || !moreUsers;
        long nextCursorUserId = complete ? 0 : selectedUserIds.getLast();
        if (complete) {
            treasury = treasury.recordFunding(instrument.symbol(), command.settlementId());
        } else {
            UUID progressCommandId = chunkCommandId == null ? new UUID(0, 0) : chunkCommandId;
            treasury = treasury.withFundingProgress(instrument.symbol(), new CoreTreasuryState.FundingProgress(
                    command.settlementId(), command.instrumentChangeId(), command.fundingRatePpm(),
                    0, nextCursorUserId, progressCommandId, fundingMark, fundingPriceSequence));
        }
        CoreFundingProgressView progress = new CoreFundingProgressView(
                command.settlementId(), complete, nextCursorUserId, selectedUserIds.size());
        TradingCoreState nextState = new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                users, state.orders(), state.instruments(), state.riskState(), treasury,
                state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(), state.clientOrderIndex(),
                state.triggerOrders());
        return new TradingCoreReducer.FundingApplication(nextState, payments, progress);
    }

    private static List<CorePositionState> positionsForSymbol(CoreUserState user, String symbol) {
        String normalized = OrderReservation.normalizeSymbol(symbol);
        return user.positions().values().stream()
                .filter(position -> position.symbol().equals(normalized) && position.signedQuantitySteps() != 0)
                .toList();
    }

    private static CoreInstrumentState requireInstrument(TradingCoreState state, String symbol, long version) {
        CoreInstrumentState instrument = state.instruments().get(OrderReservation.normalizeSymbol(symbol));
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        if (instrument.changeId() != version) {
            throw new CoreStateRejectedException("INSTRUMENT_CHANGE_ID_CONFLICT", "instrument version differs");
        }
        return instrument;
    }
}
