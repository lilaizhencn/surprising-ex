package com.surprising.trading.order.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.surprising.account.api.model.*;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.service.AccountService;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.*;
import com.surprising.trading.order.service.TradingFeeService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MakerQuoteInputsControllerTest {
    private static Process server;
    private static org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory factory;
    private static org.springframework.data.redis.core.StringRedisTemplate redis;

    @org.junit.jupiter.api.BeforeAll static void startRedis() throws Exception {
        int port;
        try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        server = new ProcessBuilder("redis-server", "--bind", "127.0.0.1", "--port", Integer.toString(port),
                "--save", "", "--appendonly", "no").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        factory = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory("127.0.0.1", port);
        factory.afterPropertiesSet(); factory.start();
        redis = new org.springframework.data.redis.core.StringRedisTemplate(factory);
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (true) {
            try (var connection = factory.getConnection()) { connection.ping(); break; }
            catch (RuntimeException unavailable) {
                if (System.nanoTime() >= deadline) throw unavailable;
                Thread.sleep(20);
            }
        }
    }
    @org.junit.jupiter.api.AfterAll static void stopRedis() throws Exception {
        if (factory != null) factory.destroy();
        if (server != null) { server.destroy(); server.waitFor(); }
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void batchKeepsAccountsProductsInventoryUnitsAndAccountFeePolicies(ProductLine line) throws Exception {
        var core = mock(com.surprising.account.provider.service.AccountAeronGateway.class);
        var projection = mock(com.surprising.account.provider.service.AccountQueryService.class);
        var accounts = new AccountService(properties(line), mock(com.surprising.account.provider.service.AccountCommandGateway.class),
                core, projection);
        org.springframework.test.util.ReflectionTestUtils.setField(accounts, "realtimeQueries",
                new com.surprising.realtime.api.ValkeyUserQueries(redis));
        var fees = mock(TradingFeeService.class);
        var cache = mock(InstrumentSnapshotCache.class);
        var instrument = mock(InstrumentResponse.class);
        when(instrument.baseAsset()).thenReturn("BTC");
        when(instrument.quantityStepUnits()).thenReturn(10L);
        when(cache.current(line, 1, 7)).thenReturn(Optional.of(instrument));
        for (long user : List.of(11L, 12L)) {
            installUser(line, user);
            when(fees.effectiveFee(user, "1", 7, line)).thenReturn(
                    new EffectiveTradingFeeResponse(user, line, "1", 7, user, 50, "USER_OVERRIDE", Instant.now()));
        }
        var controller = new MakerQuoteInputsController(accounts, fees, cache, properties(line));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        var json = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
        var result = mvc.perform(post("/internal/v1/trading/maker/quote-inputs").contentType("application/json")
                .content(json.writeValueAsBytes(new MakerQuoteInputs.Request(line, "1", 7, MarginMode.CROSS, List.of(11L, 12L)))))
                .andExpect(status().isOk()).andReturn();
        var response = json.readValue(result.getResponse().getContentAsByteArray(), MakerQuoteInputs.Response.class);
        assertThat(response.productLine()).isEqualTo(line);
        assertThat(response.accounts()).containsExactly(
                new MakerQuoteInputs.Account(11, line == ProductLine.SPOT ? 10 : -3, 11),
                new MakerQuoteInputs.Account(12, line == ProductLine.SPOT ? 10 : -3, 12));
        verifyNoInteractions(core, projection);
    }

    @Test void wrongProductAndUnavailableInstrumentFailBeforeAnyAccountOrFeeQuery() {
        var accounts = mock(AccountService.class); var fees = mock(TradingFeeService.class);
        var cache = mock(InstrumentSnapshotCache.class);
        var controller = new MakerQuoteInputsController(accounts, fees, cache, properties(ProductLine.SPOT));
        assertThatThrownBy(() -> controller.query(new MakerQuoteInputs.Request(ProductLine.OPTION, "1", 7,
                MarginMode.CROSS, List.of(11L)))).hasMessageContaining("400");
        assertThatThrownBy(() -> controller.query(new MakerQuoteInputs.Request(ProductLine.SPOT, "1", 7,
                MarginMode.CROSS, List.of(11L)))).hasMessageContaining("503");
        verifyNoInteractions(accounts, fees);
    }

    @Test void internalHttpContractRejectsDuplicateAccountIds() throws Exception {
        var controller = new MakerQuoteInputsController(mock(AccountService.class), mock(TradingFeeService.class),
                mock(InstrumentSnapshotCache.class), properties(ProductLine.SPOT));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/internal/v1/trading/maker/quote-inputs").contentType("application/json")
                .content("""
                {"productLine":"SPOT","instrumentId":"1","instrumentChangeId":7,
                 "marginMode":"CROSS","accountIds":[11,11]}
                """)).andExpect(status().isBadRequest());
    }

    private static void installUser(ProductLine line, long user) {
        long now = System.currentTimeMillis();
        var state = new com.surprising.aeron.protocol.CoreUserStateView(line, user, 1,
                List.of(new com.surprising.aeron.protocol.CoreBalanceView("BTC", 70, 30)), List.of(),
                List.of(new com.surprising.aeron.protocol.CorePositionView("1", "BTC",
                        com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                        com.surprising.aeron.protocol.CorePositionSide.NET, -3, 100, 300, 0, 20)));
        var frames = List.of(
                new com.surprising.aeron.protocol.RealtimeFrame(line, com.surprising.aeron.protocol.RealtimeFrame.Kind.SNAPSHOT_BEGIN,
                        user, 100, 0, now, 1, "", "", new byte[0]),
                new com.surprising.aeron.protocol.RealtimeFrame(line, com.surprising.aeron.protocol.RealtimeFrame.Kind.USER,
                        user, 100, 1, now, 1, "", "user", com.surprising.aeron.protocol.CoreStateQueryCodec.encodeUserState(state)),
                new com.surprising.aeron.protocol.RealtimeFrame(line, com.surprising.aeron.protocol.RealtimeFrame.Kind.SNAPSHOT_END,
                        user, 100, 2, now, 1, "", "", new byte[0]));
        new com.surprising.realtime.api.ValkeyReadViewStore(redis).install(frames, now);
        redis.opsForHash().put("rt:view:{" + line.name() + ":" + user + "}", "ORDER:unrelated", "not decoded by point queries");
    }

    private static AccountProperties properties(ProductLine line) {
        var p = new AccountProperties(); p.getKafka().setProductLine(line); return p;
    }
}
