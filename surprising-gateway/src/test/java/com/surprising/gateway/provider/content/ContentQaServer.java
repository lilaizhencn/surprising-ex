package com.surprising.gateway.provider.content;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.surprising.gateway.provider.announcement.controller.*;
import com.surprising.gateway.provider.announcement.repository.*;
import com.surprising.gateway.provider.announcement.service.AnnouncementService;
import com.surprising.gateway.provider.announcement.exception.AnnouncementExceptionHandler;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import com.surprising.gateway.provider.config.*;
import com.surprising.gateway.provider.interceptor.*;
import com.surprising.gateway.provider.content.client.ContentR2Storage;
import com.surprising.gateway.provider.content.config.ContentMediaConfiguration;
import com.surprising.gateway.provider.content.controller.ContentMediaController;
import com.surprising.gateway.provider.content.repository.ContentMediaRepository;
import com.surprising.gateway.provider.content.service.ContentMediaService;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.cors.*;

/** Local UI harness: real PostgreSQL, transactions, HTTP, permissions filter and R2; controlled identity/approval only. */
@TestConfiguration(proxyBeanMethods=false)
@EnableAutoConfiguration
@EnableTransactionManagement
@Import({AdminAnnouncementController.class,ClientAnnouncementController.class,AnnouncementExceptionHandler.class,
        AnnouncementService.class,AnnouncementRepository.class,AnnouncementTranslationRepository.class,
        AnnouncementTargetRepository.class,AnnouncementReadRepository.class,AnnouncementAuditRepository.class,
        ContentMediaConfiguration.class,ContentR2Storage.class,com.surprising.gateway.provider.content.service.ArticleRenderingService.class,ContentMediaController.class,ContentMediaRepository.class,ContentMediaService.class,
        com.surprising.gateway.provider.content.repository.ContentMediaReferenceRepository.class,
        com.surprising.gateway.provider.legal.controller.AdminLegalController.class,
        com.surprising.gateway.provider.legal.controller.LegalController.class,
        com.surprising.gateway.provider.legal.exception.LegalExceptionHandler.class,
        com.surprising.gateway.provider.legal.service.LegalCmsService.class,
        com.surprising.gateway.provider.legal.service.LegalDocumentService.class,
        com.surprising.gateway.provider.legal.service.LegalConsentService.class,
        com.surprising.gateway.provider.legal.repository.LegalRevisionRepository.class,
        com.surprising.gateway.provider.legal.repository.LegalTranslationRepository.class,
        com.surprising.gateway.provider.legal.repository.LegalConsentRepository.class,
        com.surprising.gateway.provider.legal.repository.LegalAuditRepository.class,
        GatewaySecurityConfiguration.class,AdminPermissionFilter.class,AdminIpWhitelistFilter.class})
public class ContentQaServer {
    private static final JwtPrincipal ADMIN=new JwtPrincipal(9,"content-qa-admin","NORMAL",List.of("SUPER_ADMIN"),Instant.now().plusSeconds(7200));
    public static void main(String[] args) {
        var application=new SpringApplication(ContentQaServer.class);
        application.addInitializers(context -> {
            var environment=context.getEnvironment();
            if (!environment.getProperty("spring.datasource.url", "").contains("/surprising_content_qa_")
                    || !"127.0.0.1".equals(environment.getProperty("server.address")))
                throw new IllegalArgumentException("Content QA requires a dedicated QA database and loopback binding");
        });
        application.run(args);
    }
    @Bean GatewayProperties gatewayProperties() { return new GatewayProperties(); }
    @Bean AuthService authService() {
        var auth=mock(AuthService.class);
        when(auth.requireAdminPermission(anyString(),anyString())).thenThrow(new IllegalStateException("denied"));
        when(auth.verifyBearer(anyString())).thenThrow(new IllegalArgumentException("invalid test identity"));
        when(auth.authenticateBearer(anyString())).thenThrow(new IllegalArgumentException("invalid test identity"));
        doReturn(ADMIN).when(auth).requireAdminPermission(eq("Bearer content-qa-admin"),anyString());
        doReturn(ADMIN).when(auth).verifyBearer("Bearer content-qa-admin");
        var reader=new JwtPrincipal(42,"content-qa-reader","NORMAL",List.of(),Instant.now().plusSeconds(7200));
        doReturn(reader).when(auth).authenticateBearer("Bearer content-qa-reader");
        doReturn(reader).when(auth).verifyBearer("Bearer content-qa-reader");
        return auth;
    }
    @Bean AdminApprovalService approvals() {
        var approvals=mock(AdminApprovalService.class);
        when(approvals.requireWrite(eq("Bearer content-qa-admin"),eq("admin.announcements.publish"),eq("gateway-admin"),any(),any())).thenReturn(ADMIN);
        when(approvals.requireWrite(eq("Bearer content-qa-admin"),eq("admin.legal.publish"),eq("gateway-admin"),any(),any())).thenReturn(ADMIN);
        when(approvals.approvalHeaderName()).thenReturn("X-Admin-Approval-Id");
        return approvals;
    }
    @Bean(name="gatewayCorsSource") CorsConfigurationSource cors() {
        var configuration=new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://127.0.0.1:5174","http://127.0.0.1:5173"));
        configuration.setAllowedMethods(List.of("GET","POST","PUT","OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization","Content-Type","X-Product-Line","X-Admin-Approval-Id","X-Device-Id","X-User-Id","X-Auth-Client","X-XSRF-TOKEN","X-Auth-Transport","X-Legal-Acceptance"));
        configuration.setAllowCredentials(true);
        var source=new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/**",configuration); return source;
    }
}
