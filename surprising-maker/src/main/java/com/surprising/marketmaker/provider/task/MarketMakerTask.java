package com.surprising.marketmaker.provider.task;

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
    // 只持有本服务工作线程；报价和资金状态仍由原服务及交易核心拥有。
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean();

    public MarketMakerTask(MarketMakerService service, MarketMakerProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        for (MarketMakerProperties.Strategy strategy : properties.getStrategies()) {
            startWorker(strategy, false);
            if (properties.getTrade().isEnabled()) startWorker(strategy, true);
        }
    }

    private void startWorker(MarketMakerProperties.Strategy strategy, boolean taking) {
        workers.submit(() -> {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                boolean completed = false;
                try {
                    completed = taking
                            ? service.runScheduledTrades(strategy.getStrategyId(), strategy.getProductLine())
                            : service.runScheduledStrategy(strategy.getStrategyId(), strategy.getProductLine());
                } catch (RuntimeException ex) {
                    log.warn("Market-maker worker failed strategyId={} taking={}", strategy.getStrategyId(), taking, ex);
                }
                // 正常报价/吃单不设间隔。暂停、无租约或失败时退让，防止空转和重试风暴。
                if (!completed) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        workers.shutdownNow();
    }
}
