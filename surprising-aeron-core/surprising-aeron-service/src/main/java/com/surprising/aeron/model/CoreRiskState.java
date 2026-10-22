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
     * 把一份扫描游标放进按币对索引的映射；空游标或空闲标记不加入映射，其余配置使用默认值。
     * @param markPrices 按币对编号保存的行情状态，构造时冻结并排序，供恢复和状态查看使用
     * @param snapshots 按用户、币对及持仓腿组合键保存的风险估值记录
     * @param liquidations 按强平编号保存的强平流程记录
     * @param scan 要放入状态的单份扫描游标，null 或币对为短横线表示没有待加入的扫描
     * @param nextLiquidationId 下一次新建强平计划时使用的编号，初始值为 1
     */
    public CoreRiskState(Map<String, CoreMarkPriceState> markPrices,
                         Map<String, CoreRiskSnapshot> snapshots,
                         Map<Long, CoreLiquidationState> liquidations,
                         RiskScan scan,
                         long nextLiquidationId) {
        this(markPrices, snapshots, liquidations,
                scan == null || "-".equals(scan.instrumentId()) ? Map.of() : Map.of(scan.instrumentId(), scan),
                nextLiquidationId, defaultScanControl());
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
     * 只要任一币对的账户风险或条件单扫描尚未完成，就表示仍有待处理扫描。
     */
    public boolean hasPendingScans() {
        for (RiskScan /* 当前检查的币对扫描，账户风险和条件单都结束才算完成。 */ value : scans.values()) {
            if (!value.complete()) {
                return true;
            }
        }
        return false;
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
     * 某个币对一轮行情驱动的风险及条件单扫描进度，分别保留账户估值、价格索引和关联撤单游标，使有限工作量的一批结束后还能继续。
     * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
     * @param accountLaneId 当前风险进度对应的账户 Lane 编号，范围为 0 到 63
     * @param priceSequence 行情的单调序号，用来识别扫描和估值采用的是哪一版价格
     * @param scanStartPriceSequence 这一轮扫描最初启动时的行情序号，与当前扫描序号分开保存
     * @param lastUserId 本轮最后一个已经处理完的用户编号，后续从它之后继续
     * @param riskComplete 这一轮账户风险扫描是否已经完成
     * @param riskUserId 正在分批估值的用户编号，0 表示没有尚未完成的用户
     * @param riskPhase 用户估值阶段：0 累计持仓风险，1 累计逐仓委托冻结，2 写入全仓风险
     * @param riskPositionCursor 当前估值阶段最后处理的持仓键，短横线表示尚未开始遍历
     * @param riskReservationCursor 当前用户最后处理的冻结委托编号，0 表示尚未开始
     * @param riskUnrealizedPnlUnits 已经累计的跨仓权益变化，期货为未实现盈亏，期权按持仓市值口径计入
     * @param riskMaintenanceMarginUnits 已经累计的全仓维持保证金，单位为结算资产最小单位
     * @param riskIsolatedMarginUnits 已经累计的逐仓持仓保证金，计算全仓可用权益时要扣除
     * @param riskIsolatedReservationUnits 已经累计的逐仓委托冻结金额，不能再次算进全仓共享权益
     * @param triggerComplete 这一轮条件单扫描是否已经完成，与账户风险完成标记分别保存
     * @param triggerPhase 条件单扫描阶段，同时编码价格来源及该来源内部的索引遍历阶段
     * @param triggerPriceCursor 条件单价格索引最后扫描到的位置，用于下一批继续
     * @param triggerOrderCursor 同一价格范围内最后扫描的条件单编号游标
     * @param triggerUpperId 本轮允许扫描的条件单编号上界，限制扫描期间纳入的条件单范围
     * @param triggerMarkPriceTicks 本轮条件单扫描固定采用的标记价格 tick
     * @param triggerGeneratedAtEpochMillis 本轮扫描价格的生成时间，过期判断和触发时间均使用这个毫秒时间戳
     * @param triggerOcoOrderId 正在处理关联撤单的触发单编号，0 表示没有中断待续的关联撤单
     * @param triggerOcoCursor 关联条件单按编号倒序撤销时的继续位置，避免一批内处理过多
     * @param lastScheduledRevision 最近一次调度这份扫描时记录的状态修订号
     * @param laneProgress 各账户 Lane 的独立风险游标，列表下标就是 Lane 编号；空列表表示尚未分派
     */
    public record RiskScan(
            String instrumentId,
            int accountLaneId,
            long priceSequence,
            long scanStartPriceSequence,
            long lastUserId,
            boolean riskComplete,
            long riskUserId,
            int riskPhase,
            String riskPositionCursor,
            long riskReservationCursor,
            long riskUnrealizedPnlUnits,
            long riskMaintenanceMarginUnits,
            long riskIsolatedMarginUnits,
            long riskIsolatedReservationUnits,
            boolean triggerComplete,
            int triggerPhase,
            long triggerPriceCursor,
            long triggerOrderCursor,
            long triggerUpperId,
            long triggerMarkPriceTicks,
            long triggerGeneratedAtEpochMillis,
            long triggerOcoOrderId,
            long triggerOcoCursor, long lastScheduledRevision,
            /** 各 Lane 独立恢复的风险进度；空列表表示扫描尚未分派。 */ List<RiskLaneProgress> laneProgress) {
        /**
         * 创建已有调度修订号、但尚未分派各 Lane 独立游标的扫描记录。
         * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
         * @param accountLaneId 当前风险进度对应的账户 Lane 编号，范围为 0 到 63
         * @param priceSequence 行情的单调序号，用来识别扫描和估值采用的是哪一版价格
         * @param scanStartPriceSequence 这一轮扫描最初启动时的行情序号，与当前扫描序号分开保存
         * @param lastUserId 本轮最后一个已经处理完的用户编号，后续从它之后继续
         * @param riskComplete 这一轮账户风险扫描是否已经完成
         * @param riskUserId 正在分批估值的用户编号，0 表示没有尚未完成的用户
         * @param riskPhase 用户估值阶段：0 累计持仓风险，1 累计逐仓委托冻结，2 写入全仓风险
         * @param riskPositionCursor 当前估值阶段最后处理的持仓键，短横线表示尚未开始遍历
         * @param riskReservationCursor 当前用户最后处理的冻结委托编号，0 表示尚未开始
         * @param riskUnrealizedPnlUnits 已经累计的跨仓权益变化，期货为未实现盈亏，期权按持仓市值口径计入
         * @param riskMaintenanceMarginUnits 已经累计的全仓维持保证金，单位为结算资产最小单位
         * @param riskIsolatedMarginUnits 已经累计的逐仓持仓保证金，计算全仓可用权益时要扣除
         * @param riskIsolatedReservationUnits 已经累计的逐仓委托冻结金额，不能再次算进全仓共享权益
         * @param triggerComplete 这一轮条件单扫描是否已经完成，与账户风险完成标记分别保存
         * @param triggerPhase 条件单扫描阶段，同时编码价格来源及该来源内部的索引遍历阶段
         * @param triggerPriceCursor 条件单价格索引最后扫描到的位置，用于下一批继续
         * @param triggerOrderCursor 同一价格范围内最后扫描的条件单编号游标
         * @param triggerUpperId 本轮允许扫描的条件单编号上界，限制扫描期间纳入的条件单范围
         * @param triggerMarkPriceTicks 本轮条件单扫描固定采用的标记价格 tick
         * @param triggerGeneratedAtEpochMillis 本轮扫描价格的生成时间，过期判断和触发时间均使用这个毫秒时间戳
         * @param triggerOcoOrderId 正在处理关联撤单的触发单编号，0 表示没有中断待续的关联撤单
         * @param triggerOcoCursor 关联条件单按编号倒序撤销时的继续位置，避免一批内处理过多
         * @param lastScheduledRevision 最近一次调度这份扫描时记录的状态修订号
         */
        public RiskScan(
                String instrumentId,
                int accountLaneId,
                long priceSequence,
                long scanStartPriceSequence,
                long lastUserId,
                boolean riskComplete,
                long riskUserId,
                int riskPhase,
                String riskPositionCursor,
                long riskReservationCursor,
                long riskUnrealizedPnlUnits,
                long riskMaintenanceMarginUnits,
                long riskIsolatedMarginUnits,
                long riskIsolatedReservationUnits,
                boolean triggerComplete,
                int triggerPhase,
                long triggerPriceCursor,
                long triggerOrderCursor,
                long triggerUpperId,
                long triggerMarkPriceTicks,
                long triggerGeneratedAtEpochMillis,
                long triggerOcoOrderId,
                long triggerOcoCursor, long lastScheduledRevision) {
            this(instrumentId, accountLaneId, priceSequence, scanStartPriceSequence, lastUserId, riskComplete, riskUserId,
                    riskPhase, riskPositionCursor, riskReservationCursor, riskUnrealizedPnlUnits,
                    riskMaintenanceMarginUnits, riskIsolatedMarginUnits, riskIsolatedReservationUnits,
                    triggerComplete, triggerPhase, triggerPriceCursor, triggerOrderCursor, triggerUpperId,
                    triggerMarkPriceTicks, triggerGeneratedAtEpochMillis, triggerOcoOrderId, triggerOcoCursor,
                    lastScheduledRevision, List.of());
        }

        /**
         * 创建包含完整风险和条件单游标的记录，最近调度修订号从 0 开始。
         * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
         * @param accountLaneId 当前风险进度对应的账户 Lane 编号，范围为 0 到 63
         * @param priceSequence 行情的单调序号，用来识别扫描和估值采用的是哪一版价格
         * @param scanStartPriceSequence 这一轮扫描最初启动时的行情序号，与当前扫描序号分开保存
         * @param lastUserId 本轮最后一个已经处理完的用户编号，后续从它之后继续
         * @param riskComplete 这一轮账户风险扫描是否已经完成
         * @param riskUserId 正在分批估值的用户编号，0 表示没有尚未完成的用户
         * @param riskPhase 用户估值阶段：0 累计持仓风险，1 累计逐仓委托冻结，2 写入全仓风险
         * @param riskPositionCursor 当前估值阶段最后处理的持仓键，短横线表示尚未开始遍历
         * @param riskReservationCursor 当前用户最后处理的冻结委托编号，0 表示尚未开始
         * @param riskUnrealizedPnlUnits 已经累计的跨仓权益变化，期货为未实现盈亏，期权按持仓市值口径计入
         * @param riskMaintenanceMarginUnits 已经累计的全仓维持保证金，单位为结算资产最小单位
         * @param riskIsolatedMarginUnits 已经累计的逐仓持仓保证金，计算全仓可用权益时要扣除
         * @param riskIsolatedReservationUnits 已经累计的逐仓委托冻结金额，不能再次算进全仓共享权益
         * @param triggerComplete 这一轮条件单扫描是否已经完成，与账户风险完成标记分别保存
         * @param triggerPhase 条件单扫描阶段，同时编码价格来源及该来源内部的索引遍历阶段
         * @param triggerPriceCursor 条件单价格索引最后扫描到的位置，用于下一批继续
         * @param triggerOrderCursor 同一价格范围内最后扫描的条件单编号游标
         * @param triggerUpperId 本轮允许扫描的条件单编号上界，限制扫描期间纳入的条件单范围
         * @param triggerMarkPriceTicks 本轮条件单扫描固定采用的标记价格 tick
         * @param triggerGeneratedAtEpochMillis 本轮扫描价格的生成时间，过期判断和触发时间均使用这个毫秒时间戳
         * @param triggerOcoOrderId 正在处理关联撤单的触发单编号，0 表示没有中断待续的关联撤单
         * @param triggerOcoCursor 关联条件单按编号倒序撤销时的继续位置，避免一批内处理过多
         */
        public RiskScan(
            String instrumentId,
            int accountLaneId,
            long priceSequence,
            long scanStartPriceSequence,
            long lastUserId,
            boolean riskComplete,
            long riskUserId,
            int riskPhase,
            String riskPositionCursor,
            long riskReservationCursor,
            long riskUnrealizedPnlUnits,
            long riskMaintenanceMarginUnits,
            long riskIsolatedMarginUnits,
            long riskIsolatedReservationUnits,
            boolean triggerComplete,
            int triggerPhase,
            long triggerPriceCursor,
            long triggerOrderCursor,
            long triggerUpperId,
            long triggerMarkPriceTicks,
            long triggerGeneratedAtEpochMillis,
            long triggerOcoOrderId,
            long triggerOcoCursor) {
            this(instrumentId, accountLaneId, priceSequence, scanStartPriceSequence, lastUserId, riskComplete, riskUserId,
                    riskPhase, riskPositionCursor, riskReservationCursor, riskUnrealizedPnlUnits,
                    riskMaintenanceMarginUnits, riskIsolatedMarginUnits, riskIsolatedReservationUnits,
                    triggerComplete, triggerPhase, triggerPriceCursor, triggerOrderCursor, triggerUpperId,
                    triggerMarkPriceTicks, triggerGeneratedAtEpochMillis, triggerOcoOrderId, triggerOcoCursor, 0);
        }

        /**
         * 复制 Lane 进度列表并校验数量、阶段、游标及行情序号；空编号统一成短横线，风险扫描完成时不得留有未完成用户。
         */
        public RiskScan {
            laneProgress = List.copyOf(laneProgress);
            if (laneProgress.size() > Long.SIZE) throw new IllegalArgumentException("too many risk Lanes");
            if (lastScheduledRevision < 0 || accountLaneId < 0 || accountLaneId >= Long.SIZE || priceSequence < 0 || scanStartPriceSequence < 0
                    || scanStartPriceSequence > priceSequence || lastUserId < 0 || riskUserId < 0
                    || riskPhase < 0 || riskPhase > 2 || riskReservationCursor < 0
                    || riskMaintenanceMarginUnits < 0 || riskIsolatedMarginUnits < 0
                    || riskIsolatedReservationUnits < 0 || triggerPhase < 0
                    || triggerPriceCursor < 0 || triggerOrderCursor < 0 || triggerUpperId < 0
                    || triggerMarkPriceTicks < 0 || triggerGeneratedAtEpochMillis < 0
                    || triggerOcoOrderId < 0 || triggerOcoCursor < 0) {
                throw new IllegalArgumentException("invalid risk scan");
            }
            instrumentId = instrumentId == null || instrumentId.isBlank() ? "-" : instrumentId;
            riskPositionCursor = riskPositionCursor == null || riskPositionCursor.isBlank()
                    ? "-" : riskPositionCursor;
            if (riskComplete && riskUserId != 0) {
                throw new IllegalArgumentException("complete risk scan cannot retain a user cursor");
            }
        }

        /**
         * 创建从账户 Lane 0 开始的扫描，其他风险和条件单游标由调用方传入。
         * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
         * @param priceSequence 行情的单调序号，用来识别扫描和估值采用的是哪一版价格
         * @param scanStartPriceSequence 这一轮扫描最初启动时的行情序号，与当前扫描序号分开保存
         * @param lastUserId 本轮最后一个已经处理完的用户编号，后续从它之后继续
         * @param riskComplete 这一轮账户风险扫描是否已经完成
         * @param riskUserId 正在分批估值的用户编号，0 表示没有尚未完成的用户
         * @param riskPhase 用户估值阶段：0 累计持仓风险，1 累计逐仓委托冻结，2 写入全仓风险
         * @param riskPositionCursor 当前估值阶段最后处理的持仓键，短横线表示尚未开始遍历
         * @param riskReservationCursor 当前用户最后处理的冻结委托编号，0 表示尚未开始
         * @param riskUnrealizedPnlUnits 已经累计的跨仓权益变化，期货为未实现盈亏，期权按持仓市值口径计入
         * @param riskMaintenanceMarginUnits 已经累计的全仓维持保证金，单位为结算资产最小单位
         * @param riskIsolatedMarginUnits 已经累计的逐仓持仓保证金，计算全仓可用权益时要扣除
         * @param riskIsolatedReservationUnits 已经累计的逐仓委托冻结金额，不能再次算进全仓共享权益
         * @param triggerComplete 这一轮条件单扫描是否已经完成，与账户风险完成标记分别保存
         * @param triggerPhase 条件单扫描阶段，同时编码价格来源及该来源内部的索引遍历阶段
         * @param triggerPriceCursor 条件单价格索引最后扫描到的位置，用于下一批继续
         * @param triggerOrderCursor 同一价格范围内最后扫描的条件单编号游标
         * @param triggerUpperId 本轮允许扫描的条件单编号上界，限制扫描期间纳入的条件单范围
         * @param triggerMarkPriceTicks 本轮条件单扫描固定采用的标记价格 tick
         * @param triggerGeneratedAtEpochMillis 本轮扫描价格的生成时间，过期判断和触发时间均使用这个毫秒时间戳
         * @param triggerOcoOrderId 正在处理关联撤单的触发单编号，0 表示没有中断待续的关联撤单
         * @param triggerOcoCursor 关联条件单按编号倒序撤销时的继续位置，避免一批内处理过多
         */
        public RiskScan(String instrumentId, long priceSequence, long scanStartPriceSequence, long lastUserId,
                        boolean riskComplete, long riskUserId, int riskPhase, String riskPositionCursor,
                        long riskReservationCursor, long riskUnrealizedPnlUnits,
                        long riskMaintenanceMarginUnits, long riskIsolatedMarginUnits,
                        long riskIsolatedReservationUnits, boolean triggerComplete, int triggerPhase,
                        long triggerPriceCursor, long triggerOrderCursor, long triggerUpperId,
                        long triggerMarkPriceTicks, long triggerGeneratedAtEpochMillis,
                        long triggerOcoOrderId, long triggerOcoCursor) {
            this(instrumentId, 0, priceSequence, scanStartPriceSequence, lastUserId, riskComplete, riskUserId,
                    riskPhase, riskPositionCursor, riskReservationCursor, riskUnrealizedPnlUnits,
                    riskMaintenanceMarginUnits, riskIsolatedMarginUnits, riskIsolatedReservationUnits,
                    triggerComplete, triggerPhase, triggerPriceCursor, triggerOrderCursor, triggerUpperId,
                    triggerMarkPriceTicks, triggerGeneratedAtEpochMillis, triggerOcoOrderId, triggerOcoCursor);
        }

        /**
         * 创建只有用户遍历进度的扫描，条件单部分先记为完成，详细风险游标和累计值从空值开始。
         * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
         * @param priceSequence 行情的单调序号，用来识别扫描和估值采用的是哪一版价格
         * @param scanStartPriceSequence 这一轮扫描最初启动时的行情序号，与当前扫描序号分开保存
         * @param lastUserId 本轮最后一个已经处理完的用户编号，后续从它之后继续
         * @param complete 当前更新的扫描部分是否完成；简化构造器中指账户风险部分
         */
        public RiskScan(String instrumentId, long priceSequence, long scanStartPriceSequence,
                        long lastUserId, boolean complete) {
            this(instrumentId, 0, priceSequence, scanStartPriceSequence, lastUserId, complete,
                    0, 0, "-", 0, 0, 0, 0, 0,
                    true, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        /**
         * 用同一个行情序号作为当前序号和本轮起始序号，创建简化的用户扫描游标。
         * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
         * @param priceSequence 行情的单调序号，用来识别扫描和估值采用的是哪一版价格
         * @param lastUserId 本轮最后一个已经处理完的用户编号，后续从它之后继续
         * @param complete 当前更新的扫描部分是否完成；简化构造器中指账户风险部分
         */
        public RiskScan(String instrumentId, long priceSequence, long lastUserId, boolean complete) {
            this(instrumentId, priceSequence, priceSequence, lastUserId, complete);
        }

        /**
         * 只更新最近调度修订号，保留所有账户风险和条件单进度。
         * @param revision 本次记录的最近调度状态修订号
         */
        public RiskScan withLastScheduledRevision(long revision) {
            return new RiskScan(instrumentId, accountLaneId, priceSequence, scanStartPriceSequence, lastUserId,
                    riskComplete, riskUserId, riskPhase, riskPositionCursor, riskReservationCursor,
                    riskUnrealizedPnlUnits, riskMaintenanceMarginUnits, riskIsolatedMarginUnits,
                    riskIsolatedReservationUnits, triggerComplete, triggerPhase, triggerPriceCursor,
                    triggerOrderCursor, triggerUpperId, triggerMarkPriceTicks, triggerGeneratedAtEpochMillis,
                    triggerOcoOrderId, triggerOcoCursor, revision, laneProgress);
        }

        /**
         * 用各 Lane 的新进度列表替换原列表，其他扫描游标不变。
         * @param progress 各账户 Lane 的新风险进度，构造新记录时复制为不可修改列表
         */
        public RiskScan withLaneProgress(List<RiskLaneProgress> progress) {
            return new RiskScan(instrumentId, accountLaneId, priceSequence, scanStartPriceSequence, lastUserId,
                    riskComplete, riskUserId, riskPhase, riskPositionCursor, riskReservationCursor,
                    riskUnrealizedPnlUnits, riskMaintenanceMarginUnits, riskIsolatedMarginUnits,
                    riskIsolatedReservationUnits, triggerComplete, triggerPhase, triggerPriceCursor,
                    triggerOrderCursor, triggerUpperId, triggerMarkPriceTicks, triggerGeneratedAtEpochMillis,
                    triggerOcoOrderId, triggerOcoCursor, lastScheduledRevision, progress);
        }

        /**
         * 账户风险扫描与条件单扫描都完成时，整轮扫描才算完成。
         */
        public boolean complete() {
            return riskComplete && triggerComplete;
        }

        /**
         * 替换账户风险部分的用户、阶段、游标和累计金额，保留条件单进度；此构造路径将独立 Lane 进度列表重置为空。
         * @param complete 当前更新的扫描部分是否完成；简化构造器中指账户风险部分
         * @param userId 当前尚未完成估值的用户编号，0 表示没有进行中的用户
         * @param phase 当前更新部分的扫描阶段，风险为 0 到 2，条件单则同时编码价格来源和索引阶段
         * @param positionCursor 最后处理的持仓键，短横线表示尚未开始遍历持仓
         * @param reservationCursor 最后处理的冻结委托编号，0 表示尚未开始遍历
         * @param unrealizedPnlUnits 风险估值累计的权益变化，期货按未实现盈亏、期权按市值口径计入
         * @param maintenanceMarginUnits 维持持仓所要求的保证金金额，单位为结算资产最小单位
         * @param isolatedMarginUnits 已经累计的逐仓持仓保证金，需要从全仓共享资金中扣除
         * @param isolatedReservationUnits 已经累计的逐仓委托冻结金额，需要从全仓共享资金中扣除
         * @param completedUserId 最后一个完整处理完的用户编号，作为后续用户遍历起点
         */
        public RiskScan withRiskProgress(boolean complete, long userId, int phase, String positionCursor,
                                         long reservationCursor, long unrealizedPnlUnits,
                                         long maintenanceMarginUnits, long isolatedMarginUnits,
                                         long isolatedReservationUnits, long completedUserId) {
            return new RiskScan(instrumentId, accountLaneId, priceSequence, scanStartPriceSequence, completedUserId, complete,
                    userId, phase, positionCursor, reservationCursor, unrealizedPnlUnits,
                    maintenanceMarginUnits, isolatedMarginUnits, isolatedReservationUnits,
                    triggerComplete, triggerPhase, triggerPriceCursor, triggerOrderCursor, triggerUpperId,
                    triggerMarkPriceTicks, triggerGeneratedAtEpochMillis, triggerOcoOrderId, triggerOcoCursor, lastScheduledRevision);
        }

        /**
         * 替换条件单的阶段及价格、编号游标，保留账户风险和各 Lane 的独立进度。
         * @param complete 当前更新的扫描部分是否完成；简化构造器中指账户风险部分
         * @param phase 当前更新部分的扫描阶段，风险为 0 到 2，条件单则同时编码价格来源和索引阶段
         * @param priceCursor 下一批条件单扫描继续使用的价格索引游标
         * @param orderCursor 下一批条件单扫描继续使用的条件单编号游标
         * @param upperId 这一轮条件单编号的固定上界
         * @param markPriceTicks 风险估值使用的标记价格，单位为价格 tick
         * @param generatedAtEpochMillis 行情来源生成这份价格的 Unix 毫秒时间戳
         */
        public RiskScan withTriggerProgress(boolean complete, int phase, long priceCursor, long orderCursor,
                                            long upperId, long markPriceTicks, long generatedAtEpochMillis) {
            return new RiskScan(instrumentId, accountLaneId, priceSequence, scanStartPriceSequence, lastUserId, riskComplete,
                    riskUserId, riskPhase, riskPositionCursor, riskReservationCursor,
                    riskUnrealizedPnlUnits, riskMaintenanceMarginUnits, riskIsolatedMarginUnits,
                    riskIsolatedReservationUnits, complete, phase, priceCursor, orderCursor, upperId,
                    markPriceTicks, generatedAtEpochMillis, triggerOcoOrderId, triggerOcoCursor, lastScheduledRevision, laneProgress);
        }

        /**
         * 记录或清除需要分批继续的关联撤单目标及游标，保留其余扫描进度。
         * @param orderId 正在处理关联撤单的触发单编号，0 表示没有待继续的关联撤单
         * @param cursor 关联撤单按条件单编号倒序处理的继续位置
         */
        public RiskScan withTriggerOcoProgress(long orderId, long cursor) {
            return new RiskScan(instrumentId, accountLaneId, priceSequence, scanStartPriceSequence, lastUserId, riskComplete,
                    riskUserId, riskPhase, riskPositionCursor, riskReservationCursor,
                    riskUnrealizedPnlUnits, riskMaintenanceMarginUnits, riskIsolatedMarginUnits,
                    riskIsolatedReservationUnits, triggerComplete, triggerPhase, triggerPriceCursor,
                    triggerOrderCursor, triggerUpperId, triggerMarkPriceTicks, triggerGeneratedAtEpochMillis,
                    orderId, cursor, lastScheduledRevision, laneProgress);
        }

        /**
         * 转到指定账户 Lane 并清空用户风险累加进度，保留条件单部分；独立 Lane 进度列表按该构造路径重置为空。
         * @param laneId 接下来需要处理的账户 Lane 编号
         */
        public RiskScan nextAccountLane(int laneId) {
            return new RiskScan(instrumentId, laneId, priceSequence, scanStartPriceSequence, 0, false,
                    0, 0, "-", 0, 0, 0, 0, 0,
                    triggerComplete, triggerPhase, triggerPriceCursor, triggerOrderCursor, triggerUpperId,
                    triggerMarkPriceTicks, triggerGeneratedAtEpochMillis, triggerOcoOrderId, triggerOcoCursor, lastScheduledRevision);
        }

        /**
         * 创建没有币对、没有行情序号且两个扫描部分都完成的空闲记录。
         */
        static RiskScan idle() {
            return new RiskScan("-", 0, 0, 0, true);
        }
    }
}
