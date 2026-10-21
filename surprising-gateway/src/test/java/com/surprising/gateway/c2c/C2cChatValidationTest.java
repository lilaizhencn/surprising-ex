package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.controller.C2cChatController;
import com.surprising.gateway.c2c.service.C2cAccess;
import com.surprising.gateway.c2c.service.C2cAttachments;
import com.surprising.gateway.c2c.service.C2cConversations;
import com.surprising.gateway.c2c.service.C2cMessages;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.jupiter.api.*;

class C2cChatValidationTest {
    private C2cConversations conversations;
    private C2cMessages messages;
    private C2cAttachments attachments;
    private C2cChatController controller;
    @BeforeEach void setup() {
        conversations=mock(C2cConversations.class);messages=mock(C2cMessages.class);attachments=mock(C2cAttachments.class);
        var access=mock(C2cAccess.class);when(access.user(any())).thenReturn(C2cHttpFixture.principal(12));controller=new C2cChatController(access,conversations,messages,attachments);
    }
    @Test void invalidConversationAndPaginationInputsNeverReachTheService() {
        assertThatThrownBy(()->controller.start("Bearer qa",new C2cChatController.Start(null,null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->controller.start("Bearer qa",new C2cChatController.Start(null,0L))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->controller.list("Bearer qa","",51,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->controller.page("Bearer qa",UUID.randomUUID(),"1","2","0",20,false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->controller.page("Bearer qa",UUID.randomUUID(),"1",null,"0",20,true)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(conversations,messages,attachments);
    }
    @Test void invalidMessageAndCursorFormatsAreRejectedAtTheController() {
        UUID id=UUID.randomUUID();
        assertThatThrownBy(()->controller.send("Bearer qa",id,new C2cChatController.Send(null,"text",null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->controller.send("Bearer qa",id,new C2cChatController.Send(UUID.randomUUID(),"bad\u0001text",null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->controller.send("Bearer qa",id,new C2cChatController.Send(UUID.randomUUID(),"  ",null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->controller.read("Bearer qa",id,new C2cChatController.Read("1e3","0"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(conversations,messages,attachments);
    }
    @Test void uploadIdentityFileNamePurposeAndHashAreValidatedBeforeStorageAccess() {
        UUID id=UUID.randomUUID();
        for(var request:new C2cChatController.Upload[]{
                new C2cChatController.Upload("PAYMENT_QR",null,null,"../bad.png","image/png",10,"a".repeat(64)),
                new C2cChatController.Upload("PAYMENT_QR",null,null,"ok.pdf","application/pdf",10,"a".repeat(64)),
                new C2cChatController.Upload("MESSAGE",null,null,"ok.png","image/png",10,"a".repeat(64)),
                new C2cChatController.Upload("PAYMENT_QR",null,null,"ok.png","image/png",10,"INVALID")})
            assertThatThrownBy(()->controller.presign("Bearer qa",id,request)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(conversations,messages,attachments);
    }
}
