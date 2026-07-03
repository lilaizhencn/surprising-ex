package com.surprising.trading.trigger.controller;

import com.surprising.trading.api.model.AdminTriggerOrderTimelineResponse;
import com.surprising.trading.api.model.TriggerOrderQueryResponse;
import com.surprising.trading.api.model.TriggerOrderResponse;
import com.surprising.trading.trigger.service.TriggerOrderService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/trading/trigger-orders")
public class AdminTriggerOrderController {

    private final TriggerOrderService triggerOrderService;

    public AdminTriggerOrderController(TriggerOrderService triggerOrderService) {
        this.triggerOrderService = triggerOrderService;
    }

    @GetMapping
    public TriggerOrderQueryResponse orders(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "triggerOrderId", required = false) Long triggerOrderId,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort) {
        requireAdmin(adminUserId);
        try {
            return triggerOrderService.adminOrders(userId, symbol, status, triggerOrderId, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/{triggerOrderId}")
    public TriggerOrderResponse order(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("triggerOrderId") long triggerOrderId) {
        requireAdmin(adminUserId);
        try {
            return triggerOrderService.get(triggerOrderId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping("/{triggerOrderId}/timeline")
    public AdminTriggerOrderTimelineResponse timeline(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("triggerOrderId") long triggerOrderId) {
        requireAdmin(adminUserId);
        try {
            return triggerOrderService.adminTimeline(triggerOrderId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    private void requireAdmin(String adminUserId) {
        if (adminUserId == null || adminUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin gateway header is required");
        }
    }
}
