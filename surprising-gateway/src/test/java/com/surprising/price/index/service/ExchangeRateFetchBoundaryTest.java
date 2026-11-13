package com.surprising.price.index.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import com.surprising.price.index.client.FiatRateClient;
import com.surprising.price.index.client.ExternalSpotPriceClient;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.repository.ExchangeRateRepository;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class ExchangeRateFetchBoundaryTest {
    @Test
    void actualHttpFetchKeepsExactDecimalInverseAndBusinessCache() throws Exception {
        var properties = new IndexPriceProperties();
        properties.getFiat().setQuoteCurrencies(List.of("CNY", "EUR", "JPY"));
        var repository = mock(ExchangeRateRepository.class);
        var cache = new ExchangeRateSnapshotCache();
        cache.restore(List.of());
        var seen = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/USD", exchange -> {
            seen.set(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + exchange.getRequestHeaders().getFirst("User-Agent"));
            byte[] bytes = ("{\"result\":\"success\",\"provider\":\"actual-provider\","
                    + "\"time_last_update_unix\":1700000000,\"rates\":{"
                    + "\"CNY\":\"7.123456789012345678\",\"EUR\":null,\"JPY\":\"0\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            properties.getFiat().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getFiat().setPath("/{base}");
            properties.getHttp().setUserAgent("mvc-rate-boundary");
            var service = new ExchangeRateService(properties, repository, mock(ExternalSpotPriceClient.class),
                    new FiatRateClient(properties, new ObjectMapper()), cache);
            service.refreshFiatRates();
            assertThat(seen.get()).isEqualTo("GET /USD mvc-rate-boundary");
            var direct = service.latest("USD", "CNY");
            assertThat(direct.rate()).isEqualByComparingTo("7.123456789012345678");
            assertThat(direct.provider()).isEqualTo("actual-provider");
            assertThat(direct.rateTime()).isEqualTo(java.time.Instant.ofEpochSecond(1700000000));
            assertThat(service.latest("CNY", "USD").rate()).isEqualByComparingTo(
                    BigDecimal.ONE.divide(direct.rate(), 18, java.math.RoundingMode.HALF_UP));
            assertThat(service.convert(new BigDecimal("9007199254740993"), "USD", "CNY").convertedAmount())
                    .isEqualByComparingTo(new BigDecimal("9007199254740993").multiply(direct.rate())
                            .setScale(18, java.math.RoundingMode.HALF_UP));
            verify(repository).upsert(eq("USD"), eq("CNY"), eq(direct.rate()), eq("actual-provider"),
                    eq(direct.rateTime()), any());
            assertThat(service.rates("USD").rates()).extracting(x -> x.quoteCurrency()).containsExactly("CNY", "USD");
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings={"503|{\"result\":\"success\"}", "200|{\"result\":\"error\"}", "200|invalid-json"})
    void failedWireResponseNeverOverwritesExistingRates(String response) throws Exception {
        var properties = new IndexPriceProperties();
        var repository = mock(ExchangeRateRepository.class);
        var cache = new ExchangeRateSnapshotCache();
        var old = new com.surprising.price.api.model.ExchangeRateResponse("USD", "CNY", new BigDecimal("7.1"),
                "previous", java.time.Instant.now(), java.time.Instant.now());
        cache.restore(List.of(old));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes=response.substring(4).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(Integer.parseInt(response.substring(0,3)), bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            properties.getFiat().setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
            properties.getFiat().setPath("/");
            var service=new ExchangeRateService(properties,repository,mock(ExternalSpotPriceClient.class),
                    new FiatRateClient(properties,new ObjectMapper()),cache);
            service.refreshFiatRates();
            assertThat(service.latest("USD","CNY")).isEqualTo(old);
            verifyNoInteractions(repository);
        } finally {server.stop(0);}
    }
}
