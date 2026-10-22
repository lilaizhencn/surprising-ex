package com.surprising.account.provider.controller;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.account.api.model.*;

/** 实时账户HTTP/本地入口格式绑定；不读取账户或决定资金操作。 */
public final class AccountInputBinding {
    private AccountInputBinding() { }

    public static String asset(String asset) {
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("asset is required");
        }
        String value = asset.trim().toUpperCase(java.util.Locale.ROOT);
        if (!value.matches("[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("invalid asset: " + asset);
        }
        return value;
    }

    public static String instrument(String instrumentId) {
        if (instrumentId == null || instrumentId.isBlank()) {
            throw new IllegalArgumentException("instrumentId is required");
        }
        String normalized = instrumentId.trim().toUpperCase(java.util.Locale.ROOT);
        if (!com.surprising.product.api.utils.InstrumentIds.valid(normalized)) {
            throw new IllegalArgumentException("invalid instrumentId: " + instrumentId);
        }
        return normalized;
    }

    public static MarginMode marginMode(String value) {
        return value == null || value.isBlank() ? MarginMode.CROSS
                : MarginMode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }

    public static PositionSide positionSide(String value) {
        return value == null || value.isBlank()
                ? PositionSide.NET
                : PositionSide.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private static String reference(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("referenceId is required and must be <= 128 characters");
        }
        return value.trim();
    }

    private static void request(Object request) {
        if (request == null) throw new IllegalArgumentException("request is required");
    }

    public static BalanceAdjustmentRequest adjustment(BalanceAdjustmentRequest value) {
        request(value);
        return new BalanceAdjustmentRequest(value.userId(), asset(value.asset()), value.amountUnits(),
                reference(value.referenceId()), value.reason());
    }

    public static ProductBalanceAdjustmentRequest productAdjustment(ProductBalanceAdjustmentRequest value) {
        request(value);
        return new ProductBalanceAdjustmentRequest(value.userId(), value.accountType(), asset(value.asset()),
                value.amountUnits(), reference(value.referenceId()), value.reason());
    }

    public static PositionModeUpdateRequest positionMode(PositionModeUpdateRequest value) {
        request(value);
        return new PositionModeUpdateRequest(value.userId(), value.productLine(),
                value.positionMode(), reference(value.referenceId()));
    }

    public static PositionMarginAdjustmentRequest positionMargin(PositionMarginAdjustmentRequest value) {
        request(value);
        return new PositionMarginAdjustmentRequest(value.userId(), instrument(value.instrumentId()), value.marginMode(),
                value.positionSide(), value.amountUnits(), reference(value.referenceId()), value.reason());
    }

    public static PositionSide optionalPositionSide(String value) {
        return value == null || value.isBlank() ? null : positionSide(value);
    }
}
