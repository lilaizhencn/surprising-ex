package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.service.AdminSystemObservabilityService;
import com.surprising.gateway.provider.service.AdminSystemObservabilityService.SystemObservabilityResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 后台可观测性 HTTP 入口，只负责接收鉴权头并映射服务异常。
 */
@RestController
@RequestMapping("/api/v1/admin/system")
public class AdminSystemObservabilityController {

    private final AdminSystemObservabilityService observabilityService;

    public AdminSystemObservabilityController(AdminSystemObservabilityService observabilityService) {
        this.observabilityService = observabilityService;
    }

    @GetMapping("/observability")
    public SystemObservabilityResponse observability(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        try {
            return observabilityService.observability(authorization);
        } catch (AdminSystemObservabilityService.AdminObservabilityUnauthorizedException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (AdminSystemObservabilityService.AdminObservabilityForbiddenException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }
}
