package com.surprising.account.api.model;

import com.surprising.trading.api.model.MatchTradeEvent;

public record TradeSideSettlementCommand(
        MatchTradeEvent trade,
        TradeParticipantRole participantRole) {

    public TradeSideSettlementCommand {
        if (trade == null || participantRole == null) {
            throw new IllegalArgumentException("trade and participantRole are required");
        }
    }

    public long userId() {
        return participantRole == TradeParticipantRole.TAKER ? trade.takerUserId() : trade.makerUserId();
    }

    public long orderId() {
        return participantRole == TradeParticipantRole.TAKER ? trade.takerOrderId() : trade.makerOrderId();
    }
}
