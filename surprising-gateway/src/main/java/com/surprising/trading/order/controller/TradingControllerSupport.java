package com.surprising.trading.order.controller;

import com.surprising.product.api.ProductLine;
import com.surprising.product.api.utils.InstrumentIds;
import com.surprising.trading.api.model.EffectiveTradingFeeResponse;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.LeverageSettingResponse;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.order.exception.OrderCommandRejectedException;
import com.surprising.trading.order.model.ActiveOrderPageQuery;
import com.surprising.trading.order.service.LeverageService;
import com.surprising.trading.order.service.TradingFeeService;
import com.surprising.trading.order.utils.ActiveOrderCursors;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared parameter binding, validation, and error-mapping helpers for trading controllers.
 */
public final class TradingControllerSupport {

    private TradingControllerSupport() {
    }

    public static EffectiveTradingFeeResponse effectiveFee(TradingFeeService service, long userId,
                                                           String instrumentId, long instrumentChangeId,
                                                           String headerProductLine, String queryProductLine) {
        try {
            String normalized = normalizeOptionalSymbol(instrumentId);
            if (normalized == null) {
                throw new IllegalArgumentException("instrumentId is required");
            }
            return service.effectiveFee(userId, normalized, instrumentChangeId,
                    productLine(queryProductLine, headerProductLine));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    public static LeverageSettingResponse setLeverage(LeverageService service, LeverageSettingRequest request,
                                                      String headerProductLine, String queryProductLine) {
        try {
            ProductLine line = productLine(queryProductLine, headerProductLine);
            LeverageSettingRequest bound = request == null || request.productLine() != null || line == null
                    ? request
                    : new LeverageSettingRequest(request.userId(), line, request.instrumentId(),
                            request.marginMode(), request.leveragePpm(), request.reason(), request.repriceCrossMargin());
            return service.set(bound);
        } catch (OrderCommandRejectedException rejected) {
            throw conflict(rejected);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    public static LeverageSettingResponse getLeverage(LeverageService service, long userId, String instrumentId,
                                                      MarginMode marginMode, String headerProductLine,
                                                      String queryProductLine) {
        try {
            return service.get(userId, instrumentId, marginMode, productLine(queryProductLine, headerProductLine));
        } catch (OrderCommandRejectedException rejected) {
            throw conflict(rejected);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    public static ProductLine productLine(String queryValue, String headerValue) {
        String value = queryValue == null || queryValue.isBlank() ? headerValue : queryValue;
        if (value == null || value.isBlank()) {
            return null;
        }
        return ProductLine.requireExternalCode(value);
    }

    public static String normalizeOptionalSymbol(String instrumentId) {
        if (instrumentId == null || instrumentId.isBlank()) {
            return null;
        }
        String normalized = instrumentId.trim().toUpperCase(Locale.ROOT);
        if (!InstrumentIds.valid(normalized)) {
            throw new IllegalArgumentException("invalid instrumentId: " + instrumentId);
        }
        return normalized;
    }

    public static ActiveOrderPageQuery activePage(int limit, String cursor) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        return new ActiveOrderPageQuery(limit, ActiveOrderCursors.decode(cursor));
    }

    public static String activeInstrument(String instrumentId) {
        if (instrumentId == null || instrumentId.isBlank()) {
            return null;
        }
        String value = instrumentId.trim().toUpperCase(Locale.ROOT);
        if (!InstrumentIds.valid(value)) {
            throw new IllegalArgumentException("invalid instrumentId: " + instrumentId);
        }
        return value;
    }

    public static <T> CompletionStage<T> mapAsyncFailure(CompletionStage<T> stage, HttpStatus illegalStateStatus) {
        CompletableFuture<T> mapped = new CompletableFuture<>();
        stage.whenComplete((value, failure) -> {
            if (failure == null) {
                mapped.complete(value);
                return;
            }
            Throwable cause = failure;
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof IllegalArgumentException) {
                mapped.completeExceptionally(
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, cause.getMessage(), cause));
            } else if (cause instanceof IllegalStateException) {
                mapped.completeExceptionally(
                        new ResponseStatusException(illegalStateStatus, cause.getMessage(), cause));
            } else {
                mapped.completeExceptionally(cause);
            }
        });
        return mapped;
    }

    public static ResponseEntity<OrderCommandReceipt> commandResponse(OrderCommandReceipt receipt) {
        HttpStatus status = switch (receipt.code()) {
            case "IDEMPOTENCY_CONFLICT" -> HttpStatus.CONFLICT;
            case "CLIENT_BACKPRESSURED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "MATCHING_PENDING", "RESULT_UNKNOWN" -> HttpStatus.ACCEPTED;
            case "RESULT_UNKNOWN_OUTSIDE_RETENTION" -> HttpStatus.GONE;
            case "NOT_CONNECTED", "ADMIN_ACTION", "CLOSED", "MAX_POSITION_EXCEEDED", "UNKNOWN" ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(receipt);
    }

    private static ResponseStatusException conflict(OrderCommandRejectedException rejected) {
        var response = new ResponseStatusException(HttpStatus.CONFLICT, rejected.getMessage(), rejected);
        response.getBody().setProperty("code", rejected.code().name());
        return response;
    }
}
