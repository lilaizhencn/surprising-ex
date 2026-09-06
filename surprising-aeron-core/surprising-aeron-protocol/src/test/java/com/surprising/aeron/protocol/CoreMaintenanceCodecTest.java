package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.*;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreMaintenanceCodecTest {
    @Test void rejectsTruncationAndTrailingBytesWithoutUncheckedBufferFailures() {
        var command=new CoreMaintenanceCodec.Command("BTC-USDT",0,new CoreInstrumentMaintenance(7,CoreInstrumentMaintenance.Mode.SETTLEMENT,120));
        byte[] bytes=CoreMaintenanceCodec.encodeCommand(command);
        assertThat(CoreMaintenanceCodec.decodeCommand(bytes)).isEqualTo(command);
        for(int size=0;size<bytes.length;size++) {
            byte[] truncated=Arrays.copyOf(bytes,size);
            assertThatThrownBy(()->CoreMaintenanceCodec.decodeCommand(truncated)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->CoreMaintenanceCodec.decodeCommand(Arrays.copyOf(bytes,bytes.length+1))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void boundsPaginationAndPreservesLongValues() {
        var page=new CoreMaintenanceCodec.Page(new CoreInstrumentMaintenance(Long.MAX_VALUE,CoreInstrumentMaintenance.Mode.SETTLEMENT,Long.MAX_VALUE),Long.MAX_VALUE,List.of(9007199254740997L),true);
        assertThat(CoreMaintenanceCodec.decodePage(CoreMaintenanceCodec.encodePage(page))).isEqualTo(page);
        assertThatThrownBy(()->new CoreMaintenanceCodec.Query("BTC-USDT",0,33)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new CoreMaintenanceCodec.Query("BTC-USDT",-1,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new CoreInstrumentMaintenance(0,CoreInstrumentMaintenance.Mode.HALTED,0)).isInstanceOf(IllegalArgumentException.class);
    }
}
