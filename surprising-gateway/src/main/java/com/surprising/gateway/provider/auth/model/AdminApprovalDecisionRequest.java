package com.surprising.gateway.provider.auth.model;

import jakarta.validation.constraints.Size;

public record AdminApprovalDecisionRequest(
        @Size(max = 1000) String reason) {
}
