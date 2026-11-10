package com.surprising.trading.order.service;

import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PlaceTriggerOrderRequest;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.TriggerOrderType;
import com.surprising.trading.trigger.service.TriggerOrderService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 开仓自带止盈止损派发器 (Bracket Order / TP-SL Dispatcher)。
 * 恪守 KISS 原则，为主订单自动派发和维护止盈止损触发单。
 */
@Service
public class BracketOrderDispatcher {
    private static final Logger log = LoggerFactory.getLogger(BracketOrderDispatcher.class);

    private final TriggerOrderService triggerOrderService;
    private final Map<Long, PlaceOrderRequest> pendingBrackets = new ConcurrentHashMap<>();

    public BracketOrderDispatcher(TriggerOrderService triggerOrderService) {
        this.triggerOrderService = triggerOrderService;
    }

    /**
     * 注册主订单的止盈止损需求。
     */
    public void registerBracket(PlaceOrderRequest request, OrderResponse response) {
        if (request == null || !request.hasBracketOrders() || response == null) {
            return;
        }
        if (response.status() == OrderStatus.REJECTED || response.status() == OrderStatus.CANCELED) {
            return;
        }

        // 如果主订单直接成交了部分或全部（例如市价单、IOC 或直接匹配的限价单）
        if (response.executedQuantitySteps() > 0) {
            deployBracketTriggers(request, response.executedQuantitySteps());
        }

        // 如果还有剩余挂单未成交，暂存主订单信息以备后续成交触发
        if (response.remainingQuantitySteps() > 0 && response.status() != OrderStatus.FILLED) {
            pendingBrackets.put(response.orderId(), request);
        }
    }

    /**
     * 当主订单发生新成交时调用。
     */
    public void onOrderFill(long orderId, long newFilledSteps) {
        PlaceOrderRequest request = pendingBrackets.get(orderId);
        if (request != null && newFilledSteps > 0) {
            deployBracketTriggers(request, newFilledSteps);
        }
    }

    /**
     * 当主订单终态（完成或撤销）时注销。
     */
    public void onOrderTerminal(long orderId) {
        pendingBrackets.remove(orderId);
    }

    private void deployBracketTriggers(PlaceOrderRequest request, long quantitySteps) {
        OrderSide closeSide = request.side() == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
        String ocoGroupId = "oco-" + request.clientOrderId() + "-" + System.currentTimeMillis();

        // 部署止盈单 (Take Profit)
        if (request.takeProfitPriceTicks() > 0) {
            try {
                String tpClientId = "tp-" + request.clientOrderId() + "-" + System.nanoTime() % 1_000_000;
                PlaceTriggerOrderRequest tpRequest = new PlaceTriggerOrderRequest(
                        request.userId(),
                        tpClientId,
                        ocoGroupId,
                        request.instrumentId(),
                        closeSide,
                        TriggerOrderType.TAKE_PROFIT,
                        request.takeProfitPriceTicks(),
                        null,
                        null,
                        OrderType.MARKET,
                        TimeInForce.IOC,
                        0L,
                        quantitySteps,
                        request.marginMode(),
                        request.positionSide(),
                        null,
                        request.tpSlPriceSource()
                );
                triggerOrderService.place(tpRequest);
                log.info("Bracket TP trigger placed: userId={}, instrumentId={}, price={}, qty={}",
                        request.userId(), request.instrumentId(), request.takeProfitPriceTicks(), quantitySteps);
            } catch (Exception ex) {
                log.warn("Failed to place bracket TP trigger for order {}: {}", request.clientOrderId(), ex.getMessage());
            }
        }

        // 部署止损单 (Stop Loss)
        if (request.stopLossPriceTicks() > 0) {
            try {
                String slClientId = "sl-" + request.clientOrderId() + "-" + System.nanoTime() % 1_000_000;
                PlaceTriggerOrderRequest slRequest = new PlaceTriggerOrderRequest(
                        request.userId(),
                        slClientId,
                        ocoGroupId,
                        request.instrumentId(),
                        closeSide,
                        TriggerOrderType.STOP_LOSS,
                        request.stopLossPriceTicks(),
                        null,
                        null,
                        OrderType.MARKET,
                        TimeInForce.IOC,
                        0L,
                        quantitySteps,
                        request.marginMode(),
                        request.positionSide(),
                        null,
                        request.tpSlPriceSource()
                );
                triggerOrderService.place(slRequest);
                log.info("Bracket SL trigger placed: userId={}, instrumentId={}, price={}, qty={}",
                        request.userId(), request.instrumentId(), request.stopLossPriceTicks(), quantitySteps);
            } catch (Exception ex) {
                log.warn("Failed to place bracket SL trigger for order {}: {}", request.clientOrderId(), ex.getMessage());
            }
        }
    }
}
