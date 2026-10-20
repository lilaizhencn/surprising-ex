package com.surprising.gateway.provider.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.surprising.gateway.provider.service.PortfolioIncomeService;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PortfolioIncomeControllerTest {
    private final AuthService auth = mock(AuthService.class);
    private final PortfolioIncomeService service = mock(PortfolioIncomeService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PortfolioIncomeController(auth, service)).build();

    @Test void ownerIsTakenOnlyFromAuthenticationAndResponseCannotBeCached() throws Exception {
        when(auth.authenticateBearer("Bearer own")).thenReturn(new JwtPrincipal(7, "nickname", "ACTIVE", List.of(), Instant.now().plusSeconds(60)));
        when(service.income(7)).thenReturn(new PortfolioIncomeService.Summary("USDT", "+08:00", Instant.EPOCH, Instant.EPOCH, Map.of()));
        mvc.perform(get("/api/v1/portfolio/income").header("Authorization", "Bearer own").param("userId", "8"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.currency").value("USDT"));
        verify(service).income(7); verify(service, never()).income(8);
    }
    @Test void missingAndExpiredCredentialsCannotReadFinancialHistory() throws Exception {
        when(auth.authenticateBearer(null)).thenThrow(new IllegalArgumentException("missing"));
        when(auth.authenticateBearer("Bearer expired")).thenThrow(new IllegalArgumentException("expired"));
        mvc.perform(get("/api/v1/portfolio/income")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/portfolio/income").header("Authorization", "Bearer expired")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void frozenAccountsCannotReadFinancialHistory() throws Exception {
        when(auth.authenticateBearer("Bearer frozen")).thenThrow(new IllegalStateException("frozen"));
        mvc.perform(get("/api/v1/portfolio/income").header("Authorization", "Bearer frozen")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void databaseOutageReturnsUnavailableWithoutExposingSql() throws Exception {
        when(auth.authenticateBearer("Bearer own")).thenReturn(new JwtPrincipal(7, "nickname", "ACTIVE", List.of(), Instant.now().plusSeconds(60)));
        when(service.income(7)).thenThrow(new DataAccessResourceFailureException("password=synthetic SELECT private_table"));
        mvc.perform(get("/api/v1/portfolio/income").header("Authorization", "Bearer own"))
                .andExpect(status().isServiceUnavailable()).andExpect(result -> Assertions.assertFalse(
                        result.getResponse().getErrorMessage().contains("private_table")));
    }
}
