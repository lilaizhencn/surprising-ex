package com.surprising.aeron.model;

/** 风险估值给出的三档结论，具体分界由风险策略计算，状态本身不执行强平。 */
public enum CoreRiskStatus {
    NORMAL,
    WARNING,
    LIQUIDATION;

    static final long WARNING_MARGIN_RATIO_PPM = 800_000L;
    static final long LIQUIDATION_MARGIN_RATIO_PPM = 1_000_000L;

    /** 按包含边界的阈值判级：达到一百万为强平，否则达到八十万为预警，其余为正常。 */
    public static CoreRiskStatus of(long ratioPpm) {
        return ratioPpm >= LIQUIDATION_MARGIN_RATIO_PPM ? LIQUIDATION
                : ratioPpm >= WARNING_MARGIN_RATIO_PPM ? WARNING : NORMAL;
    }
}
