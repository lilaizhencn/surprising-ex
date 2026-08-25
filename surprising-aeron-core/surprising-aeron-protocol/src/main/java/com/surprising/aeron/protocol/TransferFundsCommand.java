package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.util.Locale;
import java.util.Objects;

public record TransferFundsCommand(
        long transferId,
        ProductLine sourceProductLine,
        ProductLine targetProductLine,
        String sourceAccountType,
        String targetAccountType,
        String asset,
        long amountUnits,
        String referenceId,
        String reason) {

    public TransferFundsCommand {
        if (transferId <= 0 || amountUnits <= 0) {
            throw new IllegalArgumentException("transferId and amountUnits must be positive");
        }
        Objects.requireNonNull(sourceProductLine, "sourceProductLine");
        Objects.requireNonNull(targetProductLine, "targetProductLine");
        if (sourceProductLine == targetProductLine) {
            throw new IllegalArgumentException("source and target product lines must differ");
        }
        sourceAccountType = normalized(sourceAccountType, "sourceAccountType", 32);
        targetAccountType = normalized(targetAccountType, "targetAccountType", 32);
        if (productLine(sourceAccountType) != sourceProductLine
                || productLine(targetAccountType) != targetProductLine) {
            throw new IllegalArgumentException("transfer account type does not match product line");
        }
        asset = normalized(asset, "asset", 20);
        referenceId = text(referenceId, "referenceId", 128, false);
        reason = text(reason, "reason", 256, true);
    }

    private static String normalized(String value, String field, int maximumLength) {
        return text(value, field, maximumLength, false).toUpperCase(Locale.ROOT);
    }

    private static String text(String value, String field, int maximumLength, boolean optional) {
        String normalized = value == null ? "" : value.trim();
        if ((!optional && normalized.isEmpty()) || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " length is invalid");
        }
        return normalized;
    }

    private static ProductLine productLine(String accountType) {
        return switch (accountType) {
            case "FUNDING", "SPOT" -> ProductLine.SPOT;
            case "USDT_PERPETUAL" -> ProductLine.LINEAR_PERPETUAL;
            case "COIN_PERPETUAL" -> ProductLine.INVERSE_PERPETUAL;
            case "USDT_DELIVERY" -> ProductLine.LINEAR_DELIVERY;
            case "COIN_DELIVERY" -> ProductLine.INVERSE_DELIVERY;
            case "OPTION" -> ProductLine.OPTION;
            default -> throw new IllegalArgumentException("unsupported transfer account type: " + accountType);
        };
    }
}
