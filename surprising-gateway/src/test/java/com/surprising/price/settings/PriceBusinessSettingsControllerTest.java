package com.surprising.price.settings;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import com.surprising.price.settings.controller.PriceBusinessSettingsController;
import com.surprising.price.settings.model.PriceBusinessSettings;
import com.surprising.price.settings.service.PriceBusinessSettingsService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class PriceBusinessSettingsControllerTest {
    private final PriceBusinessSettingsService settings=mock(PriceBusinessSettingsService.class);
    private final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    private final PriceBusinessSettingsController controller=new PriceBusinessSettingsController(settings,json);
    private final String valid=json.writeValueAsString(new PriceBusinessSettingsController.Update(PriceBusinessSettings.initial(),1L,"reason"));

    @Test void validatesAllRequestShapesAndExactDecimalLimitsBeforeBusinessChanges() {
        for (String bad : List.of("null","{}",valid.replace("\"expectedVersion\":1","\"expectedVersion\":\"1\""),
                valid.replace("\"expectedVersion\":1","\"expectedVersion\":1.5"),
                valid.replace("\"expectedVersion\":1","\"expectedVersion\":9223372036854775808"),
                valid.replace("\"expectedVersion\":1","\"expectedVersion\":0"),
                valid.replace("\"reason\":\"reason\"","\"reason\":\" \""),
                valid.replace("\"indexPollDelayMs\":1000","\"indexPollDelayMs\":\"1000\""),
                valid.replace("\"indexScale\":18,",""),valid.replace("\"websocketEnabled\":true","\"websocketEnabled\":\"true\""),
                valid.replace("\"fiatQuoteCurrencies\":[]","\"fiatQuoteCurrencies\":[1]"),
                valid.replace("\"fiatQuoteCurrencies\":[]","\"fiatQuoteCurrencies\":[\"USD\",\"USD\"]"),
                valid.replace("\"fiatQuoteCurrencies\":[]","\"fiatQuoteCurrencies\":[\"usd\"]"),
                valid.replace("\"markClampRatio\":0.03","\"markClampRatio\":1.000000000000000000001"),
                valid.replace("\"indexOutlierThreshold\":0.01","\"indexOutlierThreshold\":1.000000000000000000001"),
                valid.replace("\"stableEnabled\":false","\"stableEnabled\":true"),
                valid.replace("\"settings\":{","\"settings\":{\"unknown\":1,"),
                valid.replace("\"reason\":\"reason\"","\"reason\":\"reason\",\"unknown\":1"))) {
            assertThat(bad).isNotEqualTo(valid);
            assertThatThrownBy(()->controller.update("42",bad))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        }
        verifyNoInteractions(settings);
    }
    @Test void mapsVersionConflictAndPassesNativeRulesAndNormalizedReason() {
        controller.update("42",valid.replace("reason\"}","  reason  \"}"));
        verify(settings).save(PriceBusinessSettings.initial(),1L,"42","reason");
        when(settings.save(PriceBusinessSettings.initial(),1L,"42","reason"))
                .thenThrow(new OptimisticLockingFailureException("changed"));
        assertThatThrownBy(()->controller.update("42",valid)).hasMessageContaining("409");
    }
    @Test void httpBindingPreservesTheJsonContractAndRejectsPreciseOutOfRangeDecimals() throws Exception {
        var snapshot=new com.surprising.price.settings.model.PriceSettingsSnapshot(2,PriceBusinessSettings.initial(),"42","reason",java.time.Instant.now());
        when(settings.save(PriceBusinessSettings.initial(),1L,"42","reason")).thenReturn(snapshot);
        when(settings.current()).thenReturn(snapshot);
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        String path="/api/v1/price/index/admin/business-settings";
        mvc.perform(post(path)
                .header("X-Admin-User-Id","42").contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.indexPollDelayMs").value(1000))
                .andExpect(jsonPath("$.version").value(2));
        clearInvocations(settings);
        mvc.perform(post(path)
                .header("X-Admin-User-Id","42").contentType(MediaType.APPLICATION_JSON)
                .content(valid.replace("\"markClampRatio\":0.03","\"markClampRatio\":1.000000000000000000001")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(settings);
        mvc.perform(get(path).header("X-Admin-User-Id","42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.length()").value(35))
                .andExpect(jsonPath("$.config.version").value(2));
    }

}
