package com.surprising.gateway.provider.legal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.surprising.gateway.provider.auth.model.AdminRequestMetadata;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.*;
import com.surprising.gateway.provider.content.model.ContentLocale;
import com.surprising.gateway.provider.content.service.ContentMediaService;
import com.surprising.gateway.provider.legal.exception.LegalProblem;
import com.surprising.gateway.provider.legal.model.LegalDocumentType;
import com.surprising.gateway.provider.legal.model.LegalModels.*;
import com.surprising.gateway.provider.legal.repository.*;
import com.surprising.gateway.provider.legal.service.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class LegalPostgresTest {
    @Configuration @EnableTransactionManagement static class Transactions { }
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private LegalCmsService cms;
    private LegalDocumentService documents;
    private LegalConsentService consents;
    private final byte[] raw={1,2,3};
    private final AdminRequestMetadata metadata=new AdminRequestMetadata(null,"POST","/api/v1/admin/legal/revisions/publish",null);
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource(System.getenv("INSTRUMENT_TEST_JDBC_URL"),System.getenv("INSTRUMENT_TEST_DB_USER"),System.getenv("INSTRUMENT_TEST_DB_PASSWORD"));
        jdbc=new JdbcTemplate(source);jdbc.execute("TRUNCATE gateway_legal_revisions CASCADE");
        for(long id:List.of(9L,42L))jdbc.update("INSERT INTO gateway_users(user_id,username,password_hash) VALUES (?,?,?) ON CONFLICT(user_id) DO NOTHING",id,"legal_qa_"+id,"test-only");
        var actor=new JwtPrincipal(9,"admin","NORMAL",List.of("ADMIN"),Instant.now().plusSeconds(600));
        var auth=mock(AuthService.class);when(auth.requireAdminPermission(eq("admin"),anyString())).thenReturn(actor);
        var approvals=mock(AdminApprovalService.class);when(approvals.requireWrite(eq("admin"),eq("admin.legal.publish"),eq("gateway-admin"),eq(metadata),same(raw))).thenReturn(actor);
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);
        context.registerBean(DataSourceTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(JdbcTemplate.class,()->jdbc);
        context.registerBean(tools.jackson.databind.ObjectMapper.class,()->JsonMapper.builder().findAndAddModules().build());
        context.registerBean(AuthService.class,()->auth);context.registerBean(AdminApprovalService.class,()->approvals);
        context.registerBean(ContentMediaService.class,()->mock(ContentMediaService.class));
        context.registerBean(com.surprising.gateway.provider.content.repository.ContentMediaRepository.class,()->new com.surprising.gateway.provider.content.repository.ContentMediaRepository(jdbc));
        context.registerBean(com.surprising.gateway.provider.content.client.ContentR2Storage.class,()->mock(com.surprising.gateway.provider.content.client.ContentR2Storage.class));
        context.register(com.surprising.gateway.provider.content.service.ArticleRenderingService.class);
        context.register(LegalRevisionRepository.class,LegalTranslationRepository.class,LegalConsentRepository.class,LegalAuditRepository.class,LegalCmsService.class,LegalDocumentService.class,LegalConsentService.class);
        context.refresh();cms=context.getBean(LegalCmsService.class);documents=context.getBean(LegalDocumentService.class);consents=context.getBean(LegalConsentService.class);
    }
    @AfterEach void close() { if(context!=null)context.close(); }
    private Content content(LegalDocumentType type,String label,String text) {
        return new Content(type,label,ContentLocale.supported().stream().map(locale->new Translation(locale.code(),"Synthetic fixture "+locale.code(),"<p>"+text+" "+locale.code()+"</p>")).toList());
    }
    private Revision published(LegalDocumentType type,String label,String text) {
        var draft=cms.create("admin",content(type,label,text));return cms.publish("admin",draft.revisionId(),draft.version(),"QA publication",metadata,raw);
    }
    @Test void replacementPreservesAcceptedTextAndRequiresOnlyTheNewRevision() {
        var terms=published(LegalDocumentType.USER_AGREEMENT,"1.0","Old terms");
        var privacy=published(LegalDocumentType.PRIVACY_POLICY,"1.0","Privacy");
        var choices=List.of(new Acceptance(terms.revisionId(),"en-US"),new Acceptance(privacy.revisionId(),"en-US"));
        consents.acknowledge(42,choices,"en-US");
        var acceptedAt=jdbc.queryForObject("SELECT acknowledged_at FROM gateway_legal_consents WHERE user_id=42 AND revision_id=?",java.sql.Timestamp.class,terms.revisionId());
        consents.acknowledge(42,choices,"en-US");
        assertThat(jdbc.queryForObject("SELECT acknowledged_at FROM gateway_legal_consents WHERE user_id=42 AND revision_id=?",java.sql.Timestamp.class,terms.revisionId())).isEqualTo(acceptedAt);
        assertThat(consents.status(42,"en-US").missingRevisionIds()).isEmpty();
        assertThatThrownBy(()->cms.update("admin",terms.revisionId(),terms.version(),content(LegalDocumentType.USER_AGREEMENT,"1.0","Mutation"))).isInstanceOf(LegalProblem.class);
        var replacement=published(LegalDocumentType.USER_AGREEMENT,"1.1","New terms");
        assertThat(documents.revision(terms.revisionId(),"en-US").bodyHtml()).contains("Old terms");
        assertThat(consents.status(42,"en-US").missingRevisionIds()).containsExactly(replacement.revisionId());
        assertThatThrownBy(()->consents.acknowledge(42,List.of(new Acceptance(terms.revisionId(),"en-US")),"en-US")).isInstanceOf(LegalProblem.class);
        consents.recordAndRequire(42,List.of(new Acceptance(replacement.revisionId(),"en-US")),Instant.now());
        assertThat(consents.status(42,"en-US").missingRevisionIds()).isEmpty();
    }
    @Test void auditFailureRollsBackBothReplacementAndPriorCurrentPointer() {
        var original=published(LegalDocumentType.USER_AGREEMENT,"1.0","Original");
        var draft=cms.create("admin",content(LegalDocumentType.USER_AGREEMENT,"1.1","Replacement"));
        jdbc.execute("ALTER TABLE gateway_legal_audit ADD CONSTRAINT legal_qa_audit_failure CHECK(action<>'PUBLISH') NOT VALID");
        try {
            assertThatThrownBy(()->cms.publish("admin",draft.revisionId(),draft.version(),"",metadata,raw)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(documents.current("en-US")).singleElement().satisfies(d->assertThat(d.revisionId()).isEqualTo(original.revisionId()));
            assertThat(cms.get("admin",draft.revisionId()).status()).isEqualTo("DRAFT");
            assertThat(cms.get("admin",draft.revisionId()).version()).isEqualTo(draft.version());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_legal_audit WHERE action='SUPERSEDE'",Integer.class)).isZero();
        } finally { jdbc.execute("ALTER TABLE gateway_legal_audit DROP CONSTRAINT legal_qa_audit_failure"); }
    }
    @Test void simultaneousPublicationsLeaveExactlyOneCurrentRevisionAndRetainBothTexts() throws Exception {
        var a=cms.create("admin",content(LegalDocumentType.USER_AGREEMENT,"1.0","A"));
        var b=cms.create("admin",content(LegalDocumentType.USER_AGREEMENT,"1.1","B"));
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->cms.publish("admin",a.revisionId(),a.version(),"A",metadata,raw));
            var second=executor.submit(()->cms.publish("admin",b.revisionId(),b.version(),"B",metadata,raw));
            first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_legal_revisions WHERE status='PUBLISHED'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_legal_translations",Integer.class)).isEqualTo(24);
        assertThat(documents.revision(a.revisionId(),"en-US").bodyHtml()).contains("A");
        assertThat(documents.revision(b.revisionId(),"en-US").bodyHtml()).contains("B");
    }
}
