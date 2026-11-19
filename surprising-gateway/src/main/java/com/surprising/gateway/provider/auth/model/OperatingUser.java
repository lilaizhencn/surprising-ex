package com.surprising.gateway.provider.auth.model;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注入当前会话的操作账户（主账号或已切换的子账号）。
 * 登录主体仍用 {@link CurrentUser}；交易、资产查询和实时状态用本注解。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperatingUser {
}
