package com.surprising.gateway.provider.announcement;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.surprising.gateway.provider.announcement.model.AnnouncementModels.*;
import com.surprising.gateway.provider.announcement.repository.*;
import com.surprising.gateway.provider.announcement.service.AnnouncementService;
import com.surprising.gateway.provider.announcement.exception.AnnouncementProblem;
import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AuthService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class AnnouncementPostgresTest {
    @Configuration @EnableTransactionManagement static class Transactions { }
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private CountingSource source;
    private DataSourceTransactionManager manager;
    private AnnouncementService service;
    private final JwtPrincipal actor=new JwtPrincipal(9,"operator","NORMAL",List.of("ADMIN"),Instant.now().plusSeconds(600));
    private final byte[] raw={1,2,3};
    private final AdminApprovalService.AdminRequestMetadata metadata=new AdminApprovalService.AdminRequestMetadata("approval-1","POST","/api/v1/admin/announcements/publish",null);
    @BeforeEach void setup() {
        var rawSource=new DriverManagerDataSource(System.getenv("INSTRUMENT_TEST_JDBC_URL"),System.getenv("INSTRUMENT_TEST_DB_USER"),System.getenv("INSTRUMENT_TEST_DB_PASSWORD"));
        source=new CountingSource(rawSource);jdbc=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);
        jdbc.execute("TRUNCATE gateway_announcements CASCADE");
        for(long id:List.of(9L,42L,43L))jdbc.update("INSERT INTO gateway_users(user_id,username,password_hash) VALUES (?,?,?) ON CONFLICT(user_id) DO NOTHING",id,"mvc_announcement_"+id,"test-only");
        var auth=mock(AuthService.class);
        when(auth.requireAdminPermission(eq("admin"),anyString())).thenReturn(actor);
        for(long id:List.of(42L,43L))when(auth.authenticateBearer("user"+id)).thenReturn(new JwtPrincipal(id,"reader"+id,"NORMAL",List.of(),Instant.now().plusSeconds(600)));
        var approvals=mock(AdminApprovalService.class);when(approvals.requireWrite(eq("admin"),eq("admin.announcements.publish"),eq("gateway-admin"),eq(metadata),same(raw))).thenReturn(actor);
        var json=JsonMapper.builder().findAndAddModules().build();
        context=new AnnotationConfigApplicationContext();context.register(Transactions.class);
        context.registerBean(DataSourceTransactionManager.class,()->manager);
        context.registerBean(AnnouncementRepository.class,()->new AnnouncementRepository(jdbc));
        context.registerBean(AnnouncementTranslationRepository.class,()->new AnnouncementTranslationRepository(jdbc));
        context.registerBean(AnnouncementTargetRepository.class,()->new AnnouncementTargetRepository(jdbc));
        context.registerBean(AnnouncementReadRepository.class,()->new AnnouncementReadRepository(jdbc));
        context.registerBean(AnnouncementAuditRepository.class,()->new AnnouncementAuditRepository(jdbc,json));
        context.registerBean(AuthService.class,()->auth);context.registerBean(AdminApprovalService.class,()->approvals);
        context.register(AnnouncementService.class);context.refresh();service=context.getBean(AnnouncementService.class);
    }
    @AfterEach void close() { if(context!=null)context.close(); }
    private Content content(String title,String line,Instant starts,Instant expires) {
        return new Content("GENERAL",10,List.of("CENTER","MODAL"),List.of(line),List.of("WEB"),starts,expires,
                List.of(new Translation("en-US",title,"Summary","Body"),new Translation("zh-CN",title+"中文","摘要","正文"),new Translation("en",title+" base","Summary","Body")));
    }
    private Content content(String title) { return content(title,"SPOT",Instant.now().minusSeconds(60),null); }
    private ClientFilter filter(String locale,String line) { int split=locale.indexOf('-');return new ClientFilter(locale,split<0?locale:locale.substring(0,split),line,"WEB","CENTER"); }
    private Announcement published(Content content) { var draft=service.create("admin",content);return service.publish("admin",draft.announcementId(),draft.version(),"publish",metadata,raw); }
    private Map<String,List<Map<String,Object>>> snapshot() {
        var result=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:List.of("gateway_announcements","gateway_announcement_translations","gateway_announcement_targets","gateway_announcement_reads","gateway_announcement_audit"))
            result.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1,2,3"));
        return result;
    }
    @Test void partialTranslationFailureRollsBackTheWholeDraft() {
        jdbc.execute("ALTER TABLE gateway_announcement_translations ADD CONSTRAINT mvc_translation_fault CHECK(title<>'FAULT') NOT VALID");
        try { assertThatThrownBy(()->service.create("admin",content("FAULT"))).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(snapshot().values()).allSatisfy(rows->assertThat(rows).isEmpty());
        } finally { jdbc.execute("ALTER TABLE gateway_announcement_translations DROP CONSTRAINT mvc_translation_fault"); }
    }
    @ParameterizedTest @ValueSource(strings={"CREATE","UPDATE","PUBLISH","WITHDRAW"})
    void requiredAuditFailureRollsBackStateChildrenAndReadReceipts(String action) {
        Announcement existing=action.equals("CREATE")?null:published(content("Before"));
        if(existing!=null)service.markRead("user42",existing.announcementId(),filter("en-US","SPOT"));
        var before=snapshot();
        jdbc.execute("ALTER TABLE gateway_announcement_audit ADD CONSTRAINT mvc_audit_fault CHECK(action<>'"+action+"') NOT VALID");
        try {
            assertThatThrownBy(()->{
                switch(action) {
                    case "CREATE" -> service.create("admin",content("New"));
                    case "UPDATE" -> service.update("admin",existing.announcementId(),content("Changed"),existing.version());
                    case "PUBLISH" -> service.publish("admin",existing.announcementId(),existing.version(),"republish",metadata,raw);
                    case "WITHDRAW" -> service.withdraw("admin",existing.announcementId(),existing.version(),"withdraw",metadata,raw);
                    default -> throw new IllegalArgumentException(action);
                }
            }).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(snapshot()).isEqualTo(before);
        } finally { jdbc.execute("ALTER TABLE gateway_announcement_audit DROP CONSTRAINT mvc_audit_fault"); }
    }
    @Test void replacingTargetsCannotLeaveNewTranslationsOrVersionAfterFailure() {
        var existing=published(content("Before"));service.markRead("user42",existing.announcementId(),filter("en-US","SPOT"));
        var before=snapshot();var changed=content("Changed");
        changed=new Content(changed.category(),changed.priority(),changed.placements(),changed.productLines(),List.of("IOS"),changed.startsAt(),changed.expiresAt(),changed.translations());
        var next=changed;jdbc.execute("ALTER TABLE gateway_announcement_targets ADD CONSTRAINT mvc_target_fault CHECK(target_value<>'IOS') NOT VALID");
        try { assertThatThrownBy(()->service.update("admin",existing.announcementId(),next,existing.version())).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(snapshot()).isEqualTo(before);
        } finally { jdbc.execute("ALTER TABLE gateway_announcement_targets DROP CONSTRAINT mvc_target_fault"); }
    }
    @Test void adminPageLoadsAllChildrenInFourQueriesRegardlessOfPageSize() {
        for(int i=0;i<20;i++)service.create("admin",content("Announcement "+i));
        source.statements.set(0);var page=service.adminList("admin",null,null,0,20);
        assertThat(source.statements.get()).isEqualTo(4);
        assertThat(page.count()).isEqualTo(20);assertThat(page.announcements()).hasSize(20);
        assertThat(page.announcements()).allSatisfy(row->{assertThat(row.translations()).hasSize(3);assertThat(row.placements()).containsExactly("CENTER","MODAL");assertThat(row.productLines()).containsExactly("SPOT");});
        source.statements.set(0);assertThat(service.adminList("admin","WITHDRAWN",null,0,20).announcements()).isEmpty();
        assertThat(source.statements.get()).isEqualTo(2);
        assertThat(service.adminList("admin",null,"Announcement 19",0,20).count()).isEqualTo(1);
    }
    @Test void clientVisibilityLanguageAndReadReceiptsRemainScopedAndIdempotent() {
        var live=published(content("Live"));var other=published(content("Other","OPTION",Instant.now().minusSeconds(60),null));
        published(content("Future","SPOT",Instant.now().plusSeconds(3600),null));
        published(content("Expired","SPOT",Instant.now().minusSeconds(7200),Instant.now().minusSeconds(3600)));
        var filter=filter("ja-JP","SPOT");var anonymous=service.clientList(null,filter,0,20);
        assertThat(anonymous.announcements()).hasSize(1);assertThat(anonymous.announcements().getFirst().locale()).isEqualTo("en-US");
        assertThat(anonymous.unreadCount()).isZero();
        assertThat(service.clientGet(null,live.announcementId(),filter("en-GB","SPOT")).locale()).isEqualTo("en");
        assertThat(service.clientGet(null,live.announcementId(),filter("zh-CN","SPOT")).title()).endsWith("中文");
        assertThat(service.unreadCount("user42",filter)).isEqualTo(1);
        assertThat(service.markRead("user42",live.announcementId(),filter).readAt()).isNotNull();
        service.markRead("user42",live.announcementId(),filter);
        assertThat(service.unreadCount("user42",filter)).isZero();assertThat(service.unreadCount("user43",filter)).isEqualTo(1);
        assertThatThrownBy(()->service.markRead("user42",other.announcementId(),filter)).isInstanceOf(AnnouncementProblem.class);
        published(content("Second"));assertThat(service.markAllRead("user43",filter)).isEqualTo(2);
        assertThat(service.markAllRead("user43",filter)).isZero();assertThat(service.unreadCount("user43",filter)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_announcement_reads WHERE announcement_id=?",Integer.class,other.announcementId())).isZero();
    }
    @Test void editingWaitsForReadAllSoOldReceiptsCannotReappearAfterItsReset() throws Exception {
        var existing=published(content("Before"));var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var reader=executor.submit(()->new TransactionTemplate(manager).execute(status->{
                assertThat(service.markAllRead("user42",filter("en-US","SPOT"))).isEqualTo(1);held.countDown();
                try { assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); }catch(InterruptedException ex){throw new RuntimeException(ex);}return 1;
            }));
            Future<Announcement> editor=null;
            try {
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                editor=executor.submit(()->new TransactionTemplate(manager).execute(status->{
                    jdbc.execute("SET LOCAL application_name='mvc_announcement_editor'");
                    return service.update("admin",existing.announcementId(),content("Changed"),existing.version());
                }));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);boolean blocked=false;
                while(System.nanoTime()<deadline && !editor.isDone()) {
                    blocked=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name='mvc_announcement_editor' AND wait_event_type='Lock'",Integer.class)>0;
                    if(blocked)break;Thread.sleep(10);
                }
                assertThat(blocked).as("editor waits on the announcement row before it clears receipts").isTrue();
            } finally { release.countDown();reader.get(5,TimeUnit.SECONDS); }
            assertThat(editor.get(5,TimeUnit.SECONDS).version()).isEqualTo(existing.version()+1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gateway_announcement_reads WHERE announcement_id=?",Integer.class,existing.announcementId())).isZero();
    }
    private static final class CountingSource extends DelegatingDataSource {
        final AtomicInteger statements=new AtomicInteger();
        CountingSource(DataSource target){super(target);}
        @Override public Connection getConnection() throws java.sql.SQLException {
            var delegate=super.getConnection();
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("prepareStatement"))statements.incrementAndGet();
                try{return method.invoke(delegate,args);}catch(InvocationTargetException ex){throw ex.getCause();}
            });
        }
    }
}
