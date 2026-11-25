package com.surprising.derivatives.lifecycle;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Component;

/** Uses the shared per-product Aeron client pool for derivatives lifecycle services. */
@Component
public final class DerivativesAeronClient {

    private final AeronClientPool pool;

    public DerivativesAeronClient(AeronClientPool pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    public CoreResponse query(CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        return pool.query(type, commandId, userId, payload);
    }

    public CoreResponse command(CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        return pool.command(type, commandId, userId, payload);
    }

    public CompletableFuture<CoreResponse> commandAsync(CoreMessageType type, UUID commandId,
                                                         long userId, byte[] payload) {
        return pool.commandAsync(type, commandId, userId, payload);
    }
}
