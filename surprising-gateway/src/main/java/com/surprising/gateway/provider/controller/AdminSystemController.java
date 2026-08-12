package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.service.AdminSystemService;
import com.surprising.gateway.provider.service.AdminSystemService.SystemHealthResponse;
import com.surprising.gateway.provider.service.AdminSystemService.SystemRoutesResponse;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 后台系统 HTTP 入口，只负责接收请求并映射服务异常。
 */
@RestController
@RequestMapping("/api/v1/admin/system")
public class AdminSystemController {

    private final AdminSystemService adminSystemService;

    public AdminSystemController(AdminSystemService adminSystemService) {
        this.adminSystemService = adminSystemService;
    }

    @GetMapping("/routes")
    public SystemRoutesResponse routes(@RequestHeader("Authorization") String authorization) {
        return execute(() -> adminSystemService.routes(authorization));
    }

    @GetMapping("/health")
    public SystemHealthResponse health(
            @RequestHeader("Authorization") String authorization,
            @RequestParam(value = "includePublicRoutes", defaultValue = "true")
            boolean includePublicRoutes) {
        return execute(() -> adminSystemService.health(authorization, includePublicRoutes));
    }

    private <T> T execute(Supplier<T> action) {
        try {
            return action.get();
        } catch (AdminSystemService.AdminSystemUnauthorizedException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (AdminSystemService.AdminSystemForbiddenException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }
}
