package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.model.C2cPaymentAccountInput;
import com.surprising.gateway.c2c.controller.C2cController;
import com.surprising.gateway.c2c.service.C2cAccess;
import com.surprising.gateway.c2c.service.C2cPaymentAccounts;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.surprising.gateway.c2c.service.C2cSubmissionService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class C2cCommandValidationTest {
    private C2cPaymentAccounts payments;
    private C2cSubmissionService submissions;
    private C2cController controller;
    @BeforeEach void setup() {
        var access=mock(C2cAccess.class);when(access.user(any())).thenReturn(C2cHttpFixture.principal(12));
        payments=mock(C2cPaymentAccounts.class);submissions=mock(C2cSubmissionService.class);
        controller=new C2cController(access,null,null,submissions,null,null,payments,new tools.jackson.databind.ObjectMapper());
    }
    @Test void malformedSubmissionIdentityIsRejectedBeforePersistence() {
        assertThatThrownBy(()->controller.submission("Bearer qa","bad","ORDER_CREATE","0".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->controller.stop("Bearer qa","valid-key-for-submit",new C2cController.SubmissionIdentity("UNKNOWN","0".repeat(64)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->controller.create("Bearer qa",new C2cController.OrderRequest("bad",UUID.randomUUID(),null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->controller.stop("Bearer qa","valid-key-for-submit",new C2cController.SubmissionIdentity(null,"0".repeat(64)))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(submissions,payments);
    }
    @Test void malformedPaymentFieldsNeverEnterTheBusinessService() {
        assertThatThrownBy(()->controller.savePaymentAccount("Bearer qa",new C2cController.PaymentSave(null,"0","bad","ALIPAY",
                new C2cPaymentAccountInput.Details("QA Buyer","qa-account","",null),null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->controller.savePaymentAccount("Bearer qa",new C2cController.PaymentSave(null,"0","CNY","bad-method",
                new C2cPaymentAccountInput.Details("QA Buyer","qa-account","",null),null))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(submissions,payments);
    }
}
