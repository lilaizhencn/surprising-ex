package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.controller.C2cAdminController;
import com.surprising.gateway.provider.controller.advice.GlobalGatewayExceptionHandler;
import com.surprising.gateway.c2c.service.C2cAdminAccessService;
import com.surprising.gateway.c2c.service.C2cAdminInitializationService;
import com.surprising.gateway.c2c.service.C2cAdminMarketService;
import com.surprising.gateway.c2c.service.C2cAdminReviewService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.surprising.gateway.c2c.service.C2cAdminQueryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class C2cAdminQueryControllerTest {
    @Test void rejectsMalformedPageStateAndUserBeforeAnyBusinessQuery() throws Exception {
        var queries = mock(C2cAdminQueryService.class);
        var controller = new C2cAdminController(mock(C2cAdminAccessService.class), mock(C2cAdminMarketService.class),
                mock(C2cAdminReviewService.class), queries, mock(C2cAdminInitializationService.class), new ObjectMapper(), org.mockito.Mockito.mock(com.surprising.gateway.c2c.controller.C2cAdminRequestBinding.class));
        var http = standaloneSetup(controller).setControllerAdvice(new GlobalGatewayExceptionHandler()).build();
        for (String path : List.of("merchants", "payment-accounts", "ads", "orders", "fund-intents", "appeals", "reports")) {
            for (String query : List.of("limit=0", "limit=101", "offset=-1", "offset=10001", "status=UNKNOWN")) {
                http.perform(get("/api/v1/admin/c2c/" + path + "?" + query)).andExpect(status().isBadRequest());
            }
        }
        http.perform(get("/api/v1/admin/c2c/orders?userId=-1")).andExpect(status().isBadRequest());
        verifyNoInteractions(queries);
    }
}
