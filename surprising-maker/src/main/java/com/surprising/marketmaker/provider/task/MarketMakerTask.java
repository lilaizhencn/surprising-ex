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
                // 本地模拟成交和报价按各自间隔推进，避免成功后无限循环挤占实时查询。
                long delayMillis = (taking ? properties.getEngine().getTradeInterval()
                        : properties.getEngine().getQuoteInterval()).toMillis();
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
        workers.shutdownNow();
    }
}
