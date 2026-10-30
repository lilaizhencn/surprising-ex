package com.surprising.gateway.provider.legal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.surprising.gateway.provider.auth.model.AdminRequestMetadata;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.*;
import com.surprising.gateway.provider.content.service.ContentMediaService;
import com.surprising.gateway.provider.legal.exception.LegalProblem;
import com.surprising.gateway.provider.legal.model.LegalDocumentType;
import com.surprising.gateway.provider.legal.model.LegalModels.*;
import com.surprising.gateway.provider.legal.repository.*;
import com.surprising.gateway.provider.legal.service.LegalCmsService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LegalCmsServiceTest {
    private final LegalRevisionRepository revisions=mock(LegalRevisionRepository.class);
    private final LegalTranslationRepository translations=mock(LegalTranslationRepository.class);
    private final LegalAuditRepository audit=mock(LegalAuditRepository.class);
    private final AuthService auth=mock(AuthService.class);
    private final AdminApprovalService approvals=mock(AdminApprovalService.class);
    private final LegalCmsService service=new LegalCmsService(revisions,translations,audit,mock(ContentMediaService.class),auth,approvals);
    private final Instant now=Instant.now();
    private final JwtPrincipal actor=new JwtPrincipal(9,"admin","NORMAL",List.of("ADMIN"),now.plusSeconds(60));
    private final AdminRequestMetadata metadata=new AdminRequestMetadata(null,"POST","/api/v1/admin/legal/revisions/1/publish",null);
    private final byte[] body={1,2,3};
    private RevisionRow row(String status) { return new RevisionRow(1,LegalDocumentType.USER_AGREEMENT,"1.0",status,1,9,9,now,now,status.equals("PUBLISHED")?now:null); }
    @Test void publishedTextCannotBeChangedInPlace() {
        when(auth.requireAdminPermission("admin","admin.legal.write")).thenReturn(actor);
        when(revisions.find(1)).thenReturn(Optional.of(row("PUBLISHED")));
        var content=new Content(LegalDocumentType.USER_AGREEMENT,"1.0",List.of(new Translation("en-US","Changed","<p>Changed</p>")));
        assertThatThrownBy(()->service.update("admin",1,1,content)).isInstanceOf(LegalProblem.class).hasMessageContaining("immutable");
        verify(revisions,never()).update(anyLong(),anyLong(),any(),anyLong(),any());verifyNoInteractions(translations,audit);
    }
    @Test void aPartialTranslationSetCannotReplaceTheCurrentLegalVersion() {
        when(approvals.requireWrite("admin","admin.legal.publish","gateway-admin",metadata,body)).thenReturn(actor);
        when(revisions.find(1)).thenReturn(Optional.of(row("DRAFT")));
        when(translations.findFor(List.of(1L))).thenReturn(List.of(new TranslationRow(1,new Translation("en-US","Title","<p>Text</p>"))));
        assertThatThrownBy(()->service.publish("admin",1,1,"",metadata,body)).isInstanceOf(LegalProblem.class).hasMessageContaining("twelve");
        verify(revisions,never()).supersedeCurrent(any(),anyLong(),any());verifyNoInteractions(audit);
    }
    @Test void requiredAccountAgreementsNeedAReplacementBeforeRetirement() {
        when(approvals.requireWrite("admin","admin.legal.publish","gateway-admin",metadata,body)).thenReturn(actor);
        when(revisions.find(1)).thenReturn(Optional.of(row("PUBLISHED")));
        assertThatThrownBy(()->service.withdraw("admin",1,1,"withdraw",metadata,body)).isInstanceOf(LegalProblem.class).hasMessageContaining("replacement");
        verify(revisions,never()).withdraw(anyLong(),anyLong(),anyLong(),any());
    }
}
