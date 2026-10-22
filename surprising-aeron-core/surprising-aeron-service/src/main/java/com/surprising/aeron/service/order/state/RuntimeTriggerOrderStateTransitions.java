package com.surprising.aeron.service.order.state;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.protocol.CoreTriggerOrderType;
import com.surprising.aeron.service.account.UserRuntime;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.product.api.ProductLine;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 条件单登记和生命周期状态变更。登记时核对用户、币对、持仓腿和可平仓容量，随后分别推进取消、认领、完成、追踪、到期或重试；触发条件判定、子单准入和 Matcher 执行仍在上游原流程。 构造约定：只提供已有条件单状态规则的静态入口，不新建调度线程或状态表。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeTriggerOrderStateTransitions {

    /**
     * 准备币对稠密身份、产品对应持仓键及账本结算标记，再进入登记入口；虽然名为 upsert，重复条件单编号仍拒绝。
     * @param runtime 提供原用户、持仓、条件单及版本写入入口的所属运行时
     * @param identities 当前产品身份表，准备币对和持仓腿身份使用
     * @param userId 条件单共同所属的正数用户编号
     * @param view 需要登记的新条件单视图，携带意图、触发条件和执行参数
     */
    public static void upsert(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                       long userId, CoreTriggerOrderStateView view) {
        if (runtime == null || identities == null || view == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime trigger order update");
        }
        // 当前条件单币对已准备的稠密身份。
        int symbolId = identities.symbolId(view.instrumentId());
        // 按当前产品、用户和持仓腿取得的身份，衍生品登记时继续核对真实持仓。
        long positionKey = positionKey(identities, runtime.productLine(), userId, view);
        // 账本事实中的已到期结算标记，已结算不能再登记条件单。
        boolean instrumentSettled = runtime.treasury().lifecycleSettlement(symbolId) != 0;
        upsert(runtime, userId, view, symbolId, positionKey, instrumentSettled);
    }

    /**
     * 检查全局版本容量，完整准备一份尚未存在的条件单，再写入并递增命令版本，不用于覆盖旧条件单。
     * @param runtime 提供原用户、持仓、条件单及版本写入入口的所属运行时
     * @param userId 条件单共同所属的正数用户编号
     * @param view 需要登记的新条件单视图，携带意图、触发条件和执行参数
     * @param symbolId 已经准备的币对运行时稠密编号
     * @param positionKey 衍生品对应的用户持仓腿身份键，现货为 0
     * @param instrumentSettled Owner 或账本输入固定的币对已经结算标记
     */
    public static void upsert(TradingAccountRuntime runtime, long userId,
                       CoreTriggerOrderStateView view, int symbolId,
                       long positionKey, boolean instrumentSettled) {
        runtime.requireRevisionCapacity();
        // 已经完整校验并固定执行费率的新条件单记录，随后写入原账户状态。
        CoreTriggerOrderState trigger = preparePlacement(runtime, userId, view, symbolId, positionKey, instrumentSettled);
        runtime.putTriggerOrder(trigger);
        runtime.incrementCommandRevision();
    }

    /**
     * 先校验止盈和止损两腿关系，再分别完成登记校验，并预检两次版本递增容量；两腿都准备好后才依次写入原账户状态。
     * @param runtime 提供原用户、持仓、条件单及版本写入入口的所属运行时
     * @param userId 条件单共同所属的正数用户编号
     * @param takeProfit OCO 第一腿的止盈条件单视图
     * @param stopLoss OCO 第二腿的止损条件单视图
     * @param symbolId 已经准备的币对运行时稠密编号
     * @param positionKey 衍生品对应的用户持仓腿身份键，现货为 0
     * @param instrumentSettled Owner 或账本输入固定的币对已经结算标记
     */
    public static void placeOcoPair(TradingAccountRuntime runtime, long userId,
                                   CoreTriggerOrderStateView takeProfit, CoreTriggerOrderStateView stopLoss,
                                   int symbolId, long positionKey, boolean instrumentSettled) {
        validateOcoPair(takeProfit, stopLoss);
        // 尚未写入的止盈腿准备结果，两腿都通过后才正式写入。
        var first = preparePlacement(runtime, userId, takeProfit, symbolId, positionKey, instrumentSettled);
        // 尚未写入的止损腿准备结果，原命令同时预检两次版本递增。
        var second = preparePlacement(runtime, userId, stopLoss, symbolId, positionKey, instrumentSettled);
        Math.addExact(runtime.revision(), 2);
        runtime.putTriggerOrder(first);
        runtime.putTriggerOrder(second);
        runtime.incrementCommandRevision();
        runtime.incrementCommandRevision();
    }

    /**
     * 要求同用户、产品、币对、方向、资金模式及数量，组标识相同且非空，订单和客户编号不同；第一腿为止盈、第二腿为止损，卖出止盈价高于止损价，买入则相反。
     * @param first 需要核对为止盈腿的第一个 OCO 视图
     * @param second 需要核对为止损腿的第二个 OCO 视图
     */
    public static void validateOcoPair(CoreTriggerOrderStateView first, CoreTriggerOrderStateView second) {
        if (first == null || second == null || first.ocoGroupId().isBlank()
                || !first.ocoGroupId().equals(second.ocoGroupId()) || first.userId() != second.userId()
                || first.productLine() != second.productLine() || !first.instrumentId().equals(second.instrumentId())
                || first.side() != second.side() || first.marginMode() != second.marginMode()
                || first.positionSide() != second.positionSide() || first.quantitySteps() != second.quantitySteps()
                || first.triggerOrderId() == second.triggerOrderId()
                || first.clientTriggerOrderId().equals(second.clientTriggerOrderId())
                || first.triggerType() != CoreTriggerOrderType.TAKE_PROFIT
                || second.triggerType() != CoreTriggerOrderType.STOP_LOSS
                || (first.side() == com.surprising.aeron.protocol.CoreOrderSide.SELL
                    ? first.triggerPriceTicks() <= second.triggerPriceTicks()
                    : first.triggerPriceTicks() >= second.triggerPriceTicks())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "invalid take-profit / stop-loss OCO pair");
        }
    }

    /**
     * 核对归属、客户标识、币对存在和未结算、普通编号与客户编号不重复，再检查持仓容量；准备新条件单并固定当时币对默认 Maker/Taker 费率，不直接写状态。
     * @param runtime 提供原用户、持仓、条件单及版本写入入口的所属运行时
     * @param userId 条件单共同所属的正数用户编号
     * @param view 需要登记的新条件单视图，携带意图、触发条件和执行参数
     * @param symbolId 已经准备的币对运行时稠密编号
     * @param positionKey 衍生品对应的用户持仓腿身份键，现货为 0
     * @param instrumentSettled Owner 或账本输入固定的币对已经结算标记
     */
    private static CoreTriggerOrderState preparePlacement(TradingAccountRuntime runtime, long userId,
                       CoreTriggerOrderStateView view, int symbolId,
                       long positionKey, boolean instrumentSettled) {
        if (runtime == null || view == null || userId <= 0 || symbolId < 0 || positionKey < 0) {
            throw new IllegalArgumentException("invalid prepared runtime trigger order update");
        }
        runtime.assertOwner();
        if (view.userId() != userId || view.productLine() != runtime.productLine()) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_OWNER_MISMATCH", "trigger order owner mismatch");
        }
        if (view.clientTriggerOrderId().isBlank()) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "clientTriggerOrderId is required");
        }
        // 当前运行时的规范币对，登记成功后绑定到条件单并固定其当时默认费率。
        CoreInstrument instrument = runtime.instrument(view.instrumentId());
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "trigger order instrument does not exist");
        }
        if (instrumentSettled) {
            throw new CoreStateRejectedException("INSTRUMENT_SETTLED", "instrument is already settled");
        }
        if (runtime.triggerOrder(view.triggerOrderId()) != null) {
            throw new CoreStateRejectedException("DUPLICATE_TRIGGER_ORDER_ID", "trigger order already exists");
        }
        if (runtime.hasTriggerClient(userId, view.clientTriggerOrderId())) {
            throw new CoreStateRejectedException("DUPLICATE_CLIENT_TRIGGER_ORDER_ID",
                    "clientTriggerOrderId already exists");
        }
        validatePlacement(runtime, userId, symbolId, positionKey, view);
        return CoreTriggerOrderState.from(view, instrument)
                .withExecutionSnapshot(instrument.makerFeeRatePpm(), instrument.takerFeeRatePpm());
    }

    /**
     * 核对用户归属，已不开放的条件单返回 false；仍开放则保留已有触发信息并转成 CANCELED，推进记录及命令版本。
     * @param runtime 提供原用户、持仓、条件单及版本写入入口的所属运行时
     * @param userId 条件单共同所属的正数用户编号
     * @param triggerOrderId 要读取或推进的正数条件单编号
     */
    public static boolean cancel(TradingAccountRuntime runtime, long userId, long triggerOrderId) {
        requireInput(runtime, userId, triggerOrderId);
        // 按编号读取的原条件单，只有满足本入口的状态条件才继续转换。
        CoreTriggerOrderState current = require(runtime, triggerOrderId);
        if (current.userId() != userId) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_OWNER_MISMATCH", "trigger order owner mismatch");
        }
        if (!current.status().open()) return false;
        update(runtime, current, CoreTriggerOrderStatus.CANCELED, 0, current.triggerSequence(),
                current.triggeredPriceTicks(), current.rejectReason(), current.updatedAtEpochMillis());
        return true;
    }

    /**
     * 只取消仍为 PENDING 的条件单并写回后状态，命令级版本发布由原提交事件负责，本辅助入口不重复递增命令版本。
     * @param runtime 提供原用户、持仓、条件单及版本写入入口的所属运行时
     * @param triggerId 提交阶段准备取消的条件单编号
     */
    public static boolean cancelPendingForCommit(TradingAccountRuntime runtime, long triggerId) {
        // 按编号读取的原条件单，只有满足本入口的状态条件才继续转换。
        CoreTriggerOrderState current = require(runtime, triggerId);
        if (current.status() != CoreTriggerOrderStatus.PENDING) return false;
        runtime.putTriggerOrder(preparePendingCancellation(current));
        return true;
    }

    /**
     * 要求当前仍待触发，构造记录版本加一的取消后状态；只返回值，不自行写账户。
     * @param current 当前已有条件单记录，准备转换时要求符合指定起始状态
     */
    public static CoreTriggerOrderState preparePendingCancellation(CoreTriggerOrderState current) {
        if (current == null || current.status() != CoreTriggerOrderStatus.PENDING)
            throw new IllegalStateException("closing trigger must be pending");
        return transition(current, CoreTriggerOrderStatus.CANCELED, 0, current.triggerSequence(),
                current.triggeredPriceTicks(), current.rejectReason(), current.updatedAtEpochMillis());
    }

    /**
     * 仅允许 PENDING 转为 TRIGGERING，记录本轮触发序号和价格；价格鲜度与条件成立由上游检查，本方法不重新扫描市场。
     * @param runtime 提供原用户、持仓、条件单及版本写入入口的所属运行时
     * @param triggerOrderId 要读取或推进的正数条件单编号
     * @param triggerSequence 本轮触发或价格推进的序号，由原上游流程决定
     * @param triggeredPriceTicks 触发条件成立时固定的价格刻度
     * @param triggeredAtEpochMillis 本次认领采用的业务时间，写入本轮状态更新时间
     */
    public static boolean claim(TradingAccountRuntime runtime, long triggerOrderId, long triggerSequence,
                         long triggeredPriceTicks, long triggeredAtEpochMillis) {
        // 按编号读取的原条件单，只有满足本入口的状态条件才继续转换。
        CoreTriggerOrderState current = require(runtime, triggerOrderId);
        if (current.status() != CoreTriggerOrderStatus.PENDING) return false;
        update(runtime, current, CoreTriggerOrderStatus.TRIGGERING, 0, triggerSequence,
                triggeredPriceTicks, current.rejectReason(), triggeredAtEpochMillis);
        return true;
    }

    /**
     * 仅允许已认领的 TRIGGERING 记录按结果转为 TRIGGERED 或 TRIGGER_FAILED，写子单编号及原因；这里不执行子订单撮合或资金结算。
     * @param runtime 提供原用户、持仓、条件单及版本写入入口的所属运行时
     * @param triggerOrderId 要读取或推进的正数条件单编号
     * @param success 上游执行结果是否成功，决定触发成功或失败状态
     * @param placedOrderId 已经生成的子订单编号，成功准备入口要求为正
     * @param rejectReason 触发执行的拒绝原因或需要保留的已有原因
     * @param completedAtEpochMillis 本次触发执行完成的业务时间
     */
    public static boolean complete(TradingAccountRuntime runtime, long triggerOrderId, boolean success,
                            long placedOrderId, String rejectReason, long completedAtEpochMillis) {
        // 按编号读取的原条件单，只有满足本入口的状态条件才继续转换。
        CoreTriggerOrderState current = require(runtime, triggerOrderId);
        if (current.status() != CoreTriggerOrderStatus.TRIGGERING) return false;
        update(runtime, current, success ? CoreTriggerOrderStatus.TRIGGERED
                        : CoreTriggerOrderStatus.TRIGGER_FAILED, placedOrderId, current.triggerSequence(),
                current.triggeredPriceTicks(), rejectReason, completedAtEpochMillis);
        return true;
    }

    /**
     * 只更新仍开放的移动止损，采用调用方算出的高低价格水位和激活时间，保留原执行信息并递增版本，更新时间不早于原值。
     * @param runtime 提供原用户、持仓、条件单及版本写入入口的所属运行时
     * @param triggerOrderId 要读取或推进的正数条件单编号
     * @param highestPriceTicks 上游计算的移动止损最高价格水位
     * @param lowestPriceTicks 上游计算的移动止损最低价格水位
     * @param activatedAtEpochMillis 移动止损激活的业务时间，用于更新记录时间边界
     */
    public static boolean updateTrailing(TradingAccountRuntime runtime, long triggerOrderId,
                                  long highestPriceTicks, long lowestPriceTicks,
                                  long activatedAtEpochMillis) {
        // 按编号读取的原条件单，只有满足本入口的状态条件才继续转换。
        CoreTriggerOrderState current = require(runtime, triggerOrderId);
        if (!current.status().open() || current.triggerType() != CoreTriggerOrderType.TRAILING_STOP) return false;
        runtime.putTriggerOrder(new CoreTriggerOrderState(current.triggerOrderId(), current.productLine(),
                current.userId(), current.clientTriggerOrderId(), current.ocoGroupId(), current.instrumentId(),
                current.instrument(), current.side(),
                current.triggerType(), current.triggerCondition(), current.triggerPriceTicks(),
                current.activationPriceTicks(), current.callbackRatePpm(), highestPriceTicks, lowestPriceTicks,
                activatedAtEpochMillis, current.orderType(), current.timeInForce(), current.priceTicks(),
                current.quantitySteps(), current.marginMode(), current.positionSide(), current.status(),
                current.placedOrderId(), current.triggerSequence(), current.triggeredPriceTicks(), current.rejectReason(),
                current.expiresAtEpochMillis(), current.triggeredAtEpochMillis(),
                current.createdAtEpochMillis(), Math.max(current.updatedAtEpochMillis(), activatedAtEpochMillis),
                Math.incrementExact(current.revision()), current.makerFeeRatePpm(),
                current.takerFeeRatePpm(), current.priceSource()));
        runtime.incrementCommandRevision();
        return true;
    }

    /**
     * 只有待触发、设置了到期时间且已到期的记录才转为 EXPIRED，其他情况返回 false。
     * @param runtime 提供原用户、持仓、条件单及版本写入入口的所属运行时
     * @param triggerOrderId 要读取或推进的正数条件单编号
     * @param expiredAtEpochMillis 本次检查到期采用的业务时间
     */
    public static boolean expire(TradingAccountRuntime runtime, long triggerOrderId, long expiredAtEpochMillis) {
        // 按编号读取的原条件单，只有满足本入口的状态条件才继续转换。
        CoreTriggerOrderState current = require(runtime, triggerOrderId);
        if (current.status() != CoreTriggerOrderStatus.PENDING || current.expiresAtEpochMillis() == 0
                || current.expiresAtEpochMillis() > expiredAtEpochMillis) return false;
        update(runtime, current, CoreTriggerOrderStatus.EXPIRED, 0, current.triggerSequence(),
                current.triggeredPriceTicks(), current.rejectReason(), expiredAtEpochMillis);
        return true;
    }

    /**
     * 仅把更新时间不晚于给定界限的 TRIGGERING 记录恢复为 PENDING，清除子单关联及触发序号价格，保留原拒绝原因并更新重试时间。
     * @param runtime 提供原用户、持仓、条件单及版本写入入口的所属运行时
     * @param triggerOrderId 要读取或推进的正数条件单编号
     * @param staleBeforeEpochMillis 只允许重试更新时间不晚于此值的已认领记录
     * @param retryAtEpochMillis 写入重试后状态的业务更新时间
     */
    public static boolean retry(TradingAccountRuntime runtime, long triggerOrderId,
                         long staleBeforeEpochMillis, long retryAtEpochMillis) {
        // 按编号读取的原条件单，只有满足本入口的状态条件才继续转换。
        CoreTriggerOrderState current = require(runtime, triggerOrderId);
        if (current.status() != CoreTriggerOrderStatus.TRIGGERING
                || current.updatedAtEpochMillis() > staleBeforeEpochMillis) return false;
        update(runtime, current, CoreTriggerOrderStatus.PENDING, 0, 0, 0,
                current.rejectReason(), retryAtEpochMillis);
        return true;
    }

    /**
     * 现货不需要持仓身份而返回 0；衍生品校验永久币对文本后取得或准备用户持仓腿身份键。
     * @param identities 当前产品身份表，准备币对和持仓腿身份使用
     * @param productLine 当前条件单所属产品线，决定是否使用持仓身份
     * @param userId 条件单共同所属的正数用户编号
     * @param view 需要登记的新条件单视图，携带意图、触发条件和执行参数
     */
    public static long positionKey(RuntimeIdentityRegistry identities, ProductLine productLine,
                            long userId, CoreTriggerOrderStateView view) {
        if (!productLine.isDerivative()) return 0;
        return identities.positionKey(userId, OrderReservation.requireInstrumentId(view.instrumentId()),
                view.positionSide());
    }

    /**
     * 要求正在触发且子订单编号为正，构造触发成功后状态并清拒绝原因，实际写回由调用方原提交边界负责。
     * @param current 当前已有条件单记录，准备转换时要求符合指定起始状态
     * @param placedOrderId 已经生成的子订单编号，成功准备入口要求为正
     * @param updatedAt 本次后状态的业务更新时间
     */
    public static CoreTriggerOrderState prepareMatchedCompletion(
            CoreTriggerOrderState current, long placedOrderId, long updatedAt) {
        if (current == null || current.status() != CoreTriggerOrderStatus.TRIGGERING || placedOrderId <= 0)
            throw new IllegalStateException("invalid matched trigger completion");
        return transition(current, CoreTriggerOrderStatus.TRIGGERED, placedOrderId,
                current.triggerSequence(), current.triggeredPriceTicks(), "", updatedAt);
    }

    /**
     * 要求正在触发，构造带拒绝原因且无子单编号的失败后状态，不直接写账户。
     * @param current 当前已有条件单记录，准备转换时要求符合指定起始状态
     * @param reason 需要写入触发失败后状态的拒绝原因
     * @param updatedAt 本次后状态的业务更新时间
     */
    public static CoreTriggerOrderState prepareRejectedCompletion(
            CoreTriggerOrderState current, String reason, long updatedAt) {
        if (current == null || current.status() != CoreTriggerOrderStatus.TRIGGERING)
            throw new IllegalStateException("invalid rejected trigger completion");
        return transition(current, CoreTriggerOrderStatus.TRIGGER_FAILED, 0,
                current.triggerSequence(), current.triggeredPriceTicks(), reason, updatedAt);
    }

    /**
     * 先要求用户存在；衍生品还须持仓模式和保证金模式一致、有非零持仓、方向确实平仓，并检查已挂只减仓量加本次预计条件单占量不超持仓。现货不做衍生品持仓检查。
     * @param runtime 提供原用户、持仓、条件单及版本写入入口的所属运行时
     * @param userId 条件单共同所属的正数用户编号
     * @param symbolId 已经准备的币对运行时稠密编号
     * @param positionKey 衍生品对应的用户持仓腿身份键，现货为 0
     * @param view 需要登记的新条件单视图，携带意图、触发条件和执行参数
     */
    private static void validatePlacement(TradingAccountRuntime runtime, long userId, int symbolId,
                                          long positionKey, CoreTriggerOrderStateView view) {
        // 当前用户元数据，提供单向或双向持仓模式。
        UserRuntime user = runtime.user(userId);
        if (user == null) throw new CoreStateRejectedException("USER_NOT_FOUND", "user does not exist");
        if (!runtime.productLine().isDerivative()) return;
        if (user.positionMode() == CorePositionMode.ONE_WAY && view.positionSide().hedgeSide()
                || user.positionMode() == CorePositionMode.HEDGE && !view.positionSide().hedgeSide()) {
            throw new CoreStateRejectedException("POSITION_MODE_MISMATCH",
                    "trigger position side does not match user position mode");
        }
        // 衍生品当前真实持仓，必须存在、非零且保证金模式与条件单一致。
        PositionRuntime position = runtime.position(positionKey);
        if (position == null || position.signedQuantitySteps() == 0) {
            throw new CoreStateRejectedException("TRIGGER_POSITION_REQUIRED",
                    "trigger order requires an open position");
        }
        if (position.marginMode() != view.marginMode()) {
            throw new CoreStateRejectedException("POSITION_MARGIN_ADJUSTMENT_INVALID",
                    "trigger margin mode does not match position");
        }
        // 正持仓需要卖出减少，负持仓需要买入减少的平仓方向。
        CoreOrderSide closeSide = position.signedQuantitySteps() > 0 ? CoreOrderSide.SELL : CoreOrderSide.BUY;
        if (view.side() != closeSide) {
            throw new CoreStateRejectedException("TRIGGER_SIDE_NOT_REDUCING",
                    "trigger order side must reduce the current position");
        }
        // 该用户、币对、持仓腿、方向和保证金模式下已挂只减仓数量。
        long openReduceOnly = runtime.openReduceOnlyQuantity(
                userId, symbolId, view.positionSide(), closeSide, view.marginMode());
        // 原运行时按条件单及 OCO 规则算出的预计条件平仓占量，与已挂只减仓量合计核对。
        long projectedTriggerCapacity = runtime.projectedTriggerCapacity(userId, view);
        if (Math.addExact(openReduceOnly, projectedTriggerCapacity)
                > Math.absExact(position.signedQuantitySteps())) {
            throw new CoreStateRejectedException("TRIGGER_CLOSE_CAPACITY_EXCEEDED",
                    "trigger order quantity exceeds available position");
        }
    }

    /**
     * 先检查命令版本容量，再用统一转换生成记录后状态、写入并递增命令版本。
     * @param runtime 提供原用户、持仓、条件单及版本写入入口的所属运行时
     * @param current 当前已有条件单记录，准备转换时要求符合指定起始状态
     * @param status 转换后采用的生命周期状态
     * @param placedOrderId 已经生成的子订单编号，成功准备入口要求为正
     * @param triggerSequence 本轮触发或价格推进的序号，由原上游流程决定
     * @param triggeredPriceTicks 触发条件成立时固定的价格刻度
     * @param rejectReason 触发执行的拒绝原因或需要保留的已有原因
     * @param updatedAt 本次后状态的业务更新时间
     */
    private static void update(TradingAccountRuntime runtime, CoreTriggerOrderState current,
                               CoreTriggerOrderStatus status, long placedOrderId, long triggerSequence,
                               long triggeredPriceTicks, String rejectReason, long updatedAt) {
        runtime.requireRevisionCapacity();
        runtime.putTriggerOrder(transition(current, status, placedOrderId, triggerSequence,
                triggeredPriceTicks, rejectReason, updatedAt));
        runtime.incrementCommandRevision();
    }

    /**
     * 复制原条件单意图、追踪信息和费率，只替换指定生命周期字段并将记录版本加一；TRIGGERED 或 TRIGGER_FAILED 才把 updatedAt 写入 triggeredAt 字段，其他状态保留旧值。
     * @param current 当前已有条件单记录，准备转换时要求符合指定起始状态
     * @param status 转换后采用的生命周期状态
     * @param placedOrderId 已经生成的子订单编号，成功准备入口要求为正
     * @param triggerSequence 本轮触发或价格推进的序号，由原上游流程决定
     * @param triggeredPriceTicks 触发条件成立时固定的价格刻度
     * @param rejectReason 触发执行的拒绝原因或需要保留的已有原因
     * @param updatedAt 本次后状态的业务更新时间
     */
    private static CoreTriggerOrderState transition(CoreTriggerOrderState current,
                                                    CoreTriggerOrderStatus status, long placedOrderId,
                                                    long triggerSequence, long triggeredPriceTicks,
                                                    String rejectReason, long updatedAt) {
        return new CoreTriggerOrderState(current.triggerOrderId(), current.productLine(), current.userId(),
                current.clientTriggerOrderId(), current.ocoGroupId(), current.instrumentId(), current.instrument(), current.side(),
                current.triggerType(), current.triggerCondition(), current.triggerPriceTicks(),
                current.activationPriceTicks(), current.callbackRatePpm(), current.highestPriceTicks(),
                current.lowestPriceTicks(), current.activatedAtEpochMillis(), current.orderType(),
                current.timeInForce(), current.priceTicks(), current.quantitySteps(), current.marginMode(),
                current.positionSide(), status, placedOrderId, triggerSequence, triggeredPriceTicks, rejectReason,
                current.expiresAtEpochMillis(),
                status == CoreTriggerOrderStatus.TRIGGERED || status == CoreTriggerOrderStatus.TRIGGER_FAILED
                        ? updatedAt : current.triggeredAtEpochMillis(),
                current.createdAtEpochMillis(), updatedAt, Math.incrementExact(current.revision()),
                current.makerFeeRatePpm(), current.takerFeeRatePpm(), current.priceSource());
    }

    /**
     * 校验运行时和正条件单编号，遵守原所有权并读取记录，不存在返回明确 TRIGGER_ORDER_NOT_FOUND 拒绝。
     * @param runtime 提供原用户、持仓、条件单及版本写入入口的所属运行时
     * @param triggerOrderId 要读取或推进的正数条件单编号
     */
    private static CoreTriggerOrderState require(TradingAccountRuntime runtime, long triggerOrderId) {
        if (runtime == null || triggerOrderId <= 0) throw new IllegalArgumentException("invalid runtime trigger order");
        runtime.assertOwner();
        // 按编号读取的原条件单，只有满足本入口的状态条件才继续转换。
        CoreTriggerOrderState current = runtime.triggerOrder(triggerOrderId);
        if (current == null) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
        }
        return current;
    }

    /**
     * 仅核对运行时、用户和条件单编号的基本有效性，实体读取与所有权由后续 require 检查。
     * @param runtime 提供原用户、持仓、条件单及版本写入入口的所属运行时
     * @param userId 条件单共同所属的正数用户编号
     * @param triggerOrderId 要读取或推进的正数条件单编号
     */
    private static void requireInput(TradingAccountRuntime runtime, long userId, long triggerOrderId) {
        if (runtime == null || userId <= 0 || triggerOrderId <= 0) {
            throw new IllegalArgumentException("invalid runtime trigger order");
        }
    }
}
