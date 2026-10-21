package com.surprising.gateway.provider.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.surprising.gateway.c2c.config.C2cRuntime;
import com.surprising.gateway.c2c.service.C2cSettlement;
import com.surprising.gateway.c2c.task.C2cSettlementTask;
import com.surprising.trading.order.service.InstrumentCoreSyncService;
import com.surprising.trading.order.task.InstrumentCoreSyncTask;
import com.surprising.websocket.provider.service.OpenInterestPublisher;
import com.surprising.websocket.provider.service.RealtimeWebSocketBridge;
import com.surprising.websocket.provider.task.OpenInterestPublishTask;
import com.surprising.websocket.provider.task.RealtimeWebSocketLeaseTask;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class ScheduledBusinessTasksContextTest {
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling { }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void actualSchedulerInvokesBusinessOwnersOnlyWhenEnabled(boolean enabled) throws Exception {
        var settlement = mock(C2cSettlement.class);
        var instruments = mock(InstrumentCoreSyncService.class);
        var openInterest = mock(OpenInterestPublisher.class);
        var bridge = mock(RealtimeWebSocketBridge.class);
        var instrumentTick = new CountDownLatch(1);
        var interestTick = new CountDownLatch(1);
        var settlementTick = new CountDownLatch(1);
        var leaseTick = new CountDownLatch(1);
        doAnswer(call -> { instrumentTick.countDown(); return null; }).when(instruments).reconcile();
        doAnswer(call -> { interestTick.countDown(); return null; }).when(openInterest).publish();
        doAnswer(call -> { settlementTick.countDown(); return null; }).when(settlement).settle();
        doAnswer(call -> { leaseTick.countDown(); return null; }).when(bridge).heartbeat();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "surprising.c2c.enabled", enabled, "surprising.realtime.enabled", enabled)));
            context.registerBean("appMediaDriver", Object.class, Object::new);
            context.registerBean(C2cSettlement.class, () -> settlement);
            context.registerBean(InstrumentCoreSyncService.class, () -> instruments);
            context.registerBean(OpenInterestPublisher.class, () -> openInterest);
            context.registerBean(RealtimeWebSocketBridge.class, () -> bridge);
            context.registerBean("taskScheduler", ThreadPoolTaskScheduler.class, () -> {
                var scheduler = new ThreadPoolTaskScheduler();
                scheduler.setPoolSize(1);
                scheduler.setThreadNamePrefix("mvc-task-test-");
                scheduler.setDaemon(true);
                return scheduler;
            });
            context.register(Scheduling.class, C2cRuntime.class, C2cSettlementTask.class,
                    InstrumentCoreSyncTask.class, OpenInterestPublishTask.class, RealtimeWebSocketLeaseTask.class);
            context.refresh();
            assertThat(context.getBeanNamesForType(C2cSettlementTask.class)).hasSize(enabled ? 1 : 0);
            assertThat(context.getBeanNamesForType(RealtimeWebSocketLeaseTask.class)).hasSize(enabled ? 1 : 0);
            var scheduled = context.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class);
            assertThat(scheduled.getScheduledTasks()).hasSize(enabled ? 4 : 2);
            // Waiting must not invoke a synchronized Mockito method: timeout verification
            // would hold the same monitor that the scheduler needs to call reconcile/heartbeat.
            assertThat(instrumentTick.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(interestTick.await(5, TimeUnit.SECONDS)).isTrue();
            verify(instruments, atLeastOnce()).reconcile();
            verify(openInterest, atLeastOnce()).publish();
            if (enabled) {
                assertThat(settlementTick.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(leaseTick.await(5, TimeUnit.SECONDS)).isTrue();
                verify(settlement, atLeastOnce()).settle();
                verify(bridge, atLeastOnce()).heartbeat();
            } else {
                verifyNoInteractions(settlement, bridge);
            }
        }
    }
}
