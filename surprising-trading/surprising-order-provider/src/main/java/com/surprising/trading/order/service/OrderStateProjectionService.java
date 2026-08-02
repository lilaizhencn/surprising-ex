package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.model.OrderUserState;
import com.surprising.trading.order.repository.OrderRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户订单状态的数据库异步投影。
 *
 * <p>本服务把本地 RocksDB 中的完整用户订单状态原子替换到 {@code trading_orders}，数据库
 * 只用于查询投影、启动恢复和审计。任何订单命令都不能同步调用本服务。</p>
 */
@Service
public class OrderStateProjectionService {

    private final OrderRepository orderRepository;

    public OrderStateProjectionService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void project(ProductLine productLine, long userId, OrderUserState state) {
        if (productLine == null || userId <= 0L || state == null) {
            throw new IllegalArgumentException("订单状态投影参数不能为空");
        }
        List<OrderRecord> orders = state.orders() == null ? List.of() : state.orders();
        orderRepository.replaceProjection(productLine, userId, orders);
    }
}
