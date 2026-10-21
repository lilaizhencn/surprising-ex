package com.surprising.realtime.provider.export.model;

import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;

/** 本批提交事实的原生持久化行；不保存在线账户状态或平行历史缓存。 */
public final class CommittedProjectionRows {
    private CommittedProjectionRows() { }
    public record ExecutionWrite(String tradeId, long position, int index, long takerOrder, long makerOrder,
            long takerUser, long makerUser, String instrument, String side, String takerPositionSide,
            String makerPositionSide, long takerFeeRate, long makerFeeRate, long price, long quantity, long timestamp) {}
    public record TriggerWrite(CoreTriggerOrderStateView state, byte[] raw) {}
    public record FundingCursor(long current, long next, long rate, boolean complete) {}
    public record OrderWrite(CoreOrderStateView order, byte[] raw) {}
    public record DeliveryLedgerEntry(long userId, String account, String asset, long amountUnits,
            long balanceAfterUnits, String referenceType, String reference, String instrumentId,
            String reason, java.sql.Timestamp occurred) { }
    public record FundingTotals(java.math.BigDecimal longPayment, java.math.BigDecimal shortPayment, int positions) { }

}
