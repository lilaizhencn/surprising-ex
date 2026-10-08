package com.surprising.derivatives.lifecycle;

import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.task.FundingMaintenanceTask;
import com.surprising.product.api.ProductLine;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.scheduling.TaskScheduler;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LifecycleMaintenanceSchedulingTest {
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = "SPOT", mode = EnumSource.Mode.EXCLUDE)
    void usesActualDeadlinesAndKeepsFundingOnSeparatePerpetualScheduler(ProductLine product) {
        var lifecycleTasks = mock(DerivativesLifecycleMaintenanceTask.class);
        var fundingTasks = mock(FundingMaintenanceTask.class);
        var fundingProperties = new FundingProperties();
        var settings = mock(LifecycleBusinessSettingsService.class);
        var initial = LifecycleBusinessSettings.initial();
        when(settings.current()).thenReturn(snapshot(initial));
        List<Scheduled> lifecycle = new ArrayList<>(), funding = new ArrayList<>();
        var beans = new StaticListableBeanFactory(product.isFundingProduct()
                ? Map.of("tasks", fundingTasks, "properties", fundingProperties, "scheduler", scheduler(funding))
                : Map.of());
        var owner = new LifecycleMaintenanceScheduling(scheduler(lifecycle), lifecycleTasks, settings,
                beans.getBeanProvider(FundingMaintenanceTask.class), beans.getBeanProvider(FundingProperties.class),
                beans.getBeanProvider(TaskScheduler.class));
        owner.start();
        try {
            assertThat(lifecycle).hasSize(3);
            assertThat(funding).hasSize(product.isFundingProduct() ? 2 : 0);
            for (int i = 0; i < 3; i++) lifecycle.get(i).task().run();
            verify(lifecycleTasks).processLiquidationWork();
            verify(lifecycleTasks).coverInsuranceDeficits();
            verify(lifecycleTasks).processAdlDeficits();
            var changed = new LifecycleBusinessSettings(initial.funding(), initial.liquidation(),
                    new LifecycleBusinessSettings.Insurance(true, 50, 100), initial.adl());
            when(settings.current()).thenReturn(snapshot(changed));
            owner.settingsInstalled(snapshot(changed));
            assertThat(lifecycle).hasSize(7);
            verify(lifecycle.get(4).future()).cancel(false);
            lifecycle.get(4).task().run();
            verify(lifecycleTasks, times(1)).coverInsuranceDeficits();
            lifecycle.getLast().task().run();
            verify(lifecycleTasks, times(2)).coverInsuranceDeficits();
            if (product.isFundingProduct()) {
                for (int i = 0; i < 2; i++) funding.get(i).task().run();
                verify(fundingTasks).publishRates();
                verify(fundingTasks).settleDueRates();
                fundingProperties.getSettlement().setSettleDelayMs(50);
                owner.settingsInstalled(snapshot(changed));
                assertThat(funding).hasSize(5);
                verify(funding.get(3).future()).cancel(false);
            }
        } finally { owner.stop(); }
        int stopped = lifecycle.size();
        lifecycle.getLast().task().run();
        assertThat(lifecycle).hasSize(stopped);
    }

    private static LifecycleBusinessSettingsService.Settings snapshot(LifecycleBusinessSettings value) {
        return new LifecycleBusinessSettingsService.Settings(1, value, "SYSTEM", "schedule test", Instant.now());
    }
    private record Scheduled(Runnable task, ScheduledFuture<?> future) {}
    private static TaskScheduler scheduler(List<Scheduled> tasks) {
        var scheduler = mock(TaskScheduler.class);
        when(scheduler.getClock()).thenReturn(Clock.systemUTC());
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(call -> {
            var future = mock(ScheduledFuture.class);
            tasks.add(new Scheduled(call.getArgument(0), future));
            return future;
        });
        return scheduler;
    }
}
