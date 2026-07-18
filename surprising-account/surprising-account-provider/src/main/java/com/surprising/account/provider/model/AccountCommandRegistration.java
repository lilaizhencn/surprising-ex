package com.surprising.account.provider.model;

public enum AccountCommandRegistration {
    READY,
    WAITING_DEPENDENCY,
    DEPENDENCY_REJECTED,
    ALREADY_TERMINAL
}
