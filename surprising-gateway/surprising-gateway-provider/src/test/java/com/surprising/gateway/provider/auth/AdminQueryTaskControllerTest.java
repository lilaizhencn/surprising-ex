package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.AuthModels.AdminQueryTaskArchiveRequest;
import com.surprising.gateway.provider.auth.AuthModels.AdminQueryTaskArchiveResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminQueryTaskCreateRequest;
import com.surprising.gateway.provider.auth.AuthModels.AdminQueryTaskLimitsResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminQueryTaskQueryResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminQueryTaskResponse;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AdminQueryTaskControllerTest {

    @Test
    void limitsDelegatesWithReadPermission() {
        AuthService authService = mock(AuthService.class);
        AdminQueryTaskRepository repository = mock(AdminQueryTaskRepository.class);
        AdminQueryTaskService service = mock(AdminQueryTaskService.class);
        JwtPrincipal principal = principal();
        when(authService.requireAdminPermission("Bearer admin", "admin.queries.read")).thenReturn(principal);
        when(service.limits(eq(principal), any())).thenReturn(new AdminQueryTaskLimitsResponse(
                1, 3, 4, 25, 2, 20, 3600, 1024, 50_000_000, 5));
        AdminQueryTaskController controller = new AdminQueryTaskController(authService, repository, service);

        AdminQueryTaskLimitsResponse response = controller.limits("Bearer admin");

        assertThat(response.activeTasksForUser()).isEqualTo(1);
        assertThat(response.expiredTasksReadyToArchive()).isEqualTo(5);
        verify(authService).requireAdminPermission("Bearer admin", "admin.queries.read");
    }

    @Test
    void tasksDelegatesWithCursorAndSort() {
        AuthService authService = mock(AuthService.class);
        AdminQueryTaskRepository repository = mock(AdminQueryTaskRepository.class);
        AdminQueryTaskService service = mock(AdminQueryTaskService.class);
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        AdminQueryTaskResponse task = new AdminQueryTaskResponse(
                42L, 7L, "ops", "OUTBOX_BACKLOG", "PENDING", "{}", null,
                0, 0, null, now, null, null, now.plusSeconds(3600), null, null);
        when(repository.taskPage("PENDING", "OUTBOX_BACKLOG", 2, "cursor-1", "requestedAt.asc"))
                .thenReturn(new AdminCursorPage.CursorPage<>(List.of(task),
                        "cursor-2", true, "requestedAt.asc", 2));
        AdminQueryTaskController controller = new AdminQueryTaskController(authService, repository, service);

        AdminQueryTaskQueryResponse response = controller.tasks(
                "Bearer admin", "PENDING", "OUTBOX_BACKLOG", 2, "cursor-1", "requestedAt.asc");

        assertThat(response.tasks()).containsExactly(task);
        assertThat(response.nextCursor()).isEqualTo("cursor-2");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("requestedAt.asc");
        assertThat(response.limit()).isEqualTo(2);
        verify(authService).requireAdminPermission("Bearer admin", "admin.queries.read");
    }

    @Test
    void archiveExpiredDelegatesWithWritePermission() {
        AuthService authService = mock(AuthService.class);
        AdminQueryTaskRepository repository = mock(AdminQueryTaskRepository.class);
        AdminQueryTaskService service = mock(AdminQueryTaskService.class);
        JwtPrincipal principal = principal();
        when(authService.requireAdminPermission("Bearer admin", "admin.queries.write")).thenReturn(principal);
        AdminQueryTaskArchiveRequest request = new AdminQueryTaskArchiveRequest(7, 500, "manual cleanup");
        when(service.archiveExpired(eq(request), any())).thenReturn(new AdminQueryTaskArchiveResponse(
                12, Instant.parse("2026-06-26T00:00:00Z"), 500, "manual cleanup"));
        AdminQueryTaskController controller = new AdminQueryTaskController(authService, repository, service);

        AdminQueryTaskArchiveResponse response = controller.archiveExpired("Bearer admin", request);

        assertThat(response.archivedCount()).isEqualTo(12);
        verify(authService).requireAdminPermission("Bearer admin", "admin.queries.write");
    }

    @Test
    void createMapsQuotaExceededToTooManyRequests() {
        AuthService authService = mock(AuthService.class);
        AdminQueryTaskRepository repository = mock(AdminQueryTaskRepository.class);
        AdminQueryTaskService service = mock(AdminQueryTaskService.class);
        JwtPrincipal principal = principal();
        AdminQueryTaskCreateRequest request = new AdminQueryTaskCreateRequest("OUTBOX_BACKLOG", Map.of());
        when(authService.requireAdminPermission("Bearer admin", "admin.queries.write")).thenReturn(principal);
        when(service.create(eq(principal), eq(request), any())).thenThrow(
                new AdminQueryTaskService.QueryTaskQuotaExceededException("quota exceeded"));
        AdminQueryTaskController controller = new AdminQueryTaskController(authService, repository, service);

        assertThatThrownBy(() -> controller.create("Bearer admin", request))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    private JwtPrincipal principal() {
        return new JwtPrincipal(7L, "ops", "NORMAL", List.of("ADMIN"), Instant.now().plusSeconds(60));
    }
}
