package com.surprising.trading.matching.service;

import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.matching.model.MatchedOrderSnapshot;
import com.surprising.trading.matching.repository.MatchingOrderRepository;
import com.surprising.trading.matching.repository.MatchingResultRepository;
import com.surprising.trading.matching.repository.MatchingTradeRepository;
import com.surprising.trading.matching.repository.MatchingOutboxRepository.MatchingOutboxWrite;
import com.surprising.trading.matching.repository.MatchingOutboxRepository;
import com.surprising.trading.matching.store.MatchingLocalStateStore;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 聚合撮合结果、成交和订单状态三个单表 Repository。
 *
 * <p>调用方事务覆盖整个保存流程，保证结果幂等、成交落库和订单数量更新同时提交。</p>
 */
@Service
public class MatchingPersistenceService {

    private final MatchingResultRepository resultRepository;
    private final MatchingTradeRepository tradeRepository;
    private final MatchingOrderRepository orderRepository;
    private final MatchingLocalStateStore localStateStore;
    private final MatchingOutboxRepository outboxRepository;

    /** 迁移测试兼容构造；生产 Spring 使用本地状态库构造函数。 */
    public MatchingPersistenceService(MatchingResultRepository resultRepository,
                                      MatchingTradeRepository tradeRepository,
                                      MatchingOrderRepository orderRepository) {
        this(resultRepository, tradeRepository, orderRepository, null, null);
    }

    @Autowired
    public MatchingPersistenceService(MatchingResultRepository resultRepository,
                                      MatchingTradeRepository tradeRepository,
                                      MatchingOrderRepository orderRepository,
                                      MatchingOutboxRepository outboxRepository,
                                      MatchingLocalStateStore localStateStore) {
        this.resultRepository = resultRepository;
        this.tradeRepository = tradeRepository;
        this.orderRepository = orderRepository;
        this.localStateStore = localStateStore;
        this.outboxRepository = outboxRepository;
    }

    public void prepare(com.surprising.trading.api.model.OrderCommandEvent command) {
        if (localStateStore != null) {
            localStateStore.prepare(command);
        }
    }

    public CommandState commandState(long commandId, long orderId) {
        if (localStateStore != null) {
            MatchingLocalStateStore.CommandState state = localStateStore.commandState(commandId, orderId);
            return new CommandState(state.resultExists(), state.orderExists());
        }
        return new CommandState(resultRepository.exists(commandId), orderRepository.exists(orderId));
    }

    public Map<Long, CommandState> commandStates(Map<Long, Long> commandOrderIds) {
        if (commandOrderIds == null || commandOrderIds.isEmpty()) {
            return Map.of();
        }
        if (localStateStore != null) {
            return localStateStore.commandStates(commandOrderIds).entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                            entry -> new CommandState(entry.getValue().resultExists(), entry.getValue().orderExists()),
                            (left, right) -> left));
        }
        Set<Long> resultIds = resultRepository.existingCommandIds(commandOrderIds.keySet());
        Set<Long> orderIds = orderRepository.existingOrderIds(commandOrderIds.values());
        Map<Long, CommandState> states = new LinkedHashMap<>(commandOrderIds.size());
        commandOrderIds.forEach((commandId, orderId) -> states.put(
                commandId, new CommandState(resultIds.contains(commandId), orderIds.contains(orderId))));
        return Map.copyOf(states);
    }

    public long orderInstrumentVersion(long orderId) {
        if (localStateStore != null) {
            return localStateStore.order(orderId).orElseThrow().command().instrumentVersion();
        }
        return orderRepository.instrumentVersion(orderId);
    }

    public MarginMode orderMarginMode(long orderId) {
        if (localStateStore != null) {
            return localStateStore.order(orderId).orElseThrow().command().marginMode();
        }
        return orderRepository.marginMode(orderId);
    }

    public PositionSide orderPositionSide(long orderId) {
        if (localStateStore != null) {
            return localStateStore.order(orderId).orElseThrow().command().positionSide();
        }
        return orderRepository.positionSide(orderId);
    }

    public MatchedOrderSnapshot orderSnapshot(long orderId) {
        if (localStateStore != null) {
            return localStateStore.snapshot(orderId);
        }
        return orderRepository.snapshot(orderId);
    }

    public Map<Long, MatchedOrderSnapshot> orderSnapshots(Collection<Long> orderIds) {
        if (localStateStore != null) {
            return localStateStore.snapshots(orderIds);
        }
        return orderRepository.snapshots(orderIds);
    }

    public boolean saveResult(MatchResultEvent event) {
        if (localStateStore != null) {
            return localStateStore.saveResult(event);
        }
        return resultRepository.save(event);
    }

    public void saveTrades(List<MatchTradeEvent> trades) {
        if (localStateStore != null) {
            localStateStore.saveTrades(trades);
            return;
        }
        tradeRepository.saveBatch(trades);
    }

    public void applyActiveOrderStatus(MatchResultEvent result) {
        if (localStateStore != null) {
            localStateStore.applyActiveOrderStatus(result);
            return;
        }
        orderRepository.applyActiveStatus(result);
    }

    public void applyMakerFills(List<MatchTradeEvent> trades) {
        if (localStateStore != null) {
            localStateStore.applyMakerFills(trades);
            return;
        }
        orderRepository.applyMakerFills(trades);
    }

    public boolean commit(MatchResultEvent result, List<MatchTradeEvent> persistedTrades) {
        return commit(result, persistedTrades, List.of());
    }

    public boolean commit(MatchResultEvent result,
                          List<MatchTradeEvent> persistedTrades,
                          List<MatchingOutboxWrite> outboxWrites) {
        if (localStateStore != null) {
            return localStateStore.commit(result, persistedTrades, outboxWrites);
        }
        if (!saveResult(result)) {
            return false;
        }
        applyActiveOrderStatus(result);
        saveTrades(persistedTrades);
        applyMakerFills(result.trades());
        return true;
    }

    public void enqueueOutbox(List<MatchingOutboxWrite> writes) {
        if (localStateStore != null) {
            localStateStore.enqueueOutbox(writes);
            return;
        }
        // 仅保留迁移测试装配路径；生产撮合通过本地队列发布。
        if (outboxRepository == null) {
            throw new IllegalStateException("撮合本地状态库未配置，禁止回退数据库 Outbox");
        }
        outboxRepository.enqueueBatch(writes);
    }

    public record CommandState(boolean resultExists, boolean orderExists) {
    }
}
