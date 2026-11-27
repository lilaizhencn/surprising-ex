package com.surprising.gateway.provider.auth.model;

import java.util.List;

public record KycProviderInfo(String provider, boolean simulationEnabled,
                              List<KycVerificationOption> verificationOptions) {}
