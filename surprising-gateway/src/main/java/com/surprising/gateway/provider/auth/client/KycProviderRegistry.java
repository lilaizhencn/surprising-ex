package com.surprising.gateway.provider.auth.client;

import com.surprising.gateway.provider.auth.model.ComplianceModels.KycDocument;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycSubmissionRequest;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycVerificationOption;
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
        if (!verificationOptions().contains(new KycVerificationOption(request.applicantType(), request.kycLevel()))) {
            throw new IllegalArgumentException("the requested KYC applicant type and level are not supported by the configured provider");
        }
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
    public java.time.Duration verificationValidity() { return properties.getKyc().getValidity(); }

    public List<KycVerificationOption> verificationOptions() {
        return java.util.stream.Stream.of("INDIVIDUAL", "BUSINESS")
                .flatMap(applicant -> java.util.stream.Stream.of("BASIC", "STANDARD", "ENHANCED")
                        .map(level -> new KycVerificationOption(applicant, level)))
                .filter(option -> switch (selectedProvider()) {
                    case "SELF" -> true;
                    case "SUMSUB" -> {
                        String level = properties.getKyc().getSumsub().getLevelNames().get(option.applicantType() + "_" + option.kycLevel());
                        yield level != null && !level.isBlank();
                    }
                    case "VERIFF" -> "INDIVIDUAL".equals(option.applicantType())
                            && properties.getKyc().getVeriff().getKycLevel().equals(option.kycLevel());
                    default -> false;
                }).toList();
    }

    public KycProvider.KycProviderSession refreshSession(KycProfile profile, String redirectUrl) {
        if (simulationEnabled()) throw new IllegalStateException("KYC simulation does not issue provider tokens");
        KycProvider provider = providers.get(normalize(profile.provider()));
        if (provider == null) throw new IllegalArgumentException("KYC provider is not configured: " + profile.provider());
        return provider.refreshSession(profile, redirectUrl);
    }

    public void requireCallbackLevel(KycProfile profile, KycProvider.KycProviderResult result) {
        if ("SUMSUB".equals(profile.provider()) && !simulationEnabled()
                && !properties.getKyc().getSumsub().levelName(profile.applicantType(), profile.kycLevel()).equals(result.levelName())) {
            throw new IllegalArgumentException("KYC callback does not match the requested verification level");
        }
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
