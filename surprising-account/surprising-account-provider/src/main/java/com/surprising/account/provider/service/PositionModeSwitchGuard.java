package com.surprising.account.provider.service;

import com.surprising.account.provider.repository.PositionModeAlgoOrderRepository;
import com.surprising.account.provider.repository.PositionModeLockRepository;
import com.surprising.account.provider.repository.PositionModeOrderRepository;
import com.surprising.account.provider.repository.PositionModeTriggerOrderRepository;
import com.surprising.account.provider.repository.PositionModeUnsettledTradeRepository;
import com.surprising.account.provider.repository.PositionRepository;
import com.surprising.product.api.ProductLine;
/**
 * 旧数据库仓位模式切换守卫，仅保留历史测试类型；生产入口统一由账户用户 reducer 裁决。
 */
@Deprecated(forRemoval = true)
public class PositionModeSwitchGuard {

    private final PositionModeLockRepository lockRepository;
    private final PositionRepository positionRepository;
    private final PositionModeOrderRepository orderRepository;
    private final PositionModeTriggerOrderRepository triggerOrderRepository;
    private final PositionModeAlgoOrderRepository algoOrderRepository;
    private final PositionModeUnsettledTradeRepository unsettledTradeRepository;

    public PositionModeSwitchGuard(PositionModeLockRepository lockRepository,
                                   PositionRepository positionRepository,
                                   PositionModeOrderRepository orderRepository,
                                   PositionModeTriggerOrderRepository triggerOrderRepository,
                                   PositionModeAlgoOrderRepository algoOrderRepository,
                                   PositionModeUnsettledTradeRepository unsettledTradeRepository) {
        this.lockRepository = lockRepository;
        this.positionRepository = positionRepository;
        this.orderRepository = orderRepository;
        this.triggerOrderRepository = triggerOrderRepository;
        this.algoOrderRepository = algoOrderRepository;
        this.unsettledTradeRepository = unsettledTradeRepository;
    }

    public void lock(ProductLine productLine, long userId) {
        lockRepository.lock(productLine, userId);
    }

    public void requireSwitchable(ProductLine productLine, long userId) {
        if (positionRepository.existsOpen(productLine, userId)) {
            throw new IllegalStateException("position mode switch requires no open positions");
        }
        if (orderRepository.existsActive(productLine, userId)) {
            throw new IllegalStateException("position mode switch requires no active orders");
        }
        if (triggerOrderRepository.existsPending(productLine, userId)) {
            throw new IllegalStateException("position mode switch requires no pending trigger orders");
        }
        if (algoOrderRepository.existsActive(productLine, userId)) {
            throw new IllegalStateException("position mode switch requires no active algo orders");
        }
        if (unsettledTradeRepository.exists(productLine, userId)) {
            throw new IllegalStateException("position mode switch requires all matched trades to be settled");
        }
    }
}
