package com.surprising.price.mark.service;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.mark.config.MarkPriceProperties;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public final class MarkPriceCorePublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MarkPriceCorePublisher.class);
    private final AeronClientPool clients;
    private final ExecutorService executor;
    private final ConcurrentLinkedQueue<MarkPriceEvent> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();

    public MarkPriceCorePublisher(MarkPriceProperties properties) {
        MarkPriceProperties.Aeron aeron = properties.getAeron();
        this.clients = new AeronClientPool("price-mark", properties.getKafka().getProductLine(),
                aeron.getHostnames(), aeron.getEgressHostname(), aeron.getResponseTimeout(),
                aeron.getClientConnections(), "price-mark-core");
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "price-mark-core-publisher");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void publish(MarkPriceEvent event) {
        if (event == null) return;
        queue.offer(event);
        scheduleDrain();
    }

    private void scheduleDrain() {
        if (draining.compareAndSet(false, true)) {
            try {
                executor.execute(this::drain);
            } catch (RuntimeException exception) {
                draining.set(false);
                log.error("Unable to schedule mark price Aeron publication", exception);
            }
        }
    }

    private void drain() {
        try {
            MarkPriceEvent event;
            while ((event = queue.poll()) != null) {
                publishWithRetry(event);
            }
        } finally {
            draining.set(false);
            if (!queue.isEmpty()) scheduleDrain();
        }
    }

    private void publishWithRetry(MarkPriceEvent event) {
        byte[] payload = TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand(
                event.symbol(), event.instrumentVersion(), event.markPriceTicks(), event.sequence(),
                java.util.Objects.requireNonNull(event.publishedAt(), "mark price publishedAt is required")
                        .toEpochMilli()));
        UUID commandId = UUID.nameUUIDFromBytes(("MARK_PRICE:" + event.symbol() + ':' + event.sequence())
                .getBytes(StandardCharsets.UTF_8));
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                clients.command(CoreMessageType.APPLY_MARK_PRICE, commandId, 0, payload);
                return;
            } catch (RuntimeException exception) {
                if (attempt == 3) {
                    log.error("Failed to publish mark price to Aeron symbol={} sequence={}",
                            event.symbol(), event.sequence(), exception);
                } else {
                    Thread.onSpinWait();
                }
            }
        }
    }

    @Override
    @PreDestroy
    public void close() {
        executor.shutdown();
        clients.close();
    }
}
