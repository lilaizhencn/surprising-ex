package com.surprising.realtime.provider;

import com.surprising.product.api.ProductLine;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties("surprising.realtime.router")
public record RealtimeRouterProperties(
        String directory,
        String channel,
        Integer stream,
        Integer nodeStream,
        Map<ProductLine, String> controlChannels,
        Map<ProductLine, java.util.List<String>> controlDestinations) {
    public RealtimeRouterProperties(
            String directory,
            String channel,
            Integer stream,
            Integer nodeStream,
            Map<ProductLine, String> controlChannels) {
        this(directory, channel, stream, nodeStream, controlChannels, Map.of());
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public RealtimeRouterProperties {
        if (directory == null) directory = io.aeron.CommonContext.getAeronDirectoryName();
        if (channel == null || channel.isBlank())
            throw new IllegalArgumentException("router channel is required");
        if (stream == null) stream = 2101;
        if (nodeStream == null) nodeStream = 2103;
        controlChannels = controlChannels == null ? Map.of() : Map.copyOf(controlChannels);
        controlDestinations =
                controlDestinations == null ? Map.of() : Map.copyOf(controlDestinations);
        if (!controlChannels.keySet().containsAll(controlDestinations.keySet()))
            throw new IllegalArgumentException(
                    "control destinations require a product publication channel");
    }
}
