package com.surprising.aeron.model;

import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.service.snapshot.state.StateMapSupport;
import java.util.List;
import java.util.Map;

/**
 * 可恢复的整份风险状态，汇总行情、估值、强平记录及每个币对的扫描游标。构造时把映射冻结并排序，不能把这些映射当作账户 Lane 的可变热状态直接修改。
 * @param markPrices 按币对编号保存的行情状态，构造时冻结并排序，供恢复和状态查看使用
 * @param snapshots 按用户、币对及持仓腿组合键保存的风险估值记录
 * @param liquidations 按强平编号保存的强平流程记录
 * @param scans 按币对编号保存的风险及条件单扫描进度，可从记录的游标继续
 * @param nextLiquidationId 下一次新建强平计划时使用的编号，初始值为 1
 * @param scanControl 控制风险扫描是否启用、调度间隔和每批工作量的配置
 * @param marketRevision 市场状态的修订号，用来识别跨币对估值累加过程中行情是否已经改变
 */
public record CoreRiskState(
        Map<String, CoreMarkPriceState> markPrices,
        Map<String, CoreRiskSnapshot> snapshots,
        Map<Long, CoreLiquidationState> liquidations,
        Map<String, RiskScan> scans,
        long nextLiquidationId,
        CoreRiskScanControlView scanControl,
        /** 最近一次标记价格变更的状态序号，用于使跨币对累计估值失效。 */ long marketRevision) {

    /**
     * 创建包含扫描控制配置的风险状态，市场修订号从 0 开始。
     * @param markPrices 按币对编号保存的行情状态，构造时冻结并排序，供恢复和状态查看使用
     * @param snapshots 按用户、币对及持仓腿组合键保存的风险估值记录
     * @param liquidations 按强平编号保存的强平流程记录
     * @param scans 按币对编号保存的风险及条件单扫描进度，可从记录的游标继续
     * @param nextLiquidationId 下一次新建强平计划时使用的编号，初始值为 1
     * @param scanControl 控制风险扫描是否启用、调度间隔和每批工作量的配置
     */
    public CoreRiskState(Map<String, CoreMarkPriceState> markPrices, Map<String, CoreRiskSnapshot> snapshots,
                         Map<Long, CoreLiquidationState> liquidations, Map<String, RiskScan> scans,
                         long nextLiquidationId, CoreRiskScanControlView scanControl) {
        this(markPrices, snapshots, liquidations, scans, nextLiquidationId, scanControl, 0);
    }

    /**
     * 要求各类状态映射和扫描配置完整，编号与修订号有效，再冻结排序所有映射，防止外部后续修改影响这份记录。
     */
    public CoreRiskState {
        if (markPrices == null || snapshots == null || liquidations == null || scans == null
                || marketRevision < 0 || nextLiquidationId <= 0 || scanControl == null) {
            throw new IllegalArgumentException("invalid risk state");
        }
        markPrices = immutableSorted(markPrices);
        snapshots = immutableSorted(snapshots);
        liquidations = immutableSorted(liquidations);
        scans = immutableSorted(scans);
    }

    /**
     * 创建没有行情、估值、强平和扫描任务的初始风险状态，强平编号从 1 开始。
     */
    public static CoreRiskState empty() {
        return new CoreRiskState(Map.of(), Map.of(), Map.of(), Map.of(), 1, defaultScanControl());
    }

    /**
     * 创建使用默认扫描控制配置的风险状态，市场修订号从 0 开始。
     * @param markPrices 按币对编号保存的行情状态，构造时冻结并排序，供恢复和状态查看使用
     * @param snapshots 按用户、币对及持仓腿组合键保存的风险估值记录
     * @param liquidations 按强平编号保存的强平流程记录
     * @param scans 按币对编号保存的风险及条件单扫描进度，可从记录的游标继续
     * @param nextLiquidationId 下一次新建强平计划时使用的编号，初始值为 1
     */
    public CoreRiskState(Map<String, CoreMarkPriceState> markPrices,
                         Map<String, CoreRiskSnapshot> snapshots,
                         Map<Long, CoreLiquidationState> liquidations,
                         Map<String, RiskScan> scans,
                         long nextLiquidationId) {
        this(markPrices, snapshots, liquidations, scans, nextLiquidationId, defaultScanControl());
    }

    /**
     * 创建默认启用的扫描控制配置，调度间隔为 25 毫秒，每批工作量为 64。
     */
    public static CoreRiskScanControlView defaultScanControl() {
        return new CoreRiskScanControlView(1, "Aeron risk scan control", true, 25L, 64,
                "system", "default", 0);
    }

    /**
     * 优先返回按映射顺序找到的未完成扫描；都完成时返回第一份，完全没有扫描时返回空闲游标。
     */
    public RiskScan scan() {
        // 映射中的第一份扫描，所有扫描都完成时仍可用它作为代表。
        RiskScan first = null;
        for (RiskScan /* 当前检查的币对扫描，账户风险和条件单都结束才算完成。 */ value : scans.values()) {
            if (!value.complete()) {
                return value;
            }
            if (first == null) {
                first = value;
            }
        }
        return first != null ? first : RiskScan.idle();
    }

    /**
     * 按统一状态映射规则冻结并排序，具体的复制和排序由 StateMapSupport 承担。
     * @param <K> 状态映射的键类型，排序和冻结规则沿用 StateMapSupport
     * @param <V> 状态映射保存的业务记录类型
     * @param values 需要冻结并按状态规则排序的映射
     */
    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> immutableSorted(Map<K, V> values) {
        return StateMapSupport.freezeSorted(values);
    }

    /**
     * 币对扫描的可恢复元数据。Lane 列表按编号保存各自唯一的风险进度；空列表表示尚未分派。
     * accountLaneId 是下次分配预算的轮转起点，风险和触发完成标记属于各自流程。
     */
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

        public boolean complete() {
            return riskComplete && triggerComplete;
        }

        static RiskScan idle() {
            return new RiskScan("-", 0, 0, 0, true, true, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
        }
    }
}
