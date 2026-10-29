package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.gateway.provider.auth.controller.*;
import com.surprising.gateway.provider.auth.model.AuthModels.*;
import com.surprising.gateway.provider.auth.service.*;
import com.surprising.gateway.provider.config.*;
import com.surprising.gateway.provider.controller.GatewayLimitRequestBinding;
import com.surprising.gateway.provider.interceptor.*;
import com.surprising.gateway.provider.service.GatewayRequestLimits;
import com.surprising.websocket.provider.config.WebSocketProperties;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

class GoogleLoginHttpTest {
    @TempDir Path directory;
    Tomcat server;
    AnnotationConfigWebApplicationContext context;
    HttpClient client;
    String base;
    @BeforeEach void start() throws Exception {
        server = new Tomcat(); server.setBaseDir(directory.toString()); server.setPort(0);
        server.getConnector().setProperty("address", "127.0.0.1");
        var servlet = server.addContext("", directory.toString());
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(servlet.getServletContext()); context.register(Config.class); context.refresh();
        Tomcat.addServlet(servlet, "mvc", new DispatcherServlet(context)); servlet.addServletMappingDecoded("/", "mvc");
        var definition = new FilterDef(); definition.setFilterName("security"); definition.setFilter(new DelegatingFilterProxy("springSecurityFilterChain", context)); servlet.addFilterDef(definition);
        var mapping = new FilterMap(); mapping.setFilterName("security"); mapping.addURLPattern("/*"); servlet.addFilterMap(mapping);
        server.start(); base = "http://127.0.0.1:" + server.getConnector().getLocalPort();
        client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
    @AfterEach void stop() throws Exception { client.close(); server.stop(); server.destroy(); context.close(); }
    HttpResponse<String> post(String body, boolean browser, String csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/google/login"))
                .header("Content-Type", "application/json").header("X-Device-Id", "a1111111-1111-4111-8111-111111111111");
        if (browser) request.header("X-Auth-Transport", "cookie").header("X-Auth-Client", "web");
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void googleRoutesArePublicAndBrowserLoginRequiresCsrfAndHidesRefreshSecret() throws Exception {
        var config = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/google/config")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(config.statusCode()).isEqualTo(200); assertThat(config.body()).contains("web.apps.googleusercontent.com");
        String body = "{\"credential\":\"signed-token\",\"nonce\":\"" + "n".repeat(43) + "\",\"platform\":\"WEB\"}";
        assertThat(post(body, true, null).statusCode()).isEqualTo(403);
        verifyNoInteractions(context.getBean(GoogleLoginService.class));
        var csrfResponse = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/csrf")).header("X-Auth-Client", "web").GET().build(), HttpResponse.BodyHandlers.ofString());
        String csrf = new ObjectMapper().readTree(csrfResponse.body()).get("token").asText();
        var response = post(body, true, csrf);
        assertThat(response.statusCode()).isEqualTo(200); assertThat(response.body()).doesNotContain("refresh-secret").contains("\"browserSession\":true");
        assertThat(response.headers().firstValue("Set-Cookie").orElseThrow()).contains("surprising-web-refresh=refresh-secret", "HttpOnly", "SameSite=Strict");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        var mobile = post("{\"credential\":\"signed-token\",\"platform\":\"ANDROID\"}", false, null);
        assertThat(mobile.statusCode()).isEqualTo(200); assertThat(mobile.body()).contains("refresh-secret");
    }
    @Test void upstreamGoogleKeyFailureReturnsRetryableServiceError() throws Exception {
        doThrow(new com.surprising.gateway.provider.auth.client.GoogleIdentityClient.Unavailable())
                .when(context.getBean(GoogleLoginService.class)).login(anyString(), any(), anyString(), any(), any());
        var response = post("{\"credential\":\"signed-token\",\"platform\":\"ANDROID\"}", false, null);
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("Google sign-in is temporarily unavailable").doesNotContain("signed-token");
    }
    @Test void invalidInputAndUnknownRoutesRemainDenied() throws Exception {
        assertThat(post("{\"credential\":\"\",\"platform\":\"OTHER\"}", false, null).statusCode()).isEqualTo(400);
        assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/google/unknown")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        verifyNoInteractions(context.getBean(GoogleLoginService.class));
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({GatewaySecurityConfiguration.class, GatewayCorsConfig.class, AdminIpWhitelistFilter.class, AdminPermissionFilter.class,
            GoogleLoginController.class, AuthClientMetadataBinding.class, BrowserSessionController.class, BrowserSessionResponse.class})
    static class Config {
        @Bean GatewayProperties properties() { var p = new GatewayProperties(); p.getSecurity().setBrowserCookieSecure(false); return p; }
        @Bean GoogleLoginProperties googleProperties() { var p = new GoogleLoginProperties(); p.setWebClientId("web.apps.googleusercontent.com"); return p; }
        @Bean WebSocketProperties websocket() { return new WebSocketProperties(); }
        @Bean ObjectMapper json() { return new ObjectMapper(); }
        @Bean GatewayRequestLimits limits() { return mock(GatewayRequestLimits.class); }
        @Bean GatewayLimitRequestBinding requests(ObjectMapper json) { return new GatewayLimitRequestBinding(json); }
        @Bean AuthService auth() { return mock(AuthService.class); }
        @Bean GoogleLoginService login() {
            var service = mock(GoogleLoginService.class);
            when(service.login(anyString(), any(), anyString(), any(), any())).thenReturn(new AuthResponse(new AuthenticatedUser(7, null, null, "NORMAL", List.of("USER"), Instant.now()),
                    "access", "refresh-secret", Instant.now().plusSeconds(300), Instant.now().plusSeconds(3600)));
            return service;
        }
    }
}
