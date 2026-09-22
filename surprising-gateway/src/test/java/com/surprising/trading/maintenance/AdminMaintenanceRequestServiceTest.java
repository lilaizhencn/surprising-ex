package com.surprising.trading.maintenance;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminMaintenanceRequestServiceTest {

    @Test
    void rejectsMissingIdentityAndCrossProductRequestsBeforeDoingBusiness() {
        var service = mock(MaintenanceService.class);
        when(service.productLine()).thenReturn(ProductLine.LINEAR_PERPETUAL);
        var requests = new AdminMaintenanceRequestService(service);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> requests.authorize(null, null, ProductLine.LINEAR_PERPETUAL))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("403");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> requests.authorize("1", "SPOT", ProductLine.SPOT))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("400");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> requests.authorize("1", null, ProductLine.LINEAR_PERPETUAL))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("400");
        verify(service, never()).list(anyLong());
    }

    @Test
    void keepsLongIdentifiersExactInJsonAndHidesSchedulerInternals() throws Exception {
        var service = mock(MaintenanceService.class);
        when(service.create(eq("1"), any())).thenAnswer(i -> new MaintenanceTask("9007199254740999", ProductLine.LINEAR_PERPETUAL, i.getArgument(1), "1", "RUNNING", "GATE", 0, 0, 0, null, "2026-09-06", "2026-09-06"));
        var mapper = new tools.jackson.databind.ObjectMapper();
        var request = mapper.readValue("{\"requestId\":\"98515364-34c9-46a9-bf6f-bbb48f330ea3\",\"symbol\":\"BTC-USDT\",\"userId\":\"9007199254740997\",\"mode\":\"LIMIT\",\"priceTicks\":\"9007199254740993\",\"reason\":\"upgrade\"}", MaintenanceRequest.class);
        var result = mapper.readTree(mapper.writeValueAsString(new AdminMaintenanceRequestService(service).create("1", request)));
        org.assertj.core.api.Assertions.assertThat(result.get("id").asText()).isEqualTo("9007199254740999");
        org.assertj.core.api.Assertions.assertThat(result.get("request").get("priceTicks").asText()).isEqualTo("9007199254740993");
        org.assertj.core.api.Assertions.assertThat(result.has("cursorUserId")).isFalse();
        org.assertj.core.api.Assertions.assertThat(result.has("step")).isFalse();
    }
}
