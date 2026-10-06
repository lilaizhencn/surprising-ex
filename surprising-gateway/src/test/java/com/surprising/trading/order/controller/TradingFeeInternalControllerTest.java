package com.surprising.trading.order.controller;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import com.surprising.trading.order.service.TradingFeeRequestService;

class TradingFeeInternalControllerTest {
    @Test void hostLocalLookupKeepsAccountProductAndVersion() {
        var fees = mock(TradingFeeRequestService.class);
        var request = new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.1");
        new TradingFeeInternalController(fees).effective(2, "604", 1203, "LINEAR_PERPETUAL", request);
        verify(fees).effective(2, "604", 1203, "LINEAR_PERPETUAL", "LINEAR_PERPETUAL");
    }
    @Test void refusesExternalAndProxyRequests() {
        var fees = mock(TradingFeeRequestService.class);
        var controller = new TradingFeeInternalController(fees);
        for (String header : new String[]{"X-Forwarded-For", "Forwarded", "X-Real-IP"}) {
            var request = new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.1");
            request.addHeader(header, "203.0.113.1");
            assertThatThrownBy(() -> controller.effective(2, "604", 1203, "LINEAR_PERPETUAL", request))
                    .isInstanceOf(ResponseStatusException.class);
        }
        var external = new MockHttpServletRequest(); external.setRemoteAddr("203.0.113.1");
        assertThatThrownBy(() -> controller.effective(2, "604", 1203, "LINEAR_PERPETUAL", external))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(fees);
    }
}
