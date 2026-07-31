package com.surprising.trading.matching.service;

import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.matching.model.MatchedOrderSnapshot;
import com.surprising.trading.matching.repository.MatchingOrderRepository;
import com.surprising.trading.matching.repository.MatchingResultRepository;
import com.surprising.trading.matching.repository.MatchingTradeRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    public MatchingPersistenceService(MatchingResultRepository resultRepository,
                                      MatchingTradeRepository tradeRepository,
                                      MatchingOrderRepository orderRepository) {
        this.resultRepository = resultRepository;
        this.tradeRepository = tradeRepository;
        this.orderRepository = orderRepository;
    }

    public CommandState commandState(long commandId, long orderId) {
        return new CommandState(resultRepository.exists(commandId), orderRepository.exists(orderId));
    }

    public Map<Long, CommandState> commandStates(Map<Long, Long> commandOrderIds) {
        if (commandOrderIds == null || commandOrderIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> resultIds = resultRepository.existingCommandIds(commandOrderIds.keySet());
        Set<Long> orderIds = orderRepository.existingOrderIds(commandOrderIds.values());
        Map<Long, CommandState> states = new LinkedHashMap<>(commandOrderIds.size());
        commandOrderIds.forEach((commandId, orderId) -> states.put(
                commandId, new CommandState(resultIds.contains(commandId), orderIds.contains(orderId))));
        return Map.copyOf(states);
    }

    public long orderInstrumentVersion(long orderId) {
        return orderRepository.instrumentVersion(orderId);
    }

    public MarginMode orderMarginMode(long orderId) {
        return orderRepository.marginMode(orderId);
    }

    public PositionSide orderPositionSide(long orderId) {
        return orderRepository.positionSide(orderId);
    }

    public MatchedOrderSnapshot orderSnapshot(long orderId) {
        return orderRepository.snapshot(orderId);
    }

    public Map<Long, MatchedOrderSnapshot> orderSnapshots(Collection<Long> orderIds) {
        return orderRepository.snapshots(orderIds);
    }

    public boolean saveResult(MatchResultEvent event) {
        return resultRepository.save(event);
    }

    public void saveTrades(List<MatchTradeEvent> trades) {
        tradeRepository.saveBatch(trades);
    }

    public void applyActiveOrderStatus(MatchResultEvent result) {
        orderRepository.applyActiveStatus(result);
    }

    public void applyMakerFills(List<MatchTradeEvent> trades) {
        orderRepository.applyMakerFills(trades);
    }

    public record CommandState(boolean resultExists, boolean orderExists) {
    }
}
