package com.surprising.aeron.service.state.model;

/**
 * 风险估值给出的三档结论，具体分界由风险策略计算，状态本身不执行强平。
 */
public enum CoreRiskStatus {
    /** 风险占比处于正常范围。 */
    NORMAL,
    /** 风险占比已经进入预警范围，需要关注保证金。 */
    WARNING,
    /** 风险占比达到强平条件，后续由强平流程处理。 */
    LIQUIDATION
}
