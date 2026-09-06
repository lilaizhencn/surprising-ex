package com.surprising.instrument.provider.service;

import static org.assertj.core.api.Assertions.*;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.*;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class InstrumentCurrentCacheTest {
    private InstrumentResponse row(long calculation,long audit,String status) {
        var value=org.mockito.Mockito.mock(InstrumentResponse.class);
        org.mockito.Mockito.when(value.symbol()).thenReturn("BTC-USDT");
        org.mockito.Mockito.when(value.contractType()).thenReturn(ContractType.SPOT);
        org.mockito.Mockito.when(value.changeId()).thenReturn(calculation);
        org.mockito.Mockito.when(value.lastChangeId()).thenReturn(audit);
        org.mockito.Mockito.when(value.status()).thenReturn(InstrumentStatus.valueOf(status));
        return value;
    }
    private InstrumentEvent event(InstrumentResponse row) {
        return new InstrumentEvent(row.symbol(),row.lastChangeId(),row.status(),InstrumentEventType.STATUS_CHANGED,Instant.now(),row,ProductLine.SPOT,row.lastChangeId());
    }
    @Test void snapshotAndOldEventsCannotUndoNewerPauseAndNoHistoricalConfigurationIsSelected() {
        var cache=new InstrumentSnapshotCache();
        var trading=row(1,1,"TRADING"); var halted=row(1,2,"HALT");
        cache.replace(ProductLine.SPOT,List.of(trading));
        assertThat(cache.apply(event(halted))).isTrue();
        assertThat(cache.apply(event(trading))).isFalse();
        assertThat(cache.apply(event(halted))).isFalse();
        cache.replace(ProductLine.SPOT,List.of(trading));
        assertThat(cache.current(ProductLine.SPOT,"BTC-USDT",1).orElseThrow().status()).isEqualTo(InstrumentStatus.HALT);
        assertThat(cache.current(ProductLine.INVERSE_PERPETUAL,"BTC-USDT")).isEmpty();
        assertThat(cache.apply(event(row(3,3,"HALT")))).isTrue();
        assertThat(cache.size(ProductLine.SPOT)).isEqualTo(1);
        assertThat(cache.current(ProductLine.SPOT,"BTC-USDT",1)).isEmpty();
    }
}
