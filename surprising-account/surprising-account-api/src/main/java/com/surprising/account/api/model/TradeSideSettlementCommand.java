package com.surprising.account.api.model;

import com.surprising.trading.api.model.MatchTradeEvent;

public record TradeSideSettlementCommand(
        MatchTradeEvent trade,
        TradeParticipantRole participantRole,
        long orderQuantitySteps,
        boolean reduceOnly,
        AccountType reservationAccountType,
        String reservationAsset,
        long reservedUnits) {

    public TradeSideSettlementCommand {
        if (trade == null || participantRole == null || orderQuantitySteps <= 0
                || orderQuantitySteps < trade.quantitySteps()) {
            throw new IllegalArgumentException("valid trade, participantRole and order quantity are required");
        }
        if (reservedUnits < 0L
                || (reservedUnits == 0L && (reservationAccountType != null || reservationAsset != null))
                || (reservedUnits > 0L && (reservationAccountType == null
                || reservationAsset == null || reservationAsset.isBlank()))) {
            throw new IllegalArgumentException("invalid trade-side reservation snapshot");
        }
    }

    public long userId() {
        return participantRole == TradeParticipantRole.TAKER ? trade.takerUserId() : trade.makerUserId();
    }

    public long orderId() {
        return participantRole == TradeParticipantRole.TAKER ? trade.takerOrderId() : trade.makerOrderId();
    }
}
