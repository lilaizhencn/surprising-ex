package com.surprising.gateway.provider.config;

import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.model.CurrentUser;
import com.surprising.gateway.provider.auth.model.OperatingUser;
import com.surprising.gateway.provider.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

/**
 * 解析控制器方法上的 {@link CurrentUser} 或 {@link OperatingUser} 参数，优先直接读取过滤器已认证的上下文。
 */
public final class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {
    private final ObjectProvider<AuthService> authServiceProvider;
    private final AuthService directAuthService;

    public CurrentUserArgumentResolver(ObjectProvider<AuthService> authServiceProvider) {
        this.authServiceProvider = authServiceProvider;
        this.directAuthService = null;
    }

    public CurrentUserArgumentResolver(AuthService authService) {
        this.authServiceProvider = null;
        this.directAuthService = authService;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        if (!parameter.hasParameterAnnotation(CurrentUser.class)
                && !parameter.hasParameterAnnotation(OperatingUser.class)) {
            return false;
        }
        Class<?> type = parameter.getParameterType();
        return type == long.class || type == Long.class || type == JwtPrincipal.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        JwtPrincipal principal = resolvePrincipal(webRequest.getNativeRequest(HttpServletRequest.class));
        Class<?> type = parameter.getParameterType();
        if (type == JwtPrincipal.class) {
            return principal;
        }
        if (parameter.hasParameterAnnotation(OperatingUser.class)) {
            return principal.operatingUserId();
        }
        return principal.userId();
    }

    private JwtPrincipal resolvePrincipal(HttpServletRequest request) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof JwtPrincipal principal) {
            return principal;
        }
        AuthService authService = directAuthService != null
                ? directAuthService
                : (authServiceProvider == null ? null : authServiceProvider.getIfAvailable());
        if (authService == null || request == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated");
        }
        try {
            JwtPrincipal principal = authService.authenticateBearer(request.getHeader(HttpHeaders.AUTHORIZATION));
            if (principal == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated");
            }
            return principal;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage());
        }
    }
}
