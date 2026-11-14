package com.surprising.websocket.provider.netty;

import com.surprising.gateway.provider.auth.utils.ClientIpResolver;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.websocket.api.WebSocketApiPaths;
import com.surprising.websocket.provider.config.WebSocketProperties;
import com.surprising.websocket.provider.handler.ClientWebSocketHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Embedded Netty WebSocket server replacing Tomcat's blocking WebSocket endpoint.
 * Shares the Spring application context (auth, limits, subscription registry, Kafka fanout)
 * while handling all WebSocket I/O on non-blocking Netty EventLoops.
 */
@Slf4j
@Component
public class NettyWebSocketServer implements SmartLifecycle {

    private final WebSocketProperties properties;
    private final ClientIpResolver clientIpResolver;
    private final NettyWebSocketChannelHandler channelHandler;

    private volatile boolean running;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile int boundPort = -1;

    public NettyWebSocketServer(ClientWebSocketHandler handler,
                                WebSocketProperties properties,
                                GatewayProperties gatewayProperties) {
        this.properties = properties;
        this.clientIpResolver = new ClientIpResolver(gatewayProperties);
        this.channelHandler = new NettyWebSocketChannelHandler(handler);
    }

    public int getPort() {
        return boundPort;
    }

    @Override
    public synchronized void start() {
        if (running || !properties.getNetty().isEnabled()) {
            return;
        }
        var netty = properties.getNetty();
        int bossThreads = Math.max(1, netty.getBossThreads());
        int workerThreads = Math.max(0, netty.getWorkerThreads());
        bossGroup = new NioEventLoopGroup(bossThreads);
        workerGroup = new NioEventLoopGroup(workerThreads);
        var waterMark = new WriteBufferWaterMark(
                Math.max(1024, netty.getWriteBufferLowWaterMark()),
                Math.max(2048, netty.getWriteBufferHighWaterMark()));
        long idleSeconds = netty.getIdleTimeout() == null ? 0L : Math.max(0L, netty.getIdleTimeout().toSeconds());
        int maxFramePayload = Math.max(1024, netty.getMaxFramePayloadLength());

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 4096)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, waterMark)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        var pipeline = ch.pipeline();
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(65536));
                        pipeline.addLast(new NettyWebSocketHandshakeHandler(properties, clientIpResolver));
                        if (idleSeconds > 0L) {
                            pipeline.addLast(new IdleStateHandler(idleSeconds, 0L, 0L, TimeUnit.SECONDS));
                        }
                        pipeline.addLast(new WebSocketServerProtocolHandler(
                                WebSocketApiPaths.WS_V1, null, true, maxFramePayload, false, true));
                        pipeline.addLast(channelHandler);
                    }
                });

        try {
            serverChannel = bootstrap.bind(netty.getPort()).sync().channel();
            if (serverChannel.localAddress() instanceof InetSocketAddress inet) {
                boundPort = inet.getPort();
            }
            running = true;
            log.info("Netty WebSocket server started on port {} path={}", boundPort, WebSocketApiPaths.WS_V1);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            stop();
            throw new IllegalStateException("Interrupted while starting Netty WebSocket server", ex);
        } catch (RuntimeException ex) {
            stop();
            throw ex;
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        boundPort = -1;
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
            bossGroup = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
