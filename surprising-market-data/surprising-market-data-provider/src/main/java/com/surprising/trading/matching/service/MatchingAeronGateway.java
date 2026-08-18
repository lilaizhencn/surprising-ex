package com.surprising.trading.matching.service;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.CoreOrderBookView;
import com.surprising.aeron.protocol.CoreOrderBookQuery;
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

    public CoreOrderBookView orderBookProjection() {
        return orderBookProjection(new CoreOrderBookQuery("", 1_000));
    }

    public CoreOrderBookView orderBookProjection(CoreOrderBookQuery query) {
        CoreResponse response = clients.query(CoreMessageType.BOOK_STATE_QUERY, UUID.randomUUID(), 0,
                CoreStateQueryCodec.encodeOrderBookQuery(query));
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(response.resultCode().name() + ": Aeron order-book projection query failed");
        }
        return CoreStateQueryCodec.decodeOrderBookView(response.data());
    }

    @Override
    @PreDestroy
    public void close() {
        clients.close();
    }
}
