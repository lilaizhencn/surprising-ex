package com.surprising.marketmaker.provider.task;

import com.surprising.marketmaker.provider.consumer.MakerQuoteWakeups;

import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.service.MarketMakerService;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 每个配置策略一个工作线程，完成本轮后继续，避免慢币对阻塞其他币对。 */
@Component
@Slf4j
public class MarketMakerTask {
    private final MarketMakerService service;
    private final MarketMakerProperties properties;
    private final MakerQuoteWakeups wakeups;
    // 只持有本服务工作线程；报价和资金状态仍由原服务及交易核心拥有。
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean();
    private final java.util.Map<String, java.util.List<java.util.concurrent.Future<?>>> startedStrategies = new java.util.concurrent.ConcurrentHashMap<>();

    public MarketMakerTask(MarketMakerService service, MarketMakerProperties properties, MakerQuoteWakeups wakeups) {
        this.service = service;
        this.properties = properties;
        this.wakeups = wakeups;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        refreshStrategies();
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 1000)
    public void refreshStrategies() {
        if (!running.get()) return;
        var configured = service.configuredStrategies();
        var activeKeys = new java.util.HashSet<String>(configured.size());
        for (MarketMakerProperties.Strategy strategy : configured) {
            String key = strategy.getProductLine() + ":" + strategy.getStrategyId();
            activeKeys.add(key);
            startedStrategies.computeIfAbsent(key, ignored -> {
                wakeups.register(strategy);
                return java.util.List.of(startWorker(strategy, false), startWorker(strategy, true));
            });
        }
        startedStrategies.entrySet().removeIf(entry -> {
            if (activeKeys.contains(entry.getKey())) return false;
            entry.getValue().forEach(future -> future.cancel(true));
            wakeups.unregister(entry.getKey());
            return true;
        });
    }

    private java.util.concurrent.Future<?> startWorker(MarketMakerProperties.Strategy strategy, boolean taking) {
        return workers.submit(() -> {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                boolean completed = false;
                try {
                    if (!taking) wakeups.await(strategy, properties.getEngine().getQuoteWatchdogInterval());
                    if (!running.get()) break;
                    completed = taking
                            ? service.runScheduledTrades(strategy.getStrategyId(), strategy.getProductLine())
                            : service.runScheduledStrategy(strategy.getStrategyId(), strategy.getProductLine());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (RuntimeException ex) {
                    log.warn("Market-maker worker failed strategyId={} taking={}", strategy.getStrategyId(), taking, ex);
                }
                // Quotes follow events; minQuoteInterval coalesces bursty WS updates, and watchdog recovers missed notifications.
                long delayMillis = taking
                        ? (properties.getTrade().isEnabled()
                                ? properties.getEngine().getTradeInterval().toMillis()
                                : Math.max(500L, properties.getEngine().getTradeInterval().toMillis()))
                        : properties.getEngine().getMinQuoteInterval().toMillis();
                if (!completed) delayMillis = Math.max(100, delayMillis);
                if (delayMillis > 0) {
                    try { Thread.sleep(delayMillis); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                }
            }
        });
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        startedStrategies.clear();
        workers.shutdownNow();
        wakeups.clear();
    }
}
