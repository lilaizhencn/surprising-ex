package com.surprising.account.provider.service;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

@Service
public class AccountAeronGateway implements AutoCloseable {

    private final ProductLine productLine;
    private final List<String> hostnames;
    private final String egressHostname;
    private final Duration responseTimeout;
    private final ClientSlot[] clients;
    private final AtomicInteger nextClient = new AtomicInteger();

    public AccountAeronGateway(AccountProperties properties) {
        this.productLine = properties.getKafka().getProductLine();
        AccountProperties.Aeron aeron = properties.getAeron();
        this.hostnames = aeron.getHostnames();
        this.egressHostname = aeron.getEgressHostname();
        this.responseTimeout = aeron.getResponseTimeout();
        this.clients = new ClientSlot[aeron.getClientConnections()];
        long processId = ProcessHandle.current().pid();
        long epoch = System.currentTimeMillis();
        for (int index = 0; index < clients.length; index++) {
            long sourceId = stableLong("account:" + productLine + ':' + processId + ':' + epoch + ':' + index);
            clients[index] = new ClientSlot(sourceId);
        }
    }

    public CoreResponse command(CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        ClientSlot slot = clients[Math.floorMod(nextClient.getAndIncrement(), clients.length)];
        synchronized (slot) {
            long sourceSequence = slot.nextSequence.incrementAndGet();
            long correlationId = slot.nextCorrelation.incrementAndGet();
            CoreMessage message = new CoreMessage(CoreMessageHeader.command(type, commandId, productLine,
                    CommandSource.GATEWAY, slot.sourceId, sourceSequence, userId,
                    Instant.now().toEpochMilli(), correlationId), payload);
            return requireApplied(submit(slot, message));
        }
    }

    public CoreUserStateView userState(long userId) {
        ClientSlot slot = clients[Math.floorMod(nextClient.getAndIncrement(), clients.length)];
        synchronized (slot) {
            long correlationId = slot.nextCorrelation.incrementAndGet();
            UUID queryId = UUID.randomUUID();
            CoreMessage query = new CoreMessage(CoreMessageHeader.query(CoreMessageType.USER_STATE_QUERY,
                    queryId, productLine, CommandSource.GATEWAY, slot.sourceId, 0, userId,
                    Instant.now().toEpochMilli(), correlationId), new byte[0]);
            CoreResponse response = submit(slot, query);
            if (response.status() == ResponseStatus.REJECTED
                    && response.resultCode() == com.surprising.aeron.protocol.CoreResultCode.ENTITY_NOT_FOUND) {
                return null;
            }
            if (response.status() != ResponseStatus.OK) {
                throw new AccountStateUnavailableException("Aeron user query failed: " + response.resultCode());
            }
            return CoreStateQueryCodec.decodeUserState(response.data());
        }
    }

    @Override
    @PreDestroy
    public void close() {
        RuntimeException failure = null;
        for (ClientSlot slot : clients) {
            try {
                if (slot.client != null) slot.client.close();
            } catch (RuntimeException exception) {
                failure = failure == null ? exception : failure;
            }
        }
        if (failure != null) throw failure;
    }

    private static CoreResponse requireApplied(CoreResponse response) {
        if (response.commandStatus() != ResponseStatus.APPLIED) {
            throw new AccountCommandRejectedException(response.resultCode().name(),
                    "Aeron command rejected: " + response.resultCode());
        }
        return response;
    }

    private SurprisingAeronClient client(ClientSlot slot) {
        if (slot.client == null) {
            slot.client = SurprisingAeronClient.connect(productLine, hostnames, egressHostname, responseTimeout);
        }
        return slot.client;
    }

    private CoreResponse submit(ClientSlot slot, CoreMessage message) {
        try {
            return client(slot).submit(message);
        } catch (RuntimeException exception) {
            if (slot.client != null) {
                try {
                    slot.client.close();
                } catch (RuntimeException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
                slot.client = null;
            }
            throw exception;
        }
    }

    private static long stableLong(String value) {
        UUID uuid = UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
        long result = uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
        return result == 0 ? 1 : result;
    }

    private static final class ClientSlot {
        private SurprisingAeronClient client;
        private final long sourceId;
        private final AtomicLong nextSequence = new AtomicLong();
        private final AtomicLong nextCorrelation = new AtomicLong();

        private ClientSlot(long sourceId) {
            this.sourceId = sourceId;
        }
    }
}
