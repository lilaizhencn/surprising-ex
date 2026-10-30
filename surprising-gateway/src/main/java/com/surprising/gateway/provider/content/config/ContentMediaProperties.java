package com.surprising.gateway.provider.content.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Shares Social's public R2 bucket, with a separate exchange-owned prefix. */
@ConfigurationProperties("surprising.content.r2")
public record ContentMediaProperties(boolean enabled, String endpoint, String accessKeyId,
                                     String secretAccessKey, String bucket, String publicBaseUrl) { }
