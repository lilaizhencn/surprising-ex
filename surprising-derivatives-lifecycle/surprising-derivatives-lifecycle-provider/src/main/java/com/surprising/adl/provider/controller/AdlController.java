package com.surprising.adl.provider.controller;

import com.surprising.adl.api.AdlApiPaths;
import com.surprising.adl.api.model.AdlEventQueryResponse;
import com.surprising.adl.api.model.AdlQueueQueryResponse;
import com.surprising.adl.provider.service.AdlRuntimeConfigService;
import com.surprising.adl.provider.service.AdlService;
import java.util.Map;
import com.surprising.trading.api.utils.AdminCursorPage;
import com.surprising.adl.provider.model.AdlEventPageQuery;
import java.time.DateTimeException;
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
                                        @RequestParam(required = false) String instrumentId,
                                        @RequestParam(defaultValue = "100") int limit) {
        try {
            return adlService.events(userId, optionalAsset(asset), optionalInstrument(instrumentId), historyPage(limit, null, null));
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

    @PostMapping("/admin/run-cycle")
    public AdlService.AdlCycle runCycle(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId) {
        requireAdmin(adminUserId);
        return adlService.processResidualDeficits();
    }

    @GetMapping("/admin/events")
    public AdlEventQueryResponse adminEvents(@RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
                                             @RequestParam(required = false) Long userId,
                                             @RequestParam(required = false) String asset,
                                             @RequestParam(required = false) String instrumentId,
                                             @RequestParam(defaultValue = "100") int limit,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(required = false) String sort) {
        requireAdmin(adminUserId);
        try {
            return adlService.events(userId, optionalAsset(asset), optionalInstrument(instrumentId), historyPage(limit, cursor, sort));
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
            if (request == null || request.expectedVersion() == null || request.expectedVersion() < 1
                    || adminUserId == null || !adminUserId.matches("[1-9][0-9]*") || request.reason() == null
                    || request.reason().isBlank() || request.reason().length() > 1000)
                throw new IllegalArgumentException("expectedVersion, administrator and reason (1-1000 characters) are required");
            if (request.minDeficitAgeMs() != null || request.maxMarkAgeMs() != null)
                throw new IllegalArgumentException("ADL eligibility and mark freshness are owned by Core, not provider scanner settings");
            return runtimeConfigService.update(
                    adminUserId, request.expectedVersion(),
                    request.scannerEnabled(),
                    request.scanDelayMs(),
                    request.batchSize(),
                    request.maxDeleveragesPerDeficit(),
                    request.candidateMultiplier(), request.reason().trim());
        } catch (org.springframework.dao.OptimisticLockingFailureException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
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
            Integer candidateMultiplier, Long expectedVersion, String reason) {
    }
    private AdlEventPageQuery historyPage(int limit, String cursor, String sort) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be in [1,1000]");
        String order = sort == null || sort.isBlank() ? "createdAt.desc" : sort.trim();
        if (!order.equals("createdAt.desc") && !order.equals("createdAt.asc")) {
            throw new IllegalArgumentException("unsupported sort: " + sort);
        }
        try {
            var after = AdminCursorPage.decodeCursor(cursor);
            return new AdlEventPageQuery(limit, after == null ? null : after.timestamp(),
                    after == null ? 0L : after.id(), order.equals("createdAt.desc"));
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException("invalid cursor", invalid);
        }
    }

    private String optionalAsset(String asset) {
        return asset == null || asset.isBlank() ? null : normalizeAsset(asset);
    }

    private String normalizeAsset(String asset) {
        if (asset == null || !asset.trim().toUpperCase().matches("[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("invalid asset");
        }
        return asset.trim().toUpperCase();
    }

    private String optionalInstrument(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

}
