package com.surprising.aeron.model;

/**
 * 风险估值给出的三档结论，具体分界由风险策略计算，状态本身不执行强平。
 */
public enum CoreRiskStatus {
    /** 风险占比处于正常范围。 */
    NORMAL,
    /** 风险占比已经进入预警范围，需要关注保证金。 */
    WARNING,
    /** 风险占比达到强平条件，后续由强平流程处理。 */
    LIQUIDATION;

    /** 维持保证金占权益达到八十万（按百万分之一保存）进入预警等级。 */
    static final long WARNING_MARGIN_RATIO_PPM = 800_000L;
    /** 维持保证金占权益达到一百万（按百万分之一保存）进入强平等级。 */
    static final long LIQUIDATION_MARGIN_RATIO_PPM = 1_000_000L;

    /**
     * 按包含边界的阈值判级：达到一百万为强平，否则达到八十万为预警，其余为正常。
     * @param ratioPpm 维持保证金相对权益的风险占比，按一百万倍保存，越大风险越高
     */
    public static CoreRiskStatus of(long ratioPpm) {
        return ratioPpm >= LIQUIDATION_MARGIN_RATIO_PPM ? LIQUIDATION
                : ratioPpm >= WARNING_MARGIN_RATIO_PPM ? WARNING : NORMAL;
    }
}
