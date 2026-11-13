package com.surprising.realtime.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.core.*;

class ValkeyScriptPipelineTest {
    @Test void loadsOnceBeforeAllShaUpdatesOnTheSamePipelineConnection() {
        var redis = mock(StringRedisTemplate.class);
        var connection = mock(RedisConnection.class);
        var scripts = mock(RedisScriptingCommands.class);
        when(connection.scriptingCommands()).thenReturn(scripts);
        when(redis.executePipelined(any(RedisCallback.class))).thenAnswer(call -> {
            call.<RedisCallback<?>>getArgument(0).doInRedis(connection); return List.of();
        });
        new ValkeyReadViewStore(redis).applyCommits(commits());
        var order=inOrder(scripts);
        order.verify(scripts).scriptLoad(any(byte[].class));
        order.verify(scripts,times(2)).evalSha(anyString(),eq(ReturnType.INTEGER),eq(2),any(byte[][].class));
        verify(scripts,never()).eval(any(byte[].class),any(),anyInt(),any(byte[][].class));
    }

    @Test void singleUpdateAvoidsLoadingAndShaOverhead() {
        var redis = mock(StringRedisTemplate.class);
        var connection = mock(RedisConnection.class);
        var scripts = mock(RedisScriptingCommands.class);
        when(connection.scriptingCommands()).thenReturn(scripts);
        when(redis.executePipelined(any(RedisCallback.class))).thenAnswer(call -> {
            call.<RedisCallback<?>>getArgument(0).doInRedis(connection); return List.of();
        });
        new ValkeyReadViewStore(redis).applyCommits(List.of(List.of(frame(42,1))));
        verify(scripts).eval(any(byte[].class),eq(ReturnType.INTEGER),eq(2),any(byte[][].class));
        verify(scripts,never()).scriptLoad(any(byte[].class));
        verify(scripts,never()).evalSha(anyString(),any(),anyInt(),any(byte[][].class));
    }

    @Test void pipelineFailureIsNeverBlindlyReplayed() {
        var redis = mock(StringRedisTemplate.class);
        var failure = new RedisPipelineException(new IllegalStateException("NOSCRIPT"));
        when(redis.executePipelined(any(RedisCallback.class))).thenThrow(failure);
        assertThatThrownBy(()->new ValkeyReadViewStore(redis).applyCommits(commits())).isSameAs(failure);
        verify(redis,times(1)).executePipelined(any(RedisCallback.class));
        verify(redis,never()).execute(any(RedisCallback.class));
    }
    private static List<List<RealtimeFrame>> commits() { return List.of(List.of(frame(42,1)), List.of(frame(43,2))); }
    private static RealtimeFrame frame(long user,long sequence) {
        return new RealtimeFrame(ProductLine.SPOT,RealtimeFrame.Kind.BALANCE,user,sequence,0,0,0,"","USDT",new byte[0]);
    }
}
