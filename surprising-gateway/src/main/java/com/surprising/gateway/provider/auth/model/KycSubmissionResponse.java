package com.surprising.gateway.provider.auth.model;

public record KycSubmissionResponse(KycProfile profile, KycProviderSession verificationSession) {}
