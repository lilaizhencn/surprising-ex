package com.surprising.realtime.provider.option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.surprising.realtime.provider.option.controller.OptionQuoteController;
import com.surprising.realtime.provider.option.exception.OptionQuoteException;
import com.surprising.realtime.provider.option.model.OptionQuote;
import com.surprising.realtime.provider.option.service.OptionQuoteService;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class OptionQuoteControllerTest {
    private static final String PATH = "/api/v1/candlestick/options/quote";
    private final OptionQuoteService service = mock(OptionQuoteService.class);
    private final org.springframework.test.web.servlet.MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new OptionQuoteController(service)).build();

    @Test
    void validatesCanonicalIdsBeforeCallingTheBusinessOwner() throws Exception {
        for (String id : new String[]{"bad", "0", "01", "-1", " 74", "1.0", "2147483648"}) {
            mvc.perform(get(PATH).param("instrumentId", id)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(PATH)).andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("instrumentId", "74").header("X-Product-Line", "SPOT"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }

    @Test
    void mapsExplicitBusinessReasonsAtTheHttpBoundary() throws Exception {
        when(service.quote(74)).thenThrow(OptionQuoteException.notFound());
        mvc.perform(get(PATH).param("instrumentId", "74"))
                .andExpect(status().isNotFound()).andExpect(result -> assertThat(
                        ((ResponseStatusException) result.getResolvedException()).getReason())
                        .isEqualTo("option instrument not found"));
        doThrow(OptionQuoteException.unavailable()).when(service).quote(74);
        mvc.perform(get(PATH).param("instrumentId", "74"))
                .andExpect(status().isServiceUnavailable()).andExpect(result -> assertThat(
                        ((ResponseStatusException) result.getResolvedException()).getReason())
                        .isEqualTo("OPTION_REFERENCE_UNAVAILABLE"));
    }

    @Test
    void preservesNativeQuoteFieldsAndExactInt64Strings() throws Exception {
        var now = Instant.parse("2030-01-01T00:00:00Z");
        when(service.quote(74)).thenReturn(new OptionQuote("OPTION", "74", "604", ProductLine.LINEAR_PERPETUAL,
                OptionType.CALL, now.plusSeconds(86400), Long.toString(Long.MAX_VALUE), new BigDecimal("100"),
                "BTC", "USDT", new BigDecimal("8"), new BigDecimal("100"), new BigDecimal("100"),
                BigDecimal.ZERO, now, now.plusSeconds(5), Long.toString(Long.MAX_VALUE),
                "BLACK_76_ZERO_RATE_ACT_365", BigDecimal.ONE, "PER_UNDERLYING_UNIT", null));
        mvc.perform(get(PATH).param("instrumentId", "74").header("X-Product-Line", "OPTION"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.instrumentId").value("74"))
                .andExpect(jsonPath("$.strikePriceUnits").value(Long.toString(Long.MAX_VALUE)))
                .andExpect(jsonPath("$.sourcePriceSequence").value(Long.toString(Long.MAX_VALUE)))
                .andExpect(jsonPath("$.model").value("BLACK_76_ZERO_RATE_ACT_365"))
                .andExpect(jsonPath("$.unit").value("PER_UNDERLYING_UNIT"));
    }
}
