package com.surprising.aeron.service.order;

import com.surprising.aeron.service.result.ResponseArena;
import com.surprising.aeron.service.result.ResponseArenaTestAccess;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检查批内订单副本、响应槽租约和不同大小批次复用时的清理边界，防止新批次覆盖仍被旧响应借用的数据。
 */
class OrderBatchSlotReuseTest {
    /**
     * 两个批次从同一活跃订单捕获各自独立副本；清理复用第一批只更新第一份副本，第二批和活跃订单均保持原值。
     */
    @Test void admissionReceiptBelongsToItsItemAndSurvivesOtherBatchesAndReuse() {
        // 第一批上下文或其首项对象，具体类型见声明
        var first = new OrderBatchPending(1, new ResponseArena(1, 4096));
        // 第二批独立上下文
        var second = new OrderBatchPending(1, new ResponseArena(1, 4096));
        first.addItem(11, 0, 0, new Object());
        second.addItem(12, 0, 0, new Object());
        // 用于捕获的活跃订单，不能被副本复用改写
        var live = order(11, 2);
        // 第一批第一项保存的准入副本
        var receipt = first.captureAdmittedOrder(0, live);
        assertThat(receipt).isSameAs(first.items.getFirst().admittedOrder).isNotSameAs(live);
        // 第二批保存的另一份准入副本
        var other = second.captureAdmittedOrder(0, live);
        assertThat(other).isNotSameAs(receipt);
        first.clear();
        assertThat(second.items.getFirst().admittedOrder).isSameAs(other);
        assertThat(other.orderId()).isEqualTo(11);
        first.addItem(13, 0, 0, new Object());
        // 第一批复用时要捕获的新订单
        var nextLive = order(13, 3);
        // 清理后重新借到的订单副本或响应槽，具体类型见声明
        var reused = first.captureAdmittedOrder(0, nextLive);
        assertThat(reused).isSameAs(receipt);
        assertThat(reused.orderId()).isEqualTo(13);
        assertThat(reused.quantitySteps()).isEqualTo(3);
        assertThat(other.orderId()).isEqualTo(11);
        assertThat(live.orderId()).isEqualTo(11);
        first.clear();
        second.clear();
    }

    /**
     * 构造指定编号和数量的 U 本位永续买单，供固定槽复制测试使用。
     * @param id 测试订单编号
     * @param quantity 测试订单总数量和剩余数量步数
     */
    private static com.surprising.aeron.service.order.state.OrderRuntime order(long id, long quantity) {
        // 订单复制测试使用的 U 本位永续品种配置
        var instrument = com.surprising.aeron.service.instrument.CoreInstrument.from(
                com.surprising.product.api.ProductLine.LINEAR_PERPETUAL,
                new com.surprising.aeron.protocol.RegisterInstrumentCommand("1",
                        com.surprising.instrument.api.model.ContractType.LINEAR_PERPETUAL.ordinal(),
                        "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
        return new com.surprising.aeron.service.order.state.OrderRuntime(id, 7, 1, instrument,
                com.surprising.aeron.protocol.CoreOrderSide.BUY, 0, false,
                com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET,
                com.surprising.aeron.protocol.CoreOrderType.LIMIT, com.surprising.aeron.protocol.CoreTimeInForce.GTC,
                0, 0, quantity, 0, quantity, false);
    }

    /**
     * 先成功编码，再把结果码设空触发重编码失败；检查响应引用、长度和租约均清掉，旧缓冲已能重新借出。
     */
    @Test void failedBatchReencodingDropsOldPayloadAndReturnsItsLease() {
        // 测试显式创建的有限响应分配器
        var arena = new ResponseArena(1, 4096);
        // 当前用于复用或编码检查的批次
        var batch = new OrderBatchPending(1, arena);
        batch.addItem(1, 0, 0, new Object());
        // 当前设置状态或准备结果的批内项
        var item = batch.items.getFirst();
        item.status = com.surprising.aeron.protocol.ResponseStatus.APPLIED;
        item.resultCode = com.surprising.aeron.protocol.CoreResultCode.NONE;
        batch.resultOrder(0, null, null);
        batch.prepareResponse();
        // 第一次成功编码使用的响应缓冲，失败重编码后应归还
        byte[] original = batch.preparedResponse();
        assertThat(original).isNotNull();
        item.resultCode = null;
        org.assertj.core.api.Assertions.assertThatThrownBy(batch::prepareResponse)
                .isInstanceOf(RuntimeException.class);
        assertThat(batch.preparedResponse()).isNull();
        assertThat(batch.preparedResponseLength()).isZero();
        assertThat(batch.hasPreparedResponseSlot()).isFalse();
        batch.clear();
        // 清理后重新借到的订单副本或响应槽，具体类型见声明
        var reused = arena.acquireSlot(16);
        assertThat(reused.storage()).isSameAs(original);
        arena.release(reused);
    }

    /**
     * 显式传空响应分配器必须失败，并说明缺少 response arena。
     */
    @Test void batchRequiresAnExplicitResponseArena() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new OrderBatchPending(1, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("response arena");
    }

    /**
     * 等两项 Lane 结果都准备后才编码；移交响应租约并清批后，新借槽不能覆盖旧响应，旧响应显式归还后才允许复用同一缓冲。
     */
    @Test void laneResponseRequiresEveryItemAndIsDetachedFromReusableSlots() {
        // 测试显式创建的有限响应分配器
        var arena = new ResponseArena(1, 4096);
        // 当前用于复用或编码检查的批次
        var batch = new OrderBatchPending(2, arena);
        batch.addItem(1, 0, 0, new Object());
        batch.addItem(2, 0, 0, new Object());
        for (var /* 当前设置状态或准备结果的批内项 */ item : batch.items) {
            item.status = com.surprising.aeron.protocol.ResponseStatus.APPLIED;
            item.resultCode = com.surprising.aeron.protocol.CoreResultCode.NONE;
        }
        batch.resultOrder(0, null, null);
        batch.prepareResponse();
        assertThat(batch.preparedResponse).isNull();
        batch.resultOrder(1, null, null);
        batch.prepareResponse();
        // 已经移交所有权的两项响应缓冲
        byte[] encoded = batch.preparedResponse;
        // 两项响应的实际有效字节数
        int encodedLength = batch.preparedResponseLength;
        assertThat(com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeResult(encoded, encodedLength).items())
                .hasSize(2);
        batch.transferPreparedResponseOwnership();
        batch.clear();
        assertThat(batch.preparedResponse).isNull();
        // 旧响应仍有租约时新借到的独立槽
        var next = arena.acquireSlot(encodedLength);
        assertThat(ResponseArenaTestAccess.arenaBacked(next)).isFalse();
        assertThat(next.storage()).isNotSameAs(encoded);
        java.util.Arrays.fill(next.storage(), (byte) 7);
        // 从旧响应再次解码的结果，证明没有被新槽写入覆盖
        var decoded = com.surprising.aeron.protocol.TradingOrderBatchCodec.decodeResult(encoded, encodedLength);
        assertThat(decoded.items()).hasSize(2);
        assertThat(decoded.items().getFirst().orderId()).isEqualTo(1);
        arena.release(next);
        arena.release(encoded);
        // 清理后重新借到的订单副本或响应槽，具体类型见声明
        var reused = arena.acquireSlot(encodedLength);
        assertThat(reused.storage()).isSameAs(encoded);
        arena.release(reused);
    }

    /**
     * 先填二十项再清批，以一项改单复用第一项对象，检查旧命令、品种、Lane 结果及执行计数均清理，新的改单身份正确。
     */
    @Test void reuseClearsPriorInputsAndLaneResultsAcrossDifferentBatchSizes() {
        // 当前用于复用或编码检查的批次
        var batch = new OrderBatchPending(20, new ResponseArena(1, 4096));
        // 批内多个项共用的输入标识对象
        var command = new Object();
        batch.addItem(1, 0, 0, command);
        // 第一批上下文或其首项对象，具体类型见声明
        var first = batch.items.getFirst();
        first.laneResultPrepared = true;
        first.executionCount = 3;
        first.resultOrderSymbol = "1";
        first.cancelSymbol = "1";
        for (/* 正在填充的旧批次订单编号 */ int i = 2; i <= 20; i++) batch.addItem(i, 0, 0, command);
        // 二十项旧批次末项，清批后输入引用也必须清掉
        var last = batch.items.getLast();
        batch.clear();
        assertThat(first.command).isNull();
        assertThat(last.command).isNull();
        assertThat(first.cancelSymbol).isNull();
        assertThat(first.resultOrderSymbol).isNull();
        batch.addItem(22, 21, 22, "amend");
        assertThat(batch.items.getFirst()).isSameAs(first);
        assertThat(first.laneResultPrepared).isFalse();
        assertThat(first.executionCount).isZero();
        assertThat(first.originalOrderId()).isEqualTo(21);
        assertThat(batch.size()).isOne();
        batch.clear();
        assertThat(first.command).isNull();
    }
}
