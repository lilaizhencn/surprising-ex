package com.surprising.marketmaker.provider.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.service.MarketMakerService;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MarketMakerTaskTest {
    @Test
    void slowStrategyDoesNotHoldBackAnotherStrategyAndStopInterruptsWorkers() throws Exception {
        MarketMakerProperties properties = new MarketMakerProperties();
        MarketMakerProperties.Strategy slow = strategy("slow");
        MarketMakerProperties.Strategy fast = strategy("fast");
        properties.setStrategies(List.of(slow, fast));
        properties.getTrade().setEnabled(true);
        MarketMakerService service = mock(MarketMakerService.class);
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch slowStopped = new CountDownLatch(1);
        CountDownLatch threeTrades = new CountDownLatch(3);
        CountDownLatch threeFastCycles = new CountDownLatch(3);
        when(service.runScheduledStrategy("slow", ProductLine.LINEAR_PERPETUAL)).thenAnswer(call -> {
            slowStarted.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { slowStopped.countDown(); }
            return false;
        });
        when(service.runScheduledStrategy("fast", ProductLine.LINEAR_PERPETUAL)).thenAnswer(call -> {
            assertThat(slowStarted.await(5, TimeUnit.SECONDS)).isTrue();
            threeFastCycles.countDown();
            return threeFastCycles.getCount() > 0;
        });
        when(service.runScheduledTrades("slow", ProductLine.LINEAR_PERPETUAL)).thenAnswer(call -> {
            threeTrades.countDown();
            return threeTrades.getCount() > 0;
        });
        MarketMakerTask task = new MarketMakerTask(service, properties);
        try {
            task.start();
            task.start(); // 重复就绪通知不能创建第二组工作线程。
            assertThat(threeFastCycles.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(threeTrades.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(slowStopped.getCount()).isEqualTo(1);
            verify(service, times(1)).runScheduledStrategy("slow", ProductLine.LINEAR_PERPETUAL);
        } finally { task.stop(); }
        assertThat(slowStopped.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void successfulLocalWorkHonorsConfiguredIntervalAndStopsDuringWait() throws Exception {
        var properties = new MarketMakerProperties();
        properties.setStrategies(List.of(strategy("limited")));
        properties.getEngine().setQuoteInterval(java.time.Duration.ofSeconds(1));
        var service = mock(MarketMakerService.class);
        var entered = new CountDownLatch(1);
        when(service.runScheduledStrategy("limited", ProductLine.LINEAR_PERPETUAL)).thenAnswer(call -> {
            entered.countDown(); return true;
        });
        var task = new MarketMakerTask(service, properties);
        try {
            task.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(150);
            verify(service, times(1)).runScheduledStrategy("limited", ProductLine.LINEAR_PERPETUAL);
        } finally { task.stop(); }
    }

    private MarketMakerProperties.Strategy strategy(String id) {
        MarketMakerProperties.Strategy strategy = new MarketMakerProperties.Strategy();
        strategy.setStrategyId(id);
        strategy.setProductLine(ProductLine.LINEAR_PERPETUAL);
        return strategy;
    }
}
