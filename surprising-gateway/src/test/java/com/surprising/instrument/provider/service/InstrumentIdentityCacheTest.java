package com.surprising.instrument.provider.service;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.instrument.api.InstrumentEventKeys;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.instrument.api.model.InstrumentEventType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class InstrumentIdentityCacheTest {
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void renameKeepsOneIdentityAndCannotMoveAnInstrumentAcrossProductLines(ProductLine line) {
        var cache = new InstrumentSnapshotCache();
        var other = line == ProductLine.SPOT ? ProductLine.LINEAR_PERPETUAL : ProductLine.SPOT;
        cache.replace(line, List.of(row(line, 42, "BEFORE", 1), row(line, 43, "SECOND", 1)));
        cache.replace(other, List.of(row(other, 42, "BEFORE", 1)));

        var renamed = row(line, 42, "AFTER", 2);
        var event = event(line, renamed);
        String keyBefore = InstrumentEventKeys.key(line, 42);
        assertThat(cache.apply(event)).isTrue();
        assertThat(InstrumentEventKeys.key(event)).isEqualTo(keyBefore);
        assertThat(cache.size(line)).isEqualTo(2);
        assertThat(cache.current(line, 42).orElseThrow().symbol()).isEqualTo("AFTER");
        assertThat(cache.current(line, 43).orElseThrow().symbol()).isEqualTo("SECOND");
        assertThat(cache.current(other, 42).orElseThrow().symbol()).isEqualTo("BEFORE");
        assertThat(cache.bySymbol(line, "BEFORE")).isEmpty();

        assertThat(cache.apply(event(line, row(line, 42, "BEFORE", 1)))).isFalse();
        assertThat(cache.current(line, 42).orElseThrow().symbol()).isEqualTo("AFTER");
        assertThat(cache.apply(new InstrumentEvent(43, renamed.symbol(), 2, InstrumentStatus.TRADING,
                InstrumentEventType.UPSERTED, Instant.EPOCH, renamed, line, 2))).isFalse();
        assertThat(cache.apply(new InstrumentEvent(42, renamed.symbol(), 2, InstrumentStatus.TRADING,
                InstrumentEventType.UPSERTED, Instant.EPOCH, renamed, other, 2))).isFalse();
    }

    private InstrumentEvent event(ProductLine line, InstrumentResponse row) {
        return new InstrumentEvent(row.instrumentId(), row.symbol(), row.lastChangeId(), row.status(),
                InstrumentEventType.UPSERTED, Instant.EPOCH, row, line, row.lastChangeId());
    }

    private InstrumentResponse row(ProductLine line, int id, String symbol, long change) {
        var row = mock(InstrumentResponse.class);
        when(row.instrumentId()).thenReturn(id);
        when(row.symbol()).thenReturn(symbol);
        when(row.contractType()).thenReturn(ContractType.valueOf(line.contractTypeCode()));
        when(row.status()).thenReturn(InstrumentStatus.TRADING);
        when(row.changeId()).thenReturn(1L);
        when(row.lastChangeId()).thenReturn(change);
        return row;
    }
}
