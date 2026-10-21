package com.surprising.gateway.provider.auth.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.repository.UserNotificationRepository;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.UserNotificationService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@EnabledIfEnvironmentVariable(named="LOGIN_TEST_JDBC_URL",matches=".+")
class UserNotificationControllerPostgresTest {
    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement
    static class Transactions {}

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private MockMvc mvc;
    private static final Instant OLD=Instant.parse("2020-01-01T00:00:00Z");

    @BeforeEach void prepare() {
        String url=System.getenv("LOGIN_TEST_JDBC_URL"),user=System.getenv("LOGIN_TEST_DB_USER"),password=System.getenv("LOGIN_TEST_DB_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        schema="mvc_notifications_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE SCHEMA "+schema);
        var source=new DriverManagerDataSource(url+"?currentSchema="+schema,user,password);
        jdbc=new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE gateway_user_notifications (
                  notification_id BIGSERIAL PRIMARY KEY,user_id BIGINT NOT NULL,category TEXT NOT NULL,
                  title TEXT NOT NULL,body TEXT NOT NULL,read_at TIMESTAMPTZ,created_at TIMESTAMPTZ NOT NULL)
                """);
        context=new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(DataSource.class,()->source);
        context.registerBean(PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(JdbcTemplate.class,()->jdbc);
        context.registerBean(UserNotificationRepository.class);
        context.registerBean(UserNotificationService.class);
        context.refresh();
        var auth=mock(AuthService.class);
        when(auth.authenticateBearer("Bearer user")).thenReturn(new JwtPrincipal(42,"user","NORMAL",List.of("USER"),Instant.now().plusSeconds(60)));
        when(auth.authenticateBearer("Bearer bad")).thenThrow(new IllegalArgumentException("bad bearer"));
        mvc=MockMvcBuilders.standaloneSetup(new UserNotificationController(auth,context.getBean(UserNotificationService.class))).build();
    }

    @AfterEach void clean() {
        if(context!=null)context.close();
        if(admin!=null && schema!=null && schema.startsWith("mvc_notifications_"))admin.execute("DROP SCHEMA "+schema+" CASCADE");
    }

    @Test void listUsesAuthenticatedOwnerAndPreservesLimitClampingAndUnreadFilter() throws Exception {
        add(42,"own-unread",null);add(99,"other",null);add(42,"own-read",OLD);
        mvc.perform(get("/api/v1/notifications").header("Authorization","Bearer user").header("X-User-Id","99").param("limit","0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].title").value("own-read"));
        mvc.perform(get("/api/v1/notifications").header("Authorization","Bearer user").param("unreadOnly","true").param("limit","1000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].title").value("own-unread"));
        for(int i=0;i<101;i++)add(42,"bulk-"+i,null);
        mvc.perform(get("/api/v1/notifications").header("Authorization","Bearer user").param("unreadOnly","true").param("limit","1000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(100));
    }

    @Test void markReadCannotTouchAnotherOwnerAndKeepsTheFirstReadTime() throws Exception {
        long own=add(42,"already-read",OLD),other=add(99,"other-unread",null);
        mvc.perform(post("/api/v1/notifications/"+other+"/read").header("Authorization","Bearer user"))
                .andExpect(status().isNotFound());
        assertThat(readAt(other)).isNull();
        for(int i=0;i<2;i++)mvc.perform(post("/api/v1/notifications/"+own+"/read").header("Authorization","Bearer user"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notificationId").value(own)).andExpect(jsonPath("$.readAt").value(OLD.toString()));
        assertThat(readAt(own)).isEqualTo(OLD);
    }

    @Test void markAllReadChangesOnlyUnreadNotificationsForTheActor() throws Exception {
        long unread=add(42,"unread",null),read=add(42,"read",OLD),other=add(99,"other",null);
        mvc.perform(post("/api/v1/notifications/read-all").header("Authorization","Bearer user").header("X-User-Id","99"))
                .andExpect(status().isOk()).andExpect(content().string("1"));
        assertThat(readAt(unread)).isNotNull();assertThat(readAt(read)).isEqualTo(OLD);assertThat(readAt(other)).isNull();
    }

    @Test void badCredentialsAndNonpositiveIdsKeepTheirHttpContractWithoutChangingState() throws Exception {
        long id=add(42,"unread",null);
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/notifications/"+id+"/read").header("Authorization","Bearer bad"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/notifications/0/read").header("Authorization","Bearer user"))
                .andExpect(status().isNotFound());
        assertThat(readAt(id)).isNull();
    }

    @Test void failedResultReadRollsBackTheStateChangeInsteadOfMarkingItReadPartially() {
        long id=add(42,"damaged",null);
        jdbc.execute("ALTER TABLE gateway_user_notifications ALTER COLUMN created_at DROP NOT NULL");
        jdbc.update("UPDATE gateway_user_notifications SET created_at=NULL WHERE notification_id=?",id);
        assertThatThrownBy(()->mvc.perform(post("/api/v1/notifications/"+id+"/read").header("Authorization","Bearer user")))
                .hasRootCauseInstanceOf(NullPointerException.class);
        assertThat(readAt(id)).isNull();
    }

    private long add(long user,String title,Instant readAt) {
        return jdbc.queryForObject("""
                INSERT INTO gateway_user_notifications(user_id,category,title,body,read_at,created_at)
                VALUES (?,'SYSTEM',?,'body',?,?) RETURNING notification_id
                """,Long.class,user,title,readAt==null?null:Timestamp.from(readAt),Timestamp.from(OLD));
    }

    private Instant readAt(long id) {
        Timestamp value=jdbc.queryForObject("SELECT read_at FROM gateway_user_notifications WHERE notification_id=?",Timestamp.class,id);
        return value==null?null:value.toInstant();
    }
}
