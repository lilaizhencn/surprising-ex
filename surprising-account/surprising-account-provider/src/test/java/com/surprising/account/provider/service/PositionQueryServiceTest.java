package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.repository.PositionMarginRepository;
import com.surprising.account.provider.repository.PositionRepository;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PositionQueryServiceTest {

    @Test
    void aggregatesPositionSnapshotAndMarginRepositories() {
        PositionRepository positionRepository = mock(PositionRepository.class);
        PositionMarginRepository marginRepository = mock(PositionMarginRepository.class);
        AccountProperties properties = new AccountProperties();
        InstrumentSnapshotCache cache = cache("BTC-USDT", 7L);
        PositionQueryService service =
                new PositionQueryService(positionRepository, marginRepository, properties, cache);
        Instant positionUpdatedAt = Instant.parse("2026-07-30T00:00:00Z");
        Instant marginUpdatedAt = positionUpdatedAt.plusSeconds(30);
        PositionResponse position = new PositionResponse(
                1001L, "BTC-USDT", 7L, MarginMode.ISOLATED, PositionSide.LONG,
                10L, 50_000L, 0L, positionUpdatedAt);
        when(positionRepository.find(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.ISOLATED, PositionSide.LONG))
                .thenReturn(Optional.of(position));
        when(marginRepository.find(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", "USDT",
                MarginMode.ISOLATED, PositionSide.LONG))
                .thenReturn(Optional.of(new PositionMarginRepository.PositionMarginRow(
                        "BTC-USDT", "USDT", MarginMode.ISOLATED, PositionSide.LONG,
                        700L, marginUpdatedAt)));

        var response = service.positionMargin(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT",
                MarginMode.ISOLATED, PositionSide.LONG).orElseThrow();

        assertThat(response.asset()).isEqualTo("USDT");
        assertThat(response.marginUnits()).isEqualTo(700L);
        assertThat(response.updatedAt()).isEqualTo(marginUpdatedAt);
        org.mockito.Mockito.verify(positionRepository).find(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.ISOLATED, PositionSide.LONG);
        org.mockito.Mockito.verify(marginRepository).find(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", "USDT",
                MarginMode.ISOLATED, PositionSide.LONG);
    }

    @Test
    void usesPositionTimestampWhenMarginRowDoesNotExist() {
        PositionRepository positionRepository = mock(PositionRepository.class);
        PositionMarginRepository marginRepository = mock(PositionMarginRepository.class);
        AccountProperties properties = new AccountProperties();
        InstrumentSnapshotCache cache = cache("ETH-USDT", 3L);
        PositionQueryService service =
                new PositionQueryService(positionRepository, marginRepository, properties, cache);
        Instant positionUpdatedAt = Instant.parse("2026-07-30T00:00:00Z");
        PositionResponse position = new PositionResponse(
                1001L, "ETH-USDT", 3L, MarginMode.CROSS, PositionSide.NET,
                20L, 3_000L, 10L, positionUpdatedAt);
        when(positionRepository.find(1001L, "ETH-USDT", MarginMode.CROSS, PositionSide.NET))
                .thenReturn(Optional.of(position));
        when(marginRepository.findLegacy(
                1001L, "ETH-USDT", "USDT", MarginMode.CROSS, PositionSide.NET))
                .thenReturn(Optional.empty());

        var response = service.positionMargin(
                1001L, "ETH-USDT", MarginMode.CROSS, PositionSide.NET).orElseThrow();

        assertThat(response.marginUnits()).isZero();
        assertThat(response.updatedAt()).isEqualTo(positionUpdatedAt);
    }

    private InstrumentSnapshotCache cache(String symbol, long version) {
        InstrumentSnapshotCache cache = new InstrumentSnapshotCache();
        Instant now = Instant.parse("2026-07-30T00:00:00Z");
        InstrumentResponse instrument = new InstrumentResponse(symbol, version, InstrumentType.PERPETUAL,
                ContractType.LINEAR_PERPETUAL, "BTC", "USDT", "USDT", 1_000_000L, "BTC",
                10L, 1L, 1L, 1_000_000L, 1L, 1_000_000_000L, 1L, 2, 0,
                List.of("LIMIT"), List.of("GTC"), true, true, true, 100_000_000L,
                10_000L, 5_000L, 100L, 500L, 1_000_000_000L, 300_000L, 250_000_000L,
                8, 100L, 3_000L, -3_000L, 10_000_000L, 3, null, null, null, null,
                null, null, null, InstrumentStatus.TRADING, now, now, now, List.of(), List.of());
        cache.replace(ProductLine.LINEAR_PERPETUAL, List.of(instrument));
        return cache;
    }
}
