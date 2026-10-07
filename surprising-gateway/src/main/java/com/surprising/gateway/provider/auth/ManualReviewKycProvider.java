package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.ComplianceModels.KycDocument;
import com.surprising.gateway.provider.auth.ComplianceModels.KycSubmissionRequest;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ManualReviewKycProvider implements KycProvider {
    @Override public String code() { return "SELF"; }

    @Override
    public KycProviderSession start(long userId, KycSubmissionRequest request, List<KycDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("manual KYC review requires uploaded documents");
        }
        return new KycProviderSession(code(), null, null, null, "PENDING", false);
    }
}
