package com.surprising.risk.provider.service;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.client.CoreCommandOutcome;
import com.surprising.aeron.client.ResultUnknownException;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreRiskQueryCodec;
import com.surprising.aeron.protocol.CoreRiskSnapshotView;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.risk.provider.config.RiskProperties;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.locks.LockSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RiskAeronGateway implements AutoCloseable {

    private final AeronClientPool clients;

    @Autowired
    public RiskAeronGateway(RiskProperties properties) {
        RiskProperties.Aeron aeron = properties.getAeron();
        clients = new AeronClientPool("risk", properties.getProductLine(), aeron.getHostnames(),
                aeron.getEgressHostname(), aeron.getResponseTimeout(), aeron.getClientConnections());
    }

    RiskAeronGateway(AeronClientPool clients) {
        this.clients = clients;
    }

    public List<CoreRiskSnapshotView> riskState(long userId) {
        var response = query(CoreMessageType.RISK_STATE_QUERY, userId);
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(response.resultCode() + ": Aeron risk state query failed");
        }
        return CoreRiskQueryCodec.decode(response.data());
    }

    public CoreUserStateView userState(long userId) {
        var response = query(CoreMessageType.USER_STATE_QUERY, userId);
        if (response.status() == ResponseStatus.REJECTED
                && response.resultCode() == com.surprising.aeron.protocol.CoreResultCode.ENTITY_NOT_FOUND) {
            return null;
        }
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(response.resultCode() + ": Aeron user state query failed");
        }
        return CoreStateQueryCodec.decodeUserState(response.data());
    }

    private CoreResponse query(CoreMessageType type, long userId) {
        CompletionException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return clients.query(type, UUID.randomUUID(), userId, new byte[0]);
            } catch (CompletionException exception) {
                if (!retryable(exception) || attempt == 3) {
                    throw exception;
                }
                last = exception;
                LockSupport.parkNanos(25_000_000L);
            }
        }
        throw last;
    }

    private static boolean retryable(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ResultUnknownException) {
                return true;
            }
            if (current instanceof CoreCommandOutcome.NotAcceptedException rejected) {
                return rejected.rejection().reason() == CoreCommandOutcome.NotAcceptedReason.NOT_CONNECTED;
            }
            current = current.getCause();
        }
        return false;
    }

    @Override
    @PreDestroy
    public void close() { clients.close(); }
}
