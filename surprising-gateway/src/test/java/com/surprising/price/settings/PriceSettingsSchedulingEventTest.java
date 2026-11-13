package com.surprising.price.settings;

import com.surprising.price.settings.model.PriceBusinessSettings;
import com.surprising.price.settings.model.PriceSettingsSnapshot;
import com.surprising.price.settings.repository.PriceBusinessSettingsRepository;
import com.surprising.price.settings.service.PriceBusinessSettingsService;

import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.mark.config.MarkPriceProperties;
import java.time.Instant;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PriceSettingsSchedulingEventTest {
    @Test
    void publishesOnlyInstalledVersionsAfterRuntimeIntervalsAreUpdated() {
        var repository = mock(PriceBusinessSettingsRepository.class);
        var events = mock(ApplicationEventPublisher.class);
        var index = new IndexPriceProperties();
        var mark = new MarkPriceProperties();
        index.getKafka().setProductLine(ProductLine.SPOT);
        mark.getKafka().setProductLine(ProductLine.SPOT);
        var json = JsonMapper.builder().build();
        var service = new PriceBusinessSettingsService(repository, json, index, mark, events);
        var first = new PriceSettingsSnapshot(1, PriceBusinessSettings.initial(), "SYSTEM", "initial", Instant.now());
        when(repository.find(ProductLine.SPOT)).thenReturn(first);
        service.reload();
        verify(events).publishEvent(first);
        service.reload();
        verifyNoMoreInteractions(events);
        var changed = (tools.jackson.databind.node.ObjectNode) json.valueToTree(first.settings());
        changed.put("indexPollDelayMs", 50);
        var next = new PriceSettingsSnapshot(2, json.treeToValue(changed,PriceBusinessSettings.class), "1", "change interval", Instant.now());
        when(repository.find(ProductLine.SPOT)).thenReturn(next);
        doAnswer(call -> { assertThat(index.getCalculation().getPollDelayMs()).isEqualTo(50); return null; })
                .when(events).publishEvent(next);
        service.reload();
        verify(events).publishEvent(next);
        assertThat(service.current().version()).isEqualTo(2);
    }
    @Test void rejectsMixedProductConfigurationBeforePersistenceOrInstallation() {
        var repository=mock(PriceBusinessSettingsRepository.class);
        var index=new IndexPriceProperties();index.getKafka().setProductLine(ProductLine.SPOT);
        var mark=new MarkPriceProperties();mark.getKafka().setProductLine(ProductLine.OPTION);
        assertThatThrownBy(()->new PriceBusinessSettingsService(repository,JsonMapper.builder().build(),index,mark,event -> {}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("product line mismatch");
        verifyNoInteractions(repository);
    }

}
