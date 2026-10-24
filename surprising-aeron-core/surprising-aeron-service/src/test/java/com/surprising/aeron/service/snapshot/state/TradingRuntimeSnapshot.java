package com.surprising.aeron.service.snapshot.state;

import com.surprising.aeron.model.CoreAlgoOrderState;
import com.surprising.aeron.model.CoreCancelAllAfterKey;
import com.surprising.aeron.model.CoreCancelAllAfterState;
import com.surprising.aeron.model.CoreLeverageKey;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CoreRiskStatus;
import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.model.RiskLaneProgress;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.product.api.ProductLine;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 测试使用的运行时状态投影，不是生产二进制快照格式。保存本文件列出的账户、订单、风险和财库字段，构造时复制各层顶级 Map 为自然排序只读表；业务值及其中 List 不统一深拷贝，部分投影也没有覆盖生产模型的全部字段，因此不能替代完整持久化验证。
 * @param revision 此测试投影保存的全局非负修订号
 * @param users 按用户编号排序并只读复制的测试用户表
 * @param balances 按用户和内部资产编号保存的余额表
 * @param orders 按订单编号保存的测试订单投影
 * @param reservations 按订单编号保存的资金预留投影
 * @param clientOrderIndex 按用户和内部客户编号保存的订单别名表
 * @param positions 按用户和内部持仓键保存的持仓投影
 * @param liquidations 按强平编号保存的测试强平记录
 * @param markPrices 按内部币对编号保存的参考价格投影
 * @param riskSnapshots 按用户和内部持仓键保存的风险估值投影
 * @param riskScans 按内部币对编号保存的扫描进度投影
 * @param nextLiquidationId 后续分配的下一个强平编号
 * @param marketRevision 最近行情输入修订号
 * @param instruments 注册币对配置的只读 Map 副本，业务值不深拷贝
 * @param leverages 按用户币对模式保存的杠杆设置
 * @param algoOrders 按算法单编号保存的算法业务值
 * @param cancelAllAfterTimers 按用户和币对范围保存的倒计时撤单业务值
 * @param triggerOrders 按触发单编号保存的触发业务值
 * @param treasury 按内部资产编号保存的七类财库金额投影
 * @param fundingSettlements 内部币对编号到已完成资金费编号的表
 * @param fundingProgress 内部币对编号到未完成资金费进度的表
 * @param lifecycleSettlements 内部币对编号到已完成生命周期结算编号的表
 * @param lifecycleProgress 内部币对编号到未完成生命周期结算进度的表
 */
public record TradingRuntimeSnapshot(
        long revision,
        Map<Long, UserSnapshot> users,
        Map<BalanceKey, BalanceSnapshot> balances,
        Map<Long, OrderSnapshot> orders,
        Map<Long, ReservationSnapshot> reservations,
        Map<ClientOrderKey, Long> clientOrderIndex,
        Map<PositionKey, PositionSnapshot> positions,
        Map<Long, LiquidationSnapshot> liquidations,
        Map<Integer, MarkPriceSnapshot> markPrices,
        Map<PositionKey, RiskSnapshot> riskSnapshots,
        Map<Integer, RiskScanSnapshot> riskScans,
        long nextLiquidationId,
        /** 最近行情输入序号，与分片估值游标对应。 */ long marketRevision,
        Map<String, CoreInstrument> instruments,
        Map<CoreLeverageKey, Long> leverages,
        Map<Long, CoreAlgoOrderState> algoOrders,
        Map<CoreCancelAllAfterKey, CoreCancelAllAfterState> cancelAllAfterTimers,
        Map<Long, CoreTriggerOrderState> triggerOrders,
        Map<Integer, TreasurySnapshot> treasury,
        Map<Integer, Long> fundingSettlements,
        Map<Integer, FundingProgressSnapshot> fundingProgress,
        Map<Integer, Long> lifecycleSettlements,
        Map<Integer, LifecycleProgressSnapshot> lifecycleProgress) {

    /**
     * 检查所有顶级 Map 非空、全局与行情修订非负、下个强平编号为正，再逐个复制 Map 为有序只读表；不在此检查所有键和值交叉归属，也不统一校验每个嵌套投影的全部业务不变量。
     */
    public TradingRuntimeSnapshot {
        if (marketRevision < 0 || revision < 0 || users == null || balances == null || orders == null
                || reservations == null || clientOrderIndex == null || positions == null || liquidations == null
                || markPrices == null || riskSnapshots == null || riskScans == null || nextLiquidationId <= 0
                || instruments == null || leverages == null || algoOrders == null
                || cancelAllAfterTimers == null || triggerOrders == null
                || treasury == null
                || fundingSettlements == null || fundingProgress == null
                || lifecycleSettlements == null || lifecycleProgress == null) {
            throw new IllegalArgumentException("invalid runtime snapshot");
        }
        users = immutableSorted(users);
        balances = immutableSorted(balances);
        orders = immutableSorted(orders);
        reservations = immutableSorted(reservations);
        clientOrderIndex = immutableSorted(clientOrderIndex);
        positions = immutableSorted(positions);
        liquidations = immutableSorted(liquidations);
        markPrices = immutableSorted(markPrices);
        riskSnapshots = immutableSorted(riskSnapshots);
        riskScans = immutableSorted(riskScans);
        instruments = immutableSorted(instruments);
        leverages = immutableSorted(leverages);
        algoOrders = immutableSorted(algoOrders);
        cancelAllAfterTimers = immutableSorted(cancelAllAfterTimers);
        triggerOrders = immutableSorted(triggerOrders);
        treasury = immutableSorted(treasury);
        fundingSettlements = immutableSorted(fundingSettlements);
        fundingProgress = immutableSorted(fundingProgress);
        lifecycleSettlements = immutableSorted(lifecycleSettlements);
        lifecycleProgress = immutableSorted(lifecycleProgress);
    }

    /**
     * 把全部余额条目的可用整数直接求和，仅作测试统计；不同资产没有换算，普通 long 求和不检查溢出，不应当作财务估值总额。
     */
    public long totalAvailableUnits() {
        return balances.values().stream().mapToLong(BalanceSnapshot::availableUnits).sum();
    }

    /**
     * 把全部余额条目的冻结整数直接求和，仅作测试统计；不同资产没有换算，也没有精确溢出检查。
     */
    public long totalLockedUnits() {
        return balances.values().stream().mapToLong(BalanceSnapshot::lockedUnits).sum();
    }

    /**
     * 以自然键序复制容器，再套不可修改的 Map 外壳；键和值引用仍复用输入，不是深拷贝。
     * @param <K> 采用自然顺序比较的 Map 键类型
     * @param <V> Map 业务值类型，复制容器时仍保留原值引用
     * @param source 要复制为有序只读 Map 的测试输入
     */
    private static <K extends Comparable<? super K>, V> Map<K, V> immutableSorted(Map<K, V> source) {
        return Collections.unmodifiableMap(new TreeMap<>(source));
    }

    /**
     * 测试保存的产品线、用户身份、修订和持仓模式。
     * @param productLine 独立产品线
     * @param userId 所属用户编号
     * @param revision 该测试用户的非负修订号
     * @param positionMode 单向或双向持仓模式
     */
    public record UserSnapshot(ProductLine productLine, long userId, long revision, CorePositionMode positionMode) {
        /**
         * 便捷测试构造器默认 U 本位永续、修订零和单向持仓模式，随后走完整用户校验。
         * @param userId 所属用户编号
         */
        public UserSnapshot(long userId) {
            this(ProductLine.LINEAR_PERPETUAL, userId, 0, CorePositionMode.ONE_WAY);
        }
        /**
         * 要求产品线与持仓模式非空、用户编号为正且修订非负；不从其他记录推断或校验账户内容。
         */
        public UserSnapshot {
            if (productLine == null || userId <= 0 || revision < 0 || positionMode == null) {
                throw new IllegalArgumentException("invalid snapshot user");
            }
        }
    }

    /**
     * 余额测试表的用户与内部资产复合键，按两级编号排序。
     * @param userId 所属用户编号
     * @param assetId 内部资产编号，零有效
     */
    public record BalanceKey(long userId, int assetId) implements Comparable<BalanceKey> {
        /**
         * 检查正数用户及非负内部资产编号，零资产编号有效。
         */
        public BalanceKey {
            if (userId <= 0 || assetId < 0) throw new IllegalArgumentException("invalid snapshot balance key");
        }

        /**
         * 先按用户编号排序，同用户再按内部资产编号排序。
         * @param other 排序比较的另一个同类型测试键
         */
        @Override
        public int compareTo(BalanceKey other) {
            // 两个键的用户编号比较结果，非零时无需继续比较内部身份。
            int userComparison = Long.compare(userId, other.userId);
            return userComparison != 0 ? userComparison : Integer.compare(assetId, other.assetId);
        }
    }

    /**
     * 测试保存的可用和冻结余额，不含资产名称，资产由 Map 键确定。
     * @param availableUnits 非负可用余额
     * @param lockedUnits 非负冻结余额
     */
    public record BalanceSnapshot(long availableUnits, long lockedUnits) {
        /**
         * 要求可用和冻结余额都不小于零。
         */
        public BalanceSnapshot {
            if (availableUnits < 0 || lockedUnits < 0) {
                throw new IllegalArgumentException("invalid snapshot balance");
            }
        }
    }

    /**
     * 客户别名测试表的用户与内部客户编号复合键。
     * @param userId 所属用户编号
     * @param clientKey 用户范围内的客户订单号内部身份，本测试键仅额外校验用户为正
     */
    public record ClientOrderKey(long userId, long clientKey) implements Comparable<ClientOrderKey> {
        /**
         * 只要求用户编号为正，不额外限制内部客户编号的正负或零值。
         */
        public ClientOrderKey {
            if (userId <= 0) throw new IllegalArgumentException("invalid snapshot client user");
        }

        /**
         * 先按用户编号排序，再按内部客户编号排序。
         * @param other 排序比较的另一个同类型测试键
         */
        @Override
        public int compareTo(ClientOrderKey other) {
            // 两个键的用户编号比较结果，非零时无需继续比较内部身份。
            int userComparison = Long.compare(userId, other.userId);
            return userComparison != 0 ? userComparison : Long.compare(clientKey, other.clientKey);
        }
    }

    /**
     * 普通订单的测试投影，订单编号放在外层 Map 键中；只包含本记录列出的字段，没有生产订单全部撮合保护价和成交价值字段。
     * @param productLine 独立产品线
     * @param userId 所属用户编号
     * @param symbolId 内部币对编号，零有效
     * @param side 买入或卖出方向
     * @param priceTicks 原请求订单价格
     * @param quantitySteps 订单原请求数量
     * @param executedQuantitySteps 订单累计已成交数量
     * @param remainingQuantitySteps 订单剩余未成交数量
     * @param reduceOnly 是否只允许减少已有持仓
     * @param marginMode 全仓或逐仓保证金模式
     * @param positionSide 净持仓、多仓或空仓方向
     * @param orderType 限价或市价订单类型
     * @param timeInForce GTC、IOC、FOK 或 GTX 的订单有效方式
     * @param postOnly 是否只允许成为挂单 Maker
     * @param clientOrderId 客户指定的普通订单号
     * @param commandId 原业务命令 UUID
     * @param makerFeeRatePpm Maker 手续费率，百万分比
     * @param takerFeeRatePpm Taker 手续费率，百万分比
     * @param cumulativeFeeUnits 订单累计实际手续费金额
     * @param createdAtEpochMillis 记录创建时刻，毫秒
     * @param updatedAtEpochMillis 记录最近更新时刻，毫秒
     * @param clusterPosition 订单保存的 Cluster 日志位置
     * @param status 该订单的普通订单状态
     * @param revision 该测试订单的正数修订号
     */
    public record OrderSnapshot(ProductLine productLine, long userId, int symbolId,
                                com.surprising.aeron.protocol.CoreOrderSide side, long priceTicks,
                                long quantitySteps, long executedQuantitySteps, long remainingQuantitySteps,
                                boolean reduceOnly, com.surprising.aeron.protocol.CoreMarginMode marginMode,
                                com.surprising.aeron.protocol.CorePositionSide positionSide,
                                com.surprising.aeron.protocol.CoreOrderType orderType,
                                com.surprising.aeron.protocol.CoreTimeInForce timeInForce,
                                boolean postOnly, String clientOrderId, UUID commandId,
                                long makerFeeRatePpm, long takerFeeRatePpm,
                                long cumulativeFeeUnits,
                                long createdAtEpochMillis, long updatedAtEpochMillis, long clusterPosition,
                                CoreOrderStatus status, long revision) {
        /**
         * 检查身份和枚举非空、用户为正、币对及价格非负、数量为正，且累计成交与剩余精确相加等于原数量，修订为正。此投影未另校验全部费用、时间和日志位置，精确数量运算溢出仍会抛异常。
         */
        public OrderSnapshot {
            if (productLine == null || userId <= 0 || symbolId < 0 || side == null || priceTicks < 0
                    || marginMode == null || positionSide == null || orderType == null || timeInForce == null
                    || quantitySteps <= 0 || executedQuantitySteps < 0 || remainingQuantitySteps < 0
                    || Math.addExact(executedQuantitySteps, remainingQuantitySteps) != quantitySteps
                    || clientOrderId == null || commandId == null || status == null || revision <= 0) {
                throw new IllegalArgumentException("invalid snapshot order");
            }
        }
    }

    /**
     * 测试预留投影，区分初始总额、已释放、已消耗和计算出来的剩余额度。
     * @param userId 所属用户编号
     * @param symbolId 内部币对编号，零有效
     * @param kind 现货资产或衍生品保证金预留用途
     * @param assetId 内部资产编号，零有效
     * @param totalReservedUnits 预留原始总额，必须为正
     * @param releasedUnits 预留累计已释放金额
     * @param consumedUnits 预留累计已消耗金额
     * @param orderQuantitySteps 本笔预留对应的原订单数量
     */
    public record ReservationSnapshot(long userId, int symbolId,
                                      com.surprising.aeron.protocol.ReservationKind kind, int assetId,
                                      long totalReservedUnits, long releasedUnits, long consumedUnits,
                                      long orderQuantitySteps) {
        /**
         * 用初始总额减去累计释放与消耗，精确返回当前尚未使用的预留余额。
         */
        public long reservedUnits() {
            return Math.subtractExact(totalReservedUnits, Math.addExact(releasedUnits, consumedUnits));
        }
        /**
         * 校验用户、币对、资产和预留用途，以及正总额、非负释放/消耗、合计不超过总额和正订单数量；加法溢出按精确算术抛出。
         */
        public ReservationSnapshot {
            if (userId <= 0 || symbolId < 0 || kind == null || assetId < 0
                    || totalReservedUnits <= 0 || releasedUnits < 0 || consumedUnits < 0
                    || Math.addExact(releasedUnits, consumedUnits) > totalReservedUnits || orderQuantitySteps <= 0) {
                throw new IllegalArgumentException("invalid snapshot reservation");
            }
        }
    }

    /**
     * 持仓及风险测试表的用户与内部持仓身份复合键。
     * @param userId 所属用户编号
     * @param positionKey 用户范围内的正数内部持仓身份键
     */
    public record PositionKey(long userId, long positionKey) implements Comparable<PositionKey> {
        /**
         * 要求用户编号和内部持仓键都为正。
         */
        public PositionKey {
            if (userId <= 0 || positionKey <= 0) throw new IllegalArgumentException("invalid snapshot position key");
        }

        /**
         * 先按用户编号排序，再按用户范围内的内部持仓键排序。
         * @param other 排序比较的另一个同类型测试键
         */
        @Override
        public int compareTo(PositionKey other) {
            // 两个键的用户编号比较结果，非零时无需继续比较内部身份。
            int userComparison = Long.compare(userId, other.userId);
            return userComparison != 0 ? userComparison : Long.compare(positionKey, other.positionKey);
        }
    }

    /**
     * 测试持仓的数量、成本、盈亏及保证金值，外层 Map 键保存持仓身份。
     * @param userId 所属用户编号
     * @param symbolId 内部币对编号，零有效
     * @param assetId 内部资产编号，零有效
     * @param marginMode 全仓或逐仓保证金模式
     * @param positionSide 净持仓、多仓或空仓方向
     * @param signedQuantitySteps 带方向的持仓数量，负数为空仓
     * @param entryPriceTicks 持仓开仓均价
     * @param entryValueTicks 持仓保存的开仓价值
     * @param realizedPnlUnits 持仓累计已实现盈亏
     * @param positionMarginUnits 持仓冻结的保证金金额
     */
    public record PositionSnapshot(long userId, int symbolId, int assetId, CoreMarginMode marginMode,
                                   CorePositionSide positionSide,
                                   long signedQuantitySteps, long entryPriceTicks, long entryValueTicks,
                                   long realizedPnlUnits, long positionMarginUnits) {
        /**
         * 检查正数用户、非负内部币对/资产编号、非空模式与方向以及非负保证金；数量、成本和累计盈亏的其他业务约束不在此统一检查。
         */
        public PositionSnapshot {
            if (userId <= 0 || symbolId < 0 || assetId < 0 || marginMode == null || positionSide == null
                    || positionMarginUnits < 0) {
                throw new IllegalArgumentException("invalid snapshot position");
            }
        }
    }

    /**
     * 测试保存的强平执行与亏空进度，强平编号在外层 Map 键中。
     * @param userId 所属用户编号
     * @param symbolId 内部币对编号，零有效
     * @param marginMode 全仓或逐仓保证金模式
     * @param positionSide 净持仓、多仓或空仓方向
     * @param triggerPriceSequence 产生强平计划时的参考价格序号
     * @param signedQuantitySteps 带方向的持仓数量，负数为空仓
     * @param closeQuantitySteps 强平计划需要关闭的正数数量
     * @param deficitUnits 强平尚未覆盖的亏空金额
     * @param executionPriceTicks 强平实际执行价
     * @param liquidationFeeRatePpm 强平费率，百万分比
     * @param liquidationFeeUnits 实际收取的强平费用
     * @param status 该强平记录的业务处理状态
     * @param nextCancelOrderId 强平下一页撤单游标
     */
    public record LiquidationSnapshot(long userId, int symbolId, CoreMarginMode marginMode,
                                      CorePositionSide positionSide,
                                      long triggerPriceSequence, long signedQuantitySteps,
                                      long closeQuantitySteps, long deficitUnits, long executionPriceTicks,
                                      long liquidationFeeRatePpm, long liquidationFeeUnits,
                                      CoreLiquidationState.Status status, long nextCancelOrderId) {
        /**
         * 检查用户、币对、方向、价格序号、非零原持仓、合法关闭数量及非负亏空/费用/游标，费率最多一百万；原持仓绝对值使用精确运算，最小 long 的绝对值仍会溢出。
         */
        public LiquidationSnapshot {
            if (userId <= 0 || symbolId < 0 || marginMode == null || positionSide == null
                    || triggerPriceSequence <= 0 || signedQuantitySteps == 0
                    || closeQuantitySteps <= 0 || closeQuantitySteps > Math.absExact(signedQuantitySteps)
                    || deficitUnits < 0 || executionPriceTicks < 0 || liquidationFeeRatePpm < 0
                    || liquidationFeeRatePpm > 1_000_000 || liquidationFeeUnits < 0 || status == null
                    || nextCancelOrderId < 0) {
                throw new IllegalArgumentException("invalid snapshot liquidation");
            }
        }
    }

    /**
     * 测试保存的标记、指数、远期价格及价格序号和时间，不是完整生产参考价记录。
     * @param markPriceTicks 标记价格
     * @param indexPriceTicks 指数参考价格
     * @param forwardPriceTicks 远期参考价格
     * @param priceSequence 参考价格序号
     * @param generatedAtEpochMillis 参考价格生成时刻，毫秒
     */
    public record MarkPriceSnapshot(long markPriceTicks, long indexPriceTicks,
                                    long forwardPriceTicks, long priceSequence, long generatedAtEpochMillis) {
        /**
         * 要求标记价、序号和时间为正，指数与远期价非负且同时为零或同时非零；此投影不包含生产参考价记录的最近成交价字段。
         */
        public MarkPriceSnapshot {
            if (markPriceTicks <= 0 || indexPriceTicks < 0 || forwardPriceTicks < 0
                    || (indexPriceTicks == 0) != (forwardPriceTicks == 0) || priceSequence <= 0
                    || generatedAtEpochMillis <= 0) {
                throw new IllegalArgumentException("invalid snapshot mark price");
            }
        }
    }

    /**
     * 测试保存的账户持仓风险估值及状态。
     * @param userId 所属用户编号
     * @param symbolId 内部币对编号，零有效
     * @param positionSide 净持仓、多仓或空仓方向
     * @param priceSequence 参考价格序号
     * @param equityUnits 账户风险估值权益
     * @param unrealizedPnlUnits 尚未实现的持仓盈亏
     * @param maintenanceMarginUnits 估值要求的维持保证金金额
     * @param marginRatioPpm 风险保证金比率，百万分比
     * @param status 该用户持仓的风险估值状态
     */
    public record RiskSnapshot(long userId, int symbolId, CorePositionSide positionSide,
                               long priceSequence, long equityUnits, long unrealizedPnlUnits,
                               long maintenanceMarginUnits, long marginRatioPpm, CoreRiskStatus status) {
        /**
         * 检查正数用户与价格序号、非负内部币对号和保证金/比率及非空方向/状态；权益和未实现盈亏允许保存有符号值。
         */
        public RiskSnapshot {
            if (userId <= 0 || symbolId < 0 || positionSide == null || priceSequence <= 0
                    || maintenanceMarginUnits < 0 || marginRatioPpm < 0 || status == null) {
                throw new IllegalArgumentException("invalid snapshot risk");
            }
        }
    }

    /**
     * 风险和触发扫描的测试进度记录；没有紧凑构造校验，laneProgress 按传入引用保存，不额外复制或固定列表。
     * @param accountLaneId 账户 Lane 的分页位置
     * @param priceSequence 参考价格序号
     * @param scanStartPriceSequence 此轮风险扫描开始时的价格序号
     * @param riskComplete 本币对当前风险扫描是否结束
     * @param triggerComplete 当前币对触发单扫描是否结束
     * @param triggerPhase 触发单扫描阶段
     * @param triggerPriceCursor 触发条件价格索引的分页位置
     * @param triggerOrderCursor 当前触发条件下的订单游标
     * @param triggerUpperId 本轮触发扫描固定的编号上界
     * @param triggerMarkPriceTicks 本轮触发扫描固定使用的价格
     * @param triggerGeneratedAtEpochMillis 本轮触发扫描固定价格的生成时刻，毫秒
     * @param triggerOcoOrderId 正在清理 OCO 兄弟单的触发单编号
     * @param triggerOcoCursor 当前 OCO 兄弟单扫描游标
     * @param lastScheduledRevision 此币对上次安排扫描时的修订号
     * @param laneProgress 各账户 Lane 保存的风险扫描进度列表
     */
    public record RiskScanSnapshot(int accountLaneId, long priceSequence, long scanStartPriceSequence,
                                   boolean riskComplete, boolean triggerComplete, int triggerPhase,
                                   long triggerPriceCursor, long triggerOrderCursor, long triggerUpperId,
                                   long triggerMarkPriceTicks, long triggerGeneratedAtEpochMillis,
                                   long triggerOcoOrderId, long triggerOcoCursor, long lastScheduledRevision,
                                   java.util.List<RiskLaneProgress> laneProgress) {
    }

    /**
     * 同一资产七类财库金额的测试记录；本记录没有额外数值校验，资产内部编号放在外层 Map 键。
     * @param feeUnits 该资产财库手续费余额
     * @param insuranceUnits 该资产保险基金余额
     * @param insuranceDeficitUnits 该资产保险亏空
     * @param liquidationFeeUnits 实际收取的强平费用
     * @param fundingResidualUnits 该资产资金费实际收付剩余差额
     * @param roundingResidualUnits 该资产舍入剩余差额
     * @param clearingPnlUnits 该资产财库清算盈亏余额
     */
    public record TreasurySnapshot(long feeUnits, long insuranceUnits, long insuranceDeficitUnits,
                                   long liquidationFeeUnits, long fundingResidualUnits,
                                   long roundingResidualUnits, long clearingPnlUnits) {
    }

    /** 与生产续页状态一致的测试快照值。 */
    public record FundingProgressSnapshot(long settlementId, long fundingRatePpm, long nextCursorUserId, long markPriceTicks,
            long priceSequence) {
        public FundingProgressSnapshot {
            if (settlementId <= 0 || Math.absExact(fundingRatePpm) > 1_000_000 || nextCursorUserId < 0 || markPriceTicks <= 0 || priceSequence <= 0)
                throw new IllegalArgumentException("invalid snapshot progress");
        }
    }

    /** 与生产续页状态一致的测试快照值。 */
    public record LifecycleProgressSnapshot(long settlementId, long settlementPriceTicks, boolean ordersComplete,
            long nextCursorOrderId, long nextCursorUserId, long requiredInsuranceUnits) {
        public LifecycleProgressSnapshot {
            if (settlementId <= 0 || settlementPriceTicks < 0 || requiredInsuranceUnits < 0 || requiredInsuranceUnits > 0 && !ordersComplete || nextCursorOrderId < 0 || nextCursorUserId < 0 || (!ordersComplete && nextCursorUserId != 0) || (ordersComplete && nextCursorOrderId != 0))
                throw new IllegalArgumentException("invalid snapshot progress");
        }
    }
}
