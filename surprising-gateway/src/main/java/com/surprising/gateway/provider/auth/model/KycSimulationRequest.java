package com.surprising.gateway.provider.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KycSimulationRequest(@NotBlank @Size(max = 20) String decision) {}
