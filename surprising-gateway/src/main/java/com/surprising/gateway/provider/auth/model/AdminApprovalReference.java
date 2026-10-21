package com.surprising.gateway.provider.auth.model;

/** HTTP 绑定结果；格式错误保留为事实，由业务开关决定是否要求审批，不保留原始 Header。 */
public record AdminApprovalReference(Long value, boolean supplied, boolean canonicalPositive) {
}
