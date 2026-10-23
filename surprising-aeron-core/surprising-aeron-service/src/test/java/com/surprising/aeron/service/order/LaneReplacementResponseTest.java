package com.surprising.aeron.service.order;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.result.ResponseArena;
import com.surprising.aeron.service.result.ResponseArenaTestAccess;
import com.surprising.product.api.ProductLine;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 检查单笔或改单 Lane 响应目标的编码失败、未变化订单捕获和跨代复用，保证旧响应内容及租约不会被下一轮结果覆盖。
 */
class LaneReplacementResponseTest {
    /**
     * 缺少品种名称使编码失败时，准备标记、响应引用和长度均无效；成功编码后再失败也不能保留旧成功响应，并确认原槽已归还。
     */
    @Test
    void encodingFailureDoesNotPublishAnEmptySuccessfulResponse() {
        // 当前可复用的单笔或改单 Lane 结果目标
        var target = new CommandSlot.LaneResultTarget();
        // 当前测试响应槽分配器
        var arena = new ResponseArena(1, 4096);
        target.bind(11, 0, arena);
        target.resultOrder(0, order(11, false), null);
        assertThatThrownBy(target::prepareResponse).isInstanceOf(IllegalArgumentException.class);
        assertThat(target.prepared()).isFalse();
        assertThat(target.preparedResponse()).isNull();
        assertThat(target.preparedResponseLength()).isZero();
        target.resultOrder(0, order(11, false), "1");
        target.prepareResponse();
        assertThat(target.prepared()).isTrue();
        assertThat(target.preparedResponseLength()).isPositive();
        // 成功编码的原响应缓冲，失败重编码后应归还
        byte[] encoded = target.preparedResponse();
        target.resultOrder(0, order(11, false), null);
        assertThatThrownBy(target::prepareResponse).isInstanceOf(IllegalArgumentException.class);
        assertThat(target.prepared()).isFalse();
        assertThat(target.preparedResponse()).isNull();
        assertThat(target.preparedResponseLength()).isZero();
        target.clear();
        // 清理后从分配器借回的槽，用于核对原缓冲已释放
        var reused = arena.acquireSlot(16);
        assertThat(ResponseArenaTestAccess.arenaBacked(reused)).isTrue();
        assertThat(reused.storage()).isSameAs(encoded);
        arena.release(reused);
    }

    /**
     * 从所属账户捕获未变化活跃订单和缺失订单的完成结果，再预置取消终态图像，检查后续账户捕获不会把它覆盖成活跃旧值。
     */
    @Test
    void laneCapturesUnchangedOrdersAndKeepsAlreadyCapturedTerminalImages() {
        // 品种编号解析用的运行时身份表
        var identities = new com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry();
        assertThat(identities.symbolId("1")).isZero();
        try (/* 当前测试创建并自动关闭的账户运行时 */ var runtime = new com.surprising.aeron.service.core.TradingAccountRuntime()) {
            runtime.setMetadata(ProductLine.LINEAR_PERPETUAL, 0);
            runtime.putOrder(order(11, false));
            // 用户七所属的权威账户 Lane
            var lane = runtime.accountLaneForUser(7);
            // 当前可复用的单笔或改单 Lane 结果目标
            var target = new CommandSlot.LaneResultTarget();
            // 当前测试响应槽分配器
            var arena = new ResponseArena(1, 4096);
            target.bind(11, 12, arena);
            com.surprising.aeron.service.order.state.LaneOrderResultTarget.capture(target,
                    new com.surprising.aeron.service.core.change.LaneCommitDelta(), identities, lane);
            assertThat(target.resultPrepared(0)).isTrue();
            assertThat(target.resultPrepared(1)).isTrue();
            // 已编码响应重新解码后的命令结果
            var view = CoreCommandResultCodec.decode(Arrays.copyOf(
                    target.preparedResponse(), target.preparedResponseLength()));
            assertThat(view.orders()).extracting(CoreOrderStateView::orderId).containsExactly(11L);
            target.clear();

            target.bind(11, 0, arena);
            assertThat(target.resultPrepared(0)).isFalse();
            target.resultOrder(0, order(11, true), "1");
            com.surprising.aeron.service.order.state.LaneOrderResultTarget.capture(target,
                    new com.surprising.aeron.service.core.change.LaneCommitDelta(), identities, lane);
            view = CoreCommandResultCodec.decode(Arrays.copyOf(
                    target.preparedResponse(), target.preparedResponseLength()));
            assertThat(view.orders()).extracting(CoreOrderStateView::status).containsExactly("CANCELED");
            target.clear();
        }
    }

    /** 本组 Lane 结果测试共用的 U 本位永续品种配置。 */
    private static final CoreInstrument INSTRUMENT = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
            new RegisterInstrumentCommand("1", "BTC", "USDT", "USDT",
                    1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));

    /**
     * 构造指定编号及取消标记的测试订单，其他账户和品种身份固定。
     * @param id 要构造的测试订单编号
     * @param canceled 订单是否带取消终态标记
     */
    private static OrderRuntime order(long id, boolean canceled) {
        return new OrderRuntime(id, 7, 0, INSTRUMENT, CoreOrderSide.BUY, 0,
                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, 0, 0, 10, 0, 10, canceled);
    }

    /**
     * 连续六十四代交替编码双订单和单订单结果，核对每代编号，并保留首代字节副本到最后再次解码，确认后续复用没有改它。
     */
    @Test void accountLaneEncodesBothOrderImagesAndReusesTargetAcrossSingleAndDoubleResults() {
        // 当前可复用的单笔或改单 Lane 结果目标
        var target = new CommandSlot.LaneResultTarget();
        // 当前测试响应槽分配器
        var arena = new ResponseArena(8, 4096);
        // 单独复制并保留的首代双订单响应
        byte[] first = null;
        for (/* 当前复用代数，共检查六十四代 */ int generation = 0; generation < 64; generation++) {
            // 本代第一张订单编号，第二张为它加一
            long id = generation * 2L + 1;
            target.bind(id, id + 1, arena);
            target.resultOrder(0, order(id, true), "1");
            target.resultOrder(1, order(id + 1, false), "1");
            target.prepareResponse();
            assertThat(target.preparedResponse()).isNotNull();
            // 从本代有效范围复制的独立响应字节，后续槽复用不会改它
            byte[] response = Arrays.copyOf(target.preparedResponse(), target.preparedResponseLength());
            // 已编码响应重新解码后的命令结果
            var view = CoreCommandResultCodec.decode(response);
            assertThat(view.orders()).extracting(CoreOrderStateView::orderId).containsExactly(id, id + 1);
            if (first == null) first = response;
            target.clear();

            target.bind(id, 0, arena);
            target.resultOrder(0, order(id, false), "1");
            target.prepareResponse();
            view = CoreCommandResultCodec.decode(Arrays.copyOf(target.preparedResponse(), target.preparedResponseLength()));
            assertThat(view.orders()).extracting(CoreOrderStateView::orderId).containsExactly(id);
            target.clear();
        }
        assertThat(CoreCommandResultCodec.decode(first).orders()).extracting(CoreOrderStateView::orderId)
                .containsExactly(1L, 2L);
    }
}
