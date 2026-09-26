package com.surprising.price.mark.service;

import com.surprising.aeron.client.AeronRealtimeReceiver;
import com.surprising.aeron.protocol.*;
import com.surprising.price.api.model.PerpBookTickerEvent;
import com.surprising.price.api.model.PerpTradeEvent;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.model.MarkPriceEncoding;
import com.surprising.realtime.api.RealtimeRoute;
import com.surprising.realtime.api.ValkeyRouteDirectory;
import com.surprising.realtime.api.ValkeySnapshotRequests;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 接收已提交行情；只做协议和精度转换，最新计算输入仍由 MarkPriceService 持有。 */
@Component
@ConditionalOnProperty(name = "surprising.realtime.enabled", havingValue = "true")
public final class MarkPriceMarketSubscription implements AutoCloseable {
    private final MarkPriceProperties properties;
    private final MarkPriceService prices;
    private final MarkPriceEncodingService encodings;
    private final String node = UUID.randomUUID().toString();
    private final String endpoint;
    private final ValkeyRouteDirectory directory;
    private final ValkeySnapshotRequests requests;
    private final AeronRealtimeReceiver receiver;

    @Autowired
    public MarkPriceMarketSubscription(MarkPriceProperties properties, MarkPriceService prices,
                                      MarkPriceEncodingService encodings, StringRedisTemplate redis, Environment env) {
        this.properties = properties;
        this.prices = prices;
        this.encodings = encodings;
        endpoint = env.getRequiredProperty("surprising.price.mark.market-channel");
        directory = new ValkeyRouteDirectory(redis);
        requests = new ValkeySnapshotRequests(redis);
        receiver = new AeronRealtimeReceiver(env.getRequiredProperty("surprising.realtime.directory"),
                endpoint, env.getProperty("surprising.realtime.ws.stream", Integer.class, 2103), this::receive);
    }

    MarkPriceMarketSubscription(MarkPriceProperties properties, MarkPriceService prices,
                               MarkPriceEncodingService encodings) {
        this.properties = properties;
        this.prices = prices;
        this.encodings = encodings;
        endpoint = null;
        directory = null;
        requests = null;
        receiver = null;
    }

    @Scheduled(fixedDelay = 3000)
    public void renewSubscriptions() {
        if (!receiver.ready()) {
            directory.removeNode(node);
            return;
        }
        long now = System.currentTimeMillis();
        var product = properties.getKafka().getProductLine();
        directory.heartbeat(node, endpoint, Duration.ofSeconds(15));
        // 当前产品线的公共行情；没有账户订阅，也不依赖浏览器是否打开。
        directory.register(new RealtimeRoute(product, 0, "TRADE", "*"), node, now + 15000);
        directory.register(new RealtimeRoute(product, 0, "BOOK", "*"), node, now + 15000);
        for (String symbol : prices.indexSymbols()) requests.renewBook(product, symbol, now + 15000);
    }

    void receive(RealtimeFrame frame) {
        if (frame.productLine() != properties.getKafka().getProductLine() || frame.userId() != 0
                || (frame.kind() != RealtimeFrame.Kind.BOOK && frame.kind() != RealtimeFrame.Kind.TRADE)) return;
        MarkPriceEncoding encoding = encodings.currentEncoding(frame.symbol());
        Instant time = Instant.ofEpochMilli(frame.timestamp());
        if (frame.kind() == RealtimeFrame.Kind.BOOK) {
            CoreOrderBookView book = CoreStateQueryCodec.decodeOrderBookView(frame.payload());
            long bid = 0, ask = Long.MAX_VALUE;
            for (CoreBookLevelView level : book.levels()) {
                if (level.quantitySteps() <= 0) continue;
                if (level.side() == CoreOrderSide.BUY) bid = Math.max(bid, level.priceTicks());
                else ask = Math.min(ask, level.priceTicks());
            }
            // 单边/空盘口也覆盖旧输入；不能继续用旧买卖价计算。
            prices.acceptBookTicker(new PerpBookTickerEvent(frame.symbol(),
                    bid == 0 ? null : price(bid, encoding),
                    ask == Long.MAX_VALUE ? null : price(ask, encoding), frame.sequence(), time));
        } else {
            if (frame.payloadLength() != 25) throw new IllegalArgumentException("invalid market trade payload");
            var bytes = ByteBuffer.wrap(frame.payload()).order(ByteOrder.LITTLE_ENDIAN);
            long ticks = bytes.getLong(), quantity = bytes.getLong();
            bytes.getLong();
            int side = Byte.toUnsignedInt(bytes.get());
            if (ticks <= 0 || quantity <= 0 || side >= CoreOrderSide.values().length)
                throw new IllegalArgumentException("invalid market trade");
            prices.acceptTrade(new PerpTradeEvent(frame.symbol(), frame.entityId(), frame.sequence(), time,
                    price(ticks, encoding), BigDecimal.valueOf(quantity).multiply(BigDecimal.valueOf(encoding.quantityStepUnits()))
                    .divide(BigDecimal.valueOf(encoding.baseScaleUnits()), 18, RoundingMode.HALF_UP),
                    CoreOrderSide.values()[side].name()));
        }
    }

    private BigDecimal price(long ticks, MarkPriceEncoding encoding) {
        return BigDecimal.valueOf(ticks).multiply(BigDecimal.valueOf(encoding.priceTickUnits()))
                .divide(BigDecimal.valueOf(encoding.quoteScaleUnits()), 18, RoundingMode.HALF_UP);
    }

    @Override
    @PreDestroy
    public void close() {
        if (receiver != null) receiver.close();
        if (directory != null) directory.removeNode(node);
    }
}
