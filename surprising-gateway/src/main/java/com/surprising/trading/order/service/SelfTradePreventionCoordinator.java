package com.surprising.trading.order.service;

import com.surprising.trading.api.model.CancelOpenOrdersRequest;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.SelfTradePreventionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 防自成交保护协调器 (Self-Trade Prevention Coordinator)。
 * 适用于外部做市商与普通用户。
 */
@Service
public class SelfTradePreventionCoordinator {
    private static final Logger log = LoggerFactory.getLogger(SelfTradePreventionCoordinator.class);

    /**
     * 校验并应用防自成交规则。
     * 若返回 false 表示该订单被 EXPIRE_TAKER 或 EXPIRE_BOTH 终止。
     */
    public boolean applyStpProtection(PlaceOrderRequest request, boolean hasOppositeSelfBookOrder, Runnable cancelMakerAction) {
        if (request == null || request.stpMode() == SelfTradePreventionMode.NONE || !hasOppositeSelfBookOrder) {
            return true;
        }

        SelfTradePreventionMode mode = request.stpMode();
        log.info("STP triggered for user {} symbol {} side {} mode {}",
                request.userId(), request.instrumentId(), request.side(), mode);

        switch (mode) {
            case EXPIRE_TAKER -> {
                throw new IllegalArgumentException("STP: order cancelled to prevent self-trade (EXPIRE_TAKER)");
            }
            case EXPIRE_MAKER -> {
                if (cancelMakerAction != null) {
                    cancelMakerAction.run();
                }
                return true;
            }
            case EXPIRE_BOTH -> {
                if (cancelMakerAction != null) {
                    cancelMakerAction.run();
                }
                throw new IllegalArgumentException("STP: both orders cancelled to prevent self-trade (EXPIRE_BOTH)");
            }
            default -> {
                return true;
            }
        }
    }
}
