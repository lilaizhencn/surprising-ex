package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.repository.AccountRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AccountServicePositionCacheTest {

    @Test
    void productionUserPositionQueryDoesNotReadPostgres() {
        AccountRepository repository = mock(AccountRepository.class);
        RedisPositionCache cache = mock(RedisPositionCache.class);
        AccountProperties properties = new AccountProperties();
        PositionResponse cached = new PositionResponse(
                1001L, "BTC-USDT", 7L, MarginMode.CROSS, PositionSide.NET,
                3L, 60_000L, 0L, Instant.parse("2026-07-01T00:00:00Z"));
        when(cache.position(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS, PositionSide.NET))
                .thenReturn(cached);
        AccountService service = new AccountService(
                repository, new PositionCalculator(), properties, null, cache);

        PositionResponse response = service.position(1001L, "btc-usdt", "CROSS", "NET");

        assertThat(response).isEqualTo(cached);
        verify(cache).position(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS, PositionSide.NET);
        verifyNoInteractions(repository);
    }
}
