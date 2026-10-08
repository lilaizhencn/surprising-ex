package com.surprising.price.settings;

import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.mark.config.MarkPriceProperties;
import java.time.Instant;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PriceSettingsSchedulingEventTest {
    @Test
    void publishesOnlyInstalledVersionsAfterRuntimeIntervalsAreUpdated() {
        var jdbc = mock(JdbcTemplate.class);
        var events = mock(ApplicationEventPublisher.class);
        var index = new IndexPriceProperties();
        var service = new PriceBusinessSettingsService(jdbc, JsonMapper.builder().build(), index, new MarkPriceProperties(), events);
        var values = new LinkedHashMap<String, JsonNode>();
        service.fields().forEach((key, field) -> values.put(key, field.initial().deepCopy()));
        var first = new PriceBusinessSettingsService.Snapshot(1, values, "SYSTEM", "initial", Instant.now());
        doReturn(first).when(jdbc).queryForObject(anyString(), any(RowMapper.class), any(Object[].class));
        service.reload();
        verify(events).publishEvent(first);
        service.reload();
        verifyNoMoreInteractions(events);
        var changed = new LinkedHashMap<>(values);
        changed.put("indexPollDelayMs", JsonMapper.builder().build().valueToTree(50));
        var next = new PriceBusinessSettingsService.Snapshot(2, changed, "1", "change interval", Instant.now());
        doReturn(next).when(jdbc).queryForObject(anyString(), any(RowMapper.class), any(Object[].class));
        doAnswer(call -> { assertThat(index.getCalculation().getPollDelayMs()).isEqualTo(50); return null; })
                .when(events).publishEvent(next);
        service.reload();
        verify(events).publishEvent(next);
        assertThat(service.current().version()).isEqualTo(2);
    }
}
