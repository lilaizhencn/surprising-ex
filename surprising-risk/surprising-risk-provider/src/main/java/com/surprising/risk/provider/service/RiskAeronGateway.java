package com.surprising.risk.provider.service;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreRiskQueryCodec;
import com.surprising.aeron.protocol.CoreRiskSnapshotView;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.risk.provider.config.RiskProperties;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RiskAeronGateway implements AutoCloseable {

    private final AeronClientPool clients;

    public RiskAeronGateway(RiskProperties properties) {
        RiskProperties.Aeron aeron = properties.getAeron();
        clients = new AeronClientPool("risk", properties.getProductLine(), aeron.getHostnames(),
                aeron.getEgressHostname(), aeron.getResponseTimeout(), aeron.getClientConnections());
    }

    public List<CoreRiskSnapshotView> riskState(long userId) {
        var response = clients.query(CoreMessageType.RISK_STATE_QUERY, UUID.randomUUID(), userId, new byte[0]);
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(response.resultCode() + ": Aeron risk state query failed");
        }
        return CoreRiskQueryCodec.decode(response.data());
    }

    public CoreUserStateView userState(long userId) {
        var response = clients.query(CoreMessageType.USER_STATE_QUERY, UUID.randomUUID(), userId, new byte[0]);
        if (response.status() == ResponseStatus.REJECTED
                && response.resultCode() == com.surprising.aeron.protocol.CoreResultCode.ENTITY_NOT_FOUND) {
            return null;
        }
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(response.resultCode() + ": Aeron user state query failed");
        }
        return CoreStateQueryCodec.decodeUserState(response.data());
    }

    @Override
    @PreDestroy
    public void close() { clients.close(); }
}
