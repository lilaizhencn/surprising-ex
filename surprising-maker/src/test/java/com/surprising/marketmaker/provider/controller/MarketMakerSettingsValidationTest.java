package com.surprising.marketmaker.provider.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.surprising.marketmaker.provider.config.MarketMakerBusinessSettings;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.model.MarketMakerStrategyDefinition;
import com.surprising.marketmaker.provider.service.MarketMakerBusinessSettingsService;
import com.surprising.marketmaker.provider.service.MarketMakerService;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import jakarta.validation.Validation;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class MarketMakerSettingsValidationTest {
    private final tools.jackson.databind.ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final jakarta.validation.ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
    private final MarketMakerBusinessSettingsService service = mock(MarketMakerBusinessSettingsService.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        var properties = new MarketMakerProperties();
        properties.setProductLine(ProductLine.LINEAR_PERPETUAL);
        mvc = MockMvcBuilders.standaloneSetup(new MarketMakerBusinessSettingsController(
                service, properties, validation.getValidator(), json)).build();
    }
    @AfterEach void closeValidator() { validation.close(); }

    @ParameterizedTest @MethodSource("invalidSettings")
    void rejectsMalformedSettingsBeforeCallingBusinessService(Consumer<MarketMakerBusinessSettings> invalid) throws Exception {
        var settings = settings();
        invalid.accept(settings);
        save(settings, 1, "edit", 400);
        verifyNoInteractions(service);
    }

    static Stream<Consumer<MarketMakerBusinessSettings>> invalidSettings() {
        return Stream.of(
                s -> s.quoting().setOrderLevels(51),
                s -> s.referenceMarket().setMaxAge(Duration.ZERO),
                s -> s.referenceMarket().setRequestTimeout(Duration.ofHours(1).plusNanos(1)),
                s -> s.referenceMarket().setRefreshInterval(Duration.ofMillis(-1)),
                s -> s.referenceMarket().setReconnectBackoff(Duration.ZERO),
                s -> s.quoting().setOrderReconciliationInterval(Duration.ofMillis(-1)),
                s -> s.referenceMarket().getSources().getFirst().setInstrumentId("2147483648"),
                s -> s.referenceMarket().getSources().getFirst().setUrl("ftp://market.example/book"),
                s -> s.referenceMarket().getSources().getFirst().setUrl("https://user:secret@market.example/book"),
                s -> s.referenceMarket().getSources().getFirst().setUrl("https://market.example/book#fragment"),
                s -> s.referenceMarket().getSources().getFirst().setParser("unknown"),
                s -> s.referenceMarket().getSources().getFirst().setWebSocketParser("unknown"),
                s -> s.referenceMarket().getSources().getFirst().setWebSocketUrl("https://market.example/book"),
                s -> s.referenceMarket().getSources().getFirst().setWebSocketSubscribeMessage("{broken")
        );
    }

    @Test void rejectsMissingSettingsAndNestedSectionsWithoutCallingBusinessService() throws Exception {
        save(null, 1, "edit", 400);
        save(new MarketMakerBusinessSettings(null, settings().quoting(), settings().risk(), settings().trade(), settings().referenceMarket()), 1, "edit", 400);
        verifyNoInteractions(service);
    }

    @Test void rejectsInvalidVersionAndReasonBeforeCallingBusinessService() throws Exception {
        save(settings(), 0, "edit", 400);
        save(settings(), 1, " ", 400);
        save(settings(), 1, "x".repeat(1001), 400);
        verifyNoInteractions(service);
    }

    @Test void acceptsDurationLimitsUrlTemplatesParsersAndMaximumReason() throws Exception {
        var settings = settings();
        settings.referenceMarket().setMaxAge(Duration.ofHours(1));
        settings.referenceMarket().setRefreshInterval(Duration.ZERO);
        settings.quoting().setOrderReconciliationInterval(Duration.ZERO);
        save(settings, 1, "x".repeat(1000), 200);
        verify(service).save(any(), eq(1L), eq("1001"), eq("x".repeat(1000)));
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "\t"})
    void rejectsEmptyStrategyReasonBeforeCallingBusinessService(String reason) throws Exception {
        strategy(reason, 400);
    }

    @Test void rejectsOversizedStrategyReasonBeforeCallingBusinessService() throws Exception {
        strategy("x".repeat(1001), 400);
    }

    @Test void acceptsMaximumStrategyReasonAndPassesOnlyDefinitionToService() throws Exception {
        strategy("x".repeat(1000), 200);
    }

    private void strategy(String reason, int expectedStatus) throws Exception {
        var strategies = mock(MarketMakerService.class);
        var strategyMvc = MockMvcBuilders.standaloneSetup(new AdminMarketMakerController(strategies)).build();
        var definition = new MarketMakerStrategyDefinition("test-maker", ProductLine.LINEAR_PERPETUAL, false,
                List.of(2L), List.of("604"), 10, MarginMode.CROSS, 10, 10, 1000, 100000, 3, 0, 1);
        strategyMvc.perform(post("/api/v1/admin/market-maker/strategy-definitions")
                .header("X-Admin-User-Id", "1001").param("reason", reason)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(definition)))
                .andExpect(status().is(expectedStatus));
        if (expectedStatus == 400) verifyNoInteractions(strategies);
        else verify(strategies).saveStrategyDefinition(eq(definition));
    }

    private void save(MarketMakerBusinessSettings settings, long version, String reason, int expectedStatus) throws Exception {
        var request = new MarketMakerBusinessSettingsController.Update(settings, version, reason);
        mvc.perform(post("/api/v1/admin/market-maker/business-settings")
                .header("X-Admin-User-Id", "1001").param("productLine", "LINEAR_PERPETUAL")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                .andExpect(status().is(expectedStatus));
    }

    private static MarketMakerBusinessSettings settings() {
        var settings = MarketMakerBusinessSettings.initialDisabled();
        var source = new MarketMakerProperties.ReferenceMarket.Source();
        source.setName("market"); source.setInstrumentId("604"); source.setExternalSymbol("BTCUSDT");
        source.setUrl("https://market.example/depth?symbol={externalSymbol}"); source.setParser("BINANCE_DEPTH");
        source.setWebSocketUrl("wss://market.example/{externalSymbolLower}"); source.setWebSocketParser("BINANCE_DEPTH_STREAM");
        source.setWebSocketSubscribeMessage("{\"method\":\"SUBSCRIBE\"}");
        settings.referenceMarket().setSources(List.of(source));
        return settings;
    }
}
