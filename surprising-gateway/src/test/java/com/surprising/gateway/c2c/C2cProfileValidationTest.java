package com.surprising.gateway.c2c;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class C2cProfileValidationTest {
    private C2cProfiles profiles;
    private C2cMeController controller;
    @BeforeEach void setup() {
        profiles=mock(C2cProfiles.class);var access=mock(C2cAccess.class);
        when(access.user(any())).thenReturn(C2cHttpFixture.principal(12));controller=new C2cMeController(access,profiles);
    }
    @Test void invalidSettingsAndTextAreRejectedBeforeBusinessAccess() {
        for(var request:new C2cMeController.Settings[]{
                new C2cMeController.Settings("1","QA\u200bUser",false,0,null),
                new C2cMeController.Settings("1","QA User",false,7,null),
                new C2cMeController.Settings("1","x",false,0,null)})
            assertThatThrownBy(()->controller.update("Bearer qa",request)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->controller.quickReply("Bearer qa",new C2cMeController.QuickReply(null,"bad\u0001text"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(profiles);
    }
    @Test void invalidPagingRelationsAndAlertInputsNeverReachTheService() {
        assertThatThrownBy(()->controller.relations("Bearer qa","UNKNOWN",30,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->controller.relation("Bearer qa","FOLLOW",0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->controller.reports("Bearer qa",101,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->controller.notifications("Bearer qa","-1",30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->controller.alert("Bearer qa",new C2cMeController.Alert(null,"0",UUID.randomUUID(),"BUY","1e3",true))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(profiles);
    }
    @Test void controllerNormalizesTextBeforeHandingItToTheService() {
        controller.update("Bearer qa",new C2cMeController.Settings("1","  ＱＡ  ",false,0,null));
        verify(profiles).update(any(),eq(1L),eq("QA"),eq(false),eq(0),isNull());
        controller.quickReply("Bearer qa",new C2cMeController.QuickReply(null,"  ＱＡ\r\nreply  "));
        verify(profiles).saveReply(12,null,"QA\nreply");
    }
}
