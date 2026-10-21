package com.surprising.gateway.c2c.service;

import com.surprising.gateway.c2c.exception.C2cProblem;

import com.surprising.gateway.c2c.config.C2cR2Properties;

import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.service.KycDocumentStorageException;
import com.surprising.gateway.provider.service.KycDocumentStorageService;
import com.surprising.gateway.provider.service.KycDocumentStorageUnavailableException;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/** 迁用 social R2 的 SDK 配置和预签名上传；桶和路径由服务端固定，交易对象不可覆盖。 */
@Service
@EnableConfigurationProperties(C2cR2Properties.class)
public class C2cR2Storage implements AutoCloseable {
    private final C2cR2Properties properties;
    private final S3Client client;
    private final S3Presigner presigner;
    private final KycDocumentStorageService filesystem;
    public C2cR2Storage(C2cR2Properties properties) {
        this.properties=properties;
        if(!properties.isEnabled()) {client=null;presigner=null;filesystem=null;return;}
        if(!properties.getPrefix().matches("private/trading/c2c/[A-Za-z0-9/_-]*") || properties.getPrefix().contains("//")
                || !properties.getPrefix().endsWith("/")) throw new IllegalStateException("C2C 文件目录配置无效");
        if("filesystem".equals(properties.getType())) {
            var local=new GatewayProperties();local.getKycDocuments().setEnabled(true);
            local.getKycDocuments().setType("filesystem");local.getKycDocuments().setRootPath(properties.getRootPath());
            filesystem=new KycDocumentStorageService(local);client=null;presigner=null;return;
        }
        if(!"s3".equals(properties.getType()) || properties.getEndpoint().isBlank() || properties.getAccessKeyId().isBlank()
                || properties.getSecretAccessKey().isBlank() || !properties.getBucket().matches("[a-z0-9][a-z0-9.-]{2,62}")
                || properties.getPresignedUrlExpirySeconds()<30 || properties.getPresignedUrlExpirySeconds()>900)
            throw new IllegalStateException("C2C R2 配置不完整");
        URI endpoint=URI.create(properties.getEndpoint());
        if(endpoint.getHost()==null || endpoint.getRawQuery()!=null || endpoint.getRawFragment()!=null
                || !("https".equals(endpoint.getScheme()) || ("http".equals(endpoint.getScheme())
                && Set.of("127.0.0.1","localhost").contains(endpoint.getHost())))) throw new IllegalStateException("C2C R2 端点配置无效");
        var credentials=StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.getAccessKeyId(),properties.getSecretAccessKey()));
        var configuration=S3Configuration.builder().pathStyleAccessEnabled(true).build();
        client=S3Client.builder().endpointOverride(endpoint).region(Region.of(properties.getRegion())).credentialsProvider(credentials)
                .serviceConfiguration(configuration).overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(20))
                        .apiCallAttemptTimeout(Duration.ofSeconds(15))).build();
        presigner=S3Presigner.builder().endpointOverride(endpoint).region(Region.of(properties.getRegion())).credentialsProvider(credentials)
                .serviceConfiguration(configuration).build();filesystem=null;
    }
    public void ensureEnabled() {
        if(!properties.isEnabled()) throw new KycDocumentStorageUnavailableException("C2C storage is disabled");
    }
    public void store(String key,byte[] bytes,String mime) {
        ensureEnabled();String object=objectKey(key);
        if(filesystem!=null) {filesystem.store(object,bytes,mime);return;}
        try {client.putObject(put(key,mime,bytes.length),RequestBody.fromBytes(bytes));}
        catch(RuntimeException failure) {throw new KycDocumentStorageException("C2C object write did not complete");}
    }
    public byte[] read(String key) {
        ensureEnabled();String object=objectKey(key);
        if(filesystem!=null) return filesystem.read(object);
        try(var response=client.getObject(GetObjectRequest.builder().bucket(properties.getBucket()).key(object).build())) {
            byte[] bytes=response.readNBytes(10485761);
            if(bytes.length>10485760) throw new KycDocumentStorageException("C2C object exceeds the size limit");
            return bytes;
        } catch(java.io.IOException | RuntimeException failure) {throw new KycDocumentStorageException("C2C object read did not complete");}
    }
    public Map<String,Object> presign(String key,String mime,int bytes) {
        ensureEnabled();
        if(presigner==null) throw C2cProblem.unavailable("当前存储不支持直传，请使用文件上传");
        var signed=presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(properties.getPresignedUrlExpirySeconds())).putObjectRequest(put(key,mime,bytes)).build());
        var headers=new LinkedHashMap<String,String>();
        signed.signedHeaders().forEach((name,values)->{
            // Host 由浏览器或 HTTP 客户端设置；其他签名头必须原样发送。
            if(!name.equalsIgnoreCase("host")) headers.put(name,String.join(",",values));
        });
        return Map.of("uploadUrl",signed.url().toString(),"headers",headers,"expiresAt",signed.expiration().toString());
    }
    private PutObjectRequest put(String key,String mime,int bytes) {
        if(bytes<1 || bytes>10485760) throw new IllegalArgumentException("文件大小无效");
        return PutObjectRequest.builder().bucket(properties.getBucket()).key(objectKey(key)).contentType(mime).contentLength((long)bytes)
                .overrideConfiguration(c->c.putHeader("If-None-Match","*")).build();
    }
    private String objectKey(String key) {
        if(key==null || !key.matches("c2c/[1-9][0-9]{0,18}/[a-f0-9-]{36}")) throw new IllegalArgumentException("文件路径无效");
        return properties.getPrefix()+key.substring(4);
    }
    @Override public void close() {if(client!=null)client.close();if(presigner!=null)presigner.close();}
}
