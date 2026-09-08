package com.surprising.liquidation.provider.service;

import com.surprising.aeron.protocol.*;
import com.surprising.derivatives.lifecycle.DerivativesAeronClient;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class LiquidationAeronGatewayTest {
    @Test
    void repeatedRiskCursorGetsFreshSchedulingIdWhilePureActionsRemainIdempotent() {
        var client=mock(DerivativesAeronClient.class);
        var properties=new LiquidationProperties();
        properties.setProductLine(ProductLine.LINEAR_PERPETUAL);
        var result=new CoreLiquidationBatchResultView(0,0,0,0,0,1);
        when(client.command(eq(CoreMessageType.EXECUTE_LIQUIDATION_BATCH),any(),eq(0L),any()))
                .thenReturn(new CoreResponse(ResponseStatus.OK,ResponseStatus.APPLIED,CoreResultCode.NONE,
                        1,0,CoreLiquidationBatchResultCodec.encode(result)));
        var gateway=new LiquidationAeronGateway(properties,client);
        var risk=new CoreLiquidationWorkView(ProductLine.LINEAR_PERPETUAL,0,true,
                new CoreRiskScanContinuation("BTC-USDT",1,0),List.of(),List.of());
        gateway.executeBatch(risk,0,1);gateway.executeBatch(risk,0,1);
        var action=new CoreLiquidationActionView(1,7,"BTC-USDT",CoreMarginMode.CROSS,
                CorePositionSide.NET,1,1,1,1,100);
        var work=new CoreLiquidationWorkView(ProductLine.LINEAR_PERPETUAL,1,true,null,List.of(action),List.of());
        gateway.executeBatch(work,0,0);gateway.executeBatch(work,0,0);
        var ids=ArgumentCaptor.forClass(UUID.class);
        verify(client,times(4)).command(eq(CoreMessageType.EXECUTE_LIQUIDATION_BATCH),ids.capture(),eq(0L),any());
        var values=ids.getAllValues();
        assertThat(values.get(0)).isNotEqualTo(values.get(1));
        assertThat(values.get(2)).isEqualTo(values.get(3));
    }
}
