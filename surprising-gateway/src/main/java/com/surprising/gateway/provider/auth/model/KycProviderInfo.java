package com.surprising.gateway.provider.auth.model;

import com.surprising.gateway.provider.auth.client.KycProvider;
import java.util.List;

public record KycProviderInfo(String provider, boolean simulationEnabled,
                              List<KycVerificationOption> verificationOptions) {}
