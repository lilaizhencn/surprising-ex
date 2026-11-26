package com.surprising.trading.order.controller;

import com.surprising.gateway.provider.product.GatewayUserIdentity;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.utils.InstrumentIds;
import com.surprising.trading.maintenance.controller.MaintenanceRequest;
import com.surprising.trading.maintenance.model.MaintenanceAction;
import com.surprising.trading.maintenance.model.MaintenanceCommand;
import com.surprising.trading.maintenance.model.MaintenanceTask;
import com.surprising.trading.maintenance.service.MaintenanceService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;

/**
 * Administrative order-book maintenance task endpoints.
 */
@RestController
public class AdminTradingMaintenanceController {

    private static final Pattern ADMIN_PATTERN = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern DECIMAL_INT_PATTERN = Pattern.compile("0|[1-9][0-9]*");

    private final MaintenanceService maintenanceService;

    public AdminTradingMaintenanceController(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    @GetMapping("/api/v1/admin/trading/orders/maintenance/preview")
    public MaintenanceService.Preview maintenancePreview(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader("X-Product-Line") String headerProductLine,
            @RequestParam("productLine") ProductLine productLine,
            @RequestParam("instrumentId") String instrumentId,
            @RequestParam(value = "userId", defaultValue = "0") long userId,
            @RequestParam(value = "afterUserId", defaultValue = "0") long afterUserId) {
        authorizeMaintenance(GatewayUserIdentity.requireAdminUserId(adminUserId), headerProductLine, productLine);
        if (!InstrumentIds.valid(instrumentId)) {
            badRequest("explicit instrument_id is required");
        }
        nonnegative(userId, "invalid user cursor");
        nonnegative(afterUserId, "invalid user cursor");
        return maintenanceService.preview(instrumentId, userId, afterUserId);
    }

    @GetMapping("/api/v1/admin/trading/orders/maintenance")
    public List<MaintenanceTask> maintenanceList(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader("X-Product-Line") String headerProductLine,
            @RequestParam("productLine") ProductLine productLine,
            @RequestParam(value = "beforeId", defaultValue = "0") long beforeId) {
        authorizeMaintenance(GatewayUserIdentity.requireAdminUserId(adminUserId), headerProductLine, productLine);
        nonnegative(beforeId, "invalid cursor");
        return maintenanceService.list(beforeId);
    }

    @PostMapping("/api/v1/admin/trading/orders/maintenance")
    public MaintenanceTask maintenanceCreate(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader("X-Product-Line") String headerProductLine,
            @RequestParam("productLine") ProductLine productLine,
            @RequestBody MaintenanceRequest request) {
        String verifiedAdmin = GatewayUserIdentity.requireAdminUserId(adminUserId);
        authorizeMaintenance(verifiedAdmin, headerProductLine, productLine);
        if (request == null || request.requestId() == null || request.mode() == null
                || request.instrumentId() == null || !InstrumentIds.valid(request.instrumentId())
                || request.reason() == null || request.reason().isBlank() || request.reason().length() > 1024) {
            badRequest("requestId, explicit instrument_id, mode and maintenance reason are required");
        }
        long userId = parseDecimalLong(request.userId());
        long priceTicks = parseDecimalLong(request.priceTicks());
        return maintenanceService.create(verifiedAdmin, new MaintenanceCommand(request.requestId(),
                request.instrumentId(), userId, request.mode(), priceTicks, request.reason().trim()));
    }

    @GetMapping("/api/v1/admin/trading/orders/maintenance/{id}/actions")
    public List<MaintenanceAction> maintenanceActions(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader("X-Product-Line") String headerProductLine,
            @RequestParam("productLine") ProductLine productLine,
            @PathVariable("id") long id,
            @RequestParam(value = "afterKey", defaultValue = "") String afterKey) {
        authorizeMaintenance(GatewayUserIdentity.requireAdminUserId(adminUserId), headerProductLine, productLine);
        positiveId(id);
        return maintenanceService.actions(id, afterKey == null ? "" : afterKey);
    }

    @PostMapping("/api/v1/admin/trading/orders/maintenance/{id}/release")
    public MaintenanceTask maintenanceRelease(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader("X-Product-Line") String headerProductLine,
            @RequestParam("productLine") ProductLine productLine,
            @PathVariable("id") long id) {
        authorizeMaintenance(GatewayUserIdentity.requireAdminUserId(adminUserId), headerProductLine, productLine);
        positiveId(id);
        return maintenanceService.release(id);
    }

    @PostMapping("/api/v1/admin/trading/orders/maintenance/{id}/retry")
    public MaintenanceTask maintenanceRetry(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader("X-Product-Line") String headerProductLine,
            @RequestParam("productLine") ProductLine productLine,
            @PathVariable("id") long id) {
        authorizeMaintenance(GatewayUserIdentity.requireAdminUserId(adminUserId), headerProductLine, productLine);
        positiveId(id);
        return maintenanceService.retry(id);
    }

    @GetMapping("/api/v1/admin/trading/orders/maintenance/{id}")
    public MaintenanceTask maintenanceGet(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader("X-Product-Line") String headerProductLine,
            @RequestParam("productLine") ProductLine productLine,
            @PathVariable("id") long id) {
        authorizeMaintenance(GatewayUserIdentity.requireAdminUserId(adminUserId), headerProductLine, productLine);
        positiveId(id);
        return maintenanceService.get(id);
    }

    private void authorizeMaintenance(String admin, String header, ProductLine productLine) {
        if (admin == null || !ADMIN_PATTERN.matcher(admin).matches()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "trusted admin identity is required");
        }
        if (productLine != maintenanceService.productLine() || header == null || !header.equals(productLine.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "explicit matching product line is required");
        }
    }

    private static long parseDecimalLong(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        if (!DECIMAL_INT_PATTERN.matcher(value).matches()) {
            badRequest("identifiers and prices must be decimal integer strings");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "identifiers and prices exceed 64-bit range", ex);
        }
    }

    private static void positiveId(long id) {
        if (id <= 0) {
            badRequest("maintenance task id must be positive");
        }
    }

    private static void nonnegative(long value, String message) {
        if (value < 0) {
            badRequest(message);
        }
    }

    private static void badRequest(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
