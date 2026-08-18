package com.surprising.aeron.tools;

import com.surprising.aeron.protocol.CoreBookLevelView;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderBookBootstrapPage;
import com.surprising.aeron.protocol.CoreOrderBookBootstrapQuery;
import com.surprising.aeron.protocol.CoreOrderBookQuery;
import com.surprising.aeron.protocol.CoreOrderBookView;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import java.util.ArrayList;

final class OrderBookBootstrapLoader {

    private OrderBookBootstrapLoader() {
    }

    static CoreOrderBookView load(Query query) {
        String snapshotId = "";
        String symbolCursor = "";
        long exportSequence = -1;
        ArrayList<CoreBookLevelView> levels = new ArrayList<>();
        while (true) {
            byte[] encoded = query.execute(CoreMessageType.ORDER_BOOK_BOOTSTRAP_QUERY,
                    CoreStateQueryCodec.encodeOrderBookBootstrapQuery(
                            new CoreOrderBookBootstrapQuery(snapshotId, symbolCursor, 20,
                                    CoreOrderBookQuery.MAX_DEPTH)));
            CoreOrderBookBootstrapPage page = CoreStateQueryCodec.decodeOrderBookBootstrapPage(encoded);
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

    @FunctionalInterface
    interface Query {
        byte[] execute(CoreMessageType type, byte[] payload);
    }
}
