package com.surprising.gateway.provider.auth.client;

import com.surprising.gateway.provider.auth.model.ComplianceModels.KycDocument;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycSubmissionRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Map;

/** Replaceable boundary for future hosted KYC SDKs and signed provider callbacks. */
public interface KycProvider {
    public String code();

    public KycProviderSession start(long userId, KycSubmissionRequest request, List<KycDocument> documents);

    public default KycProviderSession refreshSession(long userId, String providerReference) {
        throw new IllegalStateException("provider session refresh is not supported");
    }

    public default Optional<KycProviderResult> verifyCallback(Map<String, String> headers, byte[] body) {
        return Optional.empty();
    }

    public record KycProviderSession(String provider, String providerReference, String launchToken,
                              String redirectUrl, String status, boolean simulated) {}
    public record KycProviderResult(String providerReference, String externalUserId, String eventKey,
                             String status, String rejectionReason) {}
}
