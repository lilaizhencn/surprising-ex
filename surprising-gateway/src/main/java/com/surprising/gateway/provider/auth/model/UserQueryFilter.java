package com.surprising.gateway.provider.auth.model;

/** 已绑定的用户搜索；exactUserIdText保留原始数字文本（包括前导零）的搜索语义。 */
public record UserQueryFilter(String term, String exactUserIdText, String status) {
}
