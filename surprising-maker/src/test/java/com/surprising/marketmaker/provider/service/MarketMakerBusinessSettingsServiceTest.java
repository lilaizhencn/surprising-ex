package com.surprising.marketmaker.provider.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.marketmaker.provider.config.*;
import com.surprising.marketmaker.provider.repository.MarketMakerBusinessSettingsStore;
import com.surprising.product.api.ProductLine;
import jakarta.validation.Validation;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MarketMakerBusinessSettingsServiceTest {
    @Test
    void committedSettingsApplyImmediatelyAndStaleUpdatesCannotChangeRunningSettings() {
        var properties = new MarketMakerProperties();
        properties.setProductLine(ProductLine.LINEAR_PERPETUAL);
        properties.getEngine().setNodeId("process-local-node");
        var store = mock(MarketMakerBusinessSettingsStore.class);
        var settings = MarketMakerBusinessSettings.initialDisabled();
        settings.quoting().setOrderLevels(7);
        var json = JsonMapper.builder().findAndAddModules().build();
        var committed = json.readValue(json.writeValueAsBytes(settings), MarketMakerBusinessSettings.class);
        when(store.save(eq(ProductLine.LINEAR_PERPETUAL), same(settings), eq(1L), eq("1"), eq("change")))
                .thenReturn(new MarketMakerBusinessSettingsStore.Settings(committed, 2, "1", "change", Instant.now()));
        when(store.save(eq(ProductLine.LINEAR_PERPETUAL), same(settings), eq(1L), eq("1"), eq("stale")))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT));
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var service = new MarketMakerBusinessSettingsService(properties, store, validation.getValidator());
            service.save(settings, 1, "1", "change");
            assertThat(properties.getQuoting().getOrderLevels()).isEqualTo(7);
            assertThat(properties.getEngine().getNodeId()).isEqualTo("process-local-node");
            settings.quoting().setOrderLevels(8);
            assertThat(properties.getQuoting().getOrderLevels()).isEqualTo(7);
            assertThatThrownBy(() -> service.save(settings, 1, "1", "stale"))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThat(properties.getQuoting().getOrderLevels()).isEqualTo(7);
        }
    }

    @Test
    void rejectsInvalidBoundsWithoutWritingSettings() {
        var properties = new MarketMakerProperties();
        properties.setProductLine(ProductLine.LINEAR_PERPETUAL);
        var store = mock(MarketMakerBusinessSettingsStore.class);
        var settings = MarketMakerBusinessSettings.initialDisabled();
        settings.quoting().setOrderLevels(51);
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var service = new MarketMakerBusinessSettingsService(properties, store, validation.getValidator());
            assertThatThrownBy(() -> service.save(settings, 1, "1", "bad bound")).isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(store);
        }
    }

    @Test
    void settingsCopyKeepsDurationsAndDoesNotStoreNodeIdentity() {
        var settings = MarketMakerBusinessSettings.initialDisabled();
        settings.engine().setNodeId("must-stay-in-environment");
        var json = JsonMapper.builder().findAndAddModules().build();
        String encoded = json.writeValueAsString(settings);
        assertThat(encoded).doesNotContain("nodeId", "must-stay-in-environment").contains("PT1S");
        var decoded = json.readValue(encoded, MarketMakerBusinessSettings.class);
        assertThat(decoded.engine().getQuoteWatchdogInterval()).isEqualTo(java.time.Duration.ofSeconds(1));
        assertThat(decoded.engine().isEnabled()).isFalse();
    }
}
