package com.surprising.aeron.config;

import io.aeron.ChannelUri;
import io.aeron.CommonContext;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.env.StandardEnvironment;

/** 可选实时传输配置；空输出通道表示不启动实时传输资源。 */
@ConfigurationProperties(prefix = "surprising.realtime")
public record RealtimeConfiguration(@DefaultValue("") String channel, String directory,
        @DefaultValue("2101") int stream, @DefaultValue("") String controlChannel,
        @DefaultValue("2102") int controlStream) {
    public RealtimeConfiguration {
        channel = channel == null ? "" : channel.trim();
        controlChannel = controlChannel == null ? "" : controlChannel.trim();
        directory = directory == null ? CommonContext.getAeronDirectoryName() : directory.trim();
        if (directory.isEmpty() || stream <= 0 || controlStream <= 0)
            throw new IllegalArgumentException("realtime directory and positive stream IDs are required");
        if (!channel.isEmpty()) ChannelUri.parse(channel);
        if (!controlChannel.isEmpty()) ChannelUri.parse(controlChannel);
        if (channel.isEmpty() && !controlChannel.isEmpty())
            throw new IllegalArgumentException("realtime control channel requires an output channel");
    }
    public static RealtimeConfiguration standalone() {
        return Binder.get(new StandardEnvironment())
                .bindOrCreate("surprising.realtime", Bindable.of(RealtimeConfiguration.class));
    }
}
