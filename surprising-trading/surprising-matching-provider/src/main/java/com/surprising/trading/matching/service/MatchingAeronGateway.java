package com.surprising.trading.matching.service;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.CoreBookStateView;
import com.surprising.aeron.protocol.CoreBookStateQuery;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.trading.matching.config.MatchingProperties;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MatchingAeronGateway implements AutoCloseable {

    private final AeronClientPool clients;

    public MatchingAeronGateway(MatchingProperties properties) {
        MatchingProperties.Aeron aeron = properties.getAeron();
        clients = new AeronClientPool("matching-market-data", properties.getKafka().getProductLine(),
                aeron.getHostnames(), aeron.getEgressHostname(), aeron.getResponseTimeout(), 1);
    }

    public CoreBookStateView bookState() {
        return bookState(new CoreBookStateQuery("", 1_000));
    }

    public CoreBookStateView bookState(CoreBookStateQuery query) {
        CoreResponse response = clients.query(CoreMessageType.BOOK_STATE_QUERY, UUID.randomUUID(), 0,
                CoreStateQueryCodec.encodeBookStateQuery(query));
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(response.resultCode().name() + ": Aeron book state query failed");
        }
        return CoreStateQueryCodec.decodeBookState(response.data());
    }

    @Override
    @PreDestroy
    public void close() {
        clients.close();
    }
}
