package com.surprising.aeron.service.state.order;

import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.account.AccountLaneState;
import com.surprising.aeron.service.state.account.BalanceRuntime;
import com.surprising.aeron.service.state.account.UserRuntime;
import com.surprising.aeron.service.state.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.admission.RuntimeOrderAdmission;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import java.util.UUID;

/**
 * 已有账户订单状态变更入口：准备身份、写订单与冻结，触发及顺序批次登记待完成预留，取消或拒绝时按剩余冻结释放。调用方仍先满足原准入、Matcher 确认和账户所有权边界，这里不直接操作订单簿。
 */
public final class RuntimeOrderStateTransitions {

    /**
     * 只提供已有订单状态写入的静态入口，不创建额外执行对象。
     */
    private RuntimeOrderStateTransitions() {
    }

    /**
     * 校验基本下单输入，在原所有权范围准备客户端、币对及资产身份，再进入已准备身份的账户写入。
     * @param runtime 提供原账户所有权、订单与资金写入入口的所属运行时
     * @param identities 当前产品运行时身份表，普通控制入口可准备身份，Lane 入口查已有持仓键
     * @param userId 订单与冻结所属用户编号
     * @param command 已经解析价格、费率和冻结资产的下单输入
     * @param commandId 原业务命令 UUID，用于订单和冻结归属
     * @param requiredReservation 已经计算的正数应冻结金额，账户写入时仍核对可用余额
     */
    public static void place(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                      long userId, ResolvedPlaceOrder command, UUID commandId,
                      long requiredReservation) {
        if (runtime == null || identities == null || command == null || commandId == null || userId <= 0
                || requiredReservation <= 0) {
            throw new IllegalArgumentException("invalid runtime place order");
        }
        runtime.assertOwner();
        // 按用户及客户端标识准备的客户身份键，后续核对唯一性并与订单关联。
        long clientKey = identities.clientKey(userId, command.clientOrderId());
        // 从永久币对编号取得的稠密身份。
        int symbolId = identities.symbolId(command.instrumentId());
        // 从预留资产代码取得的整数身份。
        int assetId = identities.assetId(command.reservationAsset());
        placePrepared(runtime, userId, command, commandId, requiredReservation,
                clientKey, symbolId, assetId);
    }

    /**
     * 再次核对订单和客户端标识未占用、用户及资产余额存在且可用金额足够，准备订单和冻结，调用原 reserveOrder 写入，再推进用户和命令版本。
     * @param runtime 提供原账户所有权、订单与资金写入入口的所属运行时
     * @param userId 订单与冻结所属用户编号
     * @param command 已经解析价格、费率和冻结资产的下单输入
     * @param commandId 原业务命令 UUID，用于订单和冻结归属
     * @param requiredReservation 已经计算的正数应冻结金额，账户写入时仍核对可用余额
     * @param clientKey 当前用户客户端订单标识的已准备整数身份，0 表示没有标识
     * @param symbolId 币对的运行时稠密编号
     * @param assetId 订单冻结资产的运行时编号
     */
    public static void placePrepared(
            TradingRuntimeState runtime, long userId, ResolvedPlaceOrder command, UUID commandId,
            long requiredReservation, long clientKey, int symbolId, int assetId) {
        if (runtime == null || command == null || commandId == null || userId <= 0
                || requiredReservation <= 0 || clientKey < 0 || symbolId < 0 || assetId < 0) {
            throw new IllegalArgumentException("invalid prepared runtime place order");
        }
        runtime.assertOwner();
        if (runtime.order(command.orderId()) != null) {
            throw new CoreStateRejectedException("DUPLICATE_ORDER_ID", "orderId already exists");
        }
        if (clientKey != 0 && runtime.orderIdByClient(userId, clientKey) != null) {
            throw new CoreStateRejectedException("DUPLICATE_CLIENT_ORDER_ID", "clientOrderId already exists");
        }
        // 当前订单用户元数据，缺失时不能冻结下单。
        UserRuntime user = runtime.user(userId);
        // 订单冻结资产的真实账户余额，下单核对可用额，拒单核对可释放锁定额。
        BalanceRuntime balance = runtime.balance(userId, assetId);
        if (user == null || balance == null || balance.availableUnits() < requiredReservation) {
            throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                    "available balance is insufficient");
        }
        // 按已决策输入准备的原控制订单，稍后连同冻结写入账户。
        OrderRuntime order = runtime.prepareControlOrder(userId, command, commandId, symbolId);
        // 当前订单的冻结记录，下单时创建，取消及拒绝时以剩余额度决定释放金额。
        ReservationRuntime reservation = order.prepareReservation(command.reservationKind(), assetId, requiredReservation);
        // 精确递增后的用户版本，和本次订单冻结一起写入。
        long userRevision = Math.incrementExact(user.revision());
        runtime.reserveOrder(order, reservation, clientKey);
        runtime.setUserState(userId, userRevision, user.positionMode());
        runtime.incrementCommandRevision();
    }

    /**
     * 在触发子单所属 Lane 计算预留并写订单，标记本命令待完成冻结；从 Lane 变更回执复制一份独立命令槽输入，避免后续触发重用控制缓冲而覆盖尚未撮合的子单。
     * @param runtime 提供原账户所有权、订单与资金写入入口的所属运行时
     * @param userId 订单与冻结所属用户编号
     * @param command 已经解析价格、费率和冻结资产的下单输入
     * @param commandId 原业务命令 UUID，用于订单和冻结归属
     * @param coreSequence 触发、批次或拒单所属的正数 Core 命令序号，用来核对待完成预留
     * @param openInterestSteps 触发子单准入采用的固定币对 OI 数量步数
     * @param identities 当前产品运行时身份表，普通控制入口可准备身份，Lane 入口查已有持仓键
     * @param clientKey 当前用户客户端订单标识的已准备整数身份，0 表示没有标识
     * @param assetId 订单冻结资产的运行时编号
     * @param lifecycleSettled 该币对是否已完成到期结算，交给原准入规则检查
     * @param fundingInProgress 该币对资金费持仓截面是否仍在处理中
     */
    public static OrderRuntime placeTriggerChildInLane(TradingRuntimeState runtime, long userId,
            ResolvedPlaceOrder command, UUID commandId, long coreSequence, long openInterestSteps,
            RuntimeIdentityRegistry identities, long clientKey, int assetId,
            boolean lifecycleSettled, boolean fundingInProgress) {
        // 当前命令实际持有的账户 Lane，必须与用户拓扑一致。
        AccountLaneState lane = runtime.currentLaneCommandScope();
        if (lane == null || lane.laneId() != runtime.topology().accountLaneId(userId))
            throw new IllegalStateException("trigger child requires its Account Lane");
        // 触发子单对应的用户、币对及持仓腿身份，无已有身份时为 0。
        long positionKey = identities.findPositionKeyValueInLane(lane, userId,
                command.instrument(), command.positionSide());
        // 触发子单经过原账户准入规则算出的所需预留金额。
        long required = RuntimeOrderAdmission.requiredReservationPrepared(runtime, lane, userId, command,
                openInterestSteps, lane.admissionOrderIndex(command.symbolId()), clientKey,
                command.symbolId(), positionKey, lifecycleSettled, fundingInProgress);
        placePrepared(runtime, userId, command, commandId, required, clientKey, command.symbolId(), assetId);
        lane.markPendingReservation(command.orderId(), coreSequence);
        // 原 Lane 变更缓冲中的获准子订单回执，需复制到该子单独有命令槽再交给 Matcher。
        OrderRuntime receipt = runtime.laneDelta(lane.laneId()).orders().get(command.orderId());
        if (receipt == null) throw new IllegalStateException("trigger admission receipt is missing");
        // 每个排队触发子单独自保留此输入直到 Matcher 准入；后续触发可能提前复用控制 worker 及其输出缓冲，不能直接借用该缓冲回执。
        return receipt.copyIntoCommandSlot(null);
    }

    /**
     * 顺序批次项在目标 Lane 写订单并标记待完成预留；已有命令范围直接使用，没有范围则进入原目标 Lane 范围，Owner 路由等原控制回执发布后再登记。
     * @param runtime 提供原账户所有权、订单与资金写入入口的所属运行时
     * @param userId 订单与冻结所属用户编号
     * @param command 已经解析价格、费率和冻结资产的下单输入
     * @param commandId 原业务命令 UUID，用于订单和冻结归属
     * @param requiredReservation 已经计算的正数应冻结金额，账户写入时仍核对可用余额
     * @param clientKey 当前用户客户端订单标识的已准备整数身份，0 表示没有标识
     * @param assetId 订单冻结资产的运行时编号
     * @param coreSequence 触发、批次或拒单所属的正数 Core 命令序号，用来核对待完成预留
     */
    public static void reserveBatchOrderInLane(TradingRuntimeState runtime, long userId,
            ResolvedPlaceOrder command, UUID commandId, long requiredReservation,
            long clientKey, int assetId, long coreSequence) {
        // 当前命令实际持有的账户 Lane，必须与用户拓扑一致。
        AccountLaneState lane = runtime.currentLaneCommandScope();
        if (lane == null) {
            // 当前批次项用户所属的目标 Lane 编号。
            int laneId = runtime.topology().accountLaneId(userId);
            // 没有现成命令范围时需要进入的原账户 Lane，不另建 worker。
            AccountLaneState targetLane = runtime.accountLane(laneId);
            runtime.inLaneCommandScope(targetLane, /* 原命令范围回调传入的参数，内部沿用已经选好的 targetLane 写待完成标记。 */ ignored -> {
                placePrepared(runtime, userId, command, commandId, requiredReservation,
                        clientKey, command.symbolId(), assetId);
                targetLane.markPendingReservation(command.orderId(), coreSequence);
                return null;
            });
            return;
        }
        if (lane.laneId() != runtime.topology().accountLaneId(userId))
            throw new IllegalStateException("batch reservation requires its Account Lane");
        placePrepared(runtime, userId, command, commandId, requiredReservation,
                clientKey, command.symbolId(), assetId);
        // Lane 先保留真实待完成标记；原控制调度器发布固定准入回执后，Owner 才登记路由。
        lane.markPendingReservation(command.orderId(), coreSequence);
    }

    /**
     * 按用户核对订单存在和归属，终态返回 false；活动订单必须有冻结，按全部剩余额度调用原账户取消入口，再推进命令版本。Matcher 是否已确认由调用方保证。
     * @param runtime 提供原账户所有权、订单与资金写入入口的所属运行时
     * @param userId 订单与冻结所属用户编号
     * @param orderId 需要取消或拒绝的原订单编号
     */
    public static boolean cancel(TradingRuntimeState runtime, long userId, long orderId) {
        if (runtime == null || userId <= 0 || orderId <= 0) {
            throw new IllegalArgumentException("invalid runtime cancel order");
        }
        runtime.assertOwner();
        // 按编号取得的原订单，先核对归属和终态再释放冻结。
        OrderRuntime order = runtime.order(orderId);
        if (order == null) throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        if (order.userId() != userId) {
            throw new CoreStateRejectedException("ORDER_OWNER_MISMATCH", "order belongs to another user");
        }
        if (order.status().terminal()) return false;
        // 当前订单的冻结记录，下单时创建，取消及拒绝时以剩余额度决定释放金额。
        ReservationRuntime reservation = runtime.reservation(orderId);
        if (reservation == null) throw new IllegalStateException("open order is missing reservation");
        runtime.cancelOrder(orderId, userId, reservation.reservedUnits());
        runtime.incrementCommandRevision();
        return true;
    }

    /**
     * 核对拒单所属订单与冻结，必要时先完成本命令待处理预留，再把剩余冻结从锁定移回可用，写 REJECTED 后状态并删除冻结，推进用户及命令版本。
     * @param runtime 提供原账户所有权、订单与资金写入入口的所属运行时
     * @param userId 订单与冻结所属用户编号
     * @param orderId 需要取消或拒绝的原订单编号
     * @param coreSequence 触发、批次或拒单所属的正数 Core 命令序号，用来核对待完成预留
     */
    static void reject(TradingRuntimeState runtime, long userId, long orderId, long coreSequence) {
        if (runtime == null || userId <= 0 || orderId <= 0 || coreSequence <= 0) {
            throw new IllegalArgumentException("invalid runtime rejected order");
        }
        runtime.assertOwner();
        // 本次要标为拒绝的原订单，必须存在且属于用户。
        OrderRuntime order = runtime.order(orderId);
        if (order == null || order.userId() != userId) {
            throw new CoreStateRejectedException("ORDER_NOT_FOUND", "order does not exist");
        }
        // 当前订单的冻结记录，下单时创建，取消及拒绝时以剩余额度决定释放金额。
        ReservationRuntime reservation = runtime.reservation(orderId);
        if (reservation == null) throw new IllegalStateException("rejected order reservation is missing");
        if (runtime.pendingReservations().pendingReservation(orderId, userId)) {
            runtime.pendingReservations().completePendingReservation(userId, orderId, coreSequence);
        }
        // 订单冻结资产的真实账户余额，下单核对可用额，拒单核对可释放锁定额。
        BalanceRuntime balance = runtime.balance(userId, reservation.assetId());
        if (balance == null) throw new IllegalStateException("rejected order balance is missing");
        // 拒单时仍剩余的冻结金额，正数才从锁定余额移回可用。
        long releaseUnits = reservation.reservedUnits();
        if (releaseUnits > 0) {
            if (balance.lockedUnits() < releaseUnits) throw new IllegalArgumentException("invalid runtime release");
            runtime.replaceBalance(new BalanceRuntime(userId, reservation.assetId(),
                    Math.addExact(balance.availableUnits(), releaseUnits), balance.lockedUnits() - releaseUnits));
        }
        runtime.replaceOrder(order.withStatus(CoreOrderStatus.REJECTED, Math.incrementExact(order.revision())));
        runtime.removeReservation(orderId, userId);
        runtime.advanceUserRevision(userId);
        runtime.incrementCommandRevision();
    }
}
