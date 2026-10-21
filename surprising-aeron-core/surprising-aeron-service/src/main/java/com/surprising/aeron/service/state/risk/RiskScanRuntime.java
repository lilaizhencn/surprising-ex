package com.surprising.aeron.service.state.risk;

import java.util.List;

import com.surprising.aeron.service.state.model.RiskLaneProgress;

/**
 * 由 Owner 持有的币对风险扫描元数据。标量游标用于展示当前调度 Lane 的进度摘要，laneProgress 保存各 Lane 完整进度；风险、条件单及关联撤单分别记录继续位置。
 * @param symbolId 运行时使用的非负币对整数编号；对外币对编号需要通过身份表换回
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
public record RiskScanRuntime(int symbolId, int accountLaneId, long priceSequence, long scanStartPriceSequence,
                              long lastUserId, boolean riskComplete, long riskUserId, int riskPhase,
                              String riskPositionCursor, long riskReservationCursor,
                              long riskUnrealizedPnlUnits, long riskMaintenanceMarginUnits,
                              long riskIsolatedMarginUnits, long riskIsolatedReservationUnits,
                              boolean triggerComplete, int triggerPhase, long triggerPriceCursor,
                              long triggerOrderCursor, long triggerUpperId, long triggerMarkPriceTicks,
                              long triggerGeneratedAtEpochMillis, long triggerOcoOrderId, long triggerOcoCursor, long lastScheduledRevision,
            /** 各 Lane 独立恢复的风险进度；空列表表示扫描尚未分派。 */ List<RiskLaneProgress> laneProgress) {
    /**
     * 创建带调度修订号的运行时扫描，但尚未提供各 Lane 独立进度，列表设为空。
     * @param symbolId 运行时使用的非负币对整数编号；对外币对编号需要通过身份表换回
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
    public RiskScanRuntime(int symbolId, int accountLaneId, long priceSequence, long scanStartPriceSequence,
                              long lastUserId, boolean riskComplete, long riskUserId, int riskPhase,
                              String riskPositionCursor, long riskReservationCursor,
                              long riskUnrealizedPnlUnits, long riskMaintenanceMarginUnits,
                              long riskIsolatedMarginUnits, long riskIsolatedReservationUnits,
                              boolean triggerComplete, int triggerPhase, long triggerPriceCursor,
                              long triggerOrderCursor, long triggerUpperId, long triggerMarkPriceTicks,
                              long triggerGeneratedAtEpochMillis, long triggerOcoOrderId, long triggerOcoCursor, long lastScheduledRevision) {
        this(symbolId, accountLaneId, priceSequence, scanStartPriceSequence, lastUserId, riskComplete,
                riskUserId, riskPhase, riskPositionCursor, riskReservationCursor, riskUnrealizedPnlUnits,
                riskMaintenanceMarginUnits, riskIsolatedMarginUnits, riskIsolatedReservationUnits,
                triggerComplete, triggerPhase, triggerPriceCursor, triggerOrderCursor, triggerUpperId,
                triggerMarkPriceTicks, triggerGeneratedAtEpochMillis, triggerOcoOrderId, triggerOcoCursor,
                lastScheduledRevision, List.of());
    }

    /**
     * 创建带完整标量游标的扫描，最近调度修订号从 0 开始，各 Lane 独立进度列表为空。
     * @param symbolId 运行时使用的非负币对整数编号；对外币对编号需要通过身份表换回
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
    public RiskScanRuntime(int symbolId, int accountLaneId, long priceSequence, long scanStartPriceSequence,
                              long lastUserId, boolean riskComplete, long riskUserId, int riskPhase,
                              String riskPositionCursor, long riskReservationCursor,
                              long riskUnrealizedPnlUnits, long riskMaintenanceMarginUnits,
                              long riskIsolatedMarginUnits, long riskIsolatedReservationUnits,
                              boolean triggerComplete, int triggerPhase, long triggerPriceCursor,
                              long triggerOrderCursor, long triggerUpperId, long triggerMarkPriceTicks,
                              long triggerGeneratedAtEpochMillis, long triggerOcoOrderId, long triggerOcoCursor) {
        this(symbolId, accountLaneId, priceSequence, scanStartPriceSequence, lastUserId, riskComplete, riskUserId,
                riskPhase, riskPositionCursor, riskReservationCursor, riskUnrealizedPnlUnits,
                riskMaintenanceMarginUnits, riskIsolatedMarginUnits, riskIsolatedReservationUnits, triggerComplete,
                triggerPhase, triggerPriceCursor, triggerOrderCursor, triggerUpperId, triggerMarkPriceTicks,
                triggerGeneratedAtEpochMillis, triggerOcoOrderId, triggerOcoCursor, 0);
    }

    /**
     * 复制 Lane 进度列表并限制最多 64 个，校验币对编号、Lane 编号、行情序号及各游标范围；空持仓游标改为短横线，风险完成时不能留有未完成用户。
     */
    public RiskScanRuntime {
        laneProgress = List.copyOf(laneProgress);
        if (laneProgress.size() > Long.SIZE) throw new IllegalArgumentException("too many risk Lanes");
        if (lastScheduledRevision < 0 || symbolId < 0 || accountLaneId < 0 || accountLaneId >= Long.SIZE
                || priceSequence < 0 || scanStartPriceSequence < 0
                || scanStartPriceSequence > priceSequence || lastUserId < 0 || riskUserId < 0
                || riskPhase < 0 || riskPhase > 2 || riskReservationCursor < 0
                || riskMaintenanceMarginUnits < 0 || riskIsolatedMarginUnits < 0
                || riskIsolatedReservationUnits < 0 || triggerPhase < 0 || triggerPriceCursor < 0
                || triggerOrderCursor < 0 || triggerUpperId < 0 || triggerMarkPriceTicks < 0
                || triggerGeneratedAtEpochMillis < 0 || triggerOcoOrderId < 0 || triggerOcoCursor < 0) {
            throw new IllegalArgumentException("invalid runtime risk scan");
        }
        riskPositionCursor = riskPositionCursor == null || riskPositionCursor.isBlank()
                ? "-" : riskPositionCursor;
        if (riskComplete && riskUserId != 0) {
            throw new IllegalArgumentException("complete runtime risk scan cannot retain a user cursor");
        }
    }

    /**
     * 创建从账户 Lane 0 开始的扫描，其余风险和条件单游标由调用方明确传入。
     * @param symbolId 运行时使用的非负币对整数编号；对外币对编号需要通过身份表换回
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
    public RiskScanRuntime(int symbolId, long priceSequence, long scanStartPriceSequence,
                           long lastUserId, boolean riskComplete, long riskUserId, int riskPhase,
                           String riskPositionCursor, long riskReservationCursor,
                           long riskUnrealizedPnlUnits, long riskMaintenanceMarginUnits,
                           long riskIsolatedMarginUnits, long riskIsolatedReservationUnits,
                           boolean triggerComplete, int triggerPhase, long triggerPriceCursor,
                           long triggerOrderCursor, long triggerUpperId, long triggerMarkPriceTicks,
                           long triggerGeneratedAtEpochMillis, long triggerOcoOrderId, long triggerOcoCursor) {
        this(symbolId, 0, priceSequence, scanStartPriceSequence, lastUserId, riskComplete, riskUserId,
                riskPhase, riskPositionCursor, riskReservationCursor, riskUnrealizedPnlUnits,
                riskMaintenanceMarginUnits, riskIsolatedMarginUnits, riskIsolatedReservationUnits,
                triggerComplete, triggerPhase, triggerPriceCursor, triggerOrderCursor, triggerUpperId,
                triggerMarkPriceTicks, triggerGeneratedAtEpochMillis, triggerOcoOrderId, triggerOcoCursor);
    }

    /**
     * 只更新最近调度修订号，保留所有账户风险和条件单进度。
     * @param revision 本次记录的最近调度状态修订号
     */
    public RiskScanRuntime withLastScheduledRevision(long revision) {
        return new RiskScanRuntime(symbolId, accountLaneId, priceSequence, scanStartPriceSequence, lastUserId,
                riskComplete, riskUserId, riskPhase, riskPositionCursor, riskReservationCursor,
                riskUnrealizedPnlUnits, riskMaintenanceMarginUnits, riskIsolatedMarginUnits,
                riskIsolatedReservationUnits, triggerComplete, triggerPhase, triggerPriceCursor, triggerOrderCursor,
                triggerUpperId, triggerMarkPriceTicks, triggerGeneratedAtEpochMillis, triggerOcoOrderId,
                triggerOcoCursor, revision, laneProgress);
    }

    /**
     * 用各 Lane 的新进度列表替换原列表，其他扫描游标不变。
     * @param progress 各账户 Lane 的新风险进度，构造新记录时复制为不可修改列表
     */
    public RiskScanRuntime withLaneProgress(List<RiskLaneProgress> progress) {
        return new RiskScanRuntime(symbolId, accountLaneId, priceSequence, scanStartPriceSequence, lastUserId,
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
    public RiskScanRuntime withRiskProgress(boolean complete, long userId, int phase, String positionCursor,
                                            long reservationCursor, long unrealizedPnlUnits,
                                            long maintenanceMarginUnits, long isolatedMarginUnits,
                                            long isolatedReservationUnits, long completedUserId) {
        return new RiskScanRuntime(symbolId, accountLaneId, priceSequence, scanStartPriceSequence,
                completedUserId, complete,
                userId, phase, positionCursor, reservationCursor, unrealizedPnlUnits, maintenanceMarginUnits,
                isolatedMarginUnits, isolatedReservationUnits, triggerComplete, triggerPhase, triggerPriceCursor,
                triggerOrderCursor, triggerUpperId, triggerMarkPriceTicks, triggerGeneratedAtEpochMillis,
                triggerOcoOrderId, triggerOcoCursor, lastScheduledRevision);
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
    public RiskScanRuntime withTriggerProgress(boolean complete, int phase, long priceCursor, long orderCursor,
                                               long upperId, long markPriceTicks,
                                               long generatedAtEpochMillis) {
        return new RiskScanRuntime(symbolId, accountLaneId, priceSequence, scanStartPriceSequence,
                lastUserId, riskComplete,
                riskUserId, riskPhase, riskPositionCursor, riskReservationCursor, riskUnrealizedPnlUnits,
                riskMaintenanceMarginUnits, riskIsolatedMarginUnits, riskIsolatedReservationUnits,
                complete, phase, priceCursor, orderCursor, upperId, markPriceTicks, generatedAtEpochMillis,
                triggerOcoOrderId, triggerOcoCursor, lastScheduledRevision, laneProgress);
    }

    /**
     * 记录或清除需要分批继续的关联撤单目标及游标，保留其余扫描进度。
     * @param orderId 正在处理关联撤单的触发单编号，0 表示没有待继续的关联撤单
     * @param cursor 关联撤单按条件单编号倒序处理的继续位置
     */
    public RiskScanRuntime withTriggerOcoProgress(long orderId, long cursor) {
        return new RiskScanRuntime(symbolId, accountLaneId, priceSequence, scanStartPriceSequence,
                lastUserId, riskComplete,
                riskUserId, riskPhase, riskPositionCursor, riskReservationCursor, riskUnrealizedPnlUnits,
                riskMaintenanceMarginUnits, riskIsolatedMarginUnits, riskIsolatedReservationUnits,
                triggerComplete, triggerPhase, triggerPriceCursor, triggerOrderCursor, triggerUpperId,
                triggerMarkPriceTicks, triggerGeneratedAtEpochMillis, orderId, cursor, lastScheduledRevision, laneProgress);
    }

    /**
     * 转到指定账户 Lane 并清空用户风险累加进度，保留条件单部分；独立 Lane 进度列表按该构造路径重置为空。
     * @param laneId 接下来需要处理的账户 Lane 编号
     */
    public RiskScanRuntime nextAccountLane(int laneId) {
        return new RiskScanRuntime(symbolId, laneId, priceSequence, scanStartPriceSequence, 0, false,
                0, 0, "-", 0, 0, 0, 0, 0, triggerComplete, triggerPhase, triggerPriceCursor,
                triggerOrderCursor, triggerUpperId, triggerMarkPriceTicks, triggerGeneratedAtEpochMillis,
                triggerOcoOrderId, triggerOcoCursor, lastScheduledRevision);
    }
}
