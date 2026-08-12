package com.surprising.adl.provider.controller;

import com.surprising.adl.api.AdlApiPaths;
import com.surprising.adl.api.model.AdlEventQueryResponse;
import com.surprising.adl.api.model.AdlQueueQueryResponse;
import com.surprising.adl.provider.service.AdlRuntimeConfigService;
import com.surprising.adl.provider.service.AdlService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(AdlApiPaths.API_V1)
public class AdlController {

    private final AdlService adlService;
    private final AdlRuntimeConfigService runtimeConfigService;

    public AdlController(AdlService adlService,
                         AdlRuntimeConfigService runtimeConfigService) {
        this.adlService = adlService;
        this.runtimeConfigService = runtimeConfigService;
    }

    @GetMapping("/queue")
    public AdlQueueQueryResponse queue(@RequestParam String asset,
                                       @RequestParam(defaultValue = "100") int limit) {
        try {
            return adlService.queue(asset, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/events")
    public AdlEventQueryResponse events(@RequestParam(required = false) Long userId,
                                        @RequestParam(required = false) String asset,
                                        @RequestParam(required = false) String symbol,
                                        @RequestParam(defaultValue = "100") int limit) {
        try {
            return adlService.events(userId, asset, symbol, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/admin/queue")
    public AdlQueueQueryResponse adminQueue(@RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
                                            @RequestParam String asset,
                                            @RequestParam(defaultValue = "100") int limit,
                                            @RequestParam(required = false) String cursor,
                                            @RequestParam(required = false) String sort) {
        requireAdmin(adminUserId);
        try {
            return adlService.queue(asset, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/admin/events")
    public AdlEventQueryResponse adminEvents(@RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
                                             @RequestParam(required = false) Long userId,
                                             @RequestParam(required = false) String asset,
                                             @RequestParam(required = false) String symbol,
                                             @RequestParam(defaultValue = "100") int limit,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(required = false) String sort) {
        requireAdmin(adminUserId);
        try {
            return adlService.events(userId, asset, symbol, limit, cursor, sort);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/admin/runtime-config")
    public Map<String, Object> runtimeConfig(@RequestHeader("X-Admin-User-Id") String adminUserId) {
        return runtimeConfigService.current();
    }

    @PostMapping("/admin/runtime-config")
    public Map<String, Object> updateRuntimeConfig(@RequestHeader("X-Admin-User-Id") String adminUserId,
                                                   @RequestBody RuntimeConfigUpdate request) {
        try {
            return runtimeConfigService.update(
                    request.scannerEnabled(),
                    request.scanDelayMs(),
                    request.minDeficitAgeMs(),
                    request.maxMarkAgeMs(),
                    request.batchSize(),
                    request.maxDeleveragesPerDeficit(),
                    request.candidateMultiplier());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private void requireAdmin(String adminUserId) {
        if (adminUserId == null || adminUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin user header is required");
        }
    }

    public record RuntimeConfigUpdate(
            Boolean scannerEnabled,
            Long scanDelayMs,
            Long minDeficitAgeMs,
            Long maxMarkAgeMs,
            Integer batchSize,
            Integer maxDeleveragesPerDeficit,
            Integer candidateMultiplier) {
    }
}
