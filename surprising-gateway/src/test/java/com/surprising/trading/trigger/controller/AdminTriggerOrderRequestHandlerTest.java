package com.surprising.trading.trigger.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.utils.AdminCursorPage;
import com.surprising.trading.order.controller.AdminTradingController;
import com.surprising.trading.trigger.service.TriggerOrderService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AdminTriggerOrderRequestHandlerTest {
    @Test void bindsExactCoordinatesAndNormalizedFiltersBeforeBusinessRead() {
        var service=mock(TriggerOrderService.class);
        var requests=new AdminTradingController(null,service);
        var cursor=new AdminCursorPage.Cursor(Instant.ofEpochMilli(9007199254740993L),Long.MAX_VALUE);
        requests.triggerOrders("1",null,"OPTION",7L," 74 "," canceled ",Long.MAX_VALUE,10,
                AdminCursorPage.encodeCursor(cursor.timestamp(), cursor.id()),"createdAt.asc");
        verify(service).adminOrders(7L,"74",CoreTriggerOrderStatus.CANCELED,Long.MAX_VALUE,10,cursor,true,ProductLine.OPTION);
    }

    @Test void invalidQueryNeverReachesBusinessRead() {
        var service=mock(TriggerOrderService.class);
        var requests=new AdminTradingController(null,service);
        for (int limit:new int[]{0,1001})
            badRequest(()->requests.triggerOrders("1",null,"OPTION",null,null,null,null,limit,null,null));
        badRequest(()->requests.triggerOrders("1",null,"OPTION",0L,null,null,null,10,null,null));
        badRequest(()->requests.triggerOrders("1",null,"OPTION",null,null,null,0L,10,null,null));
        badRequest(()->requests.triggerOrders("1",null,"OPTION",null,"BTC-USDT",null,null,10,null,null));
        badRequest(()->requests.triggerOrders("1",null,"OPTION",null,null,"INVALID",null,10,null,null));
        badRequest(()->requests.triggerOrders("1",null,"OPTION",null,null,null,null,10,null,"id.asc"));
        badRequest(()->requests.triggerOrders("1",null,"OPTION",null,null,null,null,10,"garbage",null));
        for (var cursor:java.util.List.of(new AdminCursorPage.Cursor(Instant.ofEpochMilli(0),1),new AdminCursorPage.Cursor(Instant.ofEpochMilli(1),0)))
            badRequest(()->requests.triggerOrders("1",null,"OPTION",7L,null,null,null,10,AdminCursorPage.encodeCursor(cursor.timestamp(), cursor.id()),null));
        verifyNoInteractions(service);
    }

    private void badRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,
                failure->assertThat(failure.getStatusCode().value()).isEqualTo(400));
    }
}
