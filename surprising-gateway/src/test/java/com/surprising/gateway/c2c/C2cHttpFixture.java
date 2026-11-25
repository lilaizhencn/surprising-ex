package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.model.C2cPaymentAccountInput;
import com.surprising.gateway.c2c.utils.C2cPaymentFormats;

import com.surprising.gateway.provider.auth.model.ComplianceModels;
import com.surprising.gateway.provider.auth.repository.AdminApprovalRepository;
import com.surprising.gateway.provider.auth.repository.AdminAuditRepository;
import com.surprising.gateway.provider.auth.repository.GatewayUserRepository;
import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.ComplianceService;
import com.surprising.gateway.provider.auth.service.SensitiveActionVerificationService;
import com.surprising.gateway.provider.auth.service.TotpService;

import com.surprising.gateway.c2c.controller.C2cAdminChatController;
import com.surprising.gateway.c2c.controller.C2cAdminController;
import com.surprising.gateway.c2c.controller.C2cChatController;
import com.surprising.gateway.c2c.controller.C2cController;
import com.surprising.gateway.c2c.controller.C2cMeController;
import com.surprising.gateway.c2c.controller.C2cMerchantController;
import com.surprising.gateway.c2c.controller.C2cStatusController;
import com.surprising.gateway.provider.controller.advice.GlobalGatewayExceptionHandler;
import com.surprising.gateway.c2c.model.C2cVerification;
import com.surprising.gateway.c2c.repository.C2cAdsRepository;
import com.surprising.gateway.c2c.repository.C2cFundsRepository;
import com.surprising.gateway.c2c.repository.C2cOrdersRepository;
import com.surprising.gateway.c2c.repository.C2cSubmissions;
import com.surprising.gateway.c2c.service.C2cAccessService;
import com.surprising.gateway.c2c.service.C2cAdminAccessService;
import com.surprising.gateway.c2c.service.C2cAdminChatService;
import com.surprising.gateway.c2c.service.C2cAdminInitializationService;
import com.surprising.gateway.c2c.service.C2cAdminMarketService;
import com.surprising.gateway.c2c.service.C2cAdminReviewService;
import com.surprising.gateway.c2c.service.C2cAdService;
import com.surprising.gateway.c2c.service.C2cAttachmentService;
import com.surprising.gateway.c2c.service.C2cConversationService;
import com.surprising.gateway.c2c.service.C2cFundService;
import com.surprising.gateway.c2c.service.C2cMerchantBondService;
import com.surprising.gateway.c2c.service.C2cMessageService;
import com.surprising.gateway.c2c.service.C2cOrderService;
import com.surprising.gateway.c2c.service.C2cPaymentAccountService;
import com.surprising.gateway.c2c.service.C2cProfileService;
import com.surprising.gateway.c2c.storage.C2cR2Storage;

import com.surprising.gateway.c2c.service.C2cSettlementService;

import com.surprising.gateway.c2c.config.C2cR2Properties;

import com.surprising.gateway.c2c.service.C2cQueryService;
import com.surprising.gateway.c2c.service.C2cSubmissionService;
import com.surprising.gateway.c2c.service.C2cQuickQuoteService;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.surprising.account.api.model.BalanceQueryResponse;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.account.provider.service.AccountService;
import com.surprising.gateway.c2c.repository.*;
import com.surprising.gateway.c2c.service.C2cAdminQueryService;
import com.surprising.gateway.c2c.service.C2cNotificationService;
import com.surprising.gateway.c2c.service.C2cMarketService;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.gateway.provider.product.GatewayProductServices;
import com.surprising.gateway.provider.service.*;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;

/** 真实 PostgreSQL、真实 Spring 事务代理和 HTTP 编解码；只有外部身份及 Core 连接受控。 */
final class C2cHttpFixture implements AutoCloseable {
    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement(proxyTargetClass=true)
    static class Transactions { }

    final C2cDatabaseFixture db;
    final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    final ObjectMapper json = new ObjectMapper();
    final GatewayProperties properties = new GatewayProperties();
    final AuthService auth = mock(AuthService.class);
    final ComplianceService compliance = mock(ComplianceService.class);
    final SensitiveActionVerificationService security = mock(SensitiveActionVerificationService.class);
    final AccountService accountQueries = mock(AccountService.class);
    final AccountCommandGateway accountCommands = mock(AccountCommandGateway.class);
    final AccountCommandGateway core = accountCommands;
    final GatewayProductServices products = mock(GatewayProductServices.class);
    final C2cR2Properties r2 = new C2cR2Properties();
    final java.nio.file.Path attachmentRoot = java.nio.file.Files.createTempDirectory("surprising-c2c-chat-test-");
    final MockMvc mvc;
    final UUID marketId = UUID.randomUUID();
    final UUID makerAccount;
    final UUID takerAccount;

    C2cHttpFixture() throws Exception {
        db = new C2cDatabaseFixture();
        try {
            var jdbc = db.jdbc;
            jdbc.update("INSERT INTO gateway_users(user_id,username) VALUES(9,'qa_admin'),(10,'qa_reviewer'),(11,'qa_maker'),(12,'qa_buyer'),(13,'qa_other')");
            jdbc.update("INSERT INTO assets(asset,scale_units,listed,trading_enabled) VALUES('USDT',100000000,true,true)");
            jdbc.update("UPDATE c2c_configuration SET enabled=true");
            jdbc.update("""
                    INSERT INTO c2c_markets(market_id,asset,fiat,fiat_decimals,enabled,min_fiat,max_fiat,min_price,max_price,buyer_fee_ppm)
                    VALUES(?,'USDT','CNY',2,true,1,100000,1,100,1000)
                    """,marketId);
            jdbc.update("UPDATE c2c_payment_methods SET enabled=true,currencies=ARRAY['CNY'] WHERE code IN ('ALIPAY','BANK_TRANSFER')");
            jdbc.update("INSERT INTO c2c_merchants(user_id,status,application_text) VALUES(11,'APPROVED','Synthetic merchant application')");
            db.seedMerchantBond(11);
            jdbc.update("INSERT INTO c2c_profiles(user_id,nickname) VALUES(11,'QA Maker'),(12,'QA Buyer'),(13,'QA Other')");
            jdbc.update("INSERT INTO gateway_user_kyc_profiles(user_id,kyc_level,status,country) VALUES(11,'STANDARD','VERIFIED','CN'),(12,'STANDARD','VERIFIED','CN'),(13,'STANDARD','VERIFIED','CN')");
            jdbc.execute("CREATE TABLE c2c_otp_attempts(attempts INTEGER NOT NULL)");
            jdbc.update("INSERT INTO c2c_otp_attempts VALUES(0)");
            when(auth.authenticateBearer(any())).thenAnswer(call -> {
                String header=call.getArgument(0);
                if(header==null || !header.matches("Bearer qa-(9|10|11|12|13)")) throw new IllegalArgumentException("test invalid session");
                return principal(Long.parseLong(header.substring("Bearer qa-".length())));
            });
            when(auth.authenticateAdminBearer(any())).thenAnswer(call -> {
                JwtPrincipal principal=auth.authenticateBearer(call.getArgument(0));
                if(principal.userId()!=9 && principal.userId()!=10) throw new IllegalStateException("test admin role required");
                return principal;
            });
            when(auth.requireAdminPermission(any(),any())).thenAnswer(call -> {
                JwtPrincipal principal=auth.authenticateBearer(call.getArgument(0));
                if(principal.userId()!=9 && principal.userId()!=10) throw new IllegalStateException("test permission denied");
                return principal;
            });
            when(compliance.kyc(anyLong())).thenAnswer(call -> {
                long userId=call.getArgument(0);
                var rows=jdbc.queryForList("SELECT status,expires_at FROM gateway_user_kyc_profiles WHERE user_id=?",userId);
                if(rows.isEmpty()) return null;
                var row=rows.getFirst();var expires=(java.sql.Timestamp)row.get("expires_at");
                return new ComplianceModels.KycProfile(userId,"STANDARD",(String)row.get("status"),"CN","IDENTITY_CARD","MANUAL",null,
                        null,null,null,expires==null?null:expires.toInstant(),Instant.now(),Instant.now());
            });
            when(security.verify(anyLong(),anyString(),any(),any(),any())).thenAnswer(call -> {
                boolean valid="123456".equals(call.getArgument(2));
                if(!valid) jdbc.update("UPDATE c2c_otp_attempts SET attempts=attempts+1");
                return valid;
            });
            when(products.enabled()).thenReturn(List.of(ProductLine.SPOT));
            when(products.service(ProductLine.SPOT,AccountService.class)).thenReturn(accountQueries);
            when(products.service(ProductLine.SPOT,AccountCommandGateway.class)).thenReturn(accountCommands);
            when(accountQueries.balances(anyLong())).thenReturn(new BalanceQueryResponse(0,List.of()));
            properties.getSecurity().setRequireApprovalForHighRiskAdminWrites(false);
            properties.getSecurity().setMfaSecretEncryptionKey("c2c-synthetic-http-test-key-not-for-deployment");
            r2.setEnabled(true);r2.setType("filesystem");r2.setRootPath(attachmentRoot.toString());
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("c2c-test",Map.of("surprising.c2c.enabled","true")));
            context.register(Transactions.class);
            context.registerBean(PlatformTransactionManager.class,db.transactions::getTransactionManager);
            context.registerBean(C2cMarketService.class,()->C2cDatabaseFixture.markets(jdbc));
            context.registerBean(C2cAdsRepository.class,()->new C2cAdsRepository(jdbc));
            context.registerBean(C2cOrdersRepository.class,()->new C2cOrdersRepository(jdbc));
            context.registerBean(C2cSubmissions.class,()->new C2cSubmissions(jdbc));
            context.registerBean(C2cFundsRepository.class,()->new C2cFundsRepository(jdbc));
            context.registerBean(C2cNotificationService.class,()->new C2cNotificationService(new C2cPriceAlertRepository(jdbc),
                    new C2cNotificationRepository(jdbc,json),new C2cSecurityGrantRepository(jdbc),new C2cQuoteRepository(jdbc)));
            context.registerBean(C2cPaymentAccountService.class,()->C2cDatabaseFixture.payments(jdbc,new TotpService(properties),json,compliance,bean(C2cAccessService.class)));
            context.registerBean(C2cAccessService.class,()->new C2cAccessService(auth,compliance,security,new C2cProfileRepository(jdbc),new C2cSecurityGrantRepository(jdbc),new GatewayUserRepository(jdbc),db.transactions.getTransactionManager(),bean(C2cMarketService.class),products));
            context.registerBean(C2cAdService.class,()->C2cDatabaseFixture.ads(jdbc,bean(C2cAdsRepository.class),bean(C2cMarketService.class),bean(C2cPaymentAccountService.class),
                    bean(C2cAccessService.class),bean(C2cFundsRepository.class),bean(C2cSubmissions.class)));
            context.registerBean(C2cOrderService.class,()->C2cDatabaseFixture.orders(jdbc,bean(C2cOrdersRepository.class),bean(C2cAdsRepository.class),bean(C2cMarketService.class),
                    bean(C2cPaymentAccountService.class),bean(C2cAccessService.class),bean(C2cFundsRepository.class),bean(C2cSubmissions.class),json));
            context.registerBean(C2cFundService.class,()->new C2cFundService(bean(C2cFundsRepository.class),products,bean(C2cMarketService.class)));
            context.registerBean(C2cQueryService.class,()->C2cDatabaseFixture.queries(jdbc,bean(C2cAdsRepository.class),bean(C2cOrdersRepository.class),
                    bean(C2cMarketService.class),bean(C2cPaymentAccountService.class),json,bean(C2cSubmissions.class)));
            context.registerBean(C2cSubmissionService.class,()->new C2cSubmissionService(bean(C2cSubmissions.class),
                    bean(C2cAdService.class),bean(C2cOrderService.class),bean(C2cAccessService.class),bean(C2cQueryService.class)));
            context.registerBean(C2cQuickQuoteService.class,()->new C2cQuickQuoteService(bean(C2cQueryService.class),
                    bean(C2cMarketService.class),bean(C2cAccessService.class),bean(C2cOrderService.class)));
            context.registerBean(C2cProfileService.class,()->C2cDatabaseFixture.profiles(jdbc,bean(C2cAccessService.class),compliance,new GatewayUserRepository(jdbc),
                    bean(C2cOrdersRepository.class),bean(C2cMarketService.class),json));
            context.registerBean(AdminAuditRepository.class,()->new AdminAuditRepository(jdbc));
            context.registerBean(AdminApprovalRepository.class,()->new AdminApprovalRepository(jdbc));
            context.registerBean(AdminApprovalService.class,()->new AdminApprovalService(auth,bean(AdminAuditRepository.class),bean(AdminApprovalRepository.class),properties));
            context.registerBean(C2cAdminAccessService.class,()->new C2cAdminAccessService(auth,bean(AdminApprovalService.class),bean(AdminAuditRepository.class),new GatewayUserRepository(jdbc)));
            context.registerBean(C2cAdminMarketService.class,()->C2cDatabaseFixture.adminMarkets(jdbc,bean(C2cMarketService.class),bean(C2cQueryService.class),bean(C2cAdminAccessService.class),products));
            context.registerBean(C2cMerchantBondService.class,()->C2cDatabaseFixture.bonds(jdbc,bean(C2cFundsRepository.class),bean(C2cMarketService.class),bean(C2cAccessService.class),bean(C2cAdminAccessService.class),products));
            context.registerBean(C2cAdminInitializationService.class,()->new C2cAdminInitializationService(bean(C2cAdminAccessService.class),bean(C2cMarketService.class),products,db.transactions.getTransactionManager()));
            context.registerBean(C2cMerchantRepository.class,()->new C2cMerchantRepository(jdbc));
            context.registerBean(C2cPaymentAccountRepository.class,()->new C2cPaymentAccountRepository(jdbc));
            context.registerBean(C2cAppealRepository.class,()->new C2cAppealRepository(jdbc));
            context.registerBean(C2cReportRepository.class,()->new C2cReportRepository(jdbc));
            context.registerBean(C2cMarketRepository.class,()->new C2cMarketRepository(jdbc));
            context.registerBean(C2cAdminQueryService.class,()->new C2cAdminQueryService(bean(C2cMerchantRepository.class),
                    bean(C2cPaymentAccountRepository.class),bean(C2cAdsRepository.class),bean(C2cOrdersRepository.class),
                    bean(C2cFundsRepository.class),bean(C2cAppealRepository.class),bean(C2cReportRepository.class),bean(C2cMarketRepository.class)));
            context.registerBean(C2cAdminReviewService.class,()->new C2cAdminReviewService(bean(C2cAdminAccessService.class),bean(C2cMerchantRepository.class),bean(C2cAppealRepository.class),
                    bean(C2cPaymentAccountRepository.class),bean(C2cReportRepository.class),bean(C2cMarketService.class),bean(C2cAdsRepository.class),bean(C2cAdService.class),
                    bean(C2cOrdersRepository.class),bean(C2cOrderService.class),bean(C2cPaymentAccountService.class),bean(C2cQueryService.class),compliance,new GatewayUserRepository(jdbc)));
            context.registerBean(C2cConversationService.class,()->C2cDatabaseFixture.conversations(jdbc,bean(C2cOrdersRepository.class)));
            context.registerBean(C2cAttachmentService.class,()->C2cDatabaseFixture.attachments(jdbc,bean(C2cConversationService.class),bean(C2cOrdersRepository.class),
                    bean(C2cPaymentAccountService.class),new C2cR2Storage(r2),db.transactions.getTransactionManager()));
            context.registerBean(C2cMessageService.class,()->C2cDatabaseFixture.messages(jdbc,bean(C2cConversationService.class),bean(C2cAttachmentService.class),json));
            context.registerBean(C2cAdminChatService.class,()->new C2cAdminChatService(bean(C2cAdminAccessService.class),bean(C2cOrdersRepository.class),bean(C2cMessageService.class),bean(C2cAttachmentService.class)));
            context.refresh();
            makerAccount=bean(C2cPaymentAccountService.class).save(11,null,0,"CNY","ALIPAY",C2cPaymentFormats.input(new C2cPaymentAccountInput.Details("QA Maker","qa-maker-account","",null))).id();
            takerAccount=bean(C2cPaymentAccountService.class).save(12,null,0,"CNY","ALIPAY",C2cPaymentFormats.input(new C2cPaymentAccountInput.Details("QA Taker","qa-taker-account","",null))).id();
            jdbc.update("UPDATE c2c_payment_accounts SET status='APPROVED'");
            var controller=new C2cController(bean(C2cAccessService.class),bean(C2cAdService.class),bean(C2cOrderService.class),bean(C2cSubmissionService.class),
                    bean(C2cQueryService.class),bean(C2cQuickQuoteService.class),bean(C2cPaymentAccountService.class),json);
            mvc=MockMvcBuilders.standaloneSetup(controller,new C2cMerchantController(bean(C2cMerchantBondService.class),bean(C2cAccessService.class),bean(C2cAdminAccessService.class),json, new com.surprising.gateway.c2c.controller.C2cAdminRequestBinding(bean(com.surprising.gateway.provider.auth.service.AdminApprovalService.class))),new C2cStatusController(true,bean(C2cMarketService.class),products),
                    new C2cMeController(bean(C2cAccessService.class),bean(C2cProfileService.class)),
                    new C2cChatController(bean(C2cAccessService.class),bean(C2cConversationService.class),bean(C2cMessageService.class),bean(C2cAttachmentService.class)),
                    new C2cAdminChatController(bean(C2cAdminAccessService.class),bean(C2cAdminChatService.class),json, new com.surprising.gateway.c2c.controller.C2cAdminRequestBinding(bean(com.surprising.gateway.provider.auth.service.AdminApprovalService.class))),
                    new C2cAdminController(bean(C2cAdminAccessService.class),bean(C2cAdminMarketService.class),bean(C2cAdminReviewService.class),bean(C2cAdminQueryService.class),bean(C2cAdminInitializationService.class),json, new com.surprising.gateway.c2c.controller.C2cAdminRequestBinding(bean(com.surprising.gateway.provider.auth.service.AdminApprovalService.class))))
                    .setControllerAdvice(new GlobalGatewayExceptionHandler()).build();
        } catch(Exception failure) {close();throw failure;}
    }
    static JwtPrincipal principal(long id) {
        return new JwtPrincipal(id,"qa_"+id,"NORMAL",id<11?List.of("ADMIN"):List.of("USER"),Instant.now().plusSeconds(600));
    }
    <T> T bean(Class<T> type) {return context.getBean(type);}
    Map<String,Object> call(HttpMethod method,String path,long user,Object body,int expected) throws Exception {
        var result=perform(method,path,user,body);
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                .withFailMessage("HTTP %s %s: %s",method,path,result.getResponse().getContentAsString()).isEqualTo(expected);
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        return json.readValue(result.getResponse().getContentAsString(),Map.class);
    }
    MvcResult perform(HttpMethod method,String path,long user,Object body) throws Exception {
        return perform(method,path,user,body,Map.of());
    }
    MvcResult perform(HttpMethod method,String path,long user,Object body,Map<String,String> headers) throws Exception {
        var request=MockMvcRequestBuilders.request(method,path).contentType(MediaType.APPLICATION_JSON);
        if(user>0) request.header("Authorization","Bearer qa-"+user);
        headers.forEach(request::header);
        if(body!=null) request.content(json.writeValueAsBytes(body));
        return mvc.perform(request).andReturn();
    }
    C2cController.Publish payload(String side) {
        return new C2cController.Publish(marketId,side,"6.65","10","1","10000",List.of("ALIPAY"),
                side.equals("SELL")?List.of(makerAccount):List.of(),900,"Synthetic merchant terms",0);
    }
    UUID publish(String side) throws Exception {
        var response=call(HttpMethod.POST,"/api/v1/c2c/ads",11,
                new C2cController.PublishRequest("qa-ad-"+UUID.randomUUID(),payload(side),new C2cVerification("123456","")),200);
        UUID id=UUID.fromString((String)response.get("resultId"));
        settle();return id;
    }
    UUID quote(UUID adId,long user,String quantity) throws Exception {
        var ad=bean(C2cAdsRepository.class).get(adId,false);
        var response=call(HttpMethod.POST,"/api/v1/c2c/quotes",user,
                new C2cController.QuoteRequest(adId,"CRYPTO",quantity,ad.side().equals("SELL")?makerAccount:takerAccount),200);
        return UUID.fromString((String)response.get("quoteId"));
    }
    Map<String,Object> create(UUID quote,long user,String key,String code,int status) throws Exception {
        return call(HttpMethod.POST,"/api/v1/c2c/orders",user,new C2cController.OrderRequest(key,quote,new C2cVerification(code,"")),status);
    }
    void settle() {
        new C2cSettlementService(bean(C2cFundsRepository.class),bean(C2cFundService.class),bean(C2cAdsRepository.class),bean(C2cAdService.class),
                bean(C2cOrdersRepository.class),bean(C2cOrderService.class),bean(C2cSubmissions.class),products).settle();
    }
    @Override public void close() {
        context.close();if(db!=null)db.close();
        try(var paths=java.nio.file.Files.walk(attachmentRoot)) {
            for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path);
        } catch(java.io.IOException failure) {throw new java.io.UncheckedIOException(failure);}
    }
}
