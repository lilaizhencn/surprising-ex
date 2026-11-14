package com.surprising.gateway.provider.config;

import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.GatewayApiKeyService;
import com.surprising.gateway.provider.interceptor.*;
import com.surprising.gateway.provider.product.GatewayProductSelection;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.*;
import org.springframework.security.config.annotation.web.configuration.*;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.firewall.*;
import org.springframework.security.web.csrf.*;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class GatewaySecurityConfiguration {
    @Bean
    public WebSecurityCustomizer gatewayFirewall() {
        var firewall = new StrictHttpFirewall() {
            @Override public FirewalledRequest getFirewalledRequest(jakarta.servlet.http.HttpServletRequest request) {
                // Current route segments are ASCII identifiers. Decode neither signed paths nor permission paths.
                if (request.getRequestURI().contains("%")) throw new RequestRejectedException("encoded route path");
                return super.getFirewalledRequest(request);
            }
        };
        firewall.setAllowedHttpMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        return web -> web.httpFirewall(firewall).requestRejectedHandler((request, response, error) ->
                GatewaySecurityErrors.write(response, 400, "INVALID_REQUEST_PATH"));
    }

    @Bean
    public SecurityFilterChain gatewaySecurity(HttpSecurity http, AuthService auth, GatewayProperties properties,
            AdminIpWhitelistFilter addresses, AdminPermissionFilter permissions,
            ObjectProvider<AuthService> authProvider,
            ObjectProvider<GatewayApiKeyService> apiKeyProvider,
            ObjectProvider<AdminApprovalService> approvalProvider,
            ObjectProvider<GatewayProductSelection> productSelectionProvider,
            @Qualifier("gatewayCorsSource") CorsConfigurationSource cors) throws Exception {
        var directAccess = new GatewayDirectAccessFilter(
                properties, authProvider, apiKeyProvider, approvalProvider, productSelectionProvider);
        var csrf = new com.surprising.gateway.provider.auth.controller.BrowserCsrfTokens(properties);
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.requireExplicitSave(true))
                .requestCache(cache -> cache.disable()).formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
                .cors(config -> config.configurationSource(cors))
                .csrf(config -> config.csrfTokenRepository(csrf)
                        .sessionAuthenticationStrategy(new org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(GatewaySecurityConfiguration::requiresCsrf))
                .headers(headers -> headers
                        .referrerPolicy(policy -> policy.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicyHeader(policy -> policy.policy("camera=(), microphone=(), geolocation=()")))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> GatewaySecurityErrors.write(response, 401, "UNAUTHENTICATED"))
                        .accessDeniedHandler((request, response, error) -> GatewaySecurityErrors.write(response, 403, "ACCESS_DENIED")))
                .addFilterBefore(new GatewayBearerFilter(auth, properties), AnonymousAuthenticationFilter.class)
                .addFilterBefore(addresses, GatewayBearerFilter.class)
                .addFilterBefore(permissions, AuthorizationFilter.class)
                .addFilterAfter(directAccess, AuthorizationFilter.class)
                .authorizeHttpRequests(access -> access
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/actuator/**", "/internal/**", "/api/v1/accounts/**", "/api/v1/trading/**")
                            .access((authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(
                                    List.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(context.getRequest().getRemoteAddr())))
                        .requestMatchers("/api/v1/admin/**").hasAnyRole(properties.getSecurity().getAdminRoles().toArray(String[]::new))
                        .requestMatchers(HttpMethod.HEAD,"/api/v1/content/media/{mediaId}").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf", "/api/v1/auth/google/config", "/api/v1/auth/telegram/config", "/api/v1/auth/telegram/callback",
                                "/api/v1/time", "/api/v1/runtime", "/api/v1/help/articles", "/api/v1/compliance/countries",
                                "/api/v1/legal/current", "/api/v1/legal/documents/{slug}", "/api/v1/legal/revisions/{revisionId}",
                                "/api/v1/c2c/status", "/api/v1/c2c/markets", "/api/v1/c2c/ads", "/api/v1/c2c/ads/{id}",
                                "/api/v1/c2c/merchants/{id}", "/api/v1/c2c/help", "/api/v1/affiliate/preview", "/api/v1/announcements/**", "/api/v1/content/media/{mediaId}").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/google/challenge", "/api/v1/auth/google/login", "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/login/verify",
                                "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password", "/api/v1/auth/logout",
                                "/api/v1/auth/telegram/start", "/api/v1/auth/telegram/complete",
                                "/api/v1/auth/telegram/webapp/login",
                                "/api/v1/telegram/bot/webhook",
                                "/api/v1/compliance/kyc/webhooks/{provider}", "/api/v1/internal/wallet/webhooks/custody").permitAll()
                        .requestMatchers(HttpMethod.GET, "/ws/v1").permitAll()
                        // These adapters validate HMAC and scope against the exact wire body/query, or bearer for private operations.
                        .requestMatchers("/api/v1/gateway/**", "/api/v3/**", "/sapi/v1/**", "/fapi/v1/**", "/dapi/v1/**", "/eapi/v1/**",
                                "/api/v1/price/**", "/api/v1/candlestick/**", "/api/v1/funding/**",
                                "/api/v1/risk/**", "/api/v1/liquidations/**", "/api/v1/insurance/**", "/api/v1/adl/**").permitAll()
                        .requestMatchers("/api/v1/auth/me", "/api/v1/auth/session", "/api/v1/auth/verify-email", "/api/v1/auth/resend-email-verification",
                                "/api/v1/legal/status", "/api/v1/legal/consents",
                                "/api/v1/security/**", "/api/v1/users/scan", "/api/v1/notifications/**", "/api/v1/wallet/**",
                                "/api/v1/realtime/**", "/api/v1/portfolio/**", "/api/v1/affiliate/**", "/api/v1/compliance/**", "/api/v1/c2c/**", "/api/v1/announcements/**").authenticated()
                        .anyRequest().denyAll());
        return http.build();
    }

    /**
     * 浏览器 Cookie 登录的写请求必须带 X-CSRF-Token（与 CSRF Cookie 一致）。
     * 用 Authorization 头或 API Key 认证的请求（App、脚本、做市商）不受影响。
     */
    static boolean requiresCsrf(jakarta.servlet.http.HttpServletRequest request) {
        if (List.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) return false;
        if (request.getHeader("Authorization") != null || request.getHeader("X-API-Key") != null
                || request.getHeader("X-MBX-APIKEY") != null) return false;
        if (com.surprising.gateway.provider.auth.controller.BrowserSessionCookies.client(request) == null) return false;
        return com.surprising.gateway.provider.auth.controller.BrowserSessionCookies.sessionToken(request) != null
                || "cookie".equals(request.getHeader("X-Auth-Transport"));
    }

    @Bean public FilterRegistrationBean<AdminIpWhitelistFilter> adminIpRegistration(AdminIpWhitelistFilter filter) {
        var registration = new FilterRegistrationBean<>(filter); registration.setEnabled(false); return registration;
    }
    @Bean public FilterRegistrationBean<AdminPermissionFilter> adminPermissionRegistration(AdminPermissionFilter filter) {
        var registration = new FilterRegistrationBean<>(filter); registration.setEnabled(false); return registration;
    }
}
