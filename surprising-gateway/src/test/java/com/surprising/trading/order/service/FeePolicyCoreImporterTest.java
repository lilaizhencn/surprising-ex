package com.surprising.trading.order.service;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.UUID;
import com.surprising.product.api.ProductLine;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.trading.api.model.*;

class FeePolicyCoreImporterTest {
    @Test void replayKeepsPolicyRevisionButDoesNotReuseExpiredCommandIdentity() {
        var gateway = mock(OrderAeronGateway.class);
        var importer = new FeePolicyCoreImporter(gateway);
        var now = Instant.parse("2026-10-06T14:00:00Z");
        var policy = new FeeScheduleResponse(123, ProductLine.LINEAR_PERPETUAL, 2, "604", 0, 500,
                FeeScheduleSourceType.MARKET_MAKER, "TEST", "test", FeeScheduleStatus.ACTIVE,
                now, null, now, now);
        importer.importPolicy(policy); importer.importPolicy(policy);
        var ids = ArgumentCaptor.forClass(UUID.class);
        var payloads = ArgumentCaptor.forClass(byte[].class);
        verify(gateway, times(2)).command(eq(CoreMessageType.UPSERT_FEE_POLICY), ids.capture(), eq(2L), payloads.capture());
        assertThat(ids.getAllValues().get(0)).isNotEqualTo(ids.getAllValues().get(1));
        assertThat(payloads.getAllValues().get(0)).containsExactly(payloads.getAllValues().get(1));
    }
}
