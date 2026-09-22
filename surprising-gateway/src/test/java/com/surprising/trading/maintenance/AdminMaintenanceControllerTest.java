package com.surprising.trading.maintenance;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminMaintenanceControllerTest {
    @Test void rejectsMissingIdentityAndCrossProductRequestsBeforeDoingBusiness() throws Exception {
        var service=mock(MaintenanceService.class);
        when(service.productLine()).thenReturn(ProductLine.LINEAR_PERPETUAL);
        var mvc=MockMvcBuilders.standaloneSetup(new AdminMaintenanceController(service)).build();
        String path="/api/v1/admin/trading/orders/maintenance";
        mvc.perform(get(path).param("productLine","LINEAR_PERPETUAL")).andExpect(status().isForbidden());
        mvc.perform(get(path).param("productLine","SPOT").header("X-Admin-User-Id","1").header("X-Product-Line","SPOT")).andExpect(status().isBadRequest());
        mvc.perform(get(path).param("productLine","LINEAR_PERPETUAL").header("X-Admin-User-Id","1")).andExpect(status().isBadRequest());
        verify(service,never()).list(anyLong());
    }
    @Test void keepsLongIdentifiersExactOverHttpAndHidesSchedulerInternals() throws Exception {
        var service=mock(MaintenanceService.class);
        when(service.productLine()).thenReturn(ProductLine.LINEAR_PERPETUAL);
        when(service.create(eq("1"),any())).thenAnswer(i -> new MaintenanceTask("9007199254740999",ProductLine.LINEAR_PERPETUAL,
                i.getArgument(1),"1","RUNNING","GATE",0,0,0,null,"2026-09-06","2026-09-06"));
        var mvc=MockMvcBuilders.standaloneSetup(new AdminMaintenanceController(service)).build();
        mvc.perform(post("/api/v1/admin/trading/orders/maintenance").param("productLine","LINEAR_PERPETUAL")
                .header("X-Admin-User-Id","1").header("X-Product-Line","LINEAR_PERPETUAL")
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"98515364-34c9-46a9-bf6f-bbb48f330ea3","symbol":"BTC-USDT","userId":"9007199254740997","mode":"LIMIT","priceTicks":"9007199254740993","reason":"upgrade"}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("9007199254740999"))
                .andExpect(jsonPath("$.request.userId").value("9007199254740997"))
                .andExpect(jsonPath("$.request.priceTicks").value("9007199254740993"))
                .andExpect(jsonPath("$.cursorUserId").doesNotExist()).andExpect(jsonPath("$.step").doesNotExist());
    }
}
