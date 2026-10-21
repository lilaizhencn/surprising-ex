package com.surprising.gateway.c2c;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.surprising.account.api.model.BalanceQueryResponse;
import com.surprising.account.provider.service.AccountCommandGateway;
import com.surprising.account.provider.service.AccountRequestService;
import com.surprising.gateway.c2c.repository.*;
import com.surprising.gateway.c2c.service.C2cAdminQueryService;
import com.surprising.gateway.c2c.service.C2cMarketService;
import com.surprising.gateway.provider.auth.*;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
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
    final AccountRequestService accountQueries = mock(AccountRequestService.class);
    final AccountCommandGateway accountCommands = mock(AccountCommandGateway.class);
    final ProductAccountClient core = mock(ProductAccountClient.class);
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
            var applied=new ProductAccountAdjustment(ProductAccountAdjustment.Status.APPLIED,null);
            when(core.transferOut(any(),any())).thenReturn(applied);
            when(core.transferIn(any(),any())).thenReturn(applied);
            when(core.completeTransfer(any(),any())).thenReturn(applied);
            when(products.enabled()).thenReturn(List.of(ProductLine.SPOT));
            when(products.service(ProductLine.SPOT,AccountRequestService.class)).thenReturn(accountQueries);
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
            context.registerBean(C2cOrdersRepository.class,()->new C2cOrdersRepository(jdbc,json));
            context.registerBean(C2cSubmissions.class,()->new C2cSubmissions(jdbc));
            context.registerBean(C2cFundsRepository.class,()->new C2cFundsRepository(jdbc));
            context.registerBean(C2cPaymentAccounts.class,()->C2cDatabaseFixture.payments(jdbc,new TotpService(properties),json,compliance));
            context.registerBean(C2cAccess.class,()->new C2cAccess(auth,compliance,security,new C2cProfileRepository(jdbc),new C2cSecurityGrantRepository(jdbc),new GatewayUserRepository(jdbc),db.transactions.getTransactionManager()));
            context.registerBean(C2cAds.class,()->new C2cAds(bean(C2cAdsRepository.class),bean(C2cMarketService.class),bean(C2cPaymentAccounts.class),
                    bean(C2cAccess.class),bean(C2cFundsRepository.class),jdbc,json,bean(C2cSubmissions.class)));
            context.registerBean(C2cOrders.class,()->new C2cOrders(bean(C2cOrdersRepository.class),bean(C2cAdsRepository.class),bean(C2cMarketService.class),
                    bean(C2cPaymentAccounts.class),bean(C2cAccess.class),bean(C2cFundsRepository.class),jdbc,bean(C2cSubmissions.class)));
            context.registerBean(C2cFunds.class,()->new C2cFunds(bean(C2cFundsRepository.class),core,bean(C2cMarketService.class)));
            context.registerBean(C2cQueries.class,()->new C2cQueries(jdbc,bean(C2cAdsRepository.class),bean(C2cOrdersRepository.class),
                    bean(C2cMarketService.class),bean(C2cPaymentAccounts.class),json));
            context.registerBean(C2cProfiles.class,()->new C2cProfiles(jdbc,bean(C2cAccess.class),compliance,new GatewayUserRepository(jdbc),
                    bean(C2cOrdersRepository.class),bean(C2cMarketService.class),json));
            context.registerBean(AdminAuditRepository.class,()->new AdminAuditRepository(jdbc));
            context.registerBean(AdminApprovalRepository.class,()->new AdminApprovalRepository(jdbc));
            context.registerBean(AdminApprovalService.class,()->new AdminApprovalService(auth,bean(AdminAuditRepository.class),bean(AdminApprovalRepository.class),properties));
            context.registerBean(C2cAdminAccess.class,()->new C2cAdminAccess(auth,bean(AdminApprovalService.class),bean(AdminApprovalRepository.class),bean(AdminAuditRepository.class),new GatewayUserRepository(jdbc)));
            context.registerBean(C2cAdminMarkets.class,()->new C2cAdminMarkets(jdbc,bean(C2cMarketService.class),bean(C2cQueries.class),bean(C2cAdminAccess.class),products));
            context.registerBean(C2cMerchantBonds.class,()->new C2cMerchantBonds(jdbc,bean(C2cFundsRepository.class),bean(C2cMarketService.class),bean(C2cAccess.class),bean(C2cAdminAccess.class),products));
            context.registerBean(C2cAdminInitialization.class,()->new C2cAdminInitialization(bean(C2cAdminAccess.class),bean(C2cMarketService.class),products,db.transactions.getTransactionManager()));
            context.registerBean(C2cMerchantRepository.class,()->new C2cMerchantRepository(jdbc));
            context.registerBean(C2cPaymentAccountRepository.class,()->new C2cPaymentAccountRepository(jdbc));
            context.registerBean(C2cAppealRepository.class,()->new C2cAppealRepository(jdbc));
            context.registerBean(C2cReportRepository.class,()->new C2cReportRepository(jdbc));
            context.registerBean(C2cMarketRepository.class,()->new C2cMarketRepository(jdbc));
            context.registerBean(C2cAdminQueryService.class,()->new C2cAdminQueryService(bean(C2cMerchantRepository.class),
                    bean(C2cPaymentAccountRepository.class),bean(C2cAdsRepository.class),bean(C2cOrdersRepository.class),
                    bean(C2cFundsRepository.class),bean(C2cAppealRepository.class),bean(C2cReportRepository.class),bean(C2cMarketRepository.class)));
            context.registerBean(C2cAdminReview.class,()->new C2cAdminReview(bean(C2cAdminAccess.class),bean(C2cMerchantRepository.class),bean(C2cAppealRepository.class),
                    bean(C2cPaymentAccountRepository.class),bean(C2cReportRepository.class),bean(C2cMarketService.class),bean(C2cAdsRepository.class),bean(C2cAds.class),
                    bean(C2cOrdersRepository.class),bean(C2cOrders.class),bean(C2cPaymentAccounts.class),bean(C2cQueries.class),compliance,new GatewayUserRepository(jdbc)));
            context.registerBean(C2cConversations.class,()->new C2cConversations(jdbc,bean(C2cOrdersRepository.class)));
            context.registerBean(C2cAttachments.class,()->new C2cAttachments(jdbc,bean(C2cConversations.class),bean(C2cOrdersRepository.class),
                    bean(C2cPaymentAccounts.class),new C2cR2Storage(r2),db.transactions.getTransactionManager()));
            context.registerBean(C2cMessages.class,()->new C2cMessages(jdbc,bean(C2cConversations.class),bean(C2cAttachments.class),json));
            context.registerBean(C2cAdminChat.class,()->new C2cAdminChat(bean(C2cAdminAccess.class),bean(C2cOrdersRepository.class),bean(C2cMessages.class),bean(C2cAttachments.class)));
            context.refresh();
            makerAccount=bean(C2cPaymentAccounts.class).save(11,null,0,"CNY","ALIPAY",new C2cPaymentAccounts.Details("QA Maker","qa-maker-account","",null)).id();
            takerAccount=bean(C2cPaymentAccounts.class).save(12,null,0,"CNY","ALIPAY",new C2cPaymentAccounts.Details("QA Taker","qa-taker-account","",null)).id();
            jdbc.update("UPDATE c2c_payment_accounts SET status='APPROVED'");
            var controller=new C2cController(bean(C2cAccess.class),bean(C2cMarketService.class),bean(C2cAds.class),bean(C2cAdsRepository.class),
                    bean(C2cOrders.class),bean(C2cOrdersRepository.class),bean(C2cSubmissions.class),bean(C2cQueries.class),
                    bean(C2cPaymentAccounts.class),products,json);
            mvc=MockMvcBuilders.standaloneSetup(controller,new C2cMerchantController(bean(C2cMerchantBonds.class),bean(C2cAccess.class),bean(C2cAdminAccess.class),json),new C2cStatusController(true,bean(C2cMarketService.class),products),
                    new C2cMeController(bean(C2cAccess.class),bean(C2cProfiles.class)),
                    new C2cChatController(bean(C2cAccess.class),bean(C2cConversations.class),bean(C2cMessages.class),bean(C2cAttachments.class)),
                    new C2cAdminChatController(bean(C2cAdminAccess.class),bean(C2cAdminChat.class),json),
                    new C2cAdminController(bean(C2cAdminAccess.class),bean(C2cAdminMarkets.class),bean(C2cAdminReview.class),bean(C2cAdminQueryService.class),bean(C2cAdminInitialization.class),json))
                    .setControllerAdvice(new C2cHttpErrors()).build();
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
    C2cAds.Publish payload(String side) {
        return new C2cAds.Publish(marketId,side,"6.65","10","1","10000",List.of("ALIPAY"),
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
        new C2cSettlement(bean(C2cFundsRepository.class),bean(C2cFunds.class),bean(C2cAdsRepository.class),bean(C2cAds.class),
                bean(C2cOrdersRepository.class),bean(C2cOrders.class),bean(C2cSubmissions.class),products).settle();
    }
    @Override public void close() {
        context.close();if(db!=null)db.close();
        try(var paths=java.nio.file.Files.walk(attachmentRoot)) {
            for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path);
        } catch(java.io.IOException failure) {throw new java.io.UncheckedIOException(failure);}
    }
}
