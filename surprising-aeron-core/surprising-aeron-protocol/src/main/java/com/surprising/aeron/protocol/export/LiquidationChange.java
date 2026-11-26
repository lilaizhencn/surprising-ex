package com.surprising.aeron.protocol.export;

/** 原已提交日志位置、业务时间和不可变强平后值，不复制账户状态。 */
public record LiquidationChange(LiquidationOrder state, long logPosition, long occurredAt) {}
