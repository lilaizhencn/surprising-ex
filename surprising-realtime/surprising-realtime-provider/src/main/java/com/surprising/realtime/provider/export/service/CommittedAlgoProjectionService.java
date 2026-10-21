package com.surprising.realtime.provider.export.service;

import com.surprising.realtime.provider.export.repository.*;

import com.surprising.aeron.protocol.CoreAlgoOrderCodec;
import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.service.orchestration.replay.CommittedTradeReplay;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.springframework.transaction.support.TransactionTemplate;

/** Rebuildable algo read state; invoked only by the committed replay export worker. */
@org.springframework.stereotype.Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class CommittedAlgoProjectionService {
    private final TransactionTemplate transaction;
    private final CommittedAlgoRepository algoRows;
    private final AlgoInitializationRepository initialization;

    public CommittedAlgoProjectionService(CommittedAlgoRepository algoRows, AlgoInitializationRepository initialization,
            @org.springframework.beans.factory.annotation.Qualifier("committedProjectionTransactions") TransactionTemplate transaction) {
        this.algoRows = algoRows; this.initialization = initialization; this.transaction = transaction;
    }

    public void initialize(ProductLine product, CommittedTradeReplay replay, long checkpointPosition) {
        if (initialization.initialized(product)) return;
        var page = new ArrayList<CoreAlgoOrderView>(256);
        replay.forEachRetainedAlgo(value -> {
            page.add(value);
            if (page.size() == 256) {
                transaction.executeWithoutResult(tx -> persist(product, page, replay.exportSequence()));
                page.clear();
            }
        });
        transaction.executeWithoutResult(tx -> {
            persist(product, page, replay.exportSequence());
            initialization.initialize(product, checkpointPosition);
        });
    }

    /** Caller owns the transaction shared with order changes and the visibility watermark. */
    public void persist(ProductLine product, List<CoreAlgoOrderView> changes, long sequence) {
        var latest = new LinkedHashMap<Long, CoreAlgoOrderView>();
        for (var value : changes) {
            if (value.userId() <= 0 || value.algoOrderId() <= 0 || value.revision() <= 0
                    || value.createdAtEpochMillis() <= 0 || value.updatedAtEpochMillis() <= 0
                    || value.statusCode() < 0 || value.statusCode() > 5)
                throw new IllegalArgumentException("invalid committed algo state");
            var previous = latest.put(value.algoOrderId(), value);
            if (previous != null && (previous.userId() != value.userId()
                    || !previous.clientAlgoOrderId().equals(value.clientAlgoOrderId())
                    || !previous.instrumentId().equals(value.instrumentId()) || previous.revision() > value.revision()))
                throw new IllegalStateException("conflicting committed algo batch");
        }
        algoRows.persist(product, latest.values(), sequence);
    }
}
