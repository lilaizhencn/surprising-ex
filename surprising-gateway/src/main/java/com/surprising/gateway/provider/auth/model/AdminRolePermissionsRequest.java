package com.surprising.gateway.provider.auth.model;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record AdminRolePermissionsRequest(
        List<@NotBlank String> permissions) {
}
