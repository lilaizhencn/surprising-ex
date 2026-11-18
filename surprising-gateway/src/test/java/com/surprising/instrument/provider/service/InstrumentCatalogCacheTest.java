package com.surprising.instrument.provider.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.instrument.api.model.*;
import com.surprising.instrument.provider.config.InstrumentProperties;
import com.surprising.product.api.ProductLine;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class InstrumentCatalogCacheTest {
    private final InstrumentStorageService storage = mock();
    private final AtomicLong now = new AtomicLong();
    private final InstrumentService service = new InstrumentService(storage, mock(InstrumentValidator.class),
            new InstrumentProperties(), mock(InstrumentOutboxService.class), mock(MarketSummaryService.class), null, now::get);
    private InstrumentStorageService.Configuration configuration(String version, ContractType type, InstrumentStatus status) {
        var row = mock(InstrumentResponse.class);
        when(row.instrumentId()).thenReturn(7); when(row.symbol()).thenReturn("BTC-USDT");
        when(row.contractType()).thenReturn(type); when(row.status()).thenReturn(status);
        when(row.instrumentType()).thenReturn(InstrumentType.PERPETUAL);
        return new InstrumentStorageService.Configuration(version, List.of(InstrumentResponse.immutableCopy(row)), Map.of("USDT", 100L));
    }
    @Test void singleColdLoadForConcurrentReadersThenVersionOnlyRecheck() throws Exception {
        var spot = configuration("v1", ContractType.SPOT, InstrumentStatus.TRADING);
        when(storage.configuration(ProductLine.SPOT, null)).thenReturn(spot);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<InstrumentSnapshotResponse>>();
            for (int i = 0; i < 16; i++) futures.add(executor.submit(() -> service.snapshot(ProductLine.SPOT)));
            var first = futures.getFirst().get(5, TimeUnit.SECONDS);
            for (var future : futures) assertThat(future.get(5, TimeUnit.SECONDS)).isSameAs(first);
            assertThat(service.assetScales()).containsEntry("USDT", 100L);
            verify(storage).configuration(ProductLine.SPOT, null);
            now.set(TimeUnit.SECONDS.toNanos(5));
            when(storage.configuration(ProductLine.SPOT, "v1")).thenReturn(null);
            assertThat(service.snapshot(ProductLine.SPOT)).isSameAs(first);
            verify(storage).configuration(ProductLine.SPOT, "v1");
        }
    }
    @Test void productIsolationVersionChangesAndLocalInvalidation() {
        var spot = configuration("v1", ContractType.SPOT, InstrumentStatus.TRADING);
        var linear = configuration("v1", ContractType.LINEAR_PERPETUAL, InstrumentStatus.HALT);
        when(storage.configuration(ProductLine.SPOT, null)).thenReturn(spot);
        when(storage.configuration(ProductLine.LINEAR_PERPETUAL, null)).thenReturn(linear);
        assertThat(service.publicCatalog(ProductLine.SPOT, null, null, false).instruments().instruments()).hasSize(1);
        assertThat(service.snapshot(ProductLine.LINEAR_PERPETUAL).productLine()).isEqualTo(ProductLine.LINEAR_PERPETUAL);
        now.set(TimeUnit.SECONDS.toNanos(5));
        var updated = configuration("v2", ContractType.SPOT, InstrumentStatus.DRAFT);
        when(storage.configuration(ProductLine.SPOT, "v1")).thenReturn(updated);
        assertThat(service.publicCatalog(ProductLine.SPOT, null, null, false).instruments().instruments()).isEmpty();
        var event = mock(InstrumentEvent.class); when(event.productLine()).thenReturn(ProductLine.SPOT);
        service.onInstrumentEvent(event);
        service.snapshot(ProductLine.SPOT);
        verify(storage, times(2)).configuration(ProductLine.SPOT, null);
    }
    @Test void coldMissingAndExpiredErrorNeverServeInventedOrUnboundedOldConfiguration() {
        assertThatThrownBy(() -> service.snapshot(ProductLine.SPOT)).isInstanceOf(IllegalStateException.class);
        var spot = configuration("v1", ContractType.SPOT, InstrumentStatus.TRADING);
        when(storage.configuration(ProductLine.SPOT, null)).thenReturn(spot);
        service.snapshot(ProductLine.SPOT);
        now.set(TimeUnit.SECONDS.toNanos(5));
        var updated = configuration("v2", ContractType.SPOT, InstrumentStatus.DRAFT);
        when(storage.configuration(ProductLine.SPOT, "v1"))
                .thenThrow(new IllegalStateException("offline"))
                .thenReturn(updated);
        assertThatThrownBy(() -> service.snapshot(ProductLine.SPOT)).hasMessage("offline");
        assertThat(service.snapshot(ProductLine.SPOT).instruments().getFirst().status()).isEqualTo(InstrumentStatus.DRAFT);
    }
}
