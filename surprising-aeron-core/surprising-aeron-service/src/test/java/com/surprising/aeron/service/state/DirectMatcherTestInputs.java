package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.service.matching.*;
import com.surprising.aeron.service.matching.CoreMatchingOrder;
import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.order.OrderRuntime;
import com.surprising.aeron.service.state.settlement.MatcherSettlementEvent;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;

/**
 * 订单簿与撮合证据测试的输入辅助：用真实预留的 Direct 发布槽接入适配器。临时运行时在返回前关闭，返回事件供订单簿、证据断言使用；账户资金结算由其他运行时测试覆盖，不能据此当作已完成资金提交。
 */
public final class DirectMatcherTestInputs {
    /**
     * 禁止实例化，仅提供测试输入构造方法。
     */
    private DirectMatcherTestInputs() {}

    /**
     * 不带前置撤单列表地准备普通 Direct 下单槽，尚未调用适配器撮合。
     * @param adapter 本测试持有的确定性 Matcher 适配器
     * @param sequence 本次撮合输入对应的 Core 序号
     * @param commandId 用于撮合证据核对的原命令 UUID
     * @param timestamp 本次输入使用的固定业务时间
     * @param userId 本次订单或撤单所属用户
     * @param order 已构造的 Matcher 订单输入，本辅助按它的标的及数量准备路由
     */
    public static MatcherSettlementEvent preparePlace(DeterministicExchangeCoreAdapter adapter,
            long sequence, UUID commandId, long timestamp, long userId, CoreMatchingOrder order) {
        return preparePlace(adapter, sequence, commandId, timestamp, userId, order, List.of());
    }

    /**
     * 在临时冷运行时注册与输入标的一致的现货合约，构造准入订单及全部账户 Lane 路由，准备并保留 Matcher 发布槽后返回；不执行账户资金结算。
     * @param adapter 本测试持有的确定性 Matcher 适配器
     * @param sequence 本次撮合输入对应的 Core 序号
     * @param commandId 用于撮合证据核对的原命令 UUID
     * @param timestamp 本次输入使用的固定业务时间
     * @param userId 本次订单或撤单所属用户
     * @param order 已构造的 Matcher 订单输入，本辅助按它的标的及数量准备路由
     * @param cancellations 本次新单之前需要记录的既有撤单 ID 清单
     */
    public static MatcherSettlementEvent preparePlace(DeterministicExchangeCoreAdapter adapter,
            long sequence, UUID commandId, long timestamp, long userId, CoreMatchingOrder order,
            List<Long> cancellations) {
        try (/* 仅用于准备真实 Direct 事件槽的临时冷运行时，返回前自动关闭 */ var runtime = new TradingRuntimeState()) {
            // 局部资产和标的身份表，供事件路由准备使用
            var identities = new RuntimeIdentityRegistry();
            // 按 Matcher 输入标的构造的现货测试合约
            var instrument = CoreInstrument.from(ProductLine.SPOT,
                    new RegisterInstrumentCommand(Integer.toString(order.instrumentId()), ContractType.SPOT.ordinal(),
                            "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
            runtime.setMetadata(ProductLine.SPOT, 0);
            runtime.registerInstrument(instrument);
            // 为事件准备的 OPEN 准入订单，数量、方向和订单类型沿用输入
            var admitted = new OrderRuntime(order.orderId(), ProductLine.SPOT, userId,
                    identities.symbolId(Integer.toString(order.instrumentId())), instrument, order.side(), order.matchingPriceTicks(),
                    order.matchingPriceTicks(), order.quantitySteps(), 0, order.quantitySteps(), false,
                    CoreMarginMode.CROSS, CorePositionSide.NET, order.orderType(), order.timeInForce(),
                    order.timeInForce() == CoreTimeInForce.GTX, "", commandId, 0, 0, 0,
                    timestamp, timestamp, 0, CoreOrderStatus.OPEN, 1);
            // 当前拓扑全部账户 Lane 的位图，六十四 Lane 时用负一表示所有位
            long lanes = runtime.topology().accountLaneCount() == 64 ? -1L
                    : (1L << runtime.topology().accountLaneCount()) - 1;
            // 已保留 Matcher 发布租约的 Direct 事件，供适配器写入证据及订单簿结果
            var target = runtime.prepareDirectMatcherSettlement(sequence, lanes, admitted, null, 1,
                    commandId, adapter.matcherShardId(Integer.toString(order.instrumentId())), identities, timestamp, 0, cancellations, null);
            target.reserveMatcherPublication();
            return target;
        }
    }

    /**
     * 先取得保留发布租约的事件，再按同一标的分片调用适配器 Direct 下单，返回供订单簿与证据断言的事件。
     * @param adapter 本测试持有的确定性 Matcher 适配器
     * @param sequence 本次撮合输入对应的 Core 序号
     * @param commandId 用于撮合证据核对的原命令 UUID
     * @param timestamp 本次输入使用的固定业务时间
     * @param userId 本次订单或撤单所属用户
     * @param order 已构造的 Matcher 订单输入，本辅助按它的标的及数量准备路由
     */
    public static MatcherSettlementEvent placeDirect(DeterministicExchangeCoreAdapter adapter,
            long sequence, UUID commandId, long timestamp, long userId, CoreMatchingOrder order) {
        // 已保留 Matcher 发布租约的 Direct 事件，供适配器写入证据及订单簿结果
        var target = preparePlace(adapter, sequence, commandId, timestamp, userId, order);
        adapter.placeDirect(adapter.matcherShardId(Integer.toString(order.instrumentId())), sequence, commandId,
                timestamp, userId, order, target);
        return target;
    }

    /**
     * 以订单号作序号、用户与订单号作固定 UUID，并用时间一千简化测试调用。
     * @param adapter 本测试持有的确定性 Matcher 适配器
     * @param userId 本次订单或撤单所属用户
     * @param order 已构造的 Matcher 订单输入，本辅助按它的标的及数量准备路由
     */
    public static MatcherSettlementEvent placeDirect(DeterministicExchangeCoreAdapter adapter,
            long userId, CoreMatchingOrder order) {
        return placeDirect(adapter, order.orderId(), new UUID(userId, order.orderId()), 1000, userId, order);
    }

    /**
     * 用临时运行时准备并保留 Direct 撤单事件，按标的分片调用适配器撤单后返回；这里只准备订单簿与证据测试，不应用账户资金。
     * @param adapter 本测试持有的确定性 Matcher 适配器
     * @param sequence 本次撮合输入对应的 Core 序号
     * @param commandId 用于撮合证据核对的原命令 UUID
     * @param timestamp 本次输入使用的固定业务时间
     * @param userId 本次订单或撤单所属用户
     * @param orderId 要撤销的原订单号
     * @param instrumentId 原订单所属的永久数字标的文本
     */
    public static MatcherSettlementEvent cancelDirect(DeterministicExchangeCoreAdapter adapter,
            long sequence, UUID commandId, long timestamp, long userId, long orderId, String instrumentId) {
        try (/* 仅用于准备真实 Direct 事件槽的临时冷运行时，返回前自动关闭 */ var runtime = new TradingRuntimeState()) {
            // 已保留 Matcher 发布租约的 Direct 事件，供适配器写入证据及订单簿结果
            var target = runtime.prepareDirectCancellation(sequence, userId, orderId, commandId,
                    adapter.matcherShardId(instrumentId), new RuntimeIdentityRegistry(), timestamp, 0);
            target.reserveMatcherPublication();
            adapter.cancelDirect(adapter.matcherShardId(instrumentId), sequence, commandId, orderId,
                    timestamp, userId, instrumentId, target);
            return target;
        }
    }
}
