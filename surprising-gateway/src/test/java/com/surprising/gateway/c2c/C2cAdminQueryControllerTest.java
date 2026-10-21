package com.surprising.gateway.c2c;

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
        var controller = new C2cAdminController(mock(C2cAdminAccess.class), mock(C2cAdminMarkets.class),
                mock(C2cAdminReview.class), queries, mock(C2cAdminInitialization.class), new ObjectMapper());
        var http = standaloneSetup(controller).setControllerAdvice(new C2cHttpErrors()).build();
        for (String path : List.of("merchants", "payment-accounts", "ads", "orders", "fund-intents", "appeals", "reports")) {
            for (String query : List.of("limit=0", "limit=101", "offset=-1", "offset=10001", "status=UNKNOWN")) {
                http.perform(get("/api/v1/admin/c2c/" + path + "?" + query)).andExpect(status().isBadRequest());
            }
        }
        http.perform(get("/api/v1/admin/c2c/orders?userId=-1")).andExpect(status().isBadRequest());
        verifyNoInteractions(queries);
    }
}
