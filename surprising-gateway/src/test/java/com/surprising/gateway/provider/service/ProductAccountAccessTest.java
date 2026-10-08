package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.account.api.model.*;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ProductAccountAccessTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void everyProductUsesItsOwnAccountMethods(ProductLine line) {
        var products = mock(GatewayProductServices.class);
        var source = mock(AccountCommandGateway.class);
        when(products.service(line, AccountCommandGateway.class)).thenReturn(source);
        var request = new ProductTransferOperationRequest(1,42,line,line,AccountType.valueOf(line.accountTypeCode()),
                AccountType.valueOf(line.accountTypeCode()),"USDT",100,"allocation:1","test");
        var access = new ProductAccountAccess(products);
        assertThat(access.transferOut(line.accountTypeCode(),request).status()).isEqualTo(ProductAccountAdjustment.Status.APPLIED);
        assertThat(access.transferIn(line.accountTypeCode(),request).status()).isEqualTo(ProductAccountAdjustment.Status.APPLIED);
        assertThat(access.completeTransfer(line.accountTypeCode(),request).status()).isEqualTo(ProductAccountAdjustment.Status.APPLIED);
        verify(source).transferOut(request);
        verify(source).transferIn(request);
        verify(source).completeTransfer(request);
    }
    @Test void disabledTargetIsRejectedBeforeDebitingSource() {
        var products = mock(GatewayProductServices.class);
        doThrow(new IllegalArgumentException("target disabled")).when(products).requireEnabled(ProductLine.OPTION);
        var access = new ProductAccountAccess(products);
        var request = new ProductTransferOperationRequest(1,42,ProductLine.SPOT,ProductLine.OPTION,AccountType.SPOT,
                AccountType.OPTION,"USDT",100,"allocation:1","test");
        assertThatThrownBy(() -> access.transferOut("SPOT",request)).hasMessage("target disabled");
        verify(products,never()).service(any(),any());
    }
    @Test void disabledProductsAreNotScannedDuringReconciliation() {
        var products = mock(GatewayProductServices.class);
        when(products.enabled()).thenReturn(List.of(ProductLine.SPOT));
        assertThat(new ProductAccountAccess(products).pendingTransfers(ProductLine.OPTION,10)).isEmpty();
        verify(products,never()).service(any(),any());
    }
}
