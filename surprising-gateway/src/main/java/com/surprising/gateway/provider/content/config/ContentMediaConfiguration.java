package com.surprising.gateway.provider.content.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ContentMediaProperties.class)
public class ContentMediaConfiguration {
}
