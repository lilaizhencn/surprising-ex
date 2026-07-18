package com.surprising.trading.order.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.repository.OrderRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenOrderViewCoordinatorTest {

    @Test
    void rebuildsUsersIntoANewEpochBeforeMarkingTheProductLineReady() {
        TradingOrderProperties properties = new TradingOrderProperties();
        OrderRepository repository = mock(OrderRepository.class);
        RedisOpenOrderView view = mock(RedisOpenOrderView.class);
        OrderRedisLease lease = mock(OrderRedisLease.class);
        OrderRedisLease.Lease held = new OrderRedisLease.Lease("lease", "token");
        OrderRecord order = mock(OrderRecord.class);
        when(view.rebuildRequired(ProductLine.LINEAR_PERPETUAL,
                properties.getRedisIndex().getRebuildMaxAge())).thenReturn(true);
        when(lease.tryAcquire(any(), eq(properties.getRedisIndex().getLockTtl()))).thenReturn(held);
        when(view.startRebuild(ProductLine.LINEAR_PERPETUAL)).thenReturn(9L);
        when(repository.activeOpenOrderUsers(ProductLine.LINEAR_PERPETUAL, 0L, 1000))
                .thenReturn(List.of(1001L));
        when(repository.activeOrdersForOpenOrderView(ProductLine.LINEAR_PERPETUAL, 1001L, 0L, 1000))
                .thenReturn(List.of(order));
        when(order.orderId()).thenReturn(9001L);

        new OpenOrderViewCoordinator(properties, repository, view, lease).reconcile();

        verify(view).initializeUser(ProductLine.LINEAR_PERPETUAL, 1001L, 9L);
        verify(view).synchronize(order);
        verify(view).markReady(ProductLine.LINEAR_PERPETUAL, 9L, properties.getRedisIndex().getReadyTtl());
        verify(lease).release(held);
    }
}
