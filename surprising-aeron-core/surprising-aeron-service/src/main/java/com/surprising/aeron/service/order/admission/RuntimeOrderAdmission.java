package com.surprising.aeron.service.order.admission;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.account.UserRuntime;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.fee.FeePolicyBook;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPriceRuntime;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.product.ProductTradingRulesRegistry;
import com.surprising.aeron.support.math.*;
import com.surprising.aeron.support.math.CoreArithmetic;
import com.surprising.aeron.support.math.CoreContractMath;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 交易步骤 2 的账户准入检查与预留金额计算。核对客户订单身份、持仓模式、只减仓容量、杠杆和风险限额，再按产品规则返回应预留金额；不在这里创建订单、冻结可用余额或应用成交。 构造约定：该类只提供静态准入计算，不创建独立状态实例。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeOrderAdmission {

    /** 百万分比的分母，OI 比例限额和杠杆相关计算共用此比例单位。 */
    private static final long PPM = 1_000_000L;

    /**
     * 普通订单准入的便捷入口，不排除已有订单，继续取得身份并计算所需预留。
     * @param runtime 所属交易运行时，提供 Lane 权限、规范币对及账本状态入口
     * @param identities 当前产品运行时身份表，币对、客户标识和持仓键从这里准备
     * @param userId 下单用户编号，账户与挂单都必须属于这个用户
     * @param order 已经完成价格、费率和冻结资产决策的订单输入
     * @param openInterestSteps 账本或调用方固定的该币对 OI 数量步数，用于比例限额检查
     * @param activeOrders 当前用户、币对及方向的准入挂单汇总视图，可包含本批已获准订单影响
     */
    public static long requiredReservation(
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, long userId,
            ResolvedPlaceOrder order, long openInterestSteps, AdmissionOrderIndex activeOrders) {
        return requiredReservation(runtime, identities, userId, order, openInterestSteps, activeOrders, 0);
    }

    /**
     * 定位用户所属 Lane，准备客户、币对、持仓及账本身份；改单时读取被替换订单的币对，随后执行统一校验和金额计算。
     * @param runtime 所属交易运行时，提供 Lane 权限、规范币对及账本状态入口
     * @param identities 当前产品运行时身份表，币对、客户标识和持仓键从这里准备
     * @param userId 下单用户编号，账户与挂单都必须属于这个用户
     * @param order 已经完成价格、费率和冻结资产决策的订单输入
     * @param openInterestSteps 账本或调用方固定的该币对 OI 数量步数，用于比例限额检查
     * @param activeOrders 当前用户、币对及方向的准入挂单汇总视图，可包含本批已获准订单影响
     * @param excludedOrderId 改单时排除的原订单编号，普通下单使用 0
     */
    public static long requiredReservation(
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, long userId,
            ResolvedPlaceOrder order, long openInterestSteps, AdmissionOrderIndex activeOrders,
            long excludedOrderId) {
        if (runtime == null || identities == null || order == null || activeOrders == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime order admission input");
        }
        // 当前用户实际所属的账户 Lane，所有账户和订单检查从这里读取。
        AccountLaneState lane = runtime.accountLaneForUser(userId);
        // 为本次准入准备的客户、币对、持仓身份和借用的账本事实。
        AdmissionIdentity identity = admissionIdentity(runtime, identities, userId, order);
        // 被替换订单实际所属币对，未排除任何订单时保持 null。
        String excludedSymbol = null;
        if (excludedOrderId != 0) {
            // 调用方要求排除的原单，存在时取其币对身份再做统一校验。
            OrderRuntime excluded = lane.order(excludedOrderId);
            if (excluded != null) excludedSymbol = identities.instrumentId(excluded.symbolId());
        }
        return requiredReservation(runtime, lane, userId, order, openInterestSteps, activeOrders,
                excludedOrderId, identity.clientKey(), identity.positionKey(),
                identity.lifecycleSettled(), identity.fundingInProgress(), excludedSymbol);
    }

    /**
     * 取得或登记币对稠密身份，查找已有客户端和持仓身份，并借用当前账本结算状态，供后续账户准入使用。
     * @param runtime 所属交易运行时，提供 Lane 权限、规范币对及账本状态入口
     * @param identities 当前产品运行时身份表，币对、客户标识和持仓键从这里准备
     * @param userId 下单用户编号，账户与挂单都必须属于这个用户
     * @param order 已经完成价格、费率和冻结资产决策的订单输入
     */
    public static AdmissionIdentity admissionIdentity(
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
            long userId, ResolvedPlaceOrder order) {
        if (runtime == null || identities == null || order == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime order admission identity");
        }
        // 从永久币对编号取得的运行时稠密身份，用于账本及持仓路由。
        int symbolId = identities.symbolId(order.instrumentId());
        return new AdmissionIdentity(identities.findClientKeyValue(userId, order.clientOrderId()),
                symbolId,
                identities.findPositionKeyValue(userId, order.instrument(), order.positionSide()),
                runtime.treasury().settlementStatus(symbolId));
    }

    /**
     * 使用已准备好的身份事实在显式 Lane 中检查普通订单，避免账户消费者重新组装这些输入。
     * @param runtime 所属交易运行时，提供 Lane 权限、规范币对及账本状态入口
     * @param lane 明确属于当前用户的账户 Lane，调用线程必须已持有合法访问权
     * @param userId 下单用户编号，账户与挂单都必须属于这个用户
     * @param order 已经完成价格、费率和冻结资产决策的订单输入
     * @param openInterestSteps 账本或调用方固定的该币对 OI 数量步数，用于比例限额检查
     * @param activeOrders 当前用户、币对及方向的准入挂单汇总视图，可包含本批已获准订单影响
     * @param identity 已经准备好的客户、币对、持仓身份和账本结算事实
     */
    public static long requiredReservationPrepared(
            TradingAccountRuntime runtime, AccountLaneState lane, long userId, ResolvedPlaceOrder order,
            long openInterestSteps, AdmissionOrderIndex activeOrders, AdmissionIdentity identity) {
        if (runtime == null || order == null || activeOrders == null || identity == null || userId <= 0) {
            throw new IllegalArgumentException("invalid prepared runtime order admission input");
        }
        return requiredReservation(runtime, lane, userId, order, openInterestSteps, activeOrders,
                0, identity.clientKey(), identity.positionKey(),
                identity.lifecycleSettled(), identity.fundingInProgress(), null);
    }

    /**
     * 直接使用已准备的 primitive 身份值和生命周期标记执行普通订单准入，不额外构造身份记录。
     * @param runtime 所属交易运行时，提供 Lane 权限、规范币对及账本状态入口
     * @param lane 明确属于当前用户的账户 Lane，调用线程必须已持有合法访问权
     * @param userId 下单用户编号，账户与挂单都必须属于这个用户
     * @param order 已经完成价格、费率和冻结资产决策的订单输入
     * @param openInterestSteps 账本或调用方固定的该币对 OI 数量步数，用于比例限额检查
     * @param activeOrders 当前用户、币对及方向的准入挂单汇总视图，可包含本批已获准订单影响
     * @param clientKey 当前客户端订单标识的整数身份，0 表示没有已有身份
     * @param positionKey 当前用户、币对和持仓腿的已有持仓键，0 表示没有持仓身份
     * @param lifecycleSettled 当前币对是否已经完成到期结算，已完成就拒绝新准入
     * @param fundingInProgress 当前币对是否仍有资金费持仓截面处理中任务，需要拒绝冲突准入
     */
    public static long requiredReservationPrepared(
            TradingAccountRuntime runtime, AccountLaneState lane, long userId, ResolvedPlaceOrder order,
            long openInterestSteps, AdmissionOrderIndex activeOrders,
            long clientKey, long positionKey,
            boolean lifecycleSettled, boolean fundingInProgress) {
        if (runtime == null || order == null || activeOrders == null || userId <= 0) {
            throw new IllegalArgumentException("invalid primitive prepared runtime order admission input");
        }
        return requiredReservation(runtime, lane, userId, order, openInterestSteps, activeOrders,
                0, clientKey, positionKey, lifecycleSettled, fundingInProgress, null);
    }

    /**
     * 取得被替换原单并限定相同币对，统一校验时排除原单已有数量和身份占用，再计算新单所需预留。
     * @param runtime 所属交易运行时，提供 Lane 权限、规范币对及账本状态入口
     * @param lane 明确属于当前用户的账户 Lane，调用线程必须已持有合法访问权
     * @param userId 下单用户编号，账户与挂单都必须属于这个用户
     * @param order 已经完成价格、费率和冻结资产决策的订单输入
     * @param openInterestSteps 账本或调用方固定的该币对 OI 数量步数，用于比例限额检查
     * @param activeOrders 当前用户、币对及方向的准入挂单汇总视图，可包含本批已获准订单影响
     * @param excludedOrderId 改单时排除的原订单编号，普通下单使用 0
     * @param clientKey 当前客户端订单标识的整数身份，0 表示没有已有身份
     * @param positionKey 当前用户、币对和持仓腿的已有持仓键，0 表示没有持仓身份
     * @param lifecycleSettled 当前币对是否已经完成到期结算，已完成就拒绝新准入
     * @param fundingInProgress 当前币对是否仍有资金费持仓截面处理中任务，需要拒绝冲突准入
     */
    static long requiredReplacementReservationPrepared(
            TradingAccountRuntime runtime, AccountLaneState lane, long userId, ResolvedPlaceOrder order,
            long openInterestSteps, AdmissionOrderIndex activeOrders, long excludedOrderId,
            long clientKey, long positionKey, boolean lifecycleSettled, boolean fundingInProgress) {
        // 改单要替换的原订单，必须属于当前 Lane。
        OrderRuntime original = lane.order(excludedOrderId);
        // 只有原单和新单稠密币对一致时保留新单的规范编号，否则交统一检查拒绝。
        String originalSymbol = original != null && original.symbolId() == order.symbolId()
                ? order.instrumentId() : null;
        return requiredReservation(runtime, lane, userId, order, openInterestSteps, activeOrders,
                excludedOrderId, clientKey, positionKey,
                lifecycleSettled, fundingInProgress, originalSymbol);
    }

    /**
     * 把运行态准入参数交给统一检查入口，币对从当前所属 Lane 的运行时配置读取。
     * @param runtime 所属交易运行时，提供 Lane 权限、规范币对及账本状态入口
     * @param lane 明确属于当前用户的账户 Lane，调用线程必须已持有合法访问权
     * @param userId 下单用户编号，账户与挂单都必须属于这个用户
     * @param order 已经完成价格、费率和冻结资产决策的订单输入
     * @param openInterestSteps 账本或调用方固定的该币对 OI 数量步数，用于比例限额检查
     * @param activeOrders 当前用户、币对及方向的准入挂单汇总视图，可包含本批已获准订单影响
     * @param excludedOrderId 改单时排除的原订单编号，普通下单使用 0
     * @param clientKey 当前客户端订单标识的整数身份，0 表示没有已有身份
     * @param positionKey 当前用户、币对和持仓腿的已有持仓键，0 表示没有持仓身份
     * @param lifecycleSettled 当前币对是否已经完成到期结算，已完成就拒绝新准入
     * @param fundingInProgress 当前币对是否仍有资金费持仓截面处理中任务，需要拒绝冲突准入
     * @param excludedSymbol 被替换原单的币对编号文本，用于核对原单归属和币对一致性
     */
    private static long requiredReservation(
            TradingAccountRuntime runtime, AccountLaneState lane, long userId, ResolvedPlaceOrder order,
            long openInterestSteps, AdmissionOrderIndex activeOrders, long excludedOrderId,
            long clientKey, long positionKey,
            boolean lifecycleSettled, boolean fundingInProgress, String excludedSymbol) {
        return requiredReservation(runtime, lane, userId, order, openInterestSteps, activeOrders, excludedOrderId,
                clientKey, positionKey, lifecycleSettled, fundingInProgress, excludedSymbol, null);
    }

    /**
     * 预检只读取显式所属 Lane 和 Owner 捕获的币对配置；使用现有身份及挂单索引计算金额，不分配新身份、创建订单或冻结资金。
     * @param runtime 所属交易运行时，提供 Lane 权限、规范币对及账本状态入口
     * @param lane 明确属于当前用户的账户 Lane，调用线程必须已持有合法访问权
     * @param userId 下单用户编号，账户与挂单都必须属于这个用户
     * @param order 已经完成价格、费率和冻结资产决策的订单输入
     * @param openInterestSteps 账本或调用方固定的该币对 OI 数量步数，用于比例限额检查
     * @param identity 已经准备好的客户、币对、持仓身份和账本结算事实
     */
    public static long requiredReservationForQuery(TradingAccountRuntime runtime, AccountLaneState lane, long userId,
            ResolvedPlaceOrder order, long openInterestSteps, AdmissionIdentity identity) {
        if (lane == null || runtime.topology().accountLaneId(userId) != lane.laneId()
                || order == null || identity == null)
            throw new IllegalStateException("preflight query requires its owning Lane scope");
        return requiredReservation(runtime, lane, userId, order, openInterestSteps,
                lane.admissionOrderIndex(identity.symbolId()), 0, identity.clientKey(),
                identity.positionKey(), identity.lifecycleSettled(), identity.fundingInProgress(), null, order.instrument());
    }

    /**
     * 统一核对 Lane 权限、算法子单、订单与客户端身份、币对和生命周期，再检查持仓模式、只减仓容量、风险限额及杠杆，最后由所属产品规则计算预留。
     * @param runtime 所属交易运行时，提供 Lane 权限、规范币对及账本状态入口
     * @param lane 明确属于当前用户的账户 Lane，调用线程必须已持有合法访问权
     * @param userId 下单用户编号，账户与挂单都必须属于这个用户
     * @param order 已经完成价格、费率和冻结资产决策的订单输入
     * @param openInterestSteps 账本或调用方固定的该币对 OI 数量步数，用于比例限额检查
     * @param activeOrders 当前用户、币对及方向的准入挂单汇总视图，可包含本批已获准订单影响
     * @param excludedOrderId 改单时排除的原订单编号，普通下单使用 0
     * @param clientKey 当前客户端订单标识的整数身份，0 表示没有已有身份
     * @param positionKey 当前用户、币对和持仓腿的已有持仓键，0 表示没有持仓身份
     * @param lifecycleSettled 当前币对是否已经完成到期结算，已完成就拒绝新准入
     * @param fundingInProgress 当前币对是否仍有资金费持仓截面处理中任务，需要拒绝冲突准入
     * @param excludedSymbol 被替换原单的币对编号文本，用于核对原单归属和币对一致性
     * @param queryInstrument 只读预检捕获的配置副本，null 表示读取当前运行态规范币对
     */
    private static long requiredReservation(
            TradingAccountRuntime runtime, AccountLaneState lane, long userId, ResolvedPlaceOrder order,
            long openInterestSteps, AdmissionOrderIndex activeOrders, long excludedOrderId,
            long clientKey, long positionKey,
            boolean lifecycleSettled, boolean fundingInProgress, String excludedSymbol, CoreInstrument queryInstrument) {
        runtime.requireLaneAccess(lane, userId);
        lane.requireAlgoChildAdmission(userId, order);
        if (lane.order(order.orderId()) != null && order.orderId() != excludedOrderId) {
            throw rejected("DUPLICATE_ORDER_ID", "orderId already exists");
        }
        // 当前用户的客户端身份到普通订单编号映射，可能尚未建立。
        var clientOrders = lane.userClientOrders(userId);
        // 已有客户端标识占用的订单编号；没有映射或身份为 0 时视为未占用。
        long clientOrderId = clientKey == 0 || clientOrders == null ? 0 : clientOrders.get(clientKey);
        if (clientOrderId != 0 && clientOrderId != excludedOrderId) {
            throw rejected("DUPLICATE_CLIENT_ORDER_ID", "clientOrderId already exists");
        }
        // 预检显式传入的捕获配置，或当前 Lane 运行态的规范币对，必须与订单绑定对象一致。
        CoreInstrument instrument = queryInstrument == null ? runtime.instrument(lane, order.instrumentId()) : queryInstrument;
        if (instrument == null || instrument != order.instrument()) {
            throw rejected("INSTRUMENT_ORDER_MISMATCH", "order instrument differs from Runtime");
        }
        if (lifecycleSettled) {
            throw rejected("INSTRUMENT_SETTLED", "instrument is already settled");
        }
        if (fundingInProgress) {
            throw rejected("LIFECYCLE_IN_PROGRESS", "funding position cut is in progress");
        }
        validateReservation(instrument.contractType().productLine().isDerivative(), instrument, order);
        // 当前账户用户元数据，不存在时使用默认单向模式继续校验。
        UserRuntime user = lane.user(userId);
        // 用户实际持仓模式，未创建用户元数据时默认 ONE_WAY。
        CorePositionMode positionMode = user == null ? CorePositionMode.ONE_WAY : user.positionMode();
        // 按已准备持仓键读取的当前持仓，无键或未建仓时为 null。
        PositionRuntime position = positionKey == 0 ? null : lane.position(positionKey);
        // 本次改单排除的原单，必须存在且用户及币对一致。
        OrderRuntime excluded = excludedOrderId == 0 ? null : lane.order(excludedOrderId);
        if (excludedOrderId != 0 && (excluded == null || excluded.userId() != userId
                || !order.instrumentId().equals(excludedSymbol))) {
            throw rejected("ORDER_NOT_FOUND", "excluded replacement order is invalid");
        }
        // 当前订单另一种保证金模式，用它统计未清委托冲突。
        CoreMarginMode conflictingMode = order.marginMode() == CoreMarginMode.CROSS
                ? CoreMarginMode.ISOLATED : CoreMarginMode.CROSS;
        // 当前用户、币对、持仓腿和方向的挂单汇总，供容量及风险检查共用。
        AdmissionSummary admissionSummary = activeOrders.inspect(
                userId, order.instrumentId(), order.positionSide(), order.side(), conflictingMode);
        validatePositionIdentity(
                positionMode, position, order, admissionSummary, conflictingMode, excluded);
        validateReduceOnly(instrument.contractType().productLine().isDerivative(), position, order,
                admissionSummary, userId, excluded);
        // 按账户配置或币对默认得到的本次有效杠杆。
        long leverage = effectiveLeverage(lane, instrument, order, userId);
        validateRiskLimits(instrument, position, order, admissionSummary, userId,
                openInterestSteps, excluded, leverage);
        return ProductTradingRulesRegistry.forProductLine(instrument.contractType().productLine())
                .reservationUnits(instrument, position, order, leverage, admissionSummary.pendingQuantity());
    }

    /**
     * 核对冻结类别和资产符合产品规则：衍生品使用结算资产保证金，现货按买卖方向选择报价或基础资产，并要求实际撮合边界为正。
     * @param derivative 当前产品是否为衍生品，决定冻结与只减仓校验规则
     * @param instrument 订单绑定的币对配置，提供资金公式、资产、风险档位及杠杆限制
     * @param order 已经完成价格、费率和冻结资产决策的订单输入
     */
    private static void validateReservation(
            boolean derivative, CoreInstrument instrument, ResolvedPlaceOrder order) {
        // 决策输入选定的冻结资产，后面按产品和买卖方向核对。
        String asset = order.reservationAsset();
        if (asset == null) {
            throw rejected("INVALID_RESERVATION_ASSET", "reservation asset is required");
        }
        if (derivative) {
            if (order.reservationKind() != ReservationKind.DERIVATIVE_MARGIN) {
                throw rejected("INVALID_RESERVATION_KIND", "derivative order must reserve margin");
            }
            if (!asset.equals(instrument.settleAsset())) {
                throw rejected("INVALID_DERIVATIVE_RESERVATION_ASSET", "invalid derivative reservation asset");
            }
        } else {
            if (order.reservationKind() != ReservationKind.SPOT_ASSET) {
                throw rejected("INVALID_RESERVATION_KIND", "spot order must reserve an asset");
            }
            // 现货买单应冻结报价资产，卖单应冻结基础资产。
            String expected = order.side() == CoreOrderSide.BUY
                    ? instrument.quoteAsset() : instrument.baseAsset();
            if (!asset.equals(expected)) {
                throw rejected("INVALID_SPOT_RESERVATION_ASSET", "invalid spot reservation asset");
            }
        }
        if (order.matchingPriceTicks() <= 0) {
            throw rejected("INVALID_ORDER_PRICE", "matching price must be positive");
        }
    }

    /**
     * 核对单向或双向持仓腿、保证金模式及开平方向；仍有持仓或另一模式挂单时拒绝切换，改单统计时排除原委托。
     * @param mode 用户当前的单向或双向持仓模式
     * @param position 当前订单对应的已有持仓，尚未建仓时为 null
     * @param order 已经完成价格、费率和冻结资产决策的订单输入
     * @param admissionSummary 同向挂单数量、只减仓数量和保证金模式冲突数的本次汇总
     * @param conflictingMode 与新订单相反的保证金模式，用于校验仍有委托时不能切换
     * @param excluded 改单时被排除的原订单，非改单为 null
     */
    private static void validatePositionIdentity(
            CorePositionMode mode, PositionRuntime position, ResolvedPlaceOrder order,
            AdmissionSummary admissionSummary, CoreMarginMode conflictingMode, OrderRuntime excluded) {
        if ((mode == CorePositionMode.ONE_WAY && order.positionSide().hedgeSide())
                || (mode == CorePositionMode.HEDGE && !order.positionSide().hedgeSide())) {
            throw rejected("POSITION_MODE_MISMATCH", "position side differs from user position mode");
        }
        if (order.marginMode() == CoreMarginMode.ISOLATED
                && order.reservationKind() == ReservationKind.SPOT_ASSET) {
            throw rejected("POSITION_MARGIN_ADJUSTMENT_INVALID", "spot order cannot use isolated margin");
        }
        // 已有非零持仓与新订单保证金模式不同，不能在持仓未清时切换。
        boolean positionConflict = position != null && position.signedQuantitySteps() != 0
                && position.marginMode() != order.marginMode();
        // 另一保证金模式的挂单数，改单时减去本次将被替换的旧单。
        int conflictingOrders = admissionSummary.marginModeCount();
        if (excluded != null && excluded.status() == CoreOrderStatus.OPEN
                && excluded.positionSide() == order.positionSide()
                && excluded.marginMode() == conflictingMode) {
            conflictingOrders--;
        }
        if (positionConflict || conflictingOrders > 0) {
            throw rejected("POSITION_MARGIN_ADJUSTMENT_INVALID", "margin mode switch requires an empty position");
        }
        if ((order.positionSide() == CorePositionSide.LONG
                && order.reduceOnly() == (order.side() == CoreOrderSide.BUY))
                || (order.positionSide() == CorePositionSide.SHORT
                && order.reduceOnly() == (order.side() == CoreOrderSide.SELL))) {
            throw rejected("POSITION_MODE_MISMATCH", "hedge side and direction are inconsistent");
        }
    }

    /**
     * 只减仓仅支持衍生品且必须逆向减少已有非零持仓；扣除其他有效只减仓挂单已占数量，检查新订单不超剩余平仓容量。
     * @param derivative 当前产品是否为衍生品，决定冻结与只减仓校验规则
     * @param position 当前订单对应的已有持仓，尚未建仓时为 null
     * @param order 已经完成价格、费率和冻结资产决策的订单输入
     * @param admissionSummary 同向挂单数量、只减仓数量和保证金模式冲突数的本次汇总
     * @param userId 下单用户编号，账户与挂单都必须属于这个用户
     * @param excluded 改单时被排除的原订单，非改单为 null
     */
    private static void validateReduceOnly(
            boolean derivative, PositionRuntime position, ResolvedPlaceOrder order,
            AdmissionSummary admissionSummary, long userId, OrderRuntime excluded) {
        if (!order.reduceOnly()) return;
        if (!derivative) throw rejected("REDUCE_ONLY_UNSUPPORTED", "spot order cannot be reduce-only");
        if (position == null || position.signedQuantitySteps() == 0
                || (position.signedQuantitySteps() > 0) == (order.side() == CoreOrderSide.BUY)) {
            throw rejected("REDUCE_ONLY_REQUIRES_POSITION_STATE", "reduce-only order must close a position");
        }
        // 已有持仓数量的精确绝对值，作为只减仓总容量。
        long positionSteps = Math.absExact(position.signedQuantitySteps());
        // 汇总中已占平仓容量的同向只减仓数量，改单时排除原单剩余量。
        long committedOrders = admissionSummary.reduceOnlyQuantity();
        if (excluded != null && excluded.status() == CoreOrderStatus.OPEN && excluded.reduceOnly()
                && excluded.userId() == userId && excluded.side() == order.side()) {
            committedOrders = Math.subtractExact(committedOrders, excluded.remainingQuantitySteps());
        }
        // 已有只减仓占用量与持仓总量的较小值，保证剩余可减容量不因超额挂单变成负数。
        long committed = Math.min(positionSteps, committedOrders);
        if (order.quantitySteps() > Math.subtractExact(positionSteps, committed)) {
            throw rejected("REDUCE_ONLY_CAPACITY_EXCEEDED", "reduce-only quantity exceeds position capacity");
        }
    }

    /**
     * 非只减仓衍生品按当前持仓、同向挂单和新单预估持仓名义价值，依次检查合约总限额、OI 比例限额、风险档位和非期权杠杆限制。
     * @param instrument 订单绑定的币对配置，提供资金公式、资产、风险档位及杠杆限制
     * @param position 当前订单对应的已有持仓，尚未建仓时为 null
     * @param order 已经完成价格、费率和冻结资产决策的订单输入
     * @param admissionSummary 同向挂单数量、只减仓数量和保证金模式冲突数的本次汇总
     * @param userId 下单用户编号，账户与挂单都必须属于这个用户
     * @param openInterestSteps 账本或调用方固定的该币对 OI 数量步数，用于比例限额检查
     * @param excluded 改单时被排除的原订单，非改单为 null
     * @param leverage 本次准入使用的有效杠杆，单位与币对杠杆百万分比契约一致
     */
    private static void validateRiskLimits(CoreInstrument instrument, PositionRuntime position,
            ResolvedPlaceOrder order, AdmissionSummary admissionSummary, long userId, long openInterestSteps,
            OrderRuntime excluded, long leverage) {
        if (!instrument.contractType().productLine().isDerivative() || order.reduceOnly()) return;
        // 当前持仓带方向的数量步数，没有持仓时为 0。
        long current = position == null ? 0 : position.signedQuantitySteps();
        // 同向非只减仓挂单的待成交数量，改单时先减去原单剩余量。
        long pending = admissionSummary.pendingQuantity();
        if (excluded != null && excluded.status() == CoreOrderStatus.OPEN && !excluded.reduceOnly()
                && excluded.userId() == userId && excluded.positionSide() == order.positionSide()
                && excluded.side() == order.side()) {
            pending = Math.subtractExact(pending, excluded.remainingQuantitySteps());
        }
        // 当前同向待成交量加本次新订单数量的精确和。
        long totalOrders = Math.addExact(pending, order.quantitySteps());
        // 同向全部订单量转换成买正卖负的持仓方向。
        long signedOrders = order.side() == CoreOrderSide.BUY ? totalOrders : Math.negateExact(totalOrders);
        // 当前带方向持仓加全部同向订单后取精确绝对值，作为预估风险数量。
        long projectedSteps = Math.absExact(Math.addExact(current, signedOrders));
        // 期权按指数价衡量风险名义价值，其他衍生品按标记价。
        long riskPriceTicks = instrument.contractType().isOption()
                ? order.indexPriceTicks() : order.markPriceTicks();
        // 按产品公式计算的预估持仓风险名义价值，分别与总限额和风险档位核对。
        long projectedNotional = CoreContractMath.riskNotionalUnits(
                instrument, projectedSteps, riskPriceTicks);
        if (projectedNotional > instrument.maxPositionNotionalUnits()) {
            throw rejected("POSITION_NOTIONAL_LIMIT_EXCEEDED", "projected position exceeds instrument limit");
        }
        // 用户 OI 名义限额，先取固定下限与总限额的较小值，启用比例时再按 OI 计算并截在上下界内。
        long scaledLimit = Math.min(instrument.userOpenInterestLimitFloorUnits(), instrument.maxPositionNotionalUnits());
        if (instrument.usesOpenInterestForUserLimit()) {
            // 账本提供的 OI 数量按同一风险价格换算的名义价值，零 OI 保持为 0。
            long openInterestNotional = openInterestSteps == 0 ? 0
                    : CoreContractMath.riskNotionalUnits(instrument, openInterestSteps, riskPriceTicks);
            scaledLimit = CoreArithmetic.scaledFloorCapped(
                    openInterestNotional, instrument.userOpenInterestLimitRatePpm(), PPM,
                    instrument.userOpenInterestLimitFloorUnits(), instrument.maxPositionNotionalUnits());
        }
        if (projectedNotional > scaledLimit) {
            throw rejected("OPEN_INTEREST_LIMIT_EXCEEDED", "projected position exceeds open-interest limit");
        }
        // 预估名义价值对应的风险档位，提供档位上限、初始保证金率和最大杠杆。
        var bracket = CoreContractMath.riskBracket(instrument, projectedNotional);
        if (!instrument.contractType().isOption() && (leverage > bracket.maxLeveragePpm()
                || CoreContractMath.initialMarginRateFromLeverage(leverage) < bracket.initialMarginRatePpm())) {
            throw rejected("LEVERAGE_EXCEEDS_RISK_BRACKET", "configured leverage exceeds risk bracket");
        }
    }

    /**
     * 从所属 Lane 按用户、币对和保证金模式查配置杠杆，没有配置时使用币对最大杠杆；借用的查找键在 finally 清理。
     * @param lane 明确属于当前用户的账户 Lane，调用线程必须已持有合法访问权
     * @param instrument 订单绑定的币对配置，提供资金公式、资产、风险档位及杠杆限制
     * @param order 已经完成价格、费率和冻结资产决策的订单输入
     * @param userId 下单用户编号，账户与挂单都必须属于这个用户
     */
    private static long effectiveLeverage(
            AccountLaneState lane, CoreInstrument instrument,
            ResolvedPlaceOrder order, long userId) {
        if (!instrument.contractType().productLine().isDerivative() || instrument.contractType().isOption()) return 0;
        if (lane.leverageValuesEmpty()) return instrument.maxLeveragePpm();
        // 所属 Lane 借用的杠杆查找键，本次查询完成或失败都要清理。
        var lookup = lane.leverageLookup();
        lookup.set(userId, instrument.instrumentId(), order.marginMode());
        // 按用户、币对和保证金模式查到的杠杆值，未配置时为 null。
        Long configured;
        try { configured = lane.leverage(lookup); }
        finally { lookup.clear(); }
        return configured == null ? instrument.maxLeveragePpm() : configured;
    }

    /**
     * 用明确业务拒绝码和原因创建准入异常，由上层转成对应命令或预检结果。
     * @param code 用于协议映射的明确准入拒绝码
     * @param message 说明本次准入为何被拒绝的原因
     */
    private static CoreStateRejectedException rejected(String code, String message) {
        return new CoreStateRejectedException(code, message);
    }

    /**
     * 账户准入前已经准备好的身份值和借用的账本结算事实。
     */
    public record AdmissionIdentity(
            long clientKey, int symbolId, long positionKey,
            com.surprising.aeron.service.treasury.TreasuryRuntime.SettlementStatus settlementStatus) {
        public boolean lifecycleSettled() { return settlementStatus.lifecycleSettled(); }
        public boolean fundingInProgress() { return settlementStatus.fundingInProgress(); }
    }

    /**
     * 一次挂单准入检查的可复用汇总结果，只存数量和冲突条数。
     */
    public static final class AdmissionSummary {
        @lombok.experimental.Accessors(fluent = true, chain = false)
        @lombok.Getter
        private long pendingQuantity;
        @lombok.experimental.Accessors(fluent = true, chain = false)
        @lombok.Getter
        private long reduceOnlyQuantity;
        @lombok.experimental.Accessors(fluent = true, chain = false)
        @lombok.Getter
        private int marginModeCount;

        public AdmissionSummary set(long pendingQuantity, long reduceOnlyQuantity, int marginModeCount) {
            this.pendingQuantity = pendingQuantity;
            this.reduceOnlyQuantity = reduceOnlyQuantity;
            this.marginModeCount = marginModeCount;
            return this;
        }
    }

    /**
     * 订单准入需要的挂单汇总读取边界。
     */
    public interface AdmissionOrderIndex {
        AdmissionSummary inspect(long userId, String instrumentId, CorePositionSide positionSide,
                                 CoreOrderSide side, CoreMarginMode conflictingMarginMode);

        default void admitted(long userId, ResolvedPlaceOrder order) {
        }
    }

    // --- 订单决策（Order Decision & Resolution）统一收敛至本类 ---

    public static ResolvedPlaceOrder resolve(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                                             long userId, PlaceOrderCommand intent, long clusterTimestamp) {
        if (runtime == null || identities == null || intent == null || userId <= 0 || clusterTimestamp <= 0) {
            throw new IllegalArgumentException("invalid order decision input");
        }
        runtime.assertOwner();
        CoreInstrument instrument = runtime.instrument(intent.instrumentId());
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        int symbolId = identities.findSymbolIdValue(instrument.instrumentIdValue());
        if (symbolId < 0) throw new IllegalStateException("instrument instrumentId identity is missing");
        return resolveValues(null, instrument, symbolId, runtime.markPrice(symbolId),
                runtime.feePolicyBook(), userId,
                clusterTimestamp, runtime.treasury().lifecycleSettlement(symbolId) != 0, intent);
    }

    public static void prepare(PlaceAdmissionEvent target, TradingAccountRuntime runtime,
                               RuntimeIdentityRegistry identities, long userId, PlaceOrderCommand intent, long clusterTimestamp) {
        if (target == null || runtime == null || identities == null || intent == null
                || userId <= 0 || clusterTimestamp <= 0)
            throw new IllegalArgumentException("invalid order decision input");
        runtime.assertOwner();
        CoreInstrument instrument = runtime.instrument(intent.instrumentId());
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        int symbolId = identities.findSymbolIdValue(instrument.instrumentIdValue());
        if (symbolId < 0) throw new IllegalStateException("instrument instrumentId identity is missing");
        instrument.requireOrderEnabled(intent);
        if (instrument.expiryEpochMillis() > 0 && clusterTimestamp >= instrument.expiryEpochMillis()) {
            boolean lifecycleSettled = runtime.treasury().lifecycleSettlement(symbolId) != 0;
            throw new CoreStateRejectedException(lifecycleSettled ? "INSTRUMENT_SETTLED" : "INVALID_COMMAND", "expired instrument cannot accept new orders");
        }
        target.prepareDecision(intent, instrument, symbolId, runtime.markPrice(symbolId), runtime.feePolicyBook(), clusterTimestamp);
    }

    public static void resolvePrepared(PlaceAdmissionEvent target, MarkPriceRuntime mark,
                                       FeePolicyBook policies, long userId, long clusterTimestamp, boolean lifecycleSettled) {
        resolveValues(target, target.instrument(), target.symbolId(), mark, policies, userId,
                clusterTimestamp, lifecycleSettled, target.intent());
    }

    public static <T extends ResolvedPlaceOrder> T resolveInto(T target, CoreOrderDecisionResolver.Context context, long userId, PlaceOrderCommand intent) {
        if (target == null || context == null || intent == null) throw new IllegalArgumentException("invalid order decision input");
        resolveValues(target, context.instrument(), context.symbolId(), context.mark(), context.policies(), userId,
                context.clusterTimestamp(), context.lifecycleSettled(), intent);
        return target;
    }

    public static ResolvedPlaceOrder resolve(CoreOrderDecisionResolver.Context context, long userId, PlaceOrderCommand intent) {
        if (context == null || intent == null) throw new IllegalArgumentException("invalid order decision input");
        return resolveValues(null, context.instrument(), context.symbolId(), context.mark(), context.policies(), userId,
                context.clusterTimestamp(), context.lifecycleSettled(), intent);
    }

    public static CoreOrderDecisionResolver.Context context(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, String instrumentId, long clusterTimestamp) {
        return context(runtime, identities, com.surprising.product.api.utils.InstrumentIds.parse(instrumentId), clusterTimestamp);
    }

    public static CoreOrderDecisionResolver.Context context(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, int instrumentId, long clusterTimestamp) {
        runtime.assertOwner();
        CoreInstrument instrument = runtime.instrument(instrumentId);
        if (instrument == null) throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        int symbolId = identities.findSymbolIdValue(instrument.instrumentIdValue());
        if (symbolId < 0) throw new IllegalStateException("instrument instrumentId identity is missing");
        return new CoreOrderDecisionResolver.Context(instrument, symbolId, runtime.markPrice(symbolId),
                runtime.feePolicyBook(), clusterTimestamp, runtime.treasury().settlementStatus(symbolId));
    }

    public static CoreOrderDecisionResolver.Context queryContext(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, String instrumentId, long clusterTimestamp) {
        CoreOrderDecisionResolver.Context source = context(runtime, identities, instrumentId, clusterTimestamp);
        CoreInstrument instrument = source.instrument().captureForQuery();
        MarkPriceRuntime mark = source.mark();
        MarkPriceRuntime captured = mark == null || mark.instrument() != source.instrument() ? mark
                : new MarkPriceRuntime(mark.symbolId(), instrument,
                mark.markPriceTicks(), mark.indexPriceTicks(), mark.forwardPriceTicks(), mark.priceSequence(),
                mark.generatedAtEpochMillis(), mark.lastPriceTicks());
        return new CoreOrderDecisionResolver.Context(instrument, source.symbolId(), captured, source.policies(), source.clusterTimestamp(),
                source.settlementStatus());
    }

    private static ResolvedPlaceOrder resolveValues(ResolvedPlaceOrder target,
                                                    CoreInstrument instrument, int symbolId,
                                                    MarkPriceRuntime mark, FeePolicyBook policies, long userId,
                                                    long clusterTimestamp, boolean lifecycleSettled,
                                                    PlaceOrderCommand intent) {
        if (policies == null || userId <= 0) throw new IllegalArgumentException("invalid fee decision input");
        if (!instrument.instrumentId().equals(intent.instrumentId())) throw new IllegalArgumentException("decision context instrumentId mismatch");
        instrument.requireOrderEnabled(intent);
        if (instrument.expiryEpochMillis() > 0 && clusterTimestamp >= instrument.expiryEpochMillis())
            throw new CoreStateRejectedException(lifecycleSettled ? "INSTRUMENT_SETTLED" : "INVALID_COMMAND", "expired instrument cannot accept new orders");
        boolean spot = instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT;
        if (spot && intent.orderType() == CoreOrderType.MARKET && intent.limitPriceTicks() <= 0)
            throw new CoreStateRejectedException("INVALID_COMMAND", "spot market order requires a protection bound");
        if (!spot && intent.orderType() == CoreOrderType.MARKET && intent.limitPriceTicks() != 0)
            throw new CoreStateRejectedException("INVALID_COMMAND", "derivative market order price must be zero");
        if (!spot) {
            requireFreshMark(mark, instrument, clusterTimestamp);
            if (intent.orderType() == CoreOrderType.LIMIT && instrument.orderProtection().limitPriceProtectionEnabled()
                    && clusterTimestamp - mark.generatedAtEpochMillis() > instrument.orderProtection().limitPriceMaxMarkAgeMs())
                throw new CoreStateRejectedException("STALE_MARK_PRICE", "mark price is outside the limit protection freshness bound");
        }
        long markPriceTicks = spot ? intent.limitPriceTicks() : mark.markPriceTicks();
        long indexPriceTicks = spot ? 0 : mark.indexPriceTicks();
        long forwardPriceTicks = spot ? 0 : mark.forwardPriceTicks();
        long matchingPriceTicks = spot || intent.orderType() == CoreOrderType.LIMIT
                ? intent.limitPriceTicks() : protectedPrice(intent.side(), markPriceTicks, instrument.orderProtection().marketMaxSlippagePpm());
        requireLimitPriceBand(intent, instrument, markPriceTicks);
        long reservationPriceTicks = reservationPrice(intent, instrument, markPriceTicks, matchingPriceTicks);
        ReservationKind reservationKind = instrument.contractType()
                == com.surprising.instrument.api.model.ContractType.SPOT
                ? ReservationKind.SPOT_ASSET : ReservationKind.DERIVATIVE_MARGIN;
        String reservationAsset = reservationKind == ReservationKind.DERIVATIVE_MARGIN
                ? instrument.settleAsset()
                : intent.side() == CoreOrderSide.BUY ? instrument.quoteAsset() : instrument.baseAsset();
        var fee = policies.select(userId, instrument.instrumentId(), clusterTimestamp);
        long makerFee = fee == null ? instrument.makerFeeRatePpm() : fee.makerFeeRatePpm();
        long takerFee = fee == null ? instrument.takerFeeRatePpm() : fee.takerFeeRatePpm();
        if (target == null) {
            return new ResolvedPlaceOrder(intent, instrument, symbolId, matchingPriceTicks, reservationPriceTicks,
                    markPriceTicks, indexPriceTicks, forwardPriceTicks, reservationKind, reservationAsset,
                    makerFee, takerFee);
        }
        target.resolve(intent, instrument, symbolId, matchingPriceTicks, reservationPriceTicks,
                markPriceTicks, indexPriceTicks, forwardPriceTicks, reservationKind, reservationAsset,
                makerFee, takerFee);
        return target;
    }

    private static void requireLimitPriceBand(PlaceOrderCommand intent, CoreInstrument instrument, long markPriceTicks) {
        if (instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT
                || intent.orderType() != CoreOrderType.LIMIT || !instrument.orderProtection().limitPriceProtectionEnabled()) return;
        long boundary = protectedPrice(intent.side(), markPriceTicks, instrument.orderProtection().limitPriceBandPpm());
        if (intent.side() == CoreOrderSide.BUY ? intent.limitPriceTicks() > boundary : intent.limitPriceTicks() < boundary)
            throw new CoreStateRejectedException("INVALID_COMMAND", "limit price exceeds configured mark price band");
    }

    private static void requireFreshMark(MarkPriceRuntime mark, CoreInstrument instrument,
                                         long clusterTimestamp) {
        if (mark == null || mark.instrument() != instrument) {
            throw new CoreStateRejectedException("MARK_PRICE_MISSING", "current instrument mark price is required");
        }
        long age = Math.subtractExact(clusterTimestamp, mark.generatedAtEpochMillis());
        if (age < 0 || age > instrument.orderProtection().marketMaxMarkAgeMs()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "mark price is outside the Core freshness bound");
        }
    }

    private static long protectedPrice(CoreOrderSide side, long markPriceTicks, long slippagePpm) {
        long factor = side == CoreOrderSide.BUY
                ? 1_000_000L + slippagePpm
                : 1_000_000L - slippagePpm;
        return Math.max(1, scalePpm(markPriceTicks, factor, side == CoreOrderSide.BUY));
    }

    private static long reservationPrice(PlaceOrderCommand intent, CoreInstrument instrument,
                                         long markPriceTicks, long matchingPriceTicks) {
        if (instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT) {
            return matchingPriceTicks;
        }
        if (instrument.contractType().isOption()) {
            return matchingPriceTicks;
        }
        long slippagePpm = instrument.orderProtection().marketMaxSlippagePpm();
        long lower = boundedMark(markPriceTicks, 1_000_000L - slippagePpm, false);
        long upper = boundedMark(markPriceTicks, 1_000_000L + slippagePpm, true);
        if (intent.orderType() == CoreOrderType.MARKET) {
            return instrument.contractType().isInverse() ? lower : upper;
        }
        if (instrument.contractType().isInverse() && intent.side() == CoreOrderSide.BUY) {
            return Math.min(intent.limitPriceTicks(), lower);
        }
        if (instrument.contractType().isLinear() && intent.side() == CoreOrderSide.SELL) {
            return Math.max(intent.limitPriceTicks(), upper);
        }
        return intent.limitPriceTicks();
    }

    private static long boundedMark(long markPriceTicks, long factor, boolean ceiling) {
        return Math.max(1, scalePpm(markPriceTicks, factor, ceiling));
    }

    public static long scalePpm(long value, long factor, boolean ceiling) {
        long whole = Math.multiplyExact(value / PPM, factor);
        long remainderProduct = Math.multiplyExact(value % PPM, factor);
        long scaled = Math.addExact(whole, remainderProduct / PPM);
        return ceiling && remainderProduct % PPM != 0 ? Math.incrementExact(scaled) : scaled;
    }
}
