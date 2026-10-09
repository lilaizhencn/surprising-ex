package com.surprising.gateway.provider.announcement;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.announcement.AnnouncementModels.Announcement;
import com.surprising.gateway.provider.announcement.AnnouncementModels.PublishRequest;
import com.surprising.gateway.provider.announcement.AnnouncementModels.Translation;
import com.surprising.gateway.provider.auth.AdminApprovalService;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AnnouncementServiceTest {

    private final AnnouncementRepository repository = mock(AnnouncementRepository.class);
    private final AuthService auth = mock(AuthService.class);
    private final AdminApprovalService approvals = mock(AdminApprovalService.class);
    private final AnnouncementService service = new AnnouncementService(repository, auth, approvals);
    private final JwtPrincipal actor = new JwtPrincipal(9L, "operator", "ACTIVE", List.of("ADMIN"), Instant.now().plusSeconds(60));
    private final AdminApprovalService.AdminRequestMetadata metadata =
            new AdminApprovalService.AdminRequestMetadata("approval-1", "POST", "/api/v1/admin/announcements/7/publish", null, "trace-1");

    @Test
    void publishingRequiresBothSupportedLocalesBeforeChangingStatus() {
        when(approvals.requireWrite(eq("Bearer admin"), eq("admin.announcements.publish"),
                eq("gateway-admin"), eq(metadata), any())).thenReturn(actor);
        when(repository.findAdmin(7L)).thenReturn(Optional.of(announcement(List.of(translation("zh-CN")))));

        assertThatThrownBy(() -> service.publish("Bearer admin", 7L, new PublishRequest("", 3L), metadata, new byte[0]))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("zh-CN and en-US");

        verify(repository, never()).publish(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void publishingPreservesScheduleAndWritesAnAuditSnapshot() {
        Instant startsAt = Instant.now().plusSeconds(3600);
        Announcement draft = new Announcement(7L, "GENERAL", "DRAFT", 10,
                List.of("CENTER", "MODAL"), List.of("ALL"), List.of("ALL"), startsAt, null, 3L,
                List.of(translation("zh-CN"), translation("en-US")), null, null, null, null,
                null, startsAt, startsAt, "DRAFT");
        Announcement published = new Announcement(7L, "GENERAL", "PUBLISHED", 10,
                draft.placements(), draft.productLines(), draft.platforms(), startsAt, null, 4L,
                draft.translations(), null, null, null, null, null, startsAt, startsAt, "SCHEDULED");
        when(approvals.requireWrite(eq("Bearer admin"), eq("admin.announcements.publish"),
                eq("gateway-admin"), eq(metadata), any())).thenReturn(actor);
        when(repository.findAdmin(7L)).thenReturn(Optional.of(draft), Optional.of(published));
        when(repository.publish(eq(7L), eq(3L), eq(9L), any())).thenReturn(true);

        Announcement result = service.publish("Bearer admin", 7L, new PublishRequest("maintenance window", 3L), metadata, new byte[0]);

        org.assertj.core.api.Assertions.assertThat(result.effectiveStatus()).isEqualTo("SCHEDULED");
        verify(repository).audit(eq(7L), eq("PUBLISH"), eq(9L), eq("operator"), eq("maintenance window"), any());
    }

    private Announcement announcement(List<Translation> translations) {
        Instant now = Instant.now();
        return new Announcement(7L, "GENERAL", "DRAFT", 0,
                List.of("CENTER"), List.of("ALL"), List.of("ALL"), now, null, 3L,
                translations, null, null, null, null, null, now, now, "DRAFT");
    }

    private Translation translation(String locale) {
        return new Translation(locale, "Title", "Summary", "Body");
    }
}
