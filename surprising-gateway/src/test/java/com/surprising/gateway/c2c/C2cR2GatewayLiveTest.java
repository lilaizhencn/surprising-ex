package com.surprising.gateway.c2c;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/** 真实 PostgreSQL + Gateway MVC + 实际 R2；身份外部入口受控，不启动 Core 或 wallet。 */
@EnabledIfEnvironmentVariable(named="C2C_R2_LIVE_QA",matches="true")
class C2cR2GatewayLiveTest {
    @Test void presignedUploadRequiresConfirmationAndGatewayAuthorizesEveryEvidenceRead() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("INSTRUMENT_TEST_JDBC_URL")!=null);
        var properties=new C2cR2Properties();properties.setEnabled(true);
        properties.setEndpoint(System.getenv("C2C_R2_ENDPOINT"));properties.setAccessKeyId(System.getenv("C2C_R2_ACCESS_KEY_ID"));
        properties.setSecretAccessKey(System.getenv("C2C_R2_SECRET_ACCESS_KEY"));properties.setBucket(System.getenv("C2C_R2_BUCKET"));
        properties.setRegion(System.getenv("C2C_R2_REGION"));String prefix="private/trading/c2c/qa-gateway-20261020-"+UUID.randomUUID()+"/";
        properties.setPrefix(prefix);UUID id=UUID.randomUUID();String key=prefix+"12/"+id;byte[] bytes=C2cAttachmentsPostgresTest.png();
        var credentials=StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.getAccessKeyId(),properties.getSecretAccessKey()));
        try(var client=S3Client.builder().endpointOverride(URI.create(properties.getEndpoint())).region(Region.of(properties.getRegion()))
                .credentialsProvider(credentials).forcePathStyle(true).build();var storage=new C2cR2Storage(properties);var fixture=new C2cHttpFixture()) {
            var attachments=C2cDatabaseFixture.attachments(fixture.db.jdbc,fixture.bean(C2cConversations.class),fixture.bean(C2cOrdersRepository.class),
                    fixture.bean(C2cPaymentAccounts.class),storage,fixture.db.transactions.getTransactionManager());
            var mvc=MockMvcBuilders.standaloneSetup(new C2cChatController(fixture.bean(C2cAccess.class),fixture.bean(C2cConversations.class),
                    fixture.bean(C2cMessages.class),attachments)).setControllerAdvice(new C2cHttpErrors()).build();
            fixture.db.jdbc.update("UPDATE c2c_profiles SET receive_strangers=true");
            UUID conversation=UUID.fromString((String)fixture.bean(C2cConversations.class).start(12,null,11L).get("conversationId"));
            var upload=new C2cChatController.Upload("MESSAGE",null,conversation,"qa-evidence.png","image/png",bytes.length,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            String path="/api/v1/c2c/attachments/"+id;
            try {
                var response=mvc.perform(MockMvcRequestBuilders.post(path+"/presigned-put").header("Authorization","Bearer qa-12")
                        .contentType(MediaType.APPLICATION_JSON).content(fixture.json.writeValueAsBytes(upload))).andReturn().getResponse();
                assertThat(response.getStatus()).isEqualTo(200);
                var signed=fixture.json.readValue(response.getContentAsString(),Map.class);
                assertThat(signed.get("status")).isEqualTo("UPLOADING");
                assertThat(mvc.perform(MockMvcRequestBuilders.post(path+"/confirm").header("Authorization","Bearer qa-13")).andReturn().getResponse().getStatus()).isEqualTo(403);
                assertThatThrownBy(()->fixture.bean(C2cMessages.class).send(12,conversation,UUID.randomUUID(),"尚未确认",id)).isInstanceOf(IllegalArgumentException.class);
                var http=HttpClient.newHttpClient();var put=HttpRequest.newBuilder(URI.create((String)signed.get("uploadUrl"))).timeout(Duration.ofSeconds(20));
                ((Map<String,String>)signed.get("headers")).forEach((name,value)->{if(!name.equalsIgnoreCase("content-length"))put.header(name,value);});
                assertThat(http.send(put.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isIn(200,201);
                var confirmed=mvc.perform(MockMvcRequestBuilders.post(path+"/confirm").header("Authorization","Bearer qa-12")).andReturn().getResponse();
                assertThat(confirmed.getStatus()).isEqualTo(200);
                assertThat(fixture.json.readValue(confirmed.getContentAsString(),Map.class).get("status")).isEqualTo("READY");
                fixture.bean(C2cMessages.class).send(12,conversation,UUID.randomUUID(),"实际 R2 图片凭证",id);
                var download=mvc.perform(MockMvcRequestBuilders.get(path+"/content").header("Authorization","Bearer qa-11")).andReturn().getResponse();
                assertThat(download.getStatus()).isEqualTo(200);assertThat(download.getContentAsByteArray()).isEqualTo(bytes);
                assertThat(download.getHeader("Cache-Control")).isEqualTo("no-store");
                assertThat(mvc.perform(MockMvcRequestBuilders.get(path+"/content").header("Authorization","Bearer qa-13")).andReturn().getResponse().getStatus()).isEqualTo(403);
                assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents",Integer.class)).isZero();
            } finally {client.deleteObject(DeleteObjectRequest.builder().bucket(properties.getBucket()).key(key).build());}
        }
    }
}
