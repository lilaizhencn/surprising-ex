package com.surprising.trading.maintenance;

import com.surprising.product.api.ProductLine;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;

/**
 * 共享原始 HTTP 与网关入口的请求校验、业务编排和结果转换；不持有 HTTP 路由。
 */
@Service()
public class AdminMaintenanceRequestService {

    private final MaintenanceService service;

    public AdminMaintenanceRequestService(MaintenanceService service) {
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
        return service.list(beforeId);
    }

    public MaintenanceService.Preview preview(String symbol, long userId, long afterUserId) {
        return service.preview(symbol, userId, afterUserId);
    }

    public MaintenanceTask get(long id) {
        return service.get(id);
    }

    public List<MaintenanceRepository.Action> actions(long id, String afterKey) {
        return service.actions(id, afterKey);
    }

    public MaintenanceTask create(String admin, MaintenanceRequest request) {
        return service.create(admin, request);
    }

    public MaintenanceTask retry(long id) {
        return service.retry(id);
    }

    public MaintenanceTask release(long id) {
        return service.release(id);
    }
}
