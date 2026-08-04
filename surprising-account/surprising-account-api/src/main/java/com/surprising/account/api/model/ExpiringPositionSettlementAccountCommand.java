package com.surprising.account.api.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;

/** 用户分区内执行交割或期权到期行权的结算命令。 */
public record ExpiringPositionSettlementAccountCommand(
        String symbol,
        long instrumentVersion,
        MarginMode marginMode,
        PositionSide positionSide,
        long settlementPriceTicks,
        long cashSettlementUnitsPerContract,
        String referenceType,
        String reason,
        Instant eventTime) {

    public ExpiringPositionSettlementAccountCommand {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        symbol = symbol.trim().toUpperCase();
        if (!symbol.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
        if (instrumentVersion <= 0 || settlementPriceTicks < 0 || cashSettlementUnitsPerContract < 0L) {
            throw new IllegalArgumentException("到期结算价标识无效");
        }
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
        if (referenceType == null || referenceType.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("referenceType and reason are required");
        }
        referenceType = referenceType.trim().toUpperCase();
        if (!referenceType.equals("DELIVERY_SETTLEMENT") && !referenceType.equals("OPTION_EXERCISE")) {
            throw new IllegalArgumentException("不支持的到期结算类型: " + referenceType);
        }
        if (referenceType.equals("DELIVERY_SETTLEMENT") && settlementPriceTicks <= 0L) {
            throw new IllegalArgumentException("交割结算价必须为正数");
        }
        if (referenceType.equals("DELIVERY_SETTLEMENT") && cashSettlementUnitsPerContract != 0L) {
            throw new IllegalArgumentException("交割命令不能携带期权现金收益");
        }
        reason = reason.trim();
        eventTime = eventTime == null ? Instant.now() : eventTime;
    }
}
