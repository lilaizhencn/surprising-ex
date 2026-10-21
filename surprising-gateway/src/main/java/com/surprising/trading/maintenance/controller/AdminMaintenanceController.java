package com.surprising.trading.maintenance.controller;

import com.surprising.product.api.ProductLine;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Component;
import com.surprising.trading.maintenance.model.*;
import com.surprising.trading.maintenance.service.MaintenanceService;

/**
 * 本地管理协议入口：校验请求和转换HTTP参数，业务交给MaintenanceService。
 */
@Component
public class AdminMaintenanceController {

    private final MaintenanceService service;

    public AdminMaintenanceController(MaintenanceService service) {
        this.service = service;
    }

    public void authorize(String admin, String header, ProductLine productLine) {
        if (admin == null || !admin.matches("[1-9][0-9]{0,18}"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "trusted admin identity is required");
        if (productLine != service.productLine() || header == null || !header.equals(productLine.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "explicit matching product line is required");
        }
    }

    public List<MaintenanceTask> list(long beforeId) {
        nonnegative(beforeId, "invalid cursor");
        return service.list(beforeId);
    }

    public MaintenanceService.Preview preview(String instrumentId, long userId, long afterUserId) {
        if (!com.surprising.product.api.utils.InstrumentIds.valid(instrumentId)) badRequest("explicit instrument_id is required");
        nonnegative(userId, "invalid user cursor");
        nonnegative(afterUserId, "invalid user cursor");
        return service.preview(instrumentId, userId, afterUserId);
    }

    public MaintenanceTask get(long id) {
        positiveId(id);
        return service.get(id);
    }

    public List<MaintenanceAction> actions(long id, String afterKey) {
        positiveId(id);
        return service.actions(id, afterKey == null ? "" : afterKey);
    }

    public MaintenanceTask create(String admin, MaintenanceRequest request) {
        if (request == null || request.requestId() == null || request.mode() == null
                || request.instrumentId() == null || !com.surprising.product.api.utils.InstrumentIds.valid(request.instrumentId())
                || request.reason() == null || request.reason().isBlank() || request.reason().length() > 1024) {
            badRequest("requestId, explicit instrument_id, mode and maintenance reason are required");
        }
        long userId = integer(request.userId());
        long priceTicks = integer(request.priceTicks());
        return service.create(admin, new MaintenanceCommand(request.requestId(), request.instrumentId(),
                userId, request.mode(), priceTicks, request.reason().trim()));
    }

    public MaintenanceTask retry(long id) {
        positiveId(id);
        return service.retry(id);
    }

    public MaintenanceTask release(long id) {
        positiveId(id);
        return service.release(id);
    }
    private static long integer(String value) {
        if (value == null || value.isBlank()) return 0;
        if (!value.matches("0|[1-9][0-9]*")) badRequest("identifiers and prices must be decimal integer strings");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "identifiers and prices exceed 64-bit range", ex);
        }
    }

    private static void positiveId(long id) {
        if (id <= 0) badRequest("maintenance task id must be positive");
    }

    private static void nonnegative(long value, String message) {
        if (value < 0) badRequest(message);
    }

    private static void badRequest(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
