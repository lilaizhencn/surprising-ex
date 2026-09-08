package com.surprising.aeron.benchmarks.workload;

import com.surprising.aeron.client.*;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** One producer owns each endpoint. Independent actors never share request sequences or queues. */
final class OperationalEndpoint implements AutoCloseable {
    final AeronClientPool client;
    private final long source;
    private long request;
    private final ClusterOperationalSideLoad owner;

    OperationalEndpoint(long seed, String name, int window, ClusterOperationalSideLoad owner) {
        source = seed;
        this.owner = owner;
        client = new AeronClientPool(name, ProductLine.LINEAR_PERPETUAL,
                List.of(System.getProperty("surprising.aeron.hostnames").split(",")),
                System.getProperty("surprising.aeron.egress-hostname"), Duration.ofSeconds(30),
                name+"-"+seed, UUID.randomUUID().toString(),
                new AeronClientCapacity(1,1,window,16,window,8,32));
    }

    CompletableFuture<CoreResponse> send(CoreMessageType type, long user, byte[] payload) {
        long start=System.nanoTime();
        return client.commandAsync(type,new UUID(source,++request),user,payload).thenApply(response -> {
            if(response.commandStatus()!=ResponseStatus.APPLIED)
                throw new IllegalStateException("operational "+type+" rejected: "+response.resultCode());
            owner.record(type.name(),start,System.nanoTime());
            if(type==CoreMessageType.PLACE_ORDER)
                owner.recordFills(start,CoreCommandResultCodec.decode(response.data()).executions().size());
            return response;
        });
    }

    CoreResponse command(CoreMessageType type,long user,byte[] payload) {
        return send(type,user,payload).join();
    }

    CoreResponse query(CoreMessageType type,long user,byte[] payload) {
        long start=System.nanoTime();
        var response=client.query(type,new UUID(source,++request),user,payload);
        if(response.status()!=ResponseStatus.OK)
            throw new IllegalStateException("operational query "+type+": "+response.resultCode());
        owner.record(type.name(),start,System.nanoTime());
        return response;
    }

    CoreUserStateView user(long id) {
        return CoreStateQueryCodec.decodeUserState(query(CoreMessageType.USER_STATE_QUERY,id,new byte[0]).data());
    }

    @Override public void close() { client.close(); }
}
