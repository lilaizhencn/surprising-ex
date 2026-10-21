package com.surprising.marketmaker.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.surprising.marketmaker.provider.config.MarketMakerBusinessSettings;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.exception.MarketMakerSettingsConflictException;
import com.surprising.marketmaker.provider.model.MarketMakerStrategyDefinition;
import com.surprising.marketmaker.provider.service.MarketMakerBusinessSettingsService;
import com.surprising.marketmaker.provider.service.MarketMakerService;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class MarketMakerSettingsConflictTest {
    private final tools.jackson.databind.ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test void staleBusinessSettingsRemainHttp409AtTheController() throws Exception {
        var service = mock(MarketMakerBusinessSettingsService.class);
        when(service.save(any(), eq(1L), eq("1001"), eq("edit")))
                .thenThrow(new MarketMakerSettingsConflictException("做市公共设置已更新，请重新加载"));
        var properties = new MarketMakerProperties();
        properties.setProductLine(ProductLine.LINEAR_PERPETUAL);
        var mvc = MockMvcBuilders.standaloneSetup(new MarketMakerBusinessSettingsController(service, properties)).build();
        var request = new MarketMakerBusinessSettingsController.Update(MarketMakerBusinessSettings.initialDisabled(), 1, "edit");
        mvc.perform(post("/api/v1/admin/market-maker/business-settings").header("X-Admin-User-Id", "1001")
                .param("productLine", "LINEAR_PERPETUAL").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request))).andExpect(status().isConflict()).andExpect(result ->
                    assertThat(((ResponseStatusException) result.getResolvedException()).getReason())
                            .isEqualTo("做市公共设置已更新，请重新加载"));
    }

    @Test void staleStrategyDefinitionRemainsHttp409AtTheController() throws Exception {
        var service = mock(MarketMakerService.class);
        when(service.saveStrategyDefinition(any(), eq("1001"), eq("edit")))
                .thenThrow(new MarketMakerSettingsConflictException("做市配置已更新，请重新加载"));
        var mvc = MockMvcBuilders.standaloneSetup(new AdminMarketMakerController(service)).build();
        var request = new MarketMakerStrategyDefinition("test-maker", ProductLine.LINEAR_PERPETUAL, false,
                List.of(2L), List.of("604"), 10, MarginMode.CROSS, 10, 10, 1000, 100000, 3, 0, 1);
        mvc.perform(post("/api/v1/admin/market-maker/strategy-definitions").header("X-Admin-User-Id", "1001")
                .param("reason", "edit").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                .andExpect(status().isConflict()).andExpect(result ->
                    assertThat(((ResponseStatusException) result.getResolvedException()).getReason())
                            .isEqualTo("做市配置已更新，请重新加载"));
    }
}
