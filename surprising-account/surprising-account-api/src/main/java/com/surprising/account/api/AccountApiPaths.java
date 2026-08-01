package com.surprising.account.api;

public final class AccountApiPaths {

    public static final String ACCOUNT_BASE_PATH = "/api/v1/accounts";
    /** 仅供其他服务启动恢复账户聚合快照使用的内部入口。 */
    public static final String INTERNAL_BASE_PATH = "/internal/v1/accounts";
    public static final String ACCOUNT_ADMIN_BASE_PATH = "/api/v1/accounts/admin";

    private AccountApiPaths() {
    }
}
