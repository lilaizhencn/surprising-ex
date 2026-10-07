package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.ComplianceModels.KycDocument;
import com.surprising.gateway.provider.auth.ComplianceModels.KycSubmissionRequest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class KycProviderRegistry {
    private final Map<String, KycProvider> providers;
    private final GatewayProperties properties;

    public KycProviderRegistry(List<KycProvider> providers, GatewayProperties properties) {
        this.properties = properties;
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
                provider -> normalize(provider.code()), Function.identity()));
    }

    public KycProvider.KycProviderSession start(long userId, KycSubmissionRequest request,
                                                 List<KycDocument> documents) {
        KycProvider provider = providers.get(normalize(properties.getKyc().getProvider()));
        if (provider == null) {
            throw new IllegalArgumentException("KYC provider is not configured: " + properties.getKyc().getProvider());
        }
        return provider.start(userId, request, documents);
    }

    public Optional<KycProvider.KycProviderResult> verifyCallback(String providerCode, Map<String, String> headers, byte[] body) {
        KycProvider provider = providers.get(normalize(providerCode));
        if (provider == null) throw new IllegalArgumentException("KYC provider is not configured: " + providerCode);
        return provider.verifyCallback(headers, body);
    }

    public String selectedProvider() { return normalize(properties.getKyc().getProvider()); }
    public boolean simulationEnabled() { return properties.getKyc().isSimulationEnabled(); }

    public KycProvider.KycProviderSession refreshSession(long userId, String providerReference) {
        return refreshSession(selectedProvider(), userId, providerReference);
    }

    public KycProvider.KycProviderSession refreshSession(String providerCode, long userId, String providerReference) {
        if (simulationEnabled()) throw new IllegalStateException("KYC simulation does not issue provider tokens");
        KycProvider provider = providers.get(normalize(providerCode));
        if (provider == null) throw new IllegalArgumentException("KYC provider is not configured: " + providerCode);
        return provider.refreshSession(userId, providerReference);
    }

    public KycProvider.KycProviderSession simulationSession(long userId) {
        if (!simulationEnabled()) throw new IllegalStateException("KYC simulation is disabled");
        return new KycProvider.KycProviderSession(selectedProvider(), "SIM-" + selectedProvider() + "-" + userId,
                null, null, "PENDING", true);
    }

    private static String normalize(String code) {
        return code == null ? "SELF" : code.trim().toUpperCase(Locale.ROOT);
    }
}
