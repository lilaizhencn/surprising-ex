package com.surprising.gateway.provider.auth.model;

import com.surprising.gateway.provider.auth.client.KycProvider;

public record KycSubmissionResponse(KycProfile profile, KycProvider.KycProviderSession verificationSession) {}
