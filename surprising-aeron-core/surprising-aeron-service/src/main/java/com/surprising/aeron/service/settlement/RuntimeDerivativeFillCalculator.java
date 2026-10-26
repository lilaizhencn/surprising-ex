package com.surprising.aeron.service.settlement;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.account.BalanceRuntime;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPriceRuntime;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.order.state.ReservationRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.product.ProductTradingRules;
import com.surprising.aeron.service.product.ProductTradingRulesRegistry;
import com.surprising.aeron.service.product.option.OptionFillCalculator;
import com.surprising.aeron.service.treasury.RuntimeTreasuryDelta;
import com.surprising.aeron.support.math.*;
import com.surprising.aeron.support.math.CoreContractMath;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 下单步骤 4.4 的衍生品成交计算。读取所属账户的订单、预留、余额、持仓及产品规则，在 Lane 独占游标中计算开平仓、保证金、盈亏、权利金和手续费，再写回同一账户；系统资金差额交给原账本边界，不跨 Lane 读写权威状态。 构造约定：只提供成交计算静态入口，不允许单独创建工具实例。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeDerivativeFillCalculator {

    /**
     * 校验单笔成交、账户预留及余额归属，借用所属 Lane 的单笔游标计算并发布结果；期权额外要求风险标记价，finally 释放游标引用供下次复用。
     * @param runtime 负责当前所属账户写入和变化捕获的运行时
     * @param lane 当前线程独占的用户账户 Lane
     * @param identities 解析资产、币对及持仓身份的运行时字典
     * @param instrument 当前订单的规范衍生品币对
     * @param order 当前需要应用成交的开放权威订单
     * @param positionKey 此订单用户、合约及持仓方向对应的运行时键
     * @param fillPriceTicks 本笔实际成交价刻度，须为正
     * @param fillQuantitySteps 本笔成交数量步长，须为正且不超过游标剩余量
     * @param taker 本笔是否为主动成交，用于选择 taker 或 maker 费率
     * @param leveragePpm 期货成交使用的百万分比杠杆，期权不使用
     * @param settleAssetId 结算资产的运行时编号
     * @param treasuryDelta 所属 Lane 当前事件的系统财务差额接收槽
     * @param commitTimestamp 订单写回时使用的复制业务时间，负值沿既有调用约定处理
     * @param commitPosition 订单写回时使用的复制日志位置
     */
    static void apply(TradingAccountRuntime runtime, AccountLaneState lane, RuntimeIdentityRegistry identities,
                      CoreInstrument instrument, OrderRuntime order,
                      long positionKey, long fillPriceTicks, long fillQuantitySteps,
                      boolean taker, long leveragePpm, int settleAssetId,
                      RuntimeTreasuryDelta treasuryDelta, long commitTimestamp, long commitPosition) {
        if (runtime == null || identities == null || instrument == null || order == null || treasuryDelta == null
                || fillPriceTicks <= 0 || fillQuantitySteps <= 0
                || !instrument.contractType().isOption() && leveragePpm <= 0 || settleAssetId < 0) {
            throw new IllegalArgumentException("invalid perpetual fill arguments");
        }
        // 根据当前合约选择的产品资金规则，期权与各本位期货保持各自公式。
        ProductTradingRules kernel = ProductTradingRulesRegistry.forInstrument(instrument);
        if (kernel.productLine() == com.surprising.product.api.ProductLine.SPOT
                || order.symbolId() < 0 || settleAssetId < 0) {
            throw new IllegalArgumentException("runtime fill instrument identity mismatch");
        }
        if (order.orderType() == com.surprising.aeron.protocol.CoreOrderType.MARKET
                && order.priceTicks() < 0) {
            throw new IllegalArgumentException("invalid market order price");
        }
        if (fillQuantitySteps > order.remainingQuantitySteps()) {
            throw new IllegalStateException("fill exceeds runtime order remaining quantity");
        }
        // 订单对应的权威预留输入。
        ReservationRuntime reservation = lane.reservation(order.orderId());
        // 所属用户结算资产的权威余额。
        BalanceRuntime balance = runtime.balance(lane, order.userId(), settleAssetId);
        if (reservation == null || balance == null || reservation.userId() != order.userId()
                || reservation.assetId() != settleAssetId) {
            throw new IllegalStateException("runtime fill entities are missing: " + order.orderId());
        }
        // 此持仓键当前已有的持仓值，空值表示尚未建仓。
        PositionRuntime current = lane.position(positionKey);
        if (current != null && current.userId() != order.userId()) {
            throw new IllegalStateException("runtime position owner mismatch: " + positionKey);
        }

        // 当前币对标记价，期权成交要求其满足风险价条件。
        MarkPriceRuntime riskMark = runtime.markPrice(lane, order.symbolId());
        if (instrument.contractType().isOption()) OptionFillCalculator.requireRiskMark(riskMark);
        runtime.requireLaneAccess(lane, order.userId());
        // 所属 Lane 可复用的单笔成交游标，finally 清理引用。
        FillCursor cursor = lane.singleFillCursor();
        try {
            cursor.reset(instrument, order, reservation, current, balance.availableUnits(),
                    balance.lockedUnits(), settleAssetId, riskMark);
            cursor.step(fillPriceTicks, fillQuantitySteps, taker, leveragePpm, commitTimestamp, commitPosition);
            cursor.publish(runtime, lane, positionKey, treasuryDelta);
        } finally { cursor.clear(); }
    }

    /**
     * 单个账户 Lane 独占的成交计算游标，可承接同一订单多笔成交。只保留本轮标量及权威起点引用，publish 写回、clear 释放，不共享给其他 Lane，也不提供自动事务回滚。
     */
    public static final class FillCursor {
        /** 本轮独占游标关联的规范衍生品币对。 */
        private CoreInstrument instrument;
        /** 本轮开始时的权威订单引用，用于累计成交与最终差额，clear 解除引用。 */
        private OrderRuntime originalOrder;
        /** 本轮开始时的权威预留引用，用于计算仍可消耗的预留。 */
        private ReservationRuntime originalReservation;
        /** 本轮使用的标记价，期权保证金依赖该风险输入。 */
        private MarkPriceRuntime riskMark;
        /** 本轮余额、预留及系统差额使用的结算资产编号。 */
        private int settleAssetId;
        /** 游标是否已有持仓输入或已经形成成交后的持仓值。 */
        private boolean hasPosition;
        /**
         * available：游标累计计算后的可用余额，publish 前不是独立权威账户。
         * locked：游标累计计算后的冻结余额，包含订单或持仓资金占用。
         * quantity：游标累计计算后的有符号持仓量，正多负空。
         * entryPrice：当前持仓的开仓均价刻度，归零持仓时为零。
         * entryValue：当前代码按持仓绝对量乘开仓均价记录的入场价值。
         * realizedPnl：持仓累计完整已实现盈亏，不等同于余额实际可扣入的盈亏金额。
         * margin：游标计算后的持仓保证金占用。
         */
        private long available, locked, quantity, entryPrice, entryValue, realizedPnl, margin;
        /**
         * executed：订单更新后的累计成交数量。
         * remaining：订单当前尚未成交数量。
         * cumulativeFee：订单累计手续费，收费增加，返佣可抵减。
         * orderRevision：每次成交精确递增后的订单修订号。
         * consumed：订单预留累计已消耗金额，包括本轮此前成交。
         * fills：本轮游标已应用成交次数，发布用户修订时按此累计。
         */
        private long executed, remaining, cumulativeFee, orderRevision, consumed, fills;
        /**
         * executedValueHigh：订单累计成交价格乘数量的宽整数高六十四位。
         * executedValueLow：订单累计成交价格乘数量的宽整数低六十四位。
         */
        private long executedValueHigh, executedValueLow;
        /**
         * timestamp：本轮成交写回订单时使用的复制业务时间，负值按既有入口约定不补时间。
         * clusterPosition：本轮成交写回订单时使用的复制日志位置。
         */
        private long timestamp, clusterPosition;
        /**
         * feeTreasuryUnits：最近一笔成交对应的系统手续费变化，逐笔覆盖而非累计。
         * clearingTreasuryUnits：最近一笔实际计入余额盈亏的反向系统清算变化，逐笔覆盖。
         */
        private long feeTreasuryUnits, clearingTreasuryUnits;
        /**
         * positionKey：begin 保存的所属账户持仓键，clear 时归零。
         * leveragePpm：begin 保存的百万分比杠杆，用于连续成交重复计算。
         */
        private long positionKey, leveragePpm;

        /**
         * 把当前订单、预留、持仓和余额起点装入游标，重置本轮成交次数；只准备计算输入，不立即修改账户权威值。
         * @param instrument 当前订单的规范衍生品币对
         * @param order 当前需要应用成交的开放权威订单
         * @param reservation 当前订单的权威预留，须与用户及资产一致
         * @param current 当前持仓权威值，首次开仓可以为空
         * @param available 本轮起点的非负可用余额
         * @param locked 本轮起点的非负冻结余额
         * @param assetId 本轮使用的结算资产编号
         * @param mark 本轮持仓估值需要的标记价，期权必须有效
         */
        void reset(CoreInstrument instrument, OrderRuntime order, ReservationRuntime reservation,
                   PositionRuntime current, long available, long locked, int assetId, MarkPriceRuntime mark) {
            if (instrument == null || order == null || reservation == null || available < 0 || locked < 0
                    || assetId < 0 || reservation.userId() != order.userId() || reservation.assetId() != assetId
                    || current != null && current.userId() != order.userId()) {
                throw new IllegalArgumentException("invalid perpetual fill calculation");
            }
            this.instrument = instrument; originalOrder = order; originalReservation = reservation;
            riskMark = mark; settleAssetId = assetId; this.available = available; this.locked = locked;
            hasPosition = current != null;
            quantity = current == null ? 0 : current.signedQuantitySteps();
            entryPrice = current == null ? 0 : current.entryPriceTicks();
            entryValue = current == null ? 0 : current.entryValueTicks();
            realizedPnl = current == null ? 0 : current.realizedPnlUnits();
            margin = current == null ? 0 : current.positionMarginUnits();
            executed = order.executedQuantitySteps(); remaining = order.remainingQuantitySteps();
            executedValueHigh = order.executedValueHigh(); executedValueLow = order.executedValueLow();
            cumulativeFee = order.cumulativeFeeUnits(); orderRevision = order.revision();
            consumed = reservation.consumedUnits(); fills = 0;
        }
        /**
         * 以预留总额减去已释放额和游标累计已消耗额，得到本轮仍可使用的订单预留。
         */
        long reservedUnits() {
            return Math.subtractExact(originalReservation.totalReservedUnits(),
                    Math.addExact(originalReservation.releasedUnits(), consumed));
        }
        /**
         * 按给定价格、数量、角色和杠杆计算下一笔成交，更新游标；账户写回由 publish 完成。
         * @param price 本笔实际成交价刻度
         * @param size 本笔成交数量步长
         * @param taker 本笔是否为主动成交，用于选择 taker 或 maker 费率
         * @param leverage 期货成交使用的百万分比杠杆，期权不使用
         * @param timestamp 本轮订单业务时间
         * @param position 本轮复制日志位置
         */
        void step(long price, long size, boolean taker, long leverage, long timestamp, long position) {
            calculateInto(this, price, size, taker, leverage, timestamp, position);
        }
        /**
         * 使用 begin 保存的杠杆和业务元数据累加一笔成交，并立刻按原事件顺序记入本笔费用及实际盈亏财务差额；之后发布游标时不再重复记费。
         * @param price 本笔实际成交价刻度
         * @param size 本笔成交数量步长
         * @param taker 本笔是否为主动成交，用于选择 taker 或 maker 费率
         * @param treasury 本笔财务差额接收槽；publish 中可为空以避免重复记入逐笔差额
         */
        void applyNext(long price, long size, boolean taker, RuntimeTreasuryDelta treasury) {
            step(price, size, taker, leveragePpm, timestamp, clusterPosition);
            // 每笔立即记财务差额，保持与 maker 差额交错出现的原始成交顺序。
            treasury.addFee(settleAssetId, feeTreasuryUnits);
            treasury.addClearing(settleAssetId, clearingTreasuryUnits);
        }
        /**
         * 返回 begin 为本聚合订单准备的持仓身份键。
         */
        long positionKey() { return positionKey; }
        /**
         * 有成交才把游标累计的预留、余额、持仓、订单和用户修订写回所属 Lane；非空财务目标只接收游标最近一笔差额，逐笔已记账的累加路径传空。返回更新后的权威订单引用。
         * @param runtime 负责当前所属账户写入和变化捕获的运行时
         * @param lane 当前线程独占的用户账户 Lane
         * @param positionKey 此订单用户、合约及持仓方向对应的运行时键
         * @param treasury 本笔财务差额接收槽；publish 中可为空以避免重复记入逐笔差额
         */
        OrderRuntime publish(TradingAccountRuntime runtime, AccountLaneState lane, long positionKey, RuntimeTreasuryDelta treasury) {
            if (fills == 0) return null;
            // 根据最终剩余量确定的订单状态，全部成交为 FILLED，否则保留原状态。
            CoreOrderStatus nextStatus = remaining == 0 ? CoreOrderStatus.FILLED : originalOrder.status();
            // 本轮订单累计手续费与权威起点的差额，传入订单更新入口。
            long feeDelta = Math.subtractExact(cumulativeFee, originalOrder.cumulativeFeeUnits());
            runtime.updateReservationInLane(lane, originalOrder.orderId(), consumed, originalReservation.releasedUnits(), remaining != 0);
            runtime.replaceBalance(lane, originalOrder.userId(), settleAssetId, available, locked);
            runtime.updatePositionInLane(lane, positionKey, originalOrder.userId(), originalOrder.symbolId(), settleAssetId,
                    originalOrder.instrument(), quantity, entryPrice, entryValue,
                    realizedPnl, margin, originalOrder.marginMode(), originalOrder.positionSide());
            // 本轮累计结果写回后返回的权威订单引用。
            OrderRuntime nextOrder = runtime.updateOrderInLane(lane, originalOrder.orderId(), executed, remaining,
                    feeDelta, executedValueHigh, executedValueLow, nextStatus, orderRevision, timestamp, clusterPosition);
            if (treasury != null) {
                treasury.addFee(settleAssetId, feeTreasuryUnits);
                treasury.addClearing(settleAssetId, clearingTreasuryUnits);
            }
            runtime.advanceUserRevision(lane, originalOrder.userId(), fills);
            return nextOrder;
        }
        /**
         * 释放本轮币对、订单、预留和标记价引用，并清除成交数、持仓键及缓存杠杆；其余原语值在下次 reset 或 begin 覆盖，不提供账户回滚。
         */
        void clear() {
            instrument = null; originalOrder = null; originalReservation = null; riskMark = null;
            fills = 0; positionKey = 0; leveragePpm = 0;
        }
    }

    /**
     * 为连续成交准备调用方独占游标，要求旧游标已清理；核对账户余额与预留，加载持仓和期权风险价，再保存持仓键、杠杆及业务时间位置。
     * @param cursor 调用方独占且上一轮已清理的成交游标
     * @param runtime 负责当前所属账户写入和变化捕获的运行时
     * @param lane 当前线程独占的用户账户 Lane
     * @param instrument 当前订单的规范衍生品币对
     * @param order 当前需要应用成交的开放权威订单
     * @param positionKey 此订单用户、合约及持仓方向对应的运行时键
     * @param leverage 期货成交使用的百万分比杠杆，期权不使用
     * @param assetId 本轮使用的结算资产编号
     * @param timestamp 本轮订单业务时间
     * @param position 本轮复制日志位置
     */
    static void begin(FillCursor cursor, TradingAccountRuntime runtime, AccountLaneState lane, CoreInstrument instrument,
                      OrderRuntime order, long positionKey, long leverage, int assetId,
                      long timestamp, long position) {
        if (cursor == null || runtime == null || instrument == null || order == null
                || order.symbolId() < 0 || !instrument.contractType().isOption() && leverage <= 0 || assetId < 0
                || instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT
                || timestamp < -1 || position < -1 || timestamp >= 0 && position < 0) {
            throw new IllegalArgumentException("invalid perpetual fill cursor");
        }
        if (cursor.originalOrder != null) throw new IllegalStateException("perpetual fill cursor is still active");
        // 所属用户结算资产的权威余额。
        BalanceRuntime balance = runtime.balance(lane, order.userId(), assetId);
        // 订单对应的权威预留输入。
        ReservationRuntime reservation = lane.reservation(order.orderId());
        if (balance == null || reservation == null || reservation.userId() != order.userId()
                || reservation.assetId() != assetId) {
            throw new IllegalStateException("runtime fill entities are missing: " + order.orderId());
        }
        // 连续成交起点使用的当前标记价。
        MarkPriceRuntime mark = runtime.markPrice(lane, order.symbolId());
        if (instrument.contractType().isOption()) OptionFillCalculator.requireRiskMark(mark);
        cursor.reset(instrument, order, reservation, lane.position(positionKey),
                balance.availableUnits(), balance.lockedUnits(), assetId, mark);
        cursor.positionKey = positionKey;
        cursor.leveragePpm = leverage;
        cursor.timestamp = timestamp;
        cursor.clusterPosition = position;
    }

    /**
     * 把一笔成交拆为平仓和开仓，计算保证金、权利金、费用及预留消耗，校验余额和持仓后更新游标。亏损记入持仓的完整已实现盈亏，但余额实际扣款最多到可用额，系统清算差额按实际入账盈亏计算；异常交给外层结算边界处理。
     * @param state 本轮独占的成交计算游标，所有计算结果先写在此对象中
     * @param fillPriceTicks 本笔实际成交价刻度，须为正
     * @param fillQuantitySteps 本笔成交数量步长，须为正且不超过游标剩余量
     * @param taker 本笔是否为主动成交，用于选择 taker 或 maker 费率
     * @param leveragePpm 期货成交使用的百万分比杠杆，期权不使用
     * @param commitTimestamp 订单写回时使用的复制业务时间，负值沿既有调用约定处理
     * @param commitPosition 订单写回时使用的复制日志位置
     */
    private static void calculateInto(FillCursor state, long fillPriceTicks, long fillQuantitySteps,
                                      boolean taker, long leveragePpm, long commitTimestamp, long commitPosition) {
        // 游标保存的规范币对，用于选择产品规则。
        CoreInstrument instrument = state.instrument;
        // 游标起点订单引用，用于方向、费率及订单总量规则。
        OrderRuntime order = state.originalOrder;
        // 订单对应的权威预留输入。
        ReservationRuntime reservation = state.originalReservation;
        // 当前币对标记价，期权成交要求其满足风险价条件。
        MarkPriceRuntime riskMark = state.riskMark;
        // 本笔计算开始前游标冻结余额。
        // 本笔计算开始前游标可用余额。
        long availableUnits = state.available, lockedUnits = state.locked;
        if (instrument == null || fillPriceTicks <= 0 || fillQuantitySteps <= 0
                || !instrument.contractType().isOption() && leveragePpm <= 0
                || fillQuantitySteps > state.remaining) throw new IllegalArgumentException("invalid perpetual fill calculation");
        // 根据当前合约选择的产品资金规则，期权与各本位期货保持各自公式。
        ProductTradingRules kernel = ProductTradingRulesRegistry.forInstrument(instrument);
        // 把成交数量按买正卖负转换后的有符号变化。
        long signedFill = order.side() == CoreOrderSide.BUY ? fillQuantitySteps : Math.negateExact(fillQuantitySteps);
        // 本笔之前的有符号持仓数量。
        long currentQuantity = state.quantity;
        // 本笔之前持仓的绝对数量。
        long currentAbs = Math.absExact(currentQuantity);
        // 本笔成交方向是否与已有持仓相反，决定先平旧仓还是直接加仓。
        boolean opposite = currentQuantity != 0 && Long.signum(currentQuantity) != Long.signum(signedFill);
        // 本笔可用于关闭原方向持仓的数量，最多到已有绝对持仓量。
        long closeSteps = opposite ? Math.min(currentAbs, fillQuantitySteps) : 0;
        // 扣除平仓数量后本笔真正新开的数量。
        long openSteps = Math.subtractExact(fillQuantitySteps, closeSteps);
        if (order.reduceOnly() && openSteps != 0) {
            throw new CoreStateRejectedException("REDUCE_ONLY_CAPACITY_EXCEEDED",
                    "reduce-only fill would create reverse exposure");
        }

        // 关闭旧仓部分按比例可释放的原持仓保证金。
        long releasedMargin = !state.hasPosition || closeSteps == 0 ? 0
                : proportional(state.margin, closeSteps, currentAbs);
        // 本笔完成后的有符号持仓数量。
        long nextQuantity = Math.addExact(currentQuantity, signedFill);
        // 扣除平仓释放后仍留在旧仓中的保证金。
        long remainingMargin = Math.subtractExact(state.margin, releasedMargin);
        // 本笔新增持仓保证金，非期权价格改善或同价舍入时可按预留预算收敛。
        long marginIncrease = openingMarginForFill(instrument, nextQuantity, signedFill, openSteps,
                fillPriceTicks, leveragePpm, riskMark);
        // 根据主动或被动角色选择的订单费率。
        long feeRatePpm = taker ? order.takerFeeRatePpm() : order.makerFeeRatePpm();
        // 产品规则计算的本笔权利金变化，正数收入、负数支出。
        long premiumDelta = kernel.premiumDeltaUnits(instrument, order.side(), fillPriceTicks, fillQuantitySteps);
        // 本笔用户费用变化，负数收费、正数返佣，与系统费用方向相反。
        long feeDelta = CoreContractMath.feeDeltaUnits(instrument, fillPriceTicks, fillQuantitySteps, feeRatePpm);
        // 本笔需要支出的权利金金额，收入时为零。
        long premiumDebit = Math.max(0, Math.negateExact(premiumDelta));
        // 本笔需要支付的手续费金额，返佣时为零。
        long feeDebit = Math.max(0, Math.negateExact(feeDelta));
        // 期权卖出所得中用于供应本笔新增卖方保证金的金额。
        long premiumMarginFunding = instrument.contractType().isOption()
                ? OptionFillCalculator.premiumMarginFunding(
                        instrument, premiumDelta, openSteps, marginIncrease, fillPriceTicks) : 0;
        // 期权卖出收入先供应卖方保证金，再支付手续费；
        // 卖出成交价改善后，费用可能高于按订单较低保护价冻结的金额。
        // 期权卖出所得扣除保证金供给后，可用于支付本笔手续费的金额。
        long premiumFeeFunding = instrument.contractType().isOption() && premiumDelta > 0
                ? Math.min(feeDebit, Math.subtractExact(premiumDelta, premiumMarginFunding)) : 0;
        // 按本笔数量占剩余订单数量的比例分摊的预留预算。
        long proportionalBudget = proportional(state.reservedUnits(), fillQuantitySteps,
                state.remaining);
        // 以当前剩余预留为上限，并兼顾本笔费用和比例预算的可用额度。
        long fillReservationBudget = Math.min(state.reservedUnits(),
                Math.max(feeDebit, proportionalBudget));
        // 同价部分成交也要分摊已接受预算，不能因每笔向上舍入就耗尽整单保证金。
        // 更差成交价仍按下方正常资金缺口规则校验。
        // 实际成交价是否优于订单撮合保护价，买单更低或卖单更高。
        boolean betterFill = order.side() == CoreOrderSide.BUY
                ? fillPriceTicks < order.matchingPriceTicks()
                : fillPriceTicks > order.matchingPriceTicks();
        // 非期权同价开仓分笔舍入超过本笔预算、但原整单预留足够时的保证金收敛标记。
        boolean samePriceRounding = fillPriceTicks == order.matchingPriceTicks() && closeSteps == 0
                && marginIncrease > Math.max(0, fillReservationBudget - feeDebit)
                && !instrument.contractType().isOption()
                && reservation.totalReservedUnits() >= Math.addExact(
                        openingMarginForFill(instrument, nextQuantity, signedFill, order.quantitySteps(),
                                fillPriceTicks, leveragePpm, riskMark),
                        Math.max(0, Math.negateExact(CoreContractMath.feeDeltaUnits(instrument,
                                fillPriceTicks, order.quantitySteps(), Math.max(order.makerFeeRatePpm(), order.takerFeeRatePpm())))));
        if ((betterFill || samePriceRounding) && !instrument.contractType().isOption()) {
            marginIncrease = Math.min(marginIncrease, Math.max(0,
                    Math.subtractExact(fillReservationBudget, Math.addExact(premiumDebit, feeDebit))));
        }
        // 本笔保证金、权利金和费用扣除期权收入供给后需要的总资金。
        long reservationDebit = Math.addExact(Math.subtractExact(marginIncrease, premiumMarginFunding),
                Math.addExact(premiumDebit, Math.subtractExact(feeDebit, premiumFeeFunding)));
        // 总资金需求超过当前剩余订单预留的缺口，平仓路径可再检查释放保证金。
        long reservationShortfall = Math.max(0,
                Math.subtractExact(reservationDebit, state.reservedUnits()));
        if (reservationShortfall > 0 && closeSteps == 0) {
            throw new CoreStateRejectedException("INSUFFICIENT_ORDER_RESERVATION",
                    "runtime reservation is insufficient: orderId=" + order.orderId()
                            + ", required=" + reservationDebit
                            + ", remaining=" + state.reservedUnits());
        }
        // 本笔用平仓释放保证金补足订单预留缺口的金额。
        long releasedMarginDebit = reservationShortfall;
        if (releasedMarginDebit > releasedMargin) {
            throw new CoreStateRejectedException("INSUFFICIENT_ORDER_RESERVATION",
                    "runtime reservation and released position margin are insufficient");
        }
        // 本笔最终实际从订单预留中消耗的金额，不包括释放保证金补足部分。
        long orderReservationDebit = Math.subtractExact(reservationDebit, releasedMarginDebit);
        // 计入释放保证金、实际盈亏、权利金和返佣后的可用余额。
        long nextAvailable = availableUnits;
        // 扣除权利金和费用、调整保证金占用后的冻结余额。
        long nextLocked = lockedUnits;
        // 释放保证金扣除本笔资金缺口后真正可从冻结转为可用的剩余金额。
        long marginReleaseUnits = Math.subtractExact(releasedMargin, releasedMarginDebit);
        if (marginReleaseUnits > 0) {
            nextAvailable = Math.addExact(nextAvailable, marginReleaseUnits);
            nextLocked = Math.subtractExact(nextLocked, marginReleaseUnits);
        }

        // 按平仓方向和产品公式计算的本笔完整已实现盈亏。
        long realizedPnl = 0;
        if (closeSteps > 0) {
            // 平仓部分沿原持仓方向带符号的数量。
            long signedClose = currentQuantity > 0 ? closeSteps : Math.negateExact(closeSteps);
            realizedPnl = kernel.realizedPnlUnits(
                    instrument, signedClose, state.entryPrice, fillPriceTicks);
        }
        // 本笔实际计入余额的盈亏，负数亏损最多扣到当前可用余额，不等同完整持仓已实现盈亏。
        long appliedPnl;
        if (realizedPnl >= 0) {
            nextAvailable = Math.addExact(nextAvailable, realizedPnl);
            appliedPnl = realizedPnl;
        } else {
            // 完整亏损与当前可用余额取小后的实际扣款额。
            long debit = Math.min(nextAvailable, Math.negateExact(realizedPnl));
            nextAvailable = Math.subtractExact(nextAvailable, debit);
            appliedPnl = Math.negateExact(debit);
        }
        if (premiumDelta < 0) nextLocked = Math.subtractExact(nextLocked, Math.negateExact(premiumDelta));
        else if (premiumDelta > 0) {
            // 期权收入中继续留在冻结余额用于保证金和费用的金额。
            long retainedPremium = Math.addExact(premiumMarginFunding, premiumFeeFunding);
            nextLocked = Math.addExact(nextLocked, retainedPremium);
            nextAvailable = Math.addExact(nextAvailable,
                    Math.subtractExact(premiumDelta, retainedPremium));
        }
        if (feeDelta < 0) nextLocked = Math.subtractExact(nextLocked, Math.negateExact(feeDelta));
        else if (feeDelta > 0) nextAvailable = Math.addExact(nextAvailable, feeDelta);
        if (nextAvailable < 0 || nextLocked < 0) {
            throw new IllegalStateException("runtime fill balance would become negative");
        }
        // 归零、反向开仓、同向加仓或部分平仓对应的新开仓均价。
        long nextEntryPrice;
        // 本笔后持仓绝对数量乘新均价得到的入场价值。
        long nextEntryValue;
        if (nextQuantity == 0) {
            nextEntryPrice = 0;
            nextEntryValue = 0;
        } else if (currentQuantity == 0 || Long.signum(nextQuantity) != Long.signum(currentQuantity)) {
            nextEntryPrice = fillPriceTicks;
            nextEntryValue = Math.multiplyExact(Math.absExact(nextQuantity), fillPriceTicks);
        } else if (Long.signum(signedFill) == Long.signum(currentQuantity)) {
            nextEntryPrice = CoreContractMath.weightedEntryPrice(instrument, currentAbs,
                    state.entryPrice, fillQuantitySteps, fillPriceTicks);
            nextEntryValue = Math.multiplyExact(Math.absExact(nextQuantity), nextEntryPrice);
        } else {
            nextEntryPrice = state.entryPrice;
            nextEntryValue = Math.multiplyExact(Math.absExact(nextQuantity), nextEntryPrice);
        }
        // 剩余旧仓保证金加本笔新增保证金后的持仓占用。
        long nextMargin = Math.addExact(remainingMargin, marginIncrease);
        // 持仓累计已实现盈亏加本笔完整盈亏后的值。
        long nextRealizedPnl = Math.addExact(state.realizedPnl, realizedPnl);
        // 订单扣除本笔成交后的剩余数量。
        long nextRemaining = Math.subtractExact(state.remaining, fillQuantitySteps);
        // 订单加上本笔成交后的累计成交量。
        long nextExecuted = Math.addExact(state.executed, fillQuantitySteps);
        // 订单累计手续费加本笔收费或抵减返佣后的值。
        long nextFee = Math.addExact(state.cumulativeFee, Math.negateExact(feeDelta));
        // 本笔成交后精确递增的订单修订号。
        long nextRevision = Math.incrementExact(state.orderRevision);
        // 订单原累计已消耗额加本笔订单预留消耗后的值。
        long nextConsumed = Math.addExact(state.consumed, orderReservationDebit);
        if (nextMargin < 0 || nextQuantity == 0 && (nextMargin != 0 || nextEntryPrice != 0 || nextEntryValue != 0)
                || nextQuantity != 0 && (nextEntryPrice <= 0 || nextEntryValue <= 0)
                || orderReservationDebit < 0 || orderReservationDebit > state.reservedUnits()) {
            throw new IllegalArgumentException("invalid runtime fill state");
        }
        if (commitTimestamp >= 0 && commitPosition < 0) throw new IllegalArgumentException("invalid runtime order");
        state.available = nextAvailable; state.locked = nextLocked;
        state.quantity = nextQuantity; state.entryPrice = nextEntryPrice; state.entryValue = nextEntryValue;
        state.realizedPnl = nextRealizedPnl; state.margin = nextMargin; state.hasPosition = true;
        state.executed = nextExecuted; state.remaining = nextRemaining; state.cumulativeFee = nextFee;
        state.orderRevision = nextRevision; state.consumed = nextConsumed;
        state.executedValueHigh = com.surprising.aeron.protocol.OrderExecutionValue.addHigh(
                state.executedValueHigh, state.executedValueLow, fillPriceTicks, fillQuantitySteps);
        state.executedValueLow = com.surprising.aeron.protocol.OrderExecutionValue.addLow(
                state.executedValueLow, fillPriceTicks, fillQuantitySteps);
        state.fills = Math.incrementExact(state.fills);
        state.timestamp = commitTimestamp; state.clusterPosition = commitPosition;
        state.feeTreasuryUnits = Math.negateExact(feeDelta);
        state.clearingTreasuryUnits = Math.negateExact(appliedPnl);
    }

    /**
     * 按 part/total 分摊金额，相等时直接返回全部原额，否则先精确乘法再整数除法，由调用方保证分母及业务范围有效。
     * @param units 需要按比例分摊的金额
     * @param part 本次占用的数量部分
     * @param total 对应金额的总数量
     */
    private static long proportional(long units, long part, long total) {
        return part == total ? units : Math.multiplyExact(units, part) / total;
    }

    /**
     * 按合约类型选择期权或期货的开仓保证金计算器，保留各产品对持仓方向、数量、杠杆与风险价的规则。
     * @param instrument 当前订单的规范衍生品币对
     * @param projectedQuantitySteps 本笔成交后预计的有符号持仓量
     * @param signedFillSteps 本笔有符号成交数量，买正卖负
     * @param openSteps 本笔真正增加或反向建立的新仓数量
     * @param priceTicks 本笔成交价刻度
     * @param leveragePpm 期货成交使用的百万分比杠杆，期权不使用
     * @param riskMark 产品保证金计算所需的标记价输入
     */
    private static long openingMarginForFill(CoreInstrument instrument,
                                             long projectedQuantitySteps,
                                             long signedFillSteps,
                                             long openSteps,
                                             long priceTicks,
                                             long leveragePpm,
                                             MarkPriceRuntime riskMark) {
        return instrument.contractType().isOption()
                ? OptionFillCalculator.openingMarginForFill(instrument, projectedQuantitySteps, signedFillSteps, openSteps, riskMark)
                : CoreContractMath.openingMarginForFill(instrument, projectedQuantitySteps, openSteps, priceTicks, leveragePpm);
    }
}
