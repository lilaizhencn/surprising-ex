package com.surprising.aeron.tools;

import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.ValkeyUserQueries;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Calls the production read/materialization implementation; never falls back to a Core query. */
final class OperationalUserQueries implements AutoCloseable {
    private final LettuceConnectionFactory connection;
    private final ValkeyUserQueries queries;
    private final ClusterOperationalSideLoad owner;
    private final List<Long> users;
    private final long[] lastSequence;
    private final long interval;
    private long next,ready,unavailable;
    private int cursor;

    OperationalUserQueries(List<Long> users,ClusterOperationalSideLoad owner) {
        this.users=List.copyOf(users);this.owner=owner;lastSequence=new long[users.size()];
        int rate=Integer.getInteger("surprising.aeron.operational-query-rate",1000);
        if(rate<1 || rate>100000)throw new IllegalArgumentException("operational query rate must be in [1,100000]");
        interval=1_000_000_000L/rate;
        String host=System.getProperty("surprising.aeron.operational-valkey-host");
        if(host==null || host.isBlank())throw new IllegalArgumentException("operational-valkey-host is required");
        var redis=new RedisStandaloneConfiguration(host,Integer.getInteger("surprising.aeron.operational-valkey-port",6379));
        connection=new LettuceConnectionFactory(redis,LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build());
        connection.afterPropertiesSet();connection.start();
        var template=new StringRedisTemplate(connection);template.afterPropertiesSet();
        queries=new ValkeyUserQueries(template);
    }
    void step() {
        long now=System.nanoTime();
        if(now<next)LockSupport.parkNanos(next-now);
        long start=System.nanoTime();next=Math.max(next+interval,start);
        int index=cursor++%users.size();long user=users.get(index);
        try {
            var view=queries.require(ProductLine.LINEAR_PERPETUAL,user,null);
            if(view.account()==null || view.account().userId()!=user || view.exportSequence()<lastSequence[index])
                throw new IllegalStateException("read model identity/sequence regression");
            lastSequence[index]=view.exportSequence();
            ready++;
            owner.record("VALKEY_QUERY_READY",start,System.nanoTime());
        }catch(ResponseStatusException e) {
            if(e.getStatusCode().value()!=503)throw e;
            unavailable++;
            owner.record("VALKEY_QUERY_UNAVAILABLE",start,System.nanoTime());
        }
    }
    void print(){System.out.printf("operationalReadModel readyIncludingWarmup=%d unavailableIncludingWarmup=%d coreFallbacks=0%n",ready,unavailable);}
    @Override public void close(){connection.destroy();}
}
