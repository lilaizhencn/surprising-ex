package com.surprising.gateway.provider.auth.client;

import com.surprising.gateway.provider.auth.model.KycDocument;
import com.surprising.gateway.provider.auth.model.KycProfile;
import com.surprising.gateway.provider.auth.model.KycProviderResult;
import com.surprising.gateway.provider.auth.model.KycProviderSession;
import com.surprising.gateway.provider.auth.model.KycSubmissionRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Replaceable boundary for future hosted KYC SDKs and signed provider callbacks. */
public interface KycProvider {
    String code();

    KycProviderSession start(long userId, KycSubmissionRequest request, List<KycDocument> documents);

    default KycProviderSession refreshSession(KycProfile profile, String redirectUrl) {
        throw new IllegalStateException("provider session refresh is not supported");
    }

    default Optional<KycProviderResult> verifyCallback(Map<String, String> headers, byte[] body) {
        return Optional.empty();
    }
}
