package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.AdminQueryTaskCreateRequest;
import com.surprising.gateway.provider.auth.AuthModels.AdminQueryTaskArchiveRequest;
import com.surprising.gateway.provider.auth.AuthModels.AdminQueryTaskArchiveResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminQueryTaskLimitsResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminQueryTaskQueryResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminQueryTaskResponse;
import com.surprising.gateway.provider.auth.AdminQueryTaskService.QueryTaskQuotaExceededException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/query-tasks")
public class AdminQueryTaskController {

    private final AuthService authService;
    private final AdminQueryTaskRepository repository;
    private final AdminQueryTaskService queryTaskService;

    public AdminQueryTaskController(AuthService authService,
                                    AdminQueryTaskRepository repository,
                                    AdminQueryTaskService queryTaskService) {
        this.authService = authService;
        this.repository = repository;
        this.queryTaskService = queryTaskService;
    }

    @GetMapping
    public AdminQueryTaskQueryResponse tasks(@RequestHeader("Authorization") String authorization,
                                             @RequestParam(value = "status", required = false) String status,
                                             @RequestParam(value = "queryType", required = false) String queryType,
                                             @RequestParam(value = "limit", defaultValue = "100") int limit,
                                             @RequestParam(value = "cursor", required = false) String cursor,
                                             @RequestParam(value = "sort", required = false) String sort) {
        try {
            authService.requireAdminPermission(authorization, "admin.queries.read");
            var page = repository.taskPage(status, queryType, limit, cursor, sort);
            return new AdminQueryTaskQueryResponse(page.items().size(), page.items(),
                    page.nextCursor(), page.hasMore(), page.sort(), page.limit());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @GetMapping("/limits")
    public AdminQueryTaskLimitsResponse limits(@RequestHeader("Authorization") String authorization) {
        try {
            var principal = authService.requireAdminPermission(authorization, "admin.queries.read");
            return queryTaskService.limits(principal, Instant.now());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @GetMapping("/{queryTaskId}")
    public AdminQueryTaskResponse task(@RequestHeader("Authorization") String authorization,
                                       @PathVariable("queryTaskId") long queryTaskId) {
        try {
            authService.requireAdminPermission(authorization, "admin.queries.read");
            return repository.task(queryTaskId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "query task not found"));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping
    public AdminQueryTaskResponse create(@RequestHeader("Authorization") String authorization,
                                         @RequestBody AdminQueryTaskCreateRequest request) {
        try {
            var principal = authService.requireAdminPermission(authorization, "admin.queries.write");
            return queryTaskService.create(principal, request, Instant.now());
        } catch (QueryTaskQuotaExceededException ex) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    @PostMapping("/archive-expired")
    public AdminQueryTaskArchiveResponse archiveExpired(@RequestHeader("Authorization") String authorization,
                                                        @RequestBody(required = false)
                                                        AdminQueryTaskArchiveRequest request) {
        try {
            authService.requireAdminPermission(authorization, "admin.queries.write");
            return queryTaskService.archiveExpired(request, Instant.now());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }
}
