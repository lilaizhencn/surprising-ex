package com.surprising.aeron.service.risk;

import com.surprising.aeron.model.RiskLaneProgress;
import java.util.List;

/** Owner 持有的币对扫描元数据。账户风险进度只保存在各 Lane 游标中，不复制选中 Lane 的摘要。 */
public record RiskScanRuntime(
        int symbolId, int accountLaneId, long priceSequence, long scanStartPriceSequence,
        boolean riskComplete, boolean triggerComplete, int triggerPhase,
        long triggerPriceCursor, long triggerOrderCursor, long triggerUpperId,
        long triggerMarkPriceTicks, long triggerGeneratedAtEpochMillis,
        long triggerOcoOrderId, long triggerOcoCursor, long lastScheduledRevision,
        List<RiskLaneProgress> laneProgress) {
    public RiskScanRuntime {
        laneProgress = List.copyOf(laneProgress);
        if (laneProgress.size() > Long.SIZE || symbolId < 0 || accountLaneId < 0 || accountLaneId >= Long.SIZE
                || !laneProgress.isEmpty() && accountLaneId >= laneProgress.size()
                || priceSequence < 0 || scanStartPriceSequence < 0 || scanStartPriceSequence > priceSequence
                || triggerPhase < 0 || triggerPriceCursor < 0 || triggerOrderCursor < 0
                || triggerUpperId < 0 || triggerMarkPriceTicks < 0 || triggerGeneratedAtEpochMillis < 0
                || triggerOcoOrderId < 0 || triggerOcoCursor < 0 || lastScheduledRevision < 0) {
            throw new IllegalArgumentException("invalid runtime risk scan");
        }
    }

    /** 批次续扫的确切用户位置来自当前轮转 Lane；尚未分派时从用户 0 开始。 */
    public long lastUserId() {
        return laneProgress.isEmpty() ? 0 : laneProgress.get(accountLaneId).lastUserId();
    }

    public boolean complete() {
        return riskComplete && triggerComplete;
    }

    public RiskScanRuntime withTriggerProgress(boolean complete, int phase, long priceCursor, long orderCursor,
            long upperId, long markPriceTicks, long generatedAtEpochMillis) {
        return new RiskScanRuntime(symbolId, accountLaneId, priceSequence, scanStartPriceSequence,
                riskComplete, complete, phase, priceCursor, orderCursor, upperId, markPriceTicks,
                generatedAtEpochMillis, triggerOcoOrderId, triggerOcoCursor, lastScheduledRevision, laneProgress);
    }

    public RiskScanRuntime withTriggerOcoProgress(long orderId, long cursor) {
        return new RiskScanRuntime(symbolId, accountLaneId, priceSequence, scanStartPriceSequence,
                riskComplete, triggerComplete, triggerPhase, triggerPriceCursor, triggerOrderCursor,
                triggerUpperId, triggerMarkPriceTicks, triggerGeneratedAtEpochMillis, orderId, cursor,
                lastScheduledRevision, laneProgress);
    }
}
