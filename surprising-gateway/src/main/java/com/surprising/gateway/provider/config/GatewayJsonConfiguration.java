package com.surprising.gateway.provider.config;

import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.context.annotation.*;
import tools.jackson.core.StreamReadConstraints;

@Configuration(proxyBeanMethods = false)
public class GatewayJsonConfiguration {
    @Bean public JsonFactoryBuilderCustomizer gatewayJsonLimits() {
        return builder -> builder.streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(64).maxStringLength(262144).maxNumberLength(64).maxNameLength(128)
                .maxDocumentLength(1024 * 1024).build());
    }
}
