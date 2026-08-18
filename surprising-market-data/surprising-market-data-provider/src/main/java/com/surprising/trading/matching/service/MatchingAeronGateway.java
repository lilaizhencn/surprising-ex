package com.surprising.trading.matching.service;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.CoreOrderBookView;
import com.surprising.aeron.protocol.CoreOrderBookBootstrapPage;
import com.surprising.aeron.protocol.CoreOrderBookBootstrapQuery;
import com.surprising.aeron.protocol.CoreOrderBookQuery;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.trading.matching.config.MatchingProperties;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.ArrayList;
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
        String snapshotId = "";
        String symbolCursor = "";
        long exportSequence = -1;
        var levels = new ArrayList<com.surprising.aeron.protocol.CoreBookLevelView>();
        while (true) {
            CoreOrderBookBootstrapPage page = orderBookBootstrapPage(
                    new CoreOrderBookBootstrapQuery(snapshotId, symbolCursor, 20, CoreOrderBookQuery.MAX_DEPTH));
            if (snapshotId.isEmpty()) {
                snapshotId = page.snapshotId();
                exportSequence = page.exportSequence();
            } else if (!snapshotId.equals(page.snapshotId()) || exportSequence != page.exportSequence()) {
                throw new IllegalStateException("order-book bootstrap snapshot changed during pagination");
            }
            levels.addAll(page.levels());
            if (page.complete()) return new CoreOrderBookView(exportSequence, levels);
            if (page.nextSymbolCursor().compareTo(symbolCursor) <= 0) {
                throw new IllegalStateException("order-book bootstrap cursor did not advance");
            }
            symbolCursor = page.nextSymbolCursor();
        }
    }

    public CoreOrderBookView orderBookProjection(CoreOrderBookQuery query) {
        CoreResponse response = clients.query(CoreMessageType.BOOK_STATE_QUERY, UUID.randomUUID(), 0,
                CoreStateQueryCodec.encodeOrderBookQuery(query));
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(response.resultCode().name() + ": Aeron order-book projection query failed");
        }
        return CoreStateQueryCodec.decodeOrderBookView(response.data());
    }

    CoreOrderBookBootstrapPage orderBookBootstrapPage(CoreOrderBookBootstrapQuery query) {
        CoreResponse response = clients.query(CoreMessageType.ORDER_BOOK_BOOTSTRAP_QUERY, UUID.randomUUID(), 0,
                CoreStateQueryCodec.encodeOrderBookBootstrapQuery(query));
        if (response.status() != ResponseStatus.OK) {
            if (response.resultCode() == CoreResultCode.BOOK_BOOTSTRAP_CURSOR_INVALID) {
                throw new BootstrapCursorInvalidException();
            }
            throw new IllegalStateException(response.resultCode().name()
                    + ": Aeron order-book bootstrap query failed");
        }
        return CoreStateQueryCodec.decodeOrderBookBootstrapPage(response.data());
    }

    @Override
    @PreDestroy
    public void close() {
        clients.close();
    }

    static final class BootstrapCursorInvalidException extends IllegalStateException {
        BootstrapCursorInvalidException() {
            super("Aeron order-book bootstrap cursor is no longer available");
        }
    }
}
