package com.surprising.derivatives.lifecycle;

import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.service.FundingService;
import com.surprising.funding.provider.task.FundingMaintenanceTask;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.provider.config.RiskProperties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FundingCompositionTest {
    private ApplicationContextRunner context(ProductLine line) {
        var risk = new RiskProperties(); risk.setProductLine(line);
        return new ApplicationContextRunner()
                .withUserConfiguration(FundingConfiguration.class, LifecycleSchedulingConfiguration.class)
                .withPropertyValues("surprising.risk.product-line=" + line,
                        "surprising.funding.kafka.product-line=" + line)
                .withBean(RiskProperties.class, () -> risk)
                .withBean(DerivativesAeronClient.class, () -> mock(DerivativesAeronClient.class))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(LatestMarkPriceCache.class, () -> mock(LatestMarkPriceCache.class))
                .withBean("derivativesInstrumentSnapshotCache", InstrumentSnapshotCache.class, InstrumentSnapshotCache::new)
                .withBean(ObjectMapper.class, ObjectMapper::new);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void onlyPerpetualProductsLoadFunding(ProductLine line) {
        context(line).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(InstrumentSnapshotCache.class)
                    .hasSingleBean(DerivativesAeronClient.class);
            if (line.isFundingProduct()) {
                assertThat(ctx).hasSingleBean(FundingService.class).hasSingleBean(FundingProperties.class)
                        .hasSingleBean(FundingMaintenanceTask.class).hasBean("fundingScheduler");
            } else {
                assertThat(ctx).doesNotHaveBean(FundingService.class).doesNotHaveBean(FundingProperties.class)
                        .doesNotHaveBean(FundingMaintenanceTask.class).doesNotHaveBean("fundingScheduler");
            }
        });
    }

    @Test
    void mismatchedFundingProductFailsBeforeTasksStart() {
        context(ProductLine.LINEAR_PERPETUAL)
                .withPropertyValues("surprising.funding.kafka.product-line=INVERSE_PERPETUAL")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void blockedFundingThreadsDoNotBlockLifecycle() {
        context(ProductLine.LINEAR_PERPETUAL).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var funding = ctx.getBean("fundingScheduler", ThreadPoolTaskScheduler.class);
            var lifecycle = ctx.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
            var started = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            var progressed = new CountDownLatch(1);
            Runnable block = () -> { started.countDown(); try { release.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); } };
            try {
                funding.execute(block); funding.execute(block);
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                lifecycle.execute(progressed::countDown);
                assertThat(progressed.await(2, TimeUnit.SECONDS)).isTrue();
            } finally { release.countDown(); }
        });
    }
}
