package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.provider.repository.AccountInstrumentRepository;
import com.surprising.account.provider.repository.PositionMarginRepository;
import com.surprising.account.provider.repository.PositionRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PositionQueryServiceTest {

    @Test
    void aggregatesPositionInstrumentAndMarginRepositories() {
        PositionRepository positionRepository = mock(PositionRepository.class);
        PositionMarginRepository marginRepository = mock(PositionMarginRepository.class);
        AccountInstrumentRepository instrumentRepository = mock(AccountInstrumentRepository.class);
        PositionQueryService service =
                new PositionQueryService(positionRepository, marginRepository, instrumentRepository);
        Instant positionUpdatedAt = Instant.parse("2026-07-30T00:00:00Z");
        Instant marginUpdatedAt = positionUpdatedAt.plusSeconds(30);
        PositionResponse position = new PositionResponse(
                1001L, "BTC-USDT", 7L, MarginMode.ISOLATED, PositionSide.LONG,
                10L, 50_000L, 0L, positionUpdatedAt);
        when(positionRepository.find(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.ISOLATED, PositionSide.LONG))
                .thenReturn(Optional.of(position));
        when(instrumentRepository.findSettleAsset("BTC-USDT", 7L)).thenReturn(Optional.of("USDT"));
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
        verify(positionRepository).find(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.ISOLATED, PositionSide.LONG);
        verify(instrumentRepository).findSettleAsset("BTC-USDT", 7L);
        verify(marginRepository).find(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", "USDT",
                MarginMode.ISOLATED, PositionSide.LONG);
    }

    @Test
    void usesPositionTimestampWhenMarginRowDoesNotExist() {
        PositionRepository positionRepository = mock(PositionRepository.class);
        PositionMarginRepository marginRepository = mock(PositionMarginRepository.class);
        AccountInstrumentRepository instrumentRepository = mock(AccountInstrumentRepository.class);
        PositionQueryService service =
                new PositionQueryService(positionRepository, marginRepository, instrumentRepository);
        Instant positionUpdatedAt = Instant.parse("2026-07-30T00:00:00Z");
        PositionResponse position = new PositionResponse(
                1001L, "ETH-USDT", 3L, MarginMode.CROSS, PositionSide.NET,
                20L, 3_000L, 10L, positionUpdatedAt);
        when(positionRepository.find(1001L, "ETH-USDT", MarginMode.CROSS, PositionSide.NET))
                .thenReturn(Optional.of(position));
        when(instrumentRepository.findSettleAsset("ETH-USDT", 3L)).thenReturn(Optional.of("USDT"));
        when(marginRepository.findLegacy(
                1001L, "ETH-USDT", "USDT", MarginMode.CROSS, PositionSide.NET))
                .thenReturn(Optional.empty());

        var response = service.positionMargin(
                1001L, "ETH-USDT", MarginMode.CROSS, PositionSide.NET).orElseThrow();

        assertThat(response.marginUnits()).isZero();
        assertThat(response.updatedAt()).isEqualTo(positionUpdatedAt);
    }
}
