package com.surprising.trading.order.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.model.OrderBusinessSettings;
import com.surprising.trading.order.service.OrderBusinessSettingsService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class OrderBusinessSettingsRequestHandlerTest {
    private final OrderBusinessSettingsService settings=mock(OrderBusinessSettingsService.class);
    private final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    private final OrderBusinessSettingsRequestHandler controller=new OrderBusinessSettingsRequestHandler(settings,json);
    private final String valid=json.writeValueAsString(new OrderBusinessSettingsRequestHandler.Update(OrderBusinessSettings.initial(),1L,"valid"));

    @Test void rejectsCoercionOmissionsUnknownsOverflowAndInvalidLimitsBeforeBusinessChanges() {
        for (String bad : List.of("null","{}",valid.replace("10000","2.5"),
                valid.replace("\"enabled\":true","\"enabled\":\"true\""),
                valid.replace("\"risk\":{","\"risk\":{\"unknown\":1,"),
                valid.replace("\"marketMaxSlippagePpm\":10000,",""),
                valid.replace("\"claimBatchSize\":100","\"claimBatchSize\":9223372036854775807"),
                valid.replace("\"expectedVersion\":1","\"expectedVersion\":9223372036854775808"),
                valid.replace("\"expectedVersion\":1","\"expectedVersion\":\"1\""),
                valid.replace("\"expectedVersion\":1","\"expectedVersion\":0"),
                valid.replace("\"reason\":\"valid\"","\"reason\":\" \""),
                valid.replace("\"marketMaxMarkAgeMs\":5000","\"marketMaxMarkAgeMs\":0"),
                valid.replace("\"maxIntervalSeconds\":86400","\"maxIntervalSeconds\":0"))) {
            assertThat(bad).isNotEqualTo(valid);
            assertThatThrownBy(()->controller.save(ProductLine.SPOT,json.readTree(bad),"42"))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        }
        for (String admin : List.of("", "0", "-1", "user"))
            assertThatThrownBy(()->controller.save(ProductLine.SPOT,json.readTree(valid),admin)).hasMessageContaining("400");
        verifyNoInteractions(settings);
    }

    @Test void passesCompleteNativeRulesAndTrimmedReason() {
        controller.save(ProductLine.OPTION,json.readTree(valid.replace("valid","  valid  ")),"42");
        verify(settings).save(ProductLine.OPTION,OrderBusinessSettings.initial(),1L,"42","valid");
    }
}
