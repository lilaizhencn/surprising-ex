package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.model.C2cPaymentAccountInput;
import com.surprising.gateway.c2c.utils.C2cPaymentFormats;
import com.surprising.gateway.c2c.controller.C2cChatController;
import com.surprising.gateway.c2c.service.C2cPaymentAccounts;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class C2cAttachmentsPostgresTest {
    private C2cHttpFixture fixture;
    @BeforeEach void setup() throws Exception {
        fixture=new C2cHttpFixture();fixture.db.jdbc.update("UPDATE c2c_profiles SET receive_strangers=true");
    }
    @AfterEach void cleanup() {if(fixture!=null)fixture.close();}
    static byte[] png() throws Exception {
        var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var output=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",output);return output.toByteArray();
    }
    private Map<String,Object> upload(long user,UUID id,String purpose,UUID order,UUID conversation,byte[] bytes,String mime,int status) throws Exception {
        var request=MockMvcRequestBuilders.multipart("/api/v1/c2c/attachments/"+id)
                .file(new MockMultipartFile("file","qa-proof.png",mime,bytes)).param("purpose",purpose)
                .header("Authorization","Bearer qa-"+user);
        if(order!=null)request.param("orderId",order.toString());if(conversation!=null)request.param("conversationId",conversation.toString());
        var response=fixture.mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(status);
        return fixture.json.readValue(response.getContentAsString(),Map.class);
    }
    private UUID order() throws Exception {
        UUID ad=fixture.publish("SELL");return UUID.fromString((String)fixture.create(fixture.quote(ad,12,"2"),12,
                "attachment-order-"+UUID.randomUUID(),"123456",200).get("resultId"));
    }
    @Test void privateUploadIsVerifiedAndIdempotentAndNeverExposesStorageKeys() throws Exception {
        UUID id=UUID.randomUUID();byte[] png=png();
        var first=upload(11,id,"PAYMENT_QR",null,null,png,"image/png",200);
        assertThat(first.get("status")).isEqualTo("READY");assertThat(first).doesNotContainKeys("storageKey","url","secretKey");
        assertThat(upload(11,id,"PAYMENT_QR",null,null,png,"image/png",200)).isEqualTo(first);
        upload(12,id,"PAYMENT_QR",null,null,png,"image/png",403);
        byte[] changed=png.clone();changed[changed.length-1]^=1;
        upload(11,id,"PAYMENT_QR",null,null,changed,"image/png",409);
        var response=fixture.perform(HttpMethod.GET,"/api/v1/c2c/attachments/"+id+"/content",11,null).getResponse();
        assertThat(response.getContentAsByteArray()).isEqualTo(png);assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        fixture.call(HttpMethod.GET,"/api/v1/c2c/attachments/"+id+"/content",12,null,403);
    }
    @Test void unknownLookupDoesNotCreateUploadsAndDisabledStorageFailsTruthfully() throws Exception {
        UUID id=UUID.randomUUID();
        assertThat(fixture.call(HttpMethod.GET,"/api/v1/c2c/attachments/"+id,12,null,200).get("status")).isEqualTo("UNKNOWN");
        fixture.r2.setEnabled(false);
        upload(12,id,"PAYMENT_QR",null,null,png(),"image/png",503);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_attachments",Integer.class)).isZero();
    }
    @Test void rollingUploadQuotaIncludesAbandonedReservationsButDoesNotPermanentlyBlockOldUsers() throws Exception {
        fixture.db.jdbc.update("""
                WITH ids AS(SELECT gen_random_uuid() AS id FROM generate_series(1,60))
                INSERT INTO c2c_attachments(attachment_id,user_id,purpose,storage_key,content_type,byte_count,sha256,original_name,created_at)
                SELECT id,12,'PAYMENT_QR','c2c/12/'||id,'image/png',10485760,repeat('0',64),'qa-old.png',now()-interval '25 hours' FROM ids
                """);
        upload(12,UUID.randomUUID(),"PAYMENT_QR",null,null,png(),"image/png",200);
        fixture.db.jdbc.update("""
                WITH ids AS(SELECT gen_random_uuid() AS id FROM generate_series(1,99))
                INSERT INTO c2c_attachments(attachment_id,user_id,purpose,storage_key,content_type,byte_count,sha256,original_name)
                SELECT id,12,'PAYMENT_QR','c2c/12/'||id,'image/png',8,repeat('0',64),'qa-pending.png' FROM ids
                """);
        upload(12,UUID.randomUUID(),"PAYMENT_QR",null,null,png(),"image/png",429);
    }
    @Test void incompleteUploadCanResumeOnlyWithItsOriginalContents() throws Exception {
        UUID id=UUID.randomUUID();byte[] png=png();
        upload(12,id,"PAYMENT_QR",null,null,png,"image/png",200);
        // 模拟对象已完整写入，但 READY 数据库提交前进程终止。
        fixture.db.jdbc.execute("ALTER TABLE c2c_attachments DISABLE TRIGGER c2c_attachment_guard");
        fixture.db.jdbc.update("UPDATE c2c_attachments SET status='UPLOADING' WHERE attachment_id=?",id);
        fixture.db.jdbc.execute("ALTER TABLE c2c_attachments ENABLE TRIGGER c2c_attachment_guard");
        assertThat(upload(12,id,"PAYMENT_QR",null,null,png,"image/png",200).get("status")).isEqualTo("READY");
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_attachments",Integer.class)).isEqualTo(1);
    }
    @Test void rejectsMimeSpoofingQrPdfAndForeignOrderProof() throws Exception {
        upload(12,UUID.randomUUID(),"PAYMENT_QR",null,null,"not-a-png-image".getBytes(),"image/png",400);
        upload(12,UUID.randomUUID(),"PAYMENT_QR",null,null,"%PDF-1.7\n%%EOF".getBytes(),"application/pdf",400);
        UUID order=order();upload(13,UUID.randomUUID(),"PROOF",order,null,png(),"image/png",403);
        upload(12,UUID.randomUUID(),"PROOF",order,null,png(),"image/png",200);
    }
    @Test void qrReadUsesTheOriginalOrderSnapshotAfterPaymentAccountChanges() throws Exception {
        UUID original=UUID.randomUUID(),newQr=UUID.randomUUID();
        upload(11,original,"PAYMENT_QR",null,null,png(),"image/png",200);
        var account=fixture.bean(C2cPaymentAccounts.class).get(11,fixture.makerAccount,false);
        fixture.bean(C2cPaymentAccounts.class).save(11,account.id(),account.revision(),"CNY","ALIPAY",
                C2cPaymentFormats.input(new C2cPaymentAccountInput.Details("QA Maker","qa-maker-account","",original)));
        fixture.db.jdbc.update("UPDATE c2c_payment_accounts SET status='APPROVED'");
        UUID order=order();upload(11,newQr,"PAYMENT_QR",null,null,png(),"image/png",200);
        account=fixture.bean(C2cPaymentAccounts.class).get(11,fixture.makerAccount,false);
        fixture.bean(C2cPaymentAccounts.class).save(11,account.id(),account.revision(),"CNY","ALIPAY",
                C2cPaymentFormats.input(new C2cPaymentAccountInput.Details("QA Maker","qa-new-account","",newQr)));
        var response=fixture.perform(HttpMethod.GET,"/api/v1/c2c/attachments/"+original+"/content?orderId="+order,12,null).getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        fixture.call(HttpMethod.GET,"/api/v1/c2c/attachments/"+newQr+"/content?orderId="+order,12,null,403);
        fixture.call(HttpMethod.GET,"/api/v1/c2c/attachments/"+original+"/content?orderId="+order,13,null,403);
    }
    @Test void messageAttachmentsAreScopedAndNotReadableBeforeSending() throws Exception {
        UUID conversation=UUID.fromString((String)fixture.call(HttpMethod.POST,"/api/v1/c2c/conversations",12,
                new C2cChatController.Start(null,11L),200).get("conversationId"));
        UUID other=UUID.fromString((String)fixture.call(HttpMethod.POST,"/api/v1/c2c/conversations",12,
                new C2cChatController.Start(null,13L),200).get("conversationId"));
        UUID attachment=UUID.randomUUID();upload(12,attachment,"MESSAGE",null,conversation,png(),"image/png",200);
        fixture.call(HttpMethod.GET,"/api/v1/c2c/attachments/"+attachment+"/content",11,null,403);
        fixture.call(HttpMethod.POST,"/api/v1/c2c/conversations/"+other+"/messages",12,
                new C2cChatController.Send(UUID.randomUUID(),"其他会话附件",attachment),403);
        fixture.call(HttpMethod.POST,"/api/v1/c2c/conversations/"+conversation+"/messages",12,
                new C2cChatController.Send(UUID.randomUUID(),"",attachment),200);
        assertThat(fixture.perform(HttpMethod.GET,"/api/v1/c2c/attachments/"+attachment+"/content",11,null).getResponse().getStatus()).isEqualTo(200);
        fixture.call(HttpMethod.GET,"/api/v1/c2c/attachments/"+attachment+"/content",13,null,403);
    }
}
