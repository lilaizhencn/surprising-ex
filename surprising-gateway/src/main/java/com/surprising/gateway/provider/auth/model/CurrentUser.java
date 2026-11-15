package com.surprising.gateway.provider.auth.model;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式注入当前已认证用户（支持 {@code long}/{@code Long} 用户 ID 或 {@code JwtPrincipal}）。
 * 优先复用过滤器写入 {@code SecurityContextHolder} 的认证结果，避免控制器重复验签与查库。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
