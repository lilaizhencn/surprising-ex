package com.surprising.gateway.c2c;

import com.surprising.websocket.provider.handler.ClientWebSocketHandler;

import com.surprising.gateway.c2c.controller.C2cAdminChatController;
import com.surprising.gateway.c2c.controller.C2cAdminController;
import com.surprising.gateway.c2c.controller.C2cChatController;
import com.surprising.gateway.c2c.controller.C2cController;
import com.surprising.gateway.c2c.controller.C2cMeController;
import com.surprising.gateway.c2c.controller.C2cStatusController;
import com.surprising.gateway.provider.controller.advice.GlobalGatewayExceptionHandler;
import com.surprising.gateway.c2c.service.C2cAccessService;
import com.surprising.gateway.c2c.service.C2cAdminAccessService;
import com.surprising.gateway.c2c.service.C2cAdminChatService;
import com.surprising.gateway.c2c.service.C2cAdminInitializationService;
import com.surprising.gateway.c2c.service.C2cAdminMarketService;
import com.surprising.gateway.c2c.service.C2cAdminReviewService;
import com.surprising.gateway.c2c.service.C2cAdService;
import com.surprising.gateway.c2c.service.C2cAttachmentService;
import com.surprising.gateway.c2c.service.C2cConversationService;
import com.surprising.gateway.c2c.service.C2cMessageService;
import com.surprising.gateway.c2c.service.C2cOrderService;
import com.surprising.gateway.c2c.service.C2cPaymentAccountService;
import com.surprising.gateway.c2c.service.C2cProfileService;
import com.surprising.gateway.c2c.websocket.C2cChatRealtime;

import com.surprising.gateway.c2c.service.C2cQueryService;
import com.surprising.gateway.c2c.service.C2cSubmissionService;
import com.surprising.gateway.c2c.service.C2cQuickQuoteService;

import com.surprising.gateway.c2c.service.C2cAdminQueryService;

import com.surprising.gateway.c2c.service.C2cMarketService;
import com.surprising.websocket.provider.config.WebSocketProperties;
import com.surprising.websocket.provider.netty.NettyWebSocketServer;
import com.surprising.websocket.provider.service.*;
import jakarta.servlet.MultipartConfigElement;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** 显式启用的浏览器联调夹具：仅本机、独占 schema、合成身份及受控 Core；不代表资金端到端验收。 */
@EnabledIfEnvironmentVariable(named="C2C_BROWSER_QA_DIR",matches=".+")
class C2cBrowserQaTest {
    private static C2cHttpFixture fixture;
    @Configuration
    @EnableWebMvc
    static class Web {
        @Bean C2cChatController chat() {return new C2cChatController(fixture.bean(C2cAccessService.class),fixture.bean(C2cConversationService.class),fixture.bean(C2cMessageService.class),fixture.bean(C2cAttachmentService.class));}
        @Bean C2cStatusController status() {return new C2cStatusController(true,fixture.bean(C2cMarketService.class),fixture.products);}
        @Bean C2cMeController me() {return new C2cMeController(fixture.bean(C2cAccessService.class),fixture.bean(C2cProfileService.class));}
        @Bean C2cController orders() {return new C2cController(fixture.bean(C2cAccessService.class),fixture.bean(C2cAdService.class),fixture.bean(C2cOrderService.class),fixture.bean(C2cSubmissionService.class),fixture.bean(C2cQueryService.class),fixture.bean(C2cQuickQuoteService.class),fixture.bean(C2cPaymentAccountService.class),fixture.json);}
        @Bean GlobalGatewayExceptionHandler errors() {return new GlobalGatewayExceptionHandler();}
        @Bean C2cAdminChatController adminChat() {return new C2cAdminChatController(fixture.bean(C2cAdminAccessService.class),fixture.bean(C2cAdminChatService.class),fixture.json, new com.surprising.gateway.c2c.controller.C2cAdminRequestBinding(fixture.bean(com.surprising.gateway.provider.auth.service.AdminApprovalService.class)));}
        @Bean C2cAdminController admin() {return new C2cAdminController(fixture.bean(C2cAdminAccessService.class),fixture.bean(C2cAdminMarketService.class),fixture.bean(C2cAdminReviewService.class),fixture.bean(C2cAdminQueryService.class),fixture.bean(C2cAdminInitializationService.class),fixture.json, new com.surprising.gateway.c2c.controller.C2cAdminRequestBinding(fixture.bean(com.surprising.gateway.provider.auth.service.AdminApprovalService.class)));}
        @Bean StandardServletMultipartResolver multipartResolver() {return new StandardServletMultipartResolver();}
        @Bean C2cChatRealtime realtime() {return new C2cChatRealtime(fixture.json,fixture.bean(C2cAccessService.class),fixture.bean(C2cConversationService.class),fixture.bean(C2cMessageService.class),10);}
        @Bean SubscriptionRegistry registry() {return new SubscriptionRegistry(fixture.json,new WebSocketProperties());}
        @Bean ClientWebSocketHandler handler() {return new ClientWebSocketHandler(fixture.json,registry(),new WebSocketProperties(),new WebSocketAuthenticator(fixture.auth, org.mockito.Mockito.mock(com.surprising.gateway.provider.auth.service.GatewayApiKeyService.class)),fixture.products, disabledLimits());}
        @Bean NettyWebSocketServer nettyWebSocketServer() {
            var props = new WebSocketProperties();
            props.getSecurity().setAllowedOrigins(List.of("http://127.0.0.1:17173"));
            props.getNetty().setPort(0);
            return new NettyWebSocketServer(handler(), props, new com.surprising.gateway.provider.config.GatewayProperties());
        }
    }

    @Test @Timeout(900)
    void serveOwnedBrowserFixtureUntilDone() throws Exception {
        Path output=Path.of(System.getenv("C2C_BROWSER_QA_DIR")).toAbsolutePath();
        if(!output.startsWith(Path.of("/tmp")) || !output.getFileName().toString().startsWith("surprising-c2c-web-qa-")) throw new IllegalArgumentException("QA output must be an owned temporary directory");
        Files.createDirectories(output);
        Path runtime=Files.createTempDirectory("surprising-c2c-tomcat-");
        var tomcat=new Tomcat();var web=new AnnotationConfigWebApplicationContext();
        var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        C2cHttpFixture owned=null;
        try {
            owned=new C2cHttpFixture();fixture=owned;
            var ad=fixture.publish("SELL");
            var created=fixture.create(fixture.quote(ad,12,"1"),12,"qa-browser-"+UUID.randomUUID(),"123456",200);
            fixture.settle();
            UUID order=UUID.fromString((String)created.get("resultId"));
            var conversation=fixture.call(HttpMethod.POST,"/api/v1/c2c/conversations",12,Map.of("orderId",order),200);
            String id=(String)conversation.get("conversationId");
            for(int i=0;i<80;i++) fixture.call(HttpMethod.POST,"/api/v1/c2c/conversations/"+id+"/messages",i%2==0?11:12,
                    Map.of("requestId",UUID.randomUUID(),"text",String.format("QA history %02d",i)),200);
            tomcat.setBaseDir(runtime.toString());tomcat.setPort(0);
            tomcat.getConnector().setProperty("address","127.0.0.1");
            var context=tomcat.addContext("",runtime.toString());
            web.register(Web.class);
            var servlet=Tomcat.addServlet(context,"gateway",new DispatcherServlet(web));
            servlet.setLoadOnStartup(1);servlet.setMultipartConfigElement(new MultipartConfigElement(runtime.toString(),10485760,11000000,0));
            context.addServletMappingDecoded("/","gateway");
            tomcat.start();
            var nettyWs=web.getBean(NettyWebSocketServer.class);
            nettyWs.start();
            scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("c2c-browser-qa-");scheduler.initialize();
            scheduler.scheduleWithFixedDelay(web.getBean(C2cChatRealtime.class)::poll,Duration.ofSeconds(1));
            Files.writeString(output.resolve("runtime.json"),fixture.json.writeValueAsString(Map.of("port",tomcat.getConnector().getLocalPort(),"wsPort",nettyWs.getPort(),"orderId",order,"conversationId",id,"fixture","synthetic-auth-controlled-core-real-postgres")));
            long deadline=System.nanoTime()+840_000_000_000L;
            while(!Files.exists(output.resolve("done")) && System.nanoTime()<deadline) Thread.sleep(100);
        } finally {
            scheduler.shutdown();tomcat.stop();tomcat.destroy();web.close();
            if(owned!=null)owned.close();fixture=null;
            try(var paths=Files.walk(runtime)) {for(var path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);}
        }
    }
    private static com.surprising.gateway.provider.service.GatewayRequestLimits disabledLimits() {
        var properties = new com.surprising.gateway.provider.config.GatewayLimitProperties();
        properties.setEnabled(false);
        return new com.surprising.gateway.provider.service.GatewayRequestLimits(null, properties, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

}
