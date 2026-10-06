package com.surprising.aeron.protocol;

/** 后台订单价格保护规则；随合约注册写入 Core 日志及快照。 */
public record CoreOrderProtection(long marketMaxSlippagePpm, long marketMaxMarkAgeMs,
        boolean limitPriceProtectionEnabled, long limitPriceBandPpm, long limitPriceMaxMarkAgeMs) {
    public CoreOrderProtection {
        if (marketMaxSlippagePpm < 0 || marketMaxSlippagePpm >= 1_000_000
                || limitPriceBandPpm < 0 || limitPriceBandPpm >= 1_000_000
                || marketMaxMarkAgeMs < 1 || marketMaxMarkAgeMs > 600_000
                || limitPriceMaxMarkAgeMs < 1 || limitPriceMaxMarkAgeMs > 600_000)
            throw new IllegalArgumentException("invalid order price protection");
    }
    /** 旧日志的既定规则，以及新产品线数据库初始化值。 */
    public static CoreOrderProtection initial() { return new CoreOrderProtection(10_000, 5_000, false, 50_000, 5_000); }
}
