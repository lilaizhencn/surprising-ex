package com.surprising.funding.provider.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.surprising.funding.api.FundingApiPaths;
import com.surprising.funding.api.utils.AdminCursorPage;
import com.surprising.funding.provider.model.FundingPageQuery;
import com.surprising.funding.provider.service.FundingRuntimeConfigService;
import com.surprising.funding.provider.service.FundingService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FundingQueryBoundaryTest {
    private final FundingService service = mock(FundingService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FundingController(
            service, mock(FundingRuntimeConfigService.class))).build();

    @ParameterizedTest @MethodSource("invalidRequests")
    void invalidQueriesNeverReachService(String path, String parameter, String value) throws Exception {
        var request = get(FundingApiPaths.API_V1 + path).header("X-Admin-User-Id", "1001").param("userId", "42");
        if (!parameter.equals("instrumentId")) request.param("instrumentId", "1");
        mvc.perform(request.param(parameter, value))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    static Stream<String[]> invalidRequests() {
        return Stream.of("/payments", "/admin/payments", "/admin/rates/history").flatMap(path -> Stream.of(
                new String[]{path, "limit", "0"}, new String[]{path, "limit", "1001"},
                new String[]{path, "cursor", "broken"}, new String[]{path, "sort", "unknown.desc"},
                new String[]{path, "instrumentId", "BTCUSDT"}, new String[]{path, "instrumentId", "2147483648"}));
    }

    @Test void publicHistoryValidatesLimitAndInstrumentBeforeService() throws Exception {
        mvc.perform(get(FundingApiPaths.API_V1 + "/rates/history").param("instrumentId", "bad"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(FundingApiPaths.API_V1 + "/rates/history").param("instrumentId", "1").param("limit", "0"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void invalidUserIsRejectedAtBothPaymentEntries() throws Exception {
        for (String path : new String[]{"/payments", "/admin/payments"}) {
            mvc.perform(get(FundingApiPaths.API_V1 + path).header("X-Admin-User-Id", "1001").param("userId", "0"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test void nativeCursorKeepsInt64TieBreakerAndSqlSortStaysOutOfRequest() throws Exception {
        long id = 9_007_199_254_740_993L;
        long time = 1_760_000_000_000L;
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString((time + ":" + id).getBytes(StandardCharsets.UTF_8));
        mvc.perform(get(FundingApiPaths.API_V1 + "/admin/rates/history").header("X-Admin-User-Id", "1001")
                .param("instrumentId", " 1 ").param("limit", "1000").param("cursor", cursor).param("sort", "eventTime.asc"))
                .andExpect(status().isOk());
        verify(service).rateHistory("1", new FundingPageQuery(1000, false, new AdminCursorPage.Cursor(Instant.ofEpochMilli(time), id)));
        mvc.perform(get(FundingApiPaths.API_V1 + "/payments").param("userId", "42").param("instrumentId", " "))
                .andExpect(status().isOk());
        verify(service).payments(42L, null, new FundingPageQuery(100, true, null));
    }
}
