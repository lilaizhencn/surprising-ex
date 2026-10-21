package com.surprising.gateway.provider.announcement;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.surprising.gateway.provider.announcement.model.AnnouncementModels.*;
import com.surprising.gateway.provider.announcement.repository.*;
import com.surprising.gateway.provider.announcement.service.AnnouncementService;
import com.surprising.gateway.provider.announcement.exception.AnnouncementProblem;
import com.surprising.gateway.provider.auth.AdminApprovalService;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AnnouncementServiceTest {
    private final AnnouncementRepository repository=mock(AnnouncementRepository.class);
    private final AnnouncementTranslationRepository translations=mock(AnnouncementTranslationRepository.class);
    private final AnnouncementTargetRepository targets=mock(AnnouncementTargetRepository.class);
    private final AnnouncementAuditRepository audit=mock(AnnouncementAuditRepository.class);
    private final AdminApprovalService approvals=mock(AdminApprovalService.class);
    private final AnnouncementService service=new AnnouncementService(repository,translations,targets,mock(AnnouncementReadRepository.class),audit,mock(AuthService.class),approvals);
    private final JwtPrincipal actor=new JwtPrincipal(9,"operator","ACTIVE",List.of("ADMIN"),Instant.now().plusSeconds(60));
    private final AdminApprovalService.AdminRequestMetadata metadata=new AdminApprovalService.AdminRequestMetadata("approval-1","POST","/api/v1/admin/announcements/7/publish",null);
    private final byte[] raw={1,2,3};
    private BaseRow row(String status,long version,Instant start) { return new BaseRow(7,"GENERAL",status,10,start,null,version,9,9,start,start); }
    private Translation translation(String locale) { return new Translation(locale,"Title","Summary","Body"); }
    private void ready() {
        when(approvals.requireWrite("Bearer admin","admin.announcements.publish","gateway-admin",metadata,raw)).thenReturn(actor);
        when(targets.findFor(List.of(7L))).thenReturn(List.of(new TargetRow(7,"PLACEMENT","CENTER"),new TargetRow(7,"PLACEMENT","MODAL"),new TargetRow(7,"PRODUCT_LINE","ALL"),new TargetRow(7,"PLATFORM","ALL")));
    }
    @Test void publishingRequiresBothSupportedLocalesBeforeChangingStatus() {
        ready();when(repository.find(7)).thenReturn(Optional.of(row("DRAFT",3,Instant.now())));
        when(translations.findFor(List.of(7L))).thenReturn(List.of(new TranslationRow(7,translation("zh-CN"))));
        assertThatThrownBy(()->service.publish("Bearer admin",7,3,"",metadata,raw)).isInstanceOf(AnnouncementProblem.class).hasMessageContaining("zh-CN and en-US");
        verify(repository,never()).publish(anyLong(),anyLong(),anyLong(),any());
    }
    @Test void publishingPreservesScheduleAndAuditsTheReturnedSnapshot() {
        ready();var start=Instant.now().plusSeconds(3600);
        when(repository.find(7)).thenReturn(Optional.of(row("DRAFT",3,start)),Optional.of(row("PUBLISHED",4,start)));
        when(translations.findFor(List.of(7L))).thenReturn(List.of(new TranslationRow(7,translation("zh-CN")),new TranslationRow(7,translation("en-US"))));
        when(repository.publish(eq(7L),eq(3L),eq(9L),any())).thenReturn(true);
        var result=service.publish("Bearer admin",7,3,"maintenance window",metadata,raw);
        assertThat(result.effectiveStatus()).isEqualTo("SCHEDULED");
        verify(audit).append(eq(result),eq("PUBLISH"),eq(9L),eq("operator"),eq("maintenance window"),any());
        verify(approvals).requireWrite("Bearer admin","admin.announcements.publish","gateway-admin",metadata,raw);
    }
    @Test void updateCannotValidateOneRevisionAndWriteAnother() {
        when(repository.find(7)).thenReturn(Optional.of(row("DRAFT",1,Instant.now())));
        var content=new Content("GENERAL",0,List.of("CENTER"),List.of("ALL"),List.of("ALL"),Instant.now(),null,List.of(translation("en-US")));
        var auth=mock(AuthService.class);when(auth.requireAdminPermission("admin","admin.announcements.write")).thenReturn(actor);
        var service=new AnnouncementService(repository,translations,targets,mock(AnnouncementReadRepository.class),audit,auth,approvals);
        assertThatThrownBy(()->service.update("admin",7,content,2)).isInstanceOf(AnnouncementProblem.class).hasMessageContaining("changed");
        verify(repository,never()).update(anyLong(),any(),anyLong(),anyLong(),any());
    }
}
