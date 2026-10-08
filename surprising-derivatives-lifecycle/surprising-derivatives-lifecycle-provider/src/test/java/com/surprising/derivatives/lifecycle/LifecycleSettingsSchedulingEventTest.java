package com.surprising.derivatives.lifecycle;

import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.risk.provider.config.RiskProperties;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LifecycleSettingsSchedulingEventTest {
    @Test
    void publishesOnlyNewVersionsAfterInstallingFundingIntervals() {
        var jdbc = mock(JdbcTemplate.class);
        var events = mock(ApplicationEventPublisher.class);
        var funding = new FundingProperties();
        var beans = new StaticListableBeanFactory(Map.of("funding", funding));
        var service = new LifecycleBusinessSettingsService(jdbc, JsonMapper.builder().build(), new RiskProperties(),
                beans.getBeanProvider(FundingProperties.class), new LiquidationProperties(), new InsuranceProperties(),
                new AdlProperties(), events);
        var value = new LifecycleBusinessSettingsService.Settings(1, LifecycleBusinessSettings.initial(), "SYSTEM", "initial", Instant.now());
        doReturn(value).when(jdbc).queryForObject(anyString(), any(RowMapper.class), any(Object[].class));
        doAnswer(call -> { assertThat(funding.getSettlement().getSettleDelayMs()).isEqualTo(1000); return null; })
                .when(events).publishEvent(value);
        service.reload();
        verify(events).publishEvent(value);
        service.reload();
        verifyNoMoreInteractions(events);
        assertThat(service.current()).isSameAs(value);
    }
}
