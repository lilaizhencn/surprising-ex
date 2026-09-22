package com.surprising.derivatives.lifecycle;

import com.surprising.funding.provider.service.FundingService;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Production component scan, with only external I/O and background execution replaced. */
class LifecycleApplicationContextTest {
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"SPOT"}, mode = EnumSource.Mode.EXCLUDE)
    void mergedApplicationLoadsForEachDerivative(ProductLine line) {
        new WebApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer()).withUserConfiguration(SurprisingDerivativesLifecycleApplication.class)
                .withPropertyValues("surprising.risk.product-line=" + line,
                        "surprising.liquidation.product-line=" + line,
                        "surprising.insurance.kafka.product-line=" + line,
                        "surprising.adl.kafka.product-line=" + line,
                        "surprising.price.consumer.product-line=" + line,
                        "surprising.funding.kafka.product-line=" + line,
                        "surprising.funding.calculation.enabled=false",
                        "surprising.funding.settlement.enabled=false",
                        "surprising.liquidation.execution.enabled=false",
                        "surprising.insurance.coverage.enabled=false",
                        "surprising.adl.scanner.enabled=false",
                        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
                .withBean(DerivativesAeronClient.class.getName(), DerivativesAeronClient.class,
                        () -> mock(DerivativesAeronClient.class))
                .withBean(DerivativesInstrumentSnapshotInitializer.class.getName(), DerivativesInstrumentSnapshotInitializer.class,
                        () -> mock(DerivativesInstrumentSnapshotInitializer.class))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean("disableBackgroundIo", BeanPostProcessor.class, () -> new BeanPostProcessor() {
                    @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                        if (bean instanceof AbstractKafkaListenerContainerFactory<?, ?, ?> factory) factory.setAutoStartup(false);
                        return bean;
                    }
                })
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(InstrumentSnapshotCache.class)
                            .hasSingleBean(DerivativesAeronClient.class);
                    if (line.isFundingProduct()) assertThat(ctx).hasSingleBean(FundingService.class);
                    else assertThat(ctx).doesNotHaveBean(FundingService.class);
                    var mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
                    mvc.perform(get("/api/v1/funding/admin/runtime-config").header("X-Admin-User-Id", "merge-test"))
                            .andExpect(line.isFundingProduct() ? status().isOk() : status().isNotFound());
                });
    }
}
