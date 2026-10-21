package com.surprising.trading.api.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.BatchAmendOrdersRequest;
import com.surprising.trading.api.model.BatchCancelOrdersRequest;
import com.surprising.trading.api.model.BatchPlaceOrderRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.api.model.PlaceOrderRequest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class OrderRpcApiContractTest {

    @Test
    void changedMutationEndpointsExposeCommandReceipts() throws NoSuchMethodException {
        assertReceipt("place", PlaceOrderRequest.class);
        assertReceipt("placeBatch", BatchPlaceOrderRequest.class);
        assertReceipt("amend", AmendOrderRequest.class);
        assertReceipt("amendBatch", BatchAmendOrdersRequest.class);
        assertReceipt("cancel", CancelOrderRequest.class);
        assertReceipt("cancelBatch", BatchCancelOrdersRequest.class);
    }

    private void assertReceipt(String methodName, Class<?> requestType) throws NoSuchMethodException {
        Method method = OrderRpcApi.class.getMethod(methodName, requestType);
        assertThat(method.getReturnType()).as(methodName).isEqualTo(OrderCommandReceipt.class);
    }
}
