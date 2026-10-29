package com.surprising.gateway.provider.auth;

import static org.mockito.Mockito.*;

import com.surprising.gateway.provider.auth.client.*;
import com.surprising.gateway.provider.auth.controller.*;
import com.surprising.gateway.provider.auth.model.AuthModels.*;
import com.surprising.gateway.provider.auth.repository.*;
import com.surprising.gateway.provider.auth.service.*;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.service.*;
import com.surprising.gateway.provider.storage.KycDocumentStorageService;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** Loopback fixture: real controllers, transactions, PostgreSQL, synthetic documents and identities.
 * Authentication, email, upload rate limiting and malware scanning are explicit test doubles.
 * It never starts a wallet, Core, or calls an external identity provider. */
@EnabledIfEnvironmentVariable(named = "KYC_QA_DIR", matches = ".+")
class KycClosureBrowserQaTest {
    static GatewayProperties properties;
    static AuthService auth;
    static KycDocumentService documents;
    static ComplianceService target;
    static ComplianceKycRepository profiles;
    static DriverManagerDataSource source;

    @Configuration(proxyBeanMethods = false) @EnableWebMvc @EnableTransactionManagement
    static class Web implements WebMvcConfigurer {
        @Bean ComplianceService compliance() { return target; }
        @Bean org.springframework.transaction.PlatformTransactionManager transactions() { return new DataSourceTransactionManager(source); }
        @Bean UserComplianceController user(ComplianceService compliance) { return new UserComplianceController(compliance, auth, documents, properties); }
        @Bean AdminComplianceController admin(ComplianceService compliance) { return new AdminComplianceController(compliance,new ObjectMapper()); }
        @Bean StandardServletMultipartResolver multipartResolver() { return new StandardServletMultipartResolver(); }
        @Bean Controls controls() { return new Controls(); }
        @Override public void addCorsMappings(CorsRegistry registry) {
            registry.addMapping("/**").allowedOrigins("http://127.0.0.1:5183", "http://127.0.0.1:5184")
                    .allowedMethods("GET", "POST", "DELETE", "OPTIONS").allowedHeaders("*").allowCredentials(true);
        }
    }

    @RestController static class Controls {
        @PostMapping("/__qa/reset") void reset(@RequestHeader("Authorization") String token) {
            auth.requireAdminPermission(token,"admin.compliance.write");
            var jdbc=new JdbcTemplate(source);
            jdbc.execute("DELETE FROM gateway_user_kyc_profiles");
            jdbc.execute("DELETE FROM gateway_user_kyc_documents");
        }
        @PostMapping("/__qa/expire") void expire(@RequestHeader("Authorization") String token) {
            auth.requireAdminPermission(token,"admin.compliance.write");
            profiles.updateExpiry(42,Instant.now().minusSeconds(1));
        }
    }

    @Test @Timeout(7200) void serveOwnedKycAcceptanceUntilDone() throws Exception {
        Path output=Path.of(System.getenv("KYC_QA_DIR")).toRealPath();
        if(!output.getFileName().toString().startsWith("kyc-closure.")) throw new IllegalArgumentException("owned QA directory required");
        String url=System.getenv("LOGIN_TEST_JDBC_URL"),user=System.getenv("LOGIN_TEST_DB_USER");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,user,""));
        String schema="kyc_closure_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE SCHEMA "+schema);
        source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,"");
        var jdbc=new JdbcTemplate(source);var json=new ObjectMapper();
        var tomcat=new Tomcat();var web=new AnnotationConfigWebApplicationContext();
        try {
            jdbc.execute("CREATE TABLE gateway_users(user_id BIGINT PRIMARY KEY,username TEXT,status TEXT,updated_at TIMESTAMPTZ)");
            jdbc.execute("INSERT INTO gateway_users VALUES(7,'qa-admin','NORMAL',now()),(42,'kycqa','NORMAL',now())");
            String init=Files.readString(Path.of("../init.sql"));
            for(String table:List.of("gateway_user_kyc_profiles","gateway_user_kyc_documents","gateway_kyc_provider_events","gateway_user_risk_tags","gateway_user_aml_cases")) {
                int start=init.indexOf("CREATE TABLE IF NOT EXISTS "+table+" (");
                jdbc.execute(init.substring(start,init.indexOf("\n);",start)+3));
            }
            properties=new GatewayProperties();properties.getKyc().setProvider("SELF");properties.getKyc().setSimulationEnabled(false);
            properties.getKycDocuments().setEnabled(true);properties.getKycDocuments().setType("filesystem");
            properties.getKycDocuments().setRootPath(output.resolve("synthetic-documents").toString());
            auth=mock(AuthService.class);
            var principal=new JwtPrincipal(42,"kycqa","NORMAL",List.of("USER"),Instant.now().plusSeconds(7200));
            when(auth.authenticateBearer(anyString())).thenAnswer(call->{if(!"Bearer kyc-qa-user".equals(call.getArgument(0)))throw new IllegalArgumentException("invalid QA identity");return principal;});
            var administrator=new JwtPrincipal(7,"qa-admin","NORMAL",List.of("ADMIN"),Instant.now().plusSeconds(7200));
            when(auth.requireAdminPermission(anyString(),anyString())).thenAnswer(call->{if(!"Bearer kyc-qa-admin".equals(call.getArgument(0)))throw new IllegalArgumentException("invalid QA administrator");return administrator;});
            var account=new AuthenticatedUser(42,"kycqa",null,"NORMAL",List.of("USER"),Instant.now());
            when(auth.adminUser("Bearer kyc-qa-admin",42)).thenReturn(account);
            var approvals=mock(AdminApprovalService.class);
            when(approvals.approvalHeaderName()).thenReturn("X-Admin-Approval-Id");
            when(approvals.requireWrite(anyString(),anyString(),anyString(),any(),any())).thenAnswer(call->{auth.requireAdminPermission(call.getArgument(0),"admin.compliance.write");return administrator;});
            var countries=mock(CountryRepository.class);
            when(countries.supported("SG")).thenReturn(true);
            when(countries.enabled()).thenReturn(List.of(new CountryRepository.Country("SG","🇸🇬",Map.of("en","Singapore","zh","新加坡"))));
            profiles=new ComplianceKycRepository(jdbc);
            documents=new KycDocumentService(new KycDocumentRepository(jdbc),new KycDocumentStorageService(properties),properties,json,
                    mock(GatewayRequestLimits.class),mock(UploadContentScanner.class));
            var providers=new KycProviderRegistry(List.of(new ManualReviewKycProvider()),properties);
            target=new ComplianceService(new ComplianceUserProjectionRepository(jdbc),profiles,new KycProviderEventRepository(jdbc),
                    new ComplianceRiskTagRepository(jdbc),new ComplianceAmlCaseRepository(jdbc),auth,approvals,
                    documents,mock(),mock(),mock(),providers,countries);
            Path runtime=Files.createTempDirectory(output,"tomcat-");
            tomcat.setBaseDir(runtime.toString());tomcat.setPort(19086);tomcat.getConnector().setProperty("address","127.0.0.1");
            var context=tomcat.addContext("",runtime.toString());web.register(Web.class);
            var servlet=Tomcat.addServlet(context,"gateway",new DispatcherServlet(web));
            servlet.setLoadOnStartup(1);servlet.setMultipartConfigElement(new jakarta.servlet.MultipartConfigElement(runtime.toString(),16_000_000,20_000_000,0));
            context.addServletMappingDecoded("/","gateway");tomcat.start();
            Files.writeString(output.resolve("ready.json"),json.writeValueAsString(Map.of("port",19086,"userId",42,"schema",schema)));
            long deadline=System.nanoTime()+7000_000_000_000L;
            while(!Files.exists(output.resolve("qa-done"))&&System.nanoTime()<deadline)Thread.sleep(100);
        } finally {
            tomcat.stop();tomcat.destroy();web.close();admin.execute("DROP SCHEMA "+schema+" CASCADE");
        }
    }
}
