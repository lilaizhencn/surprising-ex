package com.surprising.marketmaker.provider.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.service.MarketMakerService;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MarketMakerTaskTest {
    @Test
    void eventsCoalesceDuringWorkAndNextQuoteDoesNotWaitForWatchdog() throws Exception {
        var properties = properties();
        var service = mock(MarketMakerService.class);
        var wakeups = new MakerQuoteWakeups(properties, new ObjectMapper());
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var second = new CountDownLatch(1);
        var count = new AtomicInteger();
        when(service.runScheduledStrategy("maker", ProductLine.LINEAR_PERPETUAL)).thenAnswer(call -> {
            if (count.incrementAndGet() == 1) { started.countDown(); release.await(); }
            else second.countDown();
            return true;
        });
        var task = new MarketMakerTask(service, properties, wakeups);
        try {
            task.start(); task.start();
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 10000; i++) wakeups.changed(ProductLine.LINEAR_PERPETUAL, "604");
            assertThat(count.get()).isEqualTo(1);
            release.countDown();
            assertThat(second.await(1, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            assertThat(count.get()).isEqualTo(2);
        } finally { release.countDown(); task.stop(); }
    }

    @Test
    void unrelatedProductsAndInstrumentsDoNotWakeQuoteWorker() throws Exception {
        var properties = properties();
        var service = mock(MarketMakerService.class);
        var wakeups = new MakerQuoteWakeups(properties, new ObjectMapper());
        var entered = new CountDownLatch(1);
        when(service.runScheduledStrategy(anyString(), any())).thenAnswer(call -> { entered.countDown(); return true; });
        var task = new MarketMakerTask(service, properties, wakeups);
        try {
            task.start(); assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            wakeups.changed(ProductLine.INVERSE_PERPETUAL, "604");
            wakeups.changed(ProductLine.LINEAR_PERPETUAL, "653");
            Thread.sleep(150);
            verify(service, times(1)).runScheduledStrategy("maker", ProductLine.LINEAR_PERPETUAL);
        } finally { task.stop(); }
    }

    @Test
    void missingEventsStillRunSafetyCheckAndStopInterruptsWait() throws Exception {
        var properties = properties();
        properties.getEngine().setQuoteWatchdogInterval(Duration.ofMillis(100));
        var service = mock(MarketMakerService.class);
        var checked = new CountDownLatch(2);
        when(service.runScheduledStrategy(anyString(), any())).thenAnswer(call -> { checked.countDown(); return true; });
        var task = new MarketMakerTask(service, properties, new MakerQuoteWakeups(properties, new ObjectMapper()));
        try { task.start(); assertThat(checked.await(2, TimeUnit.SECONDS)).isTrue(); }
        finally { task.stop(); }
    }

    @Test
    void slowStrategyDoesNotBlockAnotherStrategy() throws Exception {
        var properties = properties();
        var fast = strategy("fast", "653");
        properties.setStrategies(List.of(properties.getStrategies().getFirst(), fast));
        var service = mock(MarketMakerService.class);
        var slowStarted = new CountDownLatch(1);
        var slowStopped = new CountDownLatch(1);
        var fastRan = new CountDownLatch(1);
        when(service.runScheduledStrategy("maker", ProductLine.LINEAR_PERPETUAL)).thenAnswer(call -> {
            slowStarted.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { slowStopped.countDown(); }
            return false;
        });
        when(service.runScheduledStrategy("fast", ProductLine.LINEAR_PERPETUAL)).thenAnswer(call -> { fastRan.countDown(); return true; });
        var task = new MarketMakerTask(service, properties, new MakerQuoteWakeups(properties, new ObjectMapper()));
        try {
            task.start(); assertThat(slowStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(fastRan.await(2, TimeUnit.SECONDS)).isTrue();
        } finally { task.stop(); }
        assertThat(slowStopped.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void committedTradeWakesOnlyItsProductAndSymbol() throws Exception {
        var properties = properties(); var mapper = new ObjectMapper();
        var wakeups = new MakerQuoteWakeups(properties, mapper);
        var service = mock(MarketMakerService.class);
        var first = new CountDownLatch(1); var second = new CountDownLatch(1); var calls = new AtomicInteger();
        when(service.runScheduledStrategy(anyString(), any())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) first.countDown(); else second.countDown(); return true;
        });
        var task = new MarketMakerTask(service, properties, wakeups);
        try {
            task.start(); assertThat(first.await(3, TimeUnit.SECONDS)).isTrue();
            String payload = mapper.writeValueAsString(new com.surprising.trading.api.model.PublicTradeEvent(
                    "trade", 1, "604", com.surprising.trading.api.model.OrderSide.BUY, 100, 1, java.time.Instant.now(), "trace"));
            String topic = com.surprising.product.api.ProductTopicNames.of(ProductLine.LINEAR_PERPETUAL).matchTradesTopic();
            wakeups.onTrade(new org.apache.kafka.clients.consumer.ConsumerRecord<>(topic, 0, 1, "653", payload));
            Thread.sleep(100); assertThat(calls.get()).isEqualTo(1);
            wakeups.onTrade(new org.apache.kafka.clients.consumer.ConsumerRecord<>(topic, 0, 2, "604", payload));
            assertThat(second.await(1, TimeUnit.SECONDS)).isTrue();
        } finally { task.stop(); }
    }

    private MarketMakerProperties properties() {
        var properties = new MarketMakerProperties();
        properties.setStrategies(List.of(strategy("maker", "604")));
        properties.getEngine().setQuoteWatchdogInterval(Duration.ofSeconds(30));
        return properties;
    }
    private MarketMakerProperties.Strategy strategy(String id, String instrument) {
        var strategy = new MarketMakerProperties.Strategy();
        strategy.setStrategyId(id); strategy.setProductLine(ProductLine.LINEAR_PERPETUAL);
        strategy.setInstrumentIds(List.of(instrument)); return strategy;
    }
}
