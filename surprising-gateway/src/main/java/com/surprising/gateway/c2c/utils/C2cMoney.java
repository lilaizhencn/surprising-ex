package com.surprising.gateway.c2c.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** C2C 报价边界只使用资产整数及精确十进制；不接受浮点、科学计数法或隐式舍入的数量。 */
public final class C2cMoney {
    private C2cMoney() { }

    public static BigDecimal decimal(String value) {
        if (value == null || value.length() > 80 || !value.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?"))
            throw new IllegalArgumentException("请输入普通十进制金额");
        var amount = new BigDecimal(value);
        if (amount.signum() <= 0) throw new IllegalArgumentException("金额必须大于零");
        return amount;
    }

    public static long quantityUnits(String quantity, long assetScale) {
        requireScale(assetScale);
        return decimal(quantity).multiply(BigDecimal.valueOf(assetScale)).longValueExact();
    }

    public static long unitsForFiat(String fiat, BigDecimal price, long assetScale, int fiatDecimals) {
        requirePrice(price, assetScale, fiatDecimals);
        var amount = decimal(fiat).setScale(fiatDecimals, RoundingMode.UNNECESSARY);
        long units = amount.multiply(BigDecimal.valueOf(assetScale)).divide(price, 0, RoundingMode.DOWN).longValueExact();
        if (units <= 0) throw new IllegalArgumentException("金额小于最小资产数量");
        return units;
    }

    public static BigDecimal fiatAmount(long quantityUnits, BigDecimal price, long assetScale, int fiatDecimals) {
        requirePrice(price, assetScale, fiatDecimals);
        if (quantityUnits <= 0) throw new IllegalArgumentException("资产数量必须大于零");
        return BigDecimal.valueOf(quantityUnits).multiply(price)
                .divide(BigDecimal.valueOf(assetScale), fiatDecimals, RoundingMode.HALF_UP);
    }

    public static long buyerFee(long quantityUnits, int feePpm) {
        if (quantityUnits <= 0 || feePpm < 0 || feePpm >= 1_000_000)
            throw new IllegalArgumentException("手续费配置或数量无效");
        long fee = BigDecimal.valueOf(quantityUnits).multiply(BigDecimal.valueOf(feePpm))
                .divide(BigDecimal.valueOf(1_000_000), 0, RoundingMode.UP).longValueExact();
        if (fee >= quantityUnits) throw new IllegalArgumentException("数量不足以支付手续费");
        return fee;
    }

    private static void requirePrice(BigDecimal price, long scale, int fiatDecimals) {
        requireScale(scale);
        if (price == null || price.signum() <= 0 || price.precision() > 38 || price.scale() > 18
                || fiatDecimals < 0 || fiatDecimals > 8)
            throw new IllegalArgumentException("报价或法币精度无效");
    }

    private static void requireScale(long scale) {
        if (scale <= 0 || !Long.toString(scale).matches("10*"))
            throw new IllegalArgumentException("资产精度未就绪");
    }
}
