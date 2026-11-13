package com.surprising.price.mark.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.surprising.aeron.protocol.*;
import com.surprising.price.api.model.PerpBookTickerEvent;
import com.surprising.price.api.model.PerpTradeEvent;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.model.MarkPriceEncoding;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MarkPriceMarketSubscriptionTest {
    @Test
    void decodesCommittedBookAndTradeAndIsolatesProductLines() {
        var properties = new MarkPriceProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        var service = mock(MarkPriceService.class);
        var encodings = mock(MarkPriceEncodingService.class);
        when(encodings.currentEncoding("1")).thenReturn(new MarkPriceEncoding(1, 10000, 100, 10000, 1));
        var subscription = new MarkPriceMarketSubscription(properties, service, encodings);
        byte[] book = CoreStateQueryCodec.encodeOrderBookView(new CoreOrderBookView(10,
                List.of(new CoreBookLevelView("1", CoreOrderSide.BUY, 10001, 2, 1),
                        new CoreBookLevelView("1", CoreOrderSide.SELL, 10003, 3, 1))));
        subscription.receive(frame(ProductLine.INVERSE_PERPETUAL, RealtimeFrame.Kind.BOOK, book));
        verifyNoInteractions(service, encodings);
        subscription.receive(frame(ProductLine.LINEAR_PERPETUAL, RealtimeFrame.Kind.BOOK, book));
        var bookCapture = ArgumentCaptor.forClass(PerpBookTickerEvent.class);
        verify(service).acceptBookTicker(bookCapture.capture());
        assertThat(bookCapture.getValue().bestBidPrice()).isEqualByComparingTo("100.01");
        assertThat(bookCapture.getValue().bestAskPrice()).isEqualByComparingTo("100.03");
        byte[] trade = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(10002).putLong(5).putLong(7).put((byte) 0).array();
        subscription.receive(frame(ProductLine.LINEAR_PERPETUAL, RealtimeFrame.Kind.TRADE, trade));
        var tradeCapture = ArgumentCaptor.forClass(PerpTradeEvent.class);
        verify(service).acceptTrade(tradeCapture.capture());
        assertThat(tradeCapture.getValue().price()).isEqualByComparingTo("100.02");
        assertThat(tradeCapture.getValue().quantity()).isEqualByComparingTo("0.0005");
        assertThat(tradeCapture.getValue().tradeTime().toEpochMilli()).isEqualTo(123456);
    }

    @Test
    void realDriverBadFrameInvalidatesInputsAndLeaseOwnerResumesWithFreshMarketData() throws Exception {
        String directory = System.getProperty("java.io.tmpdir") + "/price-receiver-recovery-" + java.util.UUID.randomUUID();
        int port;
        try (var socket = new java.net.DatagramSocket(0)) { port = socket.getLocalPort(); }
        String channel = "aeron:udp?endpoint=127.0.0.1:" + port;
        var properties = new MarkPriceProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        var service = mock(MarkPriceService.class);
        when(service.indexSymbols()).thenReturn(java.util.Set.of());
        var encodings = mock(MarkPriceEncodingService.class);
        when(encodings.currentEncoding("1")).thenReturn(new MarkPriceEncoding(1, 10000, 100, 10000, 1));
        var redis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        @SuppressWarnings("unchecked") var values = (org.springframework.data.redis.core.ValueOperations<String,String>) mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("surprising.realtime.directory", directory)
                .withProperty("surprising.price.mark.market-channel", channel);
        try (var driver = io.aeron.driver.MediaDriver.launch(new io.aeron.driver.MediaDriver.Context()
                .aeronDirectoryName(directory).dirDeleteOnStart(true).dirDeleteOnShutdown(true));
             var subscription = new MarkPriceMarketSubscription(properties, service, encodings, redis, environment);
             var aeron = io.aeron.Aeron.connect(new io.aeron.Aeron.Context().aeronDirectoryName(directory));
             var publication = aeron.addExclusivePublication(channel, 2103)) {
            await(publication::isConnected);
            var corrupt = new org.agrona.concurrent.UnsafeBuffer(new byte[16]);
            await(() -> publication.offer(corrupt) > 0);
            verify(service, timeout(3000)).marketContinuityLost();
            subscription.renewSubscriptions();
            verify(service).resetMarketInputs();
            byte[] trade = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(10002).putLong(5).putLong(7).put((byte) 0).array();
            var bytes = new org.agrona.concurrent.UnsafeBuffer(RealtimeFrameCodec.encode(frame(ProductLine.LINEAR_PERPETUAL, RealtimeFrame.Kind.TRADE, trade)));
            await(() -> publication.offer(bytes) > 0);
            verify(service, timeout(3000)).acceptTrade(org.mockito.ArgumentMatchers.any());
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("condition timed out");
            Thread.sleep(10);
        }
    }

    private RealtimeFrame frame(ProductLine product, RealtimeFrame.Kind kind, byte[] payload) {
        return new RealtimeFrame(product, kind, 0, 100, 1, 123456, 0, "1", "trade-1", payload);
    }
}
