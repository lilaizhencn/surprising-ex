package com.surprising.price.index.controller;

import com.surprising.price.api.PriceApiPaths;
import com.surprising.price.api.model.ExchangeRateResponse;
import com.surprising.price.index.client.*;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.repository.ExchangeRateRepository;
import com.surprising.price.index.service.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExchangeRateInputBoundaryTest {
    static Stream<Arguments> invalidCurrencies() {
        return Stream.of("latest","rates","convert").flatMap(route->Stream.of("", " USD", "USD ", "U1", "U", "ABCDEFGHIJK").map(value->Arguments.of(route,value)));
    }
    @ParameterizedTest @MethodSource("invalidCurrencies")
    void malformedCurrencyNeverReachesBusiness(String route,String value) throws Exception {
        var service=mock(ExchangeRateService.class);var mvc=MockMvcBuilders.standaloneSetup(new ExchangeRateController(service)).build();
        var request=get(PriceApiPaths.EXCHANGE_RATE_BASE_PATH+"/"+route);
        if(route.equals("convert")) request.param("amount","1").param("fromCurrency",value).param("toCurrency","EUR");
        else {request.param("baseCurrency",value);if(route.equals("latest"))request.param("quoteCurrency","EUR");}
        mvc.perform(request).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","-0.000000000000000001"})
    void nonPositiveAmountNeverReachesBusiness(String value) throws Exception {
        var service=mock(ExchangeRateService.class);var mvc=MockMvcBuilders.standaloneSetup(new ExchangeRateController(service)).build();
        mvc.perform(get(PriceApiPaths.EXCHANGE_RATE_BASE_PATH+"/convert").param("amount",value).param("fromCurrency","USD").param("toCurrency","EUR")).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void realSnapshotUsesCanonicalCodesAndExactBridgeRounding() throws Exception {
        var properties=new IndexPriceProperties();var cache=new ExchangeRateSnapshotCache();var now=Instant.now();
        BigDecimal first=new BigDecimal("0.140123456789012345"),second=new BigDecimal("0.912345678901234567");
        cache.restore(List.of(new ExchangeRateResponse("CNY","USD",first,"wire",now,now),new ExchangeRateResponse("USD","EUR",second,"wire",now,now)));
        var service=new ExchangeRateService(properties,mock(ExchangeRateRepository.class),mock(ExternalSpotPriceClient.class),mock(FiatRateClient.class),cache);
        var mvc=MockMvcBuilders.standaloneSetup(new ExchangeRateController(service)).build();var json=tools.jackson.databind.json.JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();String amount="9007199254740993.000000000000000001";
        var result=mvc.perform(get(PriceApiPaths.EXCHANGE_RATE_BASE_PATH+"/convert").param("amount",amount).param("fromCurrency","cny").param("toCurrency","eur")).andExpect(status().isOk()).andReturn();
        var body=json.readTree(result.getResponse().getContentAsString());var rate=first.multiply(second).setScale(18,RoundingMode.HALF_UP);
        assertThat(body.path("fromCurrency").asText()).isEqualTo("CNY");assertThat(body.path("toCurrency").asText()).isEqualTo("EUR");
        assertThat(new BigDecimal(body.path("convertedAmount").asText())).isEqualByComparingTo(new BigDecimal(amount).multiply(rate).setScale(18,RoundingMode.HALF_UP));
        mvc.perform(get(PriceApiPaths.EXCHANGE_RATE_BASE_PATH+"/latest").param("baseCurrency","cny").param("quoteCurrency","usd")).andExpect(status().isOk());
        mvc.perform(get(PriceApiPaths.EXCHANGE_RATE_BASE_PATH+"/rates").param("baseCurrency","cny")).andExpect(status().isOk());
        mvc.perform(get(PriceApiPaths.EXCHANGE_RATE_BASE_PATH+"/latest").param("baseCurrency","USD").param("quoteCurrency","JPY")).andExpect(status().isNotFound());
    }
    @Test void invalidConfiguredCurrencyStillFailsInternalIntegrityCheck() {
        var properties=new IndexPriceProperties();properties.getFiat().setBaseCurrency("US1");
        var repo=mock(ExchangeRateRepository.class);var client=mock(FiatRateClient.class);
        var service=new ExchangeRateService(properties,repo,mock(ExternalSpotPriceClient.class),client,new ExchangeRateSnapshotCache());
        assertThatThrownBy(service::refreshFiatRates).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(repo,client);
    }
}
