package com.surprising.realtime.provider.export;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import com.surprising.realtime.provider.export.config.CommittedProjectionConfiguration;
import com.surprising.realtime.provider.export.service.CommittedOrderProjectionService;

class CommittedProjectionConfigurationTest {
    @Test
    void disabledExportDoesNotCreatePersistenceWithoutADatabase() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "disabled-export", java.util.Map.of("surprising.trade-export.enabled", "false")));
            context.register(CommittedProjectionConfiguration.class);
            context.refresh();
            assertThat(context.getBeansOfType(CommittedOrderProjectionService.class)).isEmpty();
            assertThat(context.getBeansWithAnnotation(org.springframework.stereotype.Repository.class)).isEmpty();
            assertThat(context.getBeansOfType(org.springframework.transaction.support.TransactionTemplate.class)).isEmpty();
        }
    }
}
