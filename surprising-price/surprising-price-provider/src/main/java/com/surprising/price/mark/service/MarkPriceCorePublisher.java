package com.surprising.price.mark.service;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.mark.config.MarkPriceProperties;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public final class MarkPriceCorePublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MarkPriceCorePublisher.class);
    private static final long FAILURE_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final AeronClientPool clients;
    private final Transport transport;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, MarkPriceEvent> pendingBySymbol = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, MarkPriceEvent> inFlightByCommandId = new ConcurrentHashMap<>();
    private final AeronClientPool.CommandAdmissionCallback admissionCallback = this::onAdmission;
    private final AtomicLong publishGeneration = new AtomicLong();
    private final AtomicLong nextFailureLogNanos = new AtomicLong();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Autowired
    public MarkPriceCorePublisher(MarkPriceProperties properties) {
        this(new AeronClientPool("price-mark", properties.getKafka().getProductLine(),
                properties.getAeron().getHostnames(), properties.getAeron().getEgressHostname(),
                properties.getAeron().getResponseTimeout(), properties.getAeron().getClientConnections(),
                "price-mark-core"));
    }

    MarkPriceCorePublisher(Transport transport) {
        this.clients = null;
        this.transport = Objects.requireNonNull(transport, "transport");
        this.executor = newExecutor();
    }

    private MarkPriceCorePublisher(AeronClientPool clients) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.transport = null;
        this.executor = newExecutor();
    }

    public void publish(MarkPriceEvent event) {
        if (event == null || closed.get()) {
            return;
        }
        Objects.requireNonNull(event.publishedAt(), "mark price publishedAt is required");
        pendingBySymbol.merge(event.symbol(), event,
                (current, candidate) -> candidate.sequence() > current.sequence() ? candidate : current);
        publishGeneration.incrementAndGet();
        scheduleDrain();
    }

    int pendingCount() {
        return pendingBySymbol.size();
    }

    private void scheduleDrain() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::drain);
        } catch (RuntimeException exception) {
            draining.set(false);
            log.error("Unable to schedule mark price Aeron publication", exception);
        }
    }

    private void drain() {
        long generationAtStart = publishGeneration.get();
        try {
            for (Map.Entry<String, MarkPriceEvent> entry : pendingBySymbol.entrySet()) {
                MarkPriceEvent event = entry.getValue();
                if (clients != null) {
                    try {
                        if (!sendAsync(event) && shouldLogFailure()) {
                            log.warn("Mark price remains pending symbol={} sequence={} pendingSymbols={}",
                                    event.symbol(), event.sequence(), pendingBySymbol.size());
                        }
                    } catch (RuntimeException exception) {
                        log.error("Failed to send mark price to Aeron symbol={} sequence={}",
                                event.symbol(), event.sequence(), exception);
                    }
                    continue;
                }
                boolean sent;
                try {
                    sent = transport.trySend(event);
                } catch (RuntimeException exception) {
                    sent = false;
                    log.error("Failed to send mark price to Aeron symbol={} sequence={}",
                            event.symbol(), event.sequence(), exception);
                }
                if (sent) {
                    pendingBySymbol.remove(entry.getKey(), event);
                } else if (shouldLogFailure()) {
                    log.warn("Mark price remains pending symbol={} sequence={} pendingSymbols={}",
                            event.symbol(), event.sequence(), pendingBySymbol.size());
                }
            }
        } finally {
            draining.set(false);
            if (publishGeneration.get() != generationAtStart && !pendingBySymbol.isEmpty()) {
                scheduleDrain();
            }
        }
    }

    private boolean sendAsync(MarkPriceEvent event) {
        byte[] payload = TradingCommandCodec.encodeApplyMarkPrice(toCommand(event));
        UUID commandId = markPriceCommandId(event);
        if (inFlightByCommandId.putIfAbsent(commandId, event) != null) {
            return true;
        }
        boolean queued = clients.commandOneWay(CoreMessageType.APPLY_MARK_PRICE, commandId, 0, payload,
                admissionCallback);
        if (!queued) {
            inFlightByCommandId.remove(commandId, event);
        }
        return queued;
    }

    static ApplyMarkPriceCommand toCommand(MarkPriceEvent event) {
        long publishedAt = Objects.requireNonNull(event.publishedAt(),
                "mark price publishedAt is required").toEpochMilli();
        if (event.productLine() != com.surprising.product.api.ProductLine.OPTION) {
            return new ApplyMarkPriceCommand(event.symbol(), event.instrumentVersion(),
                    event.markPriceTicks(), event.sequence(), publishedAt);
        }
        return new ApplyMarkPriceCommand(event.symbol(), event.instrumentVersion(), event.markPriceTicks(),
                priceTicks(event, event.indexPrice()), priceTicks(event, event.sameExpiryForwardPrice()),
                event.sequence(), publishedAt);
    }

    private static long priceTicks(MarkPriceEvent event, java.math.BigDecimal price) {
        if (price == null || price.signum() <= 0 || event.markPrice() == null
                || event.markPrice().signum() <= 0) {
            throw new IllegalArgumentException("index and forward prices are required");
        }
        return price.multiply(java.math.BigDecimal.valueOf(event.markPriceTicks()))
                .divide(event.markPrice(), 0, RoundingMode.HALF_UP).longValueExact();
    }

    private void onAdmission(UUID commandId, AeronClientPool.TryCommandResult result) {
        MarkPriceEvent event = inFlightByCommandId.remove(commandId);
        if (event == null) {
            return;
        }
        if (result == AeronClientPool.TryCommandResult.SENT) {
            pendingBySymbol.remove(event.symbol(), event);
        } else if (shouldLogFailure()) {
            log.warn("Mark price Aeron admission failed symbol={} sequence={} result={}",
                    event.symbol(), event.sequence(), result);
        }
    }

    private static UUID markPriceCommandId(MarkPriceEvent event) {
        return UUID.nameUUIDFromBytes(("MARK_PRICE:" + event.symbol() + ':' + event.sequence())
                .getBytes(StandardCharsets.UTF_8));
    }

    private static ExecutorService newExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), runnable -> {
            Thread thread = new Thread(runnable, "price-mark-core-publisher");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    private boolean shouldLogFailure() {
        long now = System.nanoTime();
        long next = nextFailureLogNanos.get();
        return now >= next && nextFailureLogNanos.compareAndSet(next, now + FAILURE_LOG_INTERVAL_NANOS);
    }

    @Override
    @PreDestroy
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (!pendingBySymbol.isEmpty()) {
            log.warn("Mark price publisher stopped with pending symbols={}", pendingBySymbol.size());
        }
        if (clients != null) {
            clients.close();
        } else {
            transport.close();
        }
    }

    @FunctionalInterface
    interface Transport {
        boolean trySend(MarkPriceEvent event);

        default void close() {
        }
    }
}
