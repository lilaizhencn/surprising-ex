package com.surprising.account.provider.service;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.client.ResultUnknownException;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOpenInterestCodec;
import com.surprising.aeron.protocol.CoreOpenInterestView;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.locks.LockSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AccountAeronGateway implements AutoCloseable {

    private final AeronClientPool clients;

    @Autowired
    public AccountAeronGateway(AccountProperties properties) {
        ProductLine productLine = properties.getKafka().getProductLine();
        AccountProperties.Aeron aeron = properties.getAeron();
        this.clients = new AeronClientPool("account", productLine, aeron.getHostnames(),
                aeron.getEgressHostname(), aeron.getResponseTimeout(), aeron.getClientConnections(),
                aeron.getSourceIdentity());
    }

    AccountAeronGateway(AeronClientPool clients) {
        this.clients = Objects.requireNonNull(clients, "clients");
    }

    public CoreResponse command(CoreMessageType type, UUID commandId, long userId, byte[] payload) {
        try {
            return requireApplied(clients.command(type, commandId, userId, payload));
        } catch (ResultUnknownException exception) {
            return requireApplied(awaitCommandResult(commandId, userId, type));
        }
    }

    private CoreResponse awaitCommandResult(UUID commandId, long userId, CoreMessageType commandType) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 40; attempt++) {
            try {
                CoreResponse response = clients.commandResult(commandId, userId);
                if (response.status() == ResponseStatus.OK) {
                    return response;
                }
                if (response.resultCode() != CoreResultCode.RESULT_UNKNOWN_OUTSIDE_RETENTION) {
                    return response;
                }
            } catch (ResultUnknownException | CompletionException exception) {
                last = exception;
            }
            LockSupport.parkNanos(100_000_000L);
        }
        ResultUnknownException failure = new ResultUnknownException(
                commandId, commandType + " result remains unknown");
        if (last != null) {
            failure.addSuppressed(last);
        }
        throw failure;
    }

    public CoreResponse query(CoreMessageType type, UUID queryId, byte[] payload) {
        return clients.query(type, queryId, 0, payload);
    }

    public CoreUserStateView userState(long userId) {
        CoreResponse response = clients.query(CoreMessageType.USER_STATE_QUERY, UUID.randomUUID(), userId, new byte[0]);
        if (response.status() == ResponseStatus.REJECTED
                && response.resultCode() == com.surprising.aeron.protocol.CoreResultCode.ENTITY_NOT_FOUND) {
            return null;
        }
        if (response.status() != ResponseStatus.OK) {
            throw new AccountStateUnavailableException("Aeron user query failed: " + response.resultCode());
        }
        return CoreStateQueryCodec.decodeUserState(response.data());
    }

    public OpenInterestState openInterest() {
        CoreResponse response = clients.query(CoreMessageType.OPEN_INTEREST_QUERY, UUID.randomUUID(), 0L, new byte[0]);
        if (response.status() != com.surprising.aeron.protocol.ResponseStatus.OK) {
            throw new AccountStateUnavailableException("Aeron open interest query failed: " + response.resultCode());
        }
        return new OpenInterestState(response.appliedCommandCount(), CoreOpenInterestCodec.decode(response.data()));
    }

    public record OpenInterestState(long revision, List<CoreOpenInterestView> values) {
        public OpenInterestState {
            values = List.copyOf(values);
        }
    }

    @Override
    @PreDestroy
    public void close() {
        clients.close();
    }

    private static CoreResponse requireApplied(CoreResponse response) {
        if (response.commandStatus() != ResponseStatus.APPLIED) {
            throw new AccountCommandRejectedException(response.resultCode().name(),
                    "Aeron command rejected: " + response.resultCode());
        }
        return response;
    }

}
