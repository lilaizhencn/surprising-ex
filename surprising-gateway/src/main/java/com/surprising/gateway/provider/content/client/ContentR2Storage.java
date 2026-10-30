package com.surprising.gateway.provider.content.client;

import com.surprising.gateway.provider.content.config.ContentMediaProperties;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
public class ContentR2Storage {
    public static final String PREFIX = "public/exchange/content/";
    private static final Pattern KEY = Pattern.compile(Pattern.quote(PREFIX)+"[0-9a-f-]{36}\\.(jpg|png|gif|webp|mp3|m4a|wav|mp4)");
    private final ContentMediaProperties properties;
    private final S3Client client;
    private final String publicBase;

    public ContentR2Storage(ContentMediaProperties properties) {
        this.properties = properties;
        URI base = URI.create(properties.publicBaseUrl());
        if (!"https".equals(base.getScheme()) || !"cdn.surprising.top".equals(base.getHost()) || base.getUserInfo()!=null
                || base.getPort()!=-1 || !(base.getPath().isEmpty() || base.getPath().equals("/"))
                || base.getQuery()!=null || base.getFragment()!=null || !"app-public".equals(properties.bucket()))
            throw new IllegalArgumentException("public content CDN configuration is incomplete");
        publicBase = properties.publicBaseUrl().replaceAll("/+$", "");
        if (!properties.enabled()) { client = null; return; }
        URI endpoint = URI.create(properties.endpoint());
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost()==null
                || !endpoint.getHost().endsWith(".r2.cloudflarestorage.com")
                || properties.accessKeyId()==null || properties.accessKeyId().isBlank()
                || properties.secretAccessKey()==null || properties.secretAccessKey().isBlank())
            throw new IllegalArgumentException("public content R2 configuration is incomplete");
        client = S3Client.builder().region(Region.US_EAST_1).endpointOverride(endpoint).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKeyId(),properties.secretAccessKey())))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(120)).apiCallAttemptTimeout(Duration.ofSeconds(60)))
                .build();
    }
    public void requireAvailable() {
        if (client==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Article media upload is unavailable. Please try again later.");
    }
    public void put(String key, String type, Path path) {
        requireAvailable(); requireKey(key);
        client.putObject(PutObjectRequest.builder().bucket(properties.bucket()).key(key).contentType(type)
                .cacheControl("public,max-age=31536000,immutable").build(),RequestBody.fromFile(path));
    }
    public void delete(String key) {
        requireAvailable(); requireKey(key);
        client.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucket()).key(key).build());
    }
    public URI publicUrl(String key) { requireKey(key); return URI.create(publicBase+"/"+key); }
    private void requireKey(String key) {
        if (key==null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("invalid article media key");
    }
    @PreDestroy public void close() { if (client!=null) client.close(); }
}
