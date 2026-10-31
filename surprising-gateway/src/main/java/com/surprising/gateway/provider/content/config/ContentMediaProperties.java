package com.surprising.gateway.provider.content.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Dedicated exchange public R2 bucket; private attachments use separate storage. */
@ConfigurationProperties("surprising.content.r2")
public record ContentMediaProperties(boolean enabled, String endpoint, String accessKeyId,
                                     String secretAccessKey, String bucket, String publicBaseUrl) { }
