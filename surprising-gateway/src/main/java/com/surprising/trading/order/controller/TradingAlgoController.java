package com.surprising.trading.order.controller;

import com.surprising.gateway.provider.product.GatewayUserIdentity;
import com.surprising.product.api.utils.InstrumentIds;
import com.surprising.trading.api.model.AlgoOrderBatchResponse;
import com.surprising.trading.api.model.AlgoOrderQueryResponse;
import com.surprising.trading.api.model.AlgoOrderResponse;
import com.surprising.trading.api.model.AlgoOrderSubmissionResponse;
import com.surprising.trading.api.model.CancelAlgoOrderRequest;
import com.surprising.trading.api.model.CancelOpenAlgoOrdersRequest;
import com.surprising.trading.api.model.PlaceAlgoOrderRequest;
import com.surprising.trading.order.service.AlgoOrderService;
import java.util.NoSuchElementException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST endpoints for algorithmic (TWAP/iceberg/etc.) order placement, cancellation, and queries.
 */
@RestController
public class TradingAlgoController {
    private final AlgoOrderService algoOrderService;

    @Autowired
    public TradingAlgoController(AlgoOrderService algoOrderService) {
        this.algoOrderService = algoOrderService;
    }

    @PostMapping("/api/v1/trading/orders/algo")
    public AlgoOrderResponse placeAlgo(@RequestBody PlaceAlgoOrderRequest request) {
        try {
            return algoOrderService.place(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/trading/orders/algo/cancel")
    public AlgoOrderResponse cancelAlgo(@RequestBody CancelAlgoOrderRequest request) {
        try {
            return algoOrderService.cancel(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/trading/orders/algo/cancel-open")
    public AlgoOrderBatchResponse cancelOpenAlgo(@RequestBody CancelOpenAlgoOrdersRequest request) {
        try {
            return algoOrderService.cancelOpen(request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/api/v1/trading/orders/algo/open")
    public AlgoOrderQueryResponse openAlgoOrders(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam(value = "instrumentId", required = false) String instrumentId,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        long userId = GatewayUserIdentity.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
            if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be in [1, 1000]");
            instrumentId = instrumentId == null || instrumentId.isBlank() ? null : instrumentId;
            if (instrumentId != null) InstrumentIds.parse(instrumentId);
            return algoOrderService.openOrders(userId, instrumentId, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/api/v1/trading/orders/algo/submission")
    public ResponseEntity<AlgoOrderSubmissionResponse> algoSubmission(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestParam("clientAlgoOrderId") String clientAlgoOrderId) {
        long userId = GatewayUserIdentity.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
            if (clientAlgoOrderId == null || clientAlgoOrderId.isBlank() || clientAlgoOrderId.trim().length() > 64) {
                throw new IllegalArgumentException("clientAlgoOrderId is required and must be at most 64 characters");
            }
            var result = algoOrderService.submission(userId, clientAlgoOrderId.trim());
            return ResponseEntity.status("TERMINAL".equals(result.outcome()) ? HttpStatus.OK : HttpStatus.ACCEPTED)
                    .cacheControl(CacheControl.noStore()).body(result);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (DataAccessException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "committed algo creation result is unavailable", ex);
        }
    }

    @GetMapping("/api/v1/trading/orders/algo/{algoOrderId}")
    public AlgoOrderResponse getAlgo(
            @RequestParam(value = "userId", required = false) Long queryUserId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @PathVariable("algoOrderId") long algoOrderId) {
        long userId = GatewayUserIdentity.requireUserId(queryUserId, headerUserId, adminUserId);
        try {
            if (algoOrderId <= 0) throw new IllegalArgumentException("algoOrderId must be positive");
            if (userId <= 0) throw new IllegalArgumentException("invalid algo owner");
            return algoOrderService.get(userId, algoOrderId);
        } catch (NoSuchElementException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, missing.getMessage(), missing);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        }
    }
}
