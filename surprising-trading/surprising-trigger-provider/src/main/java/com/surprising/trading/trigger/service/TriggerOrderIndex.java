package com.surprising.trading.trigger.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.trigger.model.TriggerOrderRecord;
import java.util.List;
import java.util.Optional;

/**
 * Secondary lookup index for static TP/SL orders. PostgreSQL remains authoritative for every state change.
 */
public interface TriggerOrderIndex {

    void indexPlaced(TriggerOrderRecord order);

    void synchronize(TriggerOrderRecord order);

    void remove(TriggerOrderRecord order);

    void remove(ProductLine productLine,
                String symbol,
                long triggerOrderId);

    Optional<List<Long>> dueCandidates(ProductLine productLine,
                                       String symbol,
                                       long priceTicks,
                                       int limit);

    boolean ready(ProductLine productLine);

    void markReady(ProductLine productLine);

    void markNotReady(ProductLine productLine);

    static TriggerOrderIndex disabled() {
        return DisabledTriggerOrderIndex.INSTANCE;
    }

    final class DisabledTriggerOrderIndex implements TriggerOrderIndex {
        private static final DisabledTriggerOrderIndex INSTANCE = new DisabledTriggerOrderIndex();

        private DisabledTriggerOrderIndex() {
        }

        @Override
        public void indexPlaced(TriggerOrderRecord order) {
        }

        @Override
        public void synchronize(TriggerOrderRecord order) {
        }

        @Override
        public void remove(TriggerOrderRecord order) {
        }

        @Override
        public void remove(ProductLine productLine,
                           String symbol,
                           long triggerOrderId) {
        }

        @Override
        public Optional<List<Long>> dueCandidates(ProductLine productLine,
                                                  String symbol,
                                                  long priceTicks,
                                                  int limit) {
            return Optional.empty();
        }

        @Override
        public boolean ready(ProductLine productLine) {
            return false;
        }

        @Override
        public void markReady(ProductLine productLine) {
        }

        @Override
        public void markNotReady(ProductLine productLine) {
        }
    }
}
