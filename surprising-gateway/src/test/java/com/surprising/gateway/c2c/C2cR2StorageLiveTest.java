package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.service.C2cR2Storage;

import com.surprising.gateway.c2c.config.C2cR2Properties;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/** 只写本用例随机独占目录，finally 删除准确对象；不得输出临时签名 URL 或运行凭据。 */
@EnabledIfEnvironmentVariable(named="C2C_R2_LIVE_QA",matches="true")
class C2cR2StorageLiveTest {
    @Test void actualR2PresignedUploadIsPrivateReadableAndCannotOverwriteEvidence() throws Exception {
        var properties=new C2cR2Properties();properties.setEnabled(true);
        properties.setEndpoint(required("C2C_R2_ENDPOINT"));properties.setAccessKeyId(required("C2C_R2_ACCESS_KEY_ID"));
        properties.setSecretAccessKey(required("C2C_R2_SECRET_ACCESS_KEY"));properties.setBucket(required("C2C_R2_BUCKET"));
        properties.setRegion(required("C2C_R2_REGION"));
        String prefix="private/trading/c2c/qa-20261020-"+UUID.randomUUID()+"/";
        properties.setPrefix(prefix);
        String key="c2c/12/"+UUID.randomUUID();byte[] bytes=C2cAttachmentsPostgresTest.png();
        var credentials=StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.getAccessKeyId(),properties.getSecretAccessKey()));
        try(var client=S3Client.builder().endpointOverride(URI.create(properties.getEndpoint())).region(Region.of(properties.getRegion()))
                .credentialsProvider(credentials).forcePathStyle(true).build();var storage=new C2cR2Storage(properties)) {
            try {
                var signed=storage.presign(key,"image/png",bytes.length);
                var headers=(Map<String,String>)signed.get("headers");
                assertThat(headers.entrySet().stream().anyMatch(e->e.getKey().equalsIgnoreCase("if-none-match") && e.getValue().equals("*"))).isTrue();
                var http=HttpClient.newHttpClient();
                var request=HttpRequest.newBuilder(URI.create((String)signed.get("uploadUrl"))).timeout(Duration.ofSeconds(20));
                headers.forEach((name,value)->{if(!name.equalsIgnoreCase("content-length"))request.header(name,value);});
                var first=http.send(request.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.discarding());
                assertThat(first.statusCode()).isIn(200,201);
                assertThat(storage.read(key)).isEqualTo(bytes);
                byte[] changed=bytes.clone();changed[changed.length-1]^=1;
                var second=http.send(request.PUT(HttpRequest.BodyPublishers.ofByteArray(changed)).build(),HttpResponse.BodyHandlers.discarding());
                assertThat(second.statusCode()).isEqualTo(412);
                assertThat(storage.read(key)).isEqualTo(bytes);
                var unauthorized=http.send(HttpRequest.newBuilder(URI.create(properties.getEndpoint()+"/"+properties.getBucket()+"/"+prefix+key.substring(4)))
                        .timeout(Duration.ofSeconds(15)).GET().build(),HttpResponse.BodyHandlers.discarding());
                assertThat(unauthorized.statusCode()).isBetween(400,499);
            } finally {
                client.deleteObject(DeleteObjectRequest.builder().bucket(properties.getBucket()).key(prefix+key.substring(4)).build());
            }
        }
    }
    private static String required(String name) {
        String value=System.getenv(name);if(value==null || value.isBlank()) throw new IllegalStateException("Missing test setting: "+name);return value;
    }
}
