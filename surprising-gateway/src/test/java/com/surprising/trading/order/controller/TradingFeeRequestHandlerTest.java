package com.surprising.trading.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.service.TradingFeeService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class TradingFeeRequestHandlerTest {
    @Test void effectiveFeesValidatePermanentInstrumentIdsAndKeepTheExplicitProduct() {
        var service = mock(TradingFeeService.class);
        var handler = new TradingFeeRequestHandler(service);
        handler.effective(9_007_199_254_740_993L, "604", 0L, "LINEAR_PERPETUAL", "LINEAR_PERPETUAL");
        verify(service).effectiveFee(9_007_199_254_740_993L, "604", 0L, ProductLine.LINEAR_PERPETUAL);
        clearInvocations(service);
        for (String invalid : new String[]{"", "BTC-USDT", "-1", "2147483648"}) {
            assertThatThrownBy(() -> handler.effective(42, invalid, 0, "LINEAR_PERPETUAL", "LINEAR_PERPETUAL"))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            failure -> assertThat(failure.getStatusCode().value()).isEqualTo(400));
        }
        assertThatThrownBy(() -> handler.effective(42, "604", 0, "UNKNOWN", "UNKNOWN"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(service);
    }
}
