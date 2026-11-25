package com.surprising.instrument.api.model;

/** 合约变更审计记录视图。 */
public record InstrumentChangeEntry(
        String changeId,
        String operatorId,
        String reason,
        String changedAt,
        String beforeValues,
        String afterValues) {
}
