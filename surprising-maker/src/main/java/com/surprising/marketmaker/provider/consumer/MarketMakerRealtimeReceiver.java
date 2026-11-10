package com.surprising.marketmaker.provider.consumer;

import com.surprising.aeron.client.AeronRealtimeReceiver;
import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.service.MarketMakerMarkPriceCache;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.product.api.ProductLine;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 做市商极速 UDP 行情与标记价格接收器。
 * <p>
 * 架构契约：
 * 1. 纯内存、无状态进程：遵循做市商不直连存储与数据库的设计原则，零 Redis / 零 Kafka 依赖；
 * 2. 微服务环境下通过环境变量/配置中心动态注入 UDP 接入通道 (aeron:udp?endpoint=...) 或同机共享内存通道 (aeron:ipc)；
 * 3. 基于 Aeron 零拷贝环形队列极速接收 MARK 标记价、BOOK 盘口深度与 TRADE 成交帧，微秒级唤醒策略算价。
 */
@Component
@ConditionalOnProperty(name = "surprising.realtime.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class MarketMakerRealtimeReceiver implements AutoCloseable {

    private final MarketMakerProperties properties;
    private final MarketMakerMarkPriceCache markPriceCache;
    private final MakerQuoteWakeups wakeups;
    private final ObjectMapper mapper;
    private final String channel;
    private final AeronRealtimeReceiver receiver;
    private volatile boolean ready;

    public MarketMakerRealtimeReceiver(MarketMakerProperties properties,
                                      MarketMakerMarkPriceCache markPriceCache,
                                      MakerQuoteWakeups wakeups,
                                      ObjectMapper mapper,
                                      Environment env) {
        this.properties = properties;
        this.markPriceCache = markPriceCache;
        this.wakeups = wakeups;
        this.mapper = mapper;

        this.channel = env.getProperty("surprising.realtime.channel", "aeron:udp?endpoint=127.0.0.1:40130");
        String directoryPath = env.getProperty("surprising.realtime.directory",
                io.aeron.CommonContext.getAeronDirectoryName());
        int streamId = env.getProperty("surprising.realtime.stream", Integer.class, 2103);

        AeronRealtimeReceiver activeReceiver = null;
        try {
            activeReceiver = new AeronRealtimeReceiver(directoryPath, channel, streamId, this::receive, failure -> {
                log.warn("做市商 UDP 传输故障: kind={} sequence={}", failure.kind(), failure.sequence());
            });
            this.ready = true;
            log.info("做市商 UDP 行情接收器启动成功: channel={} streamId={}", channel, streamId);
        } catch (Exception ex) {
            log.warn("做市商 UDP 接收器初始化跳过 (Aeron 目录未就绪或处于轻量测试环境): {}", ex.getMessage());
            this.ready = false;
        }
        this.receiver = activeReceiver;
    }

    void receive(RealtimeFrame frame) {
        if (frame.productLine() != properties.getProductLine()) return;

        switch (frame.kind()) {
            case MARK -> handleMarkPrice(frame);
            case BOOK -> wakeups.changed(frame.productLine(), frame.instrumentId());
            case TRADE -> wakeups.changed(frame.productLine(), frame.instrumentId());
            default -> {}
        }
    }

    private void handleMarkPrice(RealtimeFrame frame) {
        try {
            MarkPriceEvent event = mapper.readValue(frame.payload(), MarkPriceEvent.class);
            if (event != null) {
                markPriceCache.update(event);
                wakeups.onMarkPriceUpdated(event);
            }
        } catch (Exception ex) {
            log.warn("做市商 UDP MARK 帧解码跳过: instrumentId={} error={}", frame.instrumentId(), ex.getMessage());
        }
    }

    public boolean isReady() {
        return ready;
    }

    public String getChannel() {
        return channel;
    }

    @Override
    @PreDestroy
    public void close() {
        ready = false;
        if (receiver != null) {
            receiver.close();
        }
    }
}
