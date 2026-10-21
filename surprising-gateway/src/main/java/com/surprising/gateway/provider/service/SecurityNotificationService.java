package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.auth.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.EmailMessageSender;
import com.surprising.gateway.provider.auth.MfaRecoveryRepository.RecoveryRequest;
import com.surprising.gateway.provider.repository.SecurityEmailNotificationRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 持久意图是未投递安全通知的唯一来源；不在认证、KYC 或裁决事务中发送结果邮件。 */
@Service
public class SecurityNotificationService {
    private static final Logger log = LoggerFactory.getLogger(SecurityNotificationService.class);
    private final SecurityEmailNotificationRepository notifications;
    private final EmailMessageSender email;

    public SecurityNotificationService(SecurityEmailNotificationRepository notifications, EmailMessageSender email) {
        this.notifications = notifications;
        this.email = email;
    }

    public void kycResult(KycProfile profile, String recipient) {
        String body = "Your identity verification has been " + profile.status().toLowerCase(Locale.ROOT)
                + (profile.rejectionReason() == null ? "." : ": " + profile.rejectionReason());
        notifications.enqueue("KYC:" + profile.userId() + ':' + profile.updatedAt() + ':' + profile.status(),
                recipient, "Surprising identity verification update", body, profile.updatedAt());
    }

    public void mfaRecoveryDecision(RecoveryRequest request, String recipient) {
        String body = "APPROVED".equals(request.status())
                ? "Your identity verification passed. The previous authenticator was removed. "
                        + "Withdrawals will be available after the 24-hour security hold."
                : "Your authenticator recovery request was not approved. "
                        + (request.decisionReason() == null ? "" : "Reason: " + request.decisionReason());
        notifications.enqueue("MFA_RECOVERY:" + request.requestId(), recipient,
                "Surprising authenticator recovery update", body, request.reviewedAt());
    }

    /** claim 是独立 SQL 提交；外部邮件调用不持有业务事务或数据库连接。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deliverDue() {
        for (int delivered = 0; delivered < 16; delivered++) {
            Instant now = Instant.now();
            var claimed = notifications.claim(now);
            if (claimed.isEmpty()) break;
            var delivery = claimed.getFirst();
            try {
                email.send(delivery.recipient(), delivery.subject(), delivery.body());
                notifications.complete(delivery, Instant.now());
            } catch (RuntimeException failure) {
                long delay = Math.min(3600, 5L << Math.min(delivery.attempts() - 1, 10));
                notifications.retry(delivery, now.plusSeconds(delay), failure.getClass().getSimpleName());
                log.warn("security notification delivery failed notificationId={} failureType={}",
                        delivery.notificationId(), failure.getClass().getSimpleName());
            }
        }
        notifications.deleteDeliveredBefore(Instant.now().minus(30, ChronoUnit.DAYS));
    }
}
