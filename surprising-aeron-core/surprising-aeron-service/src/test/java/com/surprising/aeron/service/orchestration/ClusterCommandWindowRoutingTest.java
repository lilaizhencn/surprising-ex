package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClusterCommandWindowRoutingTest {

    @Test
    void differentUsersSharingALaneCanFillTheOwnerWindow() {
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
    @Test void decodedSlotsStayLeasedUntilTheirWindowEntriesRetire() {
        var window = new ClusterCommandWindow(64);
        var first = request(1, 101);
        var second = request(2, 202);
        var decodedFirst = window.decoded(first);
        window.resetCandidate(101); window.route(0, 1); window.add(ClusterServiceEgress.NO_SESSION_ID, first, 0, 0);
        window.releaseDecoded();
        var decodedSecond = window.decoded(second);
        window.resetCandidate(202); window.route(0, 1); window.add(ClusterServiceEgress.NO_SESSION_ID, second, 0, 0);
        window.releaseDecoded();
        assertThat(decodedFirst.placeOrder().orderId()).isEqualTo(1);
        assertThat(decodedSecond.placeOrder().orderId()).isEqualTo(2);
        assertThat(decodedFirst).isNotSameAs(decodedSecond);
        window.removePrefix(1);
        assertThat(decodedSecond.placeOrder().orderId()).isEqualTo(2);
        for (int index = 3; index <= 130; index++) {
            if (window.size() > 0) window.removePrefix(1);
            var next = request(index, index + 1000);
            var decoded = window.decoded(next);
            window.resetCandidate(index + 1000); window.route(0, 1); window.add(ClusterServiceEgress.NO_SESSION_ID, next, 0, 0);
            assertThat(window.decoded(next)).isSameAs(decoded);
            assertThat(decoded.placeOrder().orderId()).isEqualTo(index);
            window.releaseDecoded();
        }
        window.clear();
    }
    private static com.surprising.aeron.protocol.CoreMessage request(long id, long user) {
        var order = new com.surprising.aeron.protocol.PlaceOrderCommand(id, "1",
                com.surprising.aeron.protocol.CoreOrderSide.BUY, 100, 1, false,
                com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET,
                com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC, false, "");
        var header = com.surprising.aeron.protocol.CoreMessageHeader.command(
                com.surprising.aeron.protocol.CoreMessageType.PLACE_ORDER, new java.util.UUID(1, id),
                com.surprising.product.api.ProductLine.LINEAR_PERPETUAL, com.surprising.aeron.protocol.CommandSource.OPERATIONS,
                1, id, user, 1, id);
        return new com.surprising.aeron.protocol.CoreMessage(header, com.surprising.aeron.protocol.TradingCommandCodec.encodePlaceOrder(order));
    }
}
