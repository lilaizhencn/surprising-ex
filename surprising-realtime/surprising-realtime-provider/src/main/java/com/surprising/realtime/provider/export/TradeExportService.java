package com.surprising.realtime.provider.export;

import com.surprising.candlestick.provider.config.CandlestickProperties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Owns the export thread; failures restart only replay, never Router or Kafka Streams. */
@Component("tradeExport")
@ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TradeExportProperties.class)
public class TradeExportService implements SmartLifecycle, HealthIndicator {
    private static final Logger log = LoggerFactory.getLogger(TradeExportService.class);
    private final CommittedTradeExporter exporter;
    private final TradeExportProperties config;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile String state = "STOPPED";
    private volatile Thread worker;

    @org.springframework.beans.factory.annotation.Autowired
    public TradeExportService(TradeExportProperties config, CandlestickProperties candles) {
        this(config, new CommittedTradeExporter(config, candles.getKafka().getProductLine(),
                candles.getKafka().getBootstrapServers()));
    }

    TradeExportService(TradeExportProperties config, CommittedTradeExporter exporter) {
        this.config = config;
        this.exporter = exporter;
    }

    @Override public synchronized void start() {
        if (worker != null && worker.isAlive()) return;
        running.set(true);
        state = "STARTING";
        worker = new Thread(this::exportUntilStopped, "committed-trade-export");
        worker.start();
    }

    private void exportUntilStopped() {
        try {
            while (running.get()) {
                try {
                    exporter.run(running, ready -> state = ready ? "RUNNING" : "WAITING_FOR_CORE", Long.MAX_VALUE);
                } catch (Exception failure) {
                    state = "RETRYING";
                    log.error("Committed trade export failed; retrying from durable checkpoint", failure);
                }
                if (running.get()) LockSupport.parkNanos(config.retryInterval().toNanos());
            }
        } finally {
            running.set(false);
            state = "STOPPED";
        }
    }

    @Override public synchronized void stop() {
        running.set(false);
        Thread current = worker;
        if (current == null) return;
        // Do not interrupt a Kafka commit or snapshot write. Replay stops at a complete message.
        LockSupport.unpark(current);
        try {
            current.join(90_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping trade export", interrupted);
        }
        if (current.isAlive()) throw new IllegalStateException("Trade export shutdown timed out");
    }

    @Override public boolean isRunning() { return worker != null && worker.isAlive(); }

    // Stop before the other Spring workers; Core/Archive must remain up until this completes.
    @Override public int getPhase() { return Integer.MAX_VALUE; }

    @Override public Health health() {
        return ("RUNNING".equals(state) && isRunning() ? Health.up() : Health.down())
                .withDetail("state", state).build();
    }
}
