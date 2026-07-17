package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.model.AlgoOrderRecord;
import com.surprising.trading.order.model.CancelAllAfterTimer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Replayable Redis time index; PostgreSQL remains authoritative for all state transitions. */
public interface OrderScheduleIndex {
    void synchronizeTimer(ProductLine productLine, CancelAllAfterTimer timer);
    void removeTimer(ProductLine productLine, long userId, String symbolScope);
    void synchronizeAlgo(AlgoOrderRecord order);
    void removeAlgo(ProductLine productLine, long algoOrderId);
    Optional<List<TimerMember>> dueTimers(ProductLine productLine, Instant now, int limit);
    Optional<List<Long>> dueAlgos(ProductLine productLine, Instant now, int limit);
    boolean ready(ProductLine productLine);
    void markReady(ProductLine productLine);
    void markNotReady(ProductLine productLine);
    void clear(ProductLine productLine);

    record TimerMember(long userId, String symbolScope) { }
    static OrderScheduleIndex disabled() { return Disabled.INSTANCE; }

    final class Disabled implements OrderScheduleIndex {
        private static final Disabled INSTANCE = new Disabled();
        public void synchronizeTimer(ProductLine productLine, CancelAllAfterTimer timer) { }
        public void removeTimer(ProductLine productLine, long userId, String symbolScope) { }
        public void synchronizeAlgo(AlgoOrderRecord order) { }
        public void removeAlgo(ProductLine productLine, long algoOrderId) { }
        public Optional<List<TimerMember>> dueTimers(ProductLine productLine, Instant now, int limit) { return Optional.empty(); }
        public Optional<List<Long>> dueAlgos(ProductLine productLine, Instant now, int limit) { return Optional.empty(); }
        public boolean ready(ProductLine productLine) { return false; }
        public void markReady(ProductLine productLine) { }
        public void markNotReady(ProductLine productLine) { }
        public void clear(ProductLine productLine) { }
    }
}
