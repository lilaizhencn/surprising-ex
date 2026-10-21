package com.surprising.aeron.service.orchestration.trade;

import com.surprising.aeron.service.orchestration.egress.ClusterServiceEgress;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证入口窗口以用户身份而非 Lane 位单独判冲突，以及解码槽在条目退休前不被下一请求覆盖。只测试 Owner 内存窗口，不向真实 Matcher 或账户 Lane 提交命令。
 */
class ClusterCommandWindowRoutingTest {

    /**
     * 加入用户一百零一后，另一个同 Lane 用户二百零二无依赖，同用户候选需要退休一项；实际只加入一项，不在此填满全部六十四容量。
     */
    @Test
    void differentUsersSharingALaneCanFillTheOwnerWindow() {
        // 六十四条容量的 Owner 窗口。
        var window = new ClusterCommandWindow(64);

        window.resetCandidate(101);
        window.route(0, 1L);
        window.add(ClusterServiceEgress.NO_SESSION_ID, null, 0, 0);

        window.resetCandidate(202);
        window.route(0, 1L);
        assertThat(window.conflictingPrefixSize()).isZero();

        window.resetCandidate(101);
        window.route(0, 1L);
        assertThat(window.conflictingPrefixSize()).isOne();
    }
    /**
     * 加入两条命令并释放候选关联，要求各自解码仍保留订单身份、对象不同，退休首项不影响第二项；再循环越过两次容量验证交换复用。
     */
    @Test void decodedSlotsStayLeasedUntilTheirWindowEntriesRetire() {
        // 六十四条容量的 Owner 窗口。
        var window = new ClusterCommandWindow(64);
        // 首条请求。
        var first = request(1, 101);
        // 第二条请求。
        var second = request(2, 202);
        // 首条请求租用的解码槽，候选释放后仍归窗口条目。
        var decodedFirst = window.decoded(first);
        window.resetCandidate(101); window.route(0, 1); window.add(ClusterServiceEgress.NO_SESSION_ID, first, 0, 0);
        window.releaseDecoded();
        // 第二条独立解码槽。
        var decodedSecond = window.decoded(second);
        window.resetCandidate(202); window.route(0, 1); window.add(ClusterServiceEgress.NO_SESSION_ID, second, 0, 0);
        window.releaseDecoded();
        assertThat(decodedFirst.placeOrder().orderId()).isEqualTo(1);
        assertThat(decodedSecond.placeOrder().orderId()).isEqualTo(2);
        assertThat(decodedFirst).isNotSameAs(decodedSecond);
        window.removePrefix(1);
        assertThat(decodedSecond.placeOrder().orderId()).isEqualTo(2);
        for (/* 循环复用时递增的订单身份，范围三至一百三十。 */ int index = 3; index <= 130; index++) {
            if (window.size() > 0) window.removePrefix(1);
            // 当前循环的新请求。
            var next = request(index, index + 1000);
            // 本次请求加入窗口前解码的原槽，加入后缓存仍应返回同一引用。
            var decoded = window.decoded(next);
            window.resetCandidate(index + 1000); window.route(0, 1); window.add(ClusterServiceEgress.NO_SESSION_ID, next, 0, 0);
            assertThat(window.decoded(next)).isSameAs(decoded);
            assertThat(decoded.placeOrder().orderId()).isEqualTo(index);
            window.releaseDecoded();
        }
        window.clear();
    }
    /**
     * 构造指定订单号和用户的线性永续限价买单消息，UUID 低位和源序号都使用订单号。
     * @param id 测试订单号及源序号
     * @param user 提交用户编号
     */
    private static com.surprising.aeron.protocol.CoreMessage request(long id, long user) {
        // 需要编码的限价单输入。
        var order = new com.surprising.aeron.protocol.PlaceOrderCommand(id, "1",
                com.surprising.aeron.protocol.CoreOrderSide.BUY, 100, 1, false,
                com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET,
                com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, "");
        // 本次请求的固定产品线和来源头部。
        var header = com.surprising.aeron.protocol.CoreMessageHeader.command(
                com.surprising.aeron.protocol.CoreMessageType.PLACE_ORDER, new java.util.UUID(1, id),
                com.surprising.product.api.ProductLine.LINEAR_PERPETUAL, com.surprising.aeron.protocol.CommandSource.OPERATIONS,
                1, id, user, 1, id);
        return new com.surprising.aeron.protocol.CoreMessage(header, com.surprising.aeron.protocol.TradingCommandCodec.encodePlaceOrder(order));
    }
}
