package com.surprising.account.api;

public final class AccountApiPaths {

    public static final String ACCOUNT_BASE_PATH = "/api/v1/accounts";
    /** 仅供其他服务启动恢复账户聚合快照使用的内部入口。 */
    public static final String INTERNAL_BASE_PATH = "/internal/v1/accounts";
    public static final String ACCOUNT_ADMIN_BASE_PATH = "/api/v1/accounts/admin";
    public static final String TRANSFER_OUT_PATH = ACCOUNT_ADMIN_BASE_PATH + "/transfers/out";
    public static final String TRANSFER_IN_PATH = ACCOUNT_ADMIN_BASE_PATH + "/transfers/in";
    public static final String TRANSFER_COMPLETE_PATH = ACCOUNT_ADMIN_BASE_PATH + "/transfers/complete";
    public static final String TRANSFER_PENDING_PATH = ACCOUNT_ADMIN_BASE_PATH + "/transfers/pending";

    private AccountApiPaths() {
    }
}
