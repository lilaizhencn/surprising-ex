package com.surprising.account.provider.service;

/** 账户用户 JVM 快照未完成初始化时的失败关闭异常。 */
public class AccountStateUnavailableException extends RuntimeException {

    public AccountStateUnavailableException(String message) {
        super(message);
    }
}
