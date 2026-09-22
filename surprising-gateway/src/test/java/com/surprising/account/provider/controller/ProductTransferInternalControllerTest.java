package com.surprising.account.provider.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.surprising.account.api.AccountApiPaths;
import com.surprising.account.api.model.*;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class ProductTransferInternalControllerTest {
    @Test
    void transferPhasesAcceptHttpRequestsWithoutCredentials() throws Exception {
        var commands = mock(AccountCommandGateway.class);
        var controller = new ProductTransferInternalController(commands, new AccountProperties());
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        var request = operation();
        String json = new ObjectMapper().writeValueAsString(request);
        for (String path : java.util.List.of(AccountApiPaths.TRANSFER_OUT_PATH,
                AccountApiPaths.TRANSFER_IN_PATH, AccountApiPaths.TRANSFER_COMPLETE_PATH)) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
        }
        verify(commands).transferOut(request);
        verify(commands).transferIn(request);
        verify(commands).completeTransfer(request);
    }

    @Test
    void pendingQueryStillRejectsWrongProductLine() {
        var commands = mock(AccountCommandGateway.class);
        var properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.SPOT);
        var controller = new ProductTransferInternalController(commands, properties);
        assertThatThrownBy(() -> controller.pending(new PendingProductTransfersRequest(ProductLine.LINEAR_PERPETUAL, 10)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("product line mismatch");
        verifyNoInteractions(commands);
        controller.pending(new PendingProductTransfersRequest(ProductLine.SPOT, 10));
        verify(commands).pendingTransfers(10);
    }

    private ProductTransferOperationRequest operation() {
        return new ProductTransferOperationRequest(7001L, 42L, ProductLine.SPOT,
                ProductLine.LINEAR_PERPETUAL, AccountType.FUNDING, AccountType.USDT_PERPETUAL,
                "USDT", 1_250L, "transfer-7001", "allocation");
    }
}
