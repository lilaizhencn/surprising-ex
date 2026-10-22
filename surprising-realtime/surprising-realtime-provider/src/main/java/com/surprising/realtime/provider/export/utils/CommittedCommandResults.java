package com.surprising.realtime.provider.export.utils;

import com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedCommandResult;
import java.util.Objects;

/** 提交回执的不可变事实比较，批内去重和数据库重放校验共享同一规则。 */
public final class CommittedCommandResults {
    private CommittedCommandResults() { }
    public static void requireSameResult(CommittedCommandResult expected, CommittedCommandResult actual) {
        if (expected.userId() != actual.userId() || expected.commandType() != actual.commandType()
                || !Objects.equals(expected.clientOrderId(), actual.clientOrderId())
                || !Objects.equals(expected.instrumentId(), actual.instrumentId())
                || expected.status() != actual.status() || expected.code() != actual.code()
                || expected.appliedCommandCount() != actual.appliedCommandCount()
                || (!expected.cancelResults().isEmpty() && !actual.cancelResults().isEmpty()
                    && !expected.cancelResults().equals(actual.cancelResults())))
            throw new IllegalStateException("conflicting committed command result");
    }
}
