package com.surprising.insurance.provider.service;

import com.surprising.aeron.protocol.*;
import com.surprising.derivatives.lifecycle.DerivativesAeronClient;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InsuranceAeronGatewayTest {
    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = "SPOT", mode = EnumSource.Mode.EXCLUDE)
    void usesSharedClientForCommandsAndTreasury(ProductLine product) {
        var properties = new InsuranceProperties();
        properties.getKafka().setProductLine(product);
        var client = mock(DerivativesAeronClient.class);
        var gateway = new InsuranceAeronGateway(properties, client);
        var id = UUID.randomUUID();
        var payload = new byte[]{1};
        when(client.command(CoreMessageType.ADJUST_INSURANCE_FUND, id, 0, payload))
                .thenReturn(new CoreResponse(ResponseStatus.OK, ResponseStatus.APPLIED, CoreResultCode.NONE, 1, payload));
        when(client.query(eq(CoreMessageType.TREASURY_STATE_QUERY), any(UUID.class), eq(0L), any(byte[].class)))
                .thenReturn(new CoreResponse(ResponseStatus.OK, ResponseStatus.APPLIED, CoreResultCode.NONE, 1, payload));
        gateway.command(CoreMessageType.ADJUST_INSURANCE_FUND, id, payload);
        assertThat(gateway.treasury()).containsExactly(payload);
        var work = new CoreLiquidationWorkView(product, 42, true, null, java.util.List.of(), java.util.List.of());
        when(client.query(eq(CoreMessageType.LIQUIDATION_WORK_QUERY), any(UUID.class), eq(0L), any(byte[].class)))
                .thenReturn(new CoreResponse(ResponseStatus.OK, ResponseStatus.APPLIED, CoreResultCode.NONE, 2,
                        CoreLiquidationWorkCodec.encodeWork(work)));
        assertThat(gateway.resolutionWork(CoreLiquidationWorkView.Purpose.INSURANCE, 41, 10, 4096)).isEqualTo(work);
        verify(client).command(CoreMessageType.ADJUST_INSURANCE_FUND, id, 0, payload);
        verify(client).query(eq(CoreMessageType.TREASURY_STATE_QUERY), any(UUID.class), eq(0L), eq(new byte[0]));
        verify(client).query(eq(CoreMessageType.LIQUIDATION_WORK_QUERY), any(UUID.class), eq(0L),
                argThat(bytes -> CoreLiquidationWorkCodec.decodeQuery(bytes).equals(
                        new CoreLiquidationWorkView.Query(product, CoreLiquidationWorkView.Purpose.INSURANCE, 41, 10, 4096))));
        verifyNoMoreInteractions(client);
    }

    @Test
    void propagatesRejectedOrUnknownCommandsWithoutRetry() {
        var client = mock(DerivativesAeronClient.class);
        var gateway = new InsuranceAeronGateway(new InsuranceProperties(), client);
        var id = UUID.randomUUID();
        var payload = new byte[]{1};
        var timeout = new IllegalStateException("response timeout");
        when(client.command(CoreMessageType.ADJUST_INSURANCE_FUND, id, 0, payload))
                .thenReturn(new CoreResponse(ResponseStatus.OK, ResponseStatus.REJECTED,
                        CoreResultCode.INVALID_COMMAND, 1, payload))
                .thenThrow(timeout);
        assertThatThrownBy(() -> gateway.command(CoreMessageType.ADJUST_INSURANCE_FUND, id, payload))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("INVALID_COMMAND");
        assertThatThrownBy(() -> gateway.command(CoreMessageType.ADJUST_INSURANCE_FUND, id, payload))
                .isSameAs(timeout);
        verify(client, times(2)).command(CoreMessageType.ADJUST_INSURANCE_FUND, id, 0, payload);
        verifyNoMoreInteractions(client);
    }
}
