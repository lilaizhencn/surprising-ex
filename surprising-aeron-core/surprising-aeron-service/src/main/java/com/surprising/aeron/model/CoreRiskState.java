package com.surprising.aeron.model;

import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.support.collection.StateMapSupport;
import java.util.List;
import java.util.Map;

/** 可恢复的整份风险状态，汇总行情、估值、强平记录及每个币对的扫描游标。构造时把映射冻结并排序，不能把这些映射当作账户 Lane 的可变热状态直接修改。 */
public record CoreRiskState(
        Map<String, CoreMarkPriceState> markPrices,
        Map<String, CoreRiskSnapshot> snapshots,
        Map<Long, CoreLiquidationState> liquidations,
        Map<String, RiskScan> scans,
        long nextLiquidationId,
        CoreRiskScanControlView scanControl,
        long marketRevision) {

    /** 要求各类状态映射和扫描配置完整，编号与修订号有效，再冻结排序所有映射，防止外部后续修改影响这份记录。 */
    public CoreRiskState {
        if (markPrices == null || snapshots == null || liquidations == null || scans == null
                || marketRevision < 0 || nextLiquidationId <= 0 || scanControl == null) {
            throw new IllegalArgumentException("invalid risk state");
        }
        markPrices = StateMapSupport.freezeSorted(markPrices);
        snapshots = StateMapSupport.freezeSorted(snapshots);
        liquidations = StateMapSupport.freezeSorted(liquidations);
        scans = StateMapSupport.freezeSorted(scans);
    }

    /** 创建没有行情、估值、强平和扫描任务的初始风险状态，强平编号从 1 开始。 */
    public static CoreRiskState empty() {
        return new CoreRiskState(Map.of(), Map.of(), Map.of(), Map.of(), 1, defaultScanControl(), 0);
    }

    /** 创建默认启用的扫描控制配置，调度间隔为 25 毫秒，每批工作量为 64。 */
    public static CoreRiskScanControlView defaultScanControl() {
        return new CoreRiskScanControlView(1, "Aeron risk scan control", true, 25L, 64,
                "system", "default", 0);
    }

    /** 币对扫描的可恢复元数据。 */
    public record RiskScan(
            String instrumentId, int accountLaneId, long priceSequence, long scanStartPriceSequence,
            boolean riskComplete, boolean triggerComplete, int triggerPhase,
            long triggerPriceCursor, long triggerOrderCursor, long triggerUpperId,
            long triggerMarkPriceTicks, long triggerGeneratedAtEpochMillis,
            long triggerOcoOrderId, long triggerOcoCursor, long lastScheduledRevision,
            List<RiskLaneProgress> laneProgress) {
        public RiskScan {
            laneProgress = List.copyOf(laneProgress);
            if (laneProgress.size() > Long.SIZE || accountLaneId < 0 || accountLaneId >= Long.SIZE
                    || !laneProgress.isEmpty() && accountLaneId >= laneProgress.size()
                    || priceSequence < 0 || scanStartPriceSequence < 0 || scanStartPriceSequence > priceSequence
                    || triggerPhase < 0 || triggerPriceCursor < 0 || triggerOrderCursor < 0
                    || triggerUpperId < 0 || triggerMarkPriceTicks < 0 || triggerGeneratedAtEpochMillis < 0
                    || triggerOcoOrderId < 0 || triggerOcoCursor < 0 || lastScheduledRevision < 0) {
                throw new IllegalArgumentException("invalid risk scan");
            }
            instrumentId = instrumentId == null || instrumentId.isBlank() ? "-" : instrumentId;
        }

    }
}
